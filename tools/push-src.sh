#!/usr/bin/env bash
# Developer test of the on-device install without Steam: copies the Windows files the port takes from
# Steam (port.txt entries other than "apk") into the app's internal files dir on a rooted emulator,
# where the launcher's hidden "localSource" mode reads them:
#   adb shell am start -n dev.kmlom.mortal/dev.kmlom.cloud.LauncherActivity --es localSource /data/data/dev.kmlom.mortal/files/lom-src
# The app must be installed first (its uid owns the copy). Pushes only files missing or of the wrong
# size; one adb push per file (a tar stream through exec-in dies part-way on the big .resS files).
# usage: push-src.sh <serial> [port.txt]
set -euo pipefail
SERIAL=${1:?serial}
PORT=${2:-/e/android-recompile-work/lom-android-work/apk/port/port.txt}
ADB=${ADB:-/g/AndroidLab/sdk/platform-tools/adb.exe}
GAME=/h/SteamLibrary/steamapps/common/LegendOfMortal
DST=/data/data/dev.kmlom.mortal/files/lom-src
TMP=$(mktemp -d)
export MSYS_NO_PATHCONV=1
A() { "$ADB" -s "$SERIAL" "$@"; }
A root >/dev/null; A wait-for-device
A shell "mkdir -p $DST"
awk -F'\t' '$1=="entry" && $2!="apk" {print $6}' "$PORT" | sort -u | while read -r f; do echo "$f $(stat -c %s "$GAME/$f")"; done | sort > "$TMP/want"
A shell "cd $DST && find . -type f -exec stat -c '%n %s' {} +" | tr -d '\r' | sed 's|^\./||' | sort > "$TMP/have"
comm -23 "$TMP/want" "$TMP/have" | cut -d' ' -f1 > "$TMP/missing"
echo "missing $(wc -l < "$TMP/missing") of $(wc -l < "$TMP/want")"
while read -r f; do
    A shell "mkdir -p '$(dirname "$DST/$f")'"
    A push "$(cygpath -w "$GAME/$f")" "$DST/$f" | tail -1 | cut -c1-140
done < "$TMP/missing"
U=$(A shell "stat -c %U /data/data/dev.kmlom.mortal" | tr -d '\r')
A shell "chown -R $U:$U $DST && restorecon -R $DST" >/dev/null 2>&1 || true
rm -rf "$TMP"
echo "owner $U"
