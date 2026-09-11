#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Pasithea / MC 1.21.11 — READ-ONLY API verification, pass 2.
#
# Focus: the humanoid render plumbing needed for §F (slim model + visible armor +
# held item) and the §I structure accessor. Inspects bytecode only — no writes,
# no build, no downloads. Safe to run.
# ---------------------------------------------------------------------------
set -o pipefail

JDK="/c/Users/Administrator/WebstormProjects/untitled1/mc-girlfriend/.jdks/jdk-21.0.11+10/bin"
J="$JDK/javap"; JB="$JDK/jar"
[ -x "$J" ]  || J="$(command -v javap)"
[ -x "$JB" ] || JB="$(command -v jar)"

CACHE="/c/Users/Administrator/.gradle/caches"
C=$(find "$CACHE" -path '*minecraft-clientonly*1.21.11*v2.jar' 2>/dev/null | grep -vi sources | head -1)
M=$(find "$CACHE" -path '*minecraft-common*1.21.11*v2.jar'     2>/dev/null | grep -vi sources | head -1)
echo "== CLIENT=$C"
echo "== COMMON=$M"

p()  { echo; echo "######## $1"; "$J" -cp "$2" -p "$3" 2>&1; }
pc() { echo; echo "######## (bytecode) $1"; "$J" -cp "$2" -c -p "$3" 2>&1; }
pg() { echo; echo "######## $1"; "$J" -cp "$2" -p "$3" 2>&1 | grep -iE "$4"; }

# ---- which humanoid renderer base to extend? confirm the exact class name ----
echo; echo "######## humanoid *EntityRenderer classes"
"$JB" tf "$C" 2>/dev/null | grep -iE 'render/entity/[A-Za-z]*EntityRenderer\.class' | sort

# ---- generic BOUNDS on the renderer bases (do they allow EntityModel<? super S>?) ----
pg "LivingEntityRenderer decl" "$C" net.minecraft.client.render.entity.LivingEntityRenderer 'class .*LivingEntityRenderer'
pg "MobEntityRenderer decl"    "$C" net.minecraft.client.render.entity.MobEntityRenderer    'class .*MobEntityRenderer'

# ---- BipedEntityRenderer: does its ctor add the armor feature + does updateRenderState
#      copy equipment/held? bytecode reveals the exact construction recipe ----
pc "BipedEntityRenderer"       "$C" net.minecraft.client.render.entity.BipedEntityRenderer

# ---- render-state field names the features read (held item + equipment) ----
p  "ArmedEntityRenderState"    "$C" net.minecraft.client.render.entity.state.ArmedEntityRenderState
pg "PlayerEntityRenderState"   "$C" net.minecraft.client.render.entity.state.PlayerEntityRenderState 'class |public .* [a-zA-Z]+;|RenderState\('

# ---- equipment plumbing for the ArmorFeatureRenderer ctor ----
p  "EquipmentRenderer"         "$C" net.minecraft.client.render.entity.equipment.EquipmentRenderer
p  "EquipmentModelData(model)" "$C" net.minecraft.client.render.entity.model.EquipmentModelData
pg "EquipmentModel.LayerType"  "$C" 'net.minecraft.client.render.entity.equipment.EquipmentModel$LayerType' 'class |HUMANOID|public static'

# ---- §I structures: StructureAccessor query methods ----
p  "StructureAccessor"         "$M" net.minecraft.world.gen.StructureAccessor

echo; echo "######## DONE2"
