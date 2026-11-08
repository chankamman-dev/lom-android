export MSYS_NO_PATHCONV=1
A="/h/android-emu/sdk/platform-tools/adb.exe"; S="-s ${ANDROID_SERIAL:?set ANDROID_SERIAL to the device serial}"; L=${1:-/e/android-recompile-work/lom-android-work/log.txt}; T=${2:-40}
"$A" $S shell am force-stop dev.kmlom.mortal
"$A" $S logcat -v threadtime -T 1 > $L 2>&1 &
LP=$!
sleep 1
"$A" $S shell am start --display 4 -n dev.kmlom.mortal/dev.kmlom.cloud.LauncherActivity 2>&1 | tail -1 | cut -c1-100
sleep $T
P=$("$A" $S shell pidof dev.kmlom.mortal | tr -d '\r')
kill $LP 2>/dev/null
echo "pid=[$P]"
