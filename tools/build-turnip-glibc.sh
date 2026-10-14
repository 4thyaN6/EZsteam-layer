#!/bin/sh
# Build Mesa Turnip (freedreno Vulkan, MIT) for glibc aarch64 with the KGSL backend and X11 WSI,
# and pack it as the driver package that DriverInstaller installs ("turnip").
# Runs inside a debian:trixie arm64 container (same release as the rootfs) on the CI arm64 runner.
# glibc aarch64용 Mesa Turnip(freedreno Vulkan, MIT)을 KGSL 백엔드·X11 WSI로 빌드하고,
# DriverInstaller가 설치하는 드라이버 묶음("turnip")으로 묶음.
# CI arm64 실행기의 debian:trixie arm64 컨테이너(rootfs와 같은 릴리스) 안에서 실행함
#
#   docker run --rm -v "$PWD:/src" -w /src debian:trixie sh tools/build-turnip-glibc.sh out-turnip
#
# Output / 결과물:
#   turnip.tar.xz, turnip.tar.xz.sha256, turnip.version
#
# Package contents / 묶음 내용:
#   icd.json                     library_path relative to the manifest (Vulkan loader rule)
#                                매니페스트 기준 상대 경로(Vulkan 로더 규칙)
#   lib/libvulkan_freedreno.so
#   env                          MESA_VK_WSI_DEBUG=sw (needed: KGSL-only builds have no X11 DRM/DRI3 path)
#                                MESA_VK_WSI_DEBUG=sw(필수: KGSL 단독 빌드에는 X11 DRM/DRI3 경로가 없음)
#   LICENSE, source.txt, needed.txt
set -eu

# Pinned source: Mesa 26.2.4 (2026-10-01), includes Adreno 840 (added in 26.0.0)
# 고정 소스: Mesa 26.2.4(2026-10-01), Adreno 840 포함(26.0.0에서 추가)
MESA_VER=26.2.4
MESA_URL=https://archive.mesa3d.org/mesa-$MESA_VER.tar.xz
MESA_SHA256=bce5f7fbebb934373b86c999a064d52fb5065878dc57f287f95346648ec832e9
PKG=turnip
REV=1

OUT="$(mkdir -p "$1" && cd "$1" && pwd)"
WORK=/tmp/turnip-build
rm -rf "$WORK" && mkdir -p "$WORK"

# 1. Build tools and X11/xcb headers (no libdrm: KGSL-only, no LLVM, no Rust)
#    빌드 도구와 X11/xcb 헤더(libdrm 없음: KGSL 단독, LLVM·Rust 불필요)
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
    ca-certificates curl xz-utils build-essential meson ninja-build pkgconf bison flex glslang-tools \
    python3 python3-mako python3-yaml python3-packaging \
    libexpat1-dev zlib1g-dev libzstd-dev \
    libx11-dev libx11-xcb-dev libxcb1-dev libxcb-dri3-dev libxcb-present-dev libxcb-shm0-dev \
    libxcb-xfixes0-dev libxcb-randr0-dev libxcb-sync-dev libxshmfence-dev
meson --version
glslangValidator --version | head -n 1

# 2. Source (checked against the published sha256)
#    소스 받기(공개된 sha256으로 확인)
curl -fsSL -o "$WORK/mesa.tar.xz" "$MESA_URL"
echo "$MESA_SHA256  $WORK/mesa.tar.xz" | sha256sum -c -
tar -xJf "$WORK/mesa.tar.xz" -C "$WORK"
SRC="$WORK/mesa-$MESA_VER"

# 3. Configure: Turnip only, KGSL kernel interface, X11 platform, everything else off
#    설정: Turnip만, KGSL 커널 인터페이스, X11 플랫폼, 나머지는 끔
meson setup "$WORK/build" "$SRC" \
    --prefix=/usr \
    -Dbuildtype=release -Dstrip=true \
    -Dplatforms=x11 \
    -Dvulkan-drivers=freedreno -Dfreedreno-kmds=kgsl -Dvulkan-layers= \
    -Dgallium-drivers= \
    -Dopengl=false -Dgles1=disabled -Dgles2=disabled -Degl=disabled -Dglx=disabled \
    -Dgbm=disabled -Dglvnd=disabled -Dxlib-lease=disabled \
    -Dllvm=disabled -Dvalgrind=disabled -Dlibunwind=disabled -Dlmsensors=disabled \
    -Dbuild-tests=false

# 4. Build and install into a staging folder
#    빌드하고 임시 폴더에 설치
ninja -C "$WORK/build"
DESTDIR="$WORK/stage" meson install -C "$WORK/build"
SO="$(find "$WORK/stage" -name 'libvulkan_freedreno.so' | head -n 1)"
[ -n "$SO" ] || { echo "libvulkan_freedreno.so not built" >&2; exit 1; }

# 5. Assemble the package / 묶음 구성
P="$WORK/pkg"
mkdir -p "$P/lib"
cp "$SO" "$P/lib/libvulkan_freedreno.so"
# ICD manifest with a relative library_path, so the package works wherever it is bound
# library_path를 상대 경로로 둔 ICD 매니페스트(묶음을 어디에 바인드해도 동작)
API="$(python3 -c 'import json,glob,sys; f=glob.glob(sys.argv[1]+"/usr/share/vulkan/icd.d/freedreno_icd*.json"); print(json.load(open(f[0]))["ICD"]["api_version"] if f else "1.4.0")' "$WORK/stage")"
cat > "$P/icd.json" <<EOF
{
    "file_format_version": "1.0.1",
    "ICD": {
        "library_path": "./lib/libvulkan_freedreno.so",
        "api_version": "$API",
        "library_arch": "64"
    }
}
EOF
# KGSL-only builds have no DRI3 path in the X11 WSI: use the software present path
# KGSL 단독 빌드는 X11 WSI에 DRI3 경로가 없으므로 소프트웨어 출력 경로를 씀
cat > "$P/env" <<EOF
# Applied by DriverInstaller.apply when this driver is active
# 이 드라이버를 쓸 때 DriverInstaller.apply가 적용함
MESA_VK_WSI_DEBUG=sw
EOF
cp "$SRC/docs/license.rst" "$P/LICENSE" 2>/dev/null \
    || echo "Mesa license: docs/license.rst in $MESA_URL" > "$P/LICENSE"
cat > "$P/source.txt" <<EOF
mesa: $MESA_URL sha256 $MESA_SHA256 (MIT)
config: platforms=x11 vulkan-drivers=freedreno freedreno-kmds=kgsl buildtype=release
EOF
# Runtime libraries the driver needs (must exist in the rootfs)
# 드라이버가 필요로 하는 런타임 라이브러리(rootfs에 있어야 함)
objdump -p "$P/lib/libvulkan_freedreno.so" | awk '/NEEDED/ {print $2}' | tee "$P/needed.txt"

# 6. Pack / 묶기
echo "mesa-$MESA_VER-kgsl-x11-r$REV" > "$OUT/$PKG.version"
tar --numeric-owner -cJf "$OUT/$PKG.tar.xz" -C "$P" .
( cd "$OUT" && sha256sum "$PKG.tar.xz" > "$PKG.tar.xz.sha256" )
ls -l "$OUT"
