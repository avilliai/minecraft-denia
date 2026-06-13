package xyz.apollodorus.mcgf.entity.goal;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.*;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import xyz.apollodorus.mcgf.ai.SpeechBus;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;
import xyz.apollodorus.mcgf.entity.work.WorkUtil;

import java.util.*;
import java.util.function.Predicate;

/**
 * 背包满时提醒玩家，并在玩家回到家附近时自动整理箱子。
 * 不会主动离开玩家回家，只在玩家本来就在家附近时整理。
 */
public class AutoStorageGoal extends Goal {
    private static final Predicate<BlockState> IS_CHEST = st ->
        st.isOf(Blocks.CHEST) || st.isOf(Blocks.BARREL) || st.isOf(Blocks.TRAPPED_CHEST);

    private final GirlfriendEntity gf;
    private BlockPos targetChest;
    private int repathCd;
    private boolean notifiedFull;
    private long lastNotifyTime;

    public AutoStorageGoal(GirlfriendEntity gf) {
        this.gf = gf;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (!(gf.getEntityWorld() instanceof ServerWorld)) return false;
        if (gf.getTarget() != null || gf.getTask() != null) return false;
        if (!ConfigManager.get().behavior.autoStorage) return false;
        if (gf.getHomePos() == null) return false;

        // 背包快满了（85%以上）
        if (!isInventoryNearlyFull()) {
            notifiedFull = false;
            return false;
        }

        // 离家较近（16格内）时才整理，否则只提醒
        double homeDist = Math.sqrt(gf.getHomePos().getSquaredDistance(gf.getBlockPos()));
        if (homeDist > 16) {
            if (!notifiedFull && gf.getEntityWorld().getTime() - lastNotifyTime > 600) {
                SpeechBus.speak(gf, "背包快满啦…回家时帮你整理一下吧~");
                notifiedFull = true;
                lastNotifyTime = gf.getEntityWorld().getTime();
            }
            return false;
        }

        notifiedFull = false;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        if (gf.getTarget() != null) return false;
        if (targetChest == null) return false;
        return hasItemsToStore();
    }

    @Override
    public void start() {
        this.repathCd = 0;
        findNextChest();
    }

    @Override
    public void tick() {
        if (targetChest == null) {
            findNextChest();
            if (targetChest == null) return;
        }

        Vec3d chestCenter = Vec3d.ofCenter(targetChest);
        gf.getLookControl().lookAt(chestCenter);

        if (gf.squaredDistanceTo(chestCenter) <= 9.0) {
            storeItemsInChest(targetChest);
            if (hasItemsToStore()) {
                findNextChest();
            } else {
                SpeechBus.speak(gf, "整理好啦~");
                targetChest = null;
            }
        } else {
            if (--repathCd <= 0) {
                repathCd = 15;
                gf.getNavigation().startMovingTo(chestCenter.x, chestCenter.y, chestCenter.z,
                    ConfigManager.get().behavior.moveSpeed * 0.7);
            }
        }
    }

    @Override
    public void stop() {
        targetChest = null;
        gf.getNavigation().stop();
    }

    private void findNextChest() {
        if (!(gf.getEntityWorld() instanceof ServerWorld sw)) return;
        BlockPos home = gf.getHomePos();
        if (home == null) return;
        targetChest = WorkUtil.findNearestBlock(sw, home, 32, IS_CHEST, Collections.emptySet());
    }

    private void storeItemsInChest(BlockPos chestPos) {
        if (!(gf.getEntityWorld() instanceof ServerWorld sw)) return;
        BlockEntity be = sw.getBlockEntity(chestPos);
        if (!(be instanceof Inventory chest)) return;

        var inv = gf.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty() || isImportantItem(stack)) continue;

            for (int j = 0; j < chest.size(); j++) {
                ItemStack chestStack = chest.getStack(j);
                if (chestStack.isEmpty()) {
                    chest.setStack(j, stack.copy());
                    inv.setStack(i, ItemStack.EMPTY);
                    chest.markDirty();
                    break;
                } else if (ItemStack.areItemsAndComponentsEqual(chestStack, stack)) {
                    int space = chestStack.getMaxCount() - chestStack.getCount();
                    if (space > 0) {
                        int transfer = Math.min(space, stack.getCount());
                        chestStack.increment(transfer);
                        stack.decrement(transfer);
                        chest.markDirty();
                        if (stack.isEmpty()) {
                            inv.setStack(i, ItemStack.EMPTY);
                            break;
                        }
                    }
                }
            }
        }
    }

    private boolean isInventoryNearlyFull() {
        var inv = gf.getInventory();
        int used = 0;
        for (int i = 0; i < inv.size(); i++) {
            if (!inv.getStack(i).isEmpty()) used++;
        }
        return used >= (int)(inv.size() * 0.85);
    }

    private boolean hasItemsToStore() {
        var inv = gf.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && !isImportantItem(stack)) {
                return true;
            }
        }
        return false;
    }

    private boolean isImportantItem(ItemStack stack) {
        Item item = stack.getItem();
        // 食物
        if (stack.get(DataComponentTypes.FOOD) != null) return true;
        // 武器和工具（检查是否有耐久度 - 大部分工具和武器都有）
        if (stack.get(DataComponentTypes.MAX_DAMAGE) != null && stack.getMaxDamage() > 0) return true;
        // 护甲
        if (stack.get(DataComponentTypes.EQUIPPABLE) != null) return true;
        return false;
    }
}
