package xyz.apollodorus.mcgf.ai;

import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import xyz.apollodorus.mcgf.MCGirlfriendMod;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.config.GirlfriendConfig;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
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
    /** Per-pool "shuffle bag" of not-yet-used indices, so proactive topics cycle the whole pool before repeating. */
    private static final Map<String, Deque<Integer>> BAGS = new HashMap<>();

    private static final class State {
        long lastSpoke = Long.MIN_VALUE / 2;
        long lastEventSpoke = Long.MIN_VALUE / 2;   // event-triggered lines have their own (longer) cooldown
        long lastPlayerChatTick = Long.MIN_VALUE / 2; // when the player last talked TO her (drives 沉默感知)
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
        boolean ownerWasLowHealth;
        boolean selfWasLowHealth;
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

            // 首次见到这只达妮娅时，把沉默感知时钟设到「现在」——她从正常热度起步，之后若一直没人理她
            // 才慢慢安静下来（而不是一开始就被当成已沉默很久）。玩家说话会经 notePlayerChatted 重置。
            State s = STATES.computeIfAbsent(gf.getUuid(), k -> {
                State st = new State();
                st.lastPlayerChatTick = tick;
                return st;
            });

            // detect() runs every check so the baselines (群系/天气/昼夜/敌人) stay fresh and a change is
            // never silently swallowed. Whether she actually SPEAKS is gated below: event lines by the long
            // event cooldown (so combat isn't a running commentary), calm chatter by the shorter ambient gap.
            String event = detect(gf, owner, s);
            if (event != null) {
                tryEventProactive(gf, event);   // gated by proactiveEventMinSeconds; stays quiet if on cooldown
                continue;
            }

            // Nothing notable → maybe a calm ambient remark, on its own (shorter) cadence.
            // 战斗/他快没血/二形态时不碎碎念——专心打或护人，别在打架时馋蛋糕。
            if (countHostiles(gf) > 0 || owner.getHealth() <= 6.0f || gf.isFormTwo()) continue;

            // 沉默感知：玩家越久没跟她说话，她越安静——主动碎碎念的概率收紧、间隔拉长，最终基本闭嘴、安静做
            // 自己的事。事件（怪/夜/箱子/钓到鱼…）走的是上面的事件通道，不受此压制——「只有发生事情才打破沉默」。
            long silence = tick - s.lastPlayerChatTick;
            long engagedTicks = (long) Math.max(20, cfg.behavior.engagedSeconds) * 20L;
            long coolingTicks = (long) Math.max(cfg.behavior.engagedSeconds + 1, cfg.behavior.coolingSeconds) * 20L;
            double chance = cfg.behavior.proactiveChance;
            long gap = minGap;
            if (silence >= coolingTicks) {            // QUIET：基本不主动碎碎念
                chance *= Math.max(0.0, cfg.behavior.quietAmbientChanceScale);
                gap = minGap * 3;
            } else if (silence >= engagedTicks) {     // COOLING：明显变稀
                chance *= 0.4;
                gap = minGap * 2;
            }

            // 长沉默打破：玩家和她都安静了很久，偶尔轻声打破一下（走事件通道，复用事件冷却，不会和其它叠在一起）。
            long longSilenceTicks = (long) Math.max(60, cfg.behavior.longSilenceMinSeconds) * 20L;
            if (ambientWindow && silence >= longSilenceTicks && (tick - s.lastSpoke) >= longSilenceTicks
                    && gf.getRandom().nextDouble() < cfg.behavior.longSilenceChance
                    && tryEventProactive(gf, GirlfriendConfig.pickOne(cfg.prompts.longSilence))) {
                continue;
            }

            if (tick - s.lastSpoke < gap || !ambientWindow) continue;
            if (gf.getRandom().nextDouble() > chance) continue;
            s.lastSpoke = tick;
            MCGirlfriendMod.BRAIN.proactive(gf, ambient(gf));
        }
    }

    /**
     * The player just chatted with this girlfriend — reset her 沉默感知 timer (back to the ENGAGED tier) and
     * push her next proactive line out by a gap, so she doesn't natter on top of a reply. Server-thread only
     * (called from {@link ChatBrain#handleChat}).
     */
    public static void notePlayerChatted(GirlfriendEntity gf) {
        if (gf == null) return;
        State s = STATES.computeIfAbsent(gf.getUuid(), k -> new State());
        s.lastPlayerChatTick = tick;
        s.lastSpoke = tick;   // don't proactively chatter right on top of answering him
    }

    /**
     * Fire an event-triggered proactive line through the shared event cooldown
     * ({@link GirlfriendConfig.Behavior#proactiveEventMinSeconds}, ~180s). Used for monster alerts and
     * low-HP warnings (from {@link #onServerTick}) and 蚀域 enter/exit, so combat stays a rare reaction
     * rather than a running commentary. Returns true if she actually spoke. Server-thread only.
     */
    public static boolean tryEventProactive(GirlfriendEntity gf, String situation) {
        if (situation == null || situation.isBlank() || MCGirlfriendMod.BRAIN == null) return false;
        State s = STATES.computeIfAbsent(gf.getUuid(), k -> new State());
        long eventGap = (long) Math.max(10, ConfigManager.get().behavior.proactiveEventMinSeconds) * 20L;
        if (tick - s.lastEventSpoke < eventGap) return false;
        if (!MCGirlfriendMod.BRAIN.proactive(gf, situation)) return false;
        s.lastEventSpoke = tick;
        s.lastSpoke = tick;   // an event line also resets the ambient gap
        return true;
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
        // 低血量用边沿触发：持续低血不会每秒都想说话（之前会占满事件通道、和 CD 叠在一起仍显得吵）。
        boolean ownerLow = owner.getHealth() <= 6.0f;
        if (ownerLow && !s.ownerWasLowHealth) {
            s.ownerWasLowHealth = true;
            return GirlfriendConfig.pickOne(p.lowHealthOwner);
        }
        if (!ownerLow) s.ownerWasLowHealth = false;

        boolean selfLow = gf.getHealth() <= 8.0f;
        if (selfLow && !s.selfWasLowHealth) {
            s.selfWasLowHealth = true;
            return GirlfriendConfig.pickOne(p.lowHealthSelf);
        }
        if (!selfLow) s.selfWasLowHealth = false;
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
        // inDarkPlace 只在白天才报——夜晚的地表光照本来就 <7，会和 nightfall 撞车、让她短时间内反复
        // 「天黑搭话」。白天还暗的地方=洞穴/封闭空间，这时提一句才有意义；夜晚交给 nightfall 一句就够。
        if (enteredDarkPlace && !night && gf.getRandom().nextDouble() < 0.3) return GirlfriendConfig.pickOne(p.inDarkPlace);
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
        boolean parked = gf.isOwnerStationary();
        String base = pickFresh(gf, parked ? "idleParked" : "idleTravel", parked ? p.idleParked : p.idleTravel);
        List<String> topics = p.ambientTopics;
        if (topics == null || topics.isEmpty() || gf.getRandom().nextDouble() < 0.4) return base;
        return pickFresh(gf, "ambientTopics", topics);
    }

    /**
     * Sample a pool without replacement: every entry is used once (in a freshly shuffled order) before
     * any repeats. This is the main fix for "她老说同几句" — {@link GirlfriendConfig#pickOne}'s plain
     * random can repeat a topic right away, whereas this cycles the whole {@code ambientTopics} pool
     * first. Keyed by a stable pool id; a pool that changed size (e.g. config reload) just reshuffles.
     * Server-thread only (called from {@link #onServerTick}), so the plain maps need no locking.
     */
    private static String pickFresh(GirlfriendEntity gf, String poolId, List<String> pool) {
        if (pool == null || pool.isEmpty()) return "";
        if (pool.size() == 1) return pool.get(0);
        Deque<Integer> bag = BAGS.computeIfAbsent(poolId, k -> new ArrayDeque<>());
        if (bag.isEmpty()) {
            List<Integer> order = new ArrayList<>(pool.size());
            for (int i = 0; i < pool.size(); i++) order.add(i);
            for (int i = order.size() - 1; i > 0; i--) Collections.swap(order, i, gf.getRandom().nextInt(i + 1));
            bag.addAll(order);
        }
        int idx = bag.pollFirst();
        if (idx >= pool.size()) idx = gf.getRandom().nextInt(pool.size());   // pool shrank since last shuffle
        return pool.get(idx);
    }

    private static int countHostiles(GirlfriendEntity gf) {
        List<Entity> list = gf.getEntityWorld().getOtherEntities(gf,
            gf.getBoundingBox().expand(16.0), e -> e instanceof HostileEntity && e.isAlive());
        return list.size();
    }
}
