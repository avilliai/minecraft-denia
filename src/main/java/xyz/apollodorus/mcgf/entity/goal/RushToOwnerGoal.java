package xyz.apollodorus.mcgf.entity.goal;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.config.GirlfriendConfig;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;
import xyz.apollodorus.mcgf.entity.work.PathAssist;

import java.util.EnumSet;

/**
 * Top-priority emergency goal: the moment her bound owner takes a hit, she drops
 * whatever she's doing — work, following, even chasing another mob — and sprints
 * to his side for a few seconds. Driven by {@link GirlfriendEntity#markOwnerAttacked}
 * (set from the server damage hook) plus a short timer; once she arrives, the normal
 * {@link ProtectOwnerGoal} target + {@link DaniyaAttackGoal} take over to punish the attacker.
 *
 * <p>It sits at goal priority 1 (above melee / follow / work) so it preempts their
 * MOVE control while the rush window is open. Only this goal and the follow goal may
 * later bridge/break terrain toward the owner (see PathAssist, §D); chasing a mob
 * never gets that help — she stays honest about what she can reach.
 */
public class RushToOwnerGoal extends Goal {
    private final GirlfriendEntity gf;
    private final PathAssist assist = new PathAssist();
    private PlayerEntity owner;
    private int repathCooldown;

    public RushToOwnerGoal(GirlfriendEntity gf) {
        this.gf = gf;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (!gf.isRushingToOwner()) return false;
        PlayerEntity o = gf.getOwner();
        if (o == null || o.isSpectator() || o.isRemoved()) return false;
        // Already at his side → nothing to rush toward.
        if (gf.squaredDistanceTo(o) < square(reachDistance())) return false;
        this.owner = o;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        if (!gf.isRushingToOwner() || owner == null) return false;
        if (owner.isRemoved() || owner.isSpectator()) return false;
        return gf.squaredDistanceTo(owner) > square(reachDistance());
    }

    @Override
    public void start() {
        this.repathCooldown = 0;
    }

    @Override
    public void stop() {
        this.owner = null;
        assist.reset();
        gf.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (owner == null) return;
        gf.getLookControl().lookAt(owner, 30.0f, 30.0f);

        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        double dsq = gf.squaredDistanceTo(owner);
        // Teleport only if she's hopelessly far behind — otherwise she runs, so it
        // reads as urgency rather than an X-ray blink to his side.
        if (dsq > square(b.teleportDistance)) {
            gf.getNavigation().stop();
            gf.requestTeleport(owner.getX(), owner.getY(), owner.getZ());
            return;
        }

        if (--repathCooldown <= 0) {
            repathCooldown = 6;
            gf.getNavigation().startMovingTo(owner, b.moveSpeed * 1.8); // emergency sprint
        }

        // Owner-only terrain assist: kick in only when she genuinely can't path to him.
        if (gf.getEntityWorld() instanceof ServerWorld sw) {
            Path path = gf.getNavigation().getCurrentPath();
            boolean reaches = path != null && path.reachesTarget();
            if (gf.getNavigation().isIdle() && !reaches
                    && dsq > square(reachDistance()) && dsq < square(b.bridgeMaxDistance)) {
                assist.tick(sw, gf, b);
            } else if (!gf.getNavigation().isIdle()) {
                assist.reset();
            }
        }
    }

    private double reachDistance() {
        return Math.max(2.5, ConfigManager.get().behavior.followStopDistance);
    }

    private static double square(double v) {
        return v * v;
    }
}
