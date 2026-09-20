package agents.net;

import agents.protocol.ClientPackets;
import agents.protocol.ServerPackets;
import agents.protocol.ServerPackets.CharacterSummary;
import agents.protocol.ServerPackets.ChannelHandoff;
import agents.protocol.ServerPackets.LoginResult;
import agents.provision.StarterLook;
import net.opcodes.SendOpcode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Random;

/**
 * Walks an agent from a TCP connection to standing in a map, the way a real client does:
 * log in, list worlds, list characters, create one if the account is new, select it, then
 * reconnect to the channel the server hands back.
 *
 * Everything here is bootstrap, not gameplay. The agent's own cognition starts once
 * {@link #enterWorld} returns.
 */
public class LoginFlow {
    private static final Logger log = LoggerFactory.getLogger(LoginFlow.class);
    private static final Duration REPLY_TIMEOUT = Duration.ofSeconds(10);
    private static final int ADVENTURER = 1;
    private static final int TOS_NOT_ACCEPTED = 23;

    /** Empty for a client that is not pretending to have network adapters. */
    private static final String NO_MACS = "00-00-00-00-00-00";

    public record Credentials(String account, String password) {
    }

    public record InWorld(MapleSession session, PacketInbox inbox, CharacterSummary character) {
    }

    private final String loginHost;
    private final int loginPort;
    private final int world;
    private final int channel;
    private final Random random;

    public LoginFlow(String loginHost, int loginPort, int world, int channel, Random random) {
        this.loginHost = loginHost;
        this.loginPort = loginPort;
        this.world = world;
        this.channel = channel;
        this.random = random;
    }

    /**
     * @param characterName used only when the account has no characters yet
     * @return an open channel session with the character in a map
     */
    public InWorld enterWorld(Credentials credentials, String characterName) throws Exception {
        PacketInbox inbox = new PacketInbox();
        MapleSession login = MapleSession.connect(loginHost, loginPort, inbox).get();

        CharacterSummary character;
        ChannelHandoff handoff;
        try {
            authenticate(login, inbox, credentials);
            listWorlds(login, inbox);
            character = chooseOrCreateCharacter(login, inbox, characterName);
            handoff = selectCharacter(login, inbox, character, credentials);
        } finally {
            login.close();
        }

        return connectToChannel(handoff, character);
    }

    private void authenticate(MapleSession session, PacketInbox inbox, Credentials credentials) throws Exception {
        session.send(ClientPackets.login(credentials.account(), credentials.password(), hwidNibbles()));

        LoginResult result = ServerPackets.decodeLoginStatus(expect(inbox, SendOpcode.LOGIN_STATUS));

        if (!result.success() && result.reason() == TOS_NOT_ACCEPTED) {
            // Every freshly auto-registered account lands here once. A human would be
            // reading a wall of legal text; we click through it.
            session.send(ClientPackets.acceptTermsOfService());
            result = ServerPackets.decodeLoginStatus(expect(inbox, SendOpcode.LOGIN_STATUS));
        }

        if (!result.success()) {
            // 5 is "no such account", which on a server with AUTOMATIC_REGISTER off is a
            // configuration problem rather than a bug here - say so plainly.
            throw new IllegalStateException("Login rejected for " + credentials.account()
                    + ", reason " + result.reason());
        }

        log.debug("Logged in as {} (account {})", result.accountName(), result.accountId());
    }

    private void listWorlds(MapleSession session, PacketInbox inbox) throws Exception {
        session.send(ClientPackets.serverListRequest());
        expect(inbox, SendOpcode.SERVERLIST);
        inbox.clear();      // the rest of the world list, then the end marker

        session.send(ClientPackets.serverStatusRequest(world));
        expect(inbox, SendOpcode.SERVERSTATUS);
    }

    private CharacterSummary chooseOrCreateCharacter(MapleSession session, PacketInbox inbox, String name)
            throws Exception {
        session.send(ClientPackets.characterListRequest(world, channel));
        List<CharacterSummary> characters =
                ServerPackets.decodeCharacterList(expect(inbox, SendOpcode.CHARLIST));

        if (!characters.isEmpty()) {
            CharacterSummary existing = characters.get(0);
            log.debug("Using existing character {} (level {}, map {})",
                    existing.name(), existing.level(), existing.mapId());
            return existing;
        }

        log.info("Account has no characters, creating {}", name);
        boolean male = random.nextBoolean();
        StarterLook look = StarterLook.random(random, male);
        session.send(ClientPackets.createCharacter(name, ADVENTURER, look.face(), look.hair(),
                look.hairColor(), look.skin(), look.top(), look.bottom(), look.shoes(),
                look.weapon(), male));

        PacketInbox.Received reply = inbox.await(REPLY_TIMEOUT, SendOpcode.ADD_NEW_CHAR_ENTRY,
                SendOpcode.DELETE_CHAR_RESPONSE);
        if (reply == null) {
            throw new IllegalStateException("No reply to character creation for " + name);
        }
        if (reply.opcode() == SendOpcode.DELETE_CHAR_RESPONSE.getValue()) {
            throw new IllegalStateException("Server rejected character creation for " + name
                    + " - the name may be taken or the appearance invalid");
        }

        // Rather than decode the creation reply, ask for the list again: one fewer packet
        // layout to keep in step with the server, and it proves the character persisted.
        session.send(ClientPackets.characterListRequest(world, channel));
        List<CharacterSummary> created =
                ServerPackets.decodeCharacterList(expect(inbox, SendOpcode.CHARLIST));
        if (created.isEmpty()) {
            throw new IllegalStateException("Created " + name + " but the character list is still empty");
        }
        return created.get(0);
    }

    private ChannelHandoff selectCharacter(MapleSession session, PacketInbox inbox,
                                           CharacterSummary character, Credentials credentials)
            throws Exception {
        session.send(ClientPackets.selectCharacter(character.id(), NO_MACS, hostString(credentials)));
        return ServerPackets.decodeServerIp(expect(inbox, SendOpcode.SERVER_IP));
    }

    private InWorld connectToChannel(ChannelHandoff handoff, CharacterSummary character) throws Exception {
        // The server reports the address it is bound to, which inside Docker is not an address
        // we can reach. We already know the host that worked for the login server.
        String host = loginHost;
        PacketInbox inbox = new PacketInbox();
        MapleSession channelSession = MapleSession.connect(host, handoff.port(), inbox).get();
        channelSession.send(ClientPackets.playerLoggedIn(handoff.characterId()));

        log.info("{} entered the world on port {}", character.name(), handoff.port());
        return new InWorld(channelSession, inbox, character);
    }

    private net.packet.InPacket expect(PacketInbox inbox, SendOpcode opcode) throws InterruptedException {
        PacketInbox.Received received = inbox.await(REPLY_TIMEOUT, opcode);
        if (received == null) {
            throw new IllegalStateException("Timed out waiting for " + opcode);
        }
        return received.packet();
    }

    /**
     * A fresh, distinct machine per agent. With stock config the anti-multiclient coordinator
     * is off, but distinct hwids keep the door open to turning it on.
     */
    private byte[] hwidNibbles() {
        byte[] nibbles = new byte[4];
        random.nextBytes(nibbles);
        return nibbles;
    }

    private String hostString(Credentials credentials) {
        // Must match [0-9A-F]{12}_[0-9A-F]{8}; derived from the account so it is stable
        // across runs, which is what a real machine looks like.
        int hash = credentials.account().hashCode();
        return String.format("%012X_%08X", Math.abs((long) hash) % 0xFFFFFFFFFFFFL, Math.abs(hash));
    }
}
