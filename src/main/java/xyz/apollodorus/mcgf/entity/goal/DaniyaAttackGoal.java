package xyz.apollodorus.mcgf.entity.goal;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import xyz.apollodorus.mcgf.combat.BubbleAbilities;
import xyz.apollodorus.mcgf.combat.DaniyaAbilities;
import xyz.apollodorus.mcgf.combat.DaniyaCombatEngine;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.entity.work.ItemAppraiser;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
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
    private boolean fleeing;       // 形态一风筝状态：true=正在逃开拉距离（背对着跑、不打），false=已拉开、停下面敌开火
    private int fleeTicks;         // 连续逃跑的 tick 数（没拉开就一直涨）——用来判断「跑不过/没空间」
    private long standFightUntil;  // 逃不掉时停下站桩还击到这个游戏刻为止

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
        this.fleeing = false;
        this.fleeTicks = 0;
        this.standFightUntil = 0;
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

        // 形态一：strafe 风筝——始终正面朝敌，用 moveControl.strafeTo 相对自身朝向移动；太近就「面对敌人后撤」拉开、
        // 太远就靠近、距离合适则缓退 + 侧移走位。形态二：贴近 + 环绕（沿用寻路）。
        if (!formTwo) {
            kiteFormOne(target, b, dist, canSee);
        } else {
            double moveSpeed = b.moveSpeed * 1.35;
            double band = 1.0;
            boolean mustApproach = dist > preferred + band || (!canSee && dist > preferred);
            if (mustApproach) {
                if (--repathCd <= 0) { repathCd = 8; gf.getNavigation().startMovingTo(target, moveSpeed); }
            } else if (dist < preferred - band - 0.5) {
                if (--repathCd <= 0) { repathCd = 10; backAwayFrom(target, moveSpeed, preferred); }
            } else {
                if (--strafeCd <= 0) {
                    strafeCd = 8 + gf.getRandom().nextInt(8);
                    if (--strafeFlipCd <= 0) { strafeFlipCd = 3 + gf.getRandom().nextInt(4); strafeDir = -strafeDir; }
                    strafeAround(target, moveSpeed * 0.8, true);
                }
            }
            // 形态二 watchdog：长时间没命中就直接压上去。
            if (++idleAttackTicks > 50) {
                gf.getNavigation().startMovingTo(target, moveSpeed);
                if (idleAttackTicks > 70) idleAttackTicks = 0;
            }
        }

        // 施法：形态一逃跑途中不开火（背对着跑），已拉开、停下面敌时才打；被逼到角落逃不掉（寻路走不动）时也照样还击，别站着挨打。形态二贴身即可。
        boolean canAttack = formTwo || !fleeing || gf.getNavigation().isIdle();
        if (rangedCd > 0) { rangedCd--; return; }
        if (canAttack && dist <= maxRange && canSee) {
            rangedCd = Math.max(10, b.rangedIntervalTicks);
            // ?????????????????????? / ?????
            if (gf.getRandom().nextFloat() < 0.35f && DaniyaCombatEngine.tryCastResonanceSkill(sw, gf, target)) {
                gf.gainEnergy((int)(b.energyPerHit * 1.5));
                idleAttackTicks = 0;
                return;
            }
            gf.swingHand(Hand.MAIN_HAND);
            castCombo(sw, formTwo);
            comboStep = (comboStep + 1) % 4;
            gf.gainEnergy(b.energyPerHit);
            idleAttackTicks = 0;   // landed a hit → reset the freeze watchdog
        }
    }

    /**
     * 形态一·strafe 风筝：始终正面朝敌（设身体朝向，strafeTo 据此算移动方向），按距离决定 forward——太远 forward&gt;0
     * 靠近到射程、太近（或有怪贴脸）forward&lt;0「面对敌人后撤」拉开、距离合适则缓退 + 侧移走位。{@code strafeTo} 不走
     * 寻路、移动相对身体朝向，所以她能边正面对敌、边向后/侧向拉开，靠远程优势风筝——这才是真正的风筝，而不是背身盲狙。
     */
    /**
     * 形态一·风筝（跑-停-打循环）：以「最近的怪」（不只当前目标）为准——最近的怪逼近到 fleeDist 内，她就<strong>转身背对着
     * 往远离所有近处怪的方向跑开</strong>（真·拉开距离，不是面敌倒走），逃跑途中不开火；一旦拉开到 safeDist 外，就停下、
     * 转身正对目标、原地开火。中间用迟滞避免抖动。看不见/超射程则走近到射程再打。
     */
    private void kiteFormOne(LivingEntity target, GirlfriendConfig.Behavior b, double dist, boolean canSee) {
        double nearestSq = nearestHostileSq();
        double fleeDist = 5.0, safeDist = 8.0;   // 缩短：进到5格逃、拉开到8格就停下打（别跑太远）
        long now = gf.getEntityWorld().getTime();

        // 逃不掉就别无限风筝：跑不过快怪（小僵尸）/没空间时会一直逃到被耗死——明明打得过。所以站桩还击一阵。
        if (now < standFightUntil) {
            fleeing = false;
            gf.getNavigation().stop();
            faceEntity(target);
            return;
        }

        if (nearestSq < fleeDist * fleeDist) fleeing = true;          // 有怪逼近 → 逃
        else if (nearestSq > safeDist * safeDist) fleeing = false;    // 拉开够了 → 停下打

        if (fleeing) {
            if (++fleeTicks > 70) {          // 逃了 s 还没甩开（跑不过/没空间）→ 停下站桩打 ~2.5s，别被耗死
                fleeTicks = 0;
                standFightUntil = now + 50;
                fleeing = false;
                gf.getNavigation().stop();
                faceEntity(target);
                return;
            }
            if (--repathCd <= 0) { repathCd = 6; fleeAwayFromHostiles(b.moveSpeed * 1.35); }
        } else {
            fleeTicks = 0;
            if (dist > b.rangedMaxDistance || !canSee) {
                if (--repathCd <= 0) { repathCd = 12; gf.getNavigation().startMovingTo(target, b.moveSpeed); }
            } else {
                gf.getNavigation().stop();   // 已拉开、射程内 → 停下、转身正对目标、原地开火
                faceEntity(target);
            }
        }
    }

    /**
     * 逃开：朝「远离附近所有怪的合力方向」寻路跑开（背对敌人、全速）——越近的怪权重越大，所以她往人最少、最空的方向撤。
     * 用寻路（不是 strafe），所以是真正转身跑开、会绕障，而不是面朝敌人倒走。
     */
    private void fleeAwayFromHostiles(double speed) {
        Vec3d away = Vec3d.ZERO;
        var box = gf.getBoundingBox().expand(10.0);
        for (net.minecraft.entity.Entity e : gf.getEntityWorld().getOtherEntities(gf, box,
                x -> x instanceof net.minecraft.entity.mob.HostileEntity && x.isAlive())) {
            Vec3d d = gf.getEntityPos().subtract(e.getEntityPos());
            double len = d.length();
            if (len > 1.0e-3) away = away.add(d.multiply(1.0 / (len * len)));
        }
        if (away.lengthSquared() < 1.0e-6) return;
        away = away.normalize();
        gf.getNavigation().startMovingTo(gf.getX() + away.x * 8.0, gf.getY(), gf.getZ() + away.z * 8.0, speed);
    }

    /** Turn to face the target — used only in the attack stance (逃跑时靠寻路自然背对敌人跑，不在这里转身). */
    private void faceEntity(LivingEntity target) {
        double dx = target.getX() - gf.getX();
        double dz = target.getZ() - gf.getZ();
        if (dx * dx + dz * dz < 1.0e-4) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        gf.setYaw(yaw);
        gf.setBodyYaw(yaw);
        gf.setHeadYaw(yaw);
        gf.getLookControl().lookAt(target, 30f, 30f);
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

    /** Crowded in too close → step back out, away from the COMBINED direction of all nearby mobs. */
    private void backAwayFrom(LivingEntity target, double speed, double preferred) {
        // 远离附近所有怪的合力方向后退，避免「退开当前目标却退进另一只怪」被夹击。越近的怪权重越大。
        Vec3d away = Vec3d.ZERO;
        var box = gf.getBoundingBox().expand(7.0);
        for (net.minecraft.entity.Entity e : gf.getEntityWorld().getOtherEntities(gf, box,
                x -> x instanceof net.minecraft.entity.mob.HostileEntity && x.isAlive())) {
            Vec3d d = gf.getEntityPos().subtract(e.getEntityPos());
            double len = d.length();
            if (len > 1.0e-3) away = away.add(d.multiply(1.0 / (len * len)));
        }
        if (away.lengthSquared() < 1.0e-6) {
            Vec3d toGf = gf.getEntityPos().subtract(target.getEntityPos());
            away = toGf.lengthSquared() < 1.0e-3 ? new Vec3d(1, 0, 0) : toGf;
        }
        away = away.normalize();
        double nx = gf.getX() + away.x * (preferred + 1.0);
        double nz = gf.getZ() + away.z * (preferred + 1.0);
        gf.getNavigation().startMovingTo(nx, gf.getY(), nz, speed);
    }

    /** Squared distance to the nearest live hostile within 16 blocks (MAX_VALUE if none). */
    private double nearestHostileSq() {
        double best = Double.MAX_VALUE;
        var box = gf.getBoundingBox().expand(16.0);
        for (net.minecraft.entity.Entity e : gf.getEntityWorld().getOtherEntities(gf, box,
                x -> x instanceof net.minecraft.entity.mob.HostileEntity && x.isAlive())) {
            best = Math.min(best, gf.squaredDistanceTo(e));
        }
        return best;
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

    /** 智能适应形态与武器装备：
     *  - 形态二：空手释放蚀域湮灭技能与虚质撕裂
     *  - 形态一：智能评估主手与背包，若玩家给了强力神兵、现代枪械（TACZ/PointBlank/CGM等）或极品附魔武器，优先使用；无更强武器时默认使用专武泡泡法杖！
     */
    private void equipForForm(boolean formTwo) {
        if (formTwo) {
            gf.emptyMainHand();
            return;
        }

        // 评估背包中是否有枪械或更高评分的模组武器
        ItemStack current = gf.getEquippedStack(EquipmentSlot.MAINHAND);
        int currentScore = current.isEmpty() ? -1 : ItemAppraiser.evaluate(current, gf).score();
        
        int bestSlot = -1;
        int bestScore = currentScore;
        boolean currentIsGun = !current.isEmpty() && ItemAppraiser.isFirearmLike(net.minecraft.registry.Registries.ITEM.getId(current.getItem()).getPath());

        for (int i = 0; i < gf.getInventory().size(); i++) {
            ItemStack stack = gf.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            var eval = ItemAppraiser.evaluate(stack, gf);
            boolean isGun = ItemAppraiser.isFirearmLike(net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).getPath());

            // 枪械武器优先赋能，或者评分显著高于当前手持武器 (+15 分)
            if (isGun && !currentIsGun) {
                bestSlot = i;
                bestScore = eval.score() + 50; // 枪械偏好加权
                break;
            } else if (eval.score() > bestScore + 15) {
                bestScore = eval.score();
                bestSlot = i;
            }
        }

        if (bestSlot != -1) {
            ItemStack chosen = gf.getInventory().removeStack(bestSlot);
            if (!current.isEmpty()) {
                gf.getInventory().addStack(current);
            }
            gf.equipStack(EquipmentSlot.MAINHAND, chosen);
            return;
        }

        // 若当前未持有优秀模组武器，确保装备泡泡法杖专武
        if (current.isEmpty()) {
            gf.equipSignatureWeapon();
        }
    }
}
