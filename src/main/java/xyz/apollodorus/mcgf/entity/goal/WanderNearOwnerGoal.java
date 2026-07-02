package xyz.apollodorus.mcgf.entity.goal;

import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.config.GirlfriendConfig;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;
import xyz.apollodorus.mcgf.entity.work.WorkUtil;

import java.util.EnumSet;
import java.util.function.Predicate;

/**
 * Gentle, varied idle pottering for when the owner is parked (or she's been told to stay), instead
 * of the old mechanical back-and-forth. Most of the time she simply rests — this goal stays inactive
 * and the look goals keep her glancing around / at the owner. Now and then (a low random chance) she
 * picks ONE small thing to do, chosen at random so it reads like a real person mooching about:
 *
 * <ul>
 *   <li>drift to the owner's side and idle close (only while following);</li>
 *   <li>a tiny shuffle in place;</li>
 *   <li>an amble further out to take in the surroundings (within the idle roam leash).</li>
 * </ul>
 *
 * <p>Because the parked follow leash is widened to {@code idleRoamRadius}
 * (see {@link GirlfriendEntity#effectiveFollowStartDistance}), these strolls no longer trip the
 * follow goal into yanking her back — which is what made the MVP version pace endlessly.
 */
public class WanderNearOwnerGoal extends Goal {
    private final GirlfriendEntity gf;
    private double tx, ty, tz;
    private double speedMult = 0.5;

    public WanderNearOwnerGoal(GirlfriendEntity gf) {
        this.gf = gf;
        this.setControls(EnumSet.of(Control.MOVE));
    }

    @Override
    public boolean canStart() {
        if (gf.getTask() != null || gf.getTarget() != null) return false;
        if (!gf.getNavigation().isIdle()) return false;

        PlayerEntity owner = gf.getOwner();
        boolean following = gf.isFollowing();
        if (following) {
            if (owner == null) return false;
            // Only potter about once he's actually loitering nearby; if he's covering ground, FollowOwnerGoal has her.
            if (!gf.isOwnerLoitering()) return false;
            double leash = gf.effectiveFollowStartDistance();
            if (gf.squaredDistanceTo(owner) > leash * leash) return false;
        }
        // Rare trigger → she mostly just rests, varying when she next stirs.
        if (gf.getRandom().nextInt(130) != 0) return false;

        pickTarget(owner, following);
        return true;
    }

    /** Choose one varied idle move (drift to owner / small shuffle / further amble). */
    private void pickTarget(PlayerEntity owner, boolean following) {
        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        int roll = gf.getRandom().nextInt(100);
        BlockPos anchor = anchor(owner, following);
        double radius;

        if (following && owner != null && roll < 30) {
            anchor = owner.getBlockPos();   // drift to his side and idle close
            radius = 1.5;
            speedMult = 0.9;
        } else if (roll < 70) {
            radius = Math.min(3.0, b.wanderRadius); // a small shuffle in place
            speedMult = 0.85;
        } else {
            // 丰富闲逛·觅食：偶尔朝附近的花/树/水边走过去，而不是纯随机方向——像真的被什么吸引了。
            BlockPos poi = (gf.getRandom().nextInt(100) < 45) ? findPoi(b) : null;
            if (poi != null) {
                this.tx = poi.getX() + 0.5;
                this.tz = poi.getZ() + 0.5;
                this.ty = poi.getY();
                this.speedMult = 0.95;
                return;
            }
            radius = following ? b.idleRoamRadius * 0.65 : b.wanderRadius; // amble further out
            speedMult = 0.95;
        }

        this.tx = anchor.getX() + 0.5 + (gf.getRandom().nextDouble() * 2 - 1) * radius;
        this.tz = anchor.getZ() + 0.5 + (gf.getRandom().nextDouble() * 2 - 1) * radius;
        this.ty = anchor.getY();
    }

    private static final Predicate<BlockState> POI = st ->
        st.isIn(BlockTags.FLOWERS) || WorkUtil.isLog(st) || st.getFluidState().isIn(FluidTags.WATER);

    /** Nearest "interesting" block within wanderRadius — a flower, a tree, or open water — to amble toward. */
    private BlockPos findPoi(GirlfriendConfig.Behavior b) {
        int r = (int) Math.max(4, b.wanderRadius);
        return WorkUtil.findNearestBlock(gf.getEntityWorld(), gf.getBlockPos(), r, POI, null);
    }

    @Override
    public boolean shouldContinue() {
        return !gf.getNavigation().isIdle() && gf.getTask() == null && gf.getTarget() == null;
    }

    @Override
    public void start() {
        gf.getNavigation().startMovingTo(tx, ty, tz, ConfigManager.get().behavior.moveSpeed * speedMult);
    }

    @Override
    public void stop() {
        gf.getNavigation().stop();
    }

    private BlockPos anchor(PlayerEntity owner, boolean following) {
        if (following && owner != null) return owner.getBlockPos();
        if (gf.getHomePos() != null) return gf.getHomePos();
        return gf.getBlockPos();
    }
}
