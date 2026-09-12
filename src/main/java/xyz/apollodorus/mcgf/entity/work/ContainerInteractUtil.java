package xyz.apollodorus.mcgf.entity.work;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.SmeltingRecipe;
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ???????????????????????????
 * ??????????????????????????/????????
 */
public final class ContainerInteractUtil {
    private ContainerInteractUtil() {}

    public record ContainerInfo(BlockPos pos, String type, int freeSlots, int totalSlots, List<String> summary) {}

    public static List<BlockPos> findNearbyContainers(ServerWorld world, BlockPos center, int radius) {
        List<BlockPos> list = new ArrayList<>();
        BlockPos.iterate(center.add(-radius, -3, -radius), center.add(radius, 4, radius)).forEach(p -> {
            BlockEntity be = world.getBlockEntity(p);
            if (be instanceof Inventory) {
                list.add(p.toImmutable());
            }
        });
        return list;
    }

    public static List<BlockPos> findNearbyFurnaces(ServerWorld world, BlockPos center, int radius) {
        List<BlockPos> list = new ArrayList<>();
        BlockPos.iterate(center.add(-radius, -3, -radius), center.add(radius, 4, radius)).forEach(p -> {
            BlockEntity be = world.getBlockEntity(p);
            if (be instanceof AbstractFurnaceBlockEntity) {
                list.add(p.toImmutable());
            }
        });
        return list;
    }

    public static String inspectContainer(ServerWorld world, BlockPos pos) {
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof Inventory inv)) {
            return "周围5格内没有找到箱子或容器";
        }
        int total = inv.size();
        int used = 0;
        StringBuilder sb = new StringBuilder();
        sb.append(be.getClass().getSimpleName()).append(" 在 ").append(pos.toShortString()).append("：\n");
        for (int i = 0; i < total; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty()) {
                used++;
                if (used <= 8) {
                    sb.append("- ").append(s.getName().getString()).append(" x").append(s.getCount()).append("\n");
                }
            }
        }
        sb.append("占用格子: ").append(used).append("/").append(total);
        if (used > 8) sb.append(" (显示前8项)");
        return sb.toString();
    }

    public static int takeItemFromContainer(ServerWorld world, BlockPos pos, GirlfriendEntity gf, Item targetItem, int count) {
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof Inventory inv)) return 0;
        int taken = 0;
        for (int i = 0; i < inv.size() && taken < count; i++) {
            ItemStack st = inv.getStack(i);
            if (!st.isEmpty() && (targetItem == null || st.isOf(targetItem))) {
                int qty = Math.min(st.getCount(), count - taken);
                ItemStack transfer = st.split(qty);
                ItemStack leftover = gf.addToInventory(transfer);
                int actual = qty - leftover.getCount();
                taken += actual;
                if (!leftover.isEmpty()) {
                    st.increment(leftover.getCount());
                }
                inv.markDirty();
            }
        }
        return taken;
    }

    public static int depositItemToContainer(ServerWorld world, BlockPos pos, GirlfriendEntity gf, Item targetItem, int count) {
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof Inventory inv)) return 0;
        SimpleInventory gfInv = gf.getInventory();
        int deposited = 0;
        for (int i = 0; i < gfInv.size() && deposited < count; i++) {
            ItemStack st = gfInv.getStack(i);
            if (!st.isEmpty() && (targetItem == null || st.isOf(targetItem))) {
                int qty = Math.min(st.getCount(), count - deposited);
                ItemStack toDeposit = st.split(qty);
                for (int slot = 0; slot < inv.size() && !toDeposit.isEmpty(); slot++) {
                    ItemStack targetSlot = inv.getStack(slot);
                    if (targetSlot.isEmpty()) {
                        inv.setStack(slot, toDeposit.copy());
                        toDeposit.setCount(0);
                    } else if (ItemStack.areItemsAndComponentsEqual(targetSlot, toDeposit) && targetSlot.getCount() < targetSlot.getMaxCount()) {
                        int fit = Math.min(toDeposit.getCount(), targetSlot.getMaxCount() - targetSlot.getCount());
                        targetSlot.increment(fit);
                        toDeposit.decrement(fit);
                    }
                }
                int actual = qty - toDeposit.getCount();
                deposited += actual;
                if (!toDeposit.isEmpty()) {
                    st.increment(toDeposit.getCount());
                }
                inv.markDirty();
            }
        }
        return deposited;
    }

    public static boolean isFuelItem(ServerWorld world, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        try {
            return world.getFuelRegistry().isFuel(stack);
        } catch (Throwable t) {
            Item it = stack.getItem();
            return it == Items.COAL || it == Items.CHARCOAL || it == Items.LAVA_BUCKET || it == Items.BLAZE_ROD || it == Items.COAL_BLOCK;
        }
    }

    public static String interactWithFurnace(ServerWorld world, BlockPos pos, GirlfriendEntity gf) {
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof AbstractFurnaceBlockEntity furnace)) {
            return "没有找到包含该物品的箱子";
        }
        int collected = 0;
        ItemStack outStack = furnace.getStack(2);
        if (!outStack.isEmpty()) {
            collected = outStack.getCount();
            ItemStack left = gf.addToInventory(outStack.copy());
            int taken = collected - left.getCount();
            outStack.decrement(taken);
            furnace.markDirty();
        }

        SimpleInventory inv = gf.getInventory();
        int fuelAdded = 0;
        ItemStack fuelSlot = furnace.getStack(1);
        if (fuelSlot.isEmpty() || fuelSlot.getCount() < 16) {
            for (int i = 0; i < inv.size(); i++) {
                ItemStack s = inv.getStack(i);
                if (isFuelItem(world, s)) {
                    int qty = Math.min(s.getCount(), 16 - fuelSlot.getCount());
                    ItemStack put = s.split(qty);
                    if (fuelSlot.isEmpty()) {
                        furnace.setStack(1, put);
                    } else {
                        fuelSlot.increment(put.getCount());
                    }
                    fuelAdded += put.getCount();
                    furnace.markDirty();
                    break;
                }
            }
        }

        int smeltAdded = 0;
        ItemStack inputSlot = furnace.getStack(0);
        if (inputSlot.isEmpty() || inputSlot.getCount() < 16) {
            for (int i = 0; i < inv.size(); i++) {
                ItemStack s = inv.getStack(i);
                if (s.isEmpty() || isFuelItem(world, s)) continue;
                SingleStackRecipeInput recipeInput = new SingleStackRecipeInput(s);
                Optional<RecipeEntry<SmeltingRecipe>> match = world.getRecipeManager()
                        .getFirstMatch(RecipeType.SMELTING, recipeInput, world);
                if (match.isPresent()) {
                    int qty = Math.min(s.getCount(), 16 - inputSlot.getCount());
                    ItemStack put = s.split(qty);
                    if (inputSlot.isEmpty()) {
                        furnace.setStack(0, put);
                    } else if (ItemStack.areItemsAndComponentsEqual(inputSlot, put)) {
                        inputSlot.increment(put.getCount());
                    }
                    smeltAdded += put.getCount();
                    furnace.markDirty();
                    break;
                }
            }
        }

        return String.format("成功存入低价值杂物 %d 种共 %d 件，背包剩余空位 %d 格",
                collected, fuelAdded, smeltAdded);
    }
}
