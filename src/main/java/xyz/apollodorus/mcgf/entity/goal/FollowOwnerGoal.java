package xyz.apollodorus.mcgf.entity.goal;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
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
        if (gf.isGarrisoned()) return false;   // 驻守优先于跟随：被命令留守时不跟去
        // While she's leading the owner over to a noticed chest, let that goal finish (she leads him there)
        // instead of follow yanking her back — PerceiveChestGoal bails on its own if he strays too far.
        if (gf.isLeadingChest()) return false;
        // 同理：她空闲时主动去收菜/采集时，别因玩家走动就把她拽回来——WorkGoal 自己会在玩家走太远时放手。
        if (gf.isIdleWorking()) return false;
        // 她在床上打盹时，跟随完全让位——不再「玩家一动就把她叫醒」。何时醒由 SleepAtHomeGoal 决定
        // （睡够自然醒 / 床被拆 / 玩家走出 50 格）；战斗、冲向受击玩家等更高优先级目标仍会照常唤醒她。
        if (gf.isSleeping()) return false;
        PlayerEntity owner = gf.getOwner();
        if (owner == null || owner.isSpectator()) return false;
        // 注意力机制：她正忙自己的事且主人仍在近旁逗留 → 跟随让位，别因主人走几步就把她从钓鱼/种田上拽走。
        // 主人一旦不再逗留（开始转移阵地）或走出 recall 距离，selfBusyYield 转 false，跟随立刻收回。
        if (selfBusyYield(owner)) return false;
        if (gf.squaredDistanceTo(owner) < square(gf.effectiveFollowStartDistance())) return false;
        this.target = owner;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        if (!gf.isFollowing() || target == null || gf.getTask() != null || gf.getTarget() != null) return false;
        if (gf.isGarrisoned()) return false;   // 驻守优先于跟随
        if (gf.isLeadingChest()) return false;
        if (gf.isIdleWorking()) return false;
        if (gf.isSleeping()) return false;
        if (target.isRemoved() || target.isSpectator()) return false;
        if (selfBusyYield(target)) return false;
        // While the owner is loitering she only needs to get back inside the roam leash; while he's
        // covering ground she closes all the way to followStopDistance (the start/stop gap kills oscillation).
        double stop = gf.isOwnerLoitering()
            ? gf.effectiveFollowStartDistance()
            : ConfigManager.get().behavior.followStopDistance;
        return gf.squaredDistanceTo(target) > square(stop);
    }

    /**
     * True while a self-directed activity (fishing / farming / idle gather / dazing) should keep follow
     * at bay. Two cases, both still bounded by the recall radius so a departing owner always reclaims her:
     * <ul>
     *   <li>committed: she's busy (isSelfBusy) AND the owner is still loitering nearby — small steps
     *       don't yank her, but once he covers ground (loitering→false) follow reclaims;</li>
     *   <li>commanded: an AI {@code go_fishing}/{@code tend_farm} freeRoam window is open — let her go do
     *       it even if he wanders a bit (the window expires on its own).</li>
     * </ul>
     */
    private boolean selfBusyYield(PlayerEntity owner) {
        boolean committed = gf.isSelfBusy() && gf.isOwnerLoitering();
        boolean commanded = gf.isFreeRoam();
        if (!committed && !commanded) return false;
        double recall = ConfigManager.get().behavior.selfBusyRecallDistance;
        return gf.squaredDistanceTo(owner) <= recall * recall;
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

        // 二形态浮空：像创造模式那样平滑飞向玩家。只控水平速度，竖直方向交给 tickFloat() 的悬浮控制；
        // 用「随距离收敛」的速度上限避免过冲，到 followStopDistance 就缓停——不再把寻路倍率(1.2)当速度
        // 直接 setVelocity，那会以 ~24 格/秒把她甩到玩家头顶来回抽搐。
        if (gf.isFormTwo()) {
            gf.getNavigation().stop();
            double dx = target.getX() - gf.getX();
            double dz = target.getZ() - gf.getZ();
            double horiz = Math.sqrt(dx * dx + dz * dz);
            double stop = Math.max(b.followStartDistance, 4.0); // 浮空跟随时离玩家更远些，别贴脸/压头顶
            Vec3d v = gf.getVelocity();
            if (horiz > stop) {
                // 比例控制：越接近越慢，上限 0.32 格/tick，平滑滑翔不过冲。
                double step = Math.min(0.32, (horiz - stop) * 0.25 + 0.04);
                gf.setVelocity(dx / horiz * step, v.y, dz / horiz * step);
            } else {
                gf.setVelocity(v.x * 0.6, v.y, v.z * 0.6); // 到位，水平缓停，竖直继续悬浮
            }
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
            // 只在「已基本走到主人正下方(±2格)且他在上方」时才就地垫高——像正常人一样：先尽量走/破障
            // 靠到目标正下方，再往上垫，省方块。离得还远时不在这里垫，落到下面用普通寻路 + 终端的破障
            // 协助(assist.tick)横向靠近，等贴到正下方这一支再触发垫高。
            if (dy >= 1.5 && horiz2 <= 4.0) {
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
