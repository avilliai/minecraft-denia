package xyz.apollodorus.mcgf.combat;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.config.GirlfriendConfig;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.util.List;

/**
 * One active 蚀域 (Erosion Field) — 达妮娅's ultimate domain, themed on her real Wuthering Waves kit
 * (Fusion/热熔 element + 虚质 void matter). On deploy it tiles the ground around her with 虚质方块,
 * raises floating void clusters at the four sky corners, and empowers her + the owner. While active
 * it paints fire/void particles, refreshes the buffs, and every few seconds pulls nearby hostiles to
 * the center and burns them (the Erosion Field's group-and-damage pulse). On end it restores the
 * terrain exactly and strips the empowerment — all driven by {@link AbilityManager}.
 *
 * <p>Leak-proofing: blocks are tracked in the shared {@link xyz.apollodorus.mcgf.world.TransientBlocks}
 * keyed by this instance (restored on end OR by their own far-future expiry as a backstop). The +25%
 * ATTACK_DAMAGE uses a fixed {@link #ATK_ID} so it is idempotently add/removable; the owner's buff is
 * applied only while inside and stripped on exit/end, so a logout/death can't leave it stuck.
 */
public final class DaniyaDomain {
    private static final Identifier ATK_ID = Identifier.of("mcgf", "domain_atk");
    private static final int PULSE_INTERVAL = 80;   // 4s — the group+pull cadence
    private static final int REFRESH_INTERVAL = 20;  // re-apply buffs every 1s
    private static final int DIAMOND_CORNERS = 3;    // 三个旋转菱形
    private static final double DIAMOND_HEIGHT = 3.4; // 菱形悬浮在地面上方的高度（抬高，像立在杆顶）
    private static final int DIAMOND_COLOR = 0x4DA8FF; // 发光电光蓝菱形（参考图）
    // 二形态配色：深蓝 / 黑 / 紫 + 发光。
    private static final int BLUE = 0x3A6BFF;
    private static final int DEEP_BLUE = 0x1B2A8F;
    private static final int PURPLE = 0x8A3FFF;

    private final ServerWorld world;
    private final GirlfriendEntity gf;
    private final BlockPos center;
    private final Vec3d centerVec;
    private final int radius;
    private final long startTick;
    private final long endTick;

    private DaniyaDomain(ServerWorld world, GirlfriendEntity gf, BlockPos center, int radius, long start, long end) {
        this.world = world;
        this.gf = gf;
        this.center = center;
        this.centerVec = Vec3d.ofCenter(center);
        this.radius = radius;
        this.startTick = start;
        this.endTick = end;
    }

    /** Build + deploy a domain centered on {@code gf}. */
    public static DaniyaDomain create(ServerWorld world, GirlfriendEntity gf) {
        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        long now = world.getTime();
        DaniyaDomain d = new DaniyaDomain(world, gf, gf.getBlockPos(), Math.max(2, b.domainRadius),
            now, now + Math.max(1, b.domainSeconds) * 20L);
        d.deploy(b);
        return d;
    }

    private void deploy(GirlfriendConfig.Behavior b) {
        gf.setFormTwo(true);   // 切入二形态：换肤、空手、虚质连招、浮空
        gf.setNoGravity(true); // 关重力，浮空才稳——否则 tickFloat() 一直在和重力打架，跟随时上下抽搐
        // 不再替换领域内的方块（实测很丑）——改成地面上的漩涡光纹 + 悬浮装饰，地形保持原样。
        addDamageBonus(gf, b.domainDamageBonus);
        // 收敛开场：去掉刺耳的 Warden 音爆，只留一记低沉的信标激活声（震动感太强→减弱）。
        playAt(SoundEvents.BLOCK_BEACON_ACTIVATE, 0.7f, 0.7f);
        // opening burst（深蓝/紫，数量减半，免得满屏抖动）
        world.spawnParticles(ParticleTypes.PORTAL, centerVec.x, centerVec.y + 0.5, centerVec.z,
            50, radius * 0.45, 0.5, radius * 0.45, 0.3);
        world.spawnParticles(new DustParticleEffect(BLUE, 1.6f), centerVec.x, centerVec.y + 0.4, centerVec.z,
            30, radius * 0.4, 0.3, radius * 0.4, 0.05);
    }

    /** @return true once the domain has run its course (caller then invokes {@link #end()}). */
    public boolean tick() {
        long now = world.getTime();
        if (now >= endTick || gf.isRemoved() || !gf.isAlive()) return true;

        long elapsed = now - startTick;
        drawParticles(elapsed);
        if (elapsed % REFRESH_INTERVAL == 0) refreshBuffs();
        if (elapsed > 0 && elapsed % PULSE_INTERVAL == 0) pulse();
        return false;
    }

    public void end() {
        AbilityManager.blocks().restoreOwned(this);
        removeDamageBonus(gf);
        PlayerEntity owner = owner();
        if (owner != null) removeDamageBonus(owner);
        // Back to 形态一：恢复重力、关掉浮空，皮肤/人格切回甜美状态。给一段缓降，免得退形态瞬间从空中摔下来。
        gf.setFormTwo(false);
        gf.setNoGravity(false);
        gf.fallDistance = 0.0;
        gf.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 100, 0, false, false, true));
        playAt(SoundEvents.BLOCK_CONDUIT_DEACTIVATE, 1.0f, 0.7f);
        world.spawnParticles(ParticleTypes.PORTAL, centerVec.x, centerVec.y + 0.5, centerVec.z,
            80, radius * 0.5, 0.5, radius * 0.5, 0.3);
        if (!gf.isRemoved()) {
            xyz.apollodorus.mcgf.ai.MoodManager.tryEventProactive(gf, GirlfriendConfig.pickOne(ConfigManager.get().prompts.domainEnd));
        }
    }

    // --- empowerment ---

    private void refreshBuffs() {
        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        applyEffects(gf, b, b.domainHealthAmplifierSelf);
        PlayerEntity owner = owner();
        if (owner != null) {
            boolean inside = owner.squaredDistanceTo(centerVec) <= radius * radius;
            EntityAttributeInstance atk = owner.getAttributeInstance(EntityAttributes.ATTACK_DAMAGE);
            if (inside) {
                applyEffects(owner, b, b.domainHealthAmplifierOwner);
                if (atk != null && !atk.hasModifier(ATK_ID)) {
                    atk.addTemporaryModifier(new EntityAttributeModifier(ATK_ID, b.domainDamageBonus,
                        EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
                }
            } else if (atk != null) {
                atk.removeModifier(ATK_ID); // left the field → drop the bonus at once
            }
        }
    }

    private static void applyEffects(LivingEntity e, GirlfriendConfig.Behavior b, int healthAmp) {
        // 40t duration, refreshed every 20t → no expire/re-grant churn, fades ≤2s after the domain ends.
        e.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 40, b.domainSpeedAmplifier, false, false, true));
        e.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, 40, b.domainJumpAmplifier, false, false, true));
        e.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 40, b.domainResistanceAmplifier, false, false, true));
        if (healthAmp >= 0) {
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.HEALTH_BOOST, 40, healthAmp, false, false, true));
        }
    }

    private static void addDamageBonus(LivingEntity e, double bonus) {
        EntityAttributeInstance atk = e.getAttributeInstance(EntityAttributes.ATTACK_DAMAGE);
        if (atk != null && !atk.hasModifier(ATK_ID)) {
            atk.addTemporaryModifier(new EntityAttributeModifier(ATK_ID, bonus,
                EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private static void removeDamageBonus(LivingEntity e) {
        EntityAttributeInstance atk = e.getAttributeInstance(EntityAttributes.ATTACK_DAMAGE);
        if (atk != null) atk.removeModifier(ATK_ID);
    }

    // --- pulse: group + burn nearby hostiles (the Erosion Field's signature) ---

    private void pulse() {
        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        List<Entity> mobs = world.getOtherEntities(gf, Box.of(centerVec, radius * 2.0, radius * 2.0, radius * 2.0),
            e -> e instanceof HostileEntity && e.isAlive());
        for (Entity e : mobs) {
            Vec3d toCenter = centerVec.subtract(e.getEntityPos());
            if (toCenter.lengthSquared() > 1.0e-3) {
                Vec3d pull = toCenter.normalize().multiply(0.68);
                e.addVelocity(pull.x, 0.22, pull.z);
            }
            LivingEntity living = (LivingEntity) e;
            living.damage(world, world.getDamageSources().mobAttack(gf), (float) b.domainPulseDamage);
            living.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 50, 2, false, false, true));
            living.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 50, 1, false, false, true));
        }
        playAt(SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 0.65f, 1.45f);
        playAt(SoundEvents.BLOCK_RESPAWN_ANCHOR_DEPLETE.value(), 0.9f, 0.65f);

        // ?????????????????????
        int ringParticles = 36;
        for (int i = 0; i < ringParticles; i++) {
            double ang = (Math.PI * 2 / ringParticles) * i;
            double cos = Math.cos(ang);
            double sin = Math.sin(ang);
            world.spawnParticles(ParticleTypes.SONIC_BOOM, centerVec.x + cos * 2.2, centerVec.y + 0.3, centerVec.z + sin * 2.2,
                1, cos * 0.2, 0.0, sin * 0.2, 0.0);
            world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, centerVec.x + cos * (radius * 0.7), centerVec.y + 0.5, centerVec.z + sin * (radius * 0.7),
                1, cos * 0.05, 0.08, sin * 0.05, 0.02);
        }
        world.spawnParticles(new DustParticleEffect(PURPLE, 1.8f), centerVec.x, centerVec.y + 0.6, centerVec.z,
            36, radius * 0.45, 0.4, radius * 0.45, 0.08);
        world.spawnParticles(new DustParticleEffect(BLUE, 1.6f), centerVec.x, centerVec.y + 0.6, centerVec.z,
            32, radius * 0.5, 0.4, radius * 0.5, 0.08);
        world.spawnParticles(ParticleTypes.SCULK_SOUL, centerVec.x, centerVec.y + 0.6, centerVec.z,
            18, radius * 0.35, 0.35, radius * 0.35, 0.03);
    }

    // --- visuals ---

    private void drawParticles(long elapsed) {
        // 1) ?????????????? (Ground Resonance Magic Circle)
        drawResonanceRuneCircle(elapsed);

        // 2) ???????? (Gravitational Rift Swirl)
        drawGroundSwirl(elapsed);

        // 3) ????????????????? (Boundary Rift Pillars & Cosmic Monoliths)
        drawBoundaryMonoliths(elapsed);

        // 4) ????????? (Gravity Rift Pulses)
        drawGravityFieldRipples(elapsed);
    }

    /**
     * ???????????????????????????????
     */
    private void drawResonanceRuneCircle(long elapsed) {
        // ???????????????????????????????????????????
        double innerSpin = elapsed * 0.05;
        double outerSpin = -elapsed * 0.025;
        DustParticleEffect cyanDust = new DustParticleEffect(0x5BC8FF, 0.75f);
        DustParticleEffect purpleDust = new DustParticleEffect(PURPLE, 0.8f);
        DustParticleEffect deepBlueDust = new DustParticleEffect(DEEP_BLUE, 0.85f);

        // ??????? (Outer Boundary Ring) - ??0.03??????
        int outerPoints = 24;
        for (int i = 0; i < outerPoints; i++) {
            double a = outerSpin + (Math.PI * 2 / outerPoints) * i;
            double px = centerVec.x + Math.cos(a) * radius;
            double pz = centerVec.z + Math.sin(a) * radius;
            world.spawnParticles(deepBlueDust, px, centerVec.y + 0.03, pz, 1, 0.0, 0.0, 0.0, 0.0);
            if (i % 6 == 0) {
                world.spawnParticles(purpleDust, px, centerVec.y + 0.05, pz, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }

        // ????? (Middle Melody Ring - ?? 0.65) - ??1????????
        if (elapsed % 2 == 0) {
            int midPoints = 16;
            double midR = radius * 0.65;
            for (int i = 0; i < midPoints; i++) {
                double a = innerSpin + (Math.PI * 2 / midPoints) * i;
                double px = centerVec.x + Math.cos(a) * midR;
                double pz = centerVec.z + Math.sin(a) * midR;
                world.spawnParticles(cyanDust, px, centerVec.y + 0.02, pz, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }

        // ????? (Inner Singularity Ring - ?? 0.3) - ?????????
        int innerPoints = 12;
        double innerR = radius * 0.3;
        for (int i = 0; i < innerPoints; i++) {
            double a = innerSpin * 1.4 + (Math.PI * 2 / innerPoints) * i;
            double px = centerVec.x + Math.cos(a) * innerR;
            double pz = centerVec.z + Math.sin(a) * innerR;
            world.spawnParticles(purpleDust, px, centerVec.y + 0.02, pz, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /**
     * ??????????????????????????
     */
    private void drawGroundSwirl(long elapsed) {
        if (elapsed % 2 != 0) return; // ?????????????GPU??
        double base = elapsed * 0.04;
        int arms = 3; // 4???3?????????
        DustParticleEffect purple = new DustParticleEffect(PURPLE, 0.75f);
        DustParticleEffect deep = new DustParticleEffect(DEEP_BLUE, 0.75f);

        for (int arm = 0; arm < arms; arm++) {
            double off = base + (Math.PI * 2 / arms) * arm;
            int steps = 10; // 16???10?
            for (int s = 0; s < steps; s++) {
                double frac = s / (double) steps;
                double rr = frac * radius;
                double a = off + (1.0 - frac) * 1.0;
                double px = centerVec.x + Math.cos(a) * rr;
                double pz = centerVec.z + Math.sin(a) * rr;

                world.spawnParticles((s % 2 == 0 ? deep : purple), px, centerVec.y + 0.03, pz, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    /**
     * ???????????????????????? (Boundary Monoliths)
     */
    private void drawBoundaryMonoliths(long elapsed) {
        double spin = elapsed * 0.08;
        DustParticleEffect beamColor = new DustParticleEffect(0x6FC2FF, 1.2f);
        DustParticleEffect purpleCore = new DustParticleEffect(PURPLE, 1.8f);

        for (int k = 0; k < DIAMOND_CORNERS; k++) {
            double baseAng = (Math.PI * 2 / DIAMOND_CORNERS) * k;
            double cx = centerVec.x + Math.cos(baseAng) * radius;
            double cz = centerVec.z + Math.sin(baseAng) * radius;
            double cy = centerVec.y + DIAMOND_HEIGHT;

            // ???????????? (Resonance Rift Pillar)
            double poleTop = cy + 1.2;
            int seg = Math.max(4, (int) Math.round((poleTop - centerVec.y) * 1.8));
            for (int i = 0; i <= seg; i += 2) {
                double py = centerVec.y + (poleTop - centerVec.y) * (i / (double) seg);
                world.spawnParticles(beamColor, cx, py, cz, 1, 0.03, 0.0, 0.03, 0.0);
            }

            // ?????? (Floating Resonance Octahedron)
            double h = 1.0;
            double w = 0.65;
            Vec3d up = new Vec3d(cx, cy + h, cz);
            Vec3d down = new Vec3d(cx, cy - h, cz);
            Vec3d s1 = new Vec3d(cx + Math.cos(spin) * w, cy, cz + Math.sin(spin) * w);
            Vec3d s2 = new Vec3d(cx - Math.cos(spin) * w, cy, cz - Math.sin(spin) * w);
            Vec3d s3 = new Vec3d(cx - Math.sin(spin) * w, cy, cz + Math.cos(spin) * w);
            Vec3d s4 = new Vec3d(cx + Math.sin(spin) * w, cy, cz - Math.cos(spin) * w);

            edge(purpleCore, up, s1);
            edge(purpleCore, up, s2);
            edge(purpleCore, up, s3);
            edge(purpleCore, up, s4);
            edge(purpleCore, s1, down);
            edge(purpleCore, s2, down);
            edge(purpleCore, s3, down);
            edge(purpleCore, s4, down);

            if (elapsed % 3 == 0) {
                world.spawnParticles(ParticleTypes.END_ROD, cx, cy, cz, 1, 0.08, 0.08, 0.08, 0.02);
                world.spawnParticles(ParticleTypes.GLOW, cx, cy, cz, 2, 0.12, 0.2, 0.12, 0.0);
                world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, cx, cy, cz, 1, 0.05, 0.05, 0.05, 0.01);
            }
        }
    }

    /**
     * ????????? (Gravity Field Ripples)
     */
    private void drawGravityFieldRipples(long elapsed) {
        if (elapsed % 8 == 0) {
            // ?????????????
            double pulseProgress = (elapsed % 40) / 40.0;
            double ringR = pulseProgress * radius;
            int pts = 24;
            DustParticleEffect waveDust = new DustParticleEffect(0x5BC8FF, 1.2f);
            for (int i = 0; i < pts; i++) {
                double a = i * (Math.PI * 2 / pts);
                double px = centerVec.x + Math.cos(a) * ringR;
                double pz = centerVec.z + Math.sin(a) * ringR;
                world.spawnParticles(waveDust, px, centerVec.y + 0.18, pz, 1, 0.01, 0.01, 0.01, 0.0);
            }
        }
    }

    /** Trace a glowing line between two points (one segment of a diamond edge). */
    private void edge(DustParticleEffect dust, Vec3d a, Vec3d b) {
        int seg = 5;
        for (int i = 0; i <= seg; i++) {
            Vec3d p = a.lerp(b, i / (double) seg);
            world.spawnParticles(dust, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private PlayerEntity owner() {
        return gf.getOwner();
    }

    private void playAt(SoundEvent sound, float volume, float pitch) {
        world.playSound(null, centerVec.x, centerVec.y, centerVec.z, sound, SoundCategory.HOSTILE, volume, pitch);
    }

    // Some SoundEvents constants are raw SoundEvent, others RegistryEntry<SoundEvent>; overload covers both.
    private void playAt(RegistryEntry<SoundEvent> sound, float volume, float pitch) {
        playAt(sound.value(), volume, pitch);
    }
}
