package net.packet.logging;

import client.Character;
import client.Client;
import client.Job;
import client.inventory.Inventory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import config.YamlConfig;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufOutPacket;
import net.packet.OutPacket;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackInfo;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.life.Monster;
import server.maps.MapleMap;

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
