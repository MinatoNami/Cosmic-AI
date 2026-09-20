package agents.memory;

import agents.percept.Observation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The append-only record of everything an agent perceived.
 *
 * Unbounded for now, which is fine for runs measured in hours and wrong for runs measured in
 * days. When it has to be bounded, the thing to drop is the middle of the log rather than
 * its head: an agent's first encounter with something is usually the episode a belief points
 * at, and losing it would leave beliefs with dangling evidence.
 */
public class EpisodicMemory {
    private final List<Episode> episodes = new ArrayList<>();

    public Episode record(Observation observation) {
        Episode episode = new Episode(episodes.size(), observation.tick(), observation);
        episodes.add(episode);
        return episode;
    }

    /**
     * Puts back an episode from a saved mind, keeping its id.
     *
     * Ids are positions in this list and beliefs point at them by number, so a restore has to
     * be in order and complete. A gap would not fail; it would silently repoint a belief's
     * evidence at a different episode, which is the worst kind of wrong this project can be.
     */
    public Episode restore(long tick, String type, String detail) {
        Episode episode = new Episode(episodes.size(), tick,
                new Observation.Recalled(tick, type, detail));
        episodes.add(episode);
        return episode;
    }

    public Optional<Episode> byId(long id) {
        if (id < 0 || id >= episodes.size()) {
            return Optional.empty();
        }
        return Optional.of(episodes.get((int) id));
    }

    /** Most recent first, which is the order anything reading them wants. */
    public List<Episode> recent(int limit) {
        int from = Math.max(0, episodes.size() - limit);
        List<Episode> window = new ArrayList<>(episodes.subList(from, episodes.size()));
        Collections.reverse(window);
        return window;
    }

    public List<Episode> all() {
        return List.copyOf(episodes);
    }

    public int size() {
        return episodes.size();
    }
}
