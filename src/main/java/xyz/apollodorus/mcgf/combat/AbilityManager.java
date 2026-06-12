package xyz.apollodorus.mcgf.combat;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;
import xyz.apollodorus.mcgf.world.TransientBlocks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The single server-tick driver for all of 达妮娅's world-anchored ability state: the
 * {@link TransientBlocks} registry (bridge + domain blocks), active {@link DaniyaDomain}s, and
 * one-shot / repeating scheduled effects (the 2a sky-meteor impact, the a3 black hole). It is driven
 * from {@code ServerTickEvents.END_SERVER_TICK} (see {@code MCGirlfriendMod}) — NOT from the entity's
 * own tick — so nothing leaks if 达妮娅 unloads, dies, or walks out of her own domain. State is static
 * (integrated/single server) and fully cleared by {@link #flushAll()} on shutdown.
 */
public final class AbilityManager {
    private AbilityManager() {}

    private static final TransientBlocks BLOCKS = new TransientBlocks();
    private static final Map<UUID, DaniyaDomain> DOMAINS = new HashMap<>();
    private static final List<Repeating> REPEATING = new ArrayList<>();
    private static final List<Delayed> DELAYED = new ArrayList<>();

    private record Repeating(ServerWorld world, long until, Runnable each) {}
    private record Delayed(ServerWorld world, long at, Runnable action) {}

    public static TransientBlocks blocks() { return BLOCKS; }

    /** Called once per server tick. Snapshots each list so effects may schedule more without CME. */
    public static void onServerTick(MinecraftServer server) {
        BLOCKS.tick();

        for (DaniyaDomain d : new ArrayList<>(DOMAINS.values())) {
            if (d.tick()) {
                d.end();
                DOMAINS.values().remove(d);
            }
        }

        List<Repeating> reps = new ArrayList<>(REPEATING);
        REPEATING.clear();
        for (Repeating r : reps) {
            if (r.world.getTime() < r.until) {
                safeRun(r.each);
                REPEATING.add(r);
            }
        }

        List<Delayed> dels = new ArrayList<>(DELAYED);
        DELAYED.clear();
        for (Delayed d : dels) {
            if (d.world.getTime() >= d.at) safeRun(d.action);
            else DELAYED.add(d);
        }
    }

    private static void safeRun(Runnable r) {
        try { r.run(); } catch (Exception ignored) {}
    }

    // --- domains ---

    public static boolean hasDomain(GirlfriendEntity gf) {
        return DOMAINS.containsKey(gf.getUuid());
    }

    public static boolean deployDomain(GirlfriendEntity gf) {
        if (!(gf.getEntityWorld() instanceof ServerWorld sw)) return false;
        if (DOMAINS.containsKey(gf.getUuid())) return false;
        DOMAINS.put(gf.getUuid(), DaniyaDomain.create(sw, gf));
        return true;
    }

    /** Tear down 达妮娅's domain immediately (her death/removal). Safe if she has none. */
    public static void endDomainFor(GirlfriendEntity gf) {
        DaniyaDomain d = DOMAINS.remove(gf.getUuid());
        if (d != null) d.end();
    }

    // --- scheduled effects ---

    /** Run {@code each} every server tick until {@code world.getTime() >= untilTick}. */
    public static void schedule(ServerWorld world, long untilTick, Runnable each) {
        REPEATING.add(new Repeating(world, untilTick, each));
    }

    /** Run {@code action} once, the first tick that {@code world.getTime() >= atTick}. */
    public static void delay(ServerWorld world, long atTick, Runnable action) {
        DELAYED.add(new Delayed(world, atTick, action));
    }

    /** Server shutdown: end every domain, drop all schedules, restore every transient block. */
    public static void flushAll() {
        for (DaniyaDomain d : new ArrayList<>(DOMAINS.values())) d.end();
        DOMAINS.clear();
        REPEATING.clear();
        DELAYED.clear();
        BLOCKS.restoreAll();
    }
}
