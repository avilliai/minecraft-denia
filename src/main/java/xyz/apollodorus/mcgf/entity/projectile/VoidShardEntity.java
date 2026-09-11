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
import net.minecraft.util.math.Vec3d;
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
    // 形态二·制空权：追踪目标。让浮空狙击可靠命中(否则慢弹直飞、对会动的远程怪几乎打不中)。
    private net.minecraft.entity.LivingEntity homingTarget;
    private double homingStrength = 0.0;   // 每 tick 向目标方向的转向比例(0=不追踪)
    private int ticksAlive;                 // 追踪弹可能一直追不上 → 到寿命上限自毁，别泄漏

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

    /** 开启追踪：飞行途中缓缓修正朝向 {@code target}。{@code strength} 越大转向越猛(建议 0.12~0.25)。 */
    public void setHoming(net.minecraft.entity.LivingEntity target, double strength) {
        this.homingTarget = target;
        this.homingStrength = strength;
    }

    @Override
    protected Item getDefaultItem() {
        return ModBlocks.VOID_BLOCK.asItem();
    }

    @Override
    protected double getGravity() {
        // 追踪时几乎无视重力(走直线修正)，否则维持原来轻微下坠的弧线。
        return homingTarget != null ? 0.0 : 0.02;
    }

    @Override
    public void tick() {
        // 追踪修正：把当前速度朝「指向目标、保持原速」的方向做有限插值，再让 super.tick() 推进位置。
        if (homingTarget != null && homingStrength > 0) {
            if (!homingTarget.isAlive()) {
                homingTarget = null;
            } else {
                Vec3d cur = getVelocity();
                double speed = cur.length();
                if (speed > 1.0e-3) {
                    Vec3d aim = homingTarget.getEntityPos()
                        .add(0, homingTarget.getHeight() * 0.5, 0)
                        .subtract(getEntityPos());
                    if (aim.lengthSquared() > 1.0e-4) {
                        Vec3d desired = aim.normalize().multiply(speed);
                        Vec3d steered = cur.add(desired.subtract(cur).multiply(homingStrength));
                        // 保持原速大小，只改方向，避免越追越慢/越快。
                        if (steered.lengthSquared() > 1.0e-6) {
                            setVelocity(steered.normalize().multiply(speed));
                        }
                    }
                }
            }
        }
        super.tick();
        if (getEntityWorld() instanceof ServerWorld sw) {
            sw.spawnParticles(ParticleTypes.PORTAL, getX(), getY(), getZ(), 2, 0.05, 0.05, 0.05, 0.0);
            sw.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, getX(), getY(), getZ(), 1, 0.02, 0.02, 0.02, 0.0);
            if (++ticksAlive > 80) discard();   // 追踪弹 4s 未命中即消解，防止绕场追人
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
