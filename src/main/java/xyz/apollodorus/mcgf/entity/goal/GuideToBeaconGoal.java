package xyz.apollodorus.mcgf.entity.goal;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import xyz.apollodorus.mcgf.MCGirlfriendMod;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.config.GirlfriendConfig;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;
import xyz.apollodorus.mcgf.entity.work.PathAssist;

import java.util.EnumSet;

/**
 * 寻路信标带路：当玩家投掷信标、{@link GirlfriendEntity#setGuideTarget} 给了她一个落点，她就带路过去——
 * 这正是用来在崎岖地形帮玩家搭路的（沿途卡住会垒瞬时虚质柱/架桥，复用 {@link PathAssist}）。
 *
 * <p>优先级低于战斗/冲向受击玩家（Swim/Rush/Attack），所以「保护玩家」仍是最高优先级：战斗时这个目标被抢占、
 * 战斗结束后自动继续前往落点。抵达后她在周围闲逛并开始 30s 计时；若 30s 内玩家没到附近、或玩家途中遭到攻击，
 * 就传送回玩家身边并结束带路。再投一次信标会替换目标，{@code /gf stop|come} 会清除（见 clearTask→clearGuide）。
 */
public class GuideToBeaconGoal extends Goal {
    private static final double ARRIVE_DIST_SQ = 9.0;   // ~3 blocks: close enough to count as "arrived"
    private static final double OWNER_NEAR_SQ = 36.0;   // ~6 blocks: owner caught up → done

    private final GirlfriendEntity gf;
    private final PathAssist assist = new PathAssist();
    private int repathCd;
    private int loiterCd;
    private int travelTicks;

    public GuideToBeaconGoal(GirlfriendEntity gf) {
        this.gf = gf;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        return gf.isGuiding() && gf.getTarget() == null;
    }

    @Override
    public boolean shouldContinue() {
        // Keep the goal alive while a guide is set; the selector preempts it for combat (higher priority),
        // and it resumes once the fight ends.
        return gf.isGuiding();
    }

    @Override
    public void start() {
        this.repathCd = 0;
        this.loiterCd = 0;
        this.travelTicks = 0;
        assist.reset();
    }

    @Override
    public void stop() {
        assist.reset();
        gf.getNavigation().stop();
    }

    @Override
    public boolean shouldRunEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (!(gf.getEntityWorld() instanceof ServerWorld sw)) return;
        BlockPos target = gf.getGuideTarget();
        if (target == null) return;
        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        Vec3d center = Vec3d.ofCenter(target);
        gf.getLookControl().lookAt(center);

        if (!gf.isGuideArrived()) {
            travel(sw, b, target, center);
        } else {
            loiter(sw, b, target, center);
        }
    }

    /** Path to the beacon, bridging/pillaring through rough terrain when stuck; mark arrived when close. */
    private void travel(ServerWorld sw, GirlfriendConfig.Behavior b, BlockPos target, Vec3d center) {
        double dsq = gf.squaredDistanceTo(center);
        if (dsq <= ARRIVE_DIST_SQ) {
            gf.getNavigation().stop();
            gf.markGuideArrived();
            return;
        }
        travelTicks++;

        if (--repathCd <= 0) {
            repathCd = 10;
            gf.getNavigation().startMovingTo(center.x, center.y, center.z, b.moveSpeed * 1.1);
        }

        // Terrain assist: when she genuinely can't path there (a wall / a gap / a height), break or lay
        // transient void matter — this is the whole point (helping the player cross rough ground).
        if (b.allowOwnerPathAssist) {
            Path path = gf.getNavigation().getCurrentPath();
            boolean reaches = path != null && path.reachesTarget();
            if (gf.getNavigation().isIdle() && !reaches) {
                assist.tickToward(sw, gf, b, target);
            } else if (!gf.getNavigation().isIdle()) {
                assist.reset();
            }
        }

        // If she's been trying a very long time and is at least nearish, treat it as arrived so she
        // doesn't grind forever on an impossible spot.
        if (travelTicks > 600 && dsq <= 64.0) {
            gf.getNavigation().stop();
            gf.markGuideArrived();
        }
    }

    /** Loiter near the beacon; finish when the owner arrives, or teleport back on timeout / owner-attacked. */
    private void loiter(ServerWorld sw, GirlfriendConfig.Behavior b, BlockPos target, Vec3d center) {
        PlayerEntity owner = gf.getOwner();

        // Owner caught up to the spot → guide complete.
        if (owner != null && owner.squaredDistanceTo(center) <= OWNER_NEAR_SQ) {
            speak(ConfigManager.get().prompts.guideArrive);
            gf.clearGuide();
            gf.getNavigation().stop();
            return;
        }

        // Owner was attacked while she's waiting here, or 30s passed without him showing → go back to him.
        if (gf.isRushingToOwner() || gf.guideLoiterExpired()) {
            if (owner != null) {
                gf.getNavigation().stop();
                gf.requestTeleport(owner.getX(), owner.getY(), owner.getZ());
            }
            speak(ConfigManager.get().prompts.guideTimeout);
            gf.clearGuide();
            return;
        }

        // Gentle pottering around the beacon while she waits.
        if (--loiterCd <= 0 && gf.getNavigation().isIdle()) {
            loiterCd = 20 + gf.getRandom().nextInt(40);
            double r = 2.5;
            double nx = center.x + (gf.getRandom().nextDouble() * 2 - 1) * r;
            double nz = center.z + (gf.getRandom().nextDouble() * 2 - 1) * r;
            gf.getNavigation().startMovingTo(nx, center.y, nz, b.moveSpeed * 0.5);
        }
    }

    private void speak(String prompt) {
        if (MCGirlfriendMod.BRAIN != null && prompt != null && !prompt.isBlank()) {
            MCGirlfriendMod.BRAIN.proactive(gf, prompt);
        }
    }
}
