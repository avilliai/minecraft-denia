package xyz.apollodorus.mcgf.entity.goal;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.config.GirlfriendConfig;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;
import xyz.apollodorus.mcgf.entity.work.WorkUtil;

import java.util.EnumSet;
import java.util.List;

/**
 * Target-selection goal: when combat is enabled she locks onto hostiles threatening her owner
 * (or herself), letting {@link DaniyaAttackGoal} close in and strike — sword up close, the void
 * combo at range. Ports the gist of the original plugins/Guardian.js scan/engage.
 *
 * <p>She is deliberately NOT omniscient: she only targets a mob she can actually
 * SEE and can actually build a path to. A hostile walled off behind blocks, or
 * across a gap she can't cross, simply isn't chosen — so she never stands aggro'd
 * at a wall (the same honesty {@link WorkGoal} applies to buried ore). The one
 * exception is retaliation: anything actively hitting her is fair game.
 */
public class ProtectOwnerGoal extends Goal {
    private static final double ATTACK_REACH_SQ = 9.0; // ~3 blocks: melee range

    private final GirlfriendEntity gf;
    private LivingEntity candidate;

    // stuck-guard: drop a target she stops closing on (a wall between them)
    private double lastDistSq = Double.MAX_VALUE;
    private int noProgressTicks;
    // brief cooldown so a just-dropped unreachable mob isn't re-locked instantly
    private LivingEntity recentlyDropped;
    private long dropCooldownUntil;
    // periodic re-target so a near threat (esp. one hitting her) takes over from a far one
    private int retargetCd;

    public ProtectOwnerGoal(GirlfriendEntity gf) {
        this.gf = gf;
        this.setControls(EnumSet.of(Control.TARGET));
    }

    @Override
    public boolean canStart() {
        if (!gf.isCombatEnabled()) return false;
        this.candidate = findThreat();
        return candidate != null;
    }

    @Override
    public boolean shouldContinue() {
        if (!gf.isCombatEnabled()) return false;
        LivingEntity t = gf.getTarget();
        if (t == null || !t.isAlive()) return false;
        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        double maxChase = b.maxChaseDistance;
        double dsq = gf.squaredDistanceTo(t);
        // A visible mob she can range is engageable out to the (larger) ranged distance, not just maxChase.
        double keepSq = Math.max(maxChase * maxChase,
            (b.combatRangedEnabled ? b.rangedMaxDistance * b.rangedMaxDistance : 0));
        if (dsq > keepSq) return false;

        // Out of melee range and not closing the gap (and it isn't hitting her) → she's
        // stuck against terrain. Give the spot up instead of freezing aggro'd at a wall — UNLESS
        // she's a ranged fighter still holding a clear line on it (then standing off is intended).
        boolean rangedHolding = b.combatRangedEnabled && gf.canSee(t)
            && dsq <= b.rangedMaxDistance * b.rangedMaxDistance;
        if (dsq > ATTACK_REACH_SQ && gf.getAttacker() != t && !rangedHolding) {
            if (dsq >= lastDistSq - 0.05) {
                if (++noProgressTicks > 60) {
                    recentlyDropped = t;
                    dropCooldownUntil = gf.getEntityWorld().getTime() + 100L;
                    return false;
                }
            } else {
                noProgressTicks = 0;
            }
        } else {
            noProgressTicks = 0;
        }
        lastDistSq = dsq;
        return true;
    }

    @Override
    public void start() {
        gf.setTarget(candidate);
        this.lastDistSq = Double.MAX_VALUE;
        this.noProgressTicks = 0;
        WorkUtil.equipTool(gf, WorkUtil.ToolKind.SWORD);
    }

    @Override
    public void stop() {
        gf.setTarget(null);
        this.candidate = null;
    }

    /**
     * 周期性重选目标：盯着远处怪打、却被近处怪贴脸打死，是一形态风筝的主要死法。这里每 ~0.5s 复查一次——
     * 正在打她的近处怪最优先，其次是明显更近且能打的威胁——让她始终先解决离自己最近的威胁。
     */
    @Override
    public void tick() {
        if (--retargetCd > 0) return;
        retargetCd = 10;
        LivingEntity current = gf.getTarget();
        if (current == null || !gf.isCombatEnabled()) return;
        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        double curSq = gf.squaredDistanceTo(current);

        LivingEntity attacker = gf.getAttacker();
        if (attacker instanceof HostileEntity && attacker.isAlive() && attacker != current
                && gf.squaredDistanceTo(attacker) + 1.0 < curSq) {
            switchTo(attacker);
            return;
        }
        LivingEntity nearer = nearestEngageable(b);
        if (nearer != null && nearer != current && gf.squaredDistanceTo(nearer) + 9.0 < curSq) {
            switchTo(nearer);
        }
    }

    private void switchTo(LivingEntity t) {
        gf.setTarget(t);
        this.lastDistSq = Double.MAX_VALUE;
        this.noProgressTicks = 0;
    }

    /** Nearest hostile she can honestly engage (see + reach/range), scanned around owner-or-self. */
    private LivingEntity nearestEngageable(GirlfriendConfig.Behavior b) {
        Box box = scanBox(b.guardRadius);
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity e : gf.getEntityWorld().getOtherEntities(gf, box,
                x -> x instanceof HostileEntity && x.isAlive())) {
            LivingEntity le = (LivingEntity) e;
            if (!canEngage(le, b)) continue;
            double sq = gf.squaredDistanceTo(e);
            if (sq < bestSq) { bestSq = sq; best = le; }
        }
        return best;
    }

    private LivingEntity findThreat() {
        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;

        // Pile on whatever the owner just swung at — but only if she can honestly get to it.
        LivingEntity assist = gf.getAssistTarget();
        if (assist != null && !gf.isLowHealth() && canEngage(assist, b)) return assist;

        // Retaliation is always allowed: something hitting her is, by definition, right there.
        LivingEntity attacker = gf.getAttacker();
        if (attacker instanceof HostileEntity && attacker.isAlive()) return attacker;

        double r = b.guardRadius;
        PlayerEntity owner = gf.getOwner();

        // When she's badly hurt she stops picking fights and only defends the owner.
        boolean defensiveOnly = gf.isLowHealth();

        // Search around whichever anchor exists; prefer mobs close to girlfriend herself (for better kiting).
        // 驻守时改为以驻守锚点为中心扫描，且只打半径内的怪——她守的是这片区域，不追远。
        Box box = scanBox(r);
        List<Entity> nearby = gf.getEntityWorld().getOtherEntities(gf, box,
            e -> e instanceof HostileEntity && e.isAlive());

        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity e : nearby) {
            LivingEntity le = (LivingEntity) e;
            if (defensiveOnly && (owner == null || ((HostileEntity) e).getTarget() != owner)) continue;
            if (!canEngage(le, b)) continue; // skip mobs she can't see / can't reach
            // Prefer mobs closest to girlfriend herself (not owner), for better target switching during kiting
            double sq = gf.squaredDistanceTo(e);
            if (sq < bestSq) {
                bestSq = sq;
                best = le;
            }
        }
        return best;
    }

    /**
     * Honest engageability: she must be able to SEE the mob and (optionally) have a
     * real path that actually reaches it. A mob she just gave up on is ignored for a
     * couple of seconds so she doesn't immediately re-lock the same wall.
     */
    private boolean canEngage(LivingEntity e, GirlfriendConfig.Behavior b) {
        if (e == recentlyDropped && gf.getEntityWorld().getTime() < dropCooldownUntil) return false;
        if (!withinGarrison(e)) return false;   // 驻守时不打区域外的怪
        if (b.combatRequireLineOfSight && !gf.canSee(e)) return false;
        if (b.combatRequireReachable) {
            Path path = gf.getNavigation().findPathTo(e, 0);
            boolean reachable = path != null && path.reachesTarget();
            if (!reachable) {
                // Ranged fallback: a mob she can SEE within ranged band is still engageable
                // (she'll throw void matter across the gap) even with no walking path to it.
                boolean rangedOk = b.combatRangedEnabled && gf.canSee(e)
                    && gf.squaredDistanceTo(e) <= b.rangedMaxDistance * b.rangedMaxDistance;
                if (!rangedOk) return false;
            }
        }
        return true;
    }

    /** Scan box centered on the garrison post (when garrisoned) or on owner-or-self otherwise. */
    private Box scanBox(double r) {
        if (gf.isGarrisoned() && gf.getGarrisonPos() != null) {
            double gr = Math.min(r, Math.max(2.0, ConfigManager.get().behavior.garrisonRadius));
            return new Box(gf.getGarrisonPos()).expand(gr);
        }
        Entity anchor = gf.getOwner() != null ? gf.getOwner() : gf;
        return anchor.getBoundingBox().expand(r);
    }

    /** When garrisoned, ignore mobs outside the garrison radius so she never chases far from her post. */
    private boolean withinGarrison(Entity e) {
        if (!gf.isGarrisoned()) return true;
        BlockPos g = gf.getGarrisonPos();
        if (g == null) return true;
        double r = Math.max(2.0, ConfigManager.get().behavior.garrisonRadius);
        return e.getBlockPos().getSquaredDistance(g) <= r * r;
    }
}
