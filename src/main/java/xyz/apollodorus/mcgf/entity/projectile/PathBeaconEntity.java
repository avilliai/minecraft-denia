package xyz.apollodorus.mcgf.entity.projectile;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import xyz.apollodorus.mcgf.MCGirlfriendMod;
import xyz.apollodorus.mcgf.combat.AbilityManager;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.entity.GirlfriendEntities;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;
import xyz.apollodorus.mcgf.item.ModItems;

/**
 * 「寻路信标」the player throws like an ender pearl — but it NEVER teleports the player. Where it lands it
 * raises an activated-beacon-style beam of light, and ~3s later it retasks the thrower's 达妮娅 to path to
 * that spot and lead the way there (handy for bridging rough terrain). The guide itself (travel, protect-
 * first, loiter, time-out teleport-back) lives on {@link GirlfriendEntity} + its guide goal; this entity
 * only marks the spot, plays the beam, and hands off the target.
 */
public class PathBeaconEntity extends ThrownItemEntity {

    public PathBeaconEntity(EntityType<? extends PathBeaconEntity> type, World world) {
        super(type, world);
    }

    public PathBeaconEntity(World world, LivingEntity owner) {
        super(GirlfriendEntities.PATH_BEACON, owner, world, new net.minecraft.item.ItemStack(ModItems.PATH_BEACON));
    }

    @Override
    protected Item getDefaultItem() {
        return ModItems.PATH_BEACON;
    }

    @Override
    protected double getGravity() {
        return 0.03; // a gentle lob, like a pearl
    }

    @Override
    public void tick() {
        super.tick();
        if (getEntityWorld() instanceof ServerWorld sw) {
            sw.spawnParticles(ParticleTypes.END_ROD, getX(), getY(), getZ(), 1, 0.03, 0.03, 0.03, 0.0);
            sw.spawnParticles(ParticleTypes.GLOW, getX(), getY(), getZ(), 1, 0.02, 0.02, 0.02, 0.0);
        }
    }

    @Override
    protected void onCollision(HitResult hit) {
        super.onCollision(hit);
        if (!(getEntityWorld() instanceof ServerWorld sw)) return;
        BlockPos landing = BlockPos.ofFloored(getX(), getY(), getZ());
        // settle onto the surface just below the impact so the guide target is standable
        for (int i = 0; i < 4 && !sw.getBlockState(landing).isSolidBlock(sw, landing); i++) {
            landing = landing.down();
        }
        if (sw.getBlockState(landing).isSolidBlock(sw, landing)) landing = landing.up();

        onLanded(sw, landing, getOwner() instanceof PlayerEntity p ? p : null);
        discard();
    }

    /** Light the beacon beam, then after ~3s retask the owner's 达妮娅 to lead the way to {@code landing}. */
    private static void onLanded(ServerWorld sw, BlockPos landing, PlayerEntity owner) {
        Vec3d c = Vec3d.ofCenter(landing);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.0f, 1.4f);

        // Activated-beacon-style beam of light shooting up from the landing spot (~9s).
        final long beamEnd = sw.getTime() + 180;
        AbilityManager.schedule(sw, beamEnd, () -> {
            for (int dy = 0; dy < 16; dy++) {
                double y = c.y + dy;
                sw.spawnParticles(ParticleTypes.END_ROD, c.x, y, c.z, 1, 0.06, 0.0, 0.06, 0.0);
                if (dy % 2 == 0) sw.spawnParticles(ParticleTypes.GLOW, c.x, y, c.z, 1, 0.08, 0.0, 0.08, 0.0);
            }
            sw.spawnParticles(ParticleTypes.GLOW, c.x, c.y + 0.2, c.z, 4, 0.3, 0.05, 0.3, 0.0);
        });

        if (owner == null) return;
        final BlockPos target = landing.toImmutable();
        // 3s after it lands, hand the nearest owned 达妮娅 the new guide target (replacing any prior one).
        AbilityManager.delay(sw, sw.getTime() + 60, () -> {
            GirlfriendEntity gf = nearestOwned(sw, owner);
            if (gf == null) return;
            gf.setGuideTarget(target);
            if (MCGirlfriendMod.BRAIN != null) {
                MCGirlfriendMod.BRAIN.proactive(gf, ConfigManager.get().prompts.guideStart);
            }
        });
    }

    private static GirlfriendEntity nearestOwned(ServerWorld sw, PlayerEntity owner) {
        GirlfriendEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (GirlfriendEntity gf : GirlfriendEntity.ACTIVE) {
            if (gf.isRemoved() || !gf.isAlive()) continue;
            if (!gf.isOwner(owner) || gf.getEntityWorld() != sw) continue;
            double d = gf.squaredDistanceTo(owner);
            if (d < bestSq) { bestSq = d; best = gf; }
        }
        return best;
    }
}
