package agents.social;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shape of a position announcement, which is the claim an agent can accidentally
 * hear itself make.
 */
class SelfClaimTest {

    @Test
    void aPositionIsAnnouncedAsAPlayerIdRatherThanAsSelf() {
        String said = Claim.announce("player:9", "in_map", "map:40000");

        Claim heard = Claim.parse(said).orElseThrow();
        assertEquals("player:9", heard.subject());
        assertEquals("in_map", heard.predicate());
        assertEquals("map:40000", heard.object());
    }

    /**
     * The reason positions are not announced as "self": a listener would file it against its
     * own self and believe it was somewhere it has never been.
     */
    @Test
    void aClaimAboutSelfIsDistinguishableFromOneAboutAPlayer() {
        assertTrue(Claim.announce("player:9", "in_map", "map:1").contains("player:9"));
        assertEquals("self", Claim.parse("!know self in_map map:1").orElseThrow().subject());
    }
}
