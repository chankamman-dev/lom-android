#!/usr/bin/env bash
# Compiles the port's own runtime code. Referenced against the Android Input System built next door.
set -euo pipefail

WORK=/e/android-recompile-work/lom-android-work
HERE="$(cd "$(dirname "$0")" && pwd)"
CSC='C:\Program Files\dotnet\sdk\8.0.422\Roslyn\bincore\csc.dll'
OUT=$WORK/extra
mkdir -p "$OUT"
cp "$WORK/inputsystem/Unity.InputSystem.dll" "$OUT/"
RSP="$OUT/lomandroid.rsp"
{
    echo '-nologo'
    echo '-noconfig'
    echo '-nostdlib+'
    echo '-target:library'
    echo '-optimize+'
    echo '-debug-'
    echo '-langversion:8.0'
    echo "-out:$(cygpath -w "$OUT/LomAndroid.dll")"
    for f in "$WORK"/stage/*.dll; do
        case "$(basename "$f")" in LomAndroid.dll|Unity.InputSystem.dll) continue ;; esac
        echo "-r:$(cygpath -w "$f")"
    done
    echo "-r:$(cygpath -w "$OUT/Unity.InputSystem.dll")"
    find "$HERE/lomandroid" -name '*.cs' -exec cygpath -w {} \; | sed 's/^/"/; s/$/"/'
} > "$RSP"
if dotnet "$CSC" "@$(cygpath -w "$RSP")" > "$OUT/csc.log" 2>&1; then :; else grep -E "error" "$OUT/csc.log" | cut -c1-200 | sort -u | head -20; exit 1; fi
ls -la "$OUT/LomAndroid.dll"
