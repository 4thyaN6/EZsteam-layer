#!/bin/sh
# Build Mesa Turnip (freedreno Vulkan, MIT) for glibc aarch64 with the KGSL backend and X11 WSI,
# and pack it as the driver package that DriverInstaller installs ("turnip").
# Runs inside a debian:trixie arm64 container (same release as the rootfs) on the CI arm64 runner.
# glibc aarch64용 Mesa Turnip(freedreno Vulkan, MIT)을 KGSL 백엔드·X11 WSI로 빌드하고,
# DriverInstaller가 설치하는 드라이버 묶음("turnip")으로 묶음.
# CI arm64 실행기의 debian:trixie arm64 컨테이너(rootfs와 같은 릴리스) 안에서 실행함
#
#   sh tools/build-turnip-glibc.sh fetch out-turnip/mesa.tar.xz          (runner host / 실행기 본체)
#   docker run --rm -v "$PWD:/src" -w /src debian:trixie \
#       sh tools/build-turnip-glibc.sh build out-turnip out-turnip/mesa.tar.xz   (container / 컨테이너)
#
# The source is fetched on the runner host, not in the container: the container's TLS connection
# to archive.mesa3d.org failed in CI (curl exit 35). Mirrors are tried in order with retries,
# and the same published sha256 is checked whichever mirror answered.
# 소스는 컨테이너가 아니라 실행기 본체에서 받음: CI에서 컨테이너의 archive.mesa3d.org TLS 연결이
# 실패함(curl 종료 코드 35). 미러를 순서대로 재시도하며, 어느 미러든 같은 공개 sha256으로 확인함
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
MESA_MIRRORS="$MESA_URL https://mesa.freedesktop.org/archive/mesa-$MESA_VER.tar.xz"
MESA_SHA256=bce5f7fbebb934373b86c999a064d52fb5065878dc57f287f95346648ec832e9
PKG=turnip
# REV 2: kgsl timestamp patch (mesa-02) applied, so the app installs the new package once
# REV 2: kgsl 시각 패치(mesa-02) 적용. 앱이 새 묶음을 한 번 설치하게 함
REV=2

# fetch <file>: download the release tarball (host side) and verify it
# fetch <파일>: 릴리스 tarball을 받고(본체 쪽) 확인함
if [ "${1:-}" = "fetch" ]; then
    DEST="$2"
    mkdir -p "$(dirname "$DEST")"
    for u in $MESA_MIRRORS; do
        echo "fetch: $u"
        if curl -fL --retry 5 --retry-delay 5 --retry-all-errors --connect-timeout 30 -o "$DEST" "$u" \
            && echo "$MESA_SHA256  $DEST" | sha256sum -c -; then
            echo "$u" > "$DEST.url"
            exit 0
        fi
        rm -f "$DEST"
    done
    echo "mesa source not reachable from any mirror" >&2
    exit 1
fi

# build <outdir> <tarball>: build inside the debian:trixie container
# build <결과 폴더> <tarball>: debian:trixie 컨테이너 안에서 빌드함
[ "${1:-}" = "build" ] || { echo "usage: $0 fetch <file> | build <outdir> <tarball>" >&2; exit 2; }
OUT="$(mkdir -p "$2" && cd "$2" && pwd)"
TARBALL="$3"
[ -f "$TARBALL" ] || { echo "tarball not found: $TARBALL" >&2; exit 1; }
[ -f "$TARBALL.url" ] && MESA_URL="$(cat "$TARBALL.url")"
WORK=/tmp/turnip-build
rm -rf "$WORK" && mkdir -p "$WORK"

# 1. Build tools and X11/xcb headers (no libdrm: KGSL-only, no LLVM, no Rust)
#    빌드 도구와 X11/xcb 헤더(libdrm 없음: KGSL 단독, LLVM·Rust 불필요)
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
    ca-certificates xz-utils build-essential meson ninja-build pkgconf bison flex glslang-tools \
    python3 python3-mako python3-yaml python3-packaging \
    libexpat1-dev zlib1g-dev libzstd-dev \
    libx11-dev libx11-xcb-dev libxcb1-dev libxcb-dri3-dev libxcb-present-dev libxcb-shm0-dev \
    libxcb-xfixes0-dev libxcb-randr0-dev libxcb-sync-dev libxshmfence-dev
meson --version
glslangValidator --version | head -n 1

# 2. Source fetched by the host step, checked again here
#    본체 단계가 받은 소스를 여기서 다시 확인함
echo "$MESA_SHA256  $TARBALL" | sha256sum -c -
tar -xJf "$TARBALL" -C "$WORK"
SRC="$WORK/mesa-$MESA_VER"
# kgsl has no GPU timestamp read: calibrated timestamps off on kgsl, as in mesax. In the default
# package Zink on this driver printed "vkGetCalibratedTimestampsEXT failed" 180 times in the GPU
# path check (2026-10-29), and games reaching that function hit an unwritten path (undefined behaviour)
# kgsl에는 GPU 시각 읽기가 없음: mesax처럼 kgsl에서 calibrated timestamps 끔. 기본 묶음에서는 이 드라이버 위
# Zink가 GPU 경로 점검에서 "vkGetCalibratedTimestampsEXT failed"를 180번 냈고(2026-10-29), 그 함수에 닿는 게임은
# 구현되지 않은 경로(정의되지 않은 동작)를 탐
HERE="$(cd "$(dirname "$0")" && pwd)"
TSPATCH=0
if python3 "$HERE/patches/mesa-02-kgsl-timestamp.py" "$SRC"; then TSPATCH=1; fi

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
kgsl timestamp patch: $TSPATCH
EOF
echo "$TSPATCH" > "$P/timestamp-patch"
# Runtime libraries the driver needs (must exist in the rootfs)
# 드라이버가 필요로 하는 런타임 라이브러리(rootfs에 있어야 함)
objdump -p "$P/lib/libvulkan_freedreno.so" | awk '/NEEDED/ {print $2}' | tee "$P/needed.txt"

# 6. Pack / 묶기
echo "mesa-$MESA_VER-kgsl-x11-r$REV" > "$OUT/$PKG.version"
tar --numeric-owner -cJf "$OUT/$PKG.tar.xz" -C "$P" .
( cd "$OUT" && sha256sum "$PKG.tar.xz" > "$PKG.tar.xz.sha256" )
ls -l "$OUT"
