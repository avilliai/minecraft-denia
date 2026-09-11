#!/usr/bin/env bash
# READ-ONLY API verification for the 虚质方块 / 蚀域 / ranged-attack feature work.
# Dumps Yarn-named signatures from the deobfuscated MC jars. Inspects bytecode only.
set -o pipefail

JDK="/c/Users/Administrator/WebstormProjects/untitled1/mc-girlfriend/.jdks/jdk-21.0.11+10/bin"
J="$JDK/javap"; JB="$JDK/jar"
[ -x "$J" ]  || J="$(command -v javap)"
[ -x "$JB" ] || JB="$(command -v jar)"

CACHE="/c/Users/Administrator/.gradle/caches"
C=$(find "$CACHE" -path '*minecraft-clientonly*1.21.11*v2.jar' 2>/dev/null | grep -vi sources | head -1)
M=$(find "$CACHE" -path '*minecraft-common*1.21.11*v2.jar'     2>/dev/null | grep -vi sources | head -1)

p()  { echo; echo "######## $1"; "$J" -cp "$2" -p "$3" 2>&1; }
pg() { echo; echo "######## $1"; "$J" -cp "$2" -p "$3" 2>&1 | grep -iE "$4"; }

# ---- Block registration ----
pg "AbstractBlock.Settings"  "$M" 'net.minecraft.block.AbstractBlock$Settings' 'public static|registryKey|strength|luminance|sounds|mapColor|requiresTool|nonOpaque|hardness|resistance|create\(|copy'
pg "Block ctor/state"        "$M" net.minecraft.block.Block 'public Block|getDefaultState|getDefaultMapColor'
pg "Blocks.register helper"  "$M" net.minecraft.block.Blocks 'private static|register'
pg "BlockItem ctor"          "$M" net.minecraft.item.BlockItem 'public BlockItem|getBlock'
pg "Item.Settings"           "$M" net.minecraft.item.Item$Settings 'registryKey|useBlockPrefixedTranslationKey|maxCount'

# ---- Particles ----
echo; echo "######## ParticleTypes fields"; "$J" -cp "$M" -p net.minecraft.particle.ParticleTypes 2>&1 | grep -iE 'public static final' | grep -iE 'flame|soul|portal|end_rod|sculk|dust|smoke|crit|enchant|glow|witch|spell|lava|spark|firework|cloud|ash|squid|electric|wax|trial|ominous|infested|vault|gust|shriek|reverse'
p  "DustParticleEffect"      "$C" net.minecraft.particle.DustParticleEffect
pg "ServerWorld.spawnParticles" "$M" net.minecraft.server.world.ServerWorld 'spawnParticles'

# ---- Status effects + instance ----
echo; echo "######## StatusEffects fields"; "$J" -cp "$M" -p net.minecraft.entity.effect.StatusEffects 2>&1 | grep -iE 'public static final' | grep -iE 'speed|jump|resistance|health_boost|regeneration|absorption|strength|haste|fire_resistance|slowness'
pg "StatusEffectInstance ctor" "$M" net.minecraft.entity.effect.StatusEffectInstance 'public StatusEffectInstance'

# ---- Attribute modifiers ----
p  "EntityAttributeModifier" "$M" net.minecraft.entity.attribute.EntityAttributeModifier
pg "EntityAttributeInstance" "$M" net.minecraft.entity.attribute.EntityAttributeInstance 'addTemporary|addPersistent|removeModifier|hasModifier|getModifier|updateModifier'
pg "EntityAttributes consts" "$M" net.minecraft.entity.attribute.EntityAttributes 'MAX_HEALTH|ATTACK_DAMAGE|MOVEMENT_SPEED'

# ---- Explosion (a4) ----
pg "World.createExplosion"   "$M" net.minecraft.world.World 'createExplosion'
echo; echo "######## World.ExplosionSourceType"; "$JB" tf "$M" 2>/dev/null | grep -iE 'World\$ExplosionSourceType'
pg "Explosion enums"         "$M" net.minecraft.world.explosion.Explosion 'DestructionType|public'

# ---- Damage ----
pg "LivingEntity.damage"     "$M" net.minecraft.entity.LivingEntity 'damage\(|heal\(|getMaxHealth|setHealth'
pg "ServerWorld damageSources" "$M" net.minecraft.server.world.ServerWorld 'getDamageSources'
pg "DamageSources"           "$M" net.minecraft.entity.damage.DamageSources 'mobAttack|magic|explosion|indirectMagic|onFire|inFire|mobProjectile'

# ---- Projectiles (1a throw, a3 arrow-like) ----
echo; echo "######## entity.projectile classes"; "$JB" tf "$M" 2>/dev/null | grep -iE 'entity/projectile/[A-Za-z]*\.class'
pg "ProjectileEntity"        "$M" net.minecraft.entity.projectile.ProjectileEntity 'setVelocity|setOwner|public'
pg "ThrownItemEntity"        "$M" net.minecraft.entity.projectile.thrown.ThrownItemEntity 'public|protected'
pg "PersistentProjectileEntity" "$M" net.minecraft.entity.projectile.PersistentProjectileEntity 'public PersistentProjectile|setVelocity|setDamage|public void'

# ---- FallingBlockEntity (2a) ----
p  "FallingBlockEntity"      "$M" net.minecraft.entity.FallingBlockEntity

# ---- EntityType.Builder (for custom projectile) ----
pg "EntityType.Builder"      "$M" 'net.minecraft.entity.EntityType$Builder' 'create\(|dimensions|build\(|maxTrackingRange|trackingTickInterval'

# ---- FabricItemGroup / ItemGroups (creative tab for the block) ----
pg "ItemGroupEvents"         "$M" net.minecraft.item.ItemGroups 'BUILDING|FUNCTIONAL|COMBAT|NATURAL'

echo; echo "######## DONE"
