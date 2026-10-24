package agents.control;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveViewTest {

    @Test
    void idsAreNamedForPeopleWatching() {
        String said = LiveView.readable("heading for map:105040302 to hunt monster:3210450");

        assertFalse(said.contains("map:105040302"), said);
        assertTrue(said.contains("Sleepy Dungeon"), said);
        assertEquals("nothing to name here", LiveView.readable("nothing to name here"));
    }

    @Test
    void aMapIsDrawnFromItsFloorsRopesAndDoors() {
        Map<String, Object> geometry = LiveView.geometry(105040302);

        assertFalse(((List<?>) geometry.get("floors")).isEmpty());
        assertFalse(((List<?>) geometry.get("ropes")).isEmpty());
        assertTrue(((List<?>) geometry.get("doors")).stream()
                .anyMatch(door -> "in00".equals(((Map<?, ?>) door).get("name"))));
        assertEquals(false, geometry.get("swim"));
        assertEquals(true, LiveView.geometry(230010001).get("swim"));
    }
}
