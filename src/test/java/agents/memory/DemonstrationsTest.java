package agents.memory;

import client.Character;
import client.Client;
import client.Job;
import client.inventory.Inventory;
import config.YamlConfig;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufOutPacket;
import net.packet.OutPacket;
import net.packet.logging.DemonstrationRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.maps.MapleMap;

import java.awt.Point;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DemonstrationsTest {

    @TempDir
    Path dir;

    private SemanticMemory read(String... lines) throws IOException {
        Path folder = Files.createDirectories(dir.resolve("Magician"));
        Files.write(folder.resolve("20261003-000000.jsonl"), List.of(lines));
        SemanticMemory semantic = new SemanticMemory();
        Demonstrations.readInto(dir, new EpisodicMemory(), semantic);
        return semantic;
    }

    private static boolean holds(SemanticMemory semantic, String subject, String predicate, String object) {
        return semantic.liveBeliefs().stream().anyMatch(b -> b.subject().equals(subject)
                && b.predicate().equals(predicate) && b.object().equals(object)
                && b.provenance() == Belief.Provenance.HEARSAY);
    }

    @Test
    void aDoorWalkedThroughLeadsWhereItTookThem() throws IOException {
        SemanticMemory semantic = read(
                "{\"act\":\"change_map\",\"detail\":{\"portal\":\"east00\"},\"state\":{\"map\":20000},"
                        + "\"effect\":{\"map\":[10000,20000]}}");

        assertTrue(holds(semantic, "portal:10000/east00", "leads_to", "map:20000"));
    }

    @Test
    void aMapWalkedIntoIsItsDoorsAndWhoIsInIt() throws IOException {
        SemanticMemory semantic = read(
                "{\"act\":\"change_map\",\"state\":{\"map\":20000},\"arrived\":{\"map\":20000,"
                        + "\"portals\":[{\"name\":\"west00\",\"to\":10000}],\"monsters\":{\"100100\":3},"
                        + "\"npcs\":[2000]}}");

        assertTrue(holds(semantic, "map:20000", "has_door", "west00"));
        assertTrue(holds(semantic, "monster:100100", "present_in", "map:20000"));
        assertTrue(holds(semantic, "npc:2000", "present_in", "map:20000"));
        assertFalse(semantic.liveBeliefs().stream().anyMatch(b -> b.predicate().equals("leads_to")),
                "a door looked at is not a door walked through");
    }

    @Test
    void aKillIsWorthWhatItGave() throws IOException {
        SemanticMemory semantic = read(
                "{\"act\":\"close_range_attack\",\"detail\":{\"targets\":[{\"mob\":100100,\"killed\":true}]},"
                        + "\"state\":{\"map\":40000,\"level\":3},\"effect\":{\"exp\":[10,13]}}");

        assertTrue(holds(semantic, "monster:100100", "gives_exp", "3"));
        assertTrue(holds(semantic, "map:40000", "hunted_at_level", "3"));
    }

    @Test
    void twoDeadAtOnceAreNotCreditedWithTheExperience() throws IOException {
        SemanticMemory semantic = read(
                "{\"act\":\"close_range_attack\",\"detail\":{\"targets\":[{\"mob\":100100,\"killed\":true},"
                        + "{\"mob\":100101,\"killed\":true}]},\"state\":{\"map\":40000,\"level\":3},"
                        + "\"effect\":{\"exp\":[10,20]}}");

        assertFalse(semantic.liveBeliefs().stream().anyMatch(b -> b.predicate().equals("gives_exp")));
    }

    @Test
    void whatTheServerShowedBecomesWhatTheAgentsWouldHaveSeen() throws IOException {
        SemanticMemory semantic = read(
                "{\"act\":\"npc_talk\",\"state\":{\"map\":100000000},\"saw\":["
                        + "{\"type\":\"ShopOpened\",\"npcId\":9010001,\"items\":[{\"index\":0,\"itemId\":2000000,\"price\":50}]},"
                        + "{\"type\":\"DialogueShown\",\"npcId\":9010001,\"text\":\"hi\",\"style\":0}]}",
                "{\"act\":\"saw\",\"detail\":{\"type\":\"DropAppeared\",\"item\":4000019,\"fromMob\":100100}}");

        assertTrue(holds(semantic, "npc:9010001", "runs_shop", "true"));
        assertTrue(holds(semantic, "npc:9010001", "sells", "item:2000000"));
        assertTrue(holds(semantic, "npc:9010001", "talks_in", "map:100000000"));
        assertTrue(holds(semantic, "monster:100100", "drops", "item:4000019"));
    }

    @Test
    void whatAnNpcAskedForIsReadAsAnAgentWouldReadIt() throws IOException {
        SemanticMemory semantic = read(
                "{\"act\":\"npc_talk\",\"state\":{\"map\":102000000},\"saw\":[{\"type\":\"DialogueShown\","
                        + "\"npcId\":1022000,\"text\":\"Bring me #b30 #t4031013##k from #p1072000# around #m102020300#.\","
                        + "\"style\":0}]}");

        assertTrue(holds(semantic, "npc:1022000", "wants_first", "30 item:4031013"));
        assertTrue(holds(semantic, "npc:1072000", "present_in", "map:102020300"));
    }

    @Test
    void questKillCountsAndBuffsBecomeWhatTheyAre() throws IOException {
        SemanticMemory semantic = read(
                "{\"act\":\"close_range_attack\",\"saw\":[{\"type\":\"QuestStateChanged\",\"questId\":1037,"
                        + "\"state\":1,\"kills\":{\"100100\":[3,10]}}]}",
                "{\"act\":\"special_move\",\"detail\":{\"skill\":2001003},\"effect\":{\"buffs\":{\"gained\":["
                        + "{\"source\":2001003,\"skill\":true,\"seconds\":60,\"stats\":{\"magic_guard\":15}}]}}}");

        assertTrue(holds(semantic, "quest:1037", "needs_kills", "10 monster:100100"));
        assertTrue(holds(semantic, "skill:2001003", "buffs", "magic_guard"));
        assertTrue(holds(semantic, "skill:2001003", "lasts_seconds", "60"));
    }

    @Test
    void potionsHurtsAndDeaths() throws IOException {
        SemanticMemory semantic = read(
                "{\"act\":\"take_damage\",\"detail\":{\"damage\":12,\"mob\":100101},\"state\":{\"map\":40000}}",
                "{\"act\":\"use_item\",\"detail\":{\"item\":2000000},\"state\":{\"maxhp\":100},"
                        + "\"effect\":{\"hp\":[34,84]}}",
                "{\"act\":\"died\",\"detail\":{\"lastHurtBy\":100101,\"level\":4,\"map\":40000}}");

        assertTrue(holds(semantic, "monster:100101", "hurts_you", "true"));
        assertTrue(holds(semantic, "item:2000000", "restores_hp", "true"));
        assertTrue(holds(semantic, "item:2000000", "drunk_at_hp_percent", "30"));
        assertTrue(holds(semantic, "map:40000", "killed_you_at_level", "4"));
    }

    @Test
    void abilityPointsBecomeSharesOfTheJob() throws IOException {
        String intPoint = "{\"act\":\"distribute_ap\",\"detail\":{\"stat\":\"int\"},\"state\":{\"job\":200},"
                + "\"effect\":{\"ap\":[2,1]}}";
        String lukPoint = "{\"act\":\"distribute_ap\",\"detail\":{\"stat\":\"luk\"},\"state\":{\"job\":200},"
                + "\"effect\":{\"ap\":[1,0]}}";
        String refused = "{\"act\":\"distribute_ap\",\"detail\":{\"stat\":\"str\"},\"state\":{\"job\":200}}";
        SemanticMemory semantic = read(intPoint, intPoint, intPoint, lukPoint, refused);

        assertTrue(holds(semantic, "job:200", "ap_share_int", "75"));
        assertTrue(holds(semantic, "job:200", "ap_share_luk", "25"));
        assertFalse(semantic.liveBeliefs().stream().anyMatch(b -> b.predicate().equals("ap_share_str")),
                "a point the server did not take was not spent");
    }

    @Test
    void halfALineFromACrashIsSkippedNotFatal() throws IOException {
        Path folder = Files.createDirectories(dir.resolve("Magician"));
        Files.write(folder.resolve("20261003-000000.jsonl"), List.of(
                "{\"act\":\"take_damage\",\"detail\":{\"damage\":12,\"mob\":100101}}",
                "{\"act\":\"take_dam"));
        SemanticMemory semantic = new SemanticMemory();

        Demonstrations.Read read = Demonstrations.readInto(dir, new EpisodicMemory(), semantic);

        assertEquals(1, read.unreadable());
        assertTrue(holds(semantic, "monster:100101", "hurts_you", "true"));
    }

    @Test
    void theDatabaseSnapshotsAreNotARecording() throws IOException {
        Path folder = Files.createDirectories(dir.resolve("Magician"));
        Files.writeString(folder.resolve("snapshots.jsonl"), "{\"level\":1}\n");

        assertTrue(Demonstrations.recordings(dir).isEmpty());
    }

    /**
     * The recorder and this reader agree on a format neither declares anywhere else; this is
     * the test that fails when one of them changes without the other.
     */
    @Test
    void whatTheRecorderWritesIsWhatThisReads() throws IOException {
        String characters = YamlConfig.config.server.DEMONSTRATION_CHARACTERS;
        String directory = YamlConfig.config.server.DEMONSTRATION_DIR;
        YamlConfig.config.server.DEMONSTRATION_CHARACTERS = "Roundtrip";
        YamlConfig.config.server.DEMONSTRATION_DIR = dir.toString();
        try {
            Client client = mock(Client.class);
            Character chr = mock(Character.class);
            MapleMap map = mock(MapleMap.class);
            when(client.getPlayer()).thenReturn(chr);
            when(client.getAccountName()).thenReturn("roundtrip");
            when(chr.getName()).thenReturn("Roundtrip");
            when(chr.getId()).thenReturn((int) (System.nanoTime() & 0xFFFFFF));
            when(chr.getJob()).thenReturn(Job.MAGICIAN);
            when(chr.getMap()).thenReturn(map);
            when(chr.getMapId()).thenReturn(10000);
            when(chr.getPosition()).thenReturn(new Point(0, 0));
            when(chr.getInventory(any())).thenReturn(mock(Inventory.class));
            when(chr.getInt()).thenReturn(20);
            when(chr.getRemainingAp()).thenReturn(3);

            var spend = DemonstrationRecorder.before(client, (short) RecvOpcode.DISTRIBUTE_AP.getValue(), body(p -> {
                p.writeInt(0);
                p.writeInt(0x100);
            }));
            when(chr.getInt()).thenReturn(21);
            when(chr.getRemainingAp()).thenReturn(2);
            DemonstrationRecorder.after(client, spend);

            var walk = DemonstrationRecorder.before(client, (short) RecvOpcode.CHANGE_MAP.getValue(), body(p -> {
                p.writeByte(0);
                p.writeInt(-1);
                p.writeString("east00");
            }));
            when(chr.getMapId()).thenReturn(20000);
            DemonstrationRecorder.after(client, walk);
            DemonstrationRecorder.left(client, chr, "logged_out");
        } finally {
            YamlConfig.config.server.DEMONSTRATION_CHARACTERS = characters;
            YamlConfig.config.server.DEMONSTRATION_DIR = directory;
        }

        SemanticMemory semantic = new SemanticMemory();
        Demonstrations.Read read = Demonstrations.readInto(dir, new EpisodicMemory(), semantic);

        assertEquals(1, read.sessions());
        assertEquals(0, read.unreadable());
        assertTrue(holds(semantic, "job:200", "ap_share_int", "100"));
        assertTrue(holds(semantic, "portal:10000/east00", "leads_to", "map:20000"));
    }

    private static byte[] body(Consumer<OutPacket> content) {
        OutPacket out = new ByteBufOutPacket();
        content.accept(out);
        return out.getBytes();
    }
}
