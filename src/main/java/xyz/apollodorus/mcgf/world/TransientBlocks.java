package xyz.apollodorus.mcgf.world;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import xyz.apollodorus.mcgf.block.ModBlocks;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Tracks every 虚质方块 the mod places into the world transiently — bridge/pillar blocks she lays
 * while pathing (auto-vanish ~60s) and the floor/sky blocks of her 蚀域 domain (restored when the
 * domain ends). Each entry remembers the ORIGINAL state it replaced, so restoration is exact.
 *
 * <p>Restore is conditional: a tracked spot is only reverted if it is STILL our void block. If a
 * player mined or replaced it in the meantime, the entry is silently dropped — we never clobber a
 * player's edit. Driven once per server tick by {@link xyz.apollodorus.mcgf.combat.AbilityManager};
 * keyed implicitly per-world by holding the {@link ServerWorld} on each entry (worlds live for the
 * whole server, so the reference is safe). All access is on the server thread.
 */
public final class TransientBlocks {

    /** One placed void block: where it is, what it replaced, when it auto-expires, and who owns it. */
    private record Entry(ServerWorld world, BlockPos pos, BlockState original, long expiry, Object owner) {}

    private final List<Entry> entries = new ArrayList<>();

    /**
     * Replace the block at {@code pos} with the void block, remembering the original for restoration.
     * {@code owner} groups blocks for {@link #restoreOwned} (e.g. a domain instance); pass {@code null}
     * for standalone time-expiring blocks (bridging). {@code expiryTick} is compared against
     * {@code world.getTime()}.
     */
    public void place(ServerWorld world, BlockPos pos, long expiryTick, Object owner) {
        BlockPos p = pos.toImmutable();
        BlockState original = world.getBlockState(p);
        world.setBlockState(p, ModBlocks.VOID_BLOCK.getDefaultState());
        entries.add(new Entry(world, p, original, expiryTick, owner));
    }

    /** Restore every block whose time is up (server-tick driver). */
    public void tick() {
        Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (e.world.getTime() >= e.expiry) {
                restore(e);
                it.remove();
            }
        }
    }

    /** Immediately restore all blocks owned by {@code owner} (e.g. a domain ending). */
    public void restoreOwned(Object owner) {
        if (owner == null) return;
        Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (owner.equals(e.owner)) {
                restore(e);
                it.remove();
            }
        }
    }

    /** Restore everything (server shutdown). */
    public void restoreAll() {
        for (Entry e : entries) restore(e);
        entries.clear();
    }

    private void restore(Entry e) {
        // Only revert if the spot is still our void block — never clobber a player's later edit.
        if (e.world.getBlockState(e.pos).isOf(ModBlocks.VOID_BLOCK)) {
            e.world.setBlockState(e.pos, e.original);
        }
    }
}
