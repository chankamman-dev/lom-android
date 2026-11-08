#!/usr/bin/env bash
# Assembles dev.kmlom.mortal from the il2cpp output and the retargeted player data.
set -euo pipefail

WORK=/e/android-recompile-work/lom-android-work
AP=$WORK/unity/Editor/Data/PlaybackEngines/AndroidPlayer
VAR=$AP/Variations/il2cpp/${VARIATION:-Release}
SDK="${ANDROID_SDK_ROOT:-$HOME/Documents/Codex/2026-07-19/z/work/android-toolchain/sdk}"
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-35/android.jar"
OUT=${OUT:-$WORK/apk}
# il2cpp 输出目录；带咖啡伴侣的构建（lomweave/build-mod.sh）用 $WORK/mod/il2cpp
IL2CPP=${IL2CPP:-$WORK/il2cpp}
# Retargeted player data and bundles live on F: (E: is slow under sustained writes).
DATA=${DATA:-/e/android-recompile-work/lom-android-work/f-data/data2}
BUNDLES=${BUNDLES:-/e/android-recompile-work/lom-android-work/f-data/bundles-lz4-2}
PKG=dev.kmlom.mortal
HERE="$(cd "$(dirname "$0")" && pwd)"
Z='/c/Program Files/7-Zip/7z.exe'

rm -rf "$OUT/res-flat" "$OUT/gen" "$OUT/java" "$OUT/root" "$OUT/obbroot"
mkdir -p "$OUT/res-flat" "$OUT/gen" "$OUT/java" "$OUT/root/lib/arm64-v8a" "$OUT/root/assets" "$OUT/obbroot/assets"
OBB="$OUT/main.1.$PKG.obb"
# The engine opens the OBB only when its build id matches the one in the manifest.
GUID=$(sha1sum "$IL2CPP/data/Metadata/global-metadata.dat" | cut -c1-32)
sed "s|<!-- BUILD-ID -->|<meta-data android:name=\"unity.build-id\" android:value=\"$GUID\" />|" "$HERE/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"

# Resources and manifest. The app icon (art/icon, made by tools/pctest/LomIcon.java from the game's
# calligraphy logo) replaces Unity's.
rm -rf "$OUT/res"
cp -r "$AP/Apk/res" "$OUT/res"
rm -f "$OUT/res/mipmap-mdpi/"*.png "$OUT/res/mipmap-anydpi-v26/"*.xml
cp -r "$HERE/../art/icon/." "$OUT/res/"
cp -r "$HERE/res-extra/." "$OUT/res/"
"$BT/aapt2.exe" compile --dir "$(cygpath -w "$OUT/res")" -o "$(cygpath -w "$OUT/res-flat/res.zip")"
"$BT/aapt2.exe" link -o "$(cygpath -w "$OUT/base.apk")" -I "$(cygpath -w "$JAR")" \
    --manifest "$(cygpath -w "$OUT/AndroidManifest.xml")" --java "$(cygpath -w "$OUT/gen")" \
    --custom-package com.unity3d.player --min-sdk-version 26 --target-sdk-version 30 --version-code 1 --version-name release_1.0.6001.0 \
    "$(cygpath -w "$OUT/res-flat/res.zip")"

# Unity's activity ships as source only.
javac -nowarn -source 8 -target 8 -cp "$(cygpath -w "$VAR/Classes/classes.jar");$(cygpath -w "$JAR")" -d "$OUT/java" \
    "$(cygpath -w "$AP/Source/com/unity3d/player/UnityPlayerActivity.java")" $(find "$OUT/gen" -name '*.java' -exec cygpath -w {} \;) 2>&1 | grep -v '^warning\|^Note' || true
(cd "$OUT/java" && jar cf ../activity.jar .)
# The lower-screen companion (lom-thor-probe/android) is built in: its activity is compiled as is and
# its pages go to the APK assets root, where it reads them (index.html, app.js, ..., fonts/).
LOWER=${LOWER:-/g/ai liabary/lom-thor-probe/android}
rm -rf "$OUT/lower-java"; mkdir -p "$OUT/lower-java"
# (the path has a space: the sources go through an argument file)
find "$LOWER/src" -name '*.java' -exec cygpath -w {} \; | sed 's/\\/\\\\/g; s/.*/"&"/' > "$OUT/lower-sources.txt"
javac -nowarn -encoding UTF-8 -source 8 -target 8 -cp "$(cygpath -w "$JAR")" -d "$(cygpath -w "$OUT/lower-java")" \
    "@$(cygpath -w "$OUT/lower-sources.txt")" 2>&1 | grep -v '^warning\|^Note' || true
ls "$OUT/lower-java/io/github/chankamman_dev/lomthor/MainActivity.class" > /dev/null || { echo "lower-screen activity did not compile" >&2; exit 1; }
(cd "$OUT/lower-java" && jar cf ../lower.jar .)
# The Steam Cloud launcher (cloud/) and everything it depends on are dexed alongside Unity.
CLOUD="$HERE/../cloud/build"
CLOUDJARS=()
for j in "$CLOUD/libs/lom-cloud.jar" "$CLOUD"/deps/*.jar; do CLOUDJARS+=("$(cygpath -w "$j")"); done
cmd.exe //c "$(cygpath -w "$BT/d8.bat")" --release --min-api 26 --lib "$(cygpath -w "$JAR")" \
    --output "$(cygpath -w "$OUT/root")" "$(cygpath -w "$OUT/activity.jar")" "$(cygpath -w "$OUT/lower.jar")" "$(cygpath -w "$VAR/Classes/classes.jar")" "${CLOUDJARS[@]}" \
    2>&1 | grep -vE '^Warning|Missing class|^\s*$' | head -20 || true
ls "$OUT/root"/classes*.dex

# Native libraries.
cp "$VAR/Libs/arm64-v8a/libmain.so" "$VAR/Libs/arm64-v8a/libunity.so" "$OUT/root/lib/arm64-v8a/"
cp "$IL2CPP/out/libil2cpp.so" "$OUT/root/lib/arm64-v8a/"

# The engine reads boot.config before it mounts the OBB, so the APK carries a copy.
mkdir -p "$OUT/root/assets/bin/Data"
cp "$DATA/boot.config" "$OUT/root/assets/bin/Data/"
# Player data, bundles and audio go in the OBB; the APK keeps only code.
D="$OUT/obbroot/assets/bin/Data"
mkdir -p "$D/Managed/Metadata" "$D/Managed/Resources"
cp -r "$DATA/." "$D/"
rm -f "$D/shader-report.txt"
cp "$AP/Data/unity default resources" "$D/unity default resources"
cp "$IL2CPP/data/Metadata/global-metadata.dat" "$D/Managed/Metadata/"
cp "$IL2CPP/data/Resources/"*.dat "$D/Managed/Resources/"
cp -r "$IL2CPP/data/etc" "$D/Managed/etc"
node "$(cygpath -w "$HERE/add-assembly.js")" "$(cygpath -w "$D/ScriptingAssemblies.json")" LomAndroid.dll
# 带咖啡伴侣的构建额外登记程序集和启动方法（默认都为空，不影响普通构建）
for a in ${EXTRA_ASSEMBLIES:-}; do node "$(cygpath -w "$HERE/add-assembly.js")" "$(cygpath -w "$D/ScriptingAssemblies.json")" "$a"; done
if [ -n "${RUNTIME_INIT:-}" ]; then node "$(cygpath -w "$HERE/lomweave/add-runtime-init.js")" "$(cygpath -w "$D/RuntimeInitializeOnLoads.json")" $RUNTIME_INIT; fi
echo "$GUID" > "$D/unity_app_guid"
cp -r "$WORK/data-aa/aa" "$OUT/obbroot/assets/aa"
mkdir -p "$OUT/obbroot/assets/aa/StandaloneWindows64" "$OUT/obbroot/assets/audio"
cp "$BUNDLES"/*.bundle "$OUT/obbroot/assets/aa/StandaloneWindows64/"
cp "$WORK/audio/ogg/"*.ogg "$WORK/audio/audio.tsv" "$WORK/audio/gain.tsv" "$WORK/audio/events.tsv" "$OUT/obbroot/assets/audio/"
: > "$OUT/obbroot/assets/$GUID"

# The port: how the launcher makes this OBB on the device from the Steam download (GameData.java).
# Classifies every OBB file against the Windows depot and checks the device-side conversion on the PC.
GAME=${GAME:-/h/SteamLibrary/steamapps/common/LegendOfMortal}
ACF=${ACF:-/h/SteamLibrary/steamapps/appmanifest_1859910.acf}
MANIFEST=$(awk '/"1859911"/{f=1} f && /"manifest"/{gsub(/"/,"",$2); print $2; exit}' "$ACF")
BUILDID=$(awk '/"buildid"/{gsub(/"/,"",$2); print $2; exit}' "$ACF")
DEPOTMAN="/f/game software/depotcache/1859911_$MANIFEST.manifest"
[ -f "$DEPOTMAN" ] || { echo "depot manifest $DEPOTMAN missing" >&2; exit 1; }
PT=$WORK/porttool-bin
rm -rf "$PT"; mkdir -p "$PT"
DEPS=$(for j in "$CLOUD"/deps/*.jar; do cygpath -w "$j"; done | tr '\n' ';')
javac -nowarn -encoding UTF-8 -cp "$DEPS" -d "$(cygpath -w "$PT")" \
    "$(cygpath -w "$HERE/../cloud/src/main/java/dev/kmlom/cloud/BundleStamp.java")" "$(cygpath -w "$HERE/../cloud/src/main/java/dev/kmlom/cloud/PortPatch.java")" \
    "$(cygpath -w "$HERE/pctest/StampCheck.java")" "$(cygpath -w "$HERE/porttool/PortTool.java")"
java -Xmx6g -cp "$(cygpath -w "$PT");$DEPS" dev.kmlom.cloud.PortTool "$(cygpath -w "$OUT/obbroot")" "$(cygpath -w "$GAME")" \
    "$(cygpath -w "$DEPOTMAN")" "$(cygpath -w "$OUT/port")" "$(cygpath -w "$WORK/port-cache")" "$BUILDID"
mkdir -p "$OUT/root/assets/port"
cp -r "$OUT/port/." "$OUT/root/assets/port/"
cp "$HERE/../art/hero-src.jpg" "$OUT/root/assets/port/hero.jpg"
cp "$HERE/../art/logo.png" "$OUT/root/assets/port/logo.png"
# What the launcher may offer (Features.java): 咖啡伴侣 and the lower-screen bridge, when woven in.
: > "$OUT/root/assets/port/features.txt"
case " ${EXTRA_ASSEMBLIES:-} " in *" LOMAssistant.dll "*) echo companion >> "$OUT/root/assets/port/features.txt" ;; esac
case " ${EXTRA_ASSEMBLIES:-} " in *" LOMThorBridge.dll "*) echo bridge >> "$OUT/root/assets/port/features.txt"
    cp -r "$LOWER/assets/." "$OUT/root/assets/"; echo lower >> "$OUT/root/assets/port/features.txt" ;; esac

# The developer OBB (for adb push); the launcher makes the same thing on the device. NO_OBB=1 skips it.
if [ -z "${NO_OBB:-}" ]; then
rm -f "$OUT/obb-unaligned.zip"
(cd "$OUT/obbroot" && "$Z" a -tzip -mx=0 -bso0 -bsp0 ../obb-unaligned.zip .)
"$BT/zipalign.exe" -f 4 "$(cygpath -w "$OUT/obb-unaligned.zip")" "$(cygpath -w "$OBB")"
ls -la "$OBB"
fi

# Everything is stored: the engine maps these out of the zip.
cp "$OUT/base.apk" "$OUT/unaligned.apk"
(cd "$OUT/root" && "$Z" a -tzip -mx=0 -bso0 -bsp0 ../unaligned.apk .)
"$BT/zipalign.exe" -f -p 4 "$(cygpath -w "$OUT/unaligned.apk")" "$(cygpath -w "$OUT/aligned.apk")"

KS="$WORK/lom-debug.keystore"
[ -f "$KS" ] || keytool -genkeypair -keystore "$(cygpath -w "$KS")" -storepass lomdebug -keypass lomdebug -alias lom \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=lom-android" 2>/dev/null
cmd.exe //c "$(cygpath -w "$BT/apksigner.bat")" sign --ks "$(cygpath -w "$KS")" --ks-pass pass:lomdebug --key-pass pass:lomdebug \
    --v4-signing-enabled false --out "$(cygpath -w "$OUT/lom-android.apk")" "$(cygpath -w "$OUT/aligned.apk")"
ls -la "$OUT/lom-android.apk"
