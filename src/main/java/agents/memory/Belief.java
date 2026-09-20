package agents.memory;

import java.util.List;

/**
 * Something an agent holds to be true, and the evidence it holds it on.
 *
 * Beliefs are immutable; revising one produces a new instance and marks the old one
 * invalidated rather than deleting it. Keeping the wrong ones is the point - "believed X for
 * an hour, then saw Y and stopped" is the most interesting thing a replay can show, and it
 * is exactly what deletion destroys.
 *
 * @param supportedBy episode ids, never empty - a belief with no evidence is a bug
 * @param invalidatedAt tick at which this stopped being held, or null while it still is
 * @param supersededBy the belief that replaced this one, or null
 */
public record Belief(long id,
                     String subject,
                     String predicate,
                     String object,
                     double confidence,
                     Provenance provenance,
                     List<Long> supportedBy,
                     long firstSeen,
                     long lastSeen,
                     Long invalidatedAt,
                     Long supersededBy) {

    /**
     * Where a belief came from. First-hand and hearsay are both grounded in an episode - the
     * difference is whether that episode was the thing itself or someone saying so - and
     * keeping them apart is what lets an agent weigh a rumour differently from a sighting.
     */
    public enum Provenance {
        FIRST_HAND,
        HEARSAY
    }

    public Belief {
        if (supportedBy == null || supportedBy.isEmpty()) {
            throw new IllegalArgumentException("A belief must name the evidence it rests on");
        }
        supportedBy = List.copyOf(supportedBy);
    }

    public String ref() {
        return "b" + id;
    }

    public boolean isLive() {
        return invalidatedAt == null;
    }

    /** The triple as one line, for prompts, logs and lexical matching. */
    public String asSentence() {
        return subject + " " + predicate + " " + object;
    }

    public Belief corroboratedBy(long episodeId, long tick, double newConfidence) {
        List<Long> support = new java.util.ArrayList<>(supportedBy);
        if (!support.contains(episodeId)) {
            support.add(episodeId);
        }
        return new Belief(id, subject, predicate, object, newConfidence, provenance,
                support, firstSeen, tick, invalidatedAt, supersededBy);
    }

    public Belief invalidatedAt(long tick, long replacementId) {
        return new Belief(id, subject, predicate, object, confidence, provenance,
                supportedBy, firstSeen, lastSeen, tick, replacementId);
    }
}
