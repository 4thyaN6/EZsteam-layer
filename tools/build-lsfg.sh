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
git -C "$WORK/lsfg" submodule update -q --init --recursive
git -c advice.detachedHead=false clone -q --depth 1 -b "$VKH_TAG" https://github.com/KhronosGroup/Vulkan-Headers "$WORK/vkh"

cmake -S "$WORK/lsfg" -B "$WORK/build" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ \
    -DCMAKE_C_FLAGS="-I$WORK/vkh/include" -DCMAKE_CXX_FLAGS="-I$WORK/vkh/include"
ninja -C "$WORK/build" lsfg-vk

mkdir -p "$OUT"
cp "$WORK/build/liblsfg-vk.so" "$OUT/"
strip "$OUT/liblsfg-vk.so"
# The loader looks these two up by name (manifest "functions"); stop the build if either is not
# exported / 로더가 이름으로 찾는 두 함수(매니페스트 "functions"). 하나라도 내보내지 않았으면 빌드를 멈춤
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
    git -C "$WORK/lsfg" submodule status | sed 's/^/  /'
    echo "Vulkan-Headers $VKH_TAG"
} > "$OUT/source.txt"
file "$OUT/liblsfg-vk.so"
cat "$OUT/source.txt"
