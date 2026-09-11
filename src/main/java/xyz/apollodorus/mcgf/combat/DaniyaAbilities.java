package xyz.apollodorus.mcgf.combat;

import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import xyz.apollodorus.mcgf.block.ModBlocks;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.config.GirlfriendConfig;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;
import xyz.apollodorus.mcgf.entity.projectile.VoidShardEntity;

import java.util.List;

/**
 * 达妮娅's ranged void kit (远程连招). Stateless casters invoked by {@link xyz.apollodorus.mcgf.entity.goal.DaniyaAttackGoal}:
 * <ul>
 *   <li><b>1a</b> {@link #castThrow} — hurl one void shard at the target.</li>
 *   <li><b>2a</b> {@link #castMeteor} — drop a big 虚质方块 from the sky for an AoE slam.</li>
 *   <li><b>a3</b> {@link #castShardSwarm} — a slowing shard volley + a 黑洞 that drags nearby foes in.</li>
 *   <li><b>a4</b> {@link #castDetonate} — detonate the void matter around the target (scenery destruction
 *       is gated on the {@code mobGriefing} gamerule; the damage itself is always hostile-only).</li>
 * </ul>
 * Every area effect is restricted to {@link HostileEntity} and never touches the owner or passive mobs.
 */
public final class DaniyaAbilities {
    private DaniyaAbilities() {}

    // 带装发光手配色：亮紫主体 + 青色描边/手指辉光（取代旧的黑烟）。
    private static final int HAND_PURPLE = 0xB060FF;
    private static final int HAND_CYAN = 0x6FE0FF;

    // ---- 1a ----
    public static void castThrow(ServerWorld sw, GirlfriendEntity gf, LivingEntity target) {
        float dmg = (float) ConfigManager.get().behavior.rangedDamage;
        // 两只带状虚质大手朝目标前推，把虚质块掷出
        voidHands(sw, shoulders(gf), aimAt(target), 10, 1.0);
        spawnShard(sw, gf, target, dmg, false, 1.6f, 0.6f);
        play(gf, SoundEvents.ENTITY_ENDER_PEARL_THROW, 0.9f, 1.2f);
    }

    // ---- 两只带状虚质手 ----

    private static Vec3d shoulders(GirlfriendEntity gf) {
        return gf.getEntityPos().add(0, gf.getHeight() * 0.78, 0);
    }

    private static Vec3d aimAt(LivingEntity target) {
        return target.getEntityPos().add(0, target.getHeight() * 0.5, 0);
    }

    /**
     * 两只「虚质大手」——带状（缎带感）的发光手臂。从 {@code origin} 左右两侧伸出，沿一道弧线朝 {@code aim}
     * 推出/抓击，末端聚成五指张开的「手」，在 {@code ticks} 内做一次前推动作（快出、略收）。改为发光带装手：
     * 亮紫缎带 + 青色辉光描边 + END_ROD 五指（取代旧的 SQUID_INK/黑烟）。纯粒子，不引入实体也不改模型；
     * 由 {@link AbilityManager} 逐 tick 驱动，所以即使施法者随后移动/卸载也不会泄漏。
     * {@code scale} 控制手的大小（地面普攻 ~1.0，天降巨手 ~2.2）。
     */
    private static void voidHands(ServerWorld sw, Vec3d origin, Vec3d aim, int ticks, double scale) {
        final long start = sw.getTime();
        final long end = start + ticks;
        Vec3d d = aim.subtract(origin);
        final Vec3d fwd = d.lengthSquared() < 1.0e-4 ? new Vec3d(0, -1, 0) : d.normalize();
        Vec3d perp = new Vec3d(-fwd.z, 0, fwd.x);
        if (perp.lengthSquared() < 1.0e-4) perp = new Vec3d(1, 0, 0);
        final Vec3d side = perp.normalize().multiply(0.55 * scale);
        final double reach = Math.min(d.length(), 6.0 * scale);
        AbilityManager.schedule(sw, end, () -> {
            double t = (sw.getTime() - start) / (double) ticks; // 0..1
            if (t > 1.0) return;
            double ext = Math.sin(Math.min(1.0, t * 1.2) * Math.PI) * 0.85 + t * 0.15; // 推出曲线
            for (int h = -1; h <= 1; h += 2) {                  // 左(-1)/右(+1) 两只手
                Vec3d shoulder = origin.add(side.multiply(h));
                Vec3d hand = shoulder.add(fwd.multiply(reach * ext));
                drawRibbon(sw, shoulder, hand, side.multiply(h), scale, t);
                drawHand(sw, hand, fwd, scale);
            }
        });
    }

    /** 一条发光带状手臂：肩→手之间一串亮紫粒子 + 青色辉光，加正弦波让它像缎带一样飘动起伏。 */
    private static void drawRibbon(ServerWorld sw, Vec3d a, Vec3d b, Vec3d side, double scale, double t) {
        int seg = 8;
        Vec3d lateral = side.lengthSquared() < 1.0e-4 ? new Vec3d(1, 0, 0) : side.normalize();
        DustParticleEffect purple = new DustParticleEffect(HAND_PURPLE, (float) (1.4 * scale));
        DustParticleEffect cyan = new DustParticleEffect(HAND_CYAN, (float) (1.0 * scale));
        for (int i = 0; i <= seg; i++) {
            double f = i / (double) seg;
            double wave = Math.sin(f * Math.PI * 2 + t * 8.0) * 0.18 * scale;
            Vec3d p = a.lerp(b, f).add(0, wave, 0).add(lateral.multiply(wave * 0.5));
            sw.spawnParticles(purple, p.x, p.y, p.z, 1, 0.03, 0.03, 0.03, 0.0);
            if (i % 2 == 0) sw.spawnParticles(cyan, p.x, p.y, p.z, 1, 0.04, 0.04, 0.04, 0.0);
            if (i % 3 == 0) sw.spawnParticles(ParticleTypes.GLOW, p.x, p.y, p.z, 1, 0.03, 0.03, 0.03, 0.0);
        }
    }

    /** 手掌：末端一团发光暗质 + 五条 END_ROD「手指」streak 朝前张开（发光，不再是黑烟）。 */
    private static void drawHand(ServerWorld sw, Vec3d c, Vec3d fwd, double scale) {
        sw.spawnParticles(new DustParticleEffect(HAND_PURPLE, (float) (1.6 * scale)),
            c.x, c.y, c.z, 6, 0.12 * scale, 0.12 * scale, 0.12 * scale, 0.0);
        sw.spawnParticles(ParticleTypes.GLOW, c.x, c.y, c.z, 4, 0.10 * scale, 0.10 * scale, 0.10 * scale, 0.0);
        Vec3d up = new Vec3d(0, 1, 0);
        Vec3d right = fwd.crossProduct(up);
        right = right.lengthSquared() < 1.0e-4 ? new Vec3d(1, 0, 0) : right.normalize();
        for (int f = -2; f <= 2; f++) {
            Vec3d tip = c.add(fwd.multiply(0.35 * scale)).add(right.multiply(f * 0.12 * scale)).add(0, 0.05 * scale, 0);
            sw.spawnParticles(ParticleTypes.END_ROD, tip.x, tip.y, tip.z, 2, 0.02, 0.02, 0.02, 0.0);
        }
    }

    // ---- 2a ----
    public static void castMeteor(ServerWorld sw, GirlfriendEntity gf, LivingEntity target) {
        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        BlockPos impact = target.getBlockPos();
        BlockPos top = impact.up(13);
        // A 2x2x2 mega-cube of void matter dropped together — reads as one huge falling block.
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    FallingBlockEntity meteor = FallingBlockEntity.spawnFromBlock(
                        sw, top.add(dx, dy, dz), ModBlocks.VOID_BLOCK.getDefaultState());
                    meteor.dropItem = false;
                    meteor.setDestroyedOnLanding();
                }
            }
        }
        // a giant pair of void hands looming above, slamming the block down toward the impact
        voidHands(sw, Vec3d.ofCenter(top).add(0, 2.5, 0), Vec3d.ofCenter(impact), 14, 2.2);
        sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, top.getX() + 0.5, top.getY() + 0.5, top.getZ() + 0.5,
            60, 0.6, 2.0, 0.6, 0.05);
        play(gf, SoundEvents.ENTITY_BLAZE_SHOOT, 1.0f, 0.5f);
        final float dmg = (float) (b.rangedDamage * 1.8);
        AbilityManager.delay(sw, sw.getTime() + 27, () -> meteorImpact(sw, gf, impact, dmg));
    }

    private static void meteorImpact(ServerWorld sw, GirlfriendEntity gf, BlockPos impact, float dmg) {
        Vec3d c = Vec3d.ofCenter(impact);
        damageHostiles(sw, gf, c, 4.0, dmg);
        sw.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 1, 0.0, 0.0, 0.0, 0.0);
        sw.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, c.x, c.y + 0.3, c.z, 90, 2.2, 0.8, 2.2, 0.15);
        sw.spawnParticles(ParticleTypes.LAVA, c.x, c.y + 0.2, c.z, 24, 1.6, 0.4, 1.6, 0.05);
        play(gf, SoundEvents.ENTITY_GENERIC_EXPLODE, 1.3f, 0.7f);
    }

    // ---- a3 ----
    public static void castShardSwarm(ServerWorld sw, GirlfriendEntity gf, LivingEntity target) {
        float dmg = (float) (ConfigManager.get().behavior.rangedDamage * 0.7);
        voidHands(sw, shoulders(gf), aimAt(target), 12, 1.2);   // 双手横扫，抛出虚质群
        for (int i = 0; i < 4; i++) spawnShard(sw, gf, target, dmg, true, 1.4f, 4.0f);
        play(gf, SoundEvents.ENTITY_EVOKER_CAST_SPELL, 1.0f, 1.2f);
        spawnBlackHole(sw, gf, target);
    }

    /**
     * 在目标脚下生成一个 黑洞：~5s 内把附近的敌怪牵引向核心，同时持续给目标本人「附着」虚质粒子（包裹其身体、
     * 跟随其移动），并标记一层短时缓速（被侵蚀的感觉）。牵引核心锚在施法瞬间目标的脚下位置。
     */
    public static void spawnBlackHole(ServerWorld sw, GirlfriendEntity gf, LivingEntity victim) {
        final Vec3d core = victim.getEntityPos();   // 目标脚下 = 黑洞核心
        long until = sw.getTime() + 100; // 5s
        play(gf, SoundEvents.BLOCK_PORTAL_TRIGGER, 0.8f, 0.5f);
        // 标记「被侵蚀」：附上一层缓速
        victim.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 100, 1));
        AbilityManager.schedule(sw, until, () -> {
            List<Entity> mobs = sw.getOtherEntities(gf, Box.of(core, 12, 8, 12),
                e -> e instanceof HostileEntity && e.isAlive());
            for (Entity e : mobs) {
                Vec3d to = core.subtract(e.getEntityPos());
                if (to.lengthSquared() > 0.6) {
                    Vec3d pull = to.normalize().multiply(0.45);
                    e.addVelocity(pull.x, pull.y * 0.3 + 0.05, pull.z);
                }
            }
            // 黑洞核心特效（目标脚下）
            sw.spawnParticles(ParticleTypes.PORTAL, core.x, core.y + 0.6, core.z, 10, 0.5, 0.5, 0.5, 0.6);
            sw.spawnParticles(ParticleTypes.SCULK_SOUL, core.x, core.y + 0.3, core.z, 3, 0.4, 0.2, 0.4, 0.0);
            sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, core.x, core.y + 0.2, core.z, 6, 0.2, 0.1, 0.2, 0.3);
            // 虚质粒子附着在目标身上（跟随其当前位置）
            if (victim.isAlive()) {
                Vec3d b = victim.getEntityPos().add(0, victim.getHeight() * 0.5, 0);
                double rh = victim.getHeight() * 0.5;
                double rw = 0.45;   // 横向半径固定即可（多数怪宽 ~0.6-0.9），避免依赖易改名的尺寸 API
                sw.spawnParticles(ParticleTypes.PORTAL, b.x, b.y, b.z, 6, rw, rh, rw, 0.4);
                sw.spawnParticles(ParticleTypes.SCULK_SOUL, b.x, b.y, b.z, 2, rw * 0.8, rh * 0.8, rw * 0.8, 0.0);
            }
        });
    }

    // ---- a4 ----
    public static void castDetonate(ServerWorld sw, GirlfriendEntity gf, LivingEntity target) {
        Vec3d c = target.getEntityPos().add(0, target.getHeight() * 0.5, 0);
        voidHands(sw, shoulders(gf), c, 12, 1.4);   // 双手在目标处合拢、攥碎
        // 引爆前先来一记**范围牵引**：把附近敌怪猛地拽向中心聚拢，再 ~0.6s 后引爆，正好一锅端。
        gatherPull(sw, gf, c, 6.0);
        play(gf, SoundEvents.BLOCK_PORTAL_TRIGGER, 0.8f, 0.6f);
        sw.spawnParticles(ParticleTypes.PORTAL, c.x, c.y, c.z, 40, 0.6, 0.6, 0.6, 0.8);
        sw.spawnParticles(ParticleTypes.SCULK_SOUL, c.x, c.y, c.z, 14, 0.5, 0.5, 0.5, 0.02);
        final BlockPos impactPos = target.getBlockPos();
        AbilityManager.delay(sw, sw.getTime() + 12, () -> detonateAt(sw, gf, c, impactPos));
    }

    /** Drag nearby hostiles toward {@code c} (the pre-detonation 范围牵引). */
    private static void gatherPull(ServerWorld sw, GirlfriendEntity gf, Vec3d c, double r) {
        List<Entity> mobs = sw.getOtherEntities(gf, Box.of(c, 2 * r, 2 * r, 2 * r),
            e -> e instanceof HostileEntity && e.isAlive());
        for (Entity e : mobs) {
            Vec3d to = c.subtract(e.getEntityPos());
            if (to.lengthSquared() > 0.6) {
                Vec3d pull = to.normalize().multiply(0.7);
                e.addVelocity(pull.x, pull.y * 0.3 + 0.1, pull.z);
            }
        }
    }

    private static void detonateAt(ServerWorld sw, GirlfriendEntity gf, Vec3d c, BlockPos impactPos) {
        // 家附近强制【不破坏方块】的爆炸，免得把家炸穿引怪进来；家外用 MOB 源，由 mobGriefing 决定是否破坏场景。
        World.ExplosionSourceType type = isNearHome(gf, impactPos)
            ? World.ExplosionSourceType.NONE
            : World.ExplosionSourceType.MOB;
        sw.createExplosion(gf, c.x, c.y, c.z, 3.0f, type);
        sw.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, c.x, c.y, c.z, 90, 2.0, 1.4, 2.0, 0.18);
        sw.spawnParticles(ParticleTypes.LAVA, c.x, c.y, c.z, 30, 1.6, 1.0, 1.6, 0.12);
        play(gf, SoundEvents.ENTITY_GENERIC_EXPLODE, 1.3f, 0.8f);
    }

    /** True when {@code pos} sits within 达妮娅's home-protect radius (so爆炸不该破坏地形). */
    private static boolean isNearHome(GirlfriendEntity gf, BlockPos pos) {
        BlockPos home = gf.getHomePos();
        if (home == null) return false;
        double r = ConfigManager.get().behavior.homeProtectRadius;
        return home.getSquaredDistance(pos) <= r * r;
    }

    // ---- shared helpers ----

    private static void spawnShard(ServerWorld sw, GirlfriendEntity gf, LivingEntity target,
                                   float dmg, boolean slow, float speed, float divergence) {
        VoidShardEntity shard = new VoidShardEntity(sw, gf);
        shard.configure(dmg, slow);
        // 形态二·制空权：开启追踪，让浮空狙击对会动的远程怪也可靠命中(仅形态二启用，形态一泡泡弹不追)。
        double homing = ConfigManager.get().behavior.voidShardHoming;
        if (homing > 0 && gf.isFormTwo()) shard.setHoming(target, homing);
        double sx = gf.getX(), sy = gf.getEyeY() - 0.1, sz = gf.getZ();
        shard.setPosition(sx, sy, sz);
        double tx = target.getX() - sx;
        double ty = (target.getY() + target.getHeight() * 0.5) - sy;
        double tz = target.getZ() - sz;
        shard.setVelocity(tx, ty, tz, speed, divergence);
        sw.spawnEntity(shard);
    }

    private static void damageHostiles(ServerWorld sw, GirlfriendEntity gf, Vec3d c, double r, float dmg) {
        List<Entity> mobs = sw.getOtherEntities(gf, Box.of(c, 2 * r, 2 * r, 2 * r),
            e -> e instanceof HostileEntity && e.isAlive());
        double r2 = r * r;
        for (Entity e : mobs) {
            Vec3d mid = e.getEntityPos().add(0, e.getHeight() * 0.5, 0);
            if (mid.squaredDistanceTo(c) <= r2) {
                ((LivingEntity) e).damage(sw, sw.getDamageSources().mobAttack(gf), dmg);
            }
        }
    }

    private static void play(GirlfriendEntity gf, SoundEvent sound, float vol, float pitch) {
        gf.getEntityWorld().playSound(null, gf.getX(), gf.getY(), gf.getZ(), sound, SoundCategory.HOSTILE, vol, pitch);
    }

    // Some SoundEvents constants are raw SoundEvent, others RegistryEntry<SoundEvent>; overload covers both.
    private static void play(GirlfriendEntity gf, RegistryEntry<SoundEvent> sound, float vol, float pitch) {
        play(gf, sound.value(), vol, pitch);
    }
}
