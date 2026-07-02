package xyz.apollodorus.mcgf.entity.goal;

import net.minecraft.entity.ai.goal.Goal;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.util.EnumSet;

/**
 * 她「自己的事」里最安静的一种：发会儿呆 / 赏景。空闲、导航停着、（跟随时）主人在近旁逗留，偶尔（低概率）
 * 她会停下脚步，仰头望望天、慢慢张望四周，什么也不做、什么也不说——这正是配合「沉默感知」的安静模式：玩家
 * 久不说话时，她不聒噪，只是安静地待着、有自己的心情。
 *
 * <p>优先级最低（12，低于张望/对视），所以只在别的什么都不想做时才冒出来；不产出物品、不说话（沉默本身就是
 * 这段的内容）。被战斗 / 跟随 / 冲向受击主人等更高优先级目标即时抢占。
 */
public class QuietIdleGoal extends Goal {
    private final GirlfriendEntity gf;
    private double skyX, skyY, skyZ;
    private int restTimer;

    public QuietIdleGoal(GirlfriendEntity gf) {
        this.gf = gf;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (gf.getTarget() != null || gf.getTask() != null) return false;
        if (gf.isSleeping() || gf.isLeadingChest() || gf.isGuiding() || gf.isRushingToOwner()) return false;
        if (gf.isSelfBusy()) return false;                       // 正忙别的自己的事就不打断它
        if (!gf.getNavigation().isIdle()) return false;
        if (gf.isFollowing() && !gf.isOwnerLoitering()) return false;
        if (gf.getRandom().nextInt(200) != 0) return false;      // 难得的氛围小节拍
        return true;
    }

    @Override
    public boolean shouldContinue() {
        if (restTimer <= 0) return false;
        if (gf.getTarget() != null || gf.getTask() != null) return false;
        return !gf.isRushingToOwner() && !gf.isSleeping();
    }

    @Override
    public void start() {
        gf.getNavigation().stop();
        double ang = gf.getRandom().nextDouble() * Math.PI * 2;
        this.skyX = gf.getX() + Math.cos(ang) * 6.0;
        this.skyZ = gf.getZ() + Math.sin(ang) * 6.0;
        this.skyY = gf.getEyeY() + 3.0 + gf.getRandom().nextInt(4);   // 仰头望天
        this.restTimer = 40 + gf.getRandom().nextInt(80);            // 发呆 2~6s
        gf.setActivity("发会儿呆");
    }

    @Override
    public void stop() {
        this.restTimer = 0;
        if (gf.getTask() == null && !gf.isSleeping()) gf.setActivity("闲着");
    }

    @Override
    public void tick() {
        gf.markSelfBusy(40);                 // 别因主人一步移动就把发呆打断
        gf.getNavigation().stop();
        gf.getLookControl().lookAt(skyX, skyY, skyZ);
        restTimer--;
    }
}
