package xyz.apollodorus.mcgf.entity.goal;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import xyz.apollodorus.mcgf.MCGirlfriendMod;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.config.GirlfriendConfig;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;
import xyz.apollodorus.mcgf.entity.work.WorkUtil;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 她「自己的事」之一：打理自家菜地。设了家、背包里有种子、且（跟随时）主人在近旁逗留——或 AI 用
 * {@code tend_farm} 让她就近开工——她会在家附近找<strong>空的耕地</strong>（{@code FARMLAND} 且正上方为空气），
 * 一格格补种上她带着的作物。
 *
 * <p><strong>只负责「补种」</strong>：收割成熟作物仍交给 {@link WorkGoal} 的空闲采集（它会自动补种）。这样两个
 * Goal 不会抢同一块成熟作物（WorkGoal 优先级更高会一直抢赢、把 TendFarm 饿死）。她只在已有耕地上种非破坏性
 * 作物，所以<strong>豁免 homeProtectRadius</strong>（那是防破坏地形的，与种地无关）。
 *
 * <p>结构参考 {@link SleepAtHomeGoal}（近 home 门控）。
 */
public class TendFarmGoal extends Goal {
    private static final double NEAR_HOME_SQ = 26.0 * 26.0;  // 她得先待在家附近才打理菜地
    private static final double ARRIVE_SQ = 6.25;            // 走到耕地旁 ~2.5 格就能种
    private static final double RECALL_SQ = 20.0 * 20.0;
    private static final int SELF_BUSY_TICKS = 60;
    private static final int MAX_PLANTS = 8;                 // 一次最多补种几棵，免得没完没了

    /** 种子 → 它种下去长成的作物方块。 */
    private static final Map<Item, Block> SEED_CROP = new LinkedHashMap<>();
    static {
        SEED_CROP.put(Items.WHEAT_SEEDS, Blocks.WHEAT);
        SEED_CROP.put(Items.CARROT, Blocks.CARROTS);
        SEED_CROP.put(Items.POTATO, Blocks.POTATOES);
        SEED_CROP.put(Items.BEETROOT_SEEDS, Blocks.BEETROOTS);
    }

    private static final Predicate<BlockState> IS_FARMLAND = st -> st.isOf(Blocks.FARMLAND);

    private final GirlfriendEntity gf;
    private BlockPos plot;
    private int repathCd;
    private int stuckTicks;
    private int scanCd;
    private int planted;

    public TendFarmGoal(GirlfriendEntity gf) {
        this.gf = gf;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (!(gf.getEntityWorld() instanceof ServerWorld sw)) return false;
        if (!ready()) return false;
        if (!nearHome()) return false;
        if (gf.isFollowing() && !gf.isOwnerLoitering() && !gf.isFreeRoam()) return false;
        if (--scanCd > 0) return false;
        scanCd = 20;
        this.plot = findEmptyFarmland(sw);
        return plot != null;
    }

    @Override
    public boolean shouldContinue() {
        if (!(gf.getEntityWorld() instanceof ServerWorld)) return false;
        if (!ready() || !nearHome() || plot == null) return false;
        if (planted >= MAX_PLANTS) return false;
        PlayerEntity owner = gf.getOwner();
        if (gf.isFollowing() && owner != null && gf.squaredDistanceTo(owner) > RECALL_SQ) return false;
        return true;
    }

    /** Shared gate: not fighting / commanded / sleeping / leading / guiding / rushing, feature on, home + a seed. */
    private boolean ready() {
        if (gf.getTarget() != null || gf.getTask() != null) return false;
        if (gf.isSleeping() || gf.isLeadingChest() || gf.isGuiding() || gf.isRushingToOwner()) return false;
        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        if (!b.autoFarm) return false;
        if (!gf.isGatherEnabled() && !gf.isFreeRoam()) return false; // AI 指令（freeRoam）可越过顺手采集总开关
        if (gf.getHomePos() == null) return false;
        return ownedSeed() != null;
    }

    @Override
    public void start() {
        this.repathCd = 0;
        this.stuckTicks = 0;
        this.planted = 0;
        gf.markSelfBusy(SELF_BUSY_TICKS);
        gf.setActivity("种地");
        if (gf.getRandom().nextInt(100) < 50 && MCGirlfriendMod.BRAIN != null) {
            xyz.apollodorus.mcgf.ai.MoodManager.tryEventProactive(gf,
                GirlfriendConfig.pickOne(ConfigManager.get().prompts.farmingStart));
        }
    }

    @Override
    public void stop() {
        gf.getNavigation().stop();
        boolean did = planted > 0;
        this.plot = null;
        if (gf.getTask() == null && !gf.isSleeping()) gf.setActivity("闲着");
        if (did && gf.getRandom().nextInt(100) < 50 && MCGirlfriendMod.BRAIN != null) {
            xyz.apollodorus.mcgf.ai.MoodManager.tryEventProactive(gf,
                GirlfriendConfig.pickOne(ConfigManager.get().prompts.farmingDone));
        }
    }

    @Override
    public void tick() {
        if (plot == null || !(gf.getEntityWorld() instanceof ServerWorld sw)) return;
        gf.markSelfBusy(SELF_BUSY_TICKS);
        Vec3d center = Vec3d.ofCenter(plot);
        gf.getLookControl().lookAt(center);

        if (gf.squaredDistanceTo(center) > ARRIVE_SQ) {
            if (--repathCd <= 0) {
                repathCd = 10;
                boolean ok = gf.getNavigation().startMovingTo(center.x, center.y, center.z,
                    ConfigManager.get().behavior.moveSpeed * 0.9);   // 0.9 才有正常迈腿动画（太慢会"太空步"）
                if (!ok && ++stuckTicks > 6) plot = null;
            }
            if (gf.getNavigation().isIdle() && ++stuckTicks > 160) plot = null;
            return;
        }

        gf.getNavigation().stop();
        stuckTicks = 0;
        plant(sw);
        plot = findEmptyFarmland(sw);  // 继续找下一块，没有了 shouldContinue 会让它收尾
    }

    // --- 种植 ---

    private void plant(ServerWorld sw) {
        // 复核：到位时耕地仍空着才种（避免期间被填）。
        if (!IS_FARMLAND.test(sw.getBlockState(plot)) || !sw.getBlockState(plot.up()).isAir()) return;
        Item seed = ownedSeed();
        if (seed == null) return;
        Block crop = SEED_CROP.get(seed);
        sw.setBlockState(plot.up(), crop.getDefaultState());
        consumeSeed(seed);
        gf.swingHand(Hand.MAIN_HAND);
        planted++;
        gf.addAffection(1);
    }

    /** First seed type she actually carries, or null. */
    private Item ownedSeed() {
        for (Item seed : SEED_CROP.keySet()) {
            if (gf.countItem(seed) > 0) return seed;
        }
        return null;
    }

    private void consumeSeed(Item seed) {
        SimpleInventory inv = gf.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && s.isOf(seed)) {
                s.decrement(1);
                if (s.isEmpty()) inv.setStack(i, ItemStack.EMPTY);
                return;
            }
        }
    }

    private boolean nearHome() {
        BlockPos home = gf.getHomePos();
        return home != null && home.getSquaredDistance(gf.getBlockPos()) <= NEAR_HOME_SQ;
    }

    /** Nearest empty farmland (FARMLAND with air above) within idle radius; skips ones already topped. */
    private BlockPos findEmptyFarmland(ServerWorld sw) {
        int radius = ConfigManager.get().behavior.idleGatherRadius;
        Set<BlockPos> exclude = new HashSet<>();
        for (int tries = 0; tries < 8; tries++) {
            BlockPos p = WorkUtil.findNearestBlock(sw, gf.getBlockPos(), radius, IS_FARMLAND, exclude);
            if (p == null) return null;
            if (sw.getBlockState(p.up()).isAir()) return p;
            exclude.add(p);   // already has a crop / block on top → keep looking
        }
        return null;
    }
}
