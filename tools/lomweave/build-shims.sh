#!/usr/bin/env bash
# 编译 0Harmony / BepInEx.Core / BepInEx.Unity.Mono 替身。引用 Unity 的 unityaot 基础库（il2cpp 用的就是这套）
# 和 stage 里的 UnityEngine 模块，保证替身里只用到 IL2CPP 下存在的接口。
set -euo pipefail
T="$(cd "$(dirname "$0")" && pwd)"
W='E:\android-recompile-work\lom-android-work'
AOT="$W\\unity\\Editor\\Data\\MonoBleedingEdge\\lib\\mono\\unityaot"
STAGE="${STAGE:-$W\\stage}"
OUT="${OUT:-$W\\mod\\shims}"
mkdir -p "$(cygpath -u "$OUT")"
SDK="$(dotnet --list-sdks | tail -1 | sed -E 's/^([^ ]+) \[(.*)\]$/\2\\\1/')"
CSC="$SDK\\Roslyn\\bincore\\csc.dll"
base=(-nologo -noconfig -nostdlib+ -target:library -langversion:9 -optimize+ -deterministic -nowarn:CS1701,CS1702
  "-r:$AOT\\mscorlib.dll" "-r:$AOT\\System.dll" "-r:$AOT\\System.Core.dll"
  "-r:$STAGE\\UnityEngine.CoreModule.dll" "-r:$STAGE\\UnityEngine.InputLegacyModule.dll" "-r:$STAGE\\UnityEngine.AndroidJNIModule.dll")
dotnet "$CSC" "${base[@]}" "-out:$OUT\\0Harmony.dll" "$(cygpath -w "$T/shims/HarmonyLib.cs")"
dotnet "$CSC" "${base[@]}" "-out:$OUT\\BepInEx.Core.dll" "$(cygpath -w "$T/shims/BepInEx.Core.cs")"
# 随时存档核对要读的程序集作为资源嵌进启动替身（先跑 prepare-game-managed.sh）
RES=()
for f in /e/android-recompile-work/lom-android-work/mod/game-managed/*.dll; do RES+=("-resource:$(cygpath -w "$f"),LomGameManaged/$(basename "$f")"); done
# 简繁对照表（lomweave cjk-table 在 Windows 上导出）
for f in /e/android-recompile-work/lom-android-work/mod/cjk/*.tsv; do RES+=("-resource:$(cygpath -w "$f"),LomCjk/$(basename "$f")"); done
dotnet "$CSC" "${base[@]}" "${RES[@]}" "-r:$OUT\\BepInEx.Core.dll" "-out:$OUT\\BepInEx.Unity.Mono.dll" "$(cygpath -w "$T/shims/BepInEx.Unity.Mono.cs")"
ls -la "$(cygpath -u "$OUT")"
