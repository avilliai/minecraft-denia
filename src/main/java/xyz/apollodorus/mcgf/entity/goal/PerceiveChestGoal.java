package xyz.apollodorus.mcgf.entity.goal;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.config.GirlfriendConfig;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;
import xyz.apollodorus.mcgf.entity.work.PathAssist;
import xyz.apollodorus.mcgf.entity.work.WorkUtil;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Walks her over to investigate a noticed container in her free time (owner parked or told to stay),
 * breaking or bridging through obstacles when she genuinely can't path there (never the chest itself /
 * functional blocks, and never near home). The spoken heads-up is NOT done here — it lives in
 * {@link GirlfriendEntity}'s tick so it fires independently of any task; this goal only handles the
 * approach (and stays silent, to avoid double-speak). Hushed within {@code homePerceiveRadius} of home.
 *
 * <p>Sits above {@link WorkGoal} (idle gathering) but below follow / combat, so investigating a
 * chest takes precedence over foraging yet never pulls her off escorting a moving owner.
 */
public class PerceiveChestGoal extends Goal {
    private static final double REACH_SQ = 6.25;    // ~2.5 blocks: close enough to "peek"
    private static final long VISIT_EXPIRE = 2400L; // ~2 min before she'll notice the same spot again
    private static final double LEAD_ABORT_SQ = 20.0 * 20.0; // owner strayed this far → drop it and rejoin him

    private static final Predicate<BlockState> IS_CONTAINER =
        st -> st.isOf(Blocks.CHEST) || st.isOf(Blocks.TRAPPED_CHEST) || st.isOf(Blocks.BARREL);

    private final GirlfriendEntity gf;
    private final PathAssist assist = new PathAssist();
    private final Map<BlockPos, Long> visited = new HashMap<>();
    private BlockPos target;
    private int repathCd;
    private int stuckTicks;
    private int scanCd;

    public PerceiveChestGoal(GirlfriendEntity gf) {
        this.gf = gf;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        if (!b.autoPerceiveChests) return false;
        if (gf.getTarget() != null || gf.getTask() != null) return false; // fighting / commanded work first
        if (gf.isSleeping()) return false;                                 // 打盹中别去看箱子
        if (gf.isFollowing() && !gf.isOwnerStationary()) return false;     // don't leave a moving owner
        if (nearHome(b)) return false;                                     // hushed near home
        if (--scanCd > 0) return false;                                    // bound the world scan to ~1/s
        scanCd = 20;
        this.target = findChest(b);
        return target != null;
    }

    @Override
    public boolean shouldContinue() {
        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        if (!b.autoPerceiveChests || target == null) return false;
        if (gf.getTarget() != null || gf.getTask() != null) return false;
        // NOTE: unlike canStart, we do NOT abort just because the owner started moving — once she's
        // committed to a chest she keeps walking there (leading him over). She only bails if he wanders
        // far off (then follow/teleport rejoins him), or the chest is gone, or she's back near home.
        if (nearHome(b)) return false;
        net.minecraft.entity.player.PlayerEntity owner = gf.getOwner();
        if (owner != null && gf.squaredDistanceTo(owner) > LEAD_ABORT_SQ) return false;
        return IS_CONTAINER.test(gf.getEntityWorld().getBlockState(target));
    }

    @Override
    public void start() {
        this.stuckTicks = 0;
        this.repathCd = 0;
        assist.reset();
        gf.setLeadingChest(true);   // follow yields so she leads him over instead of being yanked back
        gf.setActivity("带你去看那个箱子");
    }

    @Override
    public void stop() {
        this.target = null;
        this.stuckTicks = 0;
        assist.reset();
        gf.setLeadingChest(false);
        gf.getNavigation().stop();
        if (gf.getTask() == null) gf.setActivity("闲着");
    }

    @Override
    public void tick() {
        if (target == null || !(gf.getEntityWorld() instanceof ServerWorld sw)) return;
        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        Vec3d center = Vec3d.ofCenter(target);
        gf.getLookControl().lookAt(center);

        if (gf.squaredDistanceTo(center) <= REACH_SQ) { // arrived → peek done, remember it
            markVisited(sw.getTime(), target);
            stop();
            return;
        }

        if (--repathCd <= 0) {
            repathCd = 10;
            gf.getNavigation().startMovingTo(center.x, center.y, center.z, b.moveSpeed);
        }

        // When she genuinely can't path there (a wall / a gap), break or bridge a way through.
        Path path = gf.getNavigation().getCurrentPath();
        boolean reaches = path != null && path.reachesTarget();
        if (gf.getNavigation().isIdle() && !reaches) {
            boolean acted = gf.squaredDistanceTo(center) < square(b.bridgeMaxDistance)
                && assist.tickToward(sw, gf, b, target);
            if (acted) stuckTicks = 0;
            else if (++stuckTicks > 160) giveUp(sw); // can't break through → drop it (remember, move on)
        } else {
            assist.reset();
            stuckTicks = 0;
        }
    }

    // --- helpers ---

    private void giveUp(ServerWorld sw) {
        if (target != null) markVisited(sw.getTime(), target);
        stop();
    }

    private boolean nearHome(GirlfriendConfig.Behavior b) {
        BlockPos home = gf.getHomePos();
        return home != null
            && home.getSquaredDistance(gf.getBlockPos()) <= b.homePerceiveRadius * b.homePerceiveRadius;
    }

    /** Nearest un-visited container in range, skipping any within the home-perception radius. */
    private BlockPos findChest(GirlfriendConfig.Behavior b) {
        World world = gf.getEntityWorld();
        Set<BlockPos> exclude = new HashSet<>(liveVisited(world.getTime()));
        BlockPos home = gf.getHomePos();
        double hr2 = b.homePerceiveRadius * b.homePerceiveRadius;
        for (int tries = 0; tries < 8; tries++) {
            BlockPos p = WorkUtil.findNearestBlock(world, gf.getBlockPos(), b.perceiveRadius, IS_CONTAINER, exclude);
            if (p == null) return null;
            if (gf.isChestIgnored(p)) { exclude.add(p); continue; }                  // 玩家已打开过 → 跳过
            if (home != null && home.getSquaredDistance(p) <= hr2) { exclude.add(p); continue; }
            return p;
        }
        return null;
    }

    private void markVisited(long now, BlockPos pos) {
        visited.put(pos.toImmutable(), now);
    }

    private Set<BlockPos> liveVisited(long now) {
        Set<BlockPos> live = new HashSet<>();
        Iterator<Map.Entry<BlockPos, Long>> it = visited.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Long> e = it.next();
            if (now - e.getValue() > VISIT_EXPIRE) it.remove();
            else live.add(e.getKey());
        }
        return live;
    }

    private static double square(double v) {
        return v * v;
    }
}
