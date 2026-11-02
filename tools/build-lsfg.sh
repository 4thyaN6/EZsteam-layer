#!/bin/sh
# Build the LSFG frame generation Vulkan layer (lsfg-vk 1.0.0 lineage, MIT) for glibc aarch64,
# in the same Debian release as the rootfs. Only the layer is built and shipped; the shaders come
# at run time from the user's own Lossless.dll (Lossless Scaling, Steam app 993090, bought by the
# user and installed by their Steam client), which is never shipped.
#   tools/build-lsfg.sh <out dir>   (run inside debian:trixie arm64)
# Source: GameNative/lsfg-vk-android (MIT, fork of lsfg-vk 1.0.0 with Android patches that are
# compiled out on glibc), pinned to a commit; its submodules pe-parse (MIT), toml11 (MIT),
# volk (MIT), dxbc (zlib). Later lsfg-vk versions are GPLv3 / CC BY-NC-ND and are not used.
# LSFG 프레임 생성 Vulkan 레이어(lsfg-vk 1.0.0 계열, MIT)를 rootfs와 같은 데비안 릴리스에서 glibc
# aarch64용으로 빌드함. 레이어만 빌드·배포하고, 셰이더는 실행할 때 사용자 자신의 Lossless.dll(Lossless
# Scaling, Steam 앱 993090. 사용자가 사서 자기 Steam 클라이언트로 설치)에서 꺼냄. 그 파일은 배포하지 않음.
#   tools/build-lsfg.sh <출력 폴더>   (debian:trixie arm64 안에서 실행)
# 출처: GameNative/lsfg-vk-android(MIT, lsfg-vk 1.0.0 포크. 안드로이드 패치는 glibc에서는 빠짐)를 커밋으로
# 고정. 하위 모듈 pe-parse(MIT), toml11(MIT), volk(MIT), dxbc(zlib). 이후 lsfg-vk 버전은 GPLv3 /
# CC BY-NC-ND라 쓰지 않음
set -eu
OUT=${1:?out dir}
REPO=https://github.com/GameNative/lsfg-vk-android
COMMIT=d33caceb5e26cc6773834c00b4eb570ad4906272
# Vulkan headers matching the layer's api_version (Debian's are a little older)
# 레이어 api_version에 맞는 Vulkan 헤더(데비안 것은 조금 오래됨)
VKH_TAG=v1.4.313
# dxbc (DXVK's shader translator as a library, zlib): the submodule's own repository
# (github.com/PancakeTAS/dxbc) is gone (404, CI "could not read Username", 2026-11-02). A copy of
# the same submodule commit (78ab59a8, pinned by both lsfg-vk-android lineages) is kept as plain files
# in AlkaidLab/lsfg-vk-android-legacy; that commit is used only when the original cannot be cloned
# dxbc(DXVK의 셰이더 변환기를 라이브러리로 뺀 것, zlib): 하위 모듈의 원래 저장소(github.com/PancakeTAS/dxbc)가
# 없어졌음(404, CI "could not read Username", 2026-11-02). 같은 하위 모듈 커밋(78ab59a8, lsfg-vk-android 두
# 계열이 모두 고정한 것)의 사본이 AlkaidLab/lsfg-vk-android-legacy에 일반 파일로 있음. 원래 것을 받을 수 없을 때만
# 그 커밋을 씀
DXBC_COMMIT=78ab59a8aaeb43cd1b0a5e91ba86722433a10b78
DXBC_COPY_REPO=https://github.com/AlkaidLab/lsfg-vk-android-legacy
DXBC_COPY_COMMIT=793c4cec518ac7d94234dffd1487e5b06c08c005
DXBC_FROM="submodule"

export DEBIAN_FRONTEND=noninteractive
apt-get update -q
# clang, as upstream builds it: the layer's entry points carry visibility("default") in a place
# GCC ignores ("attributes are not permitted in this position"), so with g++ they stayed hidden and
# the loader said "Failed to find 'layer_vkGetInstanceProcAddr'" (device log 2026-10-25)
# 원본처럼 clang으로 빌드: 레이어 진입 함수의 visibility("default")가 GCC가 무시하는 자리에 있어("attributes
# are not permitted in this position") g++로는 숨겨진 채였고 로더가 "Failed to find
# 'layer_vkGetInstanceProcAddr'"를 냈음(기기 로그 2026-10-25)
apt-get install -y -q --no-install-recommends ca-certificates git cmake ninja-build clang binutils file >/dev/null

WORK=$(mktemp -d)
git -c advice.detachedHead=false clone -q "$REPO" "$WORK/lsfg"
git -C "$WORK/lsfg" checkout -q "$COMMIT"
# Submodules; dxbc from the copy when its repository cannot be reached. git asks for a user name
# on a missing repository, so prompts are turned off to fail at once
# 하위 모듈. dxbc는 저장소에 닿지 못하면 사본에서 가져옴. git은 없는 저장소에서 사용자 이름을 물으므로 묻지 않게
# 해서 바로 실패하게 함
export GIT_TERMINAL_PROMPT=0
if ! git -C "$WORK/lsfg" submodule update -q --init --recursive 2>/dev/null; then
    git -C "$WORK/lsfg" submodule update -q --init --recursive thirdparty/pe-parse thirdparty/toml11 thirdparty/volk
    # The copy's parent commit must pin the same dxbc commit, so the files are that commit's
    # 사본의 부모 커밋이 같은 dxbc 커밋을 고정해야 함. 그래야 파일이 그 커밋의 것임
    git -C "$WORK/lsfg" ls-tree HEAD thirdparty/dxbc | grep -q "$DXBC_COMMIT" || {
        echo "lsfg: dxbc 하위 모듈 커밋이 $DXBC_COMMIT 이 아님" >&2
        exit 1
    }
    git -c advice.detachedHead=false clone -q "$DXBC_COPY_REPO" "$WORK/dxbc-copy"
    git -C "$WORK/dxbc-copy" checkout -q "$DXBC_COPY_COMMIT"
    git -C "$WORK/dxbc-copy" ls-tree "$DXBC_COPY_COMMIT^" thirdparty/dxbc | grep -q "$DXBC_COMMIT" || {
        echo "lsfg: 사본의 부모가 dxbc $DXBC_COMMIT 을 고정하지 않음" >&2
        exit 1
    }
    rm -rf "$WORK/lsfg/thirdparty/dxbc"
    cp -r "$WORK/dxbc-copy/thirdparty/dxbc" "$WORK/lsfg/thirdparty/dxbc"
    DXBC_FROM="copy $DXBC_COPY_REPO $DXBC_COPY_COMMIT"
    echo "lsfg: dxbc 원래 저장소에 닿지 못함, 사본 사용 ($DXBC_COPY_COMMIT)"
fi
# Project patches for the layer (MIT like the layer itself), applied in name order; the build stops
# if one no longer applies / 레이어용 프로젝트 패치(레이어와 같은 MIT)를 이름 순서로 적용. 적용되지 않으면 빌드를 멈춤
for p in tools/patches/lsfg-*.patch; do
    [ -f "$p" ] || continue
    git -C "$WORK/lsfg" apply "$PWD/$p"
    echo "applied $(basename "$p")"
done
git -c advice.detachedHead=false clone -q --depth 1 -b "$VKH_TAG" https://github.com/KhronosGroup/Vulkan-Headers "$WORK/vkh"

# The fork's Turnip path is compiled only under __ANDROID__: frame generation on the game's own
# device and queue, synchronous, without OPAQUE_FD semaphores. The desktop path exports OPAQUE_FD
# semaphores, which Turnip on KGSL refuses ("Unable to create semaphore (error -1000072003)" =
# VK_ERROR_INVALID_EXTERNAL_HANDLE, device log 2026-10-25). So this glibc build defines
# __ANDROID__ (and VK_USE_PLATFORM_ANDROID_KHR for the Vulkan types those files name) with two
# small headers of this script standing in for the NDK ones: android/log.h prints to stderr,
# android/hardware_buffer.h only names the type. No AHardwareBuffer function is called on that
# path (checked: the library needs no Android symbol, see the nm check below).
# 포크의 Turnip 경로는 __ANDROID__일 때만 컴파일됨: 게임 자신의 장치·큐에서 동기식으로 프레임 생성, OPAQUE_FD
# 세마포어 없음. 데스크톱 경로는 OPAQUE_FD 세마포어를 내보내는데 KGSL의 Turnip이 거부함("Unable to create
# semaphore (error -1000072003)" = VK_ERROR_INVALID_EXTERNAL_HANDLE, 기기 로그 2026-10-25). 그래서 이 glibc 빌드는
# __ANDROID__(그리고 그 파일들이 쓰는 Vulkan 형식용 VK_USE_PLATFORM_ANDROID_KHR)를 정의하고, NDK 헤더 대신 이
# 스크립트의 작은 헤더 두 개를 씀: android/log.h는 stderr로 출력, android/hardware_buffer.h는 형식 이름만 둠.
# 그 경로에서 AHardwareBuffer 함수는 부르지 않음(확인: 라이브러리가 안드로이드 심볼을 요구하지 않음, 아래 nm 확인)
mkdir -p "$WORK/shim/android"
cat > "$WORK/shim/android/log.h" <<'SHIM'
/* Stand-in for the NDK log header: messages go to stderr / NDK 로그 헤더 대역: 메시지는 stderr로 */
#pragma once
#include <stdio.h>
#include <stdarg.h>
enum { ANDROID_LOG_UNKNOWN = 0, ANDROID_LOG_DEFAULT, ANDROID_LOG_VERBOSE, ANDROID_LOG_DEBUG,
       ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR, ANDROID_LOG_FATAL, ANDROID_LOG_SILENT };
static inline int __android_log_print(int prio, const char *tag, const char *fmt, ...)
{
    va_list ap;
    int n;
    (void)prio;
    va_start(ap, fmt);
    fprintf(stderr, "%s: ", tag);
    n = vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
    va_end(ap);
    return n;
}
SHIM
cat > "$WORK/shim/android/hardware_buffer.h" <<'SHIM'
/* Stand-in for the NDK header: only the type name is used / NDK 헤더 대역: 형식 이름만 씀 */
#pragma once
typedef struct AHardwareBuffer AHardwareBuffer;
SHIM
LSFG_FLAGS="-I$WORK/vkh/include -I$WORK/shim -D__ANDROID__ -DVK_USE_PLATFORM_ANDROID_KHR"
cmake -S "$WORK/lsfg" -B "$WORK/build" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ \
    -DCMAKE_C_FLAGS="$LSFG_FLAGS" -DCMAKE_CXX_FLAGS="$LSFG_FLAGS"
ninja -C "$WORK/build" lsfg-vk

mkdir -p "$OUT"
cp "$WORK/build/liblsfg-vk.so" "$OUT/"
strip "$OUT/liblsfg-vk.so"
# The loader looks these two up by name (manifest "functions"); stop the build if either is not
# exported / 로더가 이름으로 찾는 두 함수(매니페스트 "functions"). 하나라도 내보내지 않았으면 빌드를 멈춤
# No Android library symbol may be needed (the shim build must stay plain glibc)
# 안드로이드 라이브러리 심볼이 필요하면 안 됨(대역 빌드는 순수 glibc여야 함)
if nm -D --undefined-only "$OUT/liblsfg-vk.so" | grep -qiE 'AHardwareBuffer_|__android_log|ANativeWindow'; then
    echo "liblsfg-vk.so: 안드로이드 심볼 필요" >&2
    exit 1
fi
for sym in layer_vkGetInstanceProcAddr layer_vkGetDeviceProcAddr; do
    nm -D --defined-only "$OUT/liblsfg-vk.so" | grep -q " T $sym\$" || {
        echo "liblsfg-vk.so: $sym 내보내지 않음" >&2
        exit 1
    }
done
# Layer manifest: library next to it. The upstream file names it without a path
# ("liblsfg-vk.so"), which the Vulkan loader looks up in the system library folders, not next to
# the manifest ("liblsfg-vk.so: cannot open shared object file", device log 2026-10-25); "./"
# makes it relative to the manifest. The build stops if the line is not found.
# 레이어 설명 파일: 라이브러리는 옆. 원본은 경로 없이 이름만 적어("liblsfg-vk.so") Vulkan 로더가 설명 파일 옆이
# 아니라 시스템 라이브러리 폴더에서 찾음("liblsfg-vk.so: cannot open shared object file", 기기 로그
# 2026-10-25). "./"를 붙여 설명 파일 기준 상대 경로로 만듦. 그 줄이 없으면 빌드를 멈춤
sed 's|"library_path": *"liblsfg-vk.so"|"library_path": "./liblsfg-vk.so"|' \
    "$WORK/lsfg/VkLayer_LS_frame_generation.json" > "$OUT/VkLayer_LS_frame_generation.json"
grep -q '"library_path": "./liblsfg-vk.so"' "$OUT/VkLayer_LS_frame_generation.json" || {
    echo "VkLayer_LS_frame_generation.json: library_path 줄을 못 찾음" >&2
    exit 1
}
# Licenses of everything inside the library / 라이브러리에 들어간 모든 것의 라이선스
mkdir -p "$OUT/licenses"
cp "$WORK/lsfg/LICENSE.md" "$OUT/licenses/lsfg-vk.md"
cp "$WORK/lsfg/framegen/LICENSE.md" "$OUT/licenses/lsfg-vk-framegen.md"
cp "$WORK/lsfg/thirdparty/pe-parse/LICENSE" "$OUT/licenses/pe-parse.txt"
cp "$WORK/lsfg/thirdparty/toml11/LICENSE" "$OUT/licenses/toml11.txt"
cp "$WORK/lsfg/thirdparty/volk/LICENSE.md" "$OUT/licenses/volk.md"
cp "$WORK/lsfg/thirdparty/dxbc/LICENSE.md" "$OUT/licenses/dxbc.md"
{
    echo "lsfg-vk $REPO $COMMIT"
    git -C "$WORK/lsfg" submodule status 2>/dev/null | sed 's/^/  /'
    echo "  dxbc $DXBC_COMMIT from $DXBC_FROM"
    echo "Vulkan-Headers $VKH_TAG"
} > "$OUT/source.txt"
file "$OUT/liblsfg-vk.so"
cat "$OUT/source.txt"
