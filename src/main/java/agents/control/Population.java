package agents.control;

import agents.Agent;
import agents.Mind;
import agents.mind.ClaudeOracle;
import agents.mind.Disposition;
import agents.mind.LlmPolicy;
import agents.mind.LmStudioOracle;
import agents.mind.Oracle;
import agents.mind.Policy;
import agents.mind.ReflexPolicy;
import agents.net.LoginFlow;
import agents.net.LoginFlow.Credentials;
import agents.net.LoginFlow.InWorld;
import agents.trace.Labels;
import agents.trace.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import agents.memory.Inheritance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The agents a daemon is currently running, and their lives between runs.
 *
 * {@link agents.Launcher} runs a population for a fixed number of minutes and exits, which is
 * right for an experiment and wrong for something you want to watch. This one starts and
 * stops on request and keeps going in between, so a run ends when someone says so.
 *
 * Every method that touches the roster is synchronized. The callers are HTTP threads, a
 * save timer and a shutdown hook, all of which can arrive at once, and the failure mode of
 * getting this wrong is two populations logged in as the same characters.
 */
public class Population {
    private static final Logger log = LoggerFactory.getLogger(Population.class);

    private static final int WORLD = 0;
    private static final int CHANNEL = 1;
    private static final String PASSWORD = "agentpass";

    /** At a 600ms tick, about one question every eighteen seconds - what a local model can keep up with. */
    private static final int LOCAL_DELIBERATE_EVERY = 30;

    /** Often enough that a crash costs a minute of learning, rare enough to be invisible. */
    private static final long SAVE_EVERY_SECONDS = 60;

    private final String host;
    private final int port;
    /** What one generation leaves the next, beside the agents' own saved minds. */
    public static final String INHERITANCE = "inherited.mind";

    private final Path minds;
    private final Path traces;

    private final List<Running> running = new ArrayList<>();
    private final ScheduledExecutorService saver =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mind-saver");
                t.setDaemon(true);
                return t;
            });

    private String policyName = "reflex";
    private Oracle oracle;
    private long startedAt;

    public Population(String host, int port, Path dataDirectory) {
        this.host = host;
        this.port = port;
        this.minds = dataDirectory.resolve("minds");
        this.traces = dataDirectory.resolve("traces");
        saver.scheduleAtFixedRate(this::saveQuietly,
                SAVE_EVERY_SECONDS, SAVE_EVERY_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * One agent, with everything needed to stop it, write it down, and wake it again.
     *
     * The index and the phase are kept because a reconnect has to produce the same agent it
     * replaced - same disposition, same place in the deliberation cycle - rather than a
     * fresh one that happens to share a name.
     */
    private record Running(String name, int index, int phase, Agent agent, Thread thread,
                           Mind mind) {
    }

    public synchronized boolean isRunning() {
        return !running.isEmpty();
    }

    public synchronized String policy() {
        return policyName;
    }

    public synchronized long startedAt() {
        return startedAt;
    }

    public Path traceDirectory() {
        return traces;
    }

    /**
     * Wakes a population and lets it get on with it.
     *
     * Each agent restores whatever mind it saved last time, so the run is a continuation
     * rather than a fresh start, and its trace is appended to the one file it has always
     * written - one agent, one life, one record.
     *
     * @return the names that made it into the world
     */
    public synchronized List<String> start(int count, String requestedPolicy) {
        if (isRunning()) {
            throw new IllegalStateException("Already running - stop first");
        }
        this.policyName = requestedPolicy == null ? "reflex" : requestedPolicy.toLowerCase();
        // The field, not a local. A local here shadowed it, wake() read the field, and so
        // every agent was built with a null oracle and quietly ran on the reflex while the
        // status endpoint reported "local" and the log said the model had been found. The
        // per-agent policy is now in the status for exactly this reason.
        this.oracle = oracleFor(policyName);

        try {
            Files.createDirectories(minds);
            Files.createDirectories(traces);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not make room for minds and traces", e);
        }

        List<String> awake = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = "Agent" + i;
            try {
                Random random = new Random(name.hashCode());
                Disposition disposition = Disposition.forAgent(i);
                LoginFlow flow = new LoginFlow(host, port, WORLD, CHANNEL, random);
                InWorld connection = flow.enterWorld(
                        new Credentials(name.toLowerCase(), PASSWORD), name);

                Mind mind = new Mind(name, Trace.toFile(traces.resolve(name + ".jsonl"), name));
                Path own = minds.resolve(name + ".mind");
                long resumedAt = mind.restoreFrom(own);
                if (resumedAt > 0) {
                    log.info("{} woke up with {} beliefs from tick {}", name,
                            mind.semantic().size(), resumedAt);
                } else if (Files.isRegularFile(minds.resolve(INHERITANCE))) {
                    // No life of its own to resume, but a previous generation left something.
                    // Bodies are cheap; a hundred maps of walking is not.
                    mind.restoreFrom(minds.resolve(INHERITANCE));
                    log.info("{} was born knowing {} things it has never seen", name,
                            mind.semantic().size());
                }

                // Spread the asking evenly around the cycle rather than having everyone ask
                // on their first decision: one laptop model, three prompts at once, is how
                // three deliberations ran out of token budget together.
                int phase = count <= 1 ? 0 : (i * LOCAL_DELIBERATE_EVERY) / count;
                running.add(wake(name, i, phase, connection, mind, resumedAt));
                awake.add(name);
            } catch (Exception e) {
                log.error("{} failed to enter the world", name, e);
            }
        }
        startedAt = System.currentTimeMillis();
        return awake;
    }

    /**
     * Builds and starts one agent around a mind and a live connection.
     *
     * Shared by the first start and by a reconnect, so the agent that comes back is the one
     * that went away: same disposition, same policy, same place in the deliberation cycle.
     */
    private Running wake(String name, int index, int phase, InWorld connection, Mind mind,
                         long resumedAt) {
        Random random = new Random(name.hashCode());
        Disposition disposition = Disposition.forAgent(index);
        Policy reflex = new ReflexPolicy(random, disposition);
        Policy policy = oracle == null ? reflex
                : new LlmPolicy(oracle, reflex, LOCAL_DELIBERATE_EVERY, phase);
        Agent agent = new Agent(connection, mind, policy, disposition);
        agent.resumeAt(resumedAt);

        Thread thread = new Thread(agent, name);
        thread.start();
        return new Running(name, index, phase, agent, thread, mind);
    }

    /**
     * Logs back in any agent that has ended up somewhere with no way out.
     *
     * Some maps cannot be left by walking. Map 1020100 is an empty tutorial staging room -
     * one portal, and it is a spawn point - and an agent warped into one by an NPC has no
     * action available to it at all. The server's own answer is that map's forcedReturn,
     * which it applies on login, so the recovery is to log back in. Verified: an agent stuck
     * in 1020100 came back in Split Road of Destiny, exactly as its returnMap specifies.
     *
     * The mind is carried across rather than reloaded, so being rescued costs the agent
     * nothing it had learned. The old session is stopped and joined before the new login,
     * because two sessions for one character is the failure this class exists to avoid.
     */
    private synchronized void rescueTheTrapped() {
        for (Running stuck : List.copyOf(running)) {
            if (!stuck.agent().isTrapped()) {
                continue;
            }
            log.warn("{} is in map {} with no way out - logging it back in",
                    stuck.name(), stuck.agent().world().mapId());
            long reachedTick = stuck.agent().tick();
            stuck.agent().stop();
            try {
                stuck.thread().join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            running.remove(stuck);
            try {
                LoginFlow flow = new LoginFlow(host, port, WORLD, CHANNEL,
                        new Random(stuck.name().hashCode()));
                InWorld connection = flow.enterWorld(
                        new Credentials(stuck.name().toLowerCase(), PASSWORD), stuck.name());
                running.add(wake(stuck.name(), stuck.index(), stuck.phase(),
                        connection, stuck.mind(), reachedTick));
                log.info("{} is back in, now in map {}", stuck.name(),
                        stuck.agent().world().mapId());
            } catch (Exception couldNotReturn) {
                // Its mind is still held and still saved; it simply is not in the world.
                // Better than a half-started agent nobody can stop.
                log.error("{} could not be logged back in", stuck.name(), couldNotReturn);
                stuck.mind().close();
            }
        }
    }

    /** Stops everything and writes every mind down before letting go of it. */
    public synchronized void stop() {
        if (running.isEmpty()) {
            return;
        }
        running.forEach(r -> r.agent().stop());
        for (Running r : running) {
            try {
                r.thread().join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        save();
        running.forEach(r -> r.mind().close());
        running.clear();
    }

    /** Writes every mind down without interrupting anyone. */
    public synchronized void save() {
        for (Running r : running) {
            try {
                r.mind().save(minds.resolve(r.name() + ".mind"), r.agent().tick());
            } catch (RuntimeException e) {
                log.error("Could not save {}'s mind", r.name(), e);
            }
        }
    }

    private void saveQuietly() {
        try {
            save();
        } catch (RuntimeException e) {
            log.error("Scheduled save failed", e);
        }
        try {
            rescueTheTrapped();
        } catch (RuntimeException e) {
            log.error("Rescue failed", e);
        }
    }

    /**
     * Forgets everything the agents have learned.
     *
     * Refused while they are running, because a mind deleted underneath a living agent would
     * be written straight back out by the next save.
     */
    /**
     * Folds the current generation's minds into the inheritance.
     *
     * Agents must be stopped, because a mind is written on the way out and condensing a
     * half-saved one would hand the next generation whatever happened to be on disk.
     * Merging is cumulative: the existing inheritance is one of the sources, so what
     * generation one learned survives generation four forgetting to look.
     */
    public synchronized Inheritance.Merged condense() {
        if (isRunning()) {
            throw new IllegalStateException("Stop the agents before condensing what they know");
        }
        List<Path> sources = new ArrayList<>(Inheritance.mindsIn(minds, INHERITANCE));
        Path standing = minds.resolve(INHERITANCE);
        if (Files.isRegularFile(standing)) {
            sources.add(standing);
        }
        return Inheritance.merge(sources, standing, "Inherited");
    }

    public synchronized int forgetMinds() {
        if (isRunning()) {
            throw new IllegalStateException("Stop the agents before wiping their minds");
        }
        int forgotten = 0;
        try {
            if (!Files.isDirectory(minds)) {
                return 0;
            }
            try (var entries = Files.list(minds)) {
                for (Path mind : entries.toList()) {
                    // The inheritance is not one agent's memory, it is what the line has
                    // learned, and a reset is meant to start a new generation rather than
                    // disown every one before it. Deleted only on request.
                    if (mind.getFileName().toString().equals(INHERITANCE)) {
                        continue;
                    }
                    Files.deleteIfExists(mind);
                    forgotten++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not clear saved minds", e);
        }
        return forgotten;
    }

    /** What every agent is doing, for the status endpoint. */
    public synchronized List<AgentStatus> status() {
        List<AgentStatus> all = new ArrayList<>();
        for (Running r : running) {
            Mind mind = r.mind();
            all.add(new AgentStatus(
                    r.name(),
                    r.agent().disposition().name(),
                    r.agent().policyName(),
                    r.thread().isAlive(),
                    r.agent().world().mapId(),
                    mapName(r.agent().world().mapId()),
                    r.agent().world().level(),
                    r.agent().world().hp(),
                    r.agent().world().maxHp(),
                    mind.episodic().size(),
                    mind.semantic().size(),
                    mind.semantic().liveBeliefs().size(),
                    r.agent().tick(),
                    mind.lastDecision().map(d -> d.goal()).orElse(null),
                    mind.lastDecision().map(d -> d.intent()).orElse(null)));
        }
        return all;
    }

    /**
     * What one agent currently believes, strongest first.
     *
     * Served from the daemon's own memory rather than derived from the trace, because a trace
     * grows for the life of the agent and a page that rebuilt the belief set by replaying it
     * would get slower every hour it ran. The trace remains the record of how the beliefs got
     * there; this is only what they are now.
     */
    public synchronized List<BeliefView> beliefs(String agent) {
        for (Running r : running) {
            if (r.name().equals(agent)) {
                return r.mind().semantic().liveBeliefs().stream()
                        .sorted(java.util.Comparator.comparingDouble(
                                agents.memory.Belief::confidence).reversed())
                        .map(b -> new BeliefView(
                                b.ref(),
                                display(b.subject()), b.predicate(), display(b.object()),
                                Math.round(b.confidence() * 1000) / 1000.0,
                                b.provenance().name().toLowerCase(),
                                b.supportedBy().size()))
                        .toList();
            }
        }
        return List.of();
    }

    public record BeliefView(String id, String subject, String predicate, String object,
                             double confidence, String provenance, int evidence) {
    }

    /** Ids with their human names attached, for display only - see {@link Labels}. */
    private static String display(String ref) {
        String name = Labels.forRef(ref);
        return name == null || name.isBlank() ? ref : name;
    }

    public record AgentStatus(String name, String disposition, String policy,
                             boolean alive, int mapId,
                              String mapName, int level, int hp, int maxHp, int episodes,
                              int beliefs, int liveBeliefs, long tick, String goal, String intent) {
    }

    /**
     * The map's name, for the person trying to walk over and find the agent.
     *
     * Display only, and read here rather than by the agent, for the reason {@link Labels}
     * exists: an agent that knew it was standing in "Dangerous Forest" would have been told
     * something it is supposed to find out.
     */
    private static String mapName(int mapId) {
        if (mapId <= 0) {
            return "";
        }
        String name = Labels.forRef("map:" + mapId);
        return name == null ? "" : name;
    }

    private static Oracle oracleFor(String policy) {
        return switch (policy) {
            case "llm" -> {
                if (!ClaudeOracle.credentialsAvailable()) {
                    throw new IllegalStateException(
                            "No ANTHROPIC_API_KEY in the environment or in .env, so every decision "
                                    + "would fall back to reflexes");
                }
                yield new ClaudeOracle();
            }
            case "local" -> {
                String url = localModelUrl();
                String model = LmStudioOracle.discoverModel(url);
                if (model == null) {
                    throw new IllegalStateException("Nothing answering at " + url
                            + " - is LM Studio running with a model loaded, and serving beyond "
                            + "localhost if it is on another machine?");
                }
                log.info("Using the model loaded at {}: {}", url, model);
                yield new LmStudioOracle(url, model);
            }
            default -> null;
        };
    }

    /**
     * Where the local model lives.
     *
     * An environment variable as well as a system property because this runs in a container,
     * where the model is on somebody's laptop across the tailnet rather than on localhost,
     * and compose passes environment rather than JVM flags.
     */
    public static String localModelUrl() {
        String fromEnv = System.getenv("LMSTUDIO_URL");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return System.getProperty("lmstudio.url", "http://localhost:1234/v1/chat/completions");
    }
}
