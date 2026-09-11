#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Pasithea / MC 1.21.11 — READ-ONLY API verification.
#
# Dumps real method signatures from the deobfuscated (Yarn-named) Minecraft +
# Fabric jars so Claude can match the post-1.21.11 API renames before writing
# the render / boat / structure / event code.
#
# This script ONLY inspects bytecode (javap/jar -t). It does NOT modify any
# file, build, download, or install anything. Safe to run.
# ---------------------------------------------------------------------------
set -o pipefail

JDK="/c/Users/Administrator/WebstormProjects/untitled1/mc-girlfriend/.jdks/jdk-21.0.11+10/bin"
J="$JDK/javap"; JB="$JDK/jar"
[ -x "$J" ]  || J="$(command -v javap)"
[ -x "$JB" ] || JB="$(command -v jar)"
echo "== javap: $J"

CACHE="/c/Users/Administrator/.gradle/caches"
C=$(find "$CACHE" -path '*minecraft-clientonly*1.21.11*v2.jar' 2>/dev/null | grep -vi sources | head -1)
M=$(find "$CACHE" -path '*minecraft-common*1.21.11*v2.jar'     2>/dev/null | grep -vi sources | head -1)
EE=$(find "$CACHE" -name '*.jar' 2>/dev/null | grep -i 'fabric-entity-events'   | grep -vi sources | head -1)
NET=$(find "$CACHE" -name '*.jar' 2>/dev/null | grep -i 'fabric-networking-api' | grep -vi sources | head -1)
echo "== CLIENT=$C"
echo "== COMMON=$M"
echo "== ENTITY_EVENTS=$EE"
echo "== NETWORKING=$NET"

p()  { echo; echo "######## $1"; "$J" -cp "$2" -p "$3" 2>&1; }
pg() { echo; echo "######## $1"; "$J" -cp "$2" -p "$3" 2>&1 | grep -iE "$4"; }

# ---- §F client render: slim model + held-item + armor ----
p  "PlayerEntityModel"            "$C" net.minecraft.client.render.entity.model.PlayerEntityModel
pg "BipedEntityModel (arms)"      "$C" net.minecraft.client.render.entity.model.BipedEntityModel 'class |getTexturedModelData|ModelWithArms|ModelPart'
p  "HeldItemFeatureRenderer"      "$C" net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer
p  "ArmorFeatureRenderer"         "$C" net.minecraft.client.render.entity.feature.ArmorFeatureRenderer
p  "BipedEntityRenderState"       "$C" net.minecraft.client.render.entity.state.BipedEntityRenderState
p  "EntityRendererFactory.Context" "$C" 'net.minecraft.client.render.entity.EntityRendererFactory$Context'
pg "LivingEntityRenderer hooks"   "$C" net.minecraft.client.render.entity.LivingEntityRenderer 'addFeature|updateRenderState'
echo; echo "######## equipment render classes"; "$JB" tf "$C" 2>/dev/null | grep -iE 'render/entity/equipment/[A-Z][A-Za-z]*\.class'

# ---- §J boats + riding ----
echo; echo "######## boat classes"; "$JB" tf "$M" 2>/dev/null | grep -iE 'entity/vehicle/.*(oat|aft).*\.class'
pg "Entity riding"                "$M" net.minecraft.entity.Entity 'startRiding|stopRiding|hasVehicle|getVehicle|getControllingPassenger'

# ---- §B combat (confirm already-used APIs) ----
pg "LivingEntity.canSee"          "$M" net.minecraft.entity.LivingEntity 'canSee'
pg "Path target/reach"            "$M" net.minecraft.entity.ai.pathing.Path 'reachesTarget|getTarget|isFinished'

# ---- §D path assist: navigation idle + block solidity ----
pg "EntityNavigation"             "$M" net.minecraft.entity.ai.pathing.EntityNavigation 'isIdle|findPathTo|getCurrentPath|startMovingTo'
pg "BlockState solidity"          "$M" 'net.minecraft.block.AbstractBlock$AbstractBlockState' 'isSolid|FullCube|isSideSolid|getCollisionShape|isOpaque'
pg "BlockItem.getBlock"           "$M" net.minecraft.item.BlockItem 'getBlock'

# ---- §I structures ----
pg "ServerWorld structure access" "$M" net.minecraft.server.world.ServerWorld 'getStructureAccessor|StructureAccessor'
pg "StructureAccessor"            "$M" net.minecraft.world.gen.structure.StructureAccessor 'public'
pg "StructureKeys"                "$M" net.minecraft.world.gen.structure.StructureKeys 'SHIPWRECK|OCEAN_RUIN|RUINED_PORTAL|DESERT|MONUMENT|MANSION|MINESHAFT'

# ---- §E charm: spawn / discard ----
pg "EntityType.spawn/create"      "$M" net.minecraft.entity.EntityType ' spawn| create'
pg "Entity discard/uuid"          "$M" net.minecraft.entity.Entity 'discard\(|getUuid\('

# ---- §C / §E fabric events ----
p  "ServerLivingEntityEvents"     "$EE"  net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
pg "ServerPlayConnectionEvents"   "$NET" net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents 'JOIN|EVENT|Event'

echo; echo "######## DONE"
