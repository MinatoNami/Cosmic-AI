package agents.memory;

import agents.mind.Instructions;
import agents.world.KnownWorld;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * What people showed the agents by playing, written as a mind the agents can inherit.
 *
 * The server records every human player it is told to ({@code net.packet.logging
 * .DemonstrationRecorder}): what they did, what it changed, and what they were shown. This reads
 * those recordings and keeps two kinds of thing.
 *
 * <p><strong>The world, in the agents' own words.</strong> Which door leads where, who stands in
 * which map and what roams it, who keeps a shop and what is on the shelf, what a monster drops,
 * how much experience it is worth and whether it hurts, where people died and at what level,
 * what a potion did, what an NPC asked to be brought. Each in exactly the form an agent writes it when it finds the same thing
 * out for itself, so everything that already reasons about doors and shops reasons about these
 * without being told they came from somewhere else.
 *
 * <p><strong>How people play.</strong> Where a job's ability points go, as a share of all of
 * them; which skills a job puts its points into; the levels people hunt a map at; how low their
 * health gets before they drink. Nothing an agent could find out by looking - it is not about
 * the world but about the people in it, and the only way to learn it is to watch them.
 *
 * <p><strong>All of it is hearsay.</strong> The agent was not there. That is the same honesty
 * {@link Inheritance} keeps, and it brings the same reward: when an agent sees for itself what
 * it was shown, the belief is promoted to first-hand by the machinery that already does that.
 *
 * <p><strong>Only what was seen happen.</strong> The server knows where every door leads; a
 * recording only says where the ones somebody walked through went, and only those are written.
 * A door that was looked at but never taken is a door, not a destination.
 */
public final class Demonstrations {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the server writes for a portal that goes nowhere: a spawn point, or a scripted one. */
    private static final int NO_MAP = 999999999;

    /** Health is remembered to the nearest tenth; nobody drinks at precisely 37%. */
    private static final int HP_BUCKET = 10;

    /** Stats a point can go into, as the recorder names them. */
    static final List<String> STATS = List.of("str", "dex", "int", "luk", "maxhp", "maxmp");

    private Demonstrations() {
    }

    /** What reading the recordings produced, for the caller to report. */
    public record Read(int sessions, int lines, int beliefs, int corroborations, int abilityPoints,
                       int unreadable) {
    }

    /**
     * Reads every recording under {@code directory} and writes what they show as a mind.
     *
     * @param directory one folder per character, one {@code .jsonl} file per login
     */
    public static Read write(Path directory, Path destination) {
        EpisodicMemory episodic = new EpisodicMemory();
        SemanticMemory semantic = new SemanticMemory();
        Read read = readInto(directory, episodic, semantic);
        MindSnapshot.save(destination, "Demonstrated", 0, episodic, semantic);
        return read;
    }

    static Read readInto(Path directory, EpisodicMemory episodic, SemanticMemory semantic) {
        Tally tally = new Tally(episodic, semantic);
        for (Path recording : recordings(directory)) {
            tally.session(recording, directory.relativize(recording).toString());
        }
        tally.writeShares();
        return new Read(tally.sessions, tally.lines, tally.created, tally.corroborated,
                tally.abilityPoints(), tally.unreadable);
    }

    /** Every login recorded, oldest first within each character. */
    public static List<Path> recordings(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(directory, 2)) {
            return files.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".jsonl"))
                    // The database snapshots kept alongside are a backup, not a recording.
                    .filter(f -> !f.getFileName().toString().startsWith("snapshots"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list recordings in " + directory, e);
        }
    }

    /** Running totals across every recording, and the memory being written. */
    private static final class Tally {
        private final EpisodicMemory episodic;
        private final SemanticMemory semantic;
        /** Ability points by job, then stat. */
        private final Map<Integer, Map<String, Integer>> points = new TreeMap<>();
        private int sessions;
        private int lines;
        private int created;
        private int corroborated;
        private int unreadable;

        Tally(EpisodicMemory episodic, SemanticMemory semantic) {
            this.episodic = episodic;
            this.semantic = semantic;
        }

        /**
         * One login. One episode for it, because each login is one occasion of being shown
         * things: two people finding the same door are two pieces of evidence, one person
         * walking through it ten times is one.
         */
        void session(Path recording, String name) {
            long shown = episodic.restore(0, "Demonstrated", "Demonstrated[" + name + "]").id();
            Session session = new Session(shown);
            sessions++;
            try (BufferedReader in = Files.newBufferedReader(recording, StandardCharsets.UTF_8)) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    lines++;
                    JsonNode node;
                    try {
                        node = JSON.readTree(line);
                    } catch (IOException malformed) {
                        // The last line of a session the server died during may be half a line.
                        unreadable++;
                        continue;
                    }
                    session.line(node);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read " + recording, e);
            }
            session.finish();
        }

        void assertTriple(String subject, String predicate, String object, long episode) {
            SemanticMemory.Assertion assertion = semantic.assertTriple(subject, predicate, object,
                    episode, 0, Belief.Provenance.HEARSAY);
            if (assertion.isNew()) {
                created++;
            } else {
                corroborated++;
            }
        }

        int abilityPoints() {
            return points.values().stream().flatMap(m -> m.values().stream()).mapToInt(i -> i).sum();
        }

        /**
         * Shares rather than counts, so that one long session and ten short ones say the same
         * thing about where a job's points go, and so that an agent can aim at the proportion
         * whatever its level.
         */
        void writeShares() {
            if (points.isEmpty()) {
                return;
            }
            long shown = episodic.restore(0, "Demonstrated",
                    "Demonstrated[ability points across " + sessions + " sessions]").id();
            points.forEach((job, byStat) -> {
                int total = byStat.values().stream().mapToInt(i -> i).sum();
                byStat.forEach((stat, spent) -> assertTriple("job:" + job, "ap_share_" + stat,
                        String.valueOf(Math.round(100.0 * spent / total)), shown));
            });
        }

        /** What one login showed, read in order, since most of it only means anything in context. */
        private final class Session {
            private final long episode;
            /** Skill points by job, then skill, within this session. */
            private final Map<Integer, Map<Integer, Integer>> skillPoints = new TreeMap<>();
            /** The levels kills happened at, by map, within this session. */
            private final Map<Integer, List<Integer>> huntedAt = new TreeMap<>();
            private int map = -1;

            Session(long episode) {
                this.episode = episode;
            }

            void line(JsonNode line) {
                String act = line.path("act").asText();
                JsonNode detail = line.path("detail");
                JsonNode state = line.path("state");
                JsonNode effect = line.path("effect");
                if (state.has("map")) {
                    map = state.get("map").asInt();
                }

                switch (act) {
                    case "session_start" -> {
                        map = detail.path("state").path("map").asInt(map);
                        surroundings(detail.path("surroundings"));
                    }
                    case "change_map", "change_map_special" -> door(detail, effect);
                    case "close_range_attack", "ranged_attack", "magic_attack" -> attack(detail, state, effect);
                    case "take_damage" -> {
                        if (detail.has("mob") && detail.path("damage").asInt() > 0) {
                            assertTriple("monster:" + detail.get("mob").asInt(), "hurts_you", "true", episode);
                        }
                    }
                    case "died" -> {
                        int where = detail.path("map").asInt(map);
                        if (where > 0 && detail.has("level")) {
                            assertTriple(KnownWorld.mapRef(where), "killed_you_at_level",
                                    String.valueOf(detail.get("level").asInt()), episode);
                        }
                    }
                    case "use_item" -> potion(detail, state, effect);
                    case "special_move" -> buffs(effect);
                    case "distribute_ap" -> {
                        String stat = detail.path("stat").asText();
                        if (STATS.contains(stat) && changed(effect, "ap") && state.has("job")) {
                            points.computeIfAbsent(state.get("job").asInt(), j -> new TreeMap<>())
                                    .merge(stat, 1, Integer::sum);
                        }
                    }
                    case "distribute_sp" -> {
                        if (detail.has("skill") && changed(effect, "sp") && state.has("job")) {
                            skillPoints.computeIfAbsent(state.get("job").asInt(), j -> new TreeMap<>())
                                    .merge(detail.get("skill").asInt(), 1, Integer::sum);
                        }
                    }
                    case "saw" -> shown(detail);
                    default -> {
                    }
                }

                if (line.has("arrived")) {
                    surroundings(line.get("arrived"));
                }
                for (JsonNode seen : line.path("saw")) {
                    shown(seen);
                }
            }

            /** A map walked into: its doors, who stands in it, what roams it. */
            private void surroundings(JsonNode around) {
                if (!around.has("map")) {
                    return;
                }
                int here = around.get("map").asInt();
                map = here;
                String mapRef = KnownWorld.mapRef(here);
                for (JsonNode portal : around.path("portals")) {
                    String name = portal.path("name").asText();
                    if (!name.isEmpty()) {
                        assertTriple(mapRef, "has_door", name, episode);
                    }
                }
                for (JsonNode npc : around.path("npcs")) {
                    assertTriple("npc:" + npc.asInt(), "present_in", mapRef, episode);
                }
                around.path("monsters").fieldNames().forEachRemaining(
                        mob -> assertTriple("monster:" + mob, "present_in", mapRef, episode));
            }

            /** A door somebody walked through, and where it took them. */
            private void door(JsonNode detail, JsonNode effect) {
                String portal = detail.path("portal").asText();
                JsonNode moved = effect.path("map");
                if (portal.isEmpty() || !moved.isArray() || moved.size() != 2) {
                    return;
                }
                int from = moved.get(0).asInt();
                int to = moved.get(1).asInt();
                if (from <= 0 || to <= 0 || from == to || to == NO_MAP) {
                    return;
                }
                assertTriple(KnownWorld.portalRef(from, portal), "leads_to", KnownWorld.mapRef(to), episode);
            }

            /**
             * Experience is credited only to an attack that killed exactly one thing, because
             * with two dead there is no telling which was worth what.
             */
            private void attack(JsonNode detail, JsonNode state, JsonNode effect) {
                List<Integer> killed = new ArrayList<>();
                for (JsonNode target : detail.path("targets")) {
                    if (target.path("killed").asBoolean() && target.has("mob")) {
                        killed.add(target.get("mob").asInt());
                    }
                }
                if (killed.isEmpty()) {
                    return;
                }
                if (state.has("level") && map > 0) {
                    huntedAt.computeIfAbsent(map, m -> new ArrayList<>()).add(state.get("level").asInt());
                }
                JsonNode exp = effect.path("exp");
                boolean levelledUp = changed(effect, "level");
                if (killed.size() == 1 && exp.isArray() && exp.size() == 2 && !levelledUp) {
                    int gained = exp.get(1).asInt() - exp.get(0).asInt();
                    if (gained > 0) {
                        assertTriple("monster:" + killed.get(0), "gives_exp", String.valueOf(gained), episode);
                    }
                }
            }

            /** What a drink did, and how low things had got before somebody reached for it. */
            private void potion(JsonNode detail, JsonNode state, JsonNode effect) {
                if (!detail.has("item")) {
                    return;
                }
                String item = "item:" + detail.get("item").asInt();
                JsonNode hp = effect.path("hp");
                JsonNode mp = effect.path("mp");
                boolean healed = hp.isArray() && hp.get(1).asInt() > hp.get(0).asInt();
                boolean restored = mp.isArray() && mp.get(1).asInt() > mp.get(0).asInt();
                if (healed) {
                    assertTriple(item, "restores_hp", "true", episode);
                    int max = state.path("maxhp").asInt();
                    if (max > 0) {
                        int percent = 100 * hp.get(0).asInt() / max;
                        assertTriple(item, "drunk_at_hp_percent",
                                String.valueOf(Math.min(100, percent / HP_BUCKET * HP_BUCKET)), episode);
                    }
                }
                if (restored) {
                    assertTriple(item, "restores_mp", "true", episode);
                }
            }

            /**
             * What a skill does when cast: each stat it raises, and how long it lasts. Items
             * that buff are left out - "item:N buffs" says nothing about when to use one.
             */
            private void buffs(JsonNode effect) {
                for (JsonNode buff : effect.path("buffs").path("gained")) {
                    if (!buff.path("skill").asBoolean()) {
                        continue;
                    }
                    String skill = "skill:" + buff.path("source").asInt();
                    buff.path("stats").fieldNames().forEachRemaining(
                            stat -> assertTriple(skill, "buffs", stat, episode));
                    if (buff.has("seconds")) {
                        assertTriple(skill, "lasts_seconds", String.valueOf(buff.get("seconds").asInt()), episode);
                    }
                }
            }

            /** Something the server showed: what an NPC said, a shop's shelves, a drop. */
            private void shown(JsonNode seen) {
                switch (seen.path("type").asText()) {
                    case "DialogueShown" -> {
                        int npcId = seen.path("npcId").asInt();
                        if (npcId <= 0) {
                            return;
                        }
                        String speaker = "npc:" + npcId;
                        if (map > 0) {
                            assertTriple(speaker, "talks_in", KnownWorld.mapRef(map), episode);
                        }
                        whatWasSaid(speaker, npcId, seen.path("text").asText());
                    }
                    case "ShopOpened" -> {
                        String npc = "npc:" + seen.path("npcId").asInt();
                        assertTriple(npc, "runs_shop", "true", episode);
                        for (JsonNode item : seen.path("items")) {
                            assertTriple(npc, "sells", "item:" + item.path("itemId").asInt(), episode);
                        }
                    }
                    case "QuestStateChanged" -> {
                        int quest = seen.path("questId").asInt();
                        seen.path("kills").fields().forEachRemaining(kill -> {
                            JsonNode count = kill.getValue();
                            if (quest > 0 && count.isArray() && count.size() == 2) {
                                assertTriple("quest:" + quest, "needs_kills",
                                        count.get(1).asInt() + " monster:" + kill.getKey(), episode);
                            }
                        });
                    }
                    case "DropAppeared" -> {
                        if (seen.has("fromMob")) {
                            String what = seen.has("meso") ? "meso" : "item:" + seen.path("item").asInt();
                            assertTriple("monster:" + seen.get("fromMob").asInt(), "drops", what, episode);
                        }
                    }
                    default -> {
                    }
                }
            }

            /**
             * What an NPC's words name, read exactly as an agent reads them when it is the one
             * being spoken to ({@code Agent.rememberWhatWasSaid}): what they want brought, where
             * and to whom they send you, and where somebody they mention can be found.
             */
            private void whatWasSaid(String speaker, int npcId, String text) {
                Instructions.Heard heard = Instructions.read(text);
                for (Instructions.ItemAsked item : heard.items()) {
                    assertTriple(speaker, "wants_first", item.quantity() + " item:" + item.itemId(), episode);
                }
                for (int to : heard.maps()) {
                    assertTriple(speaker, "sends_you_to", "map:" + to, episode);
                }
                for (int npc : heard.npcs()) {
                    if (npc != npcId) {
                        assertTriple(speaker, "sends_you_to", "npc:" + npc, episode);
                    }
                }
                if (heard.npcs().size() == 1 && heard.maps().size() == 1 && heard.npcs().get(0) != npcId) {
                    assertTriple("npc:" + heard.npcs().get(0), "present_in", "map:" + heard.maps().get(0), episode);
                }
            }

            /**
             * Once per session rather than per point: a job's skill choices are a habit worth
             * knowing, and how many points a single person spent in one sitting is not.
             */
            void finish() {
                skillPoints.forEach((job, bySkill) -> bySkill.keySet().forEach(
                        skill -> assertTriple("job:" + job, "puts_sp_into", "skill:" + skill, episode)));
                // The middle level, so that one stray kill on the way through does not say a
                // map is hunted at level one.
                huntedAt.forEach((where, levels) -> {
                    List<Integer> sorted = levels.stream().sorted().toList();
                    assertTriple(KnownWorld.mapRef(where), "hunted_at_level",
                            String.valueOf(sorted.get(sorted.size() / 2)), episode);
                });
            }
        }
    }

    private static boolean changed(JsonNode effect, String field) {
        return effect.path(field).isArray();
    }

    /**
     * Converts the recordings by hand:
     * <pre>java -cp ... agents.memory.Demonstrations agents-data/demonstrations demonstrated.mind</pre>
     */
    public static void main(String[] args) {
        Path from = Path.of(args.length > 0 ? args[0] : "agents-data/demonstrations");
        Path to = Path.of(args.length > 1 ? args[1] : "agents-data/demonstrated.mind");
        Read read = write(from, to);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sessions", read.sessions());
        report.put("lines", read.lines());
        report.put("beliefs", read.beliefs());
        report.put("corroborations", read.corroborations());
        report.put("abilityPoints", read.abilityPoints());
        report.put("unreadableLines", read.unreadable());
        System.out.println(report + " -> " + to.toAbsolutePath());
    }
}
