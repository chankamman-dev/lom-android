#!/usr/bin/env bash
# 随时存档核对要读的 6 个程序集：游戏逻辑的取 PC 原版（未织入），mscorlib / UnityEngine.CoreModule 取安卓实际运行的那份。
set -euo pipefail
GAME="${GAME:-/h/SteamLibrary/steamapps/common/LegendOfMortal/Mortal_Data/Managed}"
AOT=/e/android-recompile-work/lom-android-work/unity/Editor/Data/MonoBleedingEdge/lib/mono/unityaot
STAGE="${STAGE:-/e/android-recompile-work/lom-android-work/stage}"
OUT=/e/android-recompile-work/lom-android-work/mod/game-managed
mkdir -p "$OUT"
for f in Mortal.Core.dll Mortal.Story.dll Mortal.Free.dll Fungus.dll; do cp "$GAME/$f" "$OUT/"; done
cp "$AOT/mscorlib.dll" "$OUT/"
cp "$STAGE/UnityEngine.CoreModule.dll" "$OUT/"
ls -la "$OUT"
