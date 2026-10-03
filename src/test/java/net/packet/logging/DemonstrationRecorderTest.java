package net.packet.logging;

import client.Character;
import client.Client;
import client.Job;
import client.Stat;
import client.inventory.Inventory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import config.YamlConfig;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufOutPacket;
import net.packet.OutPacket;
import net.opcodes.SendOpcode;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackInfo;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.life.Monster;
import server.maps.MapleMap;
import server.maps.Portal;
import tools.PacketCreator;
import tools.Pair;

import java.awt.Point;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DemonstrationRecorderTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private String characters;
    private String bots;
    private String directory;
    private Client client;
    private Character chr;
    private MapleMap map;

    @BeforeEach
    void setUp() {
        characters = YamlConfig.config.server.DEMONSTRATION_CHARACTERS;
        bots = YamlConfig.config.server.DEMONSTRATION_BOT_ACCOUNTS;
        YamlConfig.config.server.DEMONSTRATION_BOT_ACCOUNTS = "agent*,asker,watcher";
        directory = YamlConfig.config.server.DEMONSTRATION_DIR;
        YamlConfig.config.server.DEMONSTRATION_CHARACTERS = "Magician, Someone";
        YamlConfig.config.server.DEMONSTRATION_DIR = dir.toString();

        // A fresh id per test, since sessions are kept per character for the life of the server.
        int id = (int) (System.nanoTime() & 0xFFFFFF);
        client = mock(Client.class);
        chr = mock(Character.class);
        map = mock(MapleMap.class);
        when(client.getPlayer()).thenReturn(chr);
        when(client.getAccountName()).thenReturn("admin");
        when(chr.getName()).thenReturn("Magician");
        when(chr.getId()).thenReturn(id);
        when(chr.getJob()).thenReturn(Job.BEGINNER);
        when(chr.getMap()).thenReturn(map);
        when(chr.getMapId()).thenReturn(10000);
        when(chr.getPosition()).thenReturn(new Point(100, 200));
        when(chr.getLevel()).thenReturn(1);
        when(chr.getInt()).thenReturn(4);
        when(chr.getRemainingAp()).thenReturn(5);
        when(chr.getInventory(any())).thenReturn(mock(Inventory.class));
    }

    @AfterEach
    void tearDown() {
        YamlConfig.config.server.DEMONSTRATION_CHARACTERS = characters;
        YamlConfig.config.server.DEMONSTRATION_BOT_ACCOUNTS = bots;
        YamlConfig.config.server.DEMONSTRATION_DIR = directory;
    }

    @Test
    void nobodyUnlistedIsRecorded() {
        when(chr.getName()).thenReturn("Agent0");

        assertNull(DemonstrationRecorder.before(client, op(RecvOpcode.DISTRIBUTE_AP), body(p -> {
            p.writeInt(0);
            p.writeInt(256);
        })));
    }

    @Test
    void everyoneMeansEveryPersonButNotTheAgents() {
        YamlConfig.config.server.DEMONSTRATION_CHARACTERS = "*";
        when(chr.getName()).thenReturn("Stranger");

        assertNotNull(DemonstrationRecorder.before(client, op(RecvOpcode.DISTRIBUTE_AP), body(p -> {
            p.writeInt(0);
            p.writeInt(256);
        })), "a person on any account is recorded");

        when(client.getAccountName()).thenReturn("agent3");
        when(chr.getName()).thenReturn("Agent3");
        assertNull(DemonstrationRecorder.before(client, op(RecvOpcode.DISTRIBUTE_AP), body(p -> {
            p.writeInt(0);
            p.writeInt(256);
        })), "an agent is not");

        when(client.getAccountName()).thenReturn("watcher");
        assertNull(DemonstrationRecorder.before(client, op(RecvOpcode.DISTRIBUTE_AP), new byte[8]),
                "nor is the watcher");
    }

    @Test
    void spendingApRecordsTheChoiceAndItsEffect() throws IOException {
        var before = DemonstrationRecorder.before(client, op(RecvOpcode.DISTRIBUTE_AP), body(p -> {
            p.writeInt(0);
            p.writeInt(256);
        }));
        when(chr.getInt()).thenReturn(5);
        when(chr.getRemainingAp()).thenReturn(4);
        DemonstrationRecorder.after(client, before);

        List<JsonNode> lines = lines();
        assertEquals("session_start", lines.get(0).get("act").asText());
        assertEquals("Magician", lines.get(0).get("detail").get("name").asText());

        JsonNode spent = lines.get(1);
        assertEquals("distribute_ap", spent.get("act").asText());
        assertEquals("int", spent.get("detail").get("stat").asText());
        assertEquals("[4,5]", spent.get("effect").get("int").toString());
        assertEquals("[5,4]", spent.get("effect").get("ap").toString());
        assertNull(spent.get("effect").get("level"), "only what changed is an effect");
    }

    @Test
    void anAttackNamesItsTargetsAndWhatDied() throws IOException {
        Monster snail = mock(Monster.class);
        when(snail.getId()).thenReturn(100100);
        when(snail.getHp()).thenReturn(8);
        when(snail.isAlive()).thenReturn(true);
        when(map.getMonsterByOid(7)).thenReturn(snail);

        var before = DemonstrationRecorder.before(client, op(RecvOpcode.CLOSE_RANGE_ATTACK), new byte[0]);
        AttackInfo attack = new AttackInfo();
        attack.skill = 0;
        attack.targets = Map.of(7, new AttackTarget((short) 0, List.of(12)));
        DemonstrationRecorder.noteAttack(chr, attack);
        when(map.getMonsterByOid(7)).thenReturn(null);
        when(chr.getExp()).thenReturn(3);
        DemonstrationRecorder.after(client, before);

        JsonNode hit = lines().get(1);
        assertEquals("close_range_attack", hit.get("act").asText());
        JsonNode target = hit.get("detail").get("targets").get(0);
        assertEquals(100100, target.get("mob").asInt());
        assertEquals("[12]", target.get("damage").toString());
        assertTrue(target.get("killed").asBoolean());
        assertEquals("[0,3]", hit.get("effect").get("exp").toString());
    }

    @Test
    void takingADoorRecordsWhichDoor() throws IOException {
        var before = DemonstrationRecorder.before(client, op(RecvOpcode.CHANGE_MAP), body(p -> {
            p.writeByte(0);
            p.writeInt(-1);
            p.writeString("east00");
            p.writeByte(0);
            p.writeShort(0);
        }));
        when(chr.getMapId()).thenReturn(20000);
        DemonstrationRecorder.after(client, before);

        JsonNode door = lines().get(1);
        assertEquals("east00", door.get("detail").get("portal").asText());
        assertEquals("[10000,20000]", door.get("effect").get("map").toString());
    }

    @Test
    void movementIsSampledRatherThanRecordedWhole() {
        byte[] step = new byte[0];
        var first = DemonstrationRecorder.before(client, op(RecvOpcode.MOVE_PLAYER), step);
        DemonstrationRecorder.after(client, first);

        assertNotNull(first);
        assertNull(DemonstrationRecorder.before(client, op(RecvOpcode.MOVE_PLAYER), step));
    }

    @Test
    void noiseIsNotRecorded() {
        assertNull(DemonstrationRecorder.before(client, op(RecvOpcode.HEAL_OVER_TIME), new byte[0]));
    }

    @Test
    void aTypedReplyToAnNpcIsReadAsText() throws IOException {
        var before = DemonstrationRecorder.before(client, op(RecvOpcode.NPC_TALK_MORE), body(p -> {
            p.writeByte(2);
            p.writeByte(1);
            p.writeString("maple");
        }));
        DemonstrationRecorder.after(client, before);

        JsonNode reply = lines().get(1);
        assertEquals("maple", reply.get("detail").get("text").asText());
        assertNull(reply.get("detail").get("selection"));
    }

    @Test
    void whatAnNpcSaysInAnswerGoesWithTheAnswer() throws IOException {
        var before = DemonstrationRecorder.before(client, op(RecvOpcode.NPC_TALK), body(p -> p.writeInt(1000)));
        DemonstrationRecorder.sent(client, PacketCreator.getNPCTalk(2000, (byte) 0, "Bring me 10 snail shells.", "00 01", (byte) 0));
        DemonstrationRecorder.after(client, before);

        JsonNode talk = lines().get(1);
        JsonNode said = talk.get("saw").get(0);
        assertEquals("DialogueShown", said.get("type").asText());
        assertEquals(2000, said.get("npcId").asInt());
        assertEquals("Bring me 10 snail shells.", said.get("text").asText());
    }

    @Test
    void somethingShownOutsideAnyActionIsALineOfItsOwn() throws IOException {
        DemonstrationRecorder.after(client, DemonstrationRecorder.before(client, op(RecvOpcode.CHANGE_CHANNEL), new byte[0]));
        DemonstrationRecorder.sent(client, PacketCreator.serverNotice(5, "The ship is arriving."));

        JsonNode notice = lines().get(2);
        assertEquals("saw", notice.get("act").asText());
        assertEquals("NoticeShown", notice.get("detail").get("type").asText());
    }

    @Test
    void aDropSaysWhichMonsterItCameFrom() throws IOException {
        Monster snail = mock(Monster.class);
        when(snail.getId()).thenReturn(100100);
        when(snail.isAlive()).thenReturn(true);
        when(map.getMonsterByOid(7)).thenReturn(snail);
        var before = DemonstrationRecorder.before(client, op(RecvOpcode.CLOSE_RANGE_ATTACK), new byte[0]);
        AttackInfo attack = new AttackInfo();
        attack.targets = Map.of(7, new AttackTarget((short) 0, List.of(12)));
        DemonstrationRecorder.noteAttack(chr, attack);
        DemonstrationRecorder.after(client, before);

        // Laid out as PacketCreator.dropItemFromMapObject writes it.
        OutPacket drop = OutPacket.create(SendOpcode.DROP_ITEM_FROM_MAPOBJECT);
        drop.writeByte(1);
        drop.writeInt(55);
        drop.writeBool(false);
        drop.writeInt(4000019);
        drop.writeInt(0);
        drop.writeByte(0);
        drop.writePos(new Point(10, 20));
        drop.writeInt(7);
        DemonstrationRecorder.sent(client, drop);

        JsonNode dropped = lines().get(2).get("detail");
        assertEquals("DropAppeared", dropped.get("type").asText());
        assertEquals(4000019, dropped.get("item").asInt());
        assertEquals(100100, dropped.get("fromMob").asInt());
        assertEquals("[10,20]", dropped.get("position").toString());
    }

    @Test
    void dyingIsRecordedOnceWithWhatLastHurtYou() throws IOException {
        var hit = DemonstrationRecorder.before(client, op(RecvOpcode.TAKE_DAMAGE), body(p -> {
            p.writeInt(0);
            p.writeByte(-1);
            p.writeByte(0);
            p.writeInt(50);
            p.writeInt(100100);
            p.writeInt(7);
        }));
        DemonstrationRecorder.after(client, hit);
        DemonstrationRecorder.sent(client, PacketCreator.updatePlayerStats(List.of(new Pair<>(Stat.HP, 0)), false, chr));
        DemonstrationRecorder.sent(client, PacketCreator.updatePlayerStats(List.of(new Pair<>(Stat.HP, 0)), false, chr));

        List<JsonNode> lines = lines();
        assertEquals("mob_contact", lines.get(1).get("detail").get("from").asText());
        JsonNode died = lines.get(2);
        assertEquals("died", died.get("act").asText());
        assertEquals(100100, died.get("detail").get("lastHurtBy").asInt());
        assertEquals(3, lines.size(), "dying once is one death");
    }

    @Test
    void walkingIntoAMapRecordsItsDoorsAndWhoIsThere() throws IOException {
        Portal door = mock(Portal.class);
        when(door.getName()).thenReturn("east00");
        when(door.getTargetMapId()).thenReturn(20000);
        when(door.getPosition()).thenReturn(new Point(500, 0));
        Monster snail = mock(Monster.class);
        when(snail.getId()).thenReturn(100100);
        when(snail.isAlive()).thenReturn(true);
        when(map.getPortals()).thenReturn(List.of(door));
        when(map.getMapObjects()).thenReturn(List.of(snail, snail));

        var before = DemonstrationRecorder.before(client, op(RecvOpcode.CHANGE_MAP), new byte[0]);
        when(chr.getMapId()).thenReturn(20000);
        DemonstrationRecorder.after(client, before);

        JsonNode arrived = lines().get(1).get("arrived");
        assertEquals(20000, arrived.get("map").asInt());
        assertEquals("east00", arrived.get("portals").get(0).get("name").asText());
        assertEquals(2, arrived.get("monsters").get("100100").asInt());
    }

    @Test
    void leavingClosesTheSession() throws IOException {
        DemonstrationRecorder.after(client, DemonstrationRecorder.before(client, op(RecvOpcode.CHANGE_CHANNEL), new byte[0]));
        DemonstrationRecorder.left(client, chr, "logged_out");
        DemonstrationRecorder.sent(client, PacketCreator.serverNotice(5, "nobody is listening"));

        List<JsonNode> lines = lines();
        assertEquals("session_end", lines.get(lines.size() - 1).get("act").asText());
        assertEquals("logged_out", lines.get(lines.size() - 1).get("detail").get("how").asText());
    }

    private static short op(RecvOpcode opcode) {
        return (short) opcode.getValue();
    }

    private static byte[] body(Consumer<OutPacket> content) {
        OutPacket out = new ByteBufOutPacket();
        content.accept(out);
        return out.getBytes();
    }

    private List<JsonNode> lines() throws IOException {
        Path folder = dir.resolve("Magician");
        try (Stream<Path> files = Files.list(folder)) {
            Path file = files.findFirst().orElseThrow();
            return Files.readAllLines(file).stream().map(line -> {
                try {
                    return JSON.readTree(line);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }
    }
}
