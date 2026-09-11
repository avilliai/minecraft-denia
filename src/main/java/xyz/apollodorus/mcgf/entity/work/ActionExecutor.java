package xyz.apollodorus.mcgf.entity.work;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.consume.UseAction;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import xyz.apollodorus.mcgf.ai.SpeechBus;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

public final class ActionExecutor {
    private ActionExecutor() {}

    public static boolean useItem(GirlfriendEntity gf, Item item) {
        if (gf == null || item == null) return false;
        int slot = -1;
        for (int i = 0; i < gf.getInventory().size(); i++) {
            if (gf.getInventory().getStack(i).isOf(item)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) return false;

        ItemStack st = gf.getInventory().getStack(slot);
        ServerWorld sw = (ServerWorld) gf.getEntityWorld();

        if (st.contains(DataComponentTypes.FOOD)) {
            gf.heal(6.0f);
            sw.playSound(null, gf.getX(), gf.getY(), gf.getZ(), SoundEvents.ENTITY_GENERIC_EAT, SoundCategory.NEUTRAL, 1.0f, 1.0f);
            sw.spawnParticles(ParticleTypes.HEART, gf.getX(), gf.getY() + 1.2, gf.getZ(), 5, 0.3, 0.3, 0.3, 0.05);
            gf.getInventory().removeStack(slot, 1);
            SpeechBus.speak(gf, "好美味~ 补充能量啦！");
            return true;
        }

        if (st.isOf(Items.POTION) || st.isOf(Items.SPLASH_POTION)) {
            gf.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 200, 1));
            gf.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 400, 1));
            sw.playSound(null, gf.getX(), gf.getY(), gf.getZ(), SoundEvents.ENTITY_GENERIC_DRINK, SoundCategory.NEUTRAL, 1.0f, 1.0f);
            sw.spawnParticles(ParticleTypes.HAPPY_VILLAGER, gf.getX(), gf.getY() + 1.0, gf.getZ(), 10, 0.3, 0.3, 0.3, 0.05);
            gf.getInventory().removeStack(slot, 1);
            SpeechBus.speak(gf, "药剂生效了，感觉变敏捷了！");
            return true;
        }

        if (st.isOf(Items.MILK_BUCKET)) {
            gf.clearStatusEffects();
            sw.playSound(null, gf.getX(), gf.getY(), gf.getZ(), SoundEvents.ENTITY_GENERIC_DRINK, SoundCategory.NEUTRAL, 1.0f, 1.0f);
            gf.getInventory().setStack(slot, new ItemStack(Items.BUCKET));
            SpeechBus.speak(gf, "呼……负面状态都消除了。");
            return true;
        }

        if (st.isOf(Items.WATER_BUCKET)) {
            BlockPos bp = gf.getBlockPos();
            if (sw.getBlockState(bp).isAir()) {
                sw.setBlockState(bp, Blocks.WATER.getDefaultState(), 3);
                gf.getInventory().setStack(slot, new ItemStack(Items.BUCKET));
                SpeechBus.speak(gf, "倒水灭火/阻挡！");
                return true;
            }
        }

        return false;
    }

    public static boolean placeBlockAt(GirlfriendEntity gf, BlockPos pos, Item item) {
        if (gf == null || pos == null || item == null) return false;
        if (!(item instanceof BlockItem bi)) return false;
        ServerWorld sw = (ServerWorld) gf.getEntityWorld();

        if (!sw.getBlockState(pos).isAir() && !sw.getBlockState(pos).isLiquid()) {
            return false;
        }

        int slot = -1;
        for (int i = 0; i < gf.getInventory().size(); i++) {
            if (gf.getInventory().getStack(i).isOf(item)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) return false;

        BlockState state = bi.getBlock().getDefaultState();
        sw.setBlockState(pos, state, 3);
        sw.playSound(null, pos, SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 1.0f, 0.8f);
        sw.spawnParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 6, 0.2, 0.2, 0.2, 0.02);
        gf.getInventory().removeStack(slot, 1);
        return true;
    }

    public static boolean scaffoldPillar(GirlfriendEntity gf, int height) {
        if (gf == null) return false;
        ServerWorld sw = (ServerWorld) gf.getEntityWorld();
        Item blockItem = null;
        for (int i = 0; i < gf.getInventory().size(); i++) {
            ItemStack st = gf.getInventory().getStack(i);
            if (!st.isEmpty() && st.getItem() instanceof BlockItem bi) {
                if (bi.getBlock().getDefaultState().isOpaque()) {
                    blockItem = st.getItem();
                    break;
                }
            }
        }
        if (blockItem == null) return false;

        BlockPos base = gf.getBlockPos();
        int built = 0;
        for (int i = 0; i < height; i++) {
            BlockPos target = base.up(i);
            if (placeBlockAt(gf, target, blockItem)) {
                built++;
            }
        }
        if (built > 0) {
            gf.requestTeleport(gf.getX(), base.getY() + built, gf.getZ());
            SpeechBus.speak(gf, "搭建好垫脚石了，视野真开阔！");
            return true;
        }
        return false;
    }

    public static boolean buildSimpleShelter(GirlfriendEntity gf) {
        if (gf == null) return false;
        ServerWorld sw = (ServerWorld) gf.getEntityWorld();
        Item blockItem = null;
        for (int i = 0; i < gf.getInventory().size(); i++) {
            ItemStack st = gf.getInventory().getStack(i);
            if (!st.isEmpty() && st.getItem() instanceof BlockItem bi) {
                if (bi.getBlock().getDefaultState().isOpaque() && st.getCount() >= 10) {
                    blockItem = st.getItem();
                    break;
                }
            }
        }
        if (blockItem == null) return false;

        BlockPos center = gf.getBlockPos();
        int placed = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = 0; dy <= 2; dy++) {
                    if (dx == 0 && dz == 1 && dy < 2) continue; // 留门
                    BlockPos p = center.add(dx, dy, dz);
                    if (placeBlockAt(gf, p, blockItem)) {
                        placed++;
                    }
                }
            }
        }
        // 屋顶
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos p = center.add(dx, 3, dz);
                if (placeBlockAt(gf, p, blockItem)) {
                    placed++;
                }
            }
        }
        if (placed > 0) {
            SpeechBus.speak(gf, "临时避难小棚搭建完成！");
            return true;
        }
        return false;
    }
}
