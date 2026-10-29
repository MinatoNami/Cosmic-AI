package agents.world;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The boat between Victoria Island and Orbis, as anybody who has played knows it: who sells the
 * ticket, who takes it, and the rooms that carry you over.
 *
 * Learned only by riding, half of it was never learned. Agents crossed from Ellinia, where the
 * ticket seller and Cherry stand side by side, and never back: in Orbis the ticket is sold in
 * the station hall, the platform usher sends you on, and Rini takes the ticket on a platform
 * past her. Three agents due their third job could not get between El Nath and Victoria for
 * days, each knowing no way over the sea.
 */
public final class Crossings {

    private Crossings() {
    }

    /**
     * A ride somebody gives from one map to another: for a ticket bought from somebody standing
     * in {@code sellerMap}, or free when {@code ticket} is 0. {@code town} is where it heads, as
     * the menus on the way name it.
     */
    public record Ride(int npc, int from, int to, String town, int ticket, int seller, int sellerMap) {
    }

    public static final List<Ride> RIDES = List.of(
            // Cherry, at Ellinia Station, for Joel's ticket.
            new Ride(1032008, 101000300, 101000301, "Orbis", 4031045, 1032007, 101000300),
            // The platform usher in Orbis Station's hall, to the platform for Ellinia.
            new Ride(2012006, 200000100, 200000110, "Ellinia", 0, 0, 0),
            // Rini, on that platform, for the ticket from the booth in the hall.
            new Ride(2012001, 200000111, 200000112, "Ellinia", 4031047, 2012000, 200000100));

    /** Rooms everybody in them is carried out of: the deck before takeoff, the boat, its cabin. */
    public static final Map<Integer, Integer> CARRIES = Map.of(
            101000301, 200090010,
            200090010, 200000100,
            200090011, 200000100,
            200000112, 200090000,
            200090000, 101000300,
            200090001, 101000300);

    public static Optional<Ride> rideBy(int npc) {
        return RIDES.stream().filter(ride -> ride.npc() == npc).findFirst();
    }
}
