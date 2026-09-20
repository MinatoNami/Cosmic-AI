package agents.memory;

import agents.memory.Belief.Provenance;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What an agent has concluded, as triples with evidence attached.
 *
 * Two things make this more than a set of facts. Every belief points at the episodes that
 * produced it, so any conclusion can be walked back to raw perception. And revision is
 * additive: a belief that turns out to be wrong is marked invalidated and left in place,
 * pointing at whatever replaced it.
 */
public class SemanticMemory {

    /**
     * Predicates that admit one value at a time. Asserting a new value for one of these
     * invalidates the old belief; asserting a new value for anything else simply adds
     * another, because most things in a world are not exclusive - a monster can drop many
     * items, a map can contain many NPCs.
     *
     * Defaulting to non-exclusive matters: treating an unknown predicate as functional would
     * silently destroy true beliefs, and a wrongly-kept belief is easier to notice than a
     * wrongly-deleted one.
     */
    private static final Set<String> FUNCTIONAL_PREDICATES = Set.of(
            "named", "level", "in_map", "hp", "maxhp", "mp", "maxmp", "exp", "meso", "job",
            "state");

    private final List<Belief> beliefs = new ArrayList<>();

    /**
     * Records a triple, merging with an existing belief where one already says the same
     * thing.
     *
     * @return what the memory holds afterwards, whether newly created or corroborated
     */
    public Assertion assertTriple(String subject, String predicate, String object,
                                  long episodeId, long tick, Provenance provenance) {
        Optional<Belief> identical = liveBeliefs().stream()
                .filter(b -> b.subject().equals(subject)
                        && b.predicate().equals(predicate)
                        && b.object().equals(object))
                .findFirst();

        if (identical.isPresent()) {
            Belief existing = identical.get();
            // Score on the better of the two sources. Being told something you already saw
            // should not drag your confidence down onto the hearsay curve, and seeing
            // something you had only been told should promote it.
            Provenance best = stronger(existing.provenance(), provenance);
            Belief corroborated = existing.corroboratedBy(episodeId, tick,
                    confidenceFor(existing.supportedBy().size() + 1, best), best);
            replace(existing, corroborated);
            return new Assertion(corroborated, null, false);
        }

        Belief created = new Belief(beliefs.size(), subject, predicate, object,
                confidenceFor(1, provenance), provenance, List.of(episodeId), tick, tick, null, null);

        Belief contradicted = null;
        if (FUNCTIONAL_PREDICATES.contains(predicate)) {
            Optional<Belief> conflicting = liveBeliefs().stream()
                    .filter(b -> b.subject().equals(subject) && b.predicate().equals(predicate))
                    .findFirst();
            if (conflicting.isPresent()) {
                contradicted = conflicting.get();
                replace(contradicted, contradicted.invalidatedAt(tick, created.id()));
            }
        }

        beliefs.add(created);
        return new Assertion(created, contradicted, true);
    }

    /**
     * What an assertion did, so the caller can trace it.
     *
     * @param contradicted the belief this one replaced, or null
     * @param isNew false when an existing belief was corroborated instead
     */
    public record Assertion(Belief belief, Belief contradicted, boolean isNew) {
    }

    /**
     * Confidence as a function of corroboration: each independent sighting halves the
     * remaining doubt, so one gets you halfway and three gets you most of the way.
     *
     * Crude, and deliberately so - it is explainable, it is visible climbing in a replay, and
     * it does not pretend to a rigour we have no basis for. Seeing beats reasoning beats
     * being told, which is the ordering the base values encode.
     */
    private static double confidenceFor(int supportCount, Provenance provenance) {
        double base = switch (provenance) {
            case FIRST_HAND -> 0.5;
            case INFERRED -> 0.35;
            case HEARSAY -> 0.25;
        };
        double doubt = Math.pow(1 - base, supportCount);
        return Math.min(0.99, 1 - doubt);
    }

    /** FIRST_HAND beats INFERRED beats HEARSAY. */
    private static Provenance stronger(Provenance a, Provenance b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }

    private void replace(Belief old, Belief updated) {
        beliefs.set((int) old.id(), updated);
    }

    /**
     * Puts back a belief from a saved mind, in id order.
     *
     * Belief ids are positions too, and {@code replace} writes by index, so an out-of-order
     * restore would corrupt every later revision. Loudly refused rather than repaired.
     */
    public void restore(Belief belief) {
        if (belief.id() != beliefs.size()) {
            throw new IllegalArgumentException("Beliefs restore in id order: expected "
                    + beliefs.size() + ", got " + belief.id());
        }
        beliefs.add(belief);
    }

    public List<Belief> liveBeliefs() {
        return beliefs.stream().filter(Belief::isLive).toList();
    }

    /** Including invalidated ones, which is what a replay wants. */
    public List<Belief> all() {
        return List.copyOf(beliefs);
    }

    public Optional<Belief> byId(long id) {
        if (id < 0 || id >= beliefs.size()) {
            return Optional.empty();
        }
        return Optional.of(beliefs.get((int) id));
    }

    public List<Belief> about(String subject) {
        return liveBeliefs().stream().filter(b -> b.subject().equals(subject)).toList();
    }

    public int size() {
        return beliefs.size();
    }
}
