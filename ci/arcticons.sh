#!/usr/bin/env bash
# Bundles the free Arcticons line icons (CC BY-SA 4.0, github.com/Arcticons-Team/Arcticons) into the app.
# Output: app/src/main/assets/arcticons/{appfilter.xml, <name>.png}
set -u
OUT=app/src/main/assets/arcticons
rm -rf "$OUT" /tmp/arc && mkdir -p "$OUT"
git clone -q --depth 1 https://github.com/Arcticons-Team/Arcticons /tmp/arc || exit 0
AF=$(find /tmp/arc -name appfilter.xml -size +100k | head -1)
[ -z "$AF" ] && AF=$(find /tmp/arc -name appfilter.xml | head -1)
[ -z "$AF" ] && { echo "no appfilter"; exit 0; }
cp "$AF" "$OUT/appfilter.xml"
# the folder with the most SVGs holds the icons
DIR=$(find /tmp/arc -name '*.svg' -printf '%h\n' | sort | uniq -c | sort -rn | head -1 | awk '{print $2}')
echo "appfilter: $AF  icons: $DIR ($(ls "$DIR" | wc -l))"
# only icons the appfilter uses
grep -o 'drawable="[^"]*"' "$OUT/appfilter.xml" | cut -d'"' -f2 | sort -u > /tmp/arc/used.txt
cat /tmp/arc/used.txt | xargs -P 8 -I{} sh -c '[ -f "'"$DIR"'/{}.svg" ] && rsvg-convert -w 96 -h 96 "'"$DIR"'/{}.svg" -o "'"$OUT"'/{}.png" 2>/dev/null' || true
echo "bundled $(ls "$OUT" | wc -l) files, $(du -sh "$OUT" | cut -f1)"
