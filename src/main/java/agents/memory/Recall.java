package agents.memory;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Picks the beliefs worth putting in front of a decision.
 *
 * Scored on relevance, recency and confidence together, after Generative Agents (Park et
 * al., 2023): relevance alone surfaces stale facts, recency alone surfaces whatever just
 * happened whether or not it matters, and confidence alone surfaces the obvious.
 *
 * Relevance is lexical overlap rather than embeddings. That is a deliberate first cut - it
 * costs nothing, it needs no model, and at the scale of a few thousand beliefs about a
 * starting town it is hard to beat. It will stop being enough once agents reason about
 * things they can only describe indirectly, and that is the moment to add embeddings, not
 * before.
 */
public final class Recall {
    private static final double RELEVANCE_WEIGHT = 1.0;
    private static final double RECENCY_WEIGHT = 0.5;
    private static final double CONFIDENCE_WEIGHT = 0.5;

    /** Ticks after which a memory has lost half its recency score. */
    private static final double RECENCY_HALF_LIFE = 500.0;

    private Recall() {
    }

    public static List<Belief> mostRelevant(SemanticMemory memory, String query, long nowTick, int limit) {
        Set<String> queryTerms = terms(query);

        return memory.liveBeliefs().stream()
                .sorted(Comparator.comparingDouble((Belief b) -> -score(b, queryTerms, nowTick)))
                .limit(limit)
                .toList();
    }

    static double score(Belief belief, Set<String> queryTerms, long nowTick) {
        return RELEVANCE_WEIGHT * relevance(belief, queryTerms)
                + RECENCY_WEIGHT * recency(belief, nowTick)
                + CONFIDENCE_WEIGHT * belief.confidence();
    }

    /** Proportion of the query's terms the belief mentions. */
    private static double relevance(Belief belief, Set<String> queryTerms) {
        if (queryTerms.isEmpty()) {
            return 0;
        }
        Set<String> beliefTerms = terms(belief.asSentence());
        long hits = queryTerms.stream().filter(beliefTerms::contains).count();
        return (double) hits / queryTerms.size();
    }

    private static double recency(Belief belief, long nowTick) {
        long age = Math.max(0, nowTick - belief.lastSeen());
        return Math.pow(0.5, age / RECENCY_HALF_LIFE);
    }

    private static Set<String> terms(String text) {
        return Arrays.stream(text.toLowerCase().split("[^a-z0-9]+"))
                .filter(t -> !t.isBlank())
                .collect(Collectors.toSet());
    }
}
