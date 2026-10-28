#!/bin/sh
# Build the "mesax" bundle: Mesa (MIT) with Zink (OpenGL on Vulkan) for GLX, EGL and GBM, plus a
# Turnip built in the same tree with libdrm (needed by Zink's device match and the X11 DRI3 path).
# Used only by the app's test switches "Zink GL" and "GPU device"; the default session keeps using
# the separate turnip package (tools/build-turnip-glibc.sh). Same Mesa release and the same source
# fetch as that script (version and sha256 are read from it, one pin for both).
# "mesax" 묶음 빌드: GLX, EGL, GBM용 Zink(Vulkan 위 OpenGL)가 든 Mesa(MIT)와, 같은 트리에서 libdrm과 함께 빌드한
# Turnip(Zink의 장치 맞춤과 X11 DRI3 경로에 필요). 앱의 시험 스위치 "Zink GL"과 "GPU 장치"에서만 씀. 기본 세션은
# 따로 있는 turnip 묶음(tools/build-turnip-glibc.sh)을 계속 씀. 그 스크립트와 같은 Mesa 릴리스, 같은 소스 받기
# (버전과 sha256은 거기서 읽음, 고정 값 하나로 둘 다 씀)
#
#   sh tools/build-turnip-glibc.sh fetch src-mesa/mesa.tar.xz                  (runner host / 실행기 본체)
#   docker run --rm -v "$PWD:/src" -w /src debian:trixie \
#       sh tools/build-mesax.sh out-mesax src-mesa/mesa.tar.xz                 (container / 컨테이너)
#
# Output / 결과물: out-mesax/mesax.tar.xz (unpacked by CI into rootfs-overlay/opt/ezsteam/mesax)
#                  (CI가 rootfs-overlay/opt/ezsteam/mesax에 풂)
# Bundle / 묶음:
#   lib/          libGLX_mesa.so.0, libEGL_mesa.so.0, libgbm.so.1, libgallium-*.so, libvulkan_freedreno.so,
#                 gbm/dri_gbm.so, dri/zink_dri.so (files named by soname, rpath $ORIGIN)
#                 (soname 이름의 파일, rpath $ORIGIN)
#   icd.json      Turnip manifest (relative library_path) / Turnip 매니페스트(상대 library_path)
#   egl.json      glvnd EGL vendor file (absolute path in the rootfs) / glvnd EGL 공급자 파일(rootfs 안 절대 경로)
#   drm-patch     1 when the kgsl DRM patch applied, 0 when its anchors were not found
#                 kgsl DRM 패치가 적용되면 1, 기준 위치를 못 찾으면 0
#   source.txt, needed.txt, LICENSE
set -eu

[ "${1:-}" ] && [ "${2:-}" ] || { echo "usage: $0 <outdir> <tarball>" >&2; exit 2; }
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$(mkdir -p "$1" && cd "$1" && pwd)"
TARBALL="$2"
[ -f "$TARBALL" ] || { echo "tarball not found: $TARBALL" >&2; exit 1; }
# One pin: read the version and checksum from the turnip script
# 고정 값 하나: turnip 스크립트에서 버전과 체크섬을 읽음
MESA_VER="$(sed -n 's/^MESA_VER=//p' "$HERE/build-turnip-glibc.sh")"
MESA_SHA256="$(sed -n 's/^MESA_SHA256=//p' "$HERE/build-turnip-glibc.sh")"
MESA_URL="https://archive.mesa3d.org/mesa-$MESA_VER.tar.xz"
[ -f "$TARBALL.url" ] && MESA_URL="$(cat "$TARBALL.url")"
GUEST=/opt/ezsteam/mesax
REV=3
WORK=/tmp/mesax-build
rm -rf "$WORK" && mkdir -p "$WORK"

# 1. Build tools, X11/xcb, libdrm and glvnd headers (no LLVM, no Rust)
#    빌드 도구, X11/xcb, libdrm, glvnd 헤더(LLVM·Rust 없음)
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
    ca-certificates xz-utils build-essential meson ninja-build pkgconf bison flex glslang-tools \
    python3 python3-mako python3-yaml python3-packaging patchelf \
    libexpat1-dev zlib1g-dev libzstd-dev libdrm-dev libglvnd-dev libvulkan-dev \
    libx11-dev libx11-xcb-dev libxext-dev libxfixes-dev libxxf86vm-dev libxrandr-dev \
    libxcb1-dev libxcb-glx0-dev libxcb-dri2-0-dev libxcb-dri3-dev libxcb-present-dev libxcb-shm0-dev \
    libxcb-xfixes0-dev libxcb-randr0-dev libxcb-sync-dev libxshmfence-dev
meson --version

# 2. Source (checked again) and the kgsl DRM patch (env-gated inside, EZS_TU_DRM=1)
#    소스(다시 확인)와 kgsl DRM 패치(안에서 환경 변수로 켬, EZS_TU_DRM=1)
echo "$MESA_SHA256  $TARBALL" | sha256sum -c -
tar -xJf "$TARBALL" -C "$WORK"
SRC="$WORK/mesa-$MESA_VER"
DRMPATCH=0
if python3 "$HERE/patches/mesa-01-kgsl-drm.py" "$SRC"; then DRMPATCH=1; fi
# kgsl has no GPU timestamp read: calibrated timestamps off on kgsl (Zink failed on it in the web helper)
# kgsl에는 GPU 시각 읽기가 없음: kgsl에서 calibrated timestamps 끔(웹 도우미에서 Zink가 이것으로 실패함)
TSPATCH=0
if python3 "$HERE/patches/mesa-02-kgsl-timestamp.py" "$SRC"; then TSPATCH=1; fi
# VK_KHR_display enabled by an application must not drop the kgsl GPU (vkcube found no GPU)
# 앱이 VK_KHR_display를 켜도 kgsl GPU를 버리지 않게 함(vkcube가 GPU를 찾지 못했음)
DISPATCH=0
if python3 "$HERE/patches/mesa-03-kgsl-khr-display.py" "$SRC"; then DISPATCH=1; fi

# 3. Configure: Zink + Turnip(kgsl), GLX(dri) / EGL / GBM / GLES2 through glvnd, X11 platform
#    설정: Zink + Turnip(kgsl), glvnd를 통한 GLX(dri) / EGL / GBM / GLES2, X11 플랫폼
# freedreno-kmds=kgsl,msm: with kgsl alone and Turnip as the only Vulkan driver, Mesa's meson.build
# turns off KMS/DRM support (system_has_kms_drm = false) and then refuses GBM ("GBM only supports
# DRM/KMS platforms", CI 2026-10-28). Adding the msm kernel interface keeps DRM on. Turnip tries
# kgsl first (its own enumerate hook) and the DRM path only when kgsl is not found; the fake
# render node answers no msm version, so it is skipped there
# freedreno-kmds=kgsl,msm: kgsl만 쓰고 Vulkan 드라이버가 Turnip 하나면 Mesa meson.build가 KMS/DRM 지원을 끄고
# (system_has_kms_drm = false) 그다음 GBM을 거부함("GBM only supports DRM/KMS platforms", CI 2026-10-28). msm 커널
# 인터페이스를 더하면 DRM이 켜진 채로 남음. Turnip은 kgsl을 먼저 찾고(자체 enumerate) kgsl이 없을 때만 DRM 경로를
# 씀. 가짜 render 노드는 msm 버전에 답하지 않아 거기서 건너뜀
meson setup "$WORK/build" "$SRC" \
    --prefix=/usr --libdir=lib \
    -Dbuildtype=release -Dstrip=true \
    -Dplatforms=x11 \
    -Dgallium-drivers=zink \
    -Dvulkan-drivers=freedreno -Dfreedreno-kmds=kgsl,msm -Dvulkan-layers= \
    -Dopengl=true -Dglx=dri -Degl=enabled -Dgbm=enabled -Dgles1=disabled -Dgles2=enabled \
    -Dglvnd=enabled -Dxlib-lease=disabled \
    -Dllvm=disabled -Dvalgrind=disabled -Dlibunwind=disabled -Dlmsensors=disabled \
    -Dbuild-tests=false

# 4. Build and install into a staging folder / 빌드하고 임시 폴더에 설치
ninja -C "$WORK/build"
DESTDIR="$WORK/stage" meson install -C "$WORK/build"
L="$WORK/stage/usr/lib"

# 5. Bundle: each library as one file named by its soname (no symlinks: APK assets keep files only)
#    묶음: 라이브러리마다 soname 이름의 파일 하나(심볼릭 링크 없음: APK assets는 파일만 유지)
P="$WORK/pkg"
mkdir -p "$P/lib/gbm" "$P/lib/dri"
for f in "$L"/*.so*; do
    [ -f "$f" ] || continue
    so="$(objdump -p "$f" | awk '/SONAME/ {print $2}')"
    [ -n "$so" ] || so="$(basename "$f")"
    [ -f "$P/lib/$so" ] || cp -L "$f" "$P/lib/$so"
done
cp -L "$L/gbm/dri_gbm.so" "$P/lib/gbm/" 2>/dev/null || echo "no gbm backend (built into libgbm)"
cp -L "$L/dri/zink_dri.so" "$P/lib/dri/" 2>/dev/null || echo "no dri/zink_dri.so (gallium loaded directly)"
for want in libGLX_mesa.so.0 libEGL_mesa.so.0 libgbm.so.1 libvulkan_freedreno.so; do
    [ -f "$P/lib/$want" ] || { echo "missing $want" >&2; ls -l "$L" >&2; exit 1; }
done
ls "$P/lib"/libgallium*.so >/dev/null 2>&1 || { echo "missing libgallium" >&2; exit 1; }
# Libraries find each other next to themselves / 라이브러리가 자기 옆에서 서로를 찾음
for f in "$P"/lib/*.so*; do patchelf --set-rpath '$ORIGIN' "$f"; done
for f in "$P"/lib/gbm/*.so "$P"/lib/dri/*.so; do
    if [ -f "$f" ]; then patchelf --set-rpath '$ORIGIN/..' "$f"; fi
done
# glvnd/vendor development files are not shipped (no libGL/libEGL front ends here: Debian's glvnd)
# glvnd 개발 파일은 넣지 않음(여기 libGL/libEGL 앞단 없음: Debian glvnd 사용)
rm -f "$P"/lib/libGL.so* "$P"/lib/libEGL.so* "$P"/lib/libGLESv2.so* "$P"/lib/libGLX.so* "$P"/lib/libOpenGL.so*

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
cat > "$P/egl.json" <<EOF
{
    "file_format_version": "1.0.0",
    "ICD": {
        "library_path": "$GUEST/lib/libEGL_mesa.so.0"
    }
}
EOF
echo "$DRMPATCH" > "$P/drm-patch"
echo "$TSPATCH" > "$P/timestamp-patch"
echo "$DISPATCH" > "$P/display-patch"
cp "$SRC/docs/license.rst" "$P/LICENSE" 2>/dev/null || echo "Mesa license: docs/license.rst in $MESA_URL" > "$P/LICENSE"
cat > "$P/source.txt" <<EOF
mesa: $MESA_URL sha256 $MESA_SHA256 (MIT)
config: gallium=zink vulkan=freedreno(kgsl,msm) glx=dri egl gbm gles2 glvnd platforms=x11 rev $REV
kgsl drm patch: $DRMPATCH
kgsl timestamp patch: $TSPATCH
kgsl display patch: $DISPATCH
EOF
# Runtime libraries needed from the rootfs / rootfs에서 필요한 런타임 라이브러리
for f in "$P"/lib/*.so* "$P"/lib/gbm/*.so "$P"/lib/dri/*.so; do
    if [ -f "$f" ]; then objdump -p "$f" | awk '/NEEDED/ {print $2}'; fi
done | sort -u | tee "$P/needed.txt"
du -sh "$P"

# 6. Pack / 묶기
echo "mesa-$MESA_VER-zink-kgsl-r$REV" > "$OUT/mesax.version"
tar --numeric-owner -cJf "$OUT/mesax.tar.xz" -C "$P" .
ls -l "$OUT"
