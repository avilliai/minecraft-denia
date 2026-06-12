package xyz.apollodorus.mcgf.screen;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import xyz.apollodorus.mcgf.item.ModItems;

/**
 * Server/common handler for the companion panel. Lays out her 6 equipment slots
 * + 27 backpack slots, then the player's inventory + hotbar. The client builds the
 * same layout against a stand-in inventory; vanilla syncs the stack contents over.
 */
public class CompanionScreenHandler extends ScreenHandler {
    private final Inventory companion;
    private final CompanionScreenData data;
    /** Always-full, take-only source of 寻路信标 — the player can pull beacons from it without limit. */
    private final Inventory beaconSource = new BeaconSource();

    /** Client constructor — registered with the {@code ExtendedScreenHandlerType}. */
    public CompanionScreenHandler(int syncId, PlayerInventory playerInv, CompanionScreenData data) {
        this(syncId, playerInv, new SimpleInventory(CompanionInventory.SIZE), data);
    }

    /** Shared constructor — the server passes the live {@link CompanionInventory}. */
    public CompanionScreenHandler(int syncId, PlayerInventory playerInv, Inventory companion, CompanionScreenData data) {
        super(ModScreens.COMPANION, syncId);
        checkSize(companion, CompanionInventory.SIZE);
        this.companion = companion;
        this.data = data;

        // Her equipment row (y=40): mainhand, offhand, then head/chest/legs/feet.
        // The four armor slots only accept the matching armor piece (like a player's
        // armor slots), so a sword can't be jammed into the helmet slot.
        addSlot(new Slot(companion, 32, 26, 40));
        addSlot(new Slot(companion, 31, 44, 40));
        addSlot(new ArmorSlot(companion, 27, 80, 40, EquipmentSlot.HEAD));
        addSlot(new ArmorSlot(companion, 28, 98, 40, EquipmentSlot.CHEST));
        addSlot(new ArmorSlot(companion, 29, 116, 40, EquipmentSlot.LEGS));
        addSlot(new ArmorSlot(companion, 30, 134, 40, EquipmentSlot.FEET));

        // Her backpack, 9x3 (y=64).
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new Slot(companion, row * 9 + col, 8 + col * 18, 64 + row * 18));

        // Player inventory, 9x3 (y=126).
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new Slot(playerInv, 9 + row * 9 + col, 8 + col * 18, 126 + row * 18));

        // Player hotbar (y=184).
        for (int col = 0; col < 9; col++)
            addSlot(new Slot(playerInv, col, 8 + col * 18, 184));

        // 专属信标槽（最后一个槽，索引在玩家物品之后）：永远有一组寻路信标，take-only，玩家可无限拿取。
        addSlot(new BeaconSlot(beaconSource, 0, 152, 40));
    }

    public CompanionScreenData data() { return data; }

    @Override
    public boolean canUse(PlayerEntity player) {
        return companion.canPlayerUse(player);
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasStack()) return ItemStack.EMPTY;

        int companionEnd = CompanionInventory.SIZE;   // her slots: 0..32
        int playerEnd = companionEnd + 36;            // + player inv + hotbar

        // Shift-click the infinite beacon slot → hand the player ONE stack of beacons, then stop the
        // quick-move loop (return EMPTY) so it doesn't keep dumping beacons into every free slot.
        if (slot instanceof BeaconSlot) {
            ItemStack give = new ItemStack(ModItems.PATH_BEACON, 16);
            insertItem(give, companionEnd, playerEnd, true);
            return ItemStack.EMPTY;
        }

        ItemStack stack = slot.getStack();
        ItemStack original = stack.copy();
        if (index < companionEnd) {
            if (!insertItem(stack, companionEnd, playerEnd, true)) return ItemStack.EMPTY;
        } else if (!insertItem(stack, 0, companionEnd, false)) {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) slot.setStack(ItemStack.EMPTY);
        else slot.markDirty();
        return original;
    }

    /** An armor slot that only accepts an item equippable in its specific slot. */
    private static class ArmorSlot extends Slot {
        private final EquipmentSlot slot;

        ArmorSlot(Inventory inv, int index, int x, int y, EquipmentSlot slot) {
            super(inv, index, x, y);
            this.slot = slot;
        }

        @Override
        public boolean canInsert(ItemStack stack) {
            EquippableComponent eq = stack.get(DataComponentTypes.EQUIPPABLE);
            return eq != null && eq.slot() == slot;
        }

        @Override
        public int getMaxItemCount() {
            return 1;
        }
    }

    /** A take-only slot over the always-full beacon source: can't accept inserts, and taking never depletes it. */
    private static class BeaconSlot extends Slot {
        BeaconSlot(Inventory inv, int index, int x, int y) {
            super(inv, index, x, y);
        }

        @Override
        public boolean canInsert(ItemStack stack) {
            return false;   // pure source, not a storage slot
        }

        @Override
        public ItemStack takeStack(int amount) {
            // Hand out beacons without ever touching/depleting the (always-full) backing inventory.
            return new ItemStack(ModItems.PATH_BEACON, Math.min(amount, 16));
        }
    }

    /** One-slot inventory that always reports a full stack of beacons — an inexhaustible dispenser. */
    private static class BeaconSource implements Inventory {
        @Override public int size() { return 1; }
        @Override public boolean isEmpty() { return false; }
        @Override public ItemStack getStack(int slot) { return new ItemStack(ModItems.PATH_BEACON, 16); }
        @Override public ItemStack removeStack(int slot, int amount) { return new ItemStack(ModItems.PATH_BEACON, Math.min(amount, 16)); }
        @Override public ItemStack removeStack(int slot) { return new ItemStack(ModItems.PATH_BEACON, 16); }
        @Override public void setStack(int slot, ItemStack stack) { /* read-only source */ }
        @Override public void markDirty() { }
        @Override public boolean canPlayerUse(PlayerEntity player) { return true; }
        @Override public void clear() { }
    }
}
