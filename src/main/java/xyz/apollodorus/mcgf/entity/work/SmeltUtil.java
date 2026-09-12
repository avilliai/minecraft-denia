package xyz.apollodorus.mcgf.entity.work;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.FurnaceBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.ServerRecipeManager;
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import xyz.apollodorus.mcgf.ai.SpeechBus;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 达妮娅自主烧炼引擎：
 * 支持原版及任何模组的熔炼配方（SmeltingRecipe / AbstractCookingRecipe）。
 * 当需要目标物品（如铁锭、铜锭、熟食、玻璃等）时，自动识别原料、燃料并自主烧制。
 */
public final class SmeltUtil {
    private SmeltUtil() {}

    public record SmeltResult(boolean ok, int smelted, String missing) {}

    private static final Map<Item, Item> RAW_SOURCES = Map.ofEntries(
        Map.entry(Items.IRON_INGOT, Items.RAW_IRON),
        Map.entry(Items.GOLD_INGOT, Items.RAW_GOLD),
        Map.entry(Items.COPPER_INGOT, Items.RAW_COPPER),
        Map.entry(Items.GLASS, Items.SAND),
        Map.entry(Items.COOKED_BEEF, Items.BEEF),
        Map.entry(Items.COOKED_PORKCHOP, Items.PORKCHOP),
        Map.entry(Items.COOKED_MUTTON, Items.MUTTON),
        Map.entry(Items.COOKED_CHICKEN, Items.CHICKEN),
        Map.entry(Items.COOKED_SALMON, Items.SALMON),
        Map.entry(Items.COOKED_COD, Items.COD),
        Map.entry(Items.SMOOTH_STONE, Items.STONE)
    );

    public static boolean isSmeltableResult(Item item) {
        if (item == null) return false;
        if (RAW_SOURCES.containsKey(item)) return true;
        String name = item.toString().toLowerCase();
        return name.endsWith("_ingot") || name.startsWith("cooked_");
    }

    public static Item getRawSourceFor(Item item) {
        if (item == null) return null;
        Item known = RAW_SOURCES.get(item);
        if (known != null) return known;
        // 自动启发式探测粗矿
        String name = item.toString().toLowerCase();
        if (name.contains("iron")) return Items.RAW_IRON;
        if (name.contains("gold")) return Items.RAW_GOLD;
        if (name.contains("copper")) return Items.RAW_COPPER;
        return null;
    }

    public static boolean trySmeltInInventory(GirlfriendEntity gf, Item targetItem) {
        if (gf == null || targetItem == null) return false;
        SmeltResult sr = smeltItem(gf, targetItem, 64);
        return sr.ok();
    }


    private static volatile ServerRecipeManager cachedManager;
    private static volatile Map<Item, AbstractCookingRecipe> bySmeltOutput;

    public static void ensureSmeltCache(ServerRecipeManager rm, ServerWorld sw) {
        if (bySmeltOutput != null && cachedManager == rm) return;
        Map<Item, AbstractCookingRecipe> map = new HashMap<>();
        RegistryWrapper.WrapperLookup reg = sw.getRegistryManager();
        for (RecipeEntry<?> entry : rm.values()) {
            if (entry.value() instanceof AbstractCookingRecipe acr) {
                if (acr.getType() == RecipeType.SMELTING) {
                    ItemStack outStack = acr.craft(new SingleStackRecipeInput(ItemStack.EMPTY), reg);
                    if (!outStack.isEmpty()) {
                        map.putIfAbsent(outStack.getItem(), acr);
                    }
                }
            }
        }
        bySmeltOutput = map;
        cachedManager = rm;
    }

    /** 查询是否有产出 targetItem 的熔炼配方 */
    public static AbstractCookingRecipe findSmeltRecipe(ServerWorld sw, Item targetItem) {
        MinecraftServer server = sw.getServer();
        if (server == null) return null;
        ensureSmeltCache(server.getRecipeManager(), sw);
        return bySmeltOutput != null ? bySmeltOutput.get(targetItem) : null;
    }

    /**
     * 自主烧炼闭环：
     * 1. 检查背包是否有对应原料与燃料（煤炭/木炭/木板/原木）
     * 2. 达妮娅放置临时熔炉或就地借用共鸣异空间熔炼，将原料转化为成品
     */
    public static SmeltResult smeltItem(GirlfriendEntity gf, Item targetItem, int count) {
        if (!(gf.getEntityWorld() instanceof ServerWorld sw)) return new SmeltResult(false, 0, null);
        AbstractCookingRecipe recipe = findSmeltRecipe(sw, targetItem);
        if (recipe == null) return new SmeltResult(false, 0, "无可用熔炼配方");

        SimpleInventory inv = gf.getInventory();
        // 查找原料
        int rawSlot = -1;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && recipe.matches(new SingleStackRecipeInput(stack), sw)) {
                rawSlot = i;
                break;
            }
        }

        if (rawSlot == -1) {
            return new SmeltResult(false, 0, "缺少熔炼原料");
        }

        // 查找燃料（煤炭、木炭、熔岩桶、木头、木板、木棍）
        int fuelSlot = findFuelSlot(inv);
        if (fuelSlot == -1) {
            // 尝试自动合成木板作为应急燃料
            CraftUtil.craft(gf, Items.OAK_PLANKS, 2);
            fuelSlot = findFuelSlot(inv);
            if (fuelSlot == -1) {
                return new SmeltResult(false, 0, "缺少燃料(煤炭/木材)");
            }
        }

        // 检查熔炉
        int furnaceCount = gf.countItem(Items.FURNACE);
        if (furnaceCount == 0) {
            // 尝试合成熔炉（8个圆石）
            CraftUtil.Result cr = CraftUtil.craft(gf, Items.FURNACE, 1);
            if (!cr.ok()) {
                return new SmeltResult(false, 0, "缺少圆石制作熔炉");
            }
        }

        // 执行烧炼：消耗原料与燃料，产出成品
        ItemStack rawStack = inv.getStack(rawSlot);
        ItemStack fuelStack = inv.getStack(fuelSlot);
        int canSmelt = Math.min(count, rawStack.getCount());

        rawStack.decrement(canSmelt);
        if (rawStack.isEmpty()) inv.setStack(rawSlot, ItemStack.EMPTY);

        // 燃料按比例消耗
        fuelStack.decrement(Math.max(1, (canSmelt + 3) / 4));
        if (fuelStack.isEmpty()) inv.setStack(fuelSlot, ItemStack.EMPTY);

        // 产出成品
        ItemStack resultStack = new ItemStack(targetItem, canSmelt);
        ItemStack overflow = inv.addStack(resultStack);
        if (!overflow.isEmpty()) {
            ItemEntity ie = new ItemEntity(sw, gf.getX(), gf.getY(), gf.getZ(), overflow);
            sw.spawnEntity(ie);
        }

        // 播放达妮娅灵动的熔炼特效与动作
        gf.swingHand(Hand.MAIN_HAND);
        sw.playSound(null, gf.getX(), gf.getY(), gf.getZ(),
                SoundEvents.BLOCK_FURNACE_FIRE_CRACKLE, SoundCategory.BLOCKS, 1.0f, 1.0f);
        sw.spawnParticles(ParticleTypes.FLAME, gf.getX(), gf.getY() + 0.8, gf.getZ(), 12, 0.3, 0.3, 0.3, 0.02);
        sw.spawnParticles(ParticleTypes.SMOKE, gf.getX(), gf.getY() + 1.0, gf.getZ(), 8, 0.2, 0.2, 0.2, 0.01);

        SpeechBus.speak(gf, "炉火烧好啦~ 喏，这是刚出炉的「" + WorkUtil.displayName(targetItem) + "」！");
        return new SmeltResult(true, canSmelt, null);
    }

    private static int findFuelSlot(SimpleInventory inv) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            Item item = s.getItem();
            if (item == Items.COAL || item == Items.CHARCOAL || item == Items.LAVA_BUCKET ||
                item.toString().contains("planks") || item.toString().contains("log") || item == Items.STICK) {
                return i;
            }
        }
        return -1;
    }
}
