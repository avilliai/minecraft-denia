package xyz.apollodorus.mcgf.ai;

import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import xyz.apollodorus.mcgf.MCGirlfriendMod;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.config.GirlfriendConfig;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drives 达妮娅's sense of "being alive": every second she notices changes around
 * her — nightfall, hostiles appearing, a new biome, low health — and once in a
 * while just chats unprompted when it's calm. Each notable moment becomes a
 * situation note handed to {@link ChatBrain#proactive}. Ports the gist of
 * plugins/AdventureMood.js + the proactive bits of plugins/Companion.js.
 */
public final class MoodManager {
    private MoodManager() {}

    private static long tick;
    private static final Map<UUID, State> STATES = new HashMap<>();

    private static final class State {
        long lastSpoke = Long.MIN_VALUE / 2;
        String biome;
        boolean night;
        boolean hostiles;
        boolean raining;
        boolean thundering;
    }

    public static void onServerTick(MinecraftServer server) {
        tick++;
        if (tick % 20 != 0) return; // evaluate ~once per second

        GirlfriendConfig cfg = ConfigManager.get();
        if (!cfg.behavior.proactiveEnabled || MCGirlfriendMod.BRAIN == null) return;

        long minGap = (long) Math.max(10, cfg.behavior.proactiveMinSeconds) * 20L;
        long ambientPeriod = (long) Math.max(5, cfg.behavior.proactiveCheckSeconds) * 20L;
        boolean ambientWindow = tick % ambientPeriod < 20;

        for (GirlfriendEntity gf : GirlfriendEntity.ACTIVE) {
            if (gf.isRemoved() || !gf.isAlive()) continue;
            PlayerEntity owner = gf.getOwner();
            if (owner == null || gf.squaredDistanceTo(owner) > 64.0 * 64.0) continue;

            State s = STATES.computeIfAbsent(gf.getUuid(), k -> new State());
            String situation = detect(gf, owner, s);

            if (situation == null) {
                if (!ambientWindow) continue;
                if (gf.getRandom().nextDouble() > cfg.behavior.proactiveChance) continue;
                situation = ambient(gf);
            }
            if (tick - s.lastSpoke < minGap) continue;

            s.lastSpoke = tick;
            MCGirlfriendMod.BRAIN.proactive(gf, situation);
        }
    }

    /** Detect the most pressing situational change, updating the baseline as it goes. */
    private static String detect(GirlfriendEntity gf, PlayerEntity owner, State s) {
        long tod = gf.getEntityWorld().getTimeOfDay() % 24000L;
        boolean night = tod >= 13000 && tod < 23000;
        boolean hostiles = countHostiles(gf) > 0;
        String biome = gf.getEntityWorld().getBiome(gf.getBlockPos())
            .getKey().map(k -> k.getValue().getPath()).orElse("unknown");

        // 天气检测
        boolean raining = gf.getEntityWorld().isRaining();
        boolean thundering = gf.getEntityWorld().isThundering();

        boolean newBiome = s.biome != null && !biome.equals(s.biome);
        boolean newHostiles = hostiles && !s.hostiles;
        boolean nightfall = night && !s.night;

        // 天气变化检测
        boolean rainStart = raining && !s.raining;
        boolean thunderStart = thundering && !s.thundering;
        boolean weatherClear = !raining && s.raining;  // 雨停了

        s.biome = biome;
        s.hostiles = hostiles;
        s.night = night;
        s.raining = raining;
        s.thundering = thundering;

        GirlfriendConfig.Prompts p = ConfigManager.get().prompts;
        // 优先级：玩家生命 > 自己生命 > 打雷（紧急） > 敌人出现 > 夜晚 > 下雨 > 天晴 > 新区域
        if (owner.getHealth() <= 6.0f) return p.lowHealthOwner;
        if (gf.getHealth() <= 8.0f) return p.lowHealthSelf;
        if (thunderStart) return p.thunderStorm;  // 打雷有点吓人，优先级高
        if (newHostiles) return p.newHostiles;
        if (nightfall) return p.nightfall;
        if (rainStart) return p.rainStart;  // 开始下雨
        if (weatherClear) return p.weatherClear;  // 雨停天晴
        // New scenery is a treat she only sometimes remarks on, not a guaranteed line.
        if (newBiome && gf.getRandom().nextDouble() < ConfigManager.get().behavior.newBiomeChance)
            return p.newBiome.replace("{biome}", biome);

        // Chest perception (notice + walk over to a nearby container) now lives in PerceiveChestGoal
        // so she can actually approach it; MoodManager no longer remarks on chests to avoid
        // double-speak. Structure remarks (shipwreck/ruins while boating) remain deferred until the
        // 1.21.11 StructureAccessor / StructureKeys signatures are javap-verified.
        return null;
    }

    /**
     * A calm-moment line: 40% the context-appropriate idle prompt (parked vs traveling), otherwise a
     * random 达妮娅-flavored topic from the configurable pool — so she proactively raises her own
     * in-character topics (撒娇/吐槽/关心/夸/小委屈…) rather than only generic chatter.
     */
    private static String ambient(GirlfriendEntity gf) {
        GirlfriendConfig.Prompts p = ConfigManager.get().prompts;
        String base = gf.isOwnerStationary() ? p.idleParked : p.idleTravel;
        List<String> topics = p.ambientTopics;
        if (topics == null || topics.isEmpty() || gf.getRandom().nextDouble() < 0.4) return base;
        return topics.get(gf.getRandom().nextInt(topics.size()));
    }

    private static int countHostiles(GirlfriendEntity gf) {
        List<Entity> list = gf.getEntityWorld().getOtherEntities(gf,
            gf.getBoundingBox().expand(16.0), e -> e instanceof HostileEntity && e.isAlive());
        return list.size();
    }
}
