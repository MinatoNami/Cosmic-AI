package agents.mind;

import agents.net.MapleSession;
import agents.protocol.ClientPackets;
import agents.world.MapGeometry;
import agents.world.WorldModel;

import java.awt.Point;
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

    /** A height difference worth looking for a rope over, rather than shrugging at. */
    private static final int CLIMB_MATTERS = 40;

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

    private final MapleSession session;

    public IntentExecutor(MapleSession session) {
        this.session = session;
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

    /**
     * Walks one step towards somewhere, rather than arriving instantly.
     *
     * Anything further than a step away takes several decisions to reach, which is the point:
     * the agent is walking there, and anyone watching sees it walk. The world model is told
     * where the step actually ended rather than where the agent was aiming, so it never
     * believes itself somewhere it has not got to yet.
     */
    private void moveTo(Point destination, WorldModel world) {
        Point from = world.selfPosition();

        // Already there. Attacking walks to the target first, and a target within reach is
        // one you are standing on, so this fired every tick of every fight: a move packet
        // going nowhere, one millisecond long. It draws nothing, but it is a packet a second
        // per agent and it buries the real movement in anything watching the map.
        if (from.distance(destination) < ARRIVED_PIXELS) {
            return;
        }

        // Height before distance, because no amount of walking closes it - and for most of
        // this project's life an agent simply gave up on anything above its own foothold.
        // That single gap wore a dozen different faces: NPCs never spoken to, loot left on
        // ledges, journeys abandoned for making no progress, agents frozen against a wall.
        // Shanks, who sells the only passage off Maple Island, stands six hundred pixels
        // above the floor an agent lands on.
        if (Math.abs(destination.y - from.y) > CLIMB_MATTERS) {
            Optional<MapGeometry.Climb> rope =
                    MapGeometry.climbTowards(world.mapId(), from.x, from.y, destination.y);
            if (rope.isPresent() && ride(rope.get(), destination, world, from)) {
                return;
            }
        }
        walk(destination, world, from);
    }

    /**
     * Gets on a rope and rides it, or walks to its foot first.
     *
     * Stops at whichever comes first: the end of the rope, or being level with the target.
     * Riding to the top and walking back is what a planner does; a character gets off when
     * it is where it meant to be.
     *
     * @return false when there is nothing left to gain here, so the caller should walk
     */
    private boolean ride(MapGeometry.Climb rope, Point destination, WorldModel world, Point from) {
        if (Math.abs(from.x - rope.x()) > ARRIVED_PIXELS) {
            walk(new Point(rope.x(), from.y), world, from);      // to the foot of it
            return true;
        }

        int endOfTheRope = rope.endAwayFrom(from.y);
        int stopAt = stopAt(endOfTheRope, destination.y, from.y);
        int toClimb = stopAt - from.y;
        if (Math.abs(toClimb) < ARRIVED_PIXELS) {
            return false;
        }

        int step = (int) Math.copySign(Math.min(CLIMB_PIXELS, Math.abs(toClimb)), toClimb);
        Point next = new Point(rope.x(), from.y + step);
        short duration = (short) Math.max(1,
                Math.round(Math.abs(step) / (double) CLIMB_PIXELS_PER_SECOND * 1000));
        session.send(ClientPackets.move(from, next, (short) 0, STANCE_CLIMBING, duration));
        world.movedTo(next);
        return true;
    }

    /** Where to get off: the rope's end, or the height wanted, whichever comes first. */
    static int stopAt(int endOfTheRope, int wanted, int from) {
        boolean goingUp = wanted < from;
        return goingUp ? Math.max(endOfTheRope, wanted) : Math.min(endOfTheRope, wanted);
    }

    private void walk(Point destination, WorldModel world, Point from) {
        // Walking is horizontal. The floor decides the height, the target decides only which
        // way to set off - which is both what a character does and the way out of a trap the
        // previous version fell into: interpolating towards something above or below moved
        // mostly in y, the snap to the floor pulled that straight back, and the step landed
        // where it started. An observer watching one of these saw a move packet to an
        // identical point, one millisecond long, several times a second, forever.
        int dx = destination.x - from.x;
        if (Math.abs(dx) < ARRIVED_PIXELS) {
            // Directly above or below, and no rope was on offer. Saying so once is better
            // than saying nothing several times a second.
            return;
        }

        Point step;
        if (Math.abs(dx) <= STEP_PIXELS) {
            // The last step lands exactly where it was aimed, height included, or an agent
            // could never arrive anywhere that is not at floor level and the arrival checks -
            // which measure both axes - would never come true.
            step = destination;
        } else {
            int towards = from.x + (int) Math.copySign(STEP_PIXELS, dx);
            step = new Point(towards, MapGeometry.groundUnder(world.mapId(), towards, from.y));
        }

        byte stance = step.x >= from.x ? STANCE_WALKING_RIGHT : STANCE_WALKING_LEFT;
        short duration = (short) Math.max(1,
                Math.round(from.distance(step) / WALK_PIXELS_PER_SECOND * 1000));

        session.send(ClientPackets.move(from, step, (short) 0, stance, duration));
        world.movedTo(step);
    }
}
