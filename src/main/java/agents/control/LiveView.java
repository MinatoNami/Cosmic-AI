package agents.control;

import agents.Agent;
import agents.memory.DecisionRecord;
import agents.mind.Policy;
import agents.percept.Item;
import agents.trace.Labels;
import agents.world.MapGeometry;
import agents.world.WorldModel;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What each agent is seeing and thinking, for people watching - the broadcast page.
 *
 * Read from the page's thread while the agents go on playing, so a snapshot can catch a map
 * mid-update; it is simply taken again. Names come from {@link Labels}, which is for display
 * only: nothing here goes back to an agent.
 */
final class LiveView {

    private LiveView() {
    }

    /** One agent, as the page draws it. */
    static Map<String, Object> of(String name, Agent agent, boolean alive, Map<String, Agent> everyone) {
        for (int attempt = 0; ; attempt++) {
            try {
                return snapshot(name, agent, alive, everyone);
            } catch (RuntimeException caughtMidUpdate) {
                if (attempt >= 2) {
                    Map<String, Object> partial = new LinkedHashMap<>();
                    partial.put("name", name);
                    partial.put("alive", alive);
                    return partial;
                }
            }
        }
    }

    private static Map<String, Object> snapshot(String name, Agent agent, boolean alive, Map<String, Agent> everyone) {
        WorldModel world = agent.world();
        long now = System.currentTimeMillis();
        int map = world.mapId();
        int level = world.level();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", name);
        view.put("alive", alive);
        view.put("disposition", agent.disposition().name());
        view.put("policy", agent.policyName());
        view.put("job", jobName(world.job()));
        view.put("level", level);
        view.put("hp", world.hp());
        view.put("maxHp", world.maxHp());
        view.put("mp", world.stat("MP"));
        view.put("maxMp", world.stat("MAXMP"));
        view.put("exp", world.stat("EXP"));
        view.put("expNeeded", level > 0 ? constants.game.ExpTable.getExpNeededForLevel(level) : 0);
        view.put("meso", world.inventory().meso());
        view.put("mapId", map);
        view.put("mapName", label("map:" + map));
        view.put("swim", map > 0 && MapGeometry.isSwim(map));
        view.put("self", point(world.selfPosition()));
        view.put("minutesWithoutProgress", agent.minutesWithoutProgress());

        List<Map<String, Object>> monsters = new ArrayList<>();
        for (WorldModel.Entity monster : world.visibleMonsters()) {
            if (monsters.size() >= 60) {
                break;
            }
            Map<String, Object> m = point(monster.position());
            m.put("name", label("monster:" + monster.typeId()));
            world.monsterHealth(monster.objectId()).ifPresent(hp -> m.put("hp", hp));
            monsters.add(m);
        }
        view.put("monsters", monsters);
        view.put("npcs", world.visibleNpcs().stream().map(npc -> {
            Map<String, Object> n = point(npc.position());
            n.put("name", label("npc:" + npc.typeId()));
            return n;
        }).toList());
        view.put("drops", world.visibleDrops().stream().limit(40).map(drop -> point(drop.position())).toList());
        List<Map<String, Object>> others = new ArrayList<>();
        everyone.forEach((otherName, other) -> {
            if (other != agent && other.world().mapId() == map && other.world().selfPosition() != null) {
                Map<String, Object> o = point(other.world().selfPosition());
                o.put("name", otherName);
                others.add(o);
            }
        });
        view.put("others", others);

        List<DecisionRecord> decisions = agent.mind().recentDecisions();
        view.put("goal", decisions.isEmpty() ? "" : readable(decisions.get(decisions.size() - 1).goal()));
        List<String> recent = new ArrayList<>();
        for (DecisionRecord decision : decisions) {
            String goal = readable(decision.goal());
            if (recent.isEmpty() || !recent.get(recent.size() - 1).equals(goal)) {
                recent.add(goal);
            }
        }
        view.put("recent", recent.subList(Math.max(0, recent.size() - 6), recent.size()));

        agent.lastThought().ifPresent(thought -> view.put("thought", thought(thought, now)));
        view.put("heard", agent.recentlyHeard().stream().map(heard -> {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("from", heard.from());
            h.put("text", readable(heard.subject() + " " + heard.predicate().replace('_', ' ') + " " + heard.object()));
            h.put("secondsAgo", (now - heard.atMillis()) / 1000);
            return h;
        }).toList());
        view.put("inventory", inventory(world));
        return view;
    }

    private static Map<String, Object> thought(Policy.Thought thought, long now) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("goal", readable(thought.goal()));
        t.put("leaning", thought.leaning());
        t.put("headingFor", thought.headingFor().isEmpty() ? "" : readable(thought.headingFor()));
        t.put("why", readable(thought.why()));
        t.put("learned", thought.learned().stream().map(LiveView::readable).toList());
        t.put("secondsAgo", (now - thought.atMillis()) / 1000);
        return t;
    }

    /** What it carries to use and to sell, by name, the most of each first. */
    private static List<Map<String, Object>> inventory(WorldModel world) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (int type : new int[] {Item.USE, Item.ETC, Item.SETUP}) {
            for (Item item : world.inventory().carried(type)) {
                counts.merge(item.itemId(), item.quantity(), Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(12)
                .map(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", label("item:" + e.getKey()));
                    item.put("qty", e.getValue());
                    return item;
                })
                .toList();
    }

    /** A map's floors, ropes and doors, to draw it by. */
    static Map<String, Object> geometry(int mapId) {
        Map<String, Object> geometry = new LinkedHashMap<>();
        List<int[]> floors = MapGeometry.groundIn(mapId).stream()
                .map(g -> new int[] {g.x1(), g.y1(), g.x2(), g.y2()}).toList();
        List<int[]> ropes = MapGeometry.climbsIn(mapId).stream()
                .map(c -> new int[] {c.x(), c.top(), c.bottom()}).toList();
        List<Map<String, Object>> doors = MapGeometry.usablePortalsIn(mapId).stream().map(p -> {
            Map<String, Object> d = point(p.position());
            d.put("name", p.name());
            return d;
        }).toList();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (int[] f : floors) {
            minX = Math.min(minX, Math.min(f[0], f[2]));
            maxX = Math.max(maxX, Math.max(f[0], f[2]));
            minY = Math.min(minY, Math.min(f[1], f[3]));
            maxY = Math.max(maxY, Math.max(f[1], f[3]));
        }
        geometry.put("mapId", mapId);
        geometry.put("name", label("map:" + mapId));
        geometry.put("swim", MapGeometry.isSwim(mapId));
        geometry.put("floors", floors);
        geometry.put("ropes", ropes);
        geometry.put("doors", doors);
        geometry.put("bounds", floors.isEmpty() ? new int[] {-500, -500, 500, 500} : new int[] {minX, minY, maxX, maxY});
        return geometry;
    }

    private static final Pattern REF = Pattern.compile("\\b(map|npc|monster|item):(\\d+)\\b");

    /** Ids named, where the game has a name for them: "map:105040302" reads "Sleepy Dungeon II". */
    static String readable(String text) {
        if (text == null) {
            return "";
        }
        Matcher ref = REF.matcher(text);
        StringBuilder out = new StringBuilder();
        while (ref.find()) {
            String name = Labels.forRef(ref.group());
            ref.appendReplacement(out, Matcher.quoteReplacement(name == null || name.isBlank() ? ref.group() : name));
        }
        ref.appendTail(out);
        return out.toString();
    }

    private static String label(String ref) {
        String name = Labels.forRef(ref);
        return name == null || name.isBlank() ? ref : name;
    }

    private static String jobName(int job) {
        client.Job known = client.Job.getById(job);
        if (known == null) {
            return String.valueOf(job);
        }
        String raw = known.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    private static Map<String, Object> point(Point at) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("x", at == null ? 0 : at.x);
        p.put("y", at == null ? 0 : at.y);
        return p;
    }
}
