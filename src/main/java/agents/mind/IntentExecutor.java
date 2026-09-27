package agents.mind;

import agents.net.MapleSession;
import agents.protocol.ClientPackets;
import net.packet.Packet;
import agents.world.MapGeometry;
import agents.world.Navigator;
import agents.world.WorldModel;

import java.awt.Point;
import java.util.List;
import java.util.Optional;

/**
 * Turns an intent into packets.
 *
 * The only place in the agent that knows the protocol. A policy says "attack that"; what a
 * melee swing looks like on the wire stays here.
 */
public class IntentExecutor {
    /**
     * How fast a character walks, in pixels per second. Roughly what the game gives a
     * beginner at base speed.
     */
    private static final double WALK_PIXELS_PER_SECOND = 125;

    /**
     * How far one step may carry the agent.
     *
     * The agent decides every 600ms, so this is about what a walking character covers between
     * decisions. Without a cap, a move packet described the whole journey as a single
     * fragment - five hundred pixels in three hundred milliseconds, which is not walking, it
     * is teleporting, and that is what onlookers saw. The server takes the destination either
     * way and never complains.
     */
    private static final double STEP_PIXELS = 75;

    /** Close enough to be standing on it. */
    private static final double ARRIVED_PIXELS = 4;

    /** Ladder-mid, per docs/moveactions.txt, so watchers see a climb rather than a glide. */
    private static final byte STANCE_CLIMBING = 16;

    private static final int CLIMB_PIXELS = 40;
    private static final int CLIMB_PIXELS_PER_SECOND = 90;
    /**
     * Walking, per docs/moveactions.txt. These were 4 and 5, which that same file lists as
     * <em>standing</em> right and left - so every agent broadcast "I am standing still" on
     * every step it took, and onlookers saw characters slide around the map in a standing
     * pose without ever appearing to walk. The server does not care, because it reads the
     * destination and ignores the pose; only the other clients do.
     */
    private static final byte STANCE_WALKING_RIGHT = 0;
    private static final byte STANCE_WALKING_LEFT = 1;

    /**
     * How much damage to claim. The server checks claims against what the character could
     * plausibly do and bans for overreach, so an agent claims the least it can: one point.
     * Killing things slowly is a fair price for never tripping the autoban.
     */
    private static final int CLAIMED_DAMAGE = 1;

    /**
     * Quests come in two flavours - script-driven and plain - and which one a quest is is
     * not something an agent can tell from outside. Both are sent; the server ignores the
     * one that does not apply, which is cheaper than knowing.
     */
    private static final int QUEST_START_SCRIPTED = 4;
    private static final int QUEST_START_PLAIN = 1;
    private static final int QUEST_END_SCRIPTED = 5;
    private static final int QUEST_END_PLAIN = 2;

    /** Where packets go: the session in a run, a list in a test. */
    private final Sender session;

    interface Sender {
        void send(Packet packet);
    }

    public IntentExecutor(MapleSession session) {
        this(session::send);
    }

    IntentExecutor(Sender sender) {
        this.session = sender;
    }

    public void execute(Intent intent, WorldModel world) {
        switch (intent) {
            case Intent.MoveTo move -> moveTo(move.destination(), world);
            case Intent.Attack attack -> {
                // Step onto the target first: the server takes our word for where we are, but
                // a human watching should see the agent walk up and swing, not swing at
                // something across the map.
                moveTo(attack.position(), world);
                session.send(ClientPackets.meleeAttack(attack.objectId(), attack.position(),
                        CLAIMED_DAMAGE, attack.position().x >= world.selfPosition().x));
            }
            case Intent.PickUp pickUp -> {
                moveTo(pickUp.position(), world);
                session.send(ClientPackets.pickUpItem(pickUp.objectId(), pickUp.position()));
            }
            case Intent.Say say -> session.send(ClientPackets.chat(say.message(), false));
            case Intent.EnterPortal portal -> {
                moveTo(portal.position(), world);
                session.send(ClientPackets.enterPortal(portal.portalName()));
            }
            case Intent.TalkTo talk -> {
                moveTo(talk.position(), world);
                session.send(ClientPackets.talkToNpc(talk.objectId()));
            }
            case Intent.StartQuest quest -> {
                // The server refuses this unless we are standing near the NPC.
                moveTo(quest.position(), world);
                session.send(ClientPackets.questAction(QUEST_START_SCRIPTED,
                        quest.questId(), quest.npcId()));
                session.send(ClientPackets.questAction(QUEST_START_PLAIN,
                        quest.questId(), quest.npcId()));
            }
            case Intent.CompleteQuest quest -> {
                moveTo(quest.position(), world);
                session.send(ClientPackets.questAction(QUEST_END_SCRIPTED,
                        quest.questId(), quest.npcId()));
                session.send(ClientPackets.questAction(QUEST_END_PLAIN,
                        quest.questId(), quest.npcId()));
            }
            case Intent.Wait ignored -> {
            }
        }
    }

    /** Jumping, per docs/moveactions.txt: what a watcher sees in the air, rising or falling. */
    private static final byte STANCE_JUMP_RIGHT = 6;
    private static final byte STANCE_JUMP_LEFT = 7;
    private static final byte STANCE_STAND_RIGHT = 4;
    private static final byte STANCE_STAND_LEFT = 5;

    /** Pixels per second squared, and a beginner's take-off speed, for timing leaps. */
    private static final double GRAVITY = 2000;
    private static final double JUMP_SPEED = 555;

    /**
     * The rope being ridden, if any, and where on it the ride ends.
     *
     * Held across decisions because a climb takes several, and mid-rope there is no floor to
     * plan from - planning from the floor below would send the agent back down it.
     */
    private MapGeometry.Climb riding;
    private int ridingMap;
    private int ridingTo;

    /**
     * Makes one decision's worth of progress towards somewhere, the way a character could.
     *
     * The route comes from {@link Navigator}: along the floor it is standing on, off an edge,
     * up a jump, up or down a rope. Nothing here ever moves the agent somewhere a character
     * could not stand. It used to: walking kept the height it started at when a step had no
     * floor under it, so agents walked off platforms and across the map through the air, and
     * the last step to a target took the target's height, so they popped up onto ledges.
     */
    private void moveTo(Point destination, WorldModel world) {
        Point from = world.selfPosition();

        // Already there. Attacking walks to the target first, and a target within reach is
        // one you are standing on, so this fired every tick of every fight: a move packet
        // going nowhere, one millisecond long.
        if (from.distance(destination) < ARRIVED_PIXELS) {
            return;
        }
        int map = world.mapId();

        if (riding != null && ridingMap == map) {
            if (climb(world, from)) {
                return;
            }
        }
        riding = null;

        if (MapGeometry.groundIn(map).isEmpty()) {
            return;         // no floor data at all: better to stand still than to guess
        }
        if (Navigator.floorHeight(map, from).isEmpty()) {
            // Not standing on anything - left in the air by an older version of this, or
            // put down just off the floor by a map change. Come down onto the floor first.
            Navigator.settleFrom(map, from).ifPresent(floor -> leap(from, floor, world, false));
            return;
        }

        Optional<Navigator.Step> route = Navigator.nextStep(map, from, destination);
        if (route.isEmpty()) {
            // No way there that this knows of. Get as near as this floor allows and let the
            // policy notice it is not arriving - which it does, and gives up - rather than
            // walking off the edge towards it.
            walkAlongFloor(destination.x, world, from);
            return;
        }
        Navigator.Step step = route.get();
        if (step.kind() == Navigator.Kind.WALK || Math.abs(from.x - step.departX()) > ARRIVED_PIXELS) {
            walkAlongFloor(step.departX(), world, from);
            return;
        }
        switch (step.kind()) {
            case JUMP -> leap(from, step.landing(), world, true);
            case DROP -> leap(from, step.landing(), world, false);
            case CLIMB -> {
                riding = step.rope();
                ridingMap = map;
                ridingTo = step.rope().endAwayFrom(step.rope().endNearest(from.y));
                climb(world, from);
            }
            default -> walkAlongFloor(step.departX(), world, from);
        }
    }

    /**
     * One step along the floor the agent is standing on, never past either end of it.
     *
     * Height comes from the floor at the new x, so a slope is walked up and down rather than
     * cut through. A step aimed past the end of the platform stops at the end.
     */
    private void walkAlongFloor(int targetX, WorldModel world, Point from) {
        int map = world.mapId();
        int west = Navigator.floorEnd(map, from, -1).orElse(from.x);
        int east = Navigator.floorEnd(map, from, 1).orElse(from.x);
        int wanted = Math.max(west, Math.min(east, targetX));
        int dx = wanted - from.x;
        if (Math.abs(dx) < ARRIVED_PIXELS) {
            return;         // as far as this floor goes; saying so once beats a stream of no-ops
        }
        int x = Math.abs(dx) <= STEP_PIXELS ? wanted : from.x + (int) Math.copySign(STEP_PIXELS, dx);
        // The height of this same floor at the new x. Asking "what floor is under x at my
        // current height" instead lost the floor on any slope steeper than the tolerance -
        // one climbs 32px in a 75px step - and dropped the agent onto the floor beneath.
        int y = Navigator.heightAlongFloor(map, from, x).orElse(from.y);
        Point step = new Point(x, y);

        byte stance = dx > 0 ? STANCE_WALKING_RIGHT : STANCE_WALKING_LEFT;
        short duration = (short) Math.max(1, Math.round(from.distance(step) / WALK_PIXELS_PER_SECOND * 1000));
        session.send(ClientPackets.move(from, step, foothold(map, step), stance, duration));
        world.movedTo(step);
    }

    /**
     * A jump or a fall, drawn as one: up to the top of the arc, then down to where it lands.
     *
     * Timed from the same gravity the client uses, so it takes as long in the air as a real
     * character would, and each leg names the foothold under it - none mid-air, the landing
     * floor at the end.
     */
    private void leap(Point from, Point landing, WorldModel world, boolean jumping) {
        int map = world.mapId();
        boolean right = landing.x >= from.x;
        byte inTheAir = right ? STANCE_JUMP_RIGHT : STANCE_JUMP_LEFT;
        byte landed = right ? STANCE_STAND_RIGHT : STANCE_STAND_LEFT;

        double rise = jumping ? JUMP_SPEED * JUMP_SPEED / (2 * GRAVITY) : 0;
        int apexY = (int) Math.round(Math.min(from.y, landing.y) - (jumping ? Math.max(8, rise - Math.abs(from.y - landing.y)) : 0));
        apexY = Math.min(apexY, from.y);
        double up = jumping ? JUMP_SPEED / GRAVITY : 0.05;
        double down = Math.sqrt(2 * Math.max(1, landing.y - apexY) / GRAVITY);
        int total = (int) Math.round((up + down) * 1000);
        Point apex = new Point(from.x + (int) Math.round((landing.x - from.x) * (up / (up + down))), apexY);

        session.send(ClientPackets.move(from, List.of(
                new ClientPackets.Fragment(apex, (short) 0, inTheAir, (short) Math.max(1, Math.round(up * 1000))),
                new ClientPackets.Fragment(landing, foothold(map, landing), landed,
                        (short) Math.max(1, total - Math.round(up * 1000))))));
        world.movedTo(landing);
    }

    /**
     * One rung of the rope being ridden, or stepping off it at the end.
     *
     * @return false when there is no rope to ride any more, so the caller should plan afresh
     */
    private boolean climb(WorldModel world, Point from) {
        MapGeometry.Climb rope = riding;
        int map = world.mapId();
        if (Math.abs(from.x - rope.x()) > ARRIVED_PIXELS) {
            walkAlongFloor(rope.x(), world, from);      // to the foot of it first
            return true;
        }
        int toClimb = ridingTo - from.y;
        if (Math.abs(toClimb) < ARRIVED_PIXELS) {
            // The end of the rope. Step off onto the floor there, and plan from it.
            int floor = Navigator.floorNear(map, rope.x(), ridingTo).orElse(ridingTo);
            Point off = new Point(rope.x(), floor);
            session.send(ClientPackets.move(from, off, foothold(map, off), STANCE_STAND_RIGHT, (short) 150));
            world.movedTo(off);
            riding = null;
            return true;
        }
        int step = (int) Math.copySign(Math.min(CLIMB_PIXELS, Math.abs(toClimb)), toClimb);
        Point next = new Point(rope.x(), from.y + step);
        short duration = (short) Math.max(1, Math.round(Math.abs(step) / (double) CLIMB_PIXELS_PER_SECOND * 1000));
        session.send(ClientPackets.move(from, next, (short) 0, STANCE_CLIMBING, duration));
        world.movedTo(next);
        return true;
    }

    private static short foothold(int map, Point at) {
        return (short) MapGeometry.footholdUnder(map, at.x, at.y);
    }

}
