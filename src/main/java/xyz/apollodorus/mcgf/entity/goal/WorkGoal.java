package xyz.apollodorus.mcgf.entity.goal;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import xyz.apollodorus.mcgf.ai.SpeechBus;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.config.GirlfriendConfig;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity.Task;
import xyz.apollodorus.mcgf.entity.work.WorkUtil;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Executes the resource-work QUEUE: commanded mine / chop / harvest / obtain jobs, and
 * opportunistic idle gathering when the owner is parked. Walks to the nearest matching block,
 * equips the right tool, breaks it (replanting crops), and vacuums up the drops.
 *
 * <p>Queue semantics: {@link GirlfriendEntity#getTasks()} holds all pending jobs; this goal works
 * the first one with a reachable target ({@link GirlfriendEntity#getTask()} is the active one). When
 * a commanded job has work left but nothing reachable nearby, it is RELEASED back to "waiting" rather
 * than abandoned — so she retries it automatically once a target is in range / she's relocated. Dead
 * ends (no pickaxe, pickaxe too weak, unminable item) finish the job with a line and move on.
 *
 * <p>She is deliberately NOT omniscient: only ore with an exposed face is targeted, and any block she
 * can't path to is blacklisted. Ports the spirit of plugins/AutonomyAgent.js + plugins/IdleGather.js.
 */
public class WorkGoal extends Goal {
    private static final long BLACKLIST_TICKS = 1200L; // ~1 minute before she'll retry a spot
    private static final double REACH_SQ = 16.0;       // ~4 blocks: break range

    private final GirlfriendEntity gf;
    private final Map<BlockPos, Long> blacklist = new HashMap<>();
    private BlockPos target;
    private int repathCd;
    private int rescanCd;
    private int stuckTicks;

    // mining-in-progress state (vanilla-paced, with block-cracking feedback)
    private double breakProgress;
    private int lastStage = -1;
    private int swingTick;

    /** Which ore/source blocks drop a given item, for obtain_item sourcing — incl. blocks whose
     *  drop differs from the block itself (stone→圆石, deepslate→深层圆石, grass→泥土), which是
     *  "她不知道石头掉圆石" 的根因：没有这张反查表她只会去找名为 cobblestone 的方块（自然界没有）。 */
    private static final Map<Item, Set<Block>> ORE_SOURCES = Map.ofEntries(
        Map.entry(Items.COAL, Set.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE)),
        Map.entry(Items.RAW_IRON, Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE)),
        Map.entry(Items.RAW_COPPER, Set.of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE)),
        Map.entry(Items.RAW_GOLD, Set.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE)),
        Map.entry(Items.DIAMOND, Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE)),
        Map.entry(Items.EMERALD, Set.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE)),
        Map.entry(Items.REDSTONE, Set.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE)),
        Map.entry(Items.LAPIS_LAZULI, Set.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE)),
        Map.entry(Items.QUARTZ, Set.of(Blocks.NETHER_QUARTZ_ORE)),
        // 方块掉落物 ≠ 方块本身的常见情况：让她明白要去挖什么。
        Map.entry(Items.COBBLESTONE, Set.of(Blocks.STONE)),
        Map.entry(Items.COBBLED_DEEPSLATE, Set.of(Blocks.DEEPSLATE)),
        Map.entry(Items.DIRT, Set.of(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT)),
        Map.entry(Items.FLINT, Set.of(Blocks.GRAVEL)),
        Map.entry(Items.WHEAT, Set.of(Blocks.WHEAT)),
        Map.entry(Items.CARROT, Set.of(Blocks.CARROTS)),
        Map.entry(Items.POTATO, Set.of(Blocks.POTATOES)),
        Map.entry(Items.BEETROOT, Set.of(Blocks.BEETROOTS))
    );

    public WorkGoal(GirlfriendEntity gf) {
        this.gf = gf;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (gf.getTarget() != null) return false;       // fighting takes priority
        if (gf.getTask() != null) return true;            // a job is already active → run it
        if (pickActiveTask()) return true;                // promote a now-doable queued job ("空闲自动重试")
        if (!idleGatherAllowed()) return false;
        return findIdleTarget() != null;                  // idle gather
    }

    @Override
    public boolean shouldContinue() {
        if (gf.getTarget() != null) return false;
        if (gf.getTask() != null) return true;
        return idleGatherAllowed();
    }

    @Override
    public void start() {
        this.target = null;
        this.breakProgress = 0;
        this.lastStage = -1;
        this.stuckTicks = 0;
        gf.setActivity(activityLabel());
    }

    @Override
    public void stop() {
        if (gf.getEntityWorld() instanceof ServerWorld sw) resetMining(sw);
        this.target = null;
        gf.getNavigation().stop();
        if (gf.getTask() == null) gf.setActivity("闲着");
    }

    @Override
    public void tick() {
        if (!(gf.getEntityWorld() instanceof ServerWorld sw)) return;
        long now = sw.getTime();
        Task active = gf.getTask();
        gf.setActivity(activityLabel());

        if (target == null || sw.getBlockState(target).isAir()) {
            if (--rescanCd > 0) return;
            rescanCd = 8;
            resetMining(sw);
            target = (active != null) ? findTargetFor(active) : findIdleTarget();
            stuckTicks = 0;
            if (target == null) {
                if (active != null) onActiveNoTarget(active);
                return;
            }
        }

        Vec3d center = Vec3d.ofCenter(target);
        gf.getLookControl().lookAt(center);
        double distSq = gf.squaredDistanceTo(center);

        if (distSq > REACH_SQ) {
            resetMining(sw); // walked off → drop any half-dug progress
            if (--repathCd <= 0) {
                repathCd = 10;
                boolean ok = gf.getNavigation().startMovingTo(center.x, center.y, center.z,
                    ConfigManager.get().behavior.moveSpeed);
                if (!ok) { blacklist(now, target); target = null; return; } // unreachable → ignore
            }
            if (++stuckTicks > 140) { blacklist(now, target); target = null; } // keeps failing → give up
            return;
        }

        gf.getNavigation().stop();
        stuckTicks = 0;

        BlockState st = sw.getBlockState(target);
        if (st.isAir()) { resetMining(sw); target = null; return; }

        WorkUtil.ToolKind kind = WorkUtil.toolFor(st);
        if (kind == WorkUtil.ToolKind.PICKAXE && WorkUtil.isOre(st)
            && !WorkUtil.hasTool(gf, WorkUtil.ToolKind.PICKAXE)) {
            if (active != null && (active.kind == Task.Kind.MINE
                || (active.kind == Task.Kind.OBTAIN && active.item != null && ORE_SOURCES.containsKey(active.item)))) {
                finishActive("我没有镐子呀，挖不动矿…给我个镐子嘛~");
            } else {
                resetMining(sw);
                blacklist(now, target);
                target = null;
            }
            return;
        }
        WorkUtil.equipTool(gf, kind);

        // Pickaxe too low-tier to actually harvest this ore (e.g. stone vs diamond) — don't grind it
        // drop-lessly. A specific obtain job is a dead end (report it); otherwise skip this deposit.
        if (kind == WorkUtil.ToolKind.PICKAXE && WorkUtil.isOre(st) && !WorkUtil.heldCanHarvest(gf, st)) {
            if (active != null && active.kind == Task.Kind.OBTAIN && active.item != null
                && ORE_SOURCES.containsKey(active.item)) {
                finishActive("我的镐子不够硬，挖不到" + WorkUtil.displayName(active.item) + "呀…换把更好的给我嘛~");
            } else {
                resetMining(sw);
                blacklist(now, target);
                target = null;
            }
            return;
        }

        // Mine at vanilla player pace: accumulate progress, swing, and show cracks.
        float delta = WorkUtil.breakDelta(sw, gf, target);
        if (delta <= 0f) { resetMining(sw); blacklist(now, target); target = null; return; }
        breakProgress += delta * ConfigManager.get().behavior.miningSpeedMultiplier;

        if (swingTick++ % 5 == 0) gf.swingHand(Hand.MAIN_HAND);

        int stage = (int) (breakProgress * 10.0);
        if (stage != lastStage) {
            sw.setBlockBreakingInfo(gf.getId(), target, Math.min(stage, 9));
            lastStage = stage;
        }
        if (breakProgress < 1.0) return;

        // Finished this block.
        sw.setBlockBreakingInfo(gf.getId(), target, -1);
        WorkUtil.breakBlock(sw, gf, target);
        WorkUtil.vacuumDrops(sw, gf, 4.0);
        breakProgress = 0;
        lastStage = -1;
        target = null;

        if (active != null && active.kind != Task.Kind.HARVEST) {
            active.remaining--;
            if (active.remaining <= 0) finishActive(doneMessage(active));
        }
    }

    /** The active job ran out of reachable targets. HARVEST / finished → complete; else keep it waiting. */
    private void onActiveNoTarget(Task t) {
        if (t.kind == Task.Kind.HARVEST) { finishActive("这附近的庄稼收完啦~"); return; }
        if (t.remaining <= 0) { finishActive(doneMessage(t)); return; }
        releaseToWaiting(); // work left but nothing reachable now → re-tried automatically when possible
    }

    /** Clear any in-progress block-cracking overlay and reset the mining accumulator. */
    private void resetMining(ServerWorld sw) {
        if (lastStage >= 0 && target != null) sw.setBlockBreakingInfo(gf.getId(), target, -1);
        breakProgress = 0;
        lastStage = -1;
    }

    // --- queue / target selection ---

    private boolean idleGatherAllowed() {
        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        if (!gf.isGatherEnabled() || !b.autoIdleGather) return false;
        return !gf.isFollowing() || gf.isOwnerStationary();
    }

    /** Lock the first queued job that currently has a reachable target as the active one. */
    private boolean pickActiveTask() {
        List<Task> queue = gf.getTasks();
        for (Task t : queue) {
            if (findTargetFor(t) != null) {
                gf.setActiveTask(t);
                return true;
            }
        }
        return false;
    }

    private BlockPos findIdleTarget() { return findTargetFor(null); }

    /** Nearest reachable, sensible target for a job ({@code null} = idle gather); skips buried ore. */
    private BlockPos findTargetFor(Task t) {
        Predicate<BlockState> pred = (t == null) ? idlePredicate() : predicateForTask(t);
        if (pred == null) return null;
        int radius = (t == null) ? ConfigManager.get().behavior.idleGatherRadius
                                 : ConfigManager.get().behavior.workSearchRadius;
        World world = gf.getEntityWorld();
        Set<BlockPos> exclude = currentBlacklist(world.getTime());
        for (int tries = 0; tries < 8; tries++) {
            BlockPos p = WorkUtil.findNearestBlock(world, gf.getBlockPos(), radius, pred, exclude);
            if (p == null) return null;
            BlockState st = world.getBlockState(p);
            if (WorkUtil.isOre(st) && !WorkUtil.isExposed(world, p)) { // no X-ray digging through walls
                exclude.add(p);
                blacklist(world.getTime(), p);
                continue;
            }
            return p;
        }
        return null;
    }

    /** Idle gathering picks up ore (if she has a pickaxe), logs (if auto-wood on), and ripe crops (if auto-crops on). */
    private Predicate<BlockState> idlePredicate() {
        boolean pick = WorkUtil.hasTool(gf, WorkUtil.ToolKind.PICKAXE);
        boolean wood = ConfigManager.get().behavior.autoGatherWood;
        boolean crops = ConfigManager.get().behavior.autoGatherCrops;
        return st -> (pick && WorkUtil.isOre(st)) || (wood && WorkUtil.isLog(st)) || (crops && WorkUtil.isMatureCrop(st));
    }

    private static Predicate<BlockState> predicateForTask(Task t) {
        return switch (t.kind) {
            case MINE -> WorkUtil::isOre;
            case CHOP -> WorkUtil::isLog;
            case HARVEST -> WorkUtil::isMatureCrop;
            case OBTAIN -> predicateForItem(t.item);
        };
    }

    private static Predicate<BlockState> predicateForItem(Item item) {
        if (item == null) return null;
        Set<Block> ores = ORE_SOURCES.get(item);
        if (ores != null) {
            boolean crop = item == Items.WHEAT || item == Items.CARROT || item == Items.POTATO || item == Items.BEETROOT;
            return crop
                ? st -> ores.contains(st.getBlock()) && WorkUtil.isMatureCrop(st)
                : st -> ores.contains(st.getBlock());
        }
        if (item instanceof BlockItem bi) {
            Block block = bi.getBlock();
            // "木头/wood/oak_log" → harvest ANY log, not just oak, so she uses whatever tree is near.
            if (WorkUtil.isLog(block.getDefaultState())) return WorkUtil::isLog;
            return st -> st.getBlock() == block;
        }
        return null;
    }

    /** Can this job be sourced at all (ore/crop/block she knows how to gather)? Used by the commands/tools. */
    public static boolean isGettable(Task t) {
        return t != null && predicateForTask(t) != null;
    }

    /** Best-effort: is there a matching block within work radius right now (for the "去弄/先记下来" reply)? */
    public static boolean hasNearbyTarget(GirlfriendEntity gf, Task t) {
        Predicate<BlockState> pred = (t == null) ? null : predicateForTask(t);
        if (pred == null) return false;
        int radius = ConfigManager.get().behavior.workSearchRadius;
        return WorkUtil.findNearestBlock(gf.getEntityWorld(), gf.getBlockPos(), radius, pred, null) != null;
    }

    // --- blacklist of unreachable / buried spots ---

    private void blacklist(long now, BlockPos pos) {
        blacklist.put(pos.toImmutable(), now);
    }

    private Set<BlockPos> currentBlacklist(long now) {
        Set<BlockPos> live = new HashSet<>();
        Iterator<Map.Entry<BlockPos, Long>> it = blacklist.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Long> e = it.next();
            if (now - e.getValue() > BLACKLIST_TICKS) it.remove();
            else live.add(e.getKey());
        }
        return live;
    }

    // --- chat / labels ---

    private String activityLabel() {
        Task t = gf.getTask();
        if (t == null) return gf.getTasks().isEmpty() ? "顺手转转采点东西" : "等会儿去弄你交代的事";
        return switch (t.kind) {
            case MINE -> "挖矿中";
            case CHOP -> "砍树中";
            case HARVEST -> "收庄稼中";
            case OBTAIN -> "去找" + WorkUtil.displayName(t.item);
        };
    }

    private String doneMessage(Task t) {
        return switch (t.kind) {
            case MINE -> "矿挖好啦，都在我这儿~";
            case CHOP -> "木头砍够咯~";
            case OBTAIN -> "你要的" + WorkUtil.displayName(t.item) + "我弄到啦，给你~";
            case HARVEST -> "收好啦~";
        };
    }

    /** Finish the ACTIVE job: hand over obtain items, drop it from the queue, speak. Other jobs remain. */
    private void finishActive(String message) {
        Task t = gf.getTask();
        if (t != null && t.kind == Task.Kind.OBTAIN && t.item != null) {
            gf.giveToOwner(t.item, Integer.MAX_VALUE);
            gf.addAffection(2);
        }
        gf.completeActiveTask();
        gf.getNavigation().stop();
        target = null;
        if (message != null && !message.isBlank()) SpeechBus.speak(gf, message);
    }

    /** Release the active job back to "waiting" (no reachable target now); auto-retried later. */
    private void releaseToWaiting() {
        gf.setActiveTask(null);
        gf.getNavigation().stop();
        target = null;
    }
}
