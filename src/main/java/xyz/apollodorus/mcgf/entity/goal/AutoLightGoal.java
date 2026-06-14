package xyz.apollodorus.mcgf.entity.goal;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import xyz.apollodorus.mcgf.combat.AbilityManager;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.util.EnumSet;

/**
 * 自动照明：当周围光照等级过低时，在她周围或头顶放置悬浮的发光虚质方块提供照明。
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
     * 方块，以免把矿物占掉。优先正上方（头顶上一格），其次她周围头部/脚部高度的空位，再不行往上多找一格。
     * 隧道/矿洞里她刚挖出的空间通常就有这些空位。
     */
    private BlockPos findBestLightPosition(ServerWorld world) {
        BlockPos foot = gf.getBlockPos();
        // 1) 正上方（头顶上方一格）——最不挡路，绝不占矿。
        BlockPos overhead = foot.up(2);
        if (canPlaceLight(world, overhead)) return overhead;
        // 2) 她周围的空位：先头部高度、再脚部高度的 4 个水平相邻格。
        for (int dy = 1; dy >= 0; dy--) {
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos p = foot.up(dy).offset(d);
                if (canPlaceLight(world, p)) return p;
            }
        }
        // 3) 再高一格兜底。
        BlockPos high = foot.up(3);
        if (canPlaceLight(world, high)) return high;
        return null;
    }

    /** 只把光块放进空气/可替换格——绝不替换实心方块（脚下/周围的矿都不动）；也不重复放已有的光块。 */
    private boolean canPlaceLight(ServerWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        if (!state.isAir() && !state.isReplaceable()) return false;
        return !state.isOf(xyz.apollodorus.mcgf.block.ModBlocks.VOID_BLOCK);
    }
}
