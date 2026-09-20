package agents.mind;

import agents.net.MapleSession;
import agents.protocol.ClientPackets;
import agents.world.MapGeometry;
import agents.world.WorldModel;

import java.awt.Point;

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
        double distance = from.distance(destination);

        Point step = destination;
        if (distance > STEP_PIXELS) {
            double fraction = STEP_PIXELS / distance;
            step = new Point(
                    (int) Math.round(from.x + (destination.x - from.x) * fraction),
                    (int) Math.round(from.y + (destination.y - from.y) * fraction));
        }

        // Put the step on the floor. Interpolating straight from here to there walks through
        // whatever happens to be in between, which is how an agent ends up strolling through
        // a platform: nothing was stopping it, because nothing knew the platform was there.
        int ground = MapGeometry.groundUnder(world.mapId(), step.x, from.y);
        step = new Point(step.x, ground);

        byte stance = step.x >= from.x ? STANCE_WALKING_RIGHT : STANCE_WALKING_LEFT;
        short duration = (short) Math.max(1,
                Math.round(from.distance(step) / WALK_PIXELS_PER_SECOND * 1000));

        session.send(ClientPackets.move(from, step, (short) 0, stance, duration));
        world.movedTo(step);
    }
}
