package xyz.apollodorus.mcgf.entity.goal;

import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;
import xyz.apollodorus.mcgf.entity.work.WorkUtil;

import java.util.EnumSet;
import java.util.List;

/**
 * Walks to nearby loose drops and pockets them into the companion inventory —
 * loot from her own kills, things the owner tosses, or items she previously
 * remembered needing. Runs only when she isn't fighting; lower priority than
 * follow and work so it fills the idle gaps. Ports plugins/InventoryManager.js
 * pickup behaviour.
 */
public class PickupItemsGoal extends Goal {
    private final GirlfriendEntity gf;
    private ItemEntity target;
    private int repathCd;

    public PickupItemsGoal(GirlfriendEntity gf) {
        this.gf = gf;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (!ConfigManager.get().behavior.autoPickup) return false;
        if (gf.getTarget() != null) return false;
        if (gf.isSleeping()) return false;          // 别为了捡东西把打盹中的她叫起来
        this.target = findNearestDrop();
        return target != null;
    }

    @Override
    public boolean shouldContinue() {
        return ConfigManager.get().behavior.autoPickup
            && gf.getTarget() == null && target != null && target.isAlive();
    }

    @Override
    public void stop() {
        this.target = null;
        gf.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (target == null || !target.isAlive()) { target = null; return; }
        gf.getLookControl().lookAt(target);

        if (gf.squaredDistanceTo(target) > 2.0) {
            if (--repathCd <= 0) {
                repathCd = 8;
                gf.getNavigation().startMovingTo(target, ConfigManager.get().behavior.moveSpeed);
            }
            return;
        }
        if (gf.getEntityWorld() instanceof ServerWorld sw) {
            WorkUtil.vacuumDrops(sw, gf, 2.0);
        }
        target = null;
    }

    private ItemEntity findNearestDrop() {
        double r = ConfigManager.get().behavior.pickupRadius;
        Box box = gf.getBoundingBox().expand(r);
        List<Entity> drops = gf.getEntityWorld().getOtherEntities(gf, box,
            e -> e instanceof ItemEntity && e.isAlive());
        ItemEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity e : drops) {
            double sq = gf.squaredDistanceTo(e);
            if (sq < bestSq) {
                bestSq = sq;
                best = (ItemEntity) e;
            }
        }
        return best;
    }
}
