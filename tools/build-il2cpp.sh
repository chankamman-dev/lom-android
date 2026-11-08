#!/usr/bin/env bash
# IL -> C++ -> libil2cpp.so. Unchanged translation units come out of the cache.
set -uo pipefail

WORK=/e/android-recompile-work/lom-android-work
U='E:\android-recompile-work\lom-android-work\unity\Editor\Data'
cd "$WORK/il2cpp"
export ANDROID_NDK_ROOT='E:\android-recompile-work\lom-android-work\ndk\android-ndk-r19c'
"$U\\il2cpp\\build\\deploy\\netcoreapp3.1\\il2cpp.exe" --convert-to-cpp --compile-cpp --libil2cpp-static \
    --platform=Android --architecture=ARM64 --configuration=Release --dotnetprofile=unityaot \
    --emit-null-checks --enable-array-bounds-check --incremental-g-c-time-slice=3 \
    --directory='E:\android-recompile-work\lom-android-work\stage' \
    --generatedcppdir='E:\android-recompile-work\lom-android-work\il2cpp\cpp' \
    --data-folder='E:\android-recompile-work\lom-android-work\il2cpp\data' \
    --cachedirectory='E:\android-recompile-work\lom-android-work\il2cpp\cache' \
    --outputpath='E:\android-recompile-work\lom-android-work\il2cpp\out\libil2cpp.so' \
    --tool-chain-path='E:\android-recompile-work\lom-android-work\ndk\android-ndk-r19c' \
    --jobs=14 > build.log 2>&1
rc=$?
echo "il2cpp exit $rc"
grep -nE ' error |Error:|undefined reference' build.log | cut -c1-220 | head -12
# A failed run leaves the previous libil2cpp.so behind; packaging it with new metadata would crash.
[ $rc -eq 0 ] || exit $rc
ls -la out/libil2cpp.so
