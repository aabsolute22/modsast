#!/usr/bin/env bash
# Internal helper: prints public API signatures of selected Minecraft classes
# (javap, no code) so the mod can be written against the exact 26.2 API.
set -u
JARS=$(find .gradle/loom-cache -name '*.jar' 2>/dev/null | grep -E 'minecraft-(common|clientOnly)-[^/]*\.jar$' | grep -v sources | sort -u)
echo "== jars"; echo "$JARS"
CP=$(echo "$JARS" | tr '\n' ':')
FAPI=$(find ~/.gradle -name 'fabric-*.jar' 2>/dev/null | grep -v sources | tr '\n' ':')
grep -v '^#' .github/probe/classes.txt | while IFS='|' read -r c pat; do
  [ -z "$c" ] && continue
  echo "=================== $c"
  if [ -z "$pat" ]; then
    javap -public -cp "$CP$FAPI" "$c" 2>&1 | grep -v 'toString()\|hashCode()\|equals(java.lang.Object)' | head -300
  else
    javap -public -cp "$CP$FAPI" "$c" 2>&1 | grep -E "class |interface |$pat" | head -120
  fi
done
echo "== data"
for f in $(grep -v '^#' .github/probe/data.txt); do
  for j in $JARS; do
    if unzip -l "$j" "$f" >/dev/null 2>&1; then echo "=================== $f"; unzip -p "$j" "$f" | head -c 3000; echo; break; fi
  done
done
echo "== nbt"
for j in $JARS; do
  if unzip -l "$j" data/minecraft/structure/end_city/base_floor.nbt >/dev/null 2>&1; then
    unzip -o -q "$j" 'data/minecraft/structure/end_city/*' -d /tmp/ec
  fi
done
python3 .github/probe/nbt_layout.py /tmp/ec/data/minecraft/structure/end_city/*.nbt | grep '###'
