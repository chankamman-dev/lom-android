#!/usr/bin/env bash
# Starts the port on the lime-sim emulator and captures its log and main-screen shot.
# usage: run-emu.sh <log file> <seconds> [display id]
export MSYS_NO_PATHCONV=1
A="/h/android-emu/sdk/platform-tools/adb.exe"
S="-s ${SERIAL:-emulator-5600}"
L=${1:-/e/android-recompile-work/lom-android-work/emu-run.log}
T=${2:-40}
DISP=${3:-0}
"$A" $S shell am force-stop dev.kmlom.mortal
"$A" $S logcat -c
# UnityPlayerActivity is not exported (the launcher does the cloud sync first); a root shell may
# still start it directly, which skips the sign-in screen on the signed-out emulator.
"$A" $S root >/dev/null; "$A" $S wait-for-device
"$A" $S shell am start --display "$DISP" -n dev.kmlom.mortal/com.unity3d.player.UnityPlayerActivity | tail -1
sleep "$T"
P=$("$A" $S shell pidof dev.kmlom.mortal | tr -d '\r')
echo "pid=[$P]"
"$A" $S logcat -d -v threadtime > "$L"
"$A" $S exec-out screencap -p > "${L%.log}.png"
