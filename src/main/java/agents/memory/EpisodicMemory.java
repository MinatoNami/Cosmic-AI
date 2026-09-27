package agents.memory;

import agents.percept.Observation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * The record of what an agent perceived, keeping what its beliefs rest on and the recent past.
 *
 * It kept everything, for ever, and two days of running made that concrete: 2.25 million
 * episodes across three agents, minds of 620 and 780MB on disk, a 4GB heap nearly full and
 * the daemon spending its time collecting garbage. {@link #forgetAllBut} now drops the
 * middle of the log - anything no belief points at and older than the recent window - which
 * is what this class always said it would have to do. Nothing is lost that is not also in
 * the trace, which remains the whole history.
 *
 * <p>Ids stay what they were. Beliefs point at episodes by id, so ids are handed out from a
 * counter that only goes up, never from a position: dropping an episode leaves a gap, and a
 * gap is simply an episode no longer held, never a different episode answering to its id.
 *
 * <p>Held in a concurrent map because it is read from other threads - the saver, the status
 * page - while its agent writes to it.
 */
public class EpisodicMemory {
    private final NavigableMap<Long, Episode> episodes = new ConcurrentSkipListMap<>();
    private volatile long nextId;

    public Episode record(Observation observation) {
        Episode episode = new Episode(nextId, observation.tick(), observation);
        episodes.put(episode.id(), episode);
        nextId++;
        return episode;
    }

    /** Adds a recalled episode under the next id, for a mind being assembled from scratch. */
    public Episode restore(long tick, String type, String detail) {
        return restore(nextId, tick, type, detail);
    }

    /** Puts back an episode from a saved mind, under the id it had. */
    public Episode restore(long id, long tick, String type, String detail) {
        Episode episode = new Episode(id, tick, new Observation.Recalled(tick, type, detail));
        episodes.put(id, episode);
        nextId = Math.max(nextId, id + 1);
        return episode;
    }

    public Optional<Episode> byId(long id) {
        return Optional.ofNullable(episodes.get(id));
    }

    /** Most recent first, which is the order anything reading them wants. */
    public List<Episode> recent(int limit) {
        List<Episode> window = new ArrayList<>(limit);
        for (Episode episode : episodes.descendingMap().values()) {
            if (window.size() >= limit) {
                break;
            }
            window.add(episode);
        }
        return window;
    }

    public List<Episode> all() {
        return List.copyOf(episodes.values());
    }

    /** How many episodes are held now, which after forgetting is fewer than were ever seen. */
    public int size() {
        return episodes.size();
    }

    /** Whether anything has ever been perceived. */
    public boolean isEmpty() {
        return nextId == 0;
    }

    /** The id of the latest episode, or -1 if there has never been one. */
    public long latestId() {
        return nextId - 1;
    }

    /**
     * Drops every episode not in {@code keep} and older than the last {@code recent}.
     *
     * @return how many were dropped
     */
    public int forgetAllBut(Set<Long> keep, int recent) {
        long recentFrom = nextId - recent;
        int dropped = 0;
        for (Map.Entry<Long, Episode> entry : episodes.headMap(recentFrom, false).entrySet()) {
            if (!keep.contains(entry.getKey())) {
                episodes.remove(entry.getKey());
                dropped++;
            }
        }
        return dropped;
    }
}
