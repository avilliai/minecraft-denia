package xyz.apollodorus.mcgf.entity.goal;

import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.config.GirlfriendConfig;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.util.Collections;
import java.util.EnumSet;
import java.util.function.Predicate;

/**
 * 慵懒少女的打盹行为：设了 home 之后，空闲时她会去 home 附近找一张床躺下睡一会儿。这并不是空闲时的唯一事件
 * （还有闲逛、捡东西、看箱子等），但夜里 + 空闲时触发概率更高，白天偶尔也会打盹——符合她爱犯困、爱摸鱼的性格。
 *
 * <p>优先级低于战斗 / 冲向受击玩家 / 带路 / 跟随（被攻击或玩家走动会自动把她唤醒去做更要紧的事），高于普通闲逛。
 * 只在她已经待在 home 附近时考虑，免得她抛下远处的玩家独自跑回家睡觉。
 */
public class SleepAtHomeGoal extends Goal {
    private static final Predicate<BlockState> IS_BED = st -> st.isIn(BlockTags.BEDS);
    private static final int BED_SEARCH = 16;          // 在 home 周围这个半径找床
    private static final double NEAR_HOME_SQ = 28.0 * 28.0; // 她得先待在 home 附近才考虑睡
    private static final double ARRIVE_SQ = 2.4 * 2.4;  // 走到床边这么近就躺下

    private final GirlfriendEntity gf;
    private BlockPos bed;
    private boolean asleep;
    private int repathCd;
    private int scanCd;
    private long wakeTime;

    public SleepAtHomeGoal(GirlfriendEntity gf) {
        this.gf = gf;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK, Control.JUMP));
    }

    @Override
    public boolean canStart() {
        if (!(gf.getEntityWorld() instanceof ServerWorld sw)) return false;
        if (gf.getTarget() != null || gf.getTask() != null || gf.isGuiding() || gf.isLeadingChest()) return false;
        BlockPos home = gf.getHomePos();
        if (home == null) return false;
        // 跟随中只有玩家停下时才考虑（别丢下走动的玩家去睡）。
        if (gf.isFollowing() && !gf.isOwnerStationary()) return false;
        if (home.getSquaredDistance(gf.getBlockPos()) > NEAR_HOME_SQ) return false;

        if (--scanCd > 0) return false;
        scanCd = 20;                                     // 限制扫描频率（更勤一点，更容易触发）
        // 夜里很想睡，白天也常犯困（慵懒）。
        boolean night = isNight(sw);
        int chanceDenom = night ? 3 : 12;
        if (gf.getRandom().nextInt(chanceDenom) != 0) return false;

        this.bed = findBed(sw, home);
        return bed != null;
    }

    @Override
    public boolean shouldContinue() {
        if (bed == null || !(gf.getEntityWorld() instanceof ServerWorld sw)) return false;
        if (gf.getTarget() != null || gf.getTask() != null || gf.isGuiding() || gf.isLeadingChest()) return false;
        // 只有在玩家距离很远（>50格）或者有明确攻击时才醒来，否则让她安心睡觉
        if (gf.isFollowing()) {
            var owner = gf.getOwner();
            if (owner != null && gf.squaredDistanceTo(owner) > 50.0 * 50.0) return false;
        }
        if (!IS_BED.test(sw.getBlockState(bed))) return false;             // 床被拆了
        if (asleep && sw.getTime() >= wakeTime) return false;              // 睡够了，自然醒
        return true;
    }

    @Override
    public void start() {
        this.asleep = false;
        this.repathCd = 0;
    }

    @Override
    public void stop() {
        if (asleep) gf.wakeUp();
        this.asleep = false;
        this.bed = null;
        gf.getNavigation().stop();
        if (gf.getTask() == null) gf.setActivity("闲着");
    }

    @Override
    public void tick() {
        if (bed == null) return;
        if (asleep) return; // 睡着后什么都不做：别让 LookControl 每 tick 拨她的头（会和躺姿打架、看起来在抽搐）
        Vec3d center = Vec3d.ofCenter(bed);
        gf.getLookControl().lookAt(center);

        if (gf.squaredDistanceTo(center) <= ARRIVE_SQ) {
            gf.getNavigation().stop();
            gf.sleep(bed);
            asleep = true;
            long extra = isNight(gf.getEntityWorld()) ? 200 + gf.getRandom().nextInt(400) : 100 + gf.getRandom().nextInt(200);
            wakeTime = gf.getEntityWorld().getTime() + extra;   // 睡 5~30s（夜里更久）
            gf.setActivity("在家里打个盹");
            return;
        }
        if (--repathCd <= 0) {
            repathCd = 12;
            gf.getNavigation().startMovingTo(center.x, center.y, center.z,
                ConfigManager.get().behavior.moveSpeed * 0.9);   // 0.9 才有正常迈腿动画（太慢会"太空步"）
        }
    }

    private BlockPos findBed(ServerWorld sw, BlockPos home) {
        return xyz.apollodorus.mcgf.entity.work.WorkUtil.findNearestBlock(
            sw, home, BED_SEARCH, IS_BED, Collections.emptySet());
    }

    private static boolean isNight(World w) {
        long tod = w.getTimeOfDay() % 24000L;
        return tod > 12500L && tod < 23500L;
    }
}
