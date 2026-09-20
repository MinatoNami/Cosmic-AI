package agents.world;

import agents.percept.Observation;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What is around the agent right now.
 *
 * Distinct from semantic memory on purpose. Beliefs are things an agent holds to be lastingly
 * true and can be wrong about; this is a scratch view of the current map that is thrown away
 * the moment the agent leaves it. Positions live here precisely because they change several
 * times a second and would churn the belief graph to no purpose.
 *
 * Built only from observations, so it inherits the same honesty: if a monster was never
 * perceived, it is not here.
 */
public class WorldModel {

    public record Entity(int objectId, int typeId, Point position) {
    }

    private final Map<Integer, Entity> monsters = new LinkedHashMap<>();
    private final Map<Integer, Entity> npcs = new LinkedHashMap<>();
    private final Map<Integer, Entity> drops = new LinkedHashMap<>();
    private final Map<Integer, String> players = new HashMap<>();
    private final Map<Integer, Point> positions = new HashMap<>();

    private int mapId = -1;
    private Point self = new Point(0, 0);
    private int hp = -1;
    private int maxHp = -1;
    private int level = -1;
    private int characterId = -1;

    public void update(Observation observation) {
        switch (observation) {
            case Observation.SelfDescribed self -> {
                enterMap(self.mapId());
                this.characterId = self.characterId();
                this.level = self.level();
            }
            case Observation.MapEntered entered -> enterMap(entered.mapId());
            case Observation.StatsChanged changed -> {
                Map<String, Integer> stats = changed.stats();
                if (stats.containsKey("HP")) {
                    hp = stats.get("HP");
                }
                if (stats.containsKey("MAXHP")) {
                    maxHp = stats.get("MAXHP");
                }
                if (stats.containsKey("LEVEL")) {
                    level = stats.get("LEVEL");
                }
            }
            case Observation.MonsterAppeared monster ->
                    monsters.put(monster.objectId(), new Entity(monster.objectId(), monster.monsterId(), monster.position()));
            case Observation.MonsterDied died -> monsters.remove(died.objectId());
            case Observation.NpcAppeared npc ->
                    npcs.put(npc.objectId(), new Entity(npc.objectId(), npc.npcId(), npc.position()));
            case Observation.DropAppeared drop ->
                    drops.put(drop.objectId(), new Entity(drop.objectId(), drop.itemId(), drop.position()));
            case Observation.PlayerAppeared player -> players.put(player.characterId(), player.name());
            case Observation.PlayerLeft left -> {
                players.remove(left.characterId());
                positions.remove(left.characterId());
            }
            case Observation.ThingMoved moved -> {
                positions.put(moved.objectId(), moved.position());
                Entity monster = monsters.get(moved.objectId());
                if (monster != null) {
                    monsters.put(moved.objectId(), new Entity(monster.objectId(), monster.typeId(), moved.position()));
                }
            }
            default -> {
            }
        }
    }

    /**
     * Everything in the old map is gone, so the model is cleared. Anything worth keeping
     * should already have become a belief.
     */
    private void enterMap(int newMapId) {
        if (newMapId != mapId) {
            monsters.clear();
            npcs.clear();
            drops.clear();
            players.clear();
            positions.clear();
        }
        mapId = newMapId;
    }

    public Optional<Entity> nearestMonster() {
        return nearest(monsters.values());
    }

    public Optional<Entity> nearestDrop() {
        return nearest(drops.values());
    }

    private Optional<Entity> nearest(Iterable<Entity> candidates) {
        List<Entity> all = new ArrayList<>();
        candidates.forEach(all::add);
        return all.stream().min(Comparator.comparingDouble(e -> e.position().distance(self)));
    }

    /** Anything visible with this object id - a monster or a drop. */
    public Optional<Entity> byObjectId(int objectId) {
        Entity monster = monsters.get(objectId);
        return monster != null ? Optional.of(monster) : Optional.ofNullable(drops.get(objectId));
    }

    public Optional<PortalTarget> portalNamed(String name) {
        return portals().stream().filter(p -> p.name().equals(name)).findFirst();
    }

    public List<PortalTarget> portals() {
        return MapGeometry.usablePortalsIn(mapId).stream()
                .map(p -> new PortalTarget(p.name(), p.position()))
                .toList();
    }

    public record PortalTarget(String name, Point position) {
    }

    public void movedTo(Point position) {
        this.self = position;
    }

    public Point selfPosition() {
        return self;
    }

    public int mapId() {
        return mapId;
    }

    public int characterId() {
        return characterId;
    }

    public int hp() {
        return hp;
    }

    public int maxHp() {
        return maxHp;
    }

    public int level() {
        return level;
    }

    public Map<Integer, String> visiblePlayers() {
        return Map.copyOf(players);
    }

    public int monsterCount() {
        return monsters.size();
    }
}
