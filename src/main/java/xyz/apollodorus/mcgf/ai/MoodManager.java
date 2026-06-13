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

        // 玩家行为追踪
        boolean playerWasMining;
        boolean playerWasBuilding;
        boolean playerWasFishing;
        boolean playerWasCrafting;

        // 环境追踪
        int lightLevel = 15;
        boolean wasInDarkPlace;
        boolean wasHighPlace;
        boolean wasUnderwater;
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

            // 先判冷却：还在冷却里就根本不跑 detect()——否则 detect() 会就地推进基线(群系/天气/昼夜)，
            // 把冷却期内发生的变化(尤其是路过新群系)悄悄吞掉，等冷却结束她已经「见过」了。这正是
            // 新群系感言「消失」的主因。
            if (tick - s.lastSpoke < minGap) continue;

            String situation = detect(gf, owner, s);
            if (situation == null) {
                if (!ambientWindow) continue;
                if (gf.getRandom().nextDouble() > cfg.behavior.proactiveChance) continue;
                situation = ambient(gf);
            }

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

        // 环境检测
        int lightLevel = gf.getEntityWorld().getLightLevel(gf.getBlockPos());
        boolean inDarkPlace = lightLevel < 7;
        boolean highPlace = isHighPlace(gf);
        boolean underwater = gf.isSubmergedInWater() || gf.getEntityWorld().getBlockState(gf.getBlockPos()).isLiquid();

        // 玩家行为检测（简单启发式）
        boolean playerMining = isPlayerMining(owner);
        boolean playerBuilding = isPlayerBuilding(owner);
        boolean playerFishing = isPlayerFishing(owner);
        boolean playerCrafting = isPlayerCrafting(owner);

        boolean newBiome = s.biome != null && !biome.equals(s.biome);
        boolean newHostiles = hostiles && !s.hostiles;
        boolean nightfall = night && !s.night;

        // 天气变化检测
        boolean rainStart = raining && !s.raining;
        boolean thunderStart = thundering && !s.thundering;
        boolean weatherClear = !raining && s.raining;

        // 环境变化检测
        boolean enteredDarkPlace = inDarkPlace && !s.wasInDarkPlace;
        boolean enteredHighPlace = highPlace && !s.wasHighPlace;
        boolean enteredWater = underwater && !s.wasUnderwater;

        // 玩家行为变化检测（持续一段时间才触发，避免频繁）
        boolean playerStartedMining = playerMining && !s.playerWasMining;
        boolean playerStartedBuilding = playerBuilding && !s.playerWasBuilding;
        boolean playerStartedFishing = playerFishing && !s.playerWasFishing;
        boolean playerStartedCrafting = playerCrafting && !s.playerWasCrafting;

        // 更新状态
        s.biome = biome;
        s.hostiles = hostiles;
        s.night = night;
        s.raining = raining;
        s.thundering = thundering;
        s.lightLevel = lightLevel;
        s.wasInDarkPlace = inDarkPlace;
        s.wasHighPlace = highPlace;
        s.wasUnderwater = underwater;
        s.playerWasMining = playerMining;
        s.playerWasBuilding = playerBuilding;
        s.playerWasFishing = playerFishing;
        s.playerWasCrafting = playerCrafting;

        GirlfriendConfig.Prompts p = ConfigManager.get().prompts;

        // 优先级：玩家生命 > 自己生命 > 打雷 > 敌人出现 > 夜晚 > 新群系 > 环境变化 > 玩家行为 > 天气
        if (owner.getHealth() <= 6.0f) return GirlfriendConfig.pickOne(p.lowHealthOwner);
        if (gf.getHealth() <= 8.0f) return GirlfriendConfig.pickOne(p.lowHealthSelf);
        if (thunderStart) return GirlfriendConfig.pickOne(p.thunderStorm);
        if (newHostiles) return GirlfriendConfig.pickOne(p.newHostiles);
        if (nightfall) return GirlfriendConfig.pickOne(p.nightfall);

        // 进入新群系：她会像真的看到这片地方一样主动感叹一句。放在随机环境/行为碎碎念之前，否则一进新
        // 群系常被一句随机的「好暗呀」之类抢掉、再也不播报了（这是之前感言「消失」的另一半原因）。
        if (newBiome) {
            String combined = getWeatherBiomeCombination(biome, raining, night);
            if (combined != null) return combined;
            String specific = getSpecificBiomeReaction(p, biome);
            if (specific != null) return specific;
            return GirlfriendConfig.pickOne(p.newBiome).replace("{biome}", biome);
        }

        // 环境变化（偶尔触发，避免过于频繁）
        if (enteredDarkPlace && gf.getRandom().nextDouble() < 0.3) return GirlfriendConfig.pickOne(p.inDarkPlace);
        if (enteredHighPlace && gf.getRandom().nextDouble() < 0.4) return GirlfriendConfig.pickOne(p.highPlace);
        if (enteredWater && gf.getRandom().nextDouble() < 0.3) return GirlfriendConfig.pickOne(p.underwaterOrCave);

        // 玩家行为反应（低频率，避免打断）
        if (playerStartedMining && gf.getRandom().nextDouble() < 0.2) return GirlfriendConfig.pickOne(p.playerMining);
        if (playerStartedBuilding && gf.getRandom().nextDouble() < 0.25) return GirlfriendConfig.pickOne(p.playerBuilding);
        if (playerStartedFishing && gf.getRandom().nextDouble() < 0.3) return GirlfriendConfig.pickOne(p.playerFishing);
        if (playerStartedCrafting && gf.getRandom().nextDouble() < 0.2) return GirlfriendConfig.pickOne(p.playerCrafting);

        if (rainStart) return GirlfriendConfig.pickOne(p.rainStart);
        if (weatherClear) return GirlfriendConfig.pickOne(p.weatherClear);

        return null;
    }

    /** 获取天气+生物群系组合的特殊反应 */
    private static String getWeatherBiomeCombination(String biome, boolean raining, boolean night) {
        GirlfriendConfig.Prompts p = ConfigManager.get().prompts;

        if (raining && (biome.contains("forest") || biome.contains("taiga"))) {
            return GirlfriendConfig.pickOne(p.rainyForest);
        }
        if (raining && (biome.contains("beach") || biome.contains("ocean"))) {
            return GirlfriendConfig.pickOne(p.rainyBeach);
        }
        if (!raining && (biome.contains("meadow") || biome.contains("plains"))) {
            return GirlfriendConfig.pickOne(p.sunnyMeadow);
        }
        if (night && (biome.contains("beach") || biome.contains("ocean"))) {
            return GirlfriendConfig.pickOne(p.nightBeach);
        }

        return null;
    }

    /** 获取具体生物群系的反应 */
    private static String getSpecificBiomeReaction(GirlfriendConfig.Prompts p, String biome) {
        // 樱花林
        if (biome.contains("cherry")) return GirlfriendConfig.pickOne(p.biomeCherryGrove);

        // 针叶林
        if (biome.contains("taiga") || biome.contains("spruce")) return GirlfriendConfig.pickOne(p.biomeTaiga);

        // 白桦林
        if (biome.contains("birch")) return GirlfriendConfig.pickOne(p.biomeBirchForest);

        // 繁花森林
        if (biome.contains("flower")) return GirlfriendConfig.pickOne(p.biomeFlowerForest);

        // 普通森林
        if (biome.contains("forest")) return GirlfriendConfig.pickOne(p.biomeForest);

        // 河流
        if (biome.contains("river")) return GirlfriendConfig.pickOne(p.biomeRiver);

        // 海滩
        if (biome.contains("beach")) return GirlfriendConfig.pickOne(p.biomeBeach);

        // 海洋
        if (biome.contains("ocean")) return GirlfriendConfig.pickOne(p.biomeOcean);

        // 草甸
        if (biome.contains("meadow")) return GirlfriendConfig.pickOne(p.biomeMeadow);

        // 平原
        if (biome.contains("plains")) return GirlfriendConfig.pickOne(p.biomePlains);

        // 沙漠
        if (biome.contains("desert")) return GirlfriendConfig.pickOne(p.biomeDesert);

        // 恶地
        if (biome.contains("badlands") || biome.contains("mesa")) return GirlfriendConfig.pickOne(p.biomeBadlands);

        // 雪地
        if (biome.contains("snowy") || biome.contains("frozen") || biome.contains("ice")) {
            return GirlfriendConfig.pickOne(p.biomeSnowy);
        }

        // 蘑菇岛
        if (biome.contains("mushroom")) return GirlfriendConfig.pickOne(p.biomeMushroom);

        // 丛林
        if (biome.contains("jungle")) return GirlfriendConfig.pickOne(p.biomeJungle);

        // 沼泽
        if (biome.contains("swamp")) return GirlfriendConfig.pickOne(p.biomeSwamp);

        // 黑森林
        if (biome.contains("dark_forest")) return GirlfriendConfig.pickOne(p.biomeDarkForest);

        // 洞穴
        if (biome.contains("cave") || biome.contains("dripstone")) return GirlfriendConfig.pickOne(p.biomeCave);

        // 深暗之域
        if (biome.contains("deep_dark")) return GirlfriendConfig.pickOne(p.biomeDeepDark);

        return null;
    }

    /** 检测玩家是否在挖矿（手持镐子且速度慢） */
    private static boolean isPlayerMining(PlayerEntity player) {
        var held = player.getMainHandStack();
        if (held.isEmpty()) return false;
        var tool = held.get(net.minecraft.component.DataComponentTypes.TOOL);
        return tool != null && player.getVelocity().lengthSquared() < 0.01;
    }

    /** 检测玩家是否在建造（手持方块且频繁放置） */
    private static boolean isPlayerBuilding(PlayerEntity player) {
        var held = player.getMainHandStack();
        return held.getItem() instanceof net.minecraft.item.BlockItem && player.getVelocity().lengthSquared() < 0.05;
    }

    /** 检测玩家是否在钓鱼（手持鱼竿） */
    private static boolean isPlayerFishing(PlayerEntity player) {
        var held = player.getMainHandStack();
        return held.isOf(net.minecraft.item.Items.FISHING_ROD);
    }

    /** 检测玩家是否在合成（靠近工作台） */
    private static boolean isPlayerCrafting(PlayerEntity player) {
        // 简单检测：附近有工作台且玩家静止
        var pos = player.getBlockPos();
        var world = player.getEntityWorld();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    var checkPos = pos.add(dx, dy, dz);
                    var state = world.getBlockState(checkPos);
                    if (state.isOf(net.minecraft.block.Blocks.CRAFTING_TABLE)) {
                        return player.getVelocity().lengthSquared() < 0.01;
                    }
                }
            }
        }
        return false;
    }

    /** 检测是否在高处（脚下10格内没有实心方块） */
    private static boolean isHighPlace(GirlfriendEntity gf) {
        var pos = gf.getBlockPos();
        var world = gf.getEntityWorld();
        for (int dy = 1; dy <= 10; dy++) {
            var checkPos = pos.down(dy);
            if (world.getBlockState(checkPos).isSolidBlock(world, checkPos)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A calm-moment line: 40% the context-appropriate idle prompt (parked vs traveling), otherwise a
     * random 达妮娅-flavored topic from the configurable pool — so she proactively raises her own
     * in-character topics (撒娇/吐槽/关心/夸/小委屈…) rather than only generic chatter.
     */
    private static String ambient(GirlfriendEntity gf) {
        GirlfriendConfig.Prompts p = ConfigManager.get().prompts;
        String base = GirlfriendConfig.pickOne(gf.isOwnerStationary() ? p.idleParked : p.idleTravel);
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
