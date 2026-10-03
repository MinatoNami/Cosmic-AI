package net.packet.logging;

import client.Character;
import client.Client;
import client.inventory.InventoryType;
import client.inventory.Item;
import com.fasterxml.jackson.databind.ObjectMapper;
import config.YamlConfig;
import io.netty.buffer.Unpooled;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackInfo;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.Monster;
import server.life.NPC;
import server.maps.MapItem;
import server.maps.MapObject;

import java.awt.Point;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Records how a person plays a character, so the agents have something to learn from besides
 * each other.
 *
 * Every packet a named character's client sends becomes one line: what was done, decoded as
 * far as it usefully can be, and what it changed - the character's map, position, HP, MP,
 * experience, level, stats and mesos, read before and after the handler ran. The pairing is
 * the point. "Put a point into INT" is an intent; "INT 4 -> 5" is its effect; a person reading
 * the file later, or an agent learning from it, needs both.
 *
 * <p>Server-side rather than a watching client, because a watcher only sees what the map is
 * told: it cannot see AP spent, a potion drunk out of sight, or which door was taken once the
 * player has gone through it. The server sees all of it, and only for the characters listed in
 * {@code DEMONSTRATION_CHARACTERS} - nobody else is recorded.
 *
 * <p>One file per login, under {@code DEMONSTRATION_DIR/<name>/}, opened with a full picture of
 * the character so that every later line can be read as a change against it.
 */
public final class DemonstrationRecorder {
    private static final Logger log = LoggerFactory.getLogger(DemonstrationRecorder.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    /** Sent constantly and meaning nothing a person chose to do. */
    private static final Set<RecvOpcode> NOISE = Set.of(
            RecvOpcode.PONG, RecvOpcode.MOVE_LIFE, RecvOpcode.NPC_ACTION, RecvOpcode.MOVE_PET,
            RecvOpcode.MOVE_SUMMON, RecvOpcode.MOVE_DRAGON, RecvOpcode.HEAL_OVER_TIME,
            RecvOpcode.CLIENT_ERROR, RecvOpcode.PLAYER_MAP_TRANSFER, RecvOpcode.FACE_EXPRESSION,
            RecvOpcode.AUTO_AGGRO, RecvOpcode.MOB_DAMAGE_MOB_FRIENDLY, RecvOpcode.MONSTER_BOMB,
            RecvOpcode.MOB_BANISH_PLAYER, RecvOpcode.CHANGE_KEYMAP, RecvOpcode.CHANGE_QUICKSLOT);

    /** Opcodes whose effect shows up in the inventory, which is too costly to diff on every step. */
    private static final Set<RecvOpcode> TOUCHES_INVENTORY = Set.of(
            RecvOpcode.ITEM_MOVE, RecvOpcode.ITEM_PICKUP, RecvOpcode.NPC_SHOP, RecvOpcode.USE_ITEM,
            RecvOpcode.QUEST_ACTION, RecvOpcode.NPC_TALK_MORE, RecvOpcode.USE_RETURN_SCROLL,
            RecvOpcode.USE_UPGRADE_SCROLL, RecvOpcode.ITEM_SORT,
            RecvOpcode.ITEM_SORT2, RecvOpcode.STORAGE, RecvOpcode.PLAYER_INTERACTION);

    /** One position a second is a path; thirty are a recording of a joystick. */
    private static final long MOVE_SAMPLE_MILLIS = 1000;

    private static final Map<Integer, Session> sessions = new ConcurrentHashMap<>();
    private static final Map<Short, RecvOpcode> byValue = Arrays.stream(RecvOpcode.values())
            .collect(Collectors.toMap(o -> (short) o.getValue(), o -> o, (a, b) -> a));

    /** Filled in mid-handler by the attack parser, collected once the handler returns. */
    private static final ThreadLocal<Map<String, Object>> pendingAttack = new ThreadLocal<>();

    private static volatile Recorded recorded;

    private DemonstrationRecorder() {
    }

    /** What the dispatcher holds onto between the two halves of one packet. */
    public record Before(Session session, RecvOpcode opcode, Map<String, Object> detail,
                         Map<String, Object> state, Map<Integer, Integer> inventory) {
    }

    /**
     * Called before the handler. Returns null for anyone not being recorded, which is
     * everyone but the listed characters, so the cost to the rest of the server is one lookup.
     */
    public static Before before(Client c, short opcodeValue, byte[] body) {
        Character chr = c.getPlayer();
        if (chr == null || !isRecorded(chr.getName())) {
            return null;
        }
        RecvOpcode opcode = byValue.get(opcodeValue);
        if (opcode == null || NOISE.contains(opcode)) {
            return null;
        }
        try {
            Session session = sessionFor(c, chr);
            if (opcode == RecvOpcode.MOVE_PLAYER && !session.timeToSampleMovement()) {
                return null;
            }
            pendingAttack.remove();
            Map<String, Object> detail = decode(opcode, new ByteBufInPacket(Unpooled.wrappedBuffer(body)), chr);
            boolean inventory = TOUCHES_INVENTORY.contains(opcode);
            return new Before(session, opcode, detail, state(chr), inventory ? inventory(chr) : null);
        } catch (Exception e) {
            log.warn("Could not record {} for {}", opcode, chr.getName(), e);
            return null;
        }
    }

    /** Called after the handler, with whatever {@link #before} returned. */
    public static void after(Client c, Before before) {
        if (before == null) {
            return;
        }
        Character chr = c.getPlayer();
        try {
            Map<String, Object> detail = before.detail();
            Map<String, Object> attack = pendingAttack.get();
            pendingAttack.remove();
            if (attack != null) {
                detail.putAll(attack);
                if (chr != null) {
                    markKills(chr, attack);
                }
            }

            Map<String, Object> line = new LinkedHashMap<>();
            line.put("at", Instant.now().toString());
            line.put("act", name(before.opcode()));
            if (!detail.isEmpty()) {
                line.put("detail", detail);
            }
            if (chr != null) {
                Map<String, Object> after = state(chr);
                line.put("state", after);
                Map<String, Object> effect = diff(before.state(), after);
                if (before.inventory() != null) {
                    Map<String, Object> items = itemDiff(before.inventory(), inventory(chr));
                    if (!items.isEmpty()) {
                        effect.put("items", items);
                    }
                }
                if (!effect.isEmpty()) {
                    line.put("effect", effect);
                }
            }
            before.session().write(line);
        } catch (Exception e) {
            log.warn("Could not record {} for {}", before.opcode(), chr != null ? chr.getName() : "?", e);
        }
    }

    /** Hooked into the attack parser, which is the only place the targets are known by name. */
    public static void noteAttack(Character chr, AttackInfo attack) {
        if (chr == null || !isRecorded(chr.getName())) {
            return;
        }
        List<Map<String, Object>> targets = new ArrayList<>();
        if (attack.targets != null) {
            for (Map.Entry<Integer, AttackTarget> target : attack.targets.entrySet()) {
                Map<String, Object> t = new LinkedHashMap<>();
                Monster mob = chr.getMap().getMonsterByOid(target.getKey());
                t.put("oid", target.getKey());
                if (mob != null) {
                    t.put("mob", mob.getId());
                    t.put("mobHp", mob.getHp());
                }
                t.put("damage", target.getValue().damageLines());
                targets.add(t);
            }
        }
        Map<String, Object> noted = new LinkedHashMap<>();
        noted.put("skill", attack.skill);
        noted.put("skillLevel", attack.skilllevel);
        noted.put("magic", attack.magic);
        noted.put("ranged", attack.ranged);
        noted.put("targets", targets);
        pendingAttack.set(noted);
    }

    @SuppressWarnings("unchecked")
    private static void markKills(Character chr, Map<String, Object> attack) {
        for (Map<String, Object> target : (List<Map<String, Object>>) attack.get("targets")) {
            Monster mob = chr.getMap().getMonsterByOid((Integer) target.get("oid"));
            target.put("killed", mob == null || !mob.isAlive());
        }
    }

    private static Map<String, Object> decode(RecvOpcode opcode, InPacket p, Character chr) {
        Map<String, Object> d = new LinkedHashMap<>();
        switch (opcode) {
            case CHANGE_MAP -> {
                if (p.available() > 0) {
                    d.put("fromDying", p.readByte() == 1);
                    d.put("targetMap", p.readInt());
                    d.put("portal", p.readString());
                } else {
                    d.put("fromCashShop", true);
                }
            }
            case CHANGE_MAP_SPECIAL -> {
                p.readByte();
                d.put("portal", p.readString());
            }
            case NPC_TALK -> {
                int oid = p.readInt();
                MapObject npc = chr.getMap().getMapObject(oid);
                if (npc instanceof NPC n) {
                    d.put("npc", n.getId());
                }
            }
            case NPC_TALK_MORE -> {
                d.put("lastType", p.readByte());
                byte action = p.readByte();
                d.put("action", action);
                if (p.available() >= 4) {
                    d.put("selection", p.readInt());
                } else if (p.available() > 2) {
                    d.put("text", p.readString());
                } else if (p.available() > 0) {
                    d.put("selection", (int) p.readUnsignedByte());
                }
            }
            case QUEST_ACTION -> {
                byte action = p.readByte();
                d.put("action", switch (action) {
                    case 0 -> "restore_lost_item";
                    case 1 -> "start";
                    case 2 -> "complete";
                    case 3 -> "forfeit";
                    case 4 -> "start_scripted";
                    case 5 -> "complete_scripted";
                    default -> Byte.toString(action);
                });
                d.put("quest", (int) p.readShort());
                if ((action == 1 || action == 2 || action == 4 || action == 5) && p.available() >= 4) {
                    d.put("npc", p.readInt());
                }
            }
            case NPC_SHOP -> {
                byte mode = p.readByte();
                switch (mode) {
                    case 0 -> {
                        p.readShort();
                        d.put("buy", p.readInt());
                        d.put("quantity", (int) p.readShort());
                    }
                    case 1 -> {
                        p.readShort();
                        d.put("sell", p.readInt());
                        d.put("quantity", (int) p.readShort());
                    }
                    case 2 -> d.put("recharge", true);
                    case 3 -> d.put("leave", true);
                    default -> d.put("mode", mode);
                }
            }
            case USE_ITEM, USE_RETURN_SCROLL -> {
                p.readInt();
                p.readShort();
                d.put("item", p.readInt());
            }
            case DISTRIBUTE_AP -> {
                p.readInt();
                d.put("stat", apStat(p.readInt()));
            }
            case AUTO_DISTRIBUTE_AP -> d.put("auto", true);
            case DISTRIBUTE_SP -> {
                p.readInt();
                d.put("skill", p.readInt());
            }
            case SPECIAL_MOVE -> {
                p.readInt();
                d.put("skill", p.readInt());
                d.put("skillLevel", (int) p.readByte());
            }
            case ITEM_MOVE -> {
                p.skip(4);
                InventoryType type = InventoryType.getByType(p.readByte());
                short src = p.readShort();
                short dst = p.readShort();
                d.put("inventory", type != null ? type.name().toLowerCase() : "?");
                Item item = type != null ? chr.getInventory(src < 0 ? InventoryType.EQUIPPED : type).getItem(src) : null;
                if (item != null) {
                    d.put("item", item.getItemId());
                }
                d.put("from", (int) src);
                d.put("to", (int) dst);
                d.put("move", dst == 0 ? "drop" : dst < 0 ? "equip" : src < 0 ? "unequip" : "rearrange");
            }
            case ITEM_PICKUP -> {
                p.readInt();
                p.readByte();
                p.readPos();
                int oid = p.readInt();
                MapObject drop = chr.getMap().getMapObject(oid);
                if (drop instanceof MapItem item) {
                    if (item.getMeso() > 0) {
                        d.put("meso", item.getMeso());
                    } else {
                        d.put("item", item.getItemId());
                    }
                }
            }
            case TAKE_DAMAGE -> {
                p.readInt();
                byte from = p.readByte();
                p.readByte();
                d.put("damage", p.readInt());
                d.put("from", from == -1 ? "touch" : from == -2 ? "map" : "skill");
                if (from != -3 && from != -4 && p.available() >= 4) {
                    d.put("mob", p.readInt());
                }
            }
            case GENERAL_CHAT -> d.put("text", p.readString());
            case WHISPER -> {
                byte mode = p.readByte();
                if (mode == 6) {
                    d.put("to", p.readString());
                    d.put("text", p.readString());
                } else {
                    d.put("mode", mode);
                }
            }
            default -> {
                // Named, timed and diffed is still worth having for anything not decoded here.
            }
        }
        return d;
    }

    private static String apStat(int mask) {
        return switch (mask) {
            case 64 -> "str";
            case 128 -> "dex";
            case 256 -> "int";
            case 512 -> "luk";
            case 2048 -> "maxhp";
            case 8192 -> "maxmp";
            default -> Integer.toString(mask);
        };
    }

    private static Map<String, Object> state(Character chr) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("map", chr.getMapId());
        Point pos = chr.getPosition();
        if (pos != null) {
            s.put("x", pos.x);
            s.put("y", pos.y);
        }
        s.put("level", chr.getLevel());
        s.put("exp", chr.getExp());
        s.put("job", chr.getJob().getId());
        s.put("hp", chr.getHp());
        s.put("maxhp", chr.getMaxHp());
        s.put("mp", chr.getMp());
        s.put("maxmp", chr.getMaxMp());
        s.put("str", chr.getStr());
        s.put("dex", chr.getDex());
        s.put("int", chr.getInt());
        s.put("luk", chr.getLuk());
        s.put("ap", chr.getRemainingAp());
        s.put("sp", chr.getRemainingSp());
        s.put("meso", chr.getMeso());
        s.put("fame", chr.getFame());
        return s;
    }

    /** Item id to total held, across every tab including what is worn. */
    private static Map<Integer, Integer> inventory(Character chr) {
        Map<Integer, Integer> held = new TreeMap<>();
        for (InventoryType type : InventoryType.values()) {
            if (type == InventoryType.UNDEFINED || type == InventoryType.CANHOLD) {
                continue;
            }
            for (Item item : chr.getInventory(type).list()) {
                held.merge(item.getItemId(), (int) item.getQuantity(), Integer::sum);
            }
        }
        return held;
    }

    private static Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> field : after.entrySet()) {
            Object was = before.get(field.getKey());
            if (!Objects.equals(was, field.getValue())) {
                changed.put(field.getKey(), Arrays.asList(was, field.getValue()));
            }
        }
        return changed;
    }

    private static Map<String, Object> itemDiff(Map<Integer, Integer> before, Map<Integer, Integer> after) {
        Map<String, Object> changed = new TreeMap<>();
        Set<Integer> ids = new TreeSet<>(before.keySet());
        ids.addAll(after.keySet());
        for (int id : ids) {
            int delta = after.getOrDefault(id, 0) - before.getOrDefault(id, 0);
            if (delta != 0) {
                changed.put(Integer.toString(id), delta);
            }
        }
        return changed;
    }

    private static Map<String, Object> portrait(Character chr) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", chr.getName());
        p.put("id", chr.getId());
        p.put("gm", chr.gmLevel());
        p.put("state", state(chr));
        List<List<Integer>> worn = new ArrayList<>();
        for (Item item : chr.getInventory(InventoryType.EQUIPPED).list()) {
            worn.add(List.of((int) item.getPosition(), item.getItemId()));
        }
        p.put("equipped", worn);
        p.put("items", inventory(chr));
        Map<String, Integer> skills = new TreeMap<>();
        chr.getSkills().forEach((skill, entry) -> skills.put(Integer.toString(skill.getId()), (int) entry.skillevel));
        p.put("skills", skills);
        return p;
    }

    private static Session sessionFor(Client c, Character chr) throws IOException {
        Session current = sessions.get(chr.getId());
        if (current != null && current.client == c) {
            return current;
        }
        if (current != null) {
            current.close();
        }
        Session fresh = Session.open(c, chr);
        sessions.put(chr.getId(), fresh);
        Map<String, Object> enter = new LinkedHashMap<>();
        enter.put("at", Instant.now().toString());
        enter.put("act", "session_start");
        enter.put("detail", portrait(chr));
        fresh.write(enter);
        return fresh;
    }

    /**
     * Re-read whenever the configured string changes, so the list can be edited in a running
     * server without the parse running on every packet.
     */
    private static boolean isRecorded(String name) {
        String configured = YamlConfig.config.server.DEMONSTRATION_CHARACTERS;
        if (configured == null || configured.isBlank()) {
            return false;
        }
        Recorded names = recorded;
        if (names == null || !configured.equals(names.from())) {
            names = new Recorded(configured, Arrays.stream(configured.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).map(String::toLowerCase)
                    .collect(Collectors.toUnmodifiableSet()));
            recorded = names;
        }
        return names.names().contains(name.toLowerCase());
    }

    private record Recorded(String from, Set<String> names) {
    }

    private static String name(RecvOpcode opcode) {
        return opcode.name().toLowerCase();
    }

    static final class Session {
        private final Client client;
        private final BufferedWriter out;
        private long lastMoveSample;

        private Session(Client client, BufferedWriter out) {
            this.client = client;
            this.out = out;
        }

        static Session open(Client c, Character chr) throws IOException {
            String root = YamlConfig.config.server.DEMONSTRATION_DIR;
            Path dir = Path.of(root == null || root.isBlank() ? "demonstrations" : root, chr.getName());
            Files.createDirectories(dir);
            Path file = dir.resolve(FILE_STAMP.format(Instant.now()) + ".jsonl");
            log.info("Recording {} to {}", chr.getName(), file.toAbsolutePath());
            return new Session(c, Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        }

        synchronized boolean timeToSampleMovement() {
            long now = System.currentTimeMillis();
            if (now - lastMoveSample < MOVE_SAMPLE_MILLIS) {
                return false;
            }
            lastMoveSample = now;
            return true;
        }

        synchronized void write(Map<String, Object> line) throws IOException {
            out.write(JSON.writeValueAsString(line));
            out.newLine();
            // Flushed every line: a session ends however the client chooses to end it, and a
            // half-written file is worth more than a tidy one that was never written.
            out.flush();
        }

        synchronized void close() {
            try {
                out.close();
            } catch (IOException e) {
                log.debug("Closing demonstration file", e);
            }
        }
    }
}
