package xyz.apollodorus.mcgf.entity.goal;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import xyz.apollodorus.mcgf.combat.AbilityManager;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.util.EnumSet;

/**
 * 自动照明：当周围光照等级过低时，在脚下放置虚质方块提供照明。
 * 虚质方块有自然发光效果，下矿时特别有用，省火把了。
 */
public class AutoLightGoal extends Goal {
    private static final int LIGHT_THRESHOLD = 7; // 光照等级低于此值时放置
    private static final long PLACEMENT_COOLDOWN = 100L; // 5秒冷却，避免过于频繁
    private static final long VOID_LIGHT_LIFETIME = 2400L; // 虚质光源持续120秒后消失（统一为120秒）

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
     * 选放置点：首选把脚下那块普通实心地板临时换成发光虚质块（120s 后自动还原），她就站在发光地板上——
     * 隧道里、开阔地都管用，也不会挡路。脚下动不了（基岩/方块实体/本来就是空）时，退而在头顶上方找个
     * 黑暗的空气格放一个悬浮光块。之前的实现要求「脚下是空气」，可她几乎总站在实心地面上，所以从不触发。
     */
    private BlockPos findBestLightPosition(ServerWorld world) {
        BlockPos foot = gf.getBlockPos();
        BlockPos ground = foot.down();
        if (canReplaceFloor(world, ground)) return ground;

        BlockPos overhead = foot.up(2);
        if (canPlaceInAir(world, overhead)) return overhead;
        return null;
    }

    /** 普通实心地板才可被临时替换：排除空气/可替换方块、已放的光块、基岩等不可破坏方块、箱子/熔炉等方块实体。 */
    private boolean canReplaceFloor(ServerWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        if (state.isAir() || state.isReplaceable()) return false;
        if (state.isOf(xyz.apollodorus.mcgf.block.ModBlocks.VOID_BLOCK)) return false; // 别把已放的光块当地板重复替换
        if (state.getHardness(world, pos) < 0) return false;   // 基岩/屏障等不可破坏
        return world.getBlockEntity(pos) == null;              // 别动有数据的方块（箱子/熔炉…）
    }

    /** 头顶悬浮光块的落点：必须是空气/可替换，且当前确实偏暗。 */
    private boolean canPlaceInAir(ServerWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        if (!state.isAir() && !state.isReplaceable()) return false;
        return world.getLightLevel(pos) < LIGHT_THRESHOLD;
    }
}
