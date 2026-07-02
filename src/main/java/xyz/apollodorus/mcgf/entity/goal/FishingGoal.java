package xyz.apollodorus.mcgf.entity.goal;

import net.minecraft.block.BlockState;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.FluidTags;
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
import java.util.function.Predicate;

/**
 * 她「自己的事」之一：钓鱼。空闲、开了顺手采集、背包里有鱼竿、附近有开阔水域、且（跟随时）主人在近旁逗留——
 * 或 AI 用 {@code go_fishing} 让她就近开工——她就走到水边、握上鱼竿，慵懒地钓上几竿。
 *
 * <p>这是<strong>模拟</strong>钓鱼：不生成真实浮漂实体（那需要一整套使用-tick 流程、还会和她的寻路/浮空打架），
 * 而是面水站定、随机等一会儿「上钩」，把鱼/战利品直接收进背包，配合水花粒子与挥手动作。被战斗/冲向受击主人
 * 等更高优先级目标抢占时，{@link #stop()} 会<strong>无条件把鱼竿摘回背包</strong>，免得她握着鱼竿去打架。
 *
 * <p>结构参考 {@link PerceiveChestGoal}（走过去 + 卡住放弃）与 {@link SleepAtHomeGoal}（近距离门控）。
 */
public class FishingGoal extends Goal {
    private static final double CAST_RANGE_SQ = 12.0;        // 走到离水约 3.5 格即可开钓
    private static final double RECALL_SQ = 20.0 * 20.0;     // 跟随时主人走出这么远就收手（对齐 WorkGoal 的 IDLE_ABORT）
    private static final int SELF_BUSY_TICKS = 60;           // 每 tick 续 3s 承诺窗口（有界，停后自动失效）

    private static final Predicate<BlockState> IS_WATER = st -> st.getFluidState().isIn(FluidTags.WATER);

    private final GirlfriendEntity gf;
    private BlockPos water;        // 目标水面方块
    private int state;             // 0=走向水边, 1=钓鱼中
    private int repathCd;
    private int stuckTicks;
    private int scanCd;
    private int castTimer;         // 距离这一竿「上钩」还剩的刻
    private int casts;             // 本次已钓上来的次数
    private boolean idleRemarked;  // 这一竿是否已经因为「等太久」自言自语过

    public FishingGoal(GirlfriendEntity gf) {
        this.gf = gf;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (!(gf.getEntityWorld() instanceof ServerWorld sw)) return false;
        if (!ready()) return false;
        if (gf.isFollowing() && !gf.isOwnerLoitering() && !gf.isFreeRoam()) return false; // 别丢下走动的主人
        if (--scanCd > 0) return false;
        scanCd = 20;                                          // 限制水域扫描频率（~1/s）
        this.water = findWater(sw);
        return water != null;
    }

    @Override
    public boolean shouldContinue() {
        if (!(gf.getEntityWorld() instanceof ServerWorld sw)) return false;
        if (!ready() || water == null) return false;
        if (casts >= Math.max(1, ConfigManager.get().behavior.fishMaxCastsPerSession)) return false;
        // 跟随模式下主人走远就收手（待命/自由模式则安心钓——她本就被允许留在原地）。
        PlayerEntity owner = gf.getOwner();
        if (gf.isFollowing() && owner != null && gf.squaredDistanceTo(owner) > RECALL_SQ) return false;
        if (!IS_WATER.test(sw.getBlockState(water))) return false; // 水没了（被填/抽干）
        return true;
    }

    /** Shared gate: not fighting / commanded / sleeping / leading / guiding / rushing, feature on, owns a rod. */
    private boolean ready() {
        if (gf.getTarget() != null || gf.getTask() != null) return false;
        if (gf.isSleeping() || gf.isLeadingChest() || gf.isGuiding() || gf.isRushingToOwner()) return false;
        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        if (!b.autoFish) return false;
        if (!gf.isGatherEnabled() && !gf.isFreeRoam()) return false; // AI 指令（freeRoam）可越过顺手采集总开关
        return hasRod();
    }

    /**
     * Does she have a fishing rod at all — counting the one in her HAND, not just the backpack. The
     * backpack-only {@link GirlfriendEntity#countItem} reads 0 once {@link #equipRod} moves the rod to her
     * main hand, which (before this) made {@code ready()} flip false every tick → equip/holster thrashing.
     */
    private boolean hasRod() {
        return gf.countItem(Items.FISHING_ROD) > 0
            || gf.getEquippedStack(EquipmentSlot.MAINHAND).isOf(Items.FISHING_ROD);
    }

    @Override
    public void start() {
        this.state = 0;
        this.repathCd = 0;
        this.stuckTicks = 0;
        this.casts = 0;
        equipRod();
        gf.markSelfBusy(SELF_BUSY_TICKS);
        gf.setActivity("钓鱼");
        if (gf.getRandom().nextInt(100) < 50 && MCGirlfriendMod.BRAIN != null) {
            xyz.apollodorus.mcgf.ai.MoodManager.tryEventProactive(gf,
                GirlfriendConfig.pickOne(ConfigManager.get().prompts.fishingStart));
        }
    }

    @Override
    public void stop() {
        holsterRod();                 // 关键：摘回鱼竿，让泡泡杖/战斗武器逻辑接管（别握着竿去打架）
        gf.getNavigation().stop();
        this.water = null;
        this.state = 0;
        if (gf.getTask() == null && !gf.isSleeping()) gf.setActivity("闲着");
        // 不立即 clearSelfBusy：让承诺窗口自然失效，避免瞬间 stop→restart 把跟随抖醒。
    }

    @Override
    public void tick() {
        if (water == null || !(gf.getEntityWorld() instanceof ServerWorld sw)) return;
        gf.markSelfBusy(SELF_BUSY_TICKS);
        Vec3d center = Vec3d.ofCenter(water);
        gf.getLookControl().lookAt(center);

        if (state == 0) {             // 走向水边
            if (gf.squaredDistanceTo(center) <= CAST_RANGE_SQ) {
                gf.getNavigation().stop();
                beginCast(sw);
                return;
            }
            if (--repathCd <= 0) {
                repathCd = 10;
                boolean ok = gf.getNavigation().startMovingTo(center.x, center.y, center.z,
                    ConfigManager.get().behavior.moveSpeed * 0.9);   // 0.9 才有正常迈腿动画（太慢会"太空步"）
                if (!ok && ++stuckTicks > 6) { water = null; }   // 够不着 → 这次作罢，下次再找
            }
            if (gf.getNavigation().isIdle() && ++stuckTicks > 160) water = null;
            return;
        }

        // state == 1：钓鱼中
        if (swingMaybe(sw)) gf.swingHand(Hand.MAIN_HAND);
        bobberParticles(sw, center);
        // 等久了发会儿呆：这一竿等得久时，偶尔自言自语一句（受事件冷却约束，不会刷屏）。
        if (!idleRemarked && castTimer < ConfigManager.get().behavior.fishMinCastTicks
                && gf.getRandom().nextInt(400) == 0 && MCGirlfriendMod.BRAIN != null) {
            idleRemarked = true;
            xyz.apollodorus.mcgf.ai.MoodManager.tryEventProactive(gf,
                GirlfriendConfig.pickOne(ConfigManager.get().prompts.fishingIdle));
        }
        if (--castTimer > 0) return;

        reelIn(sw);                   // 上钩 → 收线得鱼
        casts++;
        if (casts < Math.max(1, ConfigManager.get().behavior.fishMaxCastsPerSession)) {
            armCastTimer();           // 再钓一竿
        }
    }

    // --- 钓鱼细节 ---

    private void beginCast(ServerWorld sw) {
        state = 1;
        armCastTimer();
        gf.swingHand(Hand.MAIN_HAND);
    }

    private void armCastTimer() {
        GirlfriendConfig.Behavior b = ConfigManager.get().behavior;
        int min = Math.max(40, b.fishMinCastTicks);
        int max = Math.max(min + 20, b.fishMaxCastTicks);
        castTimer = min + gf.getRandom().nextInt(max - min);
        idleRemarked = false;
    }

    private void reelIn(ServerWorld sw) {
        ItemStack catch_ = rollCatch();
        gf.addToInventory(catch_);
        sw.spawnParticles(ParticleTypes.SPLASH, water.getX() + 0.5, water.getY() + 1.0, water.getZ() + 0.5,
            12, 0.3, 0.1, 0.3, 0.1);
        gf.swingHand(Hand.MAIN_HAND);
        gf.addAffection(1);
        if (gf.getRandom().nextInt(100) < 40 && MCGirlfriendMod.BRAIN != null) {
            xyz.apollodorus.mcgf.ai.MoodManager.tryEventProactive(gf,
                GirlfriendConfig.pickOne(ConfigManager.get().prompts.fishingCatch));
        }
    }

    /** Weighted simulated loot — mostly common fish, rarer tropical / pufferfish. */
    private ItemStack rollCatch() {
        int r = gf.getRandom().nextInt(100);
        Item item = r < 58 ? Items.COD
            : r < 83 ? Items.SALMON
            : r < 93 ? Items.TROPICAL_FISH
            : r < 98 ? Items.PUFFERFISH
            : Items.KELP;
        return new ItemStack(item);
    }

    private boolean swingMaybe(ServerWorld sw) {
        return sw.getTime() % 18 == 0;   // 偶尔抖一下竿
    }

    private void bobberParticles(ServerWorld sw, Vec3d center) {
        if (sw.getTime() % 8 != 0) return;
        sw.spawnParticles(ParticleTypes.FISHING, center.x, water.getY() + 1.0, center.z, 1, 0.1, 0.0, 0.1, 0.0);
    }

    private BlockPos findWater(ServerWorld sw) {
        int radius = ConfigManager.get().behavior.idleGatherRadius;
        return WorkUtil.findNearestBlock(sw, gf.getBlockPos(), radius, IS_WATER, null);
    }

    // --- 鱼竿装备 / 收回 ---

    private void equipRod() {
        ItemStack held = gf.getEquippedStack(EquipmentSlot.MAINHAND);
        if (held.isOf(Items.FISHING_ROD)) return;
        SimpleInventory inv = gf.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isOf(Items.FISHING_ROD)) {
                ItemStack rod = inv.removeStack(i);
                gf.equipStack(EquipmentSlot.MAINHAND, rod);
                if (!held.isEmpty()) inv.addStack(held);
                return;
            }
        }
    }

    private void holsterRod() {
        ItemStack held = gf.getEquippedStack(EquipmentSlot.MAINHAND);
        if (held.isOf(Items.FISHING_ROD)) {
            gf.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            gf.getInventory().addStack(held);
        }
    }
}
