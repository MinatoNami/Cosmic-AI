package agents.control;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * How often an agent that fell out of the world may be put back in.
 *
 * Without a limit, an agent that crashes the moment it logs in would be logged in again every
 * minute for as long as the daemon runs, each time costing the server a login and the log a
 * stack trace. A few tries an hour covers what this is for - the server dropping a session,
 * a one-off fault - and an agent that keeps dying past that has a bug worth reading rather
 * than retrying.
 */
final class Relaunches {
    static final int PER_HOUR = 3;
    private static final long HOUR_MILLIS = 3_600_000;

    private final Map<String, Deque<Long>> recent = new HashMap<>();

    /**
     * Whether this agent has been relaunched as often as it may be this hour.
     *
     * Only relaunches that worked count - an agent that could not get back in because the
     * server was down has not shown it keeps falling over, and should go on being tried.
     */
    synchronized boolean exhausted(String name, long nowMillis) {
        Deque<Long> times = recent.get(name);
        if (times == null) {
            return false;
        }
        while (!times.isEmpty() && nowMillis - times.peekFirst() >= HOUR_MILLIS) {
            times.removeFirst();
        }
        return times.size() >= PER_HOUR;
    }

    synchronized void relaunched(String name, long nowMillis) {
        recent.computeIfAbsent(name, n -> new ArrayDeque<>()).addLast(nowMillis);
    }
}
