package xyz.apollodorus.mcgf.combat;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import xyz.apollodorus.mcgf.ai.SpeechBus;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.util.List;

/**
 * ???????? (Wuthering Waves Combat Engine for Daniya):
 * 1. ??????? (Extreme Dodge & Counter)
 * 2. ???? (Resonance Skill)????? / ????
 * 3. ???????????
 */
public final class DaniyaCombatEngine {
    private DaniyaCombatEngine() {}

    private static final int CYAN = 0x5BC8FF;
    private static final int PINK = 0xFF8FD4;
    private static final int PURPLE = 0x9D4EDD;
    private static final int WHITE = 0xFFFFFF;

    private static long lastDodgeTick = 0;
    private static long lastSkillTick = 0;

    /**
     * ???????????
     */
    public static boolean tryExtremeDodge(GirlfriendEntity gf, LivingEntity attacker) {
        ServerWorld sw = (ServerWorld) gf.getEntityWorld();
        long now = sw.getTime();
        // ???????7 ? (140 ticks)
        if (now - lastDodgeTick < 140) return false;
        lastDodgeTick = now;

        Vec3d gfPos = gf.getEntityPos();
        Vec3d away;
        if (attacker != null) {
            away = gfPos.subtract(attacker.getEntityPos()).normalize();
        } else {
            away = gf.getRotationVector().multiply(-1.0);
        }
        if (away.lengthSquared() < 0.01) away = new Vec3d(0, 0, -1);

        // ??????????????
        spawnDodgeAfterimage(sw, gfPos, gf.isFormTwo());

        // ?????????????? 3.8 ?
        Vec3d targetPos = gfPos.add(away.x * 3.8, 0.2, away.z * 3.8);
        gf.teleport(targetPos.x, targetPos.y, targetPos.z, true);

        // ??????????? + ?????
        sw.playSound(null, gf.getX(), gf.getY(), gf.getZ(),
                SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.2f, 1.6f);
        sw.playSound(null, gf.getX(), gf.getY(), gf.getZ(),
                SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.8f, 1.8f);

        // ???????
        gf.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 35, 2, false, false));
        gf.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 20, 4, false, false));

        if (gf.getRandom().nextFloat() < 0.5f) {
            String[] quotes = {
                    "?????????????",
                    "??????????",
                    "????????????",
                    "????????????"
            };
            SpeechBus.speak(gf, quotes[gf.getRandom().nextInt(quotes.length)]);
        }

        // 0.25 ? (5 ticks) ?????????????
        AbilityManager.delay(sw, now + 5, () -> {
            if (!gf.isAlive()) return;
            LivingEntity target = attacker != null && attacker.isAlive() ? attacker : gf.getTarget();
            if (target != null && target.isAlive()) {
                executeDodgeCounter(sw, gf, target);
            }
        });

        return true;
    }

    /**
     * ??????????????? + ?????
     */
    private static void executeDodgeCounter(ServerWorld sw, GirlfriendEntity gf, LivingEntity target) {
        gf.swingHand(Hand.MAIN_HAND);
        Vec3d from = gf.getEntityPos().add(0, gf.getHeight() * 0.6, 0);
        Vec3d to = target.getEntityPos().add(0, target.getHeight() * 0.5, 0);
        Vec3d dir = to.subtract(from).normalize();

        // ?????????
        for (double d = 0; d < from.distanceTo(to); d += 0.4) {
            Vec3d p = from.add(dir.multiply(d));
            sw.spawnParticles(new DustParticleEffect(gf.isFormTwo() ? PURPLE : CYAN, 1.5f),
                    p.x, p.y, p.z, 2, 0.05, 0.05, 0.05, 0.0);
            sw.spawnParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0.05);
        }

        // ???????????
        sw.spawnParticles(ParticleTypes.SONIC_BOOM, to.x, to.y, to.z, 1, 0, 0, 0, 0);
        sw.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, to.x, to.y, to.z, 1, 0, 0, 0, 0);
        sw.playSound(null, to.x, to.y, to.z, SoundEvents.ENTITY_WARDEN_ATTACK_IMPACT, SoundCategory.PLAYERS, 1.0f, 1.4f);

        // ?? 2.5 ???????????
        float counterDmg = (float) (ConfigManager.get().behavior.rangedDamage * 2.5);
        target.damage(sw, sw.getDamageSources().mobAttack(gf), counterDmg);
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 2));

        // ????
        Vec3d knockback = dir.multiply(0.8).add(0, 0.25, 0);
        target.addVelocity(knockback.x, knockback.y, knockback.z);
    }

    /**
     * ?????? (Resonance Skill)?CD ? 10 ?
     */
    public static boolean tryCastResonanceSkill(ServerWorld sw, GirlfriendEntity gf, LivingEntity target) {
        long now = sw.getTime();
        if (now - lastSkillTick < 200) return false;
        lastSkillTick = now;

        gf.swingHand(Hand.MAIN_HAND);
        if (gf.isFormTwo()) {
            castFormTwoSkill(sw, gf, target);
        } else {
            castFormOneSkill(sw, gf, target);
        }
        return true;
    }

    /**
     * ??????????????
     */
    private static void castFormOneSkill(ServerWorld sw, GirlfriendEntity gf, LivingEntity target) {
        SpeechBus.speak(gf, "???????????");
        sw.playSound(null, gf.getX(), gf.getY(), gf.getZ(),
                SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.5f, 1.2f);
        sw.playSound(null, gf.getX(), gf.getY(), gf.getZ(),
                SoundEvents.ENTITY_ILLUSIONER_CAST_SPELL, SoundCategory.PLAYERS, 1.2f, 1.3f);

        Vec3d center = gf.getEntityPos().add(0, 0.6, 0);
        Vec3d look = gf.getRotationVector().normalize();

        for (int step = 1; step <= 3; step++) {
            final int distance = step * 3;
            AbilityManager.delay(sw, sw.getTime() + (step * 3), () -> {
                Vec3d ringPos = center.add(look.multiply(distance));
                int points = 24;
                for (int i = 0; i < points; i++) {
                    double ang = i * (Math.PI * 2 / points);
                    double rx = Math.cos(ang) * (distance * 0.45);
                    double rz = Math.sin(ang) * (distance * 0.45);
                    sw.spawnParticles(new DustParticleEffect(PINK, 1.6f),
                            ringPos.x + rx, ringPos.y, ringPos.z + rz, 1, 0, 0.05, 0, 0.0);
                    sw.spawnParticles(new DustParticleEffect(CYAN, 1.4f),
                            ringPos.x + rx, ringPos.y + 0.3, ringPos.z + rz, 1, 0, 0.05, 0, 0.0);
                }
                sw.spawnParticles(ParticleTypes.BUBBLE_POP, ringPos.x, ringPos.y, ringPos.z, 20, 0.8, 0.5, 0.8, 0.1);

                List<Entity> foes = sw.getOtherEntities(gf, Box.of(ringPos, 3.5, 2.5, 3.5),
                        e -> e instanceof HostileEntity && e.isAlive());
                float dmg = (float) (ConfigManager.get().behavior.bubbleDamage * 1.8);
                for (Entity e : foes) {
                    LivingEntity victim = (LivingEntity) e;
                    victim.damage(sw, sw.getDamageSources().mobAttack(gf), dmg);
                    Vec3d push = look.multiply(1.1).add(0, 0.3, 0);
                    victim.addVelocity(push.x, push.y, push.z);
                    victim.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 80, 1));
                }
            });
        }
    }

    /**
     * ??????????????
     */
    private static void castFormTwoSkill(ServerWorld sw, GirlfriendEntity gf, LivingEntity target) {
        SpeechBus.speak(gf, "??????????");
        sw.playSound(null, gf.getX(), gf.getY(), gf.getZ(),
                SoundEvents.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, SoundCategory.PLAYERS, 1.3f, 0.8f);

        Vec3d targetPos = target.getEntityPos().add(0, 0.5, 0);

        sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, targetPos.x, targetPos.y + 1.0, targetPos.z,
                50, 1.5, 1.0, 1.5, 0.3);
        sw.spawnParticles(new DustParticleEffect(PURPLE, 2.0f), targetPos.x, targetPos.y + 0.8, targetPos.z,
                40, 1.8, 0.6, 1.8, 0.1);
        sw.spawnParticles(ParticleTypes.SCULK_SOUL, targetPos.x, targetPos.y, targetPos.z, 15, 1.2, 0.3, 1.2, 0.05);

        float skillDmg = (float) (ConfigManager.get().behavior.rangedDamage * 2.2);
        List<Entity> foes = sw.getOtherEntities(gf, Box.of(targetPos, 5.0, 3.5, 5.0),
                e -> e instanceof HostileEntity && e.isAlive());
        for (Entity e : foes) {
            LivingEntity foe = (LivingEntity) e;
            foe.damage(sw, sw.getDamageSources().mobAttack(gf), skillDmg);
            foe.addVelocity(0, 0.6, 0);
        }

        AbilityManager.delay(sw, sw.getTime() + 8, () -> {
            sw.spawnParticles(ParticleTypes.SONIC_BOOM, targetPos.x, targetPos.y + 0.3, targetPos.z, 1, 0, 0, 0, 0);
            sw.playSound(null, targetPos.x, targetPos.y, targetPos.z,
                    SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 0.8f, 1.5f);
        });
    }

    /**
     * ????????????
     */
    private static void spawnDodgeAfterimage(ServerWorld sw, Vec3d pos, boolean formTwo) {
        int color = formTwo ? PURPLE : CYAN;
        for (double dy = 0.1; dy <= 1.8; dy += 0.25) {
            sw.spawnParticles(new DustParticleEffect(color, 1.3f),
                    pos.x, pos.y + dy, pos.z, 4, 0.2, 0.05, 0.2, 0.0);
            sw.spawnParticles(ParticleTypes.END_ROD,
                    pos.x, pos.y + dy, pos.z, 1, 0.1, 0.05, 0.1, 0.02);
        }
    }
}
