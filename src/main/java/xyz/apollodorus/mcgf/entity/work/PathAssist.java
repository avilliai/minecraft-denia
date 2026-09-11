package xyz.apollodorus.mcgf.entity.work;

import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import xyz.apollodorus.mcgf.combat.AbilityManager;
import xyz.apollodorus.mcgf.config.GirlfriendConfig;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

/**
 * Owner-only terrain assist: when she genuinely can't path to her owner — a wall, a
 * one-block gap, the owner standing above her — she may break the obstruction, bridge the
 * gap, or pillar up. It is capped per stuck episode. Near home she will NOT break terrain
 * (no holes for mobs), but she may still conjure transient (auto-restoring) void matter to
 * bridge or pillar, so she can still climb to an owner standing above her right by the base.
 *
 * <p>This is deliberately NOT used for chasing mobs (see {@code ProtectOwnerGoal}):
 * she stays honest about what she can reach and only ever digs/bridges to rejoin her
 * person — the same "no X-ray, no cheating" rule the work goals follow for buried ore.
 *
 * <p>One instance per goal (Follow / Rush). The goal calls {@link #tick} while stuck
 * and {@link #reset} the moment it has a real path again, so the cap measures a single
 * episode rather than leaking across the whole journey.
 */
public final class PathAssist {
    private BlockPos breaking;   // obstacle currently being chipped
    private float progress;      // 0..1 break accumulator for `breaking`
    private int blocksUsed;      // breaks + placements spent this episode
    private int placeCooldown;   // small gap between bridge placements

    /** Clear episode state — call as soon as she can path normally again. */
    public void reset() {
        breaking = null;
        progress = 0f;
        blocksUsed = 0;
        placeCooldown = 0;
    }

    /**
     * Make at most one step of progress toward the owner by breaking or bridging.
     * Returns true if it acted this tick. The caller guarantees she is genuinely stuck
     * and far enough to bother. Gated by {@code allowOwnerPathAssist}.
     */
    public boolean tick(ServerWorld world, GirlfriendEntity gf, GirlfriendConfig.Behavior b) {
        if (!b.allowOwnerPathAssist) return false;
        PlayerEntity owner = gf.getOwner();
        if (owner == null) return false;
        return tickToward(world, gf, b, owner.getBlockPos());
    }

    /**
     * Make at most one step of progress toward {@code target} by breaking or bridging — used both to
     * rejoin the owner and to reach a perceived chest. Honors the per-episode block cap, never breaks
     * terrain near home (only lays transient void matter there), and never breaks functional blocks
     * (chests / furnaces / shulkers — see {@link #isProtected}). Returns true if it acted this tick.
     */
    public boolean tickToward(ServerWorld world, GirlfriendEntity gf, GirlfriendConfig.Behavior b, BlockPos target) {
        if (blocksUsed >= b.pathAssistBlocksMax) return false;
        if (gf.isFormTwo()) return false;   // 形态二浮空，直接飞过去即可——绝不在空中垒方块搭桥（修 TODO）



        // Near the home base she won't BREAK terrain (no holes for mobs to pour into), but she may still
        // conjure transient void matter to bridge a gap or pillar up — those blocks auto-restore (~60s),
        // so they never endanger the base. This is what lets her climb to an owner standing above her
        // even right next to home, instead of getting stuck at his feet far below.
        BlockPos home = gf.getHomePos();
        boolean nearHome = home != null
            && home.getSquaredDistance(gf.getBlockPos()) <= b.homeProtectRadius * b.homeProtectRadius;

        Direction dir = horizontalToward(gf.getBlockPos(), target);
        BlockPos foot = gf.getBlockPos();
        BlockPos stepFoot = foot.offset(dir);
        BlockPos stepHead = stepFoot.up();
        BlockPos stepGround = stepFoot.down();

        // 1) A wall in the way (head or foot height) → chip it down. Skipped near home (don't dig the base open).
        if (!nearHome) {
            if (blocksStep(world, stepHead)) return chip(world, gf, stepHead);
            if (blocksStep(world, stepFoot)) return chip(world, gf, stepFoot);
            // 需要往上走（目标更高）时，还要凿掉「头顶」和「前方·头顶」，否则她要踏上一格台阶时被天花板挡住、原地
            // 卡死（STEP_HEIGHT 够爬一格，但没有头顶空间就上不去——之前必须玩家手动帮她打掉这两块）。
            if (target.getY() - foot.getY() >= 1) {
                if (blocksStep(world, foot.up(2))) return chip(world, gf, foot.up(2));
                if (blocksStep(world, stepHead.up())) return chip(world, gf, stepHead.up());
            }
        }

        // 2) A gap ahead (nothing to stand on) → lay an (infinite, transient) void block to bridge it.
        if (!world.getBlockState(stepGround).isSolidBlock(world, stepGround)
                && world.getBlockState(stepFoot).isReplaceable()) {
            if (placeCooldown > 0) { placeCooldown--; return true; }
            return placeVoid(world, gf, stepGround);
        }

        // 3) The target sits well above her AND she's basically right underneath it → pillar straight up.
        //    只有水平已贴到目标正下方(±1格)才垫高——省方块：先靠近/破障走到目标正下方，再往上垫，
        //    而不是隔着老远就先把自己垫高。
        int hdx = target.getX() - foot.getX();
        int hdz = target.getZ() - foot.getZ();
        if (target.getY() - foot.getY() >= 2 && (hdx * hdx + hdz * hdz) <= 2 && hasHeadroom(world, foot)) {
            if (placeCooldown > 0) { placeCooldown--; return true; }
            if (placeVoid(world, gf, foot)) {
                // Lift her onto the block she just conjured beneath herself (no suffocation).
                gf.requestTeleport(foot.getX() + 0.5, foot.getY() + 1.0, foot.getZ() + 0.5);
                return true;
            }
        }

        // 4) The target is well below her and she can't walk down → carefully fall and place void buffer.
        if (foot.getY() - target.getY() >= 3 && canSafelyFall(world, foot)) {
            // Let her fall naturally, and when she's falling fast enough, place a void block below to cushion
            if (gf.getVelocity().y < -0.5) {
                BlockPos cushion = foot.down(2);
                if (world.getBlockState(cushion).isAir() || world.getBlockState(cushion).isReplaceable()) {
                    if (placeCooldown > 0) { placeCooldown--; return true; }
                    return placeVoid(world, gf, cushion);
                }
            }
        }
        return false;
    }

    private static final long VOID_LIFETIME = 2000L; // ~100s before a bridge/pillar block self-vanishes

    /** Lay one transient void block (infinite supply); it auto-restores to its original state ~100s later. */
    private boolean placeVoid(ServerWorld world, GirlfriendEntity gf, BlockPos pos) {
        AbilityManager.blocks().place(world, pos, world.getTime() + VOID_LIFETIME, null);
        gf.swingHand(Hand.MAIN_HAND);
        blocksUsed++;
        placeCooldown = 6;
        return true;
    }

    /** Room for her 1.8-tall body one block higher (so a pillar step won't suffocate her). */
    private static boolean hasHeadroom(ServerWorld world, BlockPos foot) {
        BlockState above = world.getBlockState(foot.up());
        BlockState above2 = world.getBlockState(foot.up(2));
        return (above.isAir() || above.isReplaceable()) && (above2.isAir() || above2.isReplaceable());
    }

    /** Check if it's safe to fall from current position (there's eventually ground below, not void). */
    private static boolean canSafelyFall(ServerWorld world, BlockPos from) {
        for (int dy = 1; dy <= 12; dy++) {
            BlockPos check = from.down(dy);
            if (world.getBlockState(check).isSolidBlock(world, check)) return true;
        }
        return false; // no ground within 12 blocks → probably void, don't jump
    }

    /** Chip the block at {@code pos} at vanilla-ish speed; break it once progress fills. */
    private boolean chip(ServerWorld world, GirlfriendEntity gf, BlockPos pos) {
        BlockState st = world.getBlockState(pos);
        if (isProtected(st)) return false;
        // 破障前换上对应工具：石头类→镐、木头→斧、沙/土→铲（有就用，没有就空手慢慢凿），
        // 而不是一直拿着法杖干撸。
        WorkUtil.equipTool(gf, WorkUtil.toolFor(st));
        float delta = WorkUtil.breakDelta(world, gf, pos);
        if (delta <= 0f) return false; // unbreakable (bedrock) → give up so she can teleport instead
        if (!pos.equals(breaking)) { breaking = pos; progress = 0f; }
        progress += delta;
        gf.swingHand(Hand.MAIN_HAND);
        if (progress >= 1f) {
            WorkUtil.breakBlock(world, gf, pos);
            breaking = null;
            progress = 0f;
            blocksUsed++;
        }
        return true;
    }

    /** A block that physically blocks her step and is worth breaking through. */
    private static boolean blocksStep(ServerWorld world, BlockPos pos) {
        BlockState st = world.getBlockState(pos);
        if (st.isAir() || st.isReplaceable()) return false;
        return st.isSolidBlock(world, pos);
    }

    /** Don't dig through storage / functional blocks (chests, furnaces, shulkers…) even en route. */
    private static boolean isProtected(BlockState st) {
        return st.getBlock() instanceof BlockEntityProvider;
    }

    private static Direction horizontalToward(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
