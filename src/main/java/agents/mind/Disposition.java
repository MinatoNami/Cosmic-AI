package agents.mind;

/**
 * What an agent is inclined to do, so a population is not one agent copied N times.
 *
 * Identical agents are a dead end for the social side of this project: running the same
 * policy from the same start, they walk the same route, see the same things, and end up with
 * the same beliefs - so anything one announces, the others already know, and knowledge
 * transfer never gets a chance to show itself. Diverging behaviour produces diverging
 * knowledge, which is the precondition for having anything to teach each other.
 *
 * These are inclinations, not goals. A wanderer does not know that exploring is good; it
 * simply takes doors sooner than the others do.
 *
 * @param wanderlust  how readily it takes an unfamiliar door, 0 to 1
 * @param aggression  how far it will go out of its way for something to hit, 0 to 1
 * @param greed       how far it will go out of its way for something on the ground, 0 to 1
 * @param sociability how often it says what it knows, 0 to 1
 * @param curiosity   how readily it bothers an NPC, 0 to 1
 */
public record Disposition(String name, double wanderlust, double aggression, double greed,
                          double sociability, double curiosity) {

    /** Keeps moving on, so it is the one who has been somewhere the others have not. */
    public static final Disposition WANDERER =
            new Disposition("wanderer", 0.9, 0.2, 0.3, 0.5, 0.4);

    /** Stays where the monsters are, so it levels but sees little. */
    public static final Disposition FIGHTER =
            new Disposition("fighter", 0.1, 0.9, 0.3, 0.2, 0.1);

    /** Picks things up, and ends up knowing what drops where. */
    public static final Disposition FORAGER =
            new Disposition("forager", 0.3, 0.3, 0.9, 0.4, 0.3);

    /** Talks - to NPCs, and to anyone it has met. The one that spreads what others find. */
    public static final Disposition TALKER =
            new Disposition("talker", 0.5, 0.2, 0.2, 0.9, 0.9);

    private static final Disposition[] ROTATION = {WANDERER, FIGHTER, FORAGER, TALKER};

    /** Hands out dispositions in turn, so a small population covers all of them. */
    public static Disposition forAgent(int index) {
        return ROTATION[Math.floorMod(index, ROTATION.length)];
    }

    /** Decisions between portal attempts: keen wanderers try one far sooner. */
    public int portalReluctance() {
        return (int) Math.round(4 + (1 - wanderlust) * 56);
    }

    /** How far it will chase something worth hitting. */
    public int pursuitRange() {
        return (int) Math.round(120 + aggression * 680);
    }

    /** How far it will detour for something on the ground. */
    public int scavengeRange() {
        return (int) Math.round(40 + greed * 360);
    }

    /**
     * Decisions an agent will spend fighting and looting before it looks up.
     *
     * The reflex ladder tries loot, then monsters, then NPCs, then doors, and stops at the
     * first that matches - so the top two starve the rest whenever there is anything to hit.
     * Worse, they feed each other: killing a monster makes a drop, and a drop outranks a
     * monster. Ninety seconds of one agent came to ninety-six attacks and fifty-eight
     * pickups, which is eighty-eight per cent of its decisions and left no room to talk to
     * anybody or take a quest.
     *
     * Aggression buys a longer attention span, because grinding is a fighter's whole
     * character. It does not buy an unlimited one.
     */
    public int attentionSpan() {
        return (int) Math.round(20 + aggression * 60);
    }

    /** Steps between saying something. */
    public int shareInterval() {
        return (int) Math.round(80 - sociability * 60);
    }

    /**
     * Decisions an agent will keep making in one map without getting anywhere before it
     * starts looking for the door.
     *
     * This is what stops a fighter grinding the same field forever. Pursuit range is wide
     * enough that there is nearly always another monster in sight, so the reflex ladder never
     * falls through to the portal branch on its own - the agent is not stuck, it is being
     * rationally short-sighted, which is worse. Aggression buys patience, because a fighter
     * staying where the monsters are is the whole of its character; it just should not do so
     * for the rest of its life.
     */
    public int patience() {
        return (int) Math.round(120 + aggression * 240);
    }

    /** Steps between bothering an NPC. */
    public int talkInterval() {
        return (int) Math.round(120 - curiosity * 90);
    }
}
