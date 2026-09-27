package agents.body;

import agents.world.WorldModel;
import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import tools.StringUtil;

import java.awt.Point;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Notices monsters walking into the agent, and works out how much it hurt.
 *
 * <p>This is the client's job, not the server's, and not the mind's either. In this game the
 * client watches its own character's hitbox, and when a monster's body overlaps it the client
 * decides the damage and reports it; the server takes that number and subtracts it. Agents
 * never did, so for the whole life of the project nothing could hurt them, and "monsters are
 * dangerous" was a thing no agent could ever find out.
 *
 * <p>Like {@link agents.world.MapGeometry}, this reads only what every player's client loads:
 * a monster's touch strength and level from {@code Mob.wz}. It is the body reacting, not the
 * mind knowing - the mind learns it was hurt the way a player does, from its health going
 * down, and from being told what walked into it.
 *
 * <p>The damage is an approximation of the client's own formula, not a copy of it: the touch
 * strength, a random spread, what the agent's defence soaks up, and a little off for being
 * higher level than the monster. It lands a snail's touch on a fresh character at around eight.
 */
public final class Touch {

    /** What a monster's body does to whoever it walks into. */
    public record MobBody(int touchDamage, int level, boolean hurtsOnTouch) {
        static final MobBody HARMLESS = new MobBody(0, 0, false);
    }

    /** Whoever touched the agent, and how hard: what the client reports and the mind is told. */
    public record Contact(int objectId, int monsterId, int damage, boolean facingLeft) {
    }

    /**
     * How close a monster has to be to count as touching. Monster sizes vary, and the
     * client uses each one's own sprite bounds; a fixed box is the honest simplification.
     */
    static final int REACH_X = 35;
    static final int REACH_Y = 45;

    /**
     * After a hit the client makes its character untouchable for a moment and flashes it.
     * Without this a monster standing on an agent would hit it every tick.
     */
    static final long INVINCIBLE_MILLIS = 2000;

    private static final Map<Integer, MobBody> BODIES = new HashMap<>();

    private final Random random;
    private final BodyLookup bodies;
    private long untouchableUntil;

    /** How to find out what a kind of monster's body does, by id. */
    @FunctionalInterface
    public interface BodyLookup {
        MobBody of(int monsterId);
    }

    public Touch(Random random) {
        this(random, Touch::bodyOf);
    }

    /** For tests, which cannot load Mob.wz. */
    public Touch(Random random, BodyLookup bodies) {
        this.random = random;
        this.bodies = bodies;
    }

    /**
     * Whether anything is touching the agent right now, and how hard.
     *
     * @param defence what the agent's equipment soaks up
     */
    public Optional<Contact> check(WorldModel world, int defence, long nowMillis) {
        if (world.isDead() || world.hp() < 0 || nowMillis < untouchableUntil) {
            return Optional.empty();
        }
        Point self = world.selfPosition();
        for (WorldModel.Entity monster : world.visibleMonsters()) {
            Point at = monster.position();
            if (Math.abs(at.x - self.x) > REACH_X || Math.abs(at.y - self.y) > REACH_Y) {
                continue;
            }
            MobBody body = bodies.of(monster.typeId());
            if (!body.hurtsOnTouch()) {
                continue;
            }
            untouchableUntil = nowMillis + INVINCIBLE_MILLIS;
            int damage = damage(body, defence, world.level());
            // Knocked away from whatever did it.
            return Optional.of(new Contact(monster.objectId(), monster.typeId(), damage, at.x > self.x));
        }
        return Optional.empty();
    }

    int damage(MobBody body, int defence, int level) {
        double spread = 0.6 + random.nextDouble() * 0.2;
        double raw = body.touchDamage() * spread - defence / 2.0;
        if (level > body.level()) {
            raw *= Math.max(0.5, 1 - 0.02 * (level - body.level()));
        }
        return Math.max(1, (int) Math.round(raw));
    }

    public static synchronized MobBody bodyOf(int monsterId) {
        return BODIES.computeIfAbsent(monsterId, Touch::load);
    }

    private static MobBody load(int monsterId) {
        DataProvider provider = DataProviderFactory.getDataProvider(WZFiles.MOB);
        Data monster = provider.getData(StringUtil.getLeftPaddedStr(monsterId + ".img", '0', 11));
        if (monster == null || monster.getChildByPath("info") == null) {
            return MobBody.HARMLESS;
        }
        Data info = monster.getChildByPath("info");
        boolean friendly = DataTool.getIntConvert("damagedByMob", info, 0) == 1;
        boolean bodyAttack = DataTool.getIntConvert("bodyAttack", info, 1) != 0;
        return new MobBody(DataTool.getIntConvert("PADamage", info, 0),
                DataTool.getIntConvert("level", info, 1),
                bodyAttack && !friendly);
    }
}
