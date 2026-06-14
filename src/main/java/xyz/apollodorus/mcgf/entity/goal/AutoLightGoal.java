package xyz.apollodorus.mcgf.entity.goal;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import xyz.apollodorus.mcgf.combat.AbilityManager;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * 自动照明：当周围光照等级过低时，在她附近（左右/后面/头顶）放置悬浮的发光虚质方块提供照明。
 * 虚质方块有自然发光效果，下矿时特别有用，省火把了。绝不替换脚下/周围的实心方块，以免把矿物占掉。
 */
public class AutoLightGoal extends Goal {
    private static final int LIGHT_THRESHOLD = 7; // 光照等级低于此值时放置
    private static final long PLACEMENT_COOLDOWN = 100L; // 5秒冷却，避免过于频繁
    private static final long VOID_LIGHT_LIFETIME = 2000L; // 虚质光源持续100秒后消失

    private final GirlfriendEntity gf;
    private long lastPlacementTime;

    public AutoLightGoal(GirlfriendEntity gf) {
        this.gf = gf;
        this.setControls(EnumSet.noneOf(Control.class)); // 不干扰其他行为
    }

    @Override
    public boolean canStart() {
        if (!(gf.getEntityWorld() instanceof ServerWorld)) return false;
        if (!ConfigManager.get().behavior.autoLight) return false;
        if (gf.isFormTwo()) return false; // 二形态专注输出，期间不放任何虚质方块

        // 只在地下或黑暗环境中自动照明
        int lightLevel = gf.getEntityWorld().getLightLevel(gf.getBlockPos());
        if (lightLevel >= LIGHT_THRESHOLD) return false;

        // 冷却时间
        long currentTime = gf.getEntityWorld().getTime();
        if (currentTime - lastPlacementTime < PLACEMENT_COOLDOWN) return false;

        // 不在战斗中、不在忙碌时
        return gf.getTarget() == null && gf.getTask() == null;
    }

    @Override
    public boolean shouldContinue() {
        return false; // 一次性行为
    }

    @Override
    public void start() {
        if (!(gf.getEntityWorld() instanceof ServerWorld sw)) return;

        BlockPos lightPos = findBestLightPosition(sw);
        if (lightPos != null) {
            // 放置虚质方块作为光源
            AbilityManager.blocks().place(sw, lightPos, sw.getTime() + VOID_LIGHT_LIFETIME, null);
            lastPlacementTime = sw.getTime();

            // 可选：播放一个小动画或粒子效果
            gf.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        }
    }

    @Override
    public void stop() {
        // 无需清理
    }

    /**
     * 选放置点：只在空气/可替换格里放一块悬浮的发光虚质块（~100s 后自动消失），绝不替换脚下/周围的实心
     * 方块，以免把矿物占掉。
     *
     * 关键：绝不放进她自己占据的两格（脚 foot、头 foot.up(1)），也不放在她头顶正上方近处——否则寻路/
     * 跳跃时她的头会卡进虚质方块里窒息卡死。改为优先在她身侧/身后放置，随机选方向，离她远一点；正上方
     * 只在抬高到头顶以上至少两格、且水平错开时才作为兜底。
     */
    private BlockPos findBestLightPosition(ServerWorld world) {
        BlockPos foot = gf.getBlockPos();

        // 候选点分三档优先级；每档内部随机打乱方向，避免每次都往同一边放。
        // 档 1：紧贴身侧/身后（距离 1 格，头部高度与稍高一格）——在她旁边，绝不会卡头。
        // 档 2：离她远一点（距离 2 格）——空间够时优先体验更好。
        // 档 3：头顶正上方但抬高到 up(3)（头顶以上两格）作为最后兜底，留出空隙不卡头。
        List<BlockPos> tier1 = new ArrayList<>();
        List<BlockPos> tier2 = new ArrayList<>();
        for (Direction d : Direction.Type.HORIZONTAL) {
            tier1.add(foot.offset(d).up(1));
            tier1.add(foot.offset(d).up(2));
            tier2.add(foot.offset(d, 2).up(1));
            tier2.add(foot.offset(d, 2).up(2));
        }
        shuffle(tier1);
        shuffle(tier2);

        // 远一点优先：先随机的远处（档 2），再身侧（档 1）。
        for (BlockPos p : tier2) {
            if (isSafe(world, foot, p)) return p;
        }
        for (BlockPos p : tier1) {
            if (isSafe(world, foot, p)) return p;
        }
        // 档 3：头顶以上两格兜底，留出空隙不卡头。
        BlockPos high = foot.up(3);
        if (isSafe(world, foot, high)) return high;
        return null;
    }

    /** 用她自己的随机源就地洗牌（Fisher-Yates），避免依赖 java.util.Random。 */
    private void shuffle(List<BlockPos> list) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = gf.getRandom().nextInt(i + 1);
            BlockPos tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }

    /** 放置点必须能放光块，且绝不落在她自己占据的两格（脚 + 头），杜绝卡头窒息。 */
    private boolean isSafe(ServerWorld world, BlockPos foot, BlockPos pos) {
        if (pos.equals(foot) || pos.equals(foot.up(1))) return false; // 她的身体两格，绝不占
        return canPlaceLight(world, pos);
    }

    /** 只把光块放进空气/可替换格——绝不替换实心方块（脚下/周围的矿都不动）；也不重复放已有的光块。 */
    private boolean canPlaceLight(ServerWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        if (!state.isAir() && !state.isReplaceable()) return false;
        return !state.isOf(xyz.apollodorus.mcgf.block.ModBlocks.VOID_BLOCK);
    }
}
