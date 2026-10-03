package net.packet.logging;

import agents.percept.Observation;
import agents.percept.ObservationDecoder;
import client.Character;
import client.Client;
import client.inventory.InventoryType;
import client.inventory.Item;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import client.QuestStatus;
import config.YamlConfig;
import io.netty.buffer.Unpooled;
import net.opcodes.RecvOpcode;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.Packet;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackInfo;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.Monster;
import server.life.NPC;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.Portal;

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
 * {@code DEMONSTRATION_CHARACTERS}, or every human player when that is {@code *} - nobody
 * else is recorded.
 *
 * <p>It also records what the person was shown, because what somebody does only makes sense
 * against what they could see: the line an NPC said before they answered it, a shop's shelves
 * before they bought from it, what a monster dropped, what was in a map when they walked in.
 * Those are decoded by the agents' own {@link ObservationDecoder}, so a person's recording and
 * an agent's perception are written in the same terms and the one can be learned from as the
 * other. What was shown while handling an action is attached to that action as {@code saw};
 * what arrived on its own - somebody else talking, a drop landing late - is a line of its own.
 *
 * <p>One file per login, under {@code DEMONSTRATION_DIR/<name>/}, opened with a full picture of
 * the character so that every later line can be read as a change against it, and closed with
 * a {@code session_end} line when they leave.
 */
public final class DemonstrationRecorder {
    private static final Logger log = LoggerFactory.getLogger(DemonstrationRecorder.class);
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(
            new SimpleModule().addSerializer(Point.class, new StdSerializer<>(Point.class) {
                @Override
                public void serialize(Point point, JsonGenerator out, SerializerProvider provider) throws IOException {
                    out.writeStartArray();
                    out.writeNumber(point.x);
                    out.writeNumber(point.y);
                    out.writeEndArray();
                }
            }));
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

    /**
     * What the server tells a client that is worth keeping: what a person is shown, rather
     * than the bookkeeping of drawing it. Everything else is never decoded at all.
     */
    private static final Set<Integer> SHOWN = Set.of(
            SendOpcode.NPC_TALK, SendOpcode.OPEN_NPC_SHOP, SendOpcode.CHATTEXT, SendOpcode.WHISPER,
            SendOpcode.SERVERMESSAGE, SendOpcode.SHOW_STATUS_INFO, SendOpcode.SHOW_ITEM_GAIN_INCHAT,
            SendOpcode.STAT_CHANGED, SendOpcode.SPAWN_MONSTER, SendOpcode.SPAWN_MONSTER_CONTROL,
            SendOpcode.KILL_MONSTER, SendOpcode.SPAWN_PLAYER, SendOpcode.REMOVE_PLAYER_FROM_MAP,
            SendOpcode.KEYMAP, SendOpcode.UPDATE_SKILLS, SendOpcode.DROP_ITEM_FROM_MAPOBJECT
    ).stream().map(SendOpcode::getValue).collect(Collectors.toUnmodifiableSet());

    /**
     * Observations decoded but not written: bookkeeping that the action lines already say
     * better (stats), or that only feeds what is written (a monster appearing is how a drop
     * learns which monster it came from).
     */
    private static final Set<Class<?>> UNWRITTEN = Set.of(
            Observation.StatsChanged.class, Observation.MonsterAppeared.class,
            Observation.NpcAppeared.class, Observation.Unrecognised.class,
            Observation.ThingMoved.class, Observation.MonsterHurt.class,
            Observation.InventoryChanged.class, Observation.InventoryShown.class,
            Observation.SelfDescribed.class, Observation.MapEntered.class,
            Observation.DropAppeared.class, Observation.DropTaken.class);

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
     * Called before the handler. Returns null for anyone not being recorded, so the cost to
     * the rest of the server is one lookup.
     */
    public static Before before(Client c, short opcodeValue, byte[] body) {
        Character chr = c.getPlayer();
        if (chr == null || !isRecorded(chr.getName(), c.getAccountName())) {
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
            if (opcode == RecvOpcode.TAKE_DAMAGE && detail.get("mob") instanceof Integer mob) {
                session.lastHurtBy = mob;
            }
            boolean inventory = TOUCHES_INVENTORY.contains(opcode);
            Before before = new Before(session, opcode, detail, state(chr), inventory ? inventory(chr) : null);
            session.beginHandling();
            return before;
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
        List<Object> saw = before.session().endHandling();
        try {
            Map<String, Object> detail = before.detail();
            Map<String, Object> attack = pendingAttack.get();
            pendingAttack.remove();
            if (attack != null) {
                detail.putAll(attack);
                if (chr != null) {
                    markKills(chr, attack);
                }
                before.session().rememberTargets(attack);
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
                // Compared with the last map recorded rather than with the moment before the
                // packet, so that a warp nobody asked for - an event, a timer - is still noticed
                // at the next thing the person does.
                if (before.session().arrivedIn(chr.getMapId())) {
                    line.put("arrived", surroundings(chr));
                }
            }
            if (!saw.isEmpty()) {
                line.put("saw", saw);
            }
            before.session().write(line);
        } catch (Exception e) {
            log.warn("Could not record {} for {}", before.opcode(), chr != null ? chr.getName() : "?", e);
        }
    }

    /**
     * Called for every packet the server sends a client, inside the client's send lock, so one
     * client's packets arrive here one at a time and in order. Costs anyone not being recorded
     * one lookup.
     */
    public static void sent(Client c, Packet packet) {
        Character chr = c.getPlayer();
        if (chr == null) {
            return;
        }
        Session session = sessions.get(chr.getId());
        if (session == null || session.client != c) {
            return;
        }
        try {
            byte[] bytes = packet.getBytes();
            if (bytes.length < 2) {
                return;
            }
            InPacket p = new ByteBufInPacket(Unpooled.wrappedBuffer(bytes));
            int opcode = p.readShort() & 0xFFFF;
            if (!SHOWN.contains(opcode)) {
                return;
            }
            if (opcode == SendOpcode.DROP_ITEM_FROM_MAPOBJECT.getValue()) {
                Map<String, Object> drop = decodeDrop(p, session);
                if (drop != null) {
                    session.shown(drop);
                }
                return;
            }
            Observation observation = session.decoder.decode(session.tick++, opcode, p);
            while (observation != null) {
                shown(session, chr, observation);
                observation = session.decoder.hasFollowUp() ? session.decoder.takeFollowUp(session.tick++) : null;
            }
        } catch (Exception e) {
            log.warn("Could not record what {} was shown", chr.getName(), e);
        }
    }

    private static void shown(Session session, Character chr, Observation observation) throws IOException {
        if (observation instanceof Observation.MonsterAppeared appeared) {
            session.mobByOid.put(appeared.objectId(), appeared.monsterId());
        }
        if (observation instanceof Observation.StatsChanged changed && changed.stats().get("HP") instanceof Integer hp) {
            session.hpNowAt(hp, chr);
        }
        if (UNWRITTEN.contains(observation.getClass())) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> seen = JSON.convertValue(observation, LinkedHashMap.class);
        seen.remove("tick");
        Map<String, Object> typed = new LinkedHashMap<>();
        typed.put("type", observation.getClass().getSimpleName());
        typed.putAll(seen);
        if (observation instanceof Observation.MonsterDied died) {
            Integer mob = session.mobByOid.get(died.objectId());
            if (mob != null) {
                typed.put("mob", mob);
            }
        }
        session.shown(typed);
    }

    /**
     * A drop, with the one thing the agents' decoder leaves out: what dropped it. Drops a
     * person walks in on ({@code mod} 2) are part of the map rather than of anything that
     * happened, and are left to the map snapshot.
     *
     * @see tools.PacketCreator#dropItemFromMapObject
     */
    private static Map<String, Object> decodeDrop(InPacket p, Session session) {
        int mod = p.readUnsignedByte();
        if (mod == 2) {
            return null;
        }
        int oid = p.readInt();
        boolean meso = p.readByte() != 0;
        int itemOrAmount = p.readInt();
        p.readInt();
        p.readByte();
        Point at = p.readPos();
        int dropper = p.readInt();
        Map<String, Object> drop = new LinkedHashMap<>();
        drop.put("type", "DropAppeared");
        drop.put("objectId", oid);
        drop.put(meso ? "meso" : "item", itemOrAmount);
        drop.put("position", at);
        Integer mob = session.mobByOid.get(dropper);
        if (mob != null) {
            drop.put("fromMob", mob);
        }
        drop.put("dropper", dropper);
        return drop;
    }

    /**
     * Called as a client leaves, however it leaves: logging out, changing channel, going to the
     * cash shop, or the connection simply dropping. Closes the file with how things stood.
     */
    public static void left(Client c, Character chr, String how) {
        if (chr == null) {
            return;
        }
        Session session = sessions.get(chr.getId());
        if (session == null || session.client != c) {
            return;
        }
        sessions.remove(chr.getId(), session);
        try {
            Map<String, Object> end = new LinkedHashMap<>();
            end.put("at", Instant.now().toString());
            end.put("act", "session_end");
            end.put("detail", Map.of("how", how));
            end.put("state", state(chr));
            session.write(end);
        } catch (Exception e) {
            log.warn("Could not record {} leaving", chr.getName(), e);
        } finally {
            session.close();
        }
    }

    /** Hooked into the attack parser, which is the only place the targets are known by name. */
    public static void noteAttack(Character chr, AttackInfo attack) {
        // Anyone with an open session passed the check in before(); asking again here would
        // need the client, which the attack parser does not have to hand.
        if (chr == null || !sessions.containsKey(chr.getId())) {
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
                // Mirrors NPCMoreTalkHandler: what follows depends on what kind of question it
                // was, and only a text prompt (type 2) is answered with text.
                byte lastType = p.readByte();
                byte action = p.readByte();
                d.put("lastType", lastType);
                d.put("action", action);
                if (lastType == 2) {
                    if (action != 0) {
                        d.put("text", p.readString());
                    }
                } else if (p.available() >= 4) {
                    d.put("selection", p.readInt());
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
                if (action == 0 && p.available() >= 8) {
                    p.readInt();
                    d.put("item", p.readInt());
                } else if ((action == 1 || action == 2 || action == 4 || action == 5) && p.available() >= 4) {
                    d.put("npc", p.readInt());
                    if (action == 1 || action == 2) {
                        // Mirrors QuestActionHandler.isNpcNearby, which reads where the player
                        // stood, and then the reward a completion chose, when there is a choice.
                        if (p.available() >= 4) {
                            p.readShort();
                            p.readShort();
                        }
                        if (action == 2 && p.available() >= 2) {
                            d.put("rewardChoice", (int) p.readShort());
                        }
                    }
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
                // As TakeDamageHandler reads it: zero and up is one of the monster's attacks,
                // -1 and -2 are bumping into it with no attack involved, and -3 and -4 are the
                // map itself, with no monster at all.
                d.put("from", from >= 0 ? "mob_attack" : from >= -2 ? "mob_contact" : "map");
                d.put("fromCode", (int) from);
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
        Map<String, String> quests = new TreeMap<>();
        for (QuestStatus quest : chr.getStartedQuests()) {
            quests.put(Integer.toString(quest.getQuestID()), quest.getProgressData());
        }
        p.put("questsInProgress", quests);
        Map<String, List<Integer>> keys = new TreeMap<>();
        chr.getKeymap().forEach((key, binding) -> keys.put(Integer.toString(key), List.of(binding.getType(), binding.getAction())));
        p.put("keymap", keys);
        p.put("surroundings", surroundings(chr));
        return p;
    }

    /**
     * What a person sees on walking into a map: its doors and where they lead, who is standing
     * in it, and what is roaming it. Portals never reach the client as packets - it reads them
     * from its own copy of the map - so this is the only place they can be recorded from.
     */
    private static Map<String, Object> surroundings(Character chr) {
        Map<String, Object> around = new LinkedHashMap<>();
        around.put("map", chr.getMapId());
        if (chr.getMap() == null) {
            return around;
        }
        List<Map<String, Object>> doors = new ArrayList<>();
        for (Portal portal : chr.getMap().getPortals()) {
            if (portal.getTargetMapId() == 999999999 && portal.getScriptName() == null) {
                continue;   // spawn points: places to appear, not ways out
            }
            Map<String, Object> door = new LinkedHashMap<>();
            door.put("name", portal.getName());
            door.put("to", portal.getTargetMapId());
            if (portal.getScriptName() != null) {
                door.put("script", portal.getScriptName());
            }
            door.put("position", portal.getPosition());
            doors.add(door);
        }
        around.put("portals", doors);
        Map<String, Integer> monsters = new TreeMap<>();
        List<Integer> npcs = new ArrayList<>();
        int players = 0;
        for (MapObject object : chr.getMap().getMapObjects()) {
            if (object instanceof Monster mob && mob.isAlive()) {
                monsters.merge(Integer.toString(mob.getId()), 1, Integer::sum);
            } else if (object instanceof NPC npc) {
                npcs.add(npc.getId());
            } else if (object instanceof Character other && other != chr) {
                players++;
            }
        }
        around.put("monsters", monsters);
        around.put("npcs", npcs);
        around.put("otherPlayers", players);
        return around;
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
        fresh.arrivedIn(chr.getMapId());
        sessions.put(chr.getId(), fresh);
        Map<String, Object> enter = new LinkedHashMap<>();
        enter.put("at", Instant.now().toString());
        enter.put("act", "session_start");
        enter.put("detail", portrait(chr));
        fresh.write(enter);
        return fresh;
    }

    /**
     * Whether this character is one of the ones being recorded.
     *
     * {@code DEMONSTRATION_CHARACTERS} is either a list of names or {@code *}, which means every
     * person who plays - every character whose account is not one of the agents', as listed in
     * {@code DEMONSTRATION_BOT_ACCOUNTS}. Recording the agents too would feed their own
     * behaviour back to them as though somebody had shown it to them.
     *
     * <p>Both settings are re-read whenever they change, so they can be edited in a running
     * server without the parse running on every packet.
     */
    private static boolean isRecorded(String name, String account) {
        String configured = YamlConfig.config.server.DEMONSTRATION_CHARACTERS;
        if (configured == null || configured.isBlank()) {
            return false;
        }
        String bots = YamlConfig.config.server.DEMONSTRATION_BOT_ACCOUNTS;
        Recorded rules = recorded;
        if (rules == null || !configured.equals(rules.from()) || !Objects.equals(bots, rules.botsFrom())) {
            rules = new Recorded(configured, list(configured), bots, list(bots));
            recorded = rules;
        }
        if (rules.names().contains("*")) {
            return account != null && !isBot(account.toLowerCase(), rules.bots());
        }
        return rules.names().contains(name.toLowerCase());
    }

    /** Exact account names, or a prefix ending in {@code *}: "agent*" is agent0, agent1, ... */
    private static boolean isBot(String account, Set<String> bots) {
        for (String bot : bots) {
            if (bot.endsWith("*") ? account.startsWith(bot.substring(0, bot.length() - 1)) : account.equals(bot)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> list(String configured) {
        if (configured == null) {
            return Set.of();
        }
        return Arrays.stream(configured.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(String::toLowerCase)
                .collect(Collectors.toUnmodifiableSet());
    }

    private record Recorded(String from, Set<String> names, String botsFrom, Set<String> bots) {
    }

    private static String name(RecvOpcode opcode) {
        return opcode.name().toLowerCase();
    }

    static final class Session {
        private final Client client;
        private final BufferedWriter out;
        private final ObservationDecoder decoder = new ObservationDecoder();
        /** Monster object ids to monster ids, so that a drop or a death can say what it was. */
        private final Map<Integer, Integer> mobByOid = new ConcurrentHashMap<>();
        private long lastMoveSample;
        private long tick;
        private int lastMap = -1;
        private volatile int lastHurtBy;
        private boolean alive = true;
        /** The thread running this person's packet handler, while one is running. */
        private Thread handling;
        private final List<Object> sawWhileHandling = new ArrayList<>();

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

        synchronized void beginHandling() {
            handling = Thread.currentThread();
            sawWhileHandling.clear();
        }

        synchronized List<Object> endHandling() {
            handling = null;
            List<Object> saw = new ArrayList<>(sawWhileHandling);
            sawWhileHandling.clear();
            return saw;
        }

        /**
         * Something the person was shown. Shown while their own action was being handled, it
         * was the answer to that action and goes with it; shown at any other time, it happened
         * to them, and is a line of its own.
         */
        synchronized void shown(Map<String, Object> seen) throws IOException {
            if (handling == Thread.currentThread()) {
                sawWhileHandling.add(seen);
                return;
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("at", Instant.now().toString());
            line.put("act", "saw");
            line.put("detail", seen);
            write(line);
        }

        /** @return whether this is a different map from the last one recorded */
        synchronized boolean arrivedIn(int map) {
            boolean moved = map != lastMap;
            lastMap = map;
            return moved;
        }

        void rememberTargets(Map<String, Object> attack) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> targets = (List<Map<String, Object>>) attack.get("targets");
            for (Map<String, Object> target : targets) {
                if (target.get("mob") instanceof Integer mob) {
                    mobByOid.put((Integer) target.get("oid"), mob);
                }
            }
        }

        /**
         * Deaths are read from the HP the client is told about rather than from the hit that
         * caused them, because not every death is a hit: poison, a map hazard, a fall.
         */
        synchronized void hpNowAt(int hp, Character chr) throws IOException {
            if (hp > 0) {
                alive = true;
                return;
            }
            if (!alive) {
                return;
            }
            alive = false;
            Map<String, Object> died = new LinkedHashMap<>();
            died.put("at", Instant.now().toString());
            died.put("act", "died");
            Map<String, Object> detail = new LinkedHashMap<>();
            if (lastHurtBy != 0) {
                detail.put("lastHurtBy", lastHurtBy);
            }
            detail.put("level", chr.getLevel());
            detail.put("map", chr.getMapId());
            died.put("detail", detail);
            write(died);
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
