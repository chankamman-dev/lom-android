#!/usr/bin/env bash
# 带咖啡伴侣的安卓版：沿用普通构建的 stage → 织入 → il2cpp（mod/il2cpp）→ APK 和 OBB（mod/apk）。
# 前提：普通构建的 stage 是最新的（tools/rebuild.sh 跑过 stage 那一步）。
# 换原 Mod 版本：把新的 LOMAssistant.dll、Newtonsoft.Json.dll、Mono.Cecil.dll 放进一个目录，MODDIR 指向它。
set -euo pipefail
T="$(cd "$(dirname "$0")" && pwd)"
W=/e/android-recompile-work/lom-android-work
MODDIR="${MODDIR:-E:\\android-recompile-work\\lom-android-work\\mod\\lomassistant-3.4.0}"
AOT='E:\android-recompile-work\lom-android-work\unity\Editor\Data\MonoBleedingEdge\lib\mono\unityaot'
# 下屏伴侣的桥接（lom-thor-probe 构建的 LOMThorBridge.dll）；LOM_BRIDGE= 设为空串则不带下屏
export LOM_BRIDGE="${LOM_BRIDGE-G:\\ai liabary\\lom-thor-probe\\artifacts\\LOMThorBridge\\LOMThorBridge.dll}"

dotnet build -c Release "$(cygpath -w "$T")" -o "$(cygpath -w "$W/mod/lomweave-bin")" 2>&1 | grep -E 'rror CS|Build FAILED' && exit 1 || true
bash "$T/prepare-game-managed.sh" > /dev/null
bash "$T/build-shims.sh" > /dev/null
dotnet "$(cygpath -w "$W/mod/lomweave-bin/lomweave.dll")" weave 'E:\android-recompile-work\lom-android-work\stage' "$MODDIR" 'E:\android-recompile-work\lom-android-work\mod\shims' "$AOT" 'E:\android-recompile-work\lom-android-work\mod\stage'
bash "$T/build-il2cpp-mod.sh"
OUT=$W/mod/apk IL2CPP=$W/mod/il2cpp \
EXTRA_ASSEMBLIES="LOMAssistant.dll 0Harmony.dll BepInEx.Core.dll BepInEx.Unity.Mono.dll Newtonsoft.Json.dll Mono.Cecil.dll${LOM_BRIDGE:+ LOMThorBridge.dll}" \
RUNTIME_INIT="BepInEx.Unity.Mono BepInEx.Unity.Mono LomBoot Start" \
    bash "$T/../build-apk.sh" 2>&1 | grep -vE 'Expected stack map|^Warning in' | tail -4
