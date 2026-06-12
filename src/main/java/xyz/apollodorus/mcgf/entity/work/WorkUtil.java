package xyz.apollodorus.mcgf.entity.work;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Stateless helpers shared by the work goals: classifying blocks, scanning the
 * world for the nearest matching block, picking + equipping the right tool, and
 * breaking blocks (with crop replanting). Ports the gist of the original
 * lib/oreMining.js, lib/toolSelect.js, lib/itemResolve.js and lib/digBlock.js.
 */
public final class WorkUtil {
    private WorkUtil() {}

    public enum ToolKind { PICKAXE, AXE, SHOVEL, HOE, SWORD, NONE }

    /** Ores worth mining — drops require a pickaxe of sufficient tier. */
    public static final Set<Block> ORES = Set.of(
        Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
        Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
        Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
        Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE,
        Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
        Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
        Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
        Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
        Blocks.NETHER_QUARTZ_ORE, Blocks.ANCIENT_DEBRIS
    );

    // --- block classification ---

    public static boolean isOre(BlockState st) {
        return ORES.contains(st.getBlock());
    }

    public static boolean isLog(BlockState st) {
        return st.isIn(BlockTags.LOGS);
    }

    public static boolean isMatureCrop(BlockState st) {
        return st.getBlock() instanceof CropBlock crop && crop.isMature(st);
    }

    /**
     * Is at least one face of this block open to air/water/grass? Buried-solid
     * blocks return false so she never X-ray-mines ore through stone — if she
     * can't see it, she leaves it alone (imperfect on purpose).
     */
    public static boolean isExposed(World world, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockState n = world.getBlockState(pos.offset(d));
            if (n.isAir() || n.isReplaceable()) return true;
        }
        return false;
    }

    public static ToolKind toolFor(BlockState st) {
        if (isLog(st)) return ToolKind.AXE;
        if (isMatureCrop(st)) return ToolKind.HOE;
        if (st.isIn(BlockTags.PICKAXE_MINEABLE)) return ToolKind.PICKAXE;
        if (st.isIn(BlockTags.AXE_MINEABLE)) return ToolKind.AXE;
        if (st.isIn(BlockTags.SHOVEL_MINEABLE)) return ToolKind.SHOVEL;
        return ToolKind.NONE;
    }

    // --- world scanning ---

    /**
     * Nearest block within a box around {@code center} matching {@code test}.
     * Vertical reach is clamped so big horizontal radii stay affordable.
     */
    public static BlockPos findNearestBlock(World world, BlockPos center, int radius,
                                            Predicate<BlockState> test, Set<BlockPos> exclude) {
        int ry = Math.min(radius, 8);
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -ry; dy <= ry; dy++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (exclude != null && exclude.contains(cursor)) continue;
                    BlockState st = world.getBlockState(cursor);
                    if (st.isAir() || !test.test(st)) continue;
                    double sq = center.getSquaredDistance(cursor);
                    if (sq < bestSq) {
                        bestSq = sq;
                        best = cursor.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    // --- tools ---

    /**
     * Equip the best in-inventory tool of the requested kind to the main hand,
     * stashing whatever was there back into the inventory. If she's already holding
     * a tool of this kind, she only swaps when the backpack holds a STRICTLY higher
     * tier (so a wooden pickaxe is auto-upgraded to a diamond one the moment she gets
     * it, but she won't churn between equal-tier tools). Returns true if a suitable
     * tool is now held (or NONE was requested).
     */
    public static boolean equipTool(GirlfriendEntity gf, ToolKind kind) {
        if (kind == ToolKind.NONE) return true;
        SimpleInventory inv = gf.getInventory();

        ItemStack held = gf.getEquippedStack(EquipmentSlot.MAINHAND);
        boolean heldMatches = !held.isEmpty() && matchesKind(held.getItem(), kind);
        int heldRank = heldMatches ? tierRank(held.getItem()) : -1;

        // Best-tier matching tool sitting in the backpack.
        int bestSlot = -1, bestRank = -1;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || !matchesKind(s.getItem(), kind)) continue;
            int rank = tierRank(s.getItem());
            if (rank > bestRank) {
                bestRank = rank;
                bestSlot = i;
            }
        }

        // Already holding the right kind and nothing strictly better is stashed → keep it.
        if (heldMatches && bestRank <= heldRank) return true;
        if (bestSlot < 0) return heldMatches; // nothing better (or no matching tool at all)

        // Equip the better tool, returning whatever was in hand to the backpack.
        ItemStack tool = inv.removeStack(bestSlot);
        gf.equipStack(EquipmentSlot.MAINHAND, tool);
        if (!held.isEmpty()) inv.addStack(held);
        return true;
    }

    /**
     * Can the tool she's currently holding actually harvest (yield drops from) this block?
     * False when the block requires a tool she isn't holding or that's too low-tier — e.g. a
     * stone pickaxe on diamond ore — so callers can avoid a useless drop-less grind.
     */
    public static boolean heldCanHarvest(GirlfriendEntity gf, BlockState st) {
        if (!st.isToolRequired()) return true;
        ItemStack tool = gf.getEquippedStack(EquipmentSlot.MAINHAND);
        return !tool.isEmpty() && tool.isSuitableFor(st);
    }

    /** Does she own a tool of this kind (in hand or inventory)? */
    public static boolean hasTool(GirlfriendEntity gf, ToolKind kind) {
        if (kind == ToolKind.NONE) return true;
        if (matchesKind(gf.getEquippedStack(EquipmentSlot.MAINHAND).getItem(), kind)
            && !gf.getEquippedStack(EquipmentSlot.MAINHAND).isEmpty()) return true;
        SimpleInventory inv = gf.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && matchesKind(s.getItem(), kind)) return true;
        }
        return false;
    }

    private static boolean matchesKind(Item item, ToolKind kind) {
        String p = path(item);
        return switch (kind) {
            case PICKAXE -> p.endsWith("_pickaxe");
            case AXE -> p.endsWith("_axe");
            case SHOVEL -> p.endsWith("_shovel");
            case HOE -> p.endsWith("_hoe");
            case SWORD -> p.endsWith("_sword");
            case NONE -> true;
        };
    }

    private static int tierRank(Item item) {
        String p = path(item);
        if (p.startsWith("netherite_")) return 5;
        if (p.startsWith("diamond_")) return 4;
        if (p.startsWith("iron_")) return 3;
        if (p.startsWith("stone_")) return 2;
        if (p.startsWith("golden_")) return 1;
        return 0; // wooden / other
    }

    // --- breaking & replanting ---

    /**
     * Per-tick mining progress (0..1) for the block at {@code pos}, matching vanilla
     * player timing: derived from block hardness and the tool she's holding. Returns
     * 0 for unbreakable blocks (bedrock etc.) and 1 for instant-break blocks (crops,
     * torches). Accumulate this each tick and break once the running sum reaches 1.
     */
    public static float breakDelta(ServerWorld world, GirlfriendEntity gf, BlockPos pos) {
        BlockState st = world.getBlockState(pos);
        float hardness = st.getHardness(world, pos);
        if (hardness < 0f) return 0f;      // unbreakable
        if (hardness == 0f) return 1f;     // instant (crops / torches / etc.)
        ItemStack tool = gf.getEquippedStack(EquipmentSlot.MAINHAND);
        float speed = tool.isEmpty() ? 1.0f : tool.getMiningSpeedMultiplier(st);
        boolean canHarvest = !st.isToolRequired() || tool.isSuitableFor(st);
        return speed / hardness / (canHarvest ? 30f : 100f);
    }

    /** Break the block, dropping items, and replant the same crop if she has seed. */
    public static void breakBlock(ServerWorld world, GirlfriendEntity gf, BlockPos pos) {
        BlockState before = world.getBlockState(pos);
        Item seed = before.getBlock() instanceof CropBlock ? cropSeed(before.getBlock()) : null;

        gf.swingHand(Hand.MAIN_HAND);
        world.breakBlock(pos, true, gf);

        if (seed != null && removeOne(gf.getInventory(), seed)) {
            world.setBlockState(pos, before.getBlock().getDefaultState());
        }
    }

    private static Item cropSeed(Block crop) {
        if (crop == Blocks.WHEAT) return Items.WHEAT_SEEDS;
        if (crop == Blocks.CARROTS) return Items.CARROT;
        if (crop == Blocks.POTATOES) return Items.POTATO;
        if (crop == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
        return null;
    }

    private static boolean removeOne(SimpleInventory inv, Item item) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && s.isOf(item)) {
                s.decrement(1);
                if (s.isEmpty()) inv.setStack(i, ItemStack.EMPTY);
                return true;
            }
        }
        return false;
    }

    /** Suck up loose drops within {@code radius} into the companion inventory; returns total absorbed. */
    public static int vacuumDrops(ServerWorld world, GirlfriendEntity gf, double radius) {
        Box box = gf.getBoundingBox().expand(radius);
        List<Entity> drops = world.getOtherEntities(gf, box, e -> e instanceof ItemEntity && e.isAlive());
        int total = 0;
        for (Entity e : drops) {
            ItemEntity ie = (ItemEntity) e;
            ItemStack st = ie.getStack();
            if (st.isEmpty()) continue;
            int before = st.getCount();
            ItemStack remainder = gf.addToInventory(st.copy());
            int taken = before - (remainder.isEmpty() ? 0 : remainder.getCount());
            if (taken <= 0) continue;
            gf.noteCollected(st.getItem(), taken);
            total += taken;
            if (remainder.isEmpty()) ie.discard();
            else ie.setStack(remainder);
        }
        return total;
    }

    // --- item name resolution (中文俗称 / english id) ---

    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("木头", "oak_log"), Map.entry("原木", "oak_log"), Map.entry("木材", "oak_log"),
        Map.entry("橡木", "oak_log"), Map.entry("煤", "coal"), Map.entry("煤炭", "coal"),
        Map.entry("铁", "raw_iron"), Map.entry("铁矿", "raw_iron"), Map.entry("铁锭", "iron_ingot"),
        Map.entry("金", "raw_gold"), Map.entry("金矿", "raw_gold"), Map.entry("金锭", "gold_ingot"),
        Map.entry("铜", "raw_copper"), Map.entry("钻石", "diamond"), Map.entry("绿宝石", "emerald"),
        Map.entry("红石", "redstone"), Map.entry("青金石", "lapis_lazuli"), Map.entry("青金", "lapis_lazuli"),
        Map.entry("石头", "cobblestone"), Map.entry("圆石", "cobblestone"), Map.entry("泥土", "dirt"),
        Map.entry("深层圆石", "cobbled_deepslate"), Map.entry("草方块", "grass_block"),
        Map.entry("沙子", "sand"), Map.entry("沙砾", "gravel"), Map.entry("砂砾", "gravel"), Map.entry("燧石", "flint"),
        Map.entry("闪长岩", "diorite"), Map.entry("安山岩", "andesite"), Map.entry("花岗岩", "granite"),
        Map.entry("深板岩", "deepslate"), Map.entry("小麦", "wheat"), Map.entry("胡萝卜", "carrot"),
        Map.entry("土豆", "potato"), Map.entry("马铃薯", "potato"), Map.entry("甜菜根", "beetroot"),
        Map.entry("火把", "torch"), Map.entry("弓", "bow"), Map.entry("箭", "arrow"),
        Map.entry("剑", "iron_sword"), Map.entry("镐", "iron_pickaxe"), Map.entry("镐子", "iron_pickaxe"),
        Map.entry("斧", "iron_axe"), Map.entry("斧头", "iron_axe"),
        // English material aliases — the LLM (or player) may say either language.
        Map.entry("iron", "raw_iron"), Map.entry("gold", "raw_gold"), Map.entry("copper", "raw_copper"),
        Map.entry("coal", "coal"), Map.entry("diamond", "diamond"), Map.entry("emerald", "emerald"),
        Map.entry("redstone", "redstone"), Map.entry("lapis", "lapis_lazuli"), Map.entry("quartz", "quartz"),
        Map.entry("wood", "oak_log"), Map.entry("log", "oak_log"), Map.entry("logs", "oak_log"),
        Map.entry("stone", "cobblestone"), Map.entry("wheat", "wheat"), Map.entry("carrot", "carrot"),
        Map.entry("potato", "potato"), Map.entry("beetroot", "beetroot"),
        // "ore"-named words (中/英) resolve to the RAW material so she goes and MINES the ore,
        // not a crafted block — e.g. "铁/iron/铁矿石" → 去挖铁矿, never 铁块.
        Map.entry("铁矿石", "raw_iron"), Map.entry("金矿石", "raw_gold"), Map.entry("铜矿石", "raw_copper"),
        Map.entry("煤矿", "coal"), Map.entry("煤矿石", "coal"), Map.entry("钻石矿", "diamond"),
        Map.entry("绿宝石矿", "emerald"), Map.entry("红石矿", "redstone"), Map.entry("青金石矿", "lapis_lazuli"),
        Map.entry("iron_ore", "raw_iron"), Map.entry("gold_ore", "raw_gold"), Map.entry("copper_ore", "raw_copper"),
        Map.entry("coal_ore", "coal"), Map.entry("diamond_ore", "diamond"),
        Map.entry("emerald_ore", "emerald"), Map.entry("redstone_ore", "redstone"), Map.entry("lapis_ore", "lapis_lazuli")
    );

    /** Resolve a free-text item name (中/英) to a vanilla Item, or null. */
    public static Item resolveItem(String name) {
        if (name == null) return null;
        String key = name.trim().toLowerCase().replace(' ', '_').replace("minecraft:", "");
        if (key.isEmpty()) return null;
        String mapped = ALIASES.getOrDefault(name.trim(), ALIASES.getOrDefault(key, key));
        Item item = Registries.ITEM.get(Identifier.of("minecraft", mapped));
        return item == Items.AIR ? null : item;
    }

    public static String displayName(Item item) {
        return path(item);
    }

    private static String path(Item item) {
        return Registries.ITEM.getId(item).getPath();
    }
}
