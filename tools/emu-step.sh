#!/usr/bin/env bash
# One test step on the emulator: run input commands, wait, then save a screenshot and the errors
# logged meanwhile.  usage: emu-step.sh <name> "<adb shell commands>" [wait seconds]
export MSYS_NO_PATHCONV=1
A="/h/android-emu/sdk/platform-tools/adb.exe"
S="-s ${SERIAL:-emulator-5600}"
OUT=/e/android-recompile-work/lom-android-work/steps
mkdir -p "$OUT"
"$A" $S logcat -c
[ -n "$2" ] && "$A" $S shell "$2"
"$A" $S shell "sleep ${3:-5}"
"$A" $S exec-out screencap -p > "$OUT/$1.png"
"$A" $S logcat -d -v threadtime > "$OUT/$1.log"
P=$("$A" $S shell pidof dev.kmlom.mortal | tr -d '\r')
echo "step $1 pid=[$P]"
grep -E ' (Unity|CRASH|AndroidRuntime|DEBUG) *:' "$OUT/$1.log" | tr -d '\r' | cut -c44-200 \
    | grep -iE 'error|exception|fail|could not|unable|not found|crash|fatal|signal' \
    | grep -vE '^\s*at |Steamworks|FileNotFoundException: No item' | sort | uniq -c | sort -rn | head -${4:-8}
grep -E 'LomAudio\] (load failed|no media)' "$OUT/$1.log" | tr -d '\r' | cut -c44-140 | sort | uniq -c | head -5
