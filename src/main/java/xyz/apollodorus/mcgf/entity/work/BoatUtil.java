package xyz.apollodorus.mcgf.entity.work;

import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.util.math.Box;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.util.List;

/** Boarding / leaving boats — shared by the command and the LLM tool. */
public final class BoatUtil {
    private BoatUtil() {}

    /** Board the nearest boat within {@code radius} blocks. Returns true if she's now riding one. */
    public static boolean boardNearest(GirlfriendEntity gf, double radius) {
        if (gf.hasVehicle()) return true; // already aboard something
        Box box = gf.getBoundingBox().expand(radius);
        List<AbstractBoatEntity> boats = gf.getEntityWorld()
            .getEntitiesByClass(AbstractBoatEntity.class, box, b -> b.isAlive());
        AbstractBoatEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (AbstractBoatEntity b : boats) {
            double d = b.squaredDistanceTo(gf);
            if (d < bestSq) { bestSq = d; best = b; }
        }
        if (best == null) return false;
        gf.getNavigation().stop();
        return gf.startRiding(best);
    }

    /** Get off whatever she's riding. Returns true if she actually was riding something. */
    public static boolean disembark(GirlfriendEntity gf) {
        if (!gf.hasVehicle()) return false;
        gf.stopRiding();
        return true;
    }
}
