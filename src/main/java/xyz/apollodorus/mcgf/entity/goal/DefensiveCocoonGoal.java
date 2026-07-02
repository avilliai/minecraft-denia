package xyz.apollodorus.mcgf.entity.goal;

import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import xyz.apollodorus.mcgf.combat.AbilityManager;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.util.EnumSet;

/**
 * 二形态低血量防御茧：危急时（二形态、领域内、血量低于阈值）她把自己整个围进虚质里——脚下、头顶、以及前后左右
 * 四个方向各两格高，围成一个 1×1×2 的小室——并获得短时生命恢复；恢复结束（或领域结束）就自动撤掉所有虚质。
 * 这是被动，十分钟内只触发一次。
 *
 * <p>旧版会把她传送上抬 3 格再围（糊头、卡头）所以被停用。这版<strong>不传送</strong>：就地把她围住、暂停二形态
 * 悬浮（{@link GirlfriendEntity#setInDefensiveCocoon}）、原地静止回血，时间到/领域结束即解除并还原方块。
 */
public class DefensiveCocoonGoal extends Goal {
    private static final long COCOON_DURATION = 100L; // 回血 5 秒
    private static final long COOLDOWN = 12000L;      // 10 分钟（被动只触发一次/10min）
    private static final float HEALTH_THRESHOLD = 0.35f;

    private final GirlfriendEntity gf;
    private long lastCocoonTime = Long.MIN_VALUE / 2;
    private long cocoonEndTime;
    private BlockPos center;

    public DefensiveCocoonGoal(GirlfriendEntity gf) {
        this.gf = gf;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK, Control.JUMP));
    }

    @Override
    public boolean canStart() {
        if (!(gf.getEntityWorld() instanceof ServerWorld sw)) return false;
        if (!gf.isFormTwo() || !AbilityManager.hasDomain(gf)) return false;          // 二形态（领域内）才有
        if (gf.getHealth() / gf.getMaxHealth() > HEALTH_THRESHOLD) return false;     // 血量过低才触发
        if (sw.getTime() - lastCocoonTime < COOLDOWN) return false;                  // 10 分钟一次
        return gf.getTarget() != null;                                              // 有威胁、但血量危险
    }

    @Override
    public boolean shouldContinue() {
        if (!(gf.getEntityWorld() instanceof ServerWorld sw)) return false;
        if (!gf.isInDefensiveCocoon()) return false;
        if (!gf.isFormTwo() || !AbilityManager.hasDomain(gf)) return false;          // 领域/二形态结束 → 收茧
        return sw.getTime() < cocoonEndTime;
    }

    @Override
    public void start() {
        if (!(gf.getEntityWorld() instanceof ServerWorld sw)) return;
        lastCocoonTime = sw.getTime();
        cocoonEndTime = sw.getTime() + COCOON_DURATION;
        gf.setTarget(null);
        gf.getNavigation().stop();
        gf.setInDefensiveCocoon(true);     // 暂停悬浮，原地围茧
        gf.setVelocity(Vec3d.ZERO);
        center = gf.getBlockPos();
        buildCocoon(sw);
        sw.playSound(null, gf.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, gf.getSoundCategory(), 1.0f, 0.8f);
        gf.heal(4.0f);   // 即时回一点，立竿见影
        // Regen III、显示粒子——之前看不到回血主要是头卡方块在掉窒息伤抵消了；改了茧顶 + 加强这里就明显了。
        gf.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, (int) COCOON_DURATION, 2, false, true, true));
    }

    @Override
    public void tick() {
        if (!(gf.getEntityWorld() instanceof ServerWorld sw) || center == null) return;
        // 原地静止：水平拉回中心、竖直不上浮，让她安稳缩在茧里。
        double cx = center.getX() + 0.5, cz = center.getZ() + 0.5;
        Vec3d v = gf.getVelocity();
        gf.setVelocity((cx - gf.getX()) * 0.2, Math.min(0.0, v.y), (cz - gf.getZ()) * 0.2);
        if (sw.getTime() % 4 == 0) {
            double a = (sw.getTime() % 60) / 60.0 * Math.PI * 2;
            for (int i = 0; i < 3; i++) {
                double ang = a + i * Math.PI * 2 / 3;
                sw.spawnParticles(ParticleTypes.PORTAL, cx + Math.cos(ang) * 0.6,
                    center.getY() + 1.0, cz + Math.sin(ang) * 0.6, 1, 0, 0, 0, 0);
            }
        }
    }

    @Override
    public void stop() {
        if (gf.getEntityWorld() instanceof ServerWorld sw) {
            sw.playSound(null, gf.getBlockPos(), SoundEvents.BLOCK_GLASS_BREAK, gf.getSoundCategory(), 0.8f, 1.2f);
            sw.spawnParticles(ParticleTypes.ENCHANT, gf.getX(), gf.getY() + 1, gf.getZ(), 24, 0.4, 0.5, 0.4, 0.15);
        }
        AbilityManager.blocks().restoreOwned(this);   // 撤掉所有围茧虚质
        gf.setInDefensiveCocoon(false);
        center = null;
    }

    /** 把她围进虚质小室：脚下、四面三格高、以及头顶上方（{@code up(3)}，留出她 1.8 高的头，不再糊头窒息）。只在空气/可替换处放。 */
    private void buildCocoon(ServerWorld sw) {
        long expiry = cocoonEndTime + 20;
        placeVoid(sw, center.down(), expiry);    // 脚下
        placeVoid(sw, center.up(3), expiry);     // 头顶再往上两格——中心列 up(1)/up(2) 留空给头，避免卡头窒息
        for (Direction d : Direction.Type.HORIZONTAL) {
            placeVoid(sw, center.offset(d), expiry);         // 脚高四面
            placeVoid(sw, center.up().offset(d), expiry);    // 头高四面
            placeVoid(sw, center.up(2).offset(d), expiry);   // 再高一层四面，把上方也围严
        }
    }

    private void placeVoid(ServerWorld sw, BlockPos pos, long expiry) {
        BlockState st = sw.getBlockState(pos);
        if (st.isAir() || st.isReplaceable()) {
            AbilityManager.blocks().place(sw, pos, expiry, this);
        }
    }
}
