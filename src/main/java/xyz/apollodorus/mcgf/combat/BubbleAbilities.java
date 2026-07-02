package xyz.apollodorus.mcgf.combat;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import xyz.apollodorus.mcgf.ai.SpeechBus;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.config.GirlfriendConfig;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.util.List;

/**
 * 达妮娅的「形态一」泡泡连招（甜美·布景之形）。手持专武泡泡杖，四段平A围绕泡泡展开：
 * <ul>
 *   <li><b>1a</b> {@link #castShot} — 朝敌人吐一串泡泡直击。</li>
 *   <li><b>2a</b> {@link #castShot}(strong) — 更猛的泡泡爆，带一点小范围。</li>
 *   <li><b>3a</b> {@link #castBigBubble} — 在目标身上罩一个大泡泡，困住并轻轻托起。</li>
 *   <li><b>4a</b> {@link #castPop} — 引爆泡泡，范围伤害 + 一大片破裂水花。</li>
 * </ul>
 * 纯粒子实现（BUBBLE/SPLASH/CLOUD），不引入新实体；所有范围伤害仅作用于 {@link HostileEntity}。
 */
public final class BubbleAbilities {
    private BubbleAbilities() {}

    // 形态一主配色：粉色 / 白色 / 蓝色（参考专武泡泡杖）。之前缺粉色，这里把粉提为主色之一。
    private static final int BLUE = 0x5BC8FF;      // 泡泡蓝
    private static final int PINK = 0xFF8FD4;      // 主粉色
    private static final int WHITE = 0xFFFFFF;     // 白色高光

    /** 1a / 2a — 吐泡泡直击目标。伤害在**泡泡飞到目标时**才结算（不再抬手即出伤）；strong=2a 伤害更高带小范围溅射。 */
    public static void castShot(ServerWorld sw, GirlfriendEntity gf, LivingEntity target, boolean strong) {
        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        Vec3d from = new Vec3d(gf.getX(), gf.getEyeY() - 0.2, gf.getZ());
        Vec3d to = target.getEntityPos().add(0, target.getHeight() * 0.5, 0);
        int duration = strong ? 20 : 16;
        floatBubblesToward(sw, from, to, strong);
        // 1a/2a 自带音效，在达妮娅身边播放。
        SpeechBus.playClip(gf, strong ? "2a" : "1a", new Vec3d(gf.getX(), gf.getEyeY(), gf.getZ()), 1.0f);
        final LivingEntity t = target;
        final float dmg = (float) (b.bubbleDamage * (strong ? 1.4 : 1.0));
        // 命中结算：泡泡抵达目标的那一刻才造成伤害。
        AbilityManager.delay(sw, sw.getTime() + duration, () -> {
            if (t == null || !t.isAlive()) return;
            t.damage(sw, sw.getDamageSources().mobAttack(gf), dmg);
            if (strong) {
                Vec3d c = t.getEntityPos().add(0, t.getHeight() * 0.5, 0);
                damageNearbyHostiles(sw, gf, c, 2.2, dmg * 0.5f, t);
            }
        });
    }

    /**
     * 3a — 大泡泡：立刻在目标身上罩一个泡泡把它整个包裹住，困住（减速）并轻轻托起浮空（LEVITATION，幅度温和
     * 让它别飞出射程），泡泡随目标移动持续约 2.5 秒；同时**把附近的敌怪牵引**到这个被泡泡困住的目标处聚拢，
     * 方便随后 4a 一锅端。
     */
    public static void castBigBubble(ServerWorld sw, GirlfriendEntity gf, LivingEntity target) {
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 3));
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.LEVITATION, 45, 0)); // 温和浮空，留在射程内
        // 3a 音效在目标身上播放（不是达妮娅身边）。
        SpeechBus.playClip(gf, "3a", target.getEntityPos().add(0, target.getHeight() * 0.5, 0), 1.0f);
        final long start = sw.getTime();
        final long end = start + 50;     // ~2.5s 包裹
        final LivingEntity t = target;
        AbilityManager.schedule(sw, end, () -> {
            if (!t.isAlive()) return;
            Vec3d c = t.getEntityPos().add(0, t.getHeight() * 0.5, 0);
            double r = Math.max(0.9, t.getHeight() * 0.6);
            wrapBubble(sw, c, r, (sw.getTime() - start));
            pullHostilesToward(sw, gf, c, 5.0, t);   // 牵引附近怪聚向被困目标
        });
    }

    /** 4a — 引爆泡泡：以**被困目标当前位置**为中心的范围伤害 + 满屏破裂水花。纯粒子+伤害，绝不破坏场景方块。 */
    public static void castPop(ServerWorld sw, GirlfriendEntity gf, LivingEntity target) {
        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        if (target == null) return;
        Vec3d c = target.getEntityPos().add(0, target.getHeight() * 0.5, 0);
        float dmg = (float) (b.bubbleDamage * 2.0);
        // 先确保把中心目标本身打到，再 AoE 周围（exclude=null 已含目标，这里额外直伤一次保证"有伤害"）。
        if (target.isAlive()) target.damage(sw, sw.getDamageSources().mobAttack(gf), dmg);
        damageNearbyHostiles(sw, gf, c, 3.6, dmg, target);
        sw.spawnParticles(ParticleTypes.BUBBLE_POP, c.x, c.y, c.z, 80, 1.6, 1.0, 1.6, 0.2);
        sw.spawnParticles(new DustParticleEffect(PINK, 1.5f), c.x, c.y, c.z, 40, 1.5, 0.9, 1.5, 0.2);
        sw.spawnParticles(new DustParticleEffect(BLUE, 1.5f), c.x, c.y, c.z, 36, 1.5, 0.9, 1.5, 0.2);
        sw.spawnParticles(new DustParticleEffect(WHITE, 1.1f), c.x, c.y, c.z, 24, 1.3, 0.8, 1.3, 0.2);
        sw.spawnParticles(ParticleTypes.GLOW, c.x, c.y, c.z, 18, 1.2, 0.7, 1.2, 0.05);
        sw.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 14, 1.0, 0.6, 1.0, 0.08);
        // 4a 引爆音效在目标位置播放（不是达妮娅位置）。
        SpeechBus.playClip(gf, "4a", c, 1.0f);
    }

    // --- helpers ---

    /**
     * 漂浮的泡泡——从她手边生成一簇气泡，带着浮力一边轻轻上飘、一边小幅左右摇晃，缓缓飘向目标，途中走一道柔和的
     * 上凸弧线，抵达时炸开成一片破裂水花。配色按专武改成粉/白/蓝三主色（之前缺粉色），由 {@link AbilityManager}
     * 逐 tick 驱动（伤害仍在施法瞬间结算，时机不变）。
     */
    private static void floatBubblesToward(ServerWorld sw, Vec3d from, Vec3d to, boolean strong) {
        final int duration = strong ? 20 : 16;
        final long start = sw.getTime();
        final long end = start + duration;
        final int bubbles = strong ? 7 : 5;
        AbilityManager.schedule(sw, end, () -> {
            double t = (sw.getTime() - start) / (double) duration; // 0..1
            if (t > 1.0) return;
            Vec3d base = from.lerp(to, t);
            double arc = Math.sin(t * Math.PI) * 0.6;              // 飞行途中的浮力上凸
            for (int i = 0; i < bubbles; i++) {
                double phase = i * 1.7 + t * 6.0;                  // 每颗泡泡错相位 → 各自摇晃
                double ox = Math.cos(phase) * 0.22;
                double oz = Math.sin(phase) * 0.22;
                double oy = arc + (i - bubbles / 2.0) * 0.06 + Math.sin(phase * 0.5) * 0.1;
                double bx = base.x + ox, by = base.y + oy, bz = base.z + oz;
                // 三主色循环：粉 / 白 / 蓝，让粉色明确出现。
                int sel = i % 3;
                int col = sel == 0 ? PINK : (sel == 1 ? WHITE : BLUE);
                sw.spawnParticles(new DustParticleEffect(col, 1.4f), bx, by, bz, 1, 0.02, 0.03, 0.02, 0.0);
                if ((i & 1) == 0) sw.spawnParticles(ParticleTypes.GLOW, bx, by, bz, 1, 0.02, 0.02, 0.02, 0.0);
            }
            sw.spawnParticles(ParticleTypes.END_ROD, base.x, base.y + arc, base.z, 1, 0.03, 0.03, 0.03, 0.0);
            if (sw.getTime() >= end - 1) {                          // 抵达：炸开破裂水花（粉/白/蓝）
                sw.spawnParticles(ParticleTypes.BUBBLE_POP, to.x, to.y, to.z, strong ? 20 : 12, 0.25, 0.25, 0.25, 0.05);
                sw.spawnParticles(new DustParticleEffect(PINK, 1.3f), to.x, to.y, to.z, strong ? 14 : 9, 0.3, 0.3, 0.3, 0.05);
                sw.spawnParticles(new DustParticleEffect(BLUE, 1.3f), to.x, to.y, to.z, strong ? 12 : 8, 0.3, 0.3, 0.3, 0.05);
                sw.spawnParticles(new DustParticleEffect(WHITE, 1.0f), to.x, to.y, to.z, strong ? 8 : 5, 0.3, 0.3, 0.3, 0.05);
            }
        });
    }

    /** 一层把目标整个裹住的泡泡壳：一圈随时间转动的粉/白/蓝发光球面，外加几点 GLOW 高光。 */
    private static void wrapBubble(ServerWorld sw, Vec3d c, double r, long age) {
        double spin = age * 0.25;
        int ring = 14;
        for (int i = 0; i < ring; i++) {
            double a = spin + i * (Math.PI * 2 / ring);
            double px = c.x + Math.cos(a) * r;
            double py = c.y + Math.sin(a * 1.3) * r * 0.7;
            double pz = c.z + Math.sin(a) * r;
            int sel = i % 3;
            int col = sel == 0 ? PINK : (sel == 1 ? WHITE : BLUE);
            sw.spawnParticles(new DustParticleEffect(col, 1.2f), px, py, pz, 1, 0.0, 0.0, 0.0, 0.0);
            if (i % 4 == 0) sw.spawnParticles(ParticleTypes.GLOW, px, py, pz, 1, 0.0, 0.0, 0.0, 0.0);
        }
        sw.spawnParticles(ParticleTypes.BUBBLE, c.x, c.y, c.z, 3, r * 0.5, r * 0.5, r * 0.5, 0.0);
    }

    /** Drag nearby hostiles toward {@code c} (the bubbled target) so they cluster for the 4a pop. */
    private static void pullHostilesToward(ServerWorld sw, GirlfriendEntity gf, Vec3d c, double r, Entity exclude) {
        List<Entity> mobs = sw.getOtherEntities(gf, Box.of(c, 2 * r, 2 * r, 2 * r),
            e -> e instanceof HostileEntity && e.isAlive());
        for (Entity e : mobs) {
            if (e == exclude) continue;
            Vec3d to = c.subtract(e.getEntityPos());
            if (to.lengthSquared() > 1.2) {
                Vec3d pull = to.normalize().multiply(0.22);
                e.addVelocity(pull.x, pull.y * 0.2 + 0.02, pull.z);
            }
        }
    }

    private static void damageNearbyHostiles(ServerWorld sw, GirlfriendEntity gf, Vec3d c, double r, float dmg, Entity exclude) {
        List<Entity> mobs = sw.getOtherEntities(gf, Box.of(c, 2 * r, 2 * r, 2 * r),
            e -> e instanceof HostileEntity && e.isAlive());
        double r2 = r * r;
        for (Entity e : mobs) {
            if (e == exclude) continue;
            Vec3d mid = e.getEntityPos().add(0, e.getHeight() * 0.5, 0);
            if (mid.squaredDistanceTo(c) <= r2) {
                ((LivingEntity) e).damage(sw, sw.getDamageSources().mobAttack(gf), dmg);
            }
        }
    }
}
