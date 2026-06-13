package xyz.apollodorus.mcgf.entity.goal;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import xyz.apollodorus.mcgf.combat.AbilityManager;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * 二形态防御茧：当血量不佳且在领域内时，她会上升并用虚质方块围成茧保护自己，
 * 获得缓慢生命恢复。持续5秒或领域结束后解除。10分钟CD。
 */
public class DefensiveCocoonGoal extends Goal {
    private static final long COCOON_DURATION = 100L; // 5秒
    private static final long COOLDOWN = 12000L; // 10分钟
    private static final float HEALTH_THRESHOLD = 0.35f; // 血量低于35%时触发

    private final GirlfriendEntity gf;
    private long lastCocoonTime = Long.MIN_VALUE / 2;
    private long cocoonEndTime;
    private BlockPos cocoonCenter;
    private List<BlockPos> cocoonBlocks = new ArrayList<>();
    private boolean inCocoon;

    public DefensiveCocoonGoal(GirlfriendEntity gf) {
        this.gf = gf;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK, Control.JUMP));
    }

    @Override
    public boolean canStart() {
        if (!(gf.getEntityWorld() instanceof ServerWorld sw)) return false;
        // 必须在二形态领域内
        if (!gf.isFormTwo() || !AbilityManager.hasDomain(gf)) return false;
        // 血量检查
        if (gf.getHealth() / gf.getMaxHealth() > HEALTH_THRESHOLD) return false;
        // 冷却时间
        if (sw.getTime() - lastCocoonTime < COOLDOWN) return false;
        // 不在战斗攻击动作中
        return gf.getTarget() != null; // 有敌人但血量危险
    }

    @Override
    public boolean shouldContinue() {
        if (!(gf.getEntityWorld() instanceof ServerWorld sw)) return false;
        // 领域结束或时间到了
        if (!AbilityManager.hasDomain(gf)) return false;
        if (sw.getTime() >= cocoonEndTime) return false;
        return inCocoon;
    }

    @Override
    public void start() {
        if (!(gf.getEntityWorld() instanceof ServerWorld sw)) return;

        // 记录触发时间
        lastCocoonTime = sw.getTime();
        cocoonEndTime = sw.getTime() + COCOON_DURATION;
        inCocoon = true;

        // 停止攻击
        gf.setTarget(null);
        gf.getNavigation().stop();

        // 上升到合适高度（当前位置上方3格）
        cocoonCenter = new BlockPos((int)gf.getX(), (int)gf.getY() + 3, (int)gf.getZ());
        gf.requestTeleport(cocoonCenter.getX() + 0.5, cocoonCenter.getY(), cocoonCenter.getZ() + 0.5);

        // 围成茧：周围一圈 + 上下
        createCocoon(sw);

        // 播放音效
        sw.playSound(null, gf.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
            gf.getSoundCategory(), 1.0f, 0.8f);

        // 给予缓慢生命恢复效果
        gf.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION,
            (int)COCOON_DURATION, 1, false, false, true));
    }

    @Override
    public void tick() {
        if (!(gf.getEntityWorld() instanceof ServerWorld sw)) return;

        // 保持在茧中心位置
        if (cocoonCenter != null) {
            double dx = cocoonCenter.getX() + 0.5 - gf.getX();
            double dy = cocoonCenter.getY() - gf.getY();
            double dz = cocoonCenter.getZ() + 0.5 - gf.getZ();
            if (dx*dx + dy*dy + dz*dz > 0.5) {
                gf.setVelocity(dx * 0.1, dy * 0.1, dz * 0.1);
            } else {
                gf.setVelocity(0, 0, 0);
            }
        }

        // 粒子效果：紫色粒子环绕
        if (sw.getTime() % 4 == 0 && cocoonCenter != null) {
            double angle = (sw.getTime() % 60) / 60.0 * Math.PI * 2;
            double radius = 1.8;
            for (int i = 0; i < 3; i++) {
                double a = angle + i * Math.PI * 2 / 3;
                double x = cocoonCenter.getX() + 0.5 + Math.cos(a) * radius;
                double z = cocoonCenter.getZ() + 0.5 + Math.sin(a) * radius;
                double y = cocoonCenter.getY() + (sw.getTime() % 20) / 20.0 * 2 - 1;
                sw.spawnParticles(ParticleTypes.PORTAL, x, y, z, 1, 0, 0, 0, 0);
            }
        }
    }

    @Override
    public void stop() {
        if (!(gf.getEntityWorld() instanceof ServerWorld sw)) return;

        // 移除茧
        destroyCocoon(sw);

        inCocoon = false;
        cocoonCenter = null;

        // 播放破茧音效
        sw.playSound(null, gf.getBlockPos(), SoundEvents.BLOCK_GLASS_BREAK,
            gf.getSoundCategory(), 0.8f, 1.2f);

        // 粒子爆发效果
        for (int i = 0; i < 20; i++) {
            double angle = Math.random() * Math.PI * 2;
            double vx = Math.cos(angle) * 0.3;
            double vz = Math.sin(angle) * 0.3;
            sw.spawnParticles(ParticleTypes.ENCHANT,
                gf.getX(), gf.getY() + 1, gf.getZ(),
                1, vx, 0.3, vz, 0.1);
        }
    }

    /** 创建茧：周围一圈虚质方块 */
    private void createCocoon(ServerWorld sw) {
        cocoonBlocks.clear();

        // 周围一圈（半径2）
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    // 只在外壳放置方块，不是实心的
                    boolean isShell = Math.abs(dx) == 2 || Math.abs(dz) == 2 || Math.abs(dy) == 1;
                    if (isShell) {
                        BlockPos pos = cocoonCenter.add(dx, dy, dz);
                        // 持续时间：茧结束时间 + 一点缓冲
                        AbilityManager.blocks().place(sw, pos, cocoonEndTime + 20, this);
                        cocoonBlocks.add(pos);
                    }
                }
            }
        }
    }

    /** 销毁茧 */
    private void destroyCocoon(ServerWorld sw) {
        // 通过 owner 参数移除所有属于这个茧的方块
        AbilityManager.blocks().restoreOwned(this);
        cocoonBlocks.clear();
    }
}
