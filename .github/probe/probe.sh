#!/usr/bin/env bash
# Internal helper: prints public API signatures of selected Minecraft classes (javap, no code)
# and the worldgen data, so the mod can be written against the exact 26.2 API.
set -u
JARS=$(find seedcracker/.gradle/loom-cache .gradle/loom-cache -name '*.jar' 2>/dev/null | grep -E 'minecraft-(common|clientOnly)-[^/]*\.jar$' | grep -v sources | sort -u)
echo "== jars"; echo "$JARS"
CP=$(echo "$JARS" | tr '\n' ':')
FAPI=$(find ~/.gradle -name 'fabric-*.jar' 2>/dev/null | grep -v sources | tr '\n' ':')
grep -v '^#' .github/probe/classes.txt | while IFS='|' read -r c pat; do
  [ -z "$c" ] && continue
  echo "=================== $c"
  if [ -z "$pat" ]; then
    javap -public -cp "$CP$FAPI" "$c" 2>&1 | grep -v 'toString()\|hashCode()\|equals(java.lang.Object)' | head -250
  else
    javap -public -cp "$CP$FAPI" "$c" 2>&1 | grep -E "class |interface |$pat" | head -120
  fi
done
grep -v '^#' .github/probe/privates.txt | while read -r c; do
  [ -z "$c" ] && continue
  echo "=================== PRIVATE $c"
  javap -p -cp "$CP$FAPI" "$c" 2>&1 | grep -v 'lambda\$' | head -150
done
echo "== packages"
for p in $(grep -v '^#' .github/probe/packages.txt); do
  for j in $JARS; do
    unzip -l "$j" | awk '{print $4}' | grep "^$p/[^/]*\.class$" | sed 's|\.class$||; s|/|.|g' | tr '\n' ' '
  done
  echo
done
mkdir -p /tmp/mc
for j in $JARS; do unzip -o -q "$j" 'data/*' -d /tmp/mc 2>/dev/null; done
python3 .github/probe/data.py /tmp/mc
