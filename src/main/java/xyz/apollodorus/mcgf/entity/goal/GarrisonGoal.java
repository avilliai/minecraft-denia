package xyz.apollodorus.mcgf.entity.goal;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.BlockPos;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.util.EnumSet;

/**
 * 驻守：当她被明确命令留守某区域（{@link GirlfriendEntity#isGarrisoned()}）时，把她拉回锚点 {@code garrisonRadius}
 * 半径内——优先于跟随（{@link FollowOwnerGoal} 在驻守时让位）。战斗/冲向受击主人等更高优先级目标仍会临时把她带走，
 * 打完后她自己走回驻守区。靠近内圈才松手，避免在边界来回抖动。
 */
public class GarrisonGoal extends Goal {
    private final GirlfriendEntity gf;
    private int repathCd;

    public GarrisonGoal(GirlfriendEntity gf) {
        this.gf = gf;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (!gf.isGarrisoned() || gf.getTarget() != null) return false;
        return outside(radius());
    }

    @Override
    public boolean shouldContinue() {
        if (!gf.isGarrisoned() || gf.getTarget() != null) return false;
        return outside(radius() * 0.6);   // 回到内圈再松手，免得在边界反复横跳
    }

    @Override
    public void start() {
        this.repathCd = 0;
        gf.setActivity("驻守");
    }

    @Override
    public void stop() {
        gf.getNavigation().stop();
        if (gf.getTask() == null && !gf.isSleeping()) gf.setActivity("闲着");
    }

    @Override
    public void tick() {
        BlockPos g = gf.getGarrisonPos();
        if (g == null) return;
        gf.getLookControl().lookAt(g.getX() + 0.5, g.getY(), g.getZ() + 0.5);
        if (--repathCd <= 0) {
            repathCd = 10;
            gf.getNavigation().startMovingTo(g.getX() + 0.5, g.getY(), g.getZ() + 0.5,
                ConfigManager.get().behavior.moveSpeed);
        }
    }

    private double radius() {
        return Math.max(2.0, ConfigManager.get().behavior.garrisonRadius);
    }

    private boolean outside(double r) {
        BlockPos g = gf.getGarrisonPos();
        return g != null && gf.getBlockPos().getSquaredDistance(g) > r * r;
    }
}
