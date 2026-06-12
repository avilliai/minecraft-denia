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
 * Keeps the girlfriend near her owner while the follow flag is on. Faster and
 * snappier than the MVP version: she jogs when far, strolls when close, and
 * teleports if she falls hopelessly behind (laggy chunks, the owner pearling,
 * etc.). Yields to combat and to any active work job so she can finish a task.
 */
public class FollowOwnerGoal extends Goal {
    private final GirlfriendEntity gf;
    private final PathAssist assist = new PathAssist();
    private PlayerEntity target;
    private int repathCooldown;

    public FollowOwnerGoal(GirlfriendEntity gf) {
        this.gf = gf;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (!gf.isFollowing() || gf.getTask() != null || gf.getTarget() != null) return false;
        // While she's leading the owner over to a noticed chest, let that goal finish (she leads him there)
        // instead of follow yanking her back — PerceiveChestGoal bails on its own if he strays too far.
        if (gf.isLeadingChest()) return false;
        // Napping near home while the owner is parked nearby: let her sleep (SleepAtHomeGoal wakes her the
        // moment he starts moving again, so following resumes at once).
        if (gf.isSleeping() && gf.isOwnerStationary()) return false;
        PlayerEntity owner = gf.getOwner();
        if (owner == null || owner.isSpectator()) return false;
        if (gf.squaredDistanceTo(owner) < square(gf.effectiveFollowStartDistance())) return false;
        this.target = owner;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        if (!gf.isFollowing() || target == null || gf.getTask() != null || gf.getTarget() != null) return false;
        if (gf.isLeadingChest()) return false;
        if (gf.isSleeping() && gf.isOwnerStationary()) return false;
        if (target.isRemoved() || target.isSpectator()) return false;
        // While the owner is parked she only needs to get back inside the roam leash; while he's
        // moving she closes all the way to followStopDistance (the start/stop gap kills oscillation).
        double stop = gf.isOwnerStationary()
            ? gf.effectiveFollowStartDistance()
            : ConfigManager.get().behavior.followStopDistance;
        return gf.squaredDistanceTo(target) > square(stop);
    }

    @Override
    public void start() {
        this.repathCooldown = 0;
    }

    @Override
    public void stop() {
        this.target = null;
        assist.reset();
        gf.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (target == null) return;
        gf.getLookControl().lookAt(target, 30.0f, 30.0f);

        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        double dsq = gf.squaredDistanceTo(target);
        if (dsq > square(b.teleportDistance)) {
            gf.getNavigation().stop();
            gf.requestTeleport(target.getX(), target.getY(), target.getZ());
            return;
        }

        // Owner is ABOVE her and she can't WALK up. Stop and pillar straight up on void matter, rather
        // than the old behaviour of standing at his XZ far below and never climbing. Triggers when she's
        // roughly under him OR when she simply can't path up to him (nav idle / path doesn't reach).
        // Dedicated mode (nav stopped) so a walk-repath can't tug her off the pillar.
        if (gf.getEntityWorld() instanceof ServerWorld sw && b.allowOwnerPathAssist
                && dsq < square(b.bridgeMaxDistance)) {
            double dy = target.getY() - gf.getY();
            double dx = target.getX() - gf.getX();
            double dz = target.getZ() - gf.getZ();
            double horiz2 = dx * dx + dz * dz;
            Path cur = gf.getNavigation().getCurrentPath();
            boolean cannotReach = gf.getNavigation().isIdle() || (cur != null && !cur.reachesTarget());
            if (dy >= 2.0 && (horiz2 <= 9.0 || (cannotReach && horiz2 <= 36.0))) {
                gf.getNavigation().stop();
                assist.tickToward(sw, gf, b, target.getBlockPos());
                return;
            }
        }

        if (--repathCooldown <= 0) {
            repathCooldown = 8;
            double startSq = square(b.followStartDistance);
            // Jog when far behind, stroll when close.
            double mult = dsq > startSq * 4 ? 1.5 : 1.0;
            gf.getNavigation().startMovingTo(target, b.moveSpeed * mult);
        }

        // Owner-only terrain assist: only when she genuinely can't path to him (a wall / a gap).
        if (gf.getEntityWorld() instanceof ServerWorld sw) {
            Path path = gf.getNavigation().getCurrentPath();
            boolean reaches = path != null && path.reachesTarget();
            if (gf.getNavigation().isIdle() && !reaches
                    && dsq > square(b.followStopDistance) && dsq < square(b.bridgeMaxDistance)) {
                assist.tick(sw, gf, b);
            } else if (!gf.getNavigation().isIdle()) {
                assist.reset();
            }
        }
    }

    private static double square(double v) {
        return v * v;
    }
}
