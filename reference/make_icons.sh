#!/bin/bash
# Launcher icons, from one source.
#
# Horizon OS draws the library tile from the APK's launcher icon, so an app with
# no android:icon gets the blank Android default - which is what "sideloaded app
# with no name and no picture" looks like in Unknown Sources.
#
# docs/images/app-icon.png is the single source (512x512). Everything under
# res/mipmap-* is generated from it and should never be edited by hand.
set -euo pipefail
cd "$(dirname "$0")/.."
src=docs/images/app-icon.png
res=android/app/src/main/res

for pair in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
    d=${pair%%:*}; px=${pair##*:}
    mkdir -p "$res/mipmap-$d"
    sips -s format png -z "$px" "$px" "$src" --out "$res/mipmap-$d/ic_launcher.png" >/dev/null
    echo "  mipmap-$d/ic_launcher.png  ${px}x${px}"
done
