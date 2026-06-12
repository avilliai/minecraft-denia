package xyz.apollodorus.mcgf.screen;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

/**
 * Inventory view over a girlfriend for the panel: slots 0..26 are her 27-slot
 * backpack, 27..32 are her live equipment (head/chest/legs/feet/offhand/mainhand).
 * Bridging the equipment in means the held tool (e.g. her pickaxe) is finally
 * VISIBLE and removable, instead of disappearing into an equipment slot the old
 * chest GUI never showed.
 */
public class CompanionInventory implements Inventory {
    public static final int SIZE = 33;
    private static final EquipmentSlot[] GEAR = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
        EquipmentSlot.FEET, EquipmentSlot.OFFHAND, EquipmentSlot.MAINHAND
    };

    private final GirlfriendEntity gf;

    public CompanionInventory(GirlfriendEntity gf) { this.gf = gf; }

    private static boolean isGear(int slot) { return slot >= 27 && slot < SIZE; }
    private static EquipmentSlot gearSlot(int slot) { return GEAR[slot - 27]; }

    @Override
    public int size() { return SIZE; }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < SIZE; i++) if (!getStack(i).isEmpty()) return false;
        return true;
    }

    @Override
    public ItemStack getStack(int slot) {
        return isGear(slot) ? gf.getEquippedStack(gearSlot(slot)) : gf.getInventory().getStack(slot);
    }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        if (!isGear(slot)) return gf.getInventory().removeStack(slot, amount);
        ItemStack cur = getStack(slot).copy();
        if (cur.isEmpty() || amount <= 0) return ItemStack.EMPTY;
        ItemStack taken = cur.split(amount);
        setStack(slot, cur.isEmpty() ? ItemStack.EMPTY : cur);
        return taken;
    }

    @Override
    public ItemStack removeStack(int slot) {
        if (!isGear(slot)) return gf.getInventory().removeStack(slot);
        ItemStack cur = getStack(slot).copy();
        setStack(slot, ItemStack.EMPTY);
        return cur;
    }

    @Override
    public void setStack(int slot, ItemStack stack) {
        if (isGear(slot)) gf.equipStack(gearSlot(slot), stack);
        else gf.getInventory().setStack(slot, stack);
    }

    @Override
    public void markDirty() { gf.getInventory().markDirty(); }

    @Override
    public boolean canPlayerUse(PlayerEntity player) {
        return gf.isAlive() && gf.isOwner(player) && gf.squaredDistanceTo(player) < 64.0;
    }

    @Override
    public void clear() {
        for (int i = 0; i < SIZE; i++) setStack(i, ItemStack.EMPTY);
    }
}
