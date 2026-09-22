package agents.memory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What one generation of agents leaves the next.
 *
 * Bodies are cheap and knowledge is not. An agent that has spent a night walking knows a
 * hundred maps, which doors are painted on, where the shopkeepers stand and what they want
 * before they will help - and all of that dies with the character when it is reset to level
 * one. This condenses a lifetime down to the part worth keeping, merges what several agents
 * learned, and writes it as an ordinary mind the next generation wakes up holding.
 *
 * <p><strong>The world survives; the self does not.</strong> Where a door leads is true
 * tomorrow. That you were level twenty-three, standing in Henesys with nine hundred mesos,
 * is about a character who no longer exists, and carrying it forward would have an agent
 * wake up believing things about itself that are plainly false the moment it looks. The
 * episode log goes too - four hundred thousand perceptions across two agents, which is what
 * makes the files tens of megabytes, and none of it is knowledge so much as the raw material
 * knowledge was made from.
 *
 * <p><strong>Inherited beliefs are hearsay.</strong> Not first-hand: the new agent has never
 * seen Ereve, and saying otherwise in a memory whose whole point is that provenance is
 * honest would be the one unforgivable lie. Hearsay is also the machinery that already fits
 * - an agent that later sees an inherited claim for itself promotes it automatically, so a
 * generation spends its life quietly converting what it was told into what it knows.
 *
 * <p>All of it hangs on a single episode, the moment of inheritance, because every belief
 * must name the evidence it rests on and this is the honest answer: it was given this at
 * birth.
 */
public final class Inheritance {

    /**
     * Predicates worth outliving a body.
     *
     * Deliberately a short list of things about the world rather than a filter of things
     * about the self, so that a predicate invented later is dropped until somebody decides
     * it belongs. Forgetting something useful is recoverable by walking; inheriting rubbish
     * is not.
     */
    private static final Set<String> WORTH_KEEPING = Set.of(
            "leads_to",      // the map: which door goes where, including the ones that go nowhere
            "has_door",      // which exits a map has, which is what makes an unopened one findable
            "present_in",    // where a monster or an NPC was standing
            "talks_in",      // which map an NPC holds its conversations in
            "wants_first",   // what an NPC said it needed before it would help
            "drops");        // what a monster was seen to leave behind

    private Inheritance() {
    }

    /** What a merge produced, for the caller to report. */
    public record Merged(int minds, int beliefsRead, int beliefsKept, int agreed) {
    }

    /**
     * Condenses each mind, merges them, and writes the result as a mind file.
     *
     * Agreement is free and worth having: two agents that independently found the same door
     * produce one belief with two pieces of evidence behind it, and {@link SemanticMemory}
     * raises its confidence accordingly. Where they disagree, both claims are kept - a
     * portal believed to lead nowhere by one and somewhere by the other keeps both, and the
     * rule that a real destination beats a dead end resolves it at the point of use rather
     * than by throwing evidence away here.
     */
    public static Merged merge(List<Path> sources, Path destination, String name) {
        // One episode per predecessor, not one for the whole inheritance. Two agents that
        // independently found the same door are two pieces of evidence for it, and they only
        // read as two if they are two separate moments of being told - a single shared
        // episode makes the second one a duplicate and the belief no better supported than
        // if one agent had said it twice.
        EpisodicMemory episodic = new EpisodicMemory();
        SemanticMemory inherited = new SemanticMemory();
        int read = 0;
        int kept = 0;
        int agreed = 0;
        int minds = 0;

        for (Path source : sources) {
            if (!Files.isRegularFile(source)) {
                continue;
            }
            EpisodicMemory theirEpisodes = new EpisodicMemory();
            SemanticMemory theirBeliefs = new SemanticMemory();
            if (MindSnapshot.load(source, theirEpisodes, theirBeliefs) == null) {
                continue;
            }
            minds++;
            long told = episodic.restore(0, "Inherited",
                    "Inherited[from " + source.getFileName() + "]").id();
            for (Belief belief : theirBeliefs.liveBeliefs()) {
                read++;
                if (!WORTH_KEEPING.contains(belief.predicate())) {
                    continue;
                }
                SemanticMemory.Assertion assertion = inherited.assertTriple(
                        belief.subject(), belief.predicate(), belief.object(),
                        told, 0, Belief.Provenance.HEARSAY);
                if (assertion.isNew()) {
                    kept++;
                } else {
                    agreed++;
                }
            }
        }

        // Tick zero: the next generation starts its clock at the beginning, because it is a
        // new life and not a continuation of anybody's.
        MindSnapshot.save(destination, name, 0, episodic, inherited);
        return new Merged(minds, read, kept, agreed);
    }

    /** Every saved mind in a directory, excluding any inheritance file already there. */
    public static List<Path> mindsIn(Path directory, String inheritanceFile) {
        List<Path> found = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return found;
        }
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.sorted().toList()) {
                String file = entry.getFileName().toString();
                if (file.endsWith(".mind") && !file.equals(inheritanceFile)) {
                    found.add(entry);
                }
            }
        } catch (java.io.IOException cannotList) {
            throw new java.io.UncheckedIOException("Could not list minds in " + directory,
                    cannotList);
        }
        return found;
    }
}
