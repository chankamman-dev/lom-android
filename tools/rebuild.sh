#!/usr/bin/env bash
# Port code -> staged assemblies -> libil2cpp.so -> APK -> Thor.
set -euo pipefail

T="$(cd "$(dirname "$0")" && pwd)"
W='E:\android-recompile-work\lom-android-work'
U="$W\\unity\\Editor\\Data"
export PATH="$PATH:/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot/bin"

bash "$T/build-lomandroid.sh"
dotnet "$T/lomtool/bin/Release/net8.0/lomtool.dll" stage \
    'H:\SteamLibrary\steamapps\common\LegendOfMortal\Mortal_Data\Managed' \
    "$U\\MonoBleedingEdge\\lib\\mono\\unityaot" \
    "$U\\PlaybackEngines\\AndroidPlayer\\Variations\\il2cpp\\Managed" \
    "$W\\stage" "$W\\extra" | tail -3 | cut -c1-120
bash "$T/build-il2cpp.sh"
bash "$T/build-apk.sh" 2>&1 | grep -vE 'Expected stack map|^Warning in' | tail -2 | cut -c1-140
[ "${NOINSTALL:-}" = 1 ] && exit 0
MSYS_NO_PATHCONV=1 /h/android-emu/sdk/platform-tools/adb.exe -s "${ANDROID_SERIAL:?set ANDROID_SERIAL to the device serial}" install -r -g "$W\\apk\\lom-android.apk" 2>&1 | tail -1
