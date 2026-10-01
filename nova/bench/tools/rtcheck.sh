#!/bin/bash
# rtcheck.sh <path/of/Class.java relative to src root> <source dir>: round-trip check of a decompiled class.
# Decompiles Forge's original class files standalone, compiles <source dir>/<path> with -g, decompiles that the
# same way, and prints the normalized diff (imports, whitespace, labels ignored).
set -e
S="C:/Users/Oron/AppData/Local/Temp/claude/C--Users-Oron-Downloads-Projects-MTG-Forge/f2bf502d-049c-40a4-bf90-a197e539bbc6/scratchpad"
FF="C:/Program Files/Android/Android Studio/plugins/java-decompiler/lib/java-decompiler.jar"
JAR="C:/Users/Oron/Downloads/Projects/MTG Forge/forge-gui-desktop-2.0.15-SNAPSHOT-jar-with-dependencies.jar"
REL="$1"; SRC="$2"
CLS="${REL%.java}"; NAME=$(basename "$CLS"); PKG=$(dirname "$CLS")
W="$S/rt_$NAME"; rm -rf "$W"; mkdir -p "$W/o/in" "$W/o/out" "$W/n/cls" "$W/n/in" "$W/n/out"
(cd "$W/o/in" && unzip -o -q "$JAR" "$CLS.class" "$CLS\$*.class" 2>/dev/null || unzip -o -q "$JAR" "$CLS.class")
(cd "$W/o/in" && jar cf ../c.jar .)
(cd "$W/o" && java -cp "$FF" org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler -dgs=1 -rsy=1 c.jar out/ >/dev/null 2>&1 && cd out && unzip -o -q c.jar)
javac -g --release 17 -encoding UTF-8 -nowarn -Xlint:none -cp "$JAR" -sourcepath "$SRC" -d "$W/n/cls" "$SRC/$REL" 2>&1 | grep -A2 "error" || true
mkdir -p "$W/n/in/$PKG" && cp "$W/n/cls/$PKG/$NAME.class" "$W/n/in/$PKG/" && (cp "$W/n/cls/$PKG/$NAME\$"*.class "$W/n/in/$PKG/" 2>/dev/null || true)
(cd "$W/n/in" && jar cf ../c.jar .)
(cd "$W/n" && java -cp "$FF" org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler -dgs=1 -rsy=1 c.jar out/ >/dev/null 2>&1 && cd out && unzip -o -q c.jar)
python "$S/rtnorm.py" "$W/o/out/$REL" > "$W/o.norm"; python "$S/rtnorm.py" "$W/n/out/$REL" > "$W/n.norm"
diff "$W/o.norm" "$W/n.norm" > "$W/diff.txt" || true
echo "$REL: $(grep -c '^[<>]' "$W/diff.txt") normalized lines differ (see $W/diff.txt)"
