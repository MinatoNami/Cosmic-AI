package agents.mind;

import agents.Mind;
import agents.memory.Belief;
import agents.world.WorldModel;

import java.awt.Point;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Fixed rules, no model, no reasoning.
 *
 * This exists to be the control. When an LLM policy runs later, the only way to say whether
 * it helped is to have something to compare it against on the same map, and that something
 * has to be simple enough that nobody suspects it of being clever. It also keeps agents
 * alive when the model is unreachable, and it costs nothing to run for hours.
 *
 * The rules are a priority ladder, roughly what a bored player does: pick up what is at your
 * feet, hit what is in front of you, take a door if there is nothing else, otherwise wander.
 * Note what it does <em>not</em> do - it has no notion that monsters are worth killing or
 * that one map is better than another. It attacks what is near because it is near.
 */
public class ReflexPolicy implements Policy {
    private static final int MELEE_RANGE = 60;
    private static final int PORTAL_RANGE = 40;
    private static final int WANDER_STEP = 80;

    /** Roughly one portal attempt every this many decisions, so agents do not pinball. */
    private static final int PORTAL_RELUCTANCE = 20;

    private final Random random;
    private int decisionsHere;
    private int lastMapId = -1;

    /**
     * The portal currently being walked to.
     *
     * Without this the policy re-decided every tick, took one step towards a door and then
     * wandered off again, so in a two-minute run it never reached one - 199 decisions, all
     * of them MoveTo, and the agent never left the starting town. Committing to a
     * destination until it is reached is the difference between wandering and going
     * somewhere.
     */
    private WorldModel.PortalTarget committedPortal;

    public ReflexPolicy(Random random) {
        this.random = random;
    }

    @Override
    public Decision decide(Mind mind, WorldModel world, long tick) {
        if (world.mapId() != lastMapId) {
            lastMapId = world.mapId();
            decisionsHere = 0;
            committedPortal = null;     // the old map's doors are gone
        }
        decisionsHere++;

        List<String> consulted = mind.recall("map monster danger", tick, 3)
                .stream().map(Belief::ref).toList();
        Point self = world.selfPosition();

        Optional<WorldModel.Entity> drop = world.nearestDrop();
        if (drop.isPresent() && drop.get().position().distance(self) < MELEE_RANGE) {
            return new Decision(new Intent.PickUp(drop.get().objectId(), drop.get().position()),
                    "take what is at my feet", consulted, options());
        }

        Optional<WorldModel.Entity> monster = world.nearestMonster();
        if (monster.isPresent()) {
            WorldModel.Entity target = monster.get();
            if (target.position().distance(self) < MELEE_RANGE) {
                return new Decision(new Intent.Attack(target.objectId(), target.position()),
                        "hit what is in front of me", consulted, options());
            }
            return new Decision(new Intent.MoveTo(target.position()),
                    "get closer to the thing I can see", consulted, options());
        }

        // Nothing here. Head for a door, and keep heading for it until we arrive.
        if (committedPortal == null && decisionsHere % PORTAL_RELUCTANCE == 0) {
            List<WorldModel.PortalTarget> portals = world.portals();
            if (!portals.isEmpty()) {
                committedPortal = portals.get(random.nextInt(portals.size()));
            }
        }

        if (committedPortal != null) {
            if (committedPortal.position().distance(self) < PORTAL_RANGE) {
                Intent enter = new Intent.EnterPortal(committedPortal.name(), committedPortal.position());
                committedPortal = null;
                return new Decision(enter, "see where this goes", consulted, options());
            }
            return new Decision(new Intent.MoveTo(committedPortal.position()),
                    "walk to a way out", consulted, options());
        }

        Point wander = new Point(self.x + random.nextInt(2 * WANDER_STEP) - WANDER_STEP, self.y);
        return new Decision(new Intent.MoveTo(wander), "wander", consulted, options());
    }

    private static List<String> options() {
        return List.of("PickUp", "Attack", "MoveTo", "EnterPortal", "Wait");
    }

    @Override
    public String name() {
        return "reflex";
    }
}
