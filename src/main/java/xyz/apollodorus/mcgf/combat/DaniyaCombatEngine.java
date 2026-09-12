package xyz.apollodorus.mcgf.combat;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;
import xyz.apollodorus.mcgf.ai.SpeechBus;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;
import xyz.apollodorus.mcgf.combat.AbilityManager;

import java.util.List;

/**
 * 鸣潮战斗模组 (Wuthering Waves Combat Engine for Daniya):
 * 1. 极限闪避与反击 (Extreme Dodge & Counter)
 * 2. 共鸣技能 (Resonance Skill)：一形态 泡沫共振 / 二形态 暗棘碎涌
 * 3. 二形态主动折跃靠近 (Phase 2 Warp-Dash) 与空中高机动浮空压制
 */
public class DaniyaCombatEngine {

    private static final int CYAN = 0x59D8FF;
    private static final int PINK = 0xFFA0D2;
    private static final int PURPLE = 0xBA43FF;
    private static final int DARK_VIOLET = 0x6E22B0;

    private static long lastDodgeTick = 0;
    private static long lastResonanceSkillTick = 0;
    private static long lastFormTwoDashTick = 0;

    /**
     * 极限闪避判定：
     * 二形态获得更高闪避机动率（二形态 55%，一形态 35%）
     */
    public static boolean tryExtremeDodge(GirlfriendEntity gf, LivingEntity attacker) {
        if (!(gf.getEntityWorld() instanceof ServerWorld sw)) return false;
        long now = sw.getTime();
        // 极限闪避基础内置 CD：一形态 5 秒 (100 ticks)，二形态更为灵动 3.5 秒 (70 ticks)
        long cd = gf.isFormTwo() ? 70 : 100;
        if (now - lastDodgeTick < cd) return false;

        float chance = gf.isFormTwo() ? 0.55f : 0.35f;
        if (gf.getRandom().nextFloat() > chance) return false;

        lastDodgeTick = now;
        Vec3d gfPos = gf.getEntityPos();
        Vec3d away;
        if (attacker != null) {
            away = gfPos.subtract(attacker.getEntityPos()).normalize();
            if (away.lengthSquared() < 0.001) away = new Vec3d(1, 0, 0);
        } else {
            away = gf.getRotationVector().negate().normalize();
        }

        // 闪避残影
        spawnDodgeAfterimage(sw, gfPos, gf.isFormTwo());

        // 瞬间向后/侧向折跃滑步
        double dashDist = gf.isFormTwo() ? 4.5 : 3.8;
        Vec3d targetPos = gfPos.add(away.x * dashDist, 0.2, away.z * dashDist);
        gf.requestTeleport(targetPos.x, targetPos.y, targetPos.z);

        // 闪避音效（鸣潮清脆破空与音波）
        sw.playSound(null, gf.getX(), gf.getY(), gf.getZ(),
                SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.2f, 1.6f);
        sw.playSound(null, gf.getX(), gf.getY(), gf.getZ(),
                SoundEvents.ITEM_CHORUS_FRUIT_TELEPORT, SoundCategory.PLAYERS, 0.8f, 1.5f);

        // 短暂无敌帧与爆发加速
        gf.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 40, 2, false, false));
        gf.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 25, 4, false, false));

        if (gf.getRandom().nextFloat() < 0.65f) {
            String[] quotes = gf.isFormTwo() ? new String[]{
                    "慢吞吞的，在看哪里呀？",
                    "虚质引力…可不是这么好碰的哦。",
                    "抓不到我吧~",
                    "动作太迟钝了呢。"
            } : new String[]{
                    "呼啊…好险好险，差点被打中了~",
                    "动作太明显啦！",
                    "慢悠悠的，我可闪开咯~",
                    "吓我一跳，想偷袭呀？"
            };
            SpeechBus.speak(gf, quotes[gf.getRandom().nextInt(quotes.length)]);
        }

        // 0.25 秒后触发极限反击冲能
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
     * 二形态专属：主动空中折跃靠近敌对生物（复用闪避位移并附带音爆与引力）
     */
    public static boolean tryFormTwoApproachDash(GirlfriendEntity gf, LivingEntity target) {
        if (!gf.isFormTwo() || !(gf.getEntityWorld() instanceof ServerWorld sw) || target == null || !target.isAlive()) {
            return false;
        }
        long now = sw.getTime();
        // 内置 CD 6 秒 (120 ticks)
        if (now - lastFormTwoDashTick < 120) return false;

        double distSq = gf.squaredDistanceTo(target);
        // 当距离在 7 到 24 格之间时，主动折跃切入到离目标 3.5 格处施加威压
        if (distSq < 49.0 || distSq > 576.0) return false;

        lastFormTwoDashTick = now;
        Vec3d gfPos = gf.getEntityPos();
        Vec3d targetPos = target.getEntityPos();
        Vec3d dir = targetPos.subtract(gfPos).normalize();

        // 原地音波残影
        spawnDodgeAfterimage(sw, gfPos, true);

        // 切入至目标斜上方 3.5 格，保持优雅空中悬浮
        Vec3d dest = targetPos.subtract(dir.multiply(3.5)).add(0, 2.2, 0);
        gf.requestTeleport(dest.x, dest.y, dest.z);

        // 折跃破空与虚质引力音效
        sw.playSound(null, dest.x, dest.y, dest.z, SoundEvents.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.PLAYERS, 1.2f, 1.5f);
        sw.playSound(null, dest.x, dest.y, dest.z, SoundEvents.ITEM_CHORUS_FRUIT_TELEPORT, SoundCategory.PLAYERS, 1.0f, 1.3f);

        // 沿途音感仪流光轨迹
        for (double d = 0; d < gfPos.distanceTo(dest); d += 0.8) {
            Vec3d p = gfPos.add(dest.subtract(gfPos).multiply(d / gfPos.distanceTo(dest)));
            sw.spawnParticles(new DustParticleEffect(PURPLE, 1.4f), p.x, p.y, p.z, 2, 0.05, 0.05, 0.05, 0.0);
            sw.spawnParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0.01);
        }

        // 短暂轻语
        if (gf.getRandom().nextFloat() < 0.5f) {
            String[] approachQuotes = {
                    "既然你不过来，那就由我来找你咯~",
                    "别想逃出我的音律范围哦。",
                    "抓到你了呢~",
                    "就站在那里别动。"
            };
            SpeechBus.speak(gf, approachQuotes[gf.getRandom().nextInt(approachQuotes.length)]);
        }

        return true;
    }

    /**
     * 极限反击：光流穿透轰炸 + 击飞
     */
    private static void executeDodgeCounter(ServerWorld sw, GirlfriendEntity gf, LivingEntity target) {
        Vec3d from = gf.getEyePos();
        Vec3d to = target.getEyePos();
        Vec3d dir = to.subtract(from).normalize();

        // 瞬发光流轨道
        for (double d = 0; d < from.distanceTo(to); d += 0.4) {
            Vec3d p = from.add(dir.multiply(d));
            sw.spawnParticles(new DustParticleEffect(gf.isFormTwo() ? PURPLE : CYAN, 1.5f),
                    p.x, p.y, p.z, 2, 0.05, 0.05, 0.05, 0.0);
        }

        // 命中爆破与音爆
        sw.spawnParticles(ParticleTypes.SONIC_BOOM, to.x, to.y, to.z, 1, 0, 0, 0, 0);
        sw.playSound(null, to.x, to.y, to.z, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 1.0f, 1.8f);

        // 伤害加成
        float counterDmg = (float) (ConfigManager.get().behavior.rangedDamage * 2.5);
        target.damage(sw, gf.getDamageSources().indirectMagic(gf, gf), counterDmg);

        // 浮空击退
        Vec3d knockback = dir.multiply(1.2).add(0, 0.25, 0);
        target.setVelocity(knockback);
        target.velocityDirty = true;
    }

    /**
     * 共鸣技能：一形态「泡沫共振」/ 二形态「暗棘碎涌」
     */
    public static boolean tryCastResonanceSkill(ServerWorld sw, GirlfriendEntity gf, LivingEntity target) {
        long now = sw.getTime();
        if (now - lastResonanceSkillTick < 200) return false; // 10s CD

        lastResonanceSkillTick = now;
        if (gf.isFormTwo()) {
            castFormTwoSkill(sw, gf, target);
        } else {
            castFormOneSkill(sw, gf, target);
        }
        return true;
    }

    /**
     * 一形态共鸣技能：泡沫共振波
     */
    private static void castFormOneSkill(ServerWorld sw, GirlfriendEntity gf, LivingEntity target) {
        String[] skillQuotes = {
                "这一下……可要接好了哦！",
                "音波共鸣，散！",
                "哼，尝尝这个~"
        };
        SpeechBus.speak(gf, skillQuotes[gf.getRandom().nextInt(skillQuotes.length)]);
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

                List<LivingEntity> enemies = sw.getEntitiesByClass(LivingEntity.class,
                        new Box(ringPos.x - 2.5, ringPos.y - 1.5, ringPos.z - 2.5,
                                ringPos.x + 2.5, ringPos.y + 2.5, ringPos.z + 2.5),
                        e -> e != gf && e.isAlive() && !e.isTeammate(gf));
                for (LivingEntity enemy : enemies) {
                    enemy.damage(sw, gf.getDamageSources().magic(), (float) (ConfigManager.get().behavior.rangedDamage * 1.5));
                    enemy.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 1));
                }
            });
        }
    }

    /**
     * 二形态共鸣技能：暗棘碎涌 (Dark Spike Surge)
     * 地面大范围暗黑引力荆棘突刺 + 声波脉冲爆发 + 强制挑空
     */
    private static void castFormTwoSkill(ServerWorld sw, GirlfriendEntity gf, LivingEntity target) {
        String[] skillQuotes = {
                "暗棘碎涌……沉沦于此吧。",
                "在此刻，聆听引力的终结！",
                "虚质崩落，化为碎屑吧。"
        };
        SpeechBus.speak(gf, skillQuotes[gf.getRandom().nextInt(skillQuotes.length)]);
        sw.playSound(null, gf.getX(), gf.getY(), gf.getZ(),
                SoundEvents.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.PLAYERS, 1.4f, 0.9f);
        sw.playSound(null, gf.getX(), gf.getY(), gf.getZ(),
                SoundEvents.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, SoundCategory.PLAYERS, 1.3f, 0.7f);

        Vec3d targetPos = target.getEntityPos();
        long now = sw.getTime();

        // 3波递进的暗影碎涌
        for (int wave = 0; wave < 3; wave++) {
            final int waveIdx = wave;
            AbilityManager.delay(sw, now + waveIdx * 4, () -> {
                if (!gf.isAlive()) return;
                Vec3d center = (target.isAlive() ? target.getEntityPos() : targetPos);
                double waveRadius = 1.2 + waveIdx * 1.8;
                int spikes = 8 + waveIdx * 4;

                // 地面引力冲击圈
                for (int i = 0; i < 24; i++) {
                    double ang = i * (Math.PI * 2 / 24);
                    double px = center.x + Math.cos(ang) * waveRadius;
                    double pz = center.z + Math.sin(ang) * waveRadius;
                    sw.spawnParticles(new DustParticleEffect(PURPLE, 1.8f), px, center.y + 0.1, pz, 1, 0, 0, 0, 0);
                    sw.spawnParticles(new DustParticleEffect(DARK_VIOLET, 1.5f), px, center.y + 0.15, pz, 1, 0, 0, 0, 0);
                    sw.spawnParticles(ParticleTypes.WARPED_SPORE, px, center.y + 0.2, pz, 2, 0.1, 0.2, 0.1, 0.05);
                }

                // 拔地而起的暗棘突刺
                for (int s = 0; s < spikes; s++) {
                    double angle = s * (Math.PI * 2 / spikes) + (waveIdx * 0.4);
                    double dist = (s % 2 == 0) ? waveRadius : waveRadius * 0.6;
                    double sx = center.x + Math.cos(angle) * dist;
                    double sz = center.z + Math.sin(angle) * dist;

                    // 暗棘立柱粒子
                    for (double h = 0.0; h <= 3.6; h += 0.35) {
                        sw.spawnParticles(new DustParticleEffect(PURPLE, (float) (2.0 - h * 0.3)),
                                sx, center.y + h, sz, 2, 0.05, 0.05, 0.05, 0.0);
                        sw.spawnParticles(ParticleTypes.SCULK_SOUL, sx, center.y + h, sz, 1, 0.02, 0.05, 0.02, 0.02);
                    }
                }

                // 音效与范围判定
                sw.playSound(null, center.x, center.y, center.z,
                        SoundEvents.BLOCK_RESPAWN_ANCHOR_DEPLETE.value(), SoundCategory.PLAYERS, 1.3f, 1.4f);

                float dmg = (float) (ConfigManager.get().behavior.rangedDamage * (1.8 + waveIdx * 0.6));
                List<LivingEntity> hitList = sw.getEntitiesByClass(LivingEntity.class,
                        new Box(center.x - waveRadius - 1.2, center.y - 1.0, center.z - waveRadius - 1.2,
                                center.x + waveRadius + 1.2, center.y + 4.5, center.z + waveRadius + 1.2),
                        e -> e != gf && e.isAlive() && !e.isTeammate(gf));

                for (LivingEntity victim : hitList) {
                    victim.damage(sw, gf.getDamageSources().magic(), dmg);
                    // 挑空浮空
                    victim.setVelocity(new Vec3d(0, 0.65 + waveIdx * 0.2, 0));
                    victim.velocityDirty = true;
                    victim.addStatusEffect(new StatusEffectInstance(StatusEffects.LEVITATION, 15, 1));
                    victim.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 80, 1));
                }
            });
        }

        // 终末爆破音波
        AbilityManager.delay(sw, now + 12, () -> {
            if (!gf.isAlive()) return;
            Vec3d finalPos = (target.isAlive() ? target.getEntityPos() : targetPos).add(0, 1.0, 0);
            sw.spawnParticles(ParticleTypes.SONIC_BOOM, finalPos.x, finalPos.y, finalPos.z, 2, 0, 0, 0, 0);
            sw.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, finalPos.x, finalPos.y, finalPos.z, 1, 0, 0, 0, 0);
            sw.playSound(null, finalPos.x, finalPos.y, finalPos.z, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 1.5f, 1.0f);
        });
    }

    /**
     * 生成极速折跃与闪避残影
     */
    private static void spawnDodgeAfterimage(ServerWorld sw, Vec3d pos, boolean formTwo) {
        int color = formTwo ? PURPLE : CYAN;
        for (double dy = 0.1; dy <= 1.8; dy += 0.25) {
            sw.spawnParticles(new DustParticleEffect(color, 1.8f),
                    pos.x, pos.y + dy, pos.z, 4, 0.2, 0.05, 0.2, 0.0);
        }
        sw.spawnParticles(ParticleTypes.POOF, pos.x, pos.y + 0.9, pos.z, 10, 0.3, 0.4, 0.3, 0.02);
    }
}
