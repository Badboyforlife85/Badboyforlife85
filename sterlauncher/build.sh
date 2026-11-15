#!/bin/sh
set -e
BT=/root/sdk/build-tools/35.0.0; AJ=/root/sdk/platforms/android-33/android.jar
cd "$(dirname "$0")"; rm -rf build; mkdir -p build/cls build/dex
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -I $AJ --manifest AndroidManifest.xml -o build/base.apk build/res.zip --java build/gen
javac -nowarn --release 8 -classpath $AJ -d build/cls $(find src build/gen -name '*.java') 2>&1 | grep -v "^Picked\|^Note" || true
(cd build/cls && jar cf ../classes.jar .)
$BT/d8 --lib $AJ --min-api 26 --output build/dex build/classes.jar 2>&1 | grep -v Picked || true
cp build/base.apk build/u.apk; (cd build/dex && zip -q ../u.apk classes.dex)
$BT/zipalign -f 4 build/u.apk build/a.apk
$BT/apksigner sign --ks ster.jks --ks-pass pass:sterpass --out /home/claude/SterLauncher.apk build/a.apk 2>/dev/null
$BT/apksigner verify /home/claude/SterLauncher.apk 2>/dev/null && echo SIGNED
$BT/aapt2 dump badging /home/claude/SterLauncher.apk 2>/dev/null | head -1
$BT/dexdump /home/claude/SterLauncher.apk 2>/dev/null | grep "Class descriptor" | grep -v Lambda
