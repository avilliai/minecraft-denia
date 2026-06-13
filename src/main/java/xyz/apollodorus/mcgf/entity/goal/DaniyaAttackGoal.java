package xyz.apollodorus.mcgf.entity.goal;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import xyz.apollodorus.mcgf.combat.BubbleAbilities;
import xyz.apollodorus.mcgf.combat.DaniyaAbilities;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.config.GirlfriendConfig;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.util.EnumSet;

/**
 * 达妮娅's attack module — owns {@code MOVE + LOOK}; targeting stays with {@link ProtectOwnerGoal}, so
 * {@code gf.getTarget()} remains the universal "she's fighting" signal.
 *
 * <p>She has no vanilla-weapon melee any more — she fights with a four-step combo whose flavor follows
 * her current form:
 * <ul>
 *   <li><b>形态一</b> (sweet): holds her 专武泡泡杖 and runs the bubble combo
 *       (1a/2a 吐泡泡 → 3a 大泡泡困人 → 4a 引爆) via {@link BubbleAbilities}.</li>
 *   <li><b>形态二</b> (蚀域/幻灭, while the domain is up): bare-handed, runs the void combo
 *       (1a 抛虚质块 → 2a 天降巨块 → 3a 多块+黑洞 → 4a 引爆) via {@link DaniyaAbilities}, at a shorter range
 *       so she stays in her field instead of sniping from afar.</li>
 * </ul>
 * Rather than standing like a turret she closes to a preferred distance and holds there; each combo
 * step charges her 虚质粒子 energy.
 */
public class DaniyaAttackGoal extends Goal {
    private final GirlfriendEntity gf;
    private LivingEntity target;
    private int rangedCd;
    private int comboStep;
    private int repathCd;
    private boolean lastFormTwo;
    // movement: she circle-strafes around her target instead of standing like a turret
    private int strafeCd;
    private int strafeFlipCd;
    private int strafeDir = 1;
    private int idleAttackTicks;   // watchdog: ticks since she last landed an attack (fixes form-2 "freeze")

    public DaniyaAttackGoal(GirlfriendEntity gf) {
        this.gf = gf;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        LivingEntity t = gf.getTarget();
        if (t == null || !t.isAlive()) return false;
        this.target = t;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        LivingEntity t = gf.getTarget();
        if (t == null || !t.isAlive()) return false;
        this.target = t;
        return true;
    }

    @Override
    public void start() {
        this.rangedCd = 6;
        this.repathCd = 0;
        this.comboStep = 0;
        this.strafeCd = 0;
        this.strafeFlipCd = 4;
        this.lastFormTwo = gf.isFormTwo();
        equipForForm(lastFormTwo);
    }

    @Override
    public void stop() {
        this.target = null;
        gf.getNavigation().stop();
    }

    @Override
    public boolean shouldRunEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (target == null || !(gf.getEntityWorld() instanceof ServerWorld sw)) return;
        gf.getLookControl().lookAt(target, 30f, 30f);

        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        boolean formTwo = gf.isFormTwo();
        if (formTwo != lastFormTwo) {     // domain just opened / closed mid-fight → re-arm + restart combo
            lastFormTwo = formTwo;
            comboStep = 0;
            equipForForm(formTwo);
        }

        double maxRange = formTwo ? b.form2AttackDistance : b.rangedMaxDistance;
        // 形态二贴得更近再打（之前太远像炮塔/发呆）；形态一保持中距。
        // 形态一面对多个敌人时，保持更远的距离进行风筝
        int nearbyHostiles = countNearbyHostiles();
        double preferred = Math.max(3.0, formTwo ? maxRange * 0.42 :
            (nearbyHostiles >= 3 ? maxRange * 0.8 : maxRange * 0.6)); // 3个或以上敌人时拉开距离
        double dist = gf.distanceTo(target);
        boolean canSee = gf.canSee(target);

        // 形态一多敌人策略：保持距离，边退边打，利用远程泡泡攻击
        boolean shouldKite = !formTwo && nearbyHostiles >= 3 && dist < maxRange; // 确保在射程内才风筝

        // 形态二：先靠近敌人再打——离得远 OR 看不见（被挡）就径直贴近，到位后才环绕走位。
        // 形态一：稳住别多动症，进入合适距离就停下原地输出，只偶尔挪一小步。
        // 风筝模式：持续移动，保持距离，避免被包围
        double moveSpeed = b.moveSpeed * (formTwo ? 1.35 : (shouldKite ? 1.2 : 1.0));
        double band = formTwo ? 1.0 : (shouldKite ? 3.0 : 2.2); // 风筝时容忍更宽的距离带
        boolean mustApproach = dist > preferred + band || (formTwo && !canSee && dist > preferred);

        if (shouldKite) {
            // 风筝模式：始终保持移动，优先后退拉开距离
            if (dist < preferred) {
                // 距离太近，后退
                if (--repathCd <= 0) {
                    repathCd = 8;
                    backAwayFrom(target, moveSpeed, preferred);
                }
            } else if (dist > preferred + band * 1.5) {
                // 距离太远，稍微靠近一点
                if (--repathCd <= 0) {
                    repathCd = 12;
                    gf.getNavigation().startMovingTo(target, moveSpeed * 0.8);
                }
            } else {
                // 合适距离，侧向移动（风筝走位）
                if (--strafeCd <= 0) {
                    strafeCd = 6 + gf.getRandom().nextInt(6);
                    if (--strafeFlipCd <= 0) {
                        strafeFlipCd = 2 + gf.getRandom().nextInt(3);
                        strafeDir = -strafeDir;
                    }
                    strafeAround(target, moveSpeed * 0.9, false);
                }
            }
        } else if (mustApproach) {
            if (--repathCd <= 0) {
                repathCd = formTwo ? 8 : 16;
                gf.getNavigation().startMovingTo(target, moveSpeed);
            }
        } else if (dist < preferred - band - 0.5) {
            if (--repathCd <= 0) {
                repathCd = formTwo ? 10 : 16;
                backAwayFrom(target, moveSpeed, preferred);
            }
        } else if (formTwo) {
            // form-2 circle-strafe
            if (--strafeCd <= 0) {
                strafeCd = 8 + gf.getRandom().nextInt(8);
                if (--strafeFlipCd <= 0) {
                    strafeFlipCd = 3 + gf.getRandom().nextInt(4);
                    strafeDir = -strafeDir;
                }
                strafeAround(target, moveSpeed * 0.8, true);
            }
        } else {
            // form-1: mostly stand and cast. Hold position; very occasionally take one small side-step.
            if (--strafeCd <= 0) {
                strafeCd = 45 + gf.getRandom().nextInt(45);
                if (gf.getRandom().nextInt(3) == 0) {
                    strafeDir = -strafeDir;
                    strafeAround(target, moveSpeed * 0.5, false);
                } else {
                    gf.getNavigation().stop();
                }
            } else if (!gf.getNavigation().isIdle() && gf.getRandom().nextInt(20) == 0) {
                gf.getNavigation().stop();   // shed any leftover path so she settles
            }
        }

        // Watchdog: if she hasn't managed to land a hit for a while (stuck path / lost LOS / strafed into a
        // corner), force her to march straight at the target so she never just stands there doing nothing.
        if (++idleAttackTicks > 50) {
            gf.getNavigation().startMovingTo(target, moveSpeed);
            if (idleAttackTicks > 70) idleAttackTicks = 0;   // re-arm the watchdog so it keeps nudging
        }

        if (rangedCd > 0) { rangedCd--; return; }
        if (dist <= maxRange && canSee) {
            rangedCd = Math.max(10, b.rangedIntervalTicks);
            gf.swingHand(Hand.MAIN_HAND);
            castCombo(sw, formTwo);
            comboStep = (comboStep + 1) % 4;
            gf.gainEnergy(b.energyPerHit);
            idleAttackTicks = 0;   // landed a hit → reset the freeze watchdog
        }
    }

    /** Orbit the target: step to a point at the current radius but rotated sideways (circle-strafe). */
    private void strafeAround(LivingEntity target, double speed, boolean formTwo) {
        Vec3d toGf = gf.getEntityPos().subtract(target.getEntityPos());
        double r = Math.max(2.5, Math.hypot(toGf.x, toGf.z));
        double ang = Math.atan2(toGf.z, toGf.x) + strafeDir * (formTwo ? 0.95 : 0.7);
        double nx = target.getX() + Math.cos(ang) * r;
        double nz = target.getZ() + Math.sin(ang) * r;
        gf.getNavigation().startMovingTo(nx, gf.getY(), nz, speed);
    }

    /** Crowded in too close → step back out toward the preferred fighting distance. */
    private void backAwayFrom(LivingEntity target, double speed, double preferred) {
        Vec3d toGf = gf.getEntityPos().subtract(target.getEntityPos());
        Vec3d dir = toGf.lengthSquared() < 1.0e-3 ? new Vec3d(1, 0, 0) : toGf.normalize();
        double nx = target.getX() + dir.x * (preferred + 1.0);
        double nz = target.getZ() + dir.z * (preferred + 1.0);
        gf.getNavigation().startMovingTo(nx, gf.getY(), nz, speed);
    }

    /** 计算周围敌对生物的数量，用于判断是否需要风筝 */
    private int countNearbyHostiles() {
        var world = gf.getEntityWorld();
        var box = gf.getBoundingBox().expand(8.0); // 8格范围内
        var hostiles = world.getOtherEntities(gf, box,
            e -> e instanceof net.minecraft.entity.mob.HostileEntity && e.isAlive());
        return hostiles.size();
    }

    private void castCombo(ServerWorld sw, boolean formTwo) {
        if (formTwo) {
            switch (comboStep) {
                case 0 -> DaniyaAbilities.castThrow(sw, gf, target);
                case 1 -> DaniyaAbilities.castMeteor(sw, gf, target);
                case 2 -> DaniyaAbilities.castShardSwarm(sw, gf, target);
                default -> DaniyaAbilities.castDetonate(sw, gf, target);
            }
        } else {
            switch (comboStep) {
                case 0 -> BubbleAbilities.castShot(sw, gf, target, false);
                case 1 -> BubbleAbilities.castShot(sw, gf, target, true);
                case 2 -> BubbleAbilities.castBigBubble(sw, gf, target);
                default -> BubbleAbilities.castPop(sw, gf, target);
            }
        }
    }

    /** 形态一握专武泡泡杖；形态二空手。 */
    private void equipForForm(boolean formTwo) {
        if (formTwo) gf.emptyMainHand();
        else gf.equipSignatureWeapon();
    }
}
