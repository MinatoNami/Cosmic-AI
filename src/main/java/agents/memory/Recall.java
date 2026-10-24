package agents.memory;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

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

    /**
     * The best {@code limit} by score, ties in id order. Each belief is scored once and only
     * the best few are kept: sorting every belief by a comparator that scored both sides
     * scored each of 150 thousand beliefs some seventeen times over, a regex split each time,
     * and was a seventh of everything the agents spent.
     */
    public static List<Belief> mostRelevant(SemanticMemory memory, String query, long nowTick, int limit) {
        Set<String> queryTerms = terms(query);
        List<Belief> beliefs = memory.liveBeliefs();
        if (limit <= 0) {
            return List.of();
        }
        // Worst at the head, so the next better one replaces it; on a tie the later loses.
        java.util.PriorityQueue<Scored> best = new java.util.PriorityQueue<>(
                Comparator.comparingDouble(Scored::score).thenComparing(Comparator.comparingInt(Scored::order).reversed()));
        for (int i = 0; i < beliefs.size(); i++) {
            Scored scored = new Scored(beliefs.get(i), score(beliefs.get(i), queryTerms, nowTick), i);
            if (best.size() < limit) {
                best.add(scored);
            } else if (best.comparator().compare(scored, best.peek()) > 0) {
                best.poll();
                best.add(scored);
            }
        }
        return best.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed().thenComparingInt(Scored::order))
                .map(Scored::belief)
                .toList();
    }

    private record Scored(Belief belief, double score, int order) {
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
        Set<String> hits = new java.util.HashSet<>();
        forEachTerm(belief.asSentence(), term -> {
            if (queryTerms.contains(term)) {
                hits.add(term);
            }
        });
        return (double) hits.size() / queryTerms.size();
    }

    private static double recency(Belief belief, long nowTick) {
        long age = Math.max(0, nowTick - belief.lastSeen());
        return Math.pow(0.5, age / RECENCY_HALF_LIFE);
    }

    static Set<String> terms(String text) {
        Set<String> terms = new java.util.HashSet<>();
        forEachTerm(text, terms::add);
        return terms;
    }

    /** The runs of a-z and 0-9 in the text, lower-cased: what splitting on anything else gave. */
    private static void forEachTerm(String text, java.util.function.Consumer<String> term) {
        String lower = text.toLowerCase();
        int start = -1;
        for (int i = 0; i <= lower.length(); i++) {
            char c = i < lower.length() ? lower.charAt(i) : ' ';
            boolean inTerm = c >= 'a' && c <= 'z' || c >= '0' && c <= '9';
            if (inTerm && start < 0) {
                start = i;
            } else if (!inTerm && start >= 0) {
                term.accept(lower.substring(start, i));
                start = -1;
            }
        }
    }
}
