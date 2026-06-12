package xyz.apollodorus.mcgf.util;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.util.List;

/** Locates the girlfriend a given player should be talking to / commanding. */
public final class GfFinder {
    private GfFinder() {}

    /**
     * Nearest girlfriend within {@code radius} blocks of the player. Prefers one
     * the player already owns; otherwise the closest unowned one (which the
     * caller may then claim). Returns null if none nearby.
     */
    public static GirlfriendEntity nearest(ServerPlayerEntity player, double radius) {
        Box box = player.getBoundingBox().expand(radius);
        List<GirlfriendEntity> list = player.getEntityWorld()
            .getEntitiesByClass(GirlfriendEntity.class, box, e -> e.isAlive());
        GirlfriendEntity bestOwned = null, bestAny = null;
        double dOwned = Double.MAX_VALUE, dAny = Double.MAX_VALUE;
        for (GirlfriendEntity gf : list) {
            double d = gf.squaredDistanceTo(player);
            if (d < dAny) { dAny = d; bestAny = gf; }
            if (gf.isOwner(player) && d < dOwned) { dOwned = d; bestOwned = gf; }
        }
        return bestOwned != null ? bestOwned : bestAny;
    }
}
