package agents.mind;

import agents.percept.Observation;
import agents.world.SkillBook;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * When each of the agent's own buffs wears off.
 *
 * <p>Timed from the cast at first, on the length the skill data gives, and then by what the
 * server says, which beats the assumption: GIVE_BUFF confirms a cast took and says for how
 * long, CANCEL_BUFF says which stats went. A cast never confirmed did not take - short of
 * MP, or interrupted - and is tried again shortly, where it used to count as up for its whole
 * length while the agent went without. One ended early, by a dispel or anything else, goes
 * back up at once rather than when the timer says.
 */
public final class BuffClock {

    /** Recast this long before one wears off, so it never lapses between casts. */
    static final long RECAST_EARLY_MILLIS = 3000;
    /** The server answers a cast within this, or it did not take. */
    static final long CONFIRMED_WITHIN_MILLIS = 5000;
    /** And then it is tried again after this. */
    static final long FAILED_CAST_RETRY_MILLIS = 20_000;
    /** A recast ends the cast before it; a cancel this soon after a buff is given is that. */
    static final long RECAST_SETTLES_MILLIS = 2000;

    private final Map<Integer, Long> lastsUntil = new HashMap<>();
    /** For each buff the server confirmed: its two stat masks, and when it was given. */
    private final Map<Integer, long[]> stats = new HashMap<>();
    private int unconfirmed;
    private long unconfirmedAt;

    /** The first of these that is due a cast now. */
    public Optional<SkillBook.SkillLevel> due(List<SkillBook.SkillLevel> buffs, long now) {
        if (unconfirmed > 0 && now - unconfirmedAt > CONFIRMED_WITHIN_MILLIS) {
            lastsUntil.put(unconfirmed, now + FAILED_CAST_RETRY_MILLIS);
            unconfirmed = 0;
        }
        return Fighting.due(buffs, lastsUntil, now);
    }

    /** Cast just now, to last as long as the skill data says, until the server says otherwise. */
    public void cast(SkillBook.SkillLevel buff, long now) {
        lastsUntil.put(buff.skillId(), now + buff.seconds() * 1000L - RECAST_EARLY_MILLIS);
        unconfirmed = buff.skillId();
        unconfirmedAt = now;
    }

    /** What the server said about the agent's own buffs, if this is that. */
    public void heard(Observation observation, long now) {
        if (observation instanceof Observation.BuffGiven given && given.skillId() > 0) {
            lastsUntil.put(given.skillId(), now + given.millis() - RECAST_EARLY_MILLIS);
            stats.put(given.skillId(), new long[] {given.firstMask(), given.secondMask(), now});
            if (given.skillId() == unconfirmed) {
                unconfirmed = 0;
            }
        } else if (observation instanceof Observation.BuffCancelled cancelled) {
            stats.entrySet().removeIf(buff -> {
                long[] of = buff.getValue();
                boolean ended = now - of[2] > RECAST_SETTLES_MILLIS
                        && ((of[0] & cancelled.firstMask()) != 0 || (of[1] & cancelled.secondMask()) != 0);
                if (ended) {
                    lastsUntil.remove(buff.getKey());
                }
                return ended;
            });
        }
    }

    /** Dying takes every buff with it. */
    public void died() {
        lastsUntil.clear();
        stats.clear();
        unconfirmed = 0;
    }
}
