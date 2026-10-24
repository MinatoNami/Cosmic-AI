package agents.memory;

import agents.memory.Belief.Provenance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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
            "state",
            // A character has one of each. These were left out, so every point spent kept the
            // old value alive beside the new one: an agent at DEX 120 held 116 beliefs about
            // its DEX, one for every number it had passed through.
            "str", "dex", "int", "luk", "fame", "availableap", "availablesp",
            // What drinking something did, which the latest try overrules: one that seemed
            // to do nothing while a monster was hitting harder than it healed must not stay
            // believed useless alongside the time it plainly worked.
            "restores_hp", "restores_mp", "offers_calling", "heals_up_to",
            // The share of a job's ability points people put into each stat: one number,
            // which the latest recordings revise rather than add to.
            "ap_share_str", "ap_share_dex", "ap_share_int", "ap_share_luk",
            "ap_share_maxhp", "ap_share_maxmp",
            // How long a skill's buff lasts: one number, which grows with the skill's level.
            "lasts_seconds",
            // Whether somebody is waiting for the agent to do what they asked: it is one
            // thing at a time, and "nothing" must replace whatever it was, not sit beside it.
            "waiting_on");

    /**
     * Copy-on-write, because a mind is read from more threads than the one that writes it:
     * the dialogue reader, the saver, the status page. A plain list read while the agent was
     * adding to it threw ConcurrentModificationException in the reader - once inside an
     * agent's own step, which ended it. Writes are a few per tick against sixteen thousand
     * beliefs, so copying on each costs little; every reader now iterates a snapshot.
     */
    private final List<Belief> beliefs = new CopyOnWriteArrayList<>();

    /**
     * Where to find things without reading everything. Agents in their forties hold 110 to
     * 156 thousand beliefs, and two-thirds of all the time four of them spent was one NPC
     * looked up at a time by reading every one; the laptop they run on sat at 101C and hung.
     * A belief keeps its subject, predicate and object for life - only whether it is live
     * changes - so the indexes only ever grow, on add.
     */
    private final Map<String, List<Integer>> bySubject = new ConcurrentHashMap<>();
    private final Map<String, Integer> byTriple = new ConcurrentHashMap<>();

    /** The live beliefs as of a version of the memory, kept until the memory changes. */
    private record Live(long version, List<Belief> beliefs) {
    }

    private volatile long version;
    private volatile Live live;

    /**
     * Records a triple, merging with an existing belief where one already says the same
     * thing.
     *
     * @return what the memory holds afterwards, whether newly created or corroborated
     */
    public synchronized Assertion assertTriple(String subject, String predicate, String object,
                                               long episodeId, long tick, Provenance provenance) {
        Integer sameId = byTriple.get(triple(subject, predicate, object));
        Optional<Belief> identical = Optional.ofNullable(sameId).map(beliefs::get).filter(Belief::isLive);

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
            // Every one, not just the first: a mind saved before a predicate was made
            // single-valued carries several, and they should all give way to the new one.
            List<Belief> conflicting = about(subject).stream()
                    .filter(b -> b.predicate().equals(predicate))
                    .toList();
            for (Belief old : conflicting) {
                replace(old, old.invalidatedAt(tick, created.id()));
            }
            contradicted = conflicting.isEmpty() ? null : conflicting.get(0);
        }

        add(created);
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

    /**
     * FIRST_HAND beats INFERRED beats HEARSAY.
     *
     * Ranked explicitly: the enum is declared FIRST_HAND, HEARSAY, INFERRED, and comparing
     * by declaration order made hearsay the stronger. An agent that worked something out for
     * itself, about something it had inherited being told, went on holding it as hearsay - and
     * the map trusts its own conclusions over hearsay, so its own "st00 leads nowhere" lost to
     * a wrong "leads to Sleepywood" for hours.
     */
    static Provenance stronger(Provenance a, Provenance b) {
        return rank(a) <= rank(b) ? a : b;
    }

    private static int rank(Provenance provenance) {
        return switch (provenance) {
            case FIRST_HAND -> 0;
            case INFERRED -> 1;
            case HEARSAY -> 2;
        };
    }

    /**
     * Stops holding a belief without anything to replace it: what it rested on turned out
     * not to say what it was taken to say.
     */
    public synchronized void retract(long beliefId, long tick) {
        byId(beliefId).filter(Belief::isLive).ifPresent(old -> replace(old, old.invalidatedAt(tick, -1)));
    }

    private void replace(Belief old, Belief updated) {
        beliefs.set((int) old.id(), updated);
        version++;
    }

    private void add(Belief belief) {
        beliefs.add(belief);
        int id = (int) belief.id();
        List<Integer> ids = bySubject.computeIfAbsent(belief.subject(), s -> new ArrayList<>());
        synchronized (ids) {
            ids.add(id);
        }
        // The first live one is the one that counts, as reading them in order found it.
        byTriple.merge(triple(belief.subject(), belief.predicate(), belief.object()), id,
                (held, added) -> beliefs.get(held).isLive() ? held : added);
        version++;
    }

    private static String triple(String subject, String predicate, String object) {
        return subject + '\u0000' + predicate + '\u0000' + object;
    }

    /**
     * Puts back a belief from a saved mind, in id order.
     *
     * Belief ids are positions too, and {@code replace} writes by index, so an out-of-order
     * restore would corrupt every later revision. Loudly refused rather than repaired.
     */
    public synchronized void restore(Belief belief) {
        if (belief.id() != beliefs.size()) {
            throw new IllegalArgumentException("Beliefs restore in id order: expected "
                    + beliefs.size() + ", got " + belief.id());
        }
        add(belief);
    }

    /**
     * Every live belief, in id order. Worked out once per change rather than once per ask:
     * a decision asks dozens of times and the memory changes a few times a tick.
     */
    public List<Belief> liveBeliefs() {
        long now = version;
        Live held = live;
        if (held != null && held.version() == now) {
            return held.beliefs();
        }
        List<Belief> fresh = beliefs.stream().filter(Belief::isLive).toList();
        live = new Live(now, fresh);
        return fresh;
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

    /** Live beliefs about one subject, in id order, without reading the rest. */
    public List<Belief> about(String subject) {
        List<Integer> ids = bySubject.get(subject);
        if (ids == null) {
            return List.of();
        }
        List<Integer> snapshot;
        synchronized (ids) {
            snapshot = List.copyOf(ids);
        }
        List<Belief> about = new ArrayList<>(snapshot.size());
        for (int id : snapshot) {
            Belief belief = beliefs.get(id);
            if (belief.isLive()) {
                about.add(belief);
            }
        }
        return about;
    }

    public int size() {
        return beliefs.size();
    }
}
