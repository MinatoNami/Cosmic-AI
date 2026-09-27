package agents.world;

import agents.percept.Item;
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

    /** Where the portal a character arrives at stands, by map and portal number. */
    @FunctionalInterface
    public interface ArrivalPoints {
        Optional<Point> of(int mapId, int portalId);
    }

    private final ArrivalPoints arrivals;

    private final Map<Integer, Entity> monsters = new LinkedHashMap<>();
    private final Map<Integer, Entity> npcs = new LinkedHashMap<>();
    private final Map<Integer, Entity> drops = new LinkedHashMap<>();
    private final Map<Integer, String> players = new HashMap<>();

    /** Not cleared on leaving a map, unlike everything else here: a bag goes where you go. */
    private final Inventory inventory = new Inventory();
    private final Map<Integer, Point> positions = new HashMap<>();

    private int mapId = -1;
    private Point self = new Point(0, 0);
    private long inventoryFullAt = -1;

    /** What the bar over each monster the agent has hit last showed, as a percentage. */
    private final Map<Integer, Integer> monsterHealth = new HashMap<>();
    private final java.util.Set<Integer> mesoDrops = new java.util.HashSet<>();
    private int hp = -1;
    private int maxHp = -1;
    private int level = -1;
    private int characterId = -1;
    private int job = -1;
    private final Map<String, Integer> ownStats = new HashMap<>();

    public WorldModel() {
        this(MapGeometry::arrivalPoint);
    }

    /** For tests, which cannot load Map.wz. */
    public WorldModel(ArrivalPoints arrivals) {
        this.arrivals = arrivals;
    }

    public void update(Observation observation) {
        inventory.update(observation);
        switch (observation) {
            case Observation.SelfDescribed self -> {
                enterMap(self.mapId(), self.spawnPoint());
                this.characterId = self.characterId();
                this.level = self.level();
                this.job = self.job();
                absorb(self.stats());
            }
            case Observation.MapEntered entered -> enterMap(entered.mapId(), entered.spawnPoint());
            case Observation.StatsChanged changed -> absorb(changed.stats());
            case Observation.SkillChanged skill -> skillLevels.put(skill.skillId(), skill.level());
            case Observation.MonsterAppeared monster ->
                    monsters.put(monster.objectId(), new Entity(monster.objectId(), monster.monsterId(), monster.position()));
            case Observation.MonsterDied died -> forgetMonster(died.objectId());
            case Observation.MonsterVanished vanished -> forgetMonster(vanished.objectId());
            case Observation.MonsterHurt hurt -> {
                if (monsters.containsKey(hurt.objectId())) {
                    monsterHealth.put(hurt.objectId(), hurt.hpPercent());
                }
            }
            case Observation.DropTaken taken -> {
                drops.remove(taken.objectId());
                mesoDrops.remove(taken.objectId());
            }
            case Observation.NpcAppeared npc ->
                    npcs.put(npc.objectId(), new Entity(npc.objectId(), npc.npcId(), npc.position()));
            case Observation.DropAppeared drop -> {
                drops.put(drop.objectId(), new Entity(drop.objectId(), drop.itemId(), drop.position()));
                if (drop.meso()) {
                    mesoDrops.add(drop.objectId());
                }
            }
            case Observation.InventoryFull full -> inventoryFullAt = full.tick();
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
     *
     * The agent is put where the server put it, on the portal it arrived at. Nothing else
     * ever tells an agent where it is - the server does not echo your own movement back - so
     * without this it went on believing itself at its coordinates in the map it had just
     * left, and its first step claimed to start there. The server takes a client's word for
     * where it is, so that step quietly teleported it and hid the mistake.
     *
     * If the portal cannot be found the old position is kept: it is wrong, but so is every
     * other guess, and it is at least the one the server was last told.
     */
    private void enterMap(int newMapId, int spawnPoint) {
        arrivals.of(newMapId, spawnPoint).ifPresent(arrival -> self = arrival);
        if (newMapId != mapId) {
            monsters.clear();
            monsterHealth.clear();
            npcs.clear();
            drops.clear();
            mesoDrops.clear();
            players.clear();
            positions.clear();
        }
        mapId = newMapId;
    }

    /** Takes the agent's own numbers from whichever message carried them. */
    private void absorb(Map<String, Integer> stats) {
        ownStats.putAll(stats);
        if (stats.containsKey("HP")) {
            hp = stats.get("HP");
        }
        if (stats.containsKey("MAXHP")) {
            maxHp = stats.get("MAXHP");
        }
        if (stats.containsKey("LEVEL")) {
            level = stats.get("LEVEL");
        }
        if (stats.containsKey("JOB")) {
            job = stats.get("JOB");
        }
    }

    /**
     * One of the agent's own numbers - STR, MP, MAXMP and so on, under the server's names -
     * or -1 if it has not been told.
     */
    public int stat(String name) {
        return ownStats.getOrDefault(name, -1);
    }

    public int job() {
        return job;
    }

    /** Health at nothing: the "you have died" window is up and nothing else can be done. */
    /**
     * What each skill stands at, as far as the server has said since this session began.
     * A skill not heard of reads as 0, which after a restart may be less than it really is;
     * whoever spends points on it finds out when the server declines.
     */
    public int skillLevel(int skillId) {
        return skillLevels.getOrDefault(skillId, 0);
    }

    private final Map<Integer, Integer> skillLevels = new HashMap<>();

    public boolean isDead() {
        return hp == 0;
    }

    private void forgetMonster(int objectId) {
        monsters.remove(objectId);
        monsterHealth.remove(objectId);
    }

    /**
     * How much of this monster is left, as a percentage, if the agent has hit it - the server
     * only shows the bar to whoever did the hitting.
     */
    public Optional<Integer> monsterHealth(int objectId) {
        return Optional.ofNullable(monsterHealth.get(objectId));
    }

    public List<Entity> visibleMonsters() {
        return List.copyOf(monsters.values());
    }

    public Optional<Entity> nearestMonster() {
        return nearest(monsters.values());
    }

    public Optional<Entity> nearestDrop() {
        return nearest(drops.values());
    }

    /**
     * The nearest drop worth trying to pick up: anything, unless the bag was full recently,
     * in which case only money, which takes no room. Tried again after a while, in case room
     * has been made since.
     */
    public Optional<Entity> nearestDropWorthTaking(long tick) {
        boolean full = inventoryFullAt >= 0 && tick - inventoryFullAt < FULL_FOR_TICKS;
        // A quest item is worth taking whatever the bag says: room is made for it rather than
        // it being left for want of room. Thirty Dark Marbles are the whole of a second-job test.
        return full
                ? nearest(drops.values().stream()
                        .filter(d -> mesoDrops.contains(d.objectId()) || isQuestDrop(d)).toList())
                : nearest(drops.values().stream().filter(d -> roomFor(d) || isQuestDrop(d)).toList());
    }

    /** A drop on the ground that a quest needs. */
    public boolean isQuestDrop(Entity drop) {
        return !mesoDrops.contains(drop.objectId()) && QuestItems.isQuestItem(drop.typeId());
    }

    /** Every drop in sight that a quest needs and there is no room for yet. */
    public List<Entity> questDropsWithoutRoom() {
        return drops.values().stream().filter(d -> isQuestDrop(d) && !roomFor(d)).toList();
    }

    /**
     * Whether there is room for this drop, once the bags are known: money always fits, and
     * anything else fits if the bag it goes in has a free slot. A full etc bag is no reason
     * to walk past a potion.
     */
    private boolean roomFor(Entity drop) {
        return mesoDrops.contains(drop.objectId())
                || !inventory.known()
                || !inventory.isFull(Item.typeOf(drop.typeId()));
    }

    /** Whether the server said the bag was full within the last while, or the bags are. */
    public boolean inventoryFull(long tick) {
        return (inventoryFullAt >= 0 && tick - inventoryFullAt < FULL_FOR_TICKS)
                || inventory.anyBagFull();
    }

    /**
     * Forgets being told the bag was full, once room has been made - by selling, say - so
     * the agent goes back to picking things up straight away rather than in ten minutes.
     */
    public void madeRoom() {
        inventoryFullAt = -1;
    }

    public Inventory inventory() {
        return inventory;
    }

    /** Ten minutes at a 600ms tick before trying an item again. */
    private static final long FULL_FOR_TICKS = 1000;

    private Optional<Entity> nearest(Iterable<Entity> candidates) {
        List<Entity> all = new ArrayList<>();
        candidates.forEach(all::add);
        return all.stream().min(Comparator.comparingDouble(e -> e.position().distance(self)));
    }

    public Optional<Entity> nearestNpc() {
        return nearest(npcs.values());
    }

    public List<Entity> visibleNpcs() {
        return List.copyOf(npcs.values());
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
