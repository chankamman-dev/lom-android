#!/usr/bin/env bash
# 织入后的程序集 -> C++ -> libil2cpp.so。参数与 tools/build-il2cpp.sh 相同，只是目录都换到 mod/ 下，
# 不影响不带 Mod 的构建和它的增量缓存。
set -uo pipefail

M=/e/android-recompile-work/lom-android-work/mod
U='E:\android-recompile-work\lom-android-work\unity\Editor\Data'
mkdir -p "$M/il2cpp"
cd "$M/il2cpp"
export ANDROID_NDK_ROOT='E:\android-recompile-work\lom-android-work\ndk\android-ndk-r19c'
"$U\\il2cpp\\build\\deploy\\netcoreapp3.1\\il2cpp.exe" --convert-to-cpp --compile-cpp --libil2cpp-static \
    --platform=Android --architecture=ARM64 --configuration=Release --dotnetprofile=unityaot \
    --emit-null-checks --enable-array-bounds-check --incremental-g-c-time-slice=3 \
    --directory='E:\android-recompile-work\lom-android-work\mod\stage' \
    --generatedcppdir='E:\android-recompile-work\lom-android-work\mod\il2cpp\cpp' \
    --data-folder='E:\android-recompile-work\lom-android-work\mod\il2cpp\data' \
    --cachedirectory='E:\android-recompile-work\lom-android-work\mod\il2cpp\cache' \
    --outputpath='E:\android-recompile-work\lom-android-work\mod\il2cpp\out\libil2cpp.so' \
    --tool-chain-path='E:\android-recompile-work\lom-android-work\ndk\android-ndk-r19c' \
    --jobs=14 > build.log 2>&1
echo "il2cpp exit $?"
grep -nE ' error |Error:|undefined reference' build.log | cut -c1-220 | head -12
ls -la out/libil2cpp.so
