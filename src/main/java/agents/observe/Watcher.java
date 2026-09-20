package agents.observe;

import agents.net.PacketInbox;
import net.opcodes.SendOpcode;
import net.packet.InPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * Sees what everyone else in the map sees.
 *
 * Half of what an agent sends is never read by the server. A movement packet is mined for its
 * destination and an attack packet for its damage; the walking pose, the path taken and the
 * swing animation are passed straight through to every other client in the map and looked at
 * by nobody else. Which means getting them wrong cannot fail, cannot be logged, and cannot be
 * caught by any test that talks only to the server - and four separate bugs lived in exactly
 * that blind spot, each one found by a person looking at the game and saying the characters
 * were standing still.
 *
 * <p>This closes the blind spot by being that person. It is an ordinary client that logs in,
 * stands there, and decodes what the server broadcasts <em>about somebody else</em>. What it
 * reports is by definition what a player would have seen.
 *
 * <p>Deliberately not built on {@link agents.percept.ObservationDecoder}. That decoder is
 * lossy on purpose - it drops the animation fields, because an agent has no business
 * perceiving the pose another character is drawn in. Those fields are the entire point here,
 * so this reads the broadcasts itself.
 */
public class Watcher {

    /** A character swinging at something, as the map was told about it. */
    public record Swing(int characterId, int display, int direction, int stance, int speed,
                        int targets) {

        /**
         * Whether another client has enough here to draw anything.
         *
         * Stance is the action to play and speed is how fast to play it; zero is not a
         * value either can meaningfully take, and zero in both was what the agents sent for
         * the entire life of the project before anyone looked.
         */
        public boolean wouldAnimate() {
            return stance != 0 && speed != 0;
        }
    }

    /** A character moving, as the map was told about it. */
    public record Step(int characterId, int x, int y, int stance, int durationMillis) {

        /**
         * Stances 0-3 walk, 4 and 5 stand. A character that is changing position while
         * broadcasting a standing pose is sliding around the map, which is what onlookers
         * reported before this existed.
         */
        public boolean isWalking() {
            return stance <= 3;
        }
    }

    private final List<Swing> swings = new ArrayList<>();
    private final List<Step> steps = new ArrayList<>();

    /**
     * Reads everything that has arrived.
     *
     * Anything that is not one of the two broadcasts is dropped rather than counted: this is
     * an instrument pointed at two specific things, not another agent.
     */
    public void watch(PacketInbox inbox) {
        PacketInbox.Received received;
        while ((received = inbox.poll()) != null) {
            if (received.opcode() == SendOpcode.CLOSE_RANGE_ATTACK.getValue()) {
                readSwing(received.packet());
            } else if (received.opcode() == SendOpcode.MOVE_PLAYER.getValue()) {
                readSteps(received.packet());
            }
        }
    }

    /** @see tools.PacketCreator#addAttackBody */
    private void readSwing(InPacket p) {
        try {
            int characterId = p.readInt();
            int numAttackedAndDamage = p.readByte() & 0xFF;
            p.readByte();                       // 0x5B, unexplained in the server too
            int skillLevel = p.readByte() & 0xFF;
            if (skillLevel > 0) {
                p.readInt();                    // the skill, only present when levelled
            }
            int display = p.readByte() & 0xFF;
            int direction = p.readByte() & 0xFF;
            int stance = p.readByte() & 0xFF;
            int speed = p.readByte() & 0xFF;
            swings.add(new Swing(characterId, display, direction, stance, speed,
                    (numAttackedAndDamage >>> 4) & 0xF));
        } catch (RuntimeException e) {
            // A broadcast we cannot read is worth nothing and worth crashing over even less.
        }
    }

    /** @see tools.PacketCreator#movePlayer */
    private void readSteps(InPacket p) {
        try {
            int characterId = p.readInt();
            p.readInt();                        // unused
            int commands = p.readByte() & 0xFF;
            for (int i = 0; i < commands; i++) {
                int command = p.readByte() & 0xFF;
                if (command != 0) {
                    // Only the absolute move carries a position and a pose in this shape;
                    // anything else and the rest of the packet is no longer where we think.
                    return;
                }
                int x = p.readShort();
                int y = p.readShort();
                p.readShort();                  // wobble
                p.readShort();
                p.readShort();                  // foothold
                int stance = p.readByte() & 0xFF;
                int duration = p.readShort();
                steps.add(new Step(characterId, x, y, stance, duration));
            }
        } catch (RuntimeException e) {
            // As above.
        }
    }

    /** Everything seen so far, oldest first. */
    public List<Swing> swings() {
        return List.copyOf(swings);
    }

    public List<Step> steps() {
        return List.copyOf(steps);
    }

    /** Only what somebody else did, which is the only thing worth asserting on. */
    public List<Swing> swingsBy(int characterId) {
        return swings.stream().filter(s -> s.characterId() == characterId).toList();
    }

    public List<Step> stepsBy(int characterId) {
        return steps.stream().filter(s -> s.characterId() == characterId).toList();
    }

    public void forget() {
        swings.clear();
        steps.clear();
    }
}
