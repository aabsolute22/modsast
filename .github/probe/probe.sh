#!/usr/bin/env bash
# Internal helper: prints public API signatures of selected Minecraft classes
# (javap, no code) so the mod can be written against the exact 26.2 API.
set -u
JARS=$(find ~/.gradle .gradle -name '*.jar' 2>/dev/null | grep -E 'minecraft-(common|clientOnly|merged|client)[^/]*\.jar$' | grep -v sources | sort -u)
echo "== jars"; echo "$JARS"
CP=$(echo "$JARS" | tr '\n' ':')
FAPI=$(find ~/.gradle -name 'fabric-*.jar' 2>/dev/null | grep -v sources | tr '\n' ':')
for c in $(cat .github/probe/classes.txt | grep -v '^#'); do
  echo "=================== $c"
  javap -public -cp "$CP$FAPI" "$c" 2>&1 | head -400
done
echo "== packages"
for p in $(cat .github/probe/packages.txt | grep -v '^#'); do
  for j in $JARS; do
    unzip -l "$j" | awk '{print $4}' | grep "^$p/[^/]*\.class$" | sed 's|\.class$||; s|/|.|g' | while read c; do
      echo "=================== $c"; javap -public -cp "$CP" "$c" 2>&1 | head -200
    done
  done
done
echo "== data"
for f in $(cat .github/probe/data.txt | grep -v '^#'); do
  for j in $JARS; do
    if unzip -l "$j" "$f" >/dev/null 2>&1; then echo "=================== $f ($j)"; unzip -p "$j" "$f" | head -c 4000; echo; fi
  done
done
for j in $JARS; do unzip -l "$j" | awk '{print $4}' | grep -E 'end_city|end_cit|end_spike|has_structure/end' ; done | sort -u | head -80
echo "== nbt"
for j in $JARS; do
  if unzip -l "$j" data/minecraft/structure/end_city/base_floor.nbt >/dev/null 2>&1; then
    unzip -o -q "$j" 'data/minecraft/structure/end_city/*' -d /tmp/ec
  fi
done
python3 .github/probe/nbt_layout.py /tmp/ec/data/minecraft/structure/end_city/*.nbt
