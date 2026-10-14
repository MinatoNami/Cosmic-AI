package agents.memory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            "drops",         // what a monster was seen to leave behind
            // Learnt the hard way by the last generation, and every bit as true for the next:
            "killed_you_at_level", // where a predecessor died, and how strong it was then
            "runs_shop",     // who keeps a shop
            "sells",         // and what is on their shelves
            "restores_hp",   // what an item did when it was drunk
            "restores_mp",
            "does_not_answer", // who never says a word, however often greeted
            "strands_you",   // who sends you somewhere there is no leaving
            "takes_you_to",  // where somebody's offer of passage actually goes
            "nothing_to_do", // somewhere with nothing to hunt and nowhere new to walk to
            "offers_calling", // which calling somebody offers a beginner
            "heals_up_to",   // the most a drink has been seen to restore
            // Every bit as true of the world, and the first things a recording of somebody
            // playing can teach: what a monster is worth, and whether it fights back.
            "gives_exp",
            "hurts_you",
            // How people play, from recordings of them doing it (see Demonstrations). About
            // the people rather than the world, but just as true for the next generation.
            "ap_share_str", "ap_share_dex", "ap_share_int", "ap_share_luk",
            "ap_share_maxhp", "ap_share_maxmp",
            "puts_sp_into",  // which skills a job spends its points on
            "hunted_at_level", // the level people hunt a map at
            "drunk_at_hp_percent", // how low health gets before somebody drinks
            "needs_kills",   // what a quest wants hunted, and how many
            "buffs",         // what a skill raises when it is cast
            "lasts_seconds"); // and for how long

    private Inheritance() {
    }

    /** What a merge produced, for the caller to report. */
    public record Merged(int minds, int beliefsRead, int beliefsKept, int agreed,
                         List<Contribution> sources) {
    }

    /**
     * What one source brought.
     *
     * beliefsKept and agreed depend on the order of the merge - the first source to say
     * something keeps it, the rest agree - so on their own they credit whoever went first.
     * notInBaseline does not: it is what this source held that the inheritance before the
     * merge did not, which is the honest answer to "what did this agent add".
     */
    public record Contribution(String file, int beliefsRead, int beliefsKept, int agreed,
                               int notInBaseline) {
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
        return merge(sources, destination, name, null);
    }

    /** @param baseline the inheritance before this merge, to measure each source against */
    public static Merged merge(List<Path> sources, Path destination, String name, Path baseline) {
        return merge(sources, destination, name, baseline,
                "condensed " + java.time.Instant.now());
    }

    /**
     * Merges, counting witnesses rather than files.
     *
     * The evidence behind an inherited belief is the predecessors who said it, one episode
     * each. Counting files instead had two faults. A previous inheritance read as a single
     * witness, so a belief four agents had agreed on came out of the next merge resting on
     * one - every condense quietly undid the agreement of the ones before, and folding in a
     * generation that had little new to add left 1,823 beliefs weaker than it found them.
     * And an agent repeating what it was born being told counted as a fresh witness, though
     * it had seen nothing.
     *
     * <p>So each belief is traced to who actually said it. Something an agent saw for itself
     * is a witness of its own, named after the file and this merge's label so that it can
     * never be mistaken for another generation's agent of the same name. Something it holds
     * only because it inherited it passes through as the witnesses it inherited it from, and
     * a previous inheritance's beliefs keep every witness they had. A belief can only gain
     * support in a merge, never lose it.
     *
     * <p>Witnesses passed through are known only by their labels, and inheritances made before
     * labels were unique named theirs by file alone - "Agent0.mind" in one generation and
     * the next. Two such are taken to be one. That undercounts, which is the safe way round.
     *
     * @param label what this merge is, to tell its witnesses from any other merge's
     */
    public static Merged merge(List<Path> sources, Path destination, String name, Path baseline,
                               String label) {
        Set<String> before = new java.util.HashSet<>();
        if (baseline != null && Files.isRegularFile(baseline)) {
            EpisodicMemory ignored = new EpisodicMemory();
            SemanticMemory held = new SemanticMemory();
            if (MindSnapshot.load(baseline, ignored, held) != null) {
                for (Belief b : held.liveBeliefs()) {
                    before.add(said(b));
                }
            }
        }
        // One episode per witness, never shared: two agents that independently found the same
        // door only read as two pieces of evidence if they are two separate moments of being
        // told.
        EpisodicMemory episodic = new EpisodicMemory();
        Map<String, Long> witnessEpisodes = new java.util.HashMap<>();
        Map<String, Set<String>> witnessesOf = new java.util.HashMap<>();
        Set<String> ownLabels = new java.util.HashSet<>();
        SemanticMemory inherited = new SemanticMemory();
        int read = 0;
        int kept = 0;
        int agreed = 0;
        int minds = 0;
        List<Contribution> contributions = new ArrayList<>();

        // What the inheritances among the sources already hold. An agent was born holding an
        // inheritance, and what it only inherited is in that inheritance, which is itself a
        // source here (or an older one, folded into a source here). The inheritance carries
        // the witnesses; the agent repeating them would be counted again, and wrongly if its
        // labels are older than the inheritance's - as they are when a chain is rebuilt.
        Set<String> coveredByInheritance = new java.util.HashSet<>();
        for (Path source : sources) {
            EpisodicMemory theirEpisodes = new EpisodicMemory();
            SemanticMemory theirBeliefs = new SemanticMemory();
            if (Files.isRegularFile(source)
                    && MindSnapshot.load(source, theirEpisodes, theirBeliefs) != null
                    && isInheritance(theirEpisodes)) {
                theirBeliefs.liveBeliefs().forEach(b -> coveredByInheritance.add(said(b)));
            }
        }

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
            boolean anInheritance = isInheritance(theirEpisodes);
            int theirRead = 0;
            int theirKept = 0;
            int theirAgreed = 0;
            int theirNovel = 0;
            String own = INHERITED + "[from " + source.getFileName() + ", " + label + "]";
            for (int n = 2; !ownLabels.add(own); n++) {
                own = INHERITED + "[from " + source.getFileName() + " #" + n + ", " + label + "]";
            }
            for (Belief belief : theirBeliefs.liveBeliefs()) {
                theirRead++;
                if (!WORTH_KEEPING.contains(belief.predicate())) {
                    continue;
                }
                String triple = said(belief);
                Set<String> already = witnessesOf.computeIfAbsent(triple, t -> new java.util.LinkedHashSet<>());
                boolean first = already.isEmpty();
                if (!before.contains(triple)) {
                    theirNovel++;
                }
                boolean passOn = anInheritance || !coveredByInheritance.contains(triple);
                for (String witness : witnessesFor(belief, theirEpisodes, own, passOn)) {
                    if (!already.add(witness)) {
                        continue;
                    }
                    long told = witnessEpisodes.computeIfAbsent(witness,
                            w -> episodic.restore(0, INHERITED, w).id());
                    inherited.assertTriple(belief.subject(), belief.predicate(), belief.object(),
                            told, 0, Belief.Provenance.HEARSAY);
                }
                // First to say it only if it actually said it: an agent passing on what an
                // inheritance here already holds agrees with it, it does not introduce it.
                if (first && !already.isEmpty()) {
                    theirKept++;
                } else {
                    theirAgreed++;
                }
            }
            read += theirRead;
            kept += theirKept;
            agreed += theirAgreed;
            contributions.add(new Contribution(source.getFileName().toString(),
                    theirRead, theirKept, theirAgreed, theirNovel));
        }

        // Tick zero: the next generation starts its clock at the beginning, because it is a
        // new life and not a continuation of anybody's.
        MindSnapshot.save(destination, name, 0, episodic, inherited);
        return new Merged(minds, read, kept, agreed, contributions);
    }

    private static final String INHERITED = "Inherited";

    /** A mind that is nothing but what it was told: an inheritance, not an agent's life. */
    private static boolean isInheritance(EpisodicMemory episodes) {
        List<Episode> all = episodes.all();
        return !all.isEmpty() && all.stream().allMatch(e ->
                e.observation() instanceof agents.percept.Observation.Recalled r
                        && INHERITED.equals(r.type()));
    }

    /**
     * Who a source's belief rests on: the witnesses it inherited it from, and the source
     * itself if it has any evidence of its own. An episode it no longer remembers is taken
     * to be its own - what a bounded mind forgets first is what it saw, not what it was born
     * knowing.
     */
    private static List<String> witnessesFor(Belief belief, EpisodicMemory theirs, String own,
                                             boolean passOn) {
        List<String> witnesses = new ArrayList<>();
        boolean saw = false;
        for (long id : belief.supportedBy()) {
            var told = theirs.byId(id)
                    .map(Episode::observation)
                    .filter(o -> o instanceof agents.percept.Observation.Recalled r
                            && INHERITED.equals(r.type()))
                    .map(o -> ((agents.percept.Observation.Recalled) o).detail());
            if (told.isPresent()) {
                if (passOn && !witnesses.contains(told.get())) {
                    witnesses.add(told.get());
                }
            } else {
                saw = true;
            }
        }
        if (saw) {
            witnesses.add(0, own);
        }
        return witnesses;
    }

    private static String said(Belief b) {
        return b.subject() + '\u0000' + b.predicate() + '\u0000' + b.object();
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
