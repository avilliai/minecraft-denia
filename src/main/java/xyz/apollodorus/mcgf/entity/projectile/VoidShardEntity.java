package xyz.apollodorus.mcgf.entity.projectile;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.Item;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.World;
import xyz.apollodorus.mcgf.block.ModBlocks;
import xyz.apollodorus.mcgf.entity.GirlfriendEntities;

/**
 * A 虚质碎块 — the void-matter shard 达妮娅 hurls in her ranged kit (普攻1a, and the 普攻3a swarm).
 * It is a {@link ThrownItemEntity} whose item is the 虚质方块, so the vanilla
 * {@code FlyingItemEntityRenderer} draws it as a spinning void cube with no custom render code. On
 * hit it deals Fusion(fire)-flavored damage and optionally slows the target (a3). Trails void/soul-fire
 * particles in flight. (Energy charging is handled by {@code DaniyaAttackGoal}, per attack action.)
 */
public class VoidShardEntity extends ThrownItemEntity {
    private float shardDamage = 4.0f;
    private boolean slow = false;

    public VoidShardEntity(EntityType<? extends VoidShardEntity> type, World world) {
        super(type, world);
    }

    public VoidShardEntity(World world, LivingEntity owner) {
        super(GirlfriendEntities.VOID_SHARD, owner, world,
            new net.minecraft.item.ItemStack(ModBlocks.VOID_BLOCK));
    }

    /** Set per-cast damage and whether a hit applies SLOWNESS (the a3 swarm). */
    public void configure(float damage, boolean slow) {
        this.shardDamage = damage;
        this.slow = slow;
    }

    @Override
    protected Item getDefaultItem() {
        return ModBlocks.VOID_BLOCK.asItem();
    }

    @Override
    protected double getGravity() {
        return 0.02; // a gentle arc, flatter than a snowball
    }

    @Override
    public void tick() {
        super.tick();
        if (getEntityWorld() instanceof ServerWorld sw) {
            sw.spawnParticles(ParticleTypes.PORTAL, getX(), getY(), getZ(), 2, 0.05, 0.05, 0.05, 0.0);
            sw.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, getX(), getY(), getZ(), 1, 0.02, 0.02, 0.02, 0.0);
        }
    }

    @Override
    protected void onEntityHit(EntityHitResult hit) {
        super.onEntityHit(hit);
        if (!(getEntityWorld() instanceof ServerWorld sw)) return;
        Entity target = hit.getEntity();
        Entity owner = getOwner();
        DamageSource src = owner instanceof LivingEntity le
            ? sw.getDamageSources().mobProjectile(this, le)
            : sw.getDamageSources().magic();
        if (target.damage(sw, src, shardDamage)) {
            if (slow && target instanceof LivingEntity lt) {
                lt.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 80, 1));
            }
        }
    }

    @Override
    protected void onCollision(HitResult hit) {
        super.onCollision(hit);
        if (getEntityWorld() instanceof ServerWorld sw) {
            sw.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, getX(), getY(), getZ(), 12, 0.2, 0.2, 0.2, 0.05);
            sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, getX(), getY(), getZ(), 8, 0.2, 0.2, 0.2, 0.1);
            discard();
        }
    }
}
