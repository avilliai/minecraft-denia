#!/usr/bin/env bash
# §A — full rename  mcgf -> pasithea  (project brand only; in-world persona stays 达妮娅,
# and internal domain classes GirlfriendEntity/GirlfriendConfig/etc. stay as-is).
# Safe to re-run: every step is guarded / idempotent.
set -euo pipefail

ROOT="/c/Users/Administrator/WebstormProjects/untitled1/mc-girlfriend"
cd "$ROOT"
echo "== Pasithea rename in: $ROOT =="

MAIN="src/main/java/xyz/apollodorus"
CLIENT="src/client/java/xyz/apollodorus"
ASSETS="src/main/resources/assets"

# ---------- 1) move package + asset directories  mcgf -> pasithea ----------
if [ -d "$MAIN/mcgf" ];   then mv "$MAIN/mcgf"   "$MAIN/pasithea";   echo "moved  $MAIN/mcgf"; fi
if [ -d "$CLIENT/mcgf" ]; then mv "$CLIENT/mcgf" "$CLIENT/pasithea"; echo "moved  $CLIENT/mcgf"; fi
if [ -d "$ASSETS/mcgf" ]; then mv "$ASSETS/mcgf" "$ASSETS/pasithea"; echo "moved  $ASSETS/mcgf"; fi

# ---------- 2) rename entrypoint + manager source files ----------
if [ -f "$MAIN/pasithea/MCGirlfriendMod.java" ]; then
  mv "$MAIN/pasithea/MCGirlfriendMod.java" "$MAIN/pasithea/PasitheaMod.java"; echo "renamed PasitheaMod.java"; fi
if [ -f "$CLIENT/pasithea/client/MCGirlfriendClient.java" ]; then
  mv "$CLIENT/pasithea/client/MCGirlfriendClient.java" "$CLIENT/pasithea/client/PasitheaClient.java"; echo "renamed PasitheaClient.java"; fi
if [ -f "$MAIN/pasithea/entity/DownedManager.java" ]; then
  mv "$MAIN/pasithea/entity/DownedManager.java" "$MAIN/pasithea/entity/BondManager.java"; echo "renamed BondManager.java"; fi

# ---------- 3) rewrite Java sources ----------
# -e order matters: package FQN first; specific filenames before the generic "mcgf-"/"mcgf/" rules.
find src -name '*.java' -print0 | xargs -0 -r sed -i \
  -e 's/xyz\.apollodorus\.mcgf/xyz.apollodorus.pasithea/g' \
  -e 's/MCGirlfriendClient/PasitheaClient/g' \
  -e 's/MCGirlfriendMod/PasitheaMod/g' \
  -e 's/DownedManager/BondManager/g' \
  -e 's/MOD_ID = "mcgf"/MOD_ID = "pasithea"/g' \
  -e 's/mcgf-downed\.json/pasithea-bonds.json/g' \
  -e 's/mcgf\.json/pasithea.json/g' \
  -e 's#"mcgf/#"pasithea/#g' \
  -e 's/"mcgf-/"pasithea-/g' \
  -e 's/commands\.mcgf\./commands.pasithea./g' \
  -e 's/\[mcgf\]/[pasithea]/g' \
  -e 's/"girlfriend"/"companion"/g'
echo "rewrote Java sources"

# ---------- 4) build + project metadata ----------
sed -i 's/"mcgf"/"pasithea"/g' build.gradle
sed -i -e 's/xyz\.apollodorus\.mcgf/xyz.apollodorus.pasithea/g' \
       -e 's/^archives_base_name=.*/archives_base_name=pasithea/' gradle.properties
sed -i "s/rootProject\.name = 'mcgf'/rootProject.name = 'pasithea'/" settings.gradle
if [ -f README.md ]; then sed -i -e 's/mcgf/pasithea/g' -e 's#/gf\b#/pasithea#g' README.md; fi
echo "rewrote build + project metadata"

# ---------- 5) leftover report ----------
echo "== remaining 'mcgf' / 'MCGirlfriend' (tools/ dev scripts are expected & fine) =="
grep -rIn --exclude-dir=.gradle --exclude-dir=build --exclude-dir=.idea \
  -e 'mcgf' -e 'MCGirlfriend' \
  src settings.gradle build.gradle gradle.properties src/main/resources/fabric.mod.json \
  && echo "  ^ review the above" || echo "  (clean — none in src/build/resources)"

echo "== file tree under new package =="
ls "$MAIN/pasithea" "$CLIENT/pasithea" "$ASSETS/pasithea" 2>/dev/null || true
echo "== rename done — now run tools/build.sh =="
