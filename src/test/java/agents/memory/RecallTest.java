package agents.memory;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecallTest {

    /** What recall was before it kept only the best few: every belief sorted by score. */
    private static List<Belief> sortedByScore(SemanticMemory memory, String query, long now, int limit) {
        Set<String> queryTerms = Arrays.stream(query.toLowerCase().split("[^a-z0-9]+"))
                .filter(t -> !t.isBlank()).collect(Collectors.toSet());
        return memory.liveBeliefs().stream()
                .sorted(Comparator.comparingDouble((Belief b) -> -Recall.score(b, queryTerms, now)))
                .limit(limit)
                .toList();
    }

    @Test
    void picksWhatSortingEverythingPicked() {
        Random random = new Random(7);
        SemanticMemory memory = new SemanticMemory();
        String[] subjects = {"npc:1002000", "map:104000000", "monster:100100", "self", "item:2000000"};
        String[] predicates = {"present_in", "talks_in", "gives_exp", "hp", "sells"};
        for (int i = 0; i < 3000; i++) {
            memory.assertTriple(subjects[random.nextInt(subjects.length)], predicates[random.nextInt(predicates.length)],
                    "map:" + random.nextInt(40), i, random.nextInt(2000), Belief.Provenance.values()[random.nextInt(3)]);
        }
        for (String query : List.of("where is npc:1002000", "map:104000000 monsters", "", "hp self potion sells")) {
            for (int limit : List.of(1, 10, 40)) {
                assertEquals(sortedByScore(memory, query, 2000, limit), Recall.mostRelevant(memory, query, 2000, limit),
                        query + " / " + limit);
            }
        }
    }

    @Test
    void termsAreTheRunsOfLettersAndDigits() {
        assertEquals(Set.of("npc", "1002000", "present", "in", "map", "104000000"),
                Recall.terms("npc:1002000 Present_In map:104000000"));
    }
}
