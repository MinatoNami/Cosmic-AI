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
                // Walk up and swing, rather than swing at something across the map. Walking
                // is a step a decision now, so a target further than that is walked towards
                // and hit on a later decision. Sending the swing anyway had an agent hitting a
                // monster 2,500px off, which the server flags as a distance hack.
                moveTo(attack.position(), world);
                if (within(attack.position(), ATTACK_REACH, world)) {
                    session.send(ClientPackets.meleeAttack(attack.objectId(), attack.position(),
                            claimedDamage(world), attack.position().x >= world.selfPosition().x));
                }
            }
            case Intent.PickUp pickUp -> {
                moveTo(pickUp.position(), world);
                if (within(pickUp.position(), HAND_REACH, world)) {
                    session.send(ClientPackets.pickUpItem(pickUp.objectId(), pickUp.position()));
                }
            }
            case Intent.Say say -> session.send(ClientPackets.chat(say.message(), false));
            case Intent.EnterPortal portal -> {
                moveTo(portal.position(), world);
                if (within(portal.position(), HAND_REACH, world)) {
                    session.send(ClientPackets.enterPortal(portal.portalName()));
                }
            }
            case Intent.TalkTo talk -> {
                if (canSpeakTo(world.mapId(), world.selfPosition(), talk.position(), SPEAKING_DISTANCE)) {
                    session.send(ClientPackets.talkToNpc(talk.objectId()));
                    return;
                }
                moveTo(talk.position(), world);
                if (within(talk.position(), SPEAKING_DISTANCE, world)) {
                    session.send(ClientPackets.talkToNpc(talk.objectId()));
                }
            }
            case Intent.StartQuest quest -> {
                // The server refuses this unless we are standing near the NPC.
                if (!canSpeakTo(world.mapId(), world.selfPosition(), quest.position(), SPEAKING_DISTANCE)) {
                    moveTo(quest.position(), world);
                }
                if (canSpeakTo(world.mapId(), world.selfPosition(), quest.position(), SPEAKING_DISTANCE)) {
                    session.send(ClientPackets.questAction(QUEST_START_SCRIPTED,
                            quest.questId(), quest.npcId()));
                    session.send(ClientPackets.questAction(QUEST_START_PLAIN,
                            quest.questId(), quest.npcId()));
                }
            }
            case Intent.CompleteQuest quest -> {
                if (!canSpeakTo(world.mapId(), world.selfPosition(), quest.position(), SPEAKING_DISTANCE)) {
                    moveTo(quest.position(), world);
                }
                if (canSpeakTo(world.mapId(), world.selfPosition(), quest.position(), SPEAKING_DISTANCE)) {
                    session.send(ClientPackets.questAction(QUEST_END_SCRIPTED,
                            quest.questId(), quest.npcId()));
                    session.send(ClientPackets.questAction(QUEST_END_PLAIN,
                            quest.questId(), quest.npcId()));
                }
            }
            case Intent.Wait ignored -> {
            }
        }
    }

    /**
     * How close counts as reaching something, now that getting there takes more than one
     * decision. Generous against the policy's own ranges, which choose these actions only when
     * close; tight against the server's, which lets a melee swing land from about 447px.
     */
    static final int ATTACK_REACH = 120;
    static final int HAND_REACH = 60;
    static final int SPEAKING_DISTANCE = 150;

    /**
     * Whether somebody can be spoken to from here.
     *
     * Close enough is close enough. But a player speaks to an NPC by clicking on them, and
     * the server checks no distance for a conversation and only a generous one for a quest,
     * so somebody standing where no floor leads - Heena, on a ledge above Mushroom Town with
     * no way up - is spoken to from below, from anywhere on the same screen. Insisting on
     * walking up to her left every agent pacing underneath and never once hearing her.
     */
    public static boolean canSpeakTo(int map, Point self, Point npc, int reach) {
        if (self.distance(npc) <= reach) {
            return true;
        }
        if (Math.abs(npc.x - self.x) > ON_SCREEN_X || Math.abs(npc.y - self.y) > ON_SCREEN_Y) {
            return false;
        }
        return !MapGeometry.groundIn(map).isEmpty() && Navigator.nextStep(map, self, npc).isEmpty();
    }

    /** Half the client's 800x600 window: how far off something can be and still be clicked. */
    static final int ON_SCREEN_X = 400;
    static final int ON_SCREEN_Y = 300;

    private static boolean within(Point target, int reach, WorldModel world) {
        return world.selfPosition().distance(target) <= reach;
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
            case HOP -> {
                // A portal to elsewhere in this map: the client moves itself, and says so.
                session.send(ClientPackets.move(from, step.landing(), foothold(map, step.landing()),
                        STANCE_STAND_RIGHT, (short) 100));
                world.movedTo(step.landing());
            }
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

    /**
     * What one swing does, worked out as the client works it out: the weapon's multiplier on
     * the stat its kind lives on, plus the backing stat, times weapon attack - and somewhere
     * between half that and all of it, as a real swing lands.
     *
     * It claimed one point, every swing, for fear of the server's damage check. A level-34
     * agent with a mace hit monsters with hundreds of health for one, so it never killed
     * anything; watching it, you saw it walk up to monsters and nothing happen. The server
     * checks claims against this same calculation and only warns at half as much again, so a
     * claim inside it is simply a hit.
     */
    int claimedDamage(WorldModel world) {
        Optional<agents.percept.Item> weapon = world.inventory().wornAt(WEAPON_SLOT);
        if (weapon.isEmpty()) {
            return CLAIMED_DAMAGE;              // bare hands: the server allows one
        }
        int kind = (weapon.get().itemId() / 10000) % 100;
        boolean thief = (world.job() % 1000) / 100 == 4;
        double multiplier;
        String main = "STR";
        String backing = "DEX";
        switch (kind) {
            case 30 -> multiplier = 4.0;                        // one-handed sword
            case 31, 32 -> multiplier = 4.4;                    // one-handed axe, mace
            case 33 -> {                                        // dagger
                multiplier = thief ? 3.6 : 4.0;
                if (thief) {
                    main = "LUK";
                    backing = "DEX+STR";
                }
            }
            case 37, 38 -> multiplier = 3.6;                    // wand, staff, swung
            case 40 -> multiplier = 4.6;                        // two-handed sword
            case 41, 42 -> multiplier = 4.8;                    // two-handed axe, mace
            case 43, 44 -> multiplier = 5.0;                    // spear, polearm
            case 45 -> { multiplier = 3.4; main = "DEX"; backing = "STR"; }    // bow
            case 46, 49 -> { multiplier = 3.6; main = "DEX"; backing = "STR"; } // crossbow, gun
            case 47 -> { multiplier = 3.6; main = "LUK"; backing = "DEX+STR"; } // claw
            case 48 -> multiplier = 4.8;                        // knuckle
            default -> {
                return CLAIMED_DAMAGE;
            }
        }
        int watk = 0;
        for (agents.percept.Item worn : world.inventory().worn()) {
            if (worn.stats() != null) {
                watk += worn.stats().watk();
            }
        }
        int mainStat = Math.max(0, world.stat(main));
        int backingStat = 0;
        for (String stat : backing.split("\\+")) {
            backingStat += Math.max(0, world.stat(stat));
        }
        int max = (int) Math.ceil((multiplier * mainStat + backingStat) / 100.0 * watk);
        if (max <= 1) {
            return CLAIMED_DAMAGE;
        }
        return max / 2 + swing.nextInt(max - max / 2 + 1);
    }

    private static final short WEAPON_SLOT = -11;
    private final java.util.Random swing = new java.util.Random();

    /**
     * Thrown back by a monster's touch, as a hit character is: a short hop away from it along
     * the floor. Without it a touched agent walked straight on through the monster, and to
     * anyone watching it looked as though nothing had happened.
     */
    public void knockedBack(Point monster, WorldModel world) {
        Point from = world.selfPosition();
        int map = world.mapId();
        int away = from.x >= monster.x ? 1 : -1;
        int west = Navigator.floorEnd(map, from, -1).orElse(from.x);
        int east = Navigator.floorEnd(map, from, 1).orElse(from.x);
        int x = Math.max(west, Math.min(east, from.x + away * KNOCKBACK_PIXELS));
        Point landing = new Point(x, Navigator.heightAlongFloor(map, from, x).orElse(from.y));
        Point apex = new Point((from.x + x) / 2, Math.min(from.y, landing.y) - 20);
        byte thrown = away > 0 ? STANCE_JUMP_RIGHT : STANCE_JUMP_LEFT;
        session.send(ClientPackets.move(from, List.of(
                new ClientPackets.Fragment(apex, (short) 0, thrown, (short) 150),
                new ClientPackets.Fragment(landing, foothold(map, landing),
                        away > 0 ? STANCE_STAND_LEFT : STANCE_STAND_RIGHT, (short) 150))));
        world.movedTo(landing);
    }

    private static final int KNOCKBACK_PIXELS = 60;

    private static short foothold(int map, Point at) {
        return (short) MapGeometry.footholdUnder(map, at.x, at.y);
    }

}
