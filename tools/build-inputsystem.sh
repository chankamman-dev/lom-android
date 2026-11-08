#!/usr/bin/env bash
# Recompiles Input System 1.5.1 for Android. The depot's copy was built for Windows, so everything
# behind UNITY_ANDROID -- the gamepad layouts and the sensor devices -- is missing from it.
set -euo pipefail

WORK=/e/android-recompile-work/lom-android-work
SRC=$WORK/pkg/package/InputSystem
STAGE=$WORK/stage
OUT=$WORK/inputsystem
CSC='C:\Program Files\dotnet\sdk\8.0.422\Roslyn\bincore\csc.dll'
mkdir -p "$OUT"
RSP="$OUT/build.rsp"

{
    echo '-nologo'
    echo '-noconfig'
    echo '-nostdlib+'
    echo '-target:library'
    echo '-optimize+'
    echo '-unsafe+'
    echo '-debug-'
    echo '-deterministic'
    echo '-langversion:8.0'
    echo '-nowarn:0169,0414,0649,0618,0067,1701,1702,CS8632'
    echo "-out:$(cygpath -w "$OUT/Unity.InputSystem.dll")"
    for d in UNITY_ANDROID PLATFORM_ANDROID UNITY_ANDROID_API ENABLE_INPUT_SYSTEM ENABLE_IL2CPP ENABLE_UNITYWEBREQUEST \
        NET_STANDARD_2_0 NET_STANDARD UNITY_2020_3_49 UNITY_2020_3 UNITY_2020 \
        UNITY_INPUT_SYSTEM_ENABLE_UI UNITY_INPUT_SYSTEM_ENABLE_PHYSICS UNITY_INPUT_SYSTEM_ENABLE_PHYSICS2D \
        UNITY_INPUT_SYSTEM_ENABLE_XR UNITY_INPUT_SYSTEM_ENABLE_VR; do
        echo "-define:$d"
    done
    for y in 5_3 5_4 5_5 5_6 2017_1 2017_2 2017_3 2017_4 2018_1 2018_2 2018_3 2018_4 2019_1 2019_2 2019_3 2019_4 2020_1 2020_2 2020_3; do
        echo "-define:UNITY_${y}_OR_NEWER"
    done
    for f in "$STAGE"/*.dll; do
        [ "$(basename "$f")" = Unity.InputSystem.dll ] && continue
        echo "-r:$(cygpath -w "$f")"
    done
    find "$SRC" -name '*.cs' -exec cygpath -w {} \; | sed 's/^/"/; s/$/"/'
} > "$RSP"

dotnet "$CSC" "@$(cygpath -w "$RSP")" 2>&1 | grep -E 'error|错误' | sed -E 's/^.*InputSystem\\//' | cut -c1-170 | sort | uniq | head -30
ls -la "$OUT/Unity.InputSystem.dll"
