#!/bin/sh
# Build the EZSteam ALSA playback plugin (native/ezsaudio/pcm_ezs.c) for glibc aarch64.
# Runs inside a debian:trixie arm64 container (same release as the rootfs) on the CI arm64 runner.
# The result goes into the APK assets (rootfs-overlay/opt/ezsteam/lib), and the app copies it to
# /opt/ezsteam/lib in the rootfs before each run, so no new rootfs download is needed.
# EZSteam ALSA 재생 플러그인(native/ezsaudio/pcm_ezs.c)을 glibc aarch64용으로 빌드함.
# CI arm64 실행기의 debian:trixie arm64 컨테이너(rootfs와 같은 릴리스) 안에서 실행함.
# 결과물은 APK assets(rootfs-overlay/opt/ezsteam/lib)에 들어가고, 앱이 실행 전마다 rootfs의
# /opt/ezsteam/lib로 복사하므로 rootfs를 다시 받을 필요가 없음
#
#   docker run --rm -v "$PWD:/src" -w /src debian:trixie sh tools/build-alsa-plugin.sh out-alsa
#
# Also builds the game controller preload library (native/ezspad/ezs_pad.c), same container and
# same delivery path (small native helpers for the rootfs)
# 게임 컨트롤러 preload 라이브러리(native/ezspad/ezs_pad.c)도 같은 컨테이너에서 빌드함.
# 전달 경로도 같음(rootfs용 작은 네이티브 보조 파일)
#
# And an SDL2 build without libudev for that library (see native/ezspad/ezs_pad.c: the rootfs SDL2
# is built with libudev and its haptic init fails without udev, so Wine's SDL bus never starts).
# Source: the libsdl-org/SDL GitHub release tag below (zlib license)
# 그리고 그 라이브러리용 libudev 없는 SDL2 빌드(native/ezspad/ezs_pad.c 참고: rootfs SDL2는 libudev와
# 함께 빌드되어 udev가 없으면 햅틱 초기화가 실패하고, 그래서 Wine SDL 버스가 시작하지 못함).
# 소스: 아래 libsdl-org/SDL GitHub 릴리스 태그(zlib 라이선스)
#
# Output / 결과물: libasound_module_pcm_ezs.so, libezs_pad.so, libSDL2-2.0.so.0, libezs_compat.so, libezs_fsync.so,
#                  bin/ezs-wl, libezs_drm.so, libezs_crash.so, bin/ezs-gltest,
#                  alsa-plugin.version
set -eu
OUT="${1:-out-alsa}"
SDL_TAG=release-2.30.9
# SDL3 headers only, for the Steam client part of the controller library (the client brings its
# own libSDL3; nothing of SDL3 is built or shipped)
# 컨트롤러 라이브러리의 Steam 클라이언트 부분용 SDL3 헤더만(클라이언트가 자체 libSDL3을 가짐.
# SDL3은 빌드·배포하지 않음)
SDL3_TAG=release-3.2.0

apt-get update -q
# g++: SDL2's CMakeLists declares "project(SDL2 C CXX)", so CMake stops without a C++ compiler even
# though nothing C++ is built here
# g++: SDL2 CMakeLists가 "project(SDL2 C CXX)"로 선언해서, 여기서 C++을 빌드하지 않아도 C++ 컴파일러가
# 없으면 CMake가 멈춤
apt-get install -y -q --no-install-recommends gcc g++ libc6-dev libasound2-dev libvulkan-dev binutils git cmake make \
    ca-certificates pkgconf libwayland-dev libwayland-bin wayland-protocols libgl-dev libx11-dev

mkdir -p "$OUT"
# -DPIC: alsa-lib's plugin macros need it for a shared object (else snd_dlsym_start is undefined)
# -DPIC: 공유 객체에서는 alsa-lib 플러그인 매크로에 필요함(없으면 snd_dlsym_start 미정의)
gcc -shared -fPIC -DPIC -O2 -Wall -Wextra \
    -o "$OUT/libasound_module_pcm_ezs.so" native/ezsaudio/pcm_ezs.c -lasound
strip --strip-unneeded "$OUT/libasound_module_pcm_ezs.so"

# The plugin must export its open function and need only glibc + libasound
# 플러그인은 open 함수를 내보내고 glibc + libasound만 필요해야 함
nm -D "$OUT/libasound_module_pcm_ezs.so" | grep -q ' T _snd_pcm_ezs_open$'
objdump -p "$OUT/libasound_module_pcm_ezs.so" | grep NEEDED
# SDL2 without libudev: joystick, haptic, game controller and events only (what Wine's SDL bus
# uses). The virtual joystick driver needs SDL_HIDAPI on; HIDAPI's own joystick drivers stay off
# libudev 없는 SDL2: 조이스틱, 햅틱, 게임 컨트롤러, 이벤트만(Wine SDL 버스가 쓰는 것).
# 가상 조이스틱 드라이버는 SDL_HIDAPI가 켜져 있어야 함. HIDAPI 자체 조이스틱 드라이버는 끔
git clone -q --depth 1 --branch "$SDL_TAG" https://github.com/libsdl-org/SDL /tmp/sdl-src
cmake -S /tmp/sdl-src -B /tmp/sdl-build -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/tmp/sdl-inst \
    -DSDL_SHARED=ON -DSDL_STATIC=OFF -DSDL_TEST=OFF \
    -DSDL_AUDIO=OFF -DSDL_VIDEO=OFF -DSDL_RENDER=OFF \
    -DSDL_LIBUDEV=OFF -DSDL_DBUS=OFF -DSDL_IBUS=OFF \
    -DSDL_HIDAPI=ON -DSDL_HIDAPI_JOYSTICK=OFF -DSDL_HIDAPI_LIBUSB=OFF
cmake --build /tmp/sdl-build -j"$(nproc)"
cmake --install /tmp/sdl-build
cp -L /tmp/sdl-inst/lib/libSDL2-2.0.so.0 "$OUT/libSDL2-2.0.so.0"
strip --strip-unneeded "$OUT/libSDL2-2.0.so.0"
# Must keep the soname Wine asks for, must not need libudev, must not contain the udev haptic path
# Wine이 찾는 soname이어야 하고, libudev가 필요 없어야 하며, udev 햅틱 경로가 없어야 함
objdump -p "$OUT/libSDL2-2.0.so.0" | grep -q 'SONAME *libSDL2-2.0.so.0$'
objdump -p "$OUT/libSDL2-2.0.so.0" | grep NEEDED
if grep -q 'Could not initialize UDEV' "$OUT/libSDL2-2.0.so.0"; then
    echo "SDL2 was built with udev" >&2
    exit 1
fi

# Controller library: SDL2 headers only, SDL2 itself is loaded at run time (dlopen), so it needs
# only glibc + libdl; the constructor must be present
# 컨트롤러 라이브러리: SDL2 헤더만 씀. SDL2는 실행 중에 불러오므로(dlopen) glibc + libdl만
# 필요함. 생성자가 들어 있어야 함
# Two parts with different SDL headers: ezs_pad.c (Wine, SDL2) and ezs_pad_steam.c (Steam client,
# SDL3), linked into one library
# SDL 헤더가 다른 두 부분: ezs_pad.c(Wine, SDL2), ezs_pad_steam.c(Steam 클라이언트, SDL3)를 한
# 라이브러리로 링크함
git clone -q --depth 1 --branch "$SDL3_TAG" https://github.com/libsdl-org/SDL /tmp/sdl3-src
gcc -c -fPIC -O2 -Wall -Wextra -I/tmp/sdl-inst/include -o /tmp/ezs_pad.o native/ezspad/ezs_pad.c
gcc -c -fPIC -O2 -Wall -Wextra -I/tmp/sdl3-src/include -o /tmp/ezs_pad_steam.o native/ezspad/ezs_pad_steam.c
gcc -shared -o "$OUT/libezs_pad.so" /tmp/ezs_pad.o /tmp/ezs_pad_steam.o -ldl -lpthread
strip --strip-unneeded "$OUT/libezs_pad.so"
objdump -p "$OUT/libezs_pad.so" | grep NEEDED
if objdump -p "$OUT/libezs_pad.so" | grep NEEDED | grep -q SDL; then
    echo "libezs_pad.so must not link SDL2 directly" >&2
    exit 1
fi

# flock fallback for shared storage (native/ezscompat), glibc + libdl only
# 공용 저장소용 flock 대체(native/ezscompat), glibc + libdl만 필요
# plus file I/O timing of the client (ezs_iostat.c, diagnosis of slow downloads into shared storage)
# 클라이언트 파일 입출력 시간 측정 포함(ezs_iostat.c, 공용 저장소로의 느린 다운로드 진단)
gcc -shared -fPIC -O2 -Wall -Wextra -o "$OUT/libezs_compat.so" native/ezscompat/ezs_flock.c native/ezscompat/ezs_iostat.c -ldl -lpthread
strip --strip-unneeded "$OUT/libezs_compat.so"
nm -D "$OUT/libezs_compat.so" | grep -q ' T flock$'
nm -D "$OUT/libezs_compat.so" | grep -q ' T pwrite64$'

# futex_waitv stand-in for Wine's fsync (native/ezsfsync), preloaded only when the app's test
# switch is on (EZ Proton) / Wine fsync용 futex_waitv 대체(native/ezsfsync). 앱의 시험 스위치가 켜졌을
# 때만 미리 불러옴(EZ Proton)
gcc -shared -fPIC -O2 -Wall -Wextra -o "$OUT/libezs_fsync.so" native/ezsfsync/ezs_fsync.c -ldl
strip --strip-unneeded "$OUT/libezs_fsync.so"
nm -D "$OUT/libezs_fsync.so" | grep -q ' T syscall$'

# Game frame path check tool (native/ezspresent): Vulkan headers only, the loader is opened at run
# time (libvulkan.so.1 of the rootfs) / 게임 프레임 경로 확인 도구(native/ezspresent): Vulkan 헤더만 씀,
# 로더는 실행할 때 엶(rootfs의 libvulkan.so.1)
gcc -O2 -Wall -Wextra -o "$OUT/ezs-present-probe" native/ezspresent/ezs_present_probe.c -ldl
strip --strip-unneeded "$OUT/ezs-present-probe"
objdump -p "$OUT/ezs-present-probe" | grep NEEDED
# Present layer (game frames straight to the app compositor), only its negotiate entry exported
# present 레이어(게임 프레임을 앱 컴포지터로 바로 보냄). 협상 진입점만 내보냄
gcc -shared -fPIC -O2 -Wall -Wextra -fvisibility=hidden -o "$OUT/libezs_present_layer.so" \
    native/ezspresent/ezs_present_layer.c -lpthread
strip --strip-unneeded "$OUT/libezs_present_layer.so"
nm -D "$OUT/libezs_present_layer.so" | grep -q ' T ezs_NegotiateLoaderLayerInterfaceVersion$'
objdump -p "$OUT/libezs_present_layer.so" | grep NEEDED

# Guest Wayland compositor for the Xwayland screen mode (native/ezswl): protocol code generated
# from Debian's wayland-protocols, needs only libwayland-server
# Xwayland 화면 방식용 rootfs 안 Wayland 컴포지터(native/ezswl): 프로토콜 코드는 Debian
# wayland-protocols에서 생성함. libwayland-server만 필요함
WP=/usr/share/wayland-protocols
G=/tmp/ezswl-gen
mkdir -p "$G"
wayland-scanner server-header "$WP/stable/xdg-shell/xdg-shell.xml" "$G/xdg-shell-server-protocol.h"
wayland-scanner private-code "$WP/stable/xdg-shell/xdg-shell.xml" "$G/xdg-shell-protocol.c"
wayland-scanner server-header "$WP/stable/linux-dmabuf/linux-dmabuf-v1.xml" "$G/linux-dmabuf-v1-server-protocol.h"
wayland-scanner private-code "$WP/stable/linux-dmabuf/linux-dmabuf-v1.xml" "$G/linux-dmabuf-v1-protocol.c"
mkdir -p "$OUT/bin"
gcc -O2 -Wall -Wextra -I"$G" -o "$OUT/bin/ezs-wl" native/ezswl/ezs_wl.c "$G/xdg-shell-protocol.c" \
    "$G/linux-dmabuf-v1-protocol.c" $(pkg-config --cflags --libs wayland-server)
strip --strip-unneeded "$OUT/bin/ezs-wl"
objdump -p "$OUT/bin/ezs-wl" | grep NEEDED

# libdrm checks for the Android GPU device (native/ezsdrm), preloaded only with the "GPU device"
# test switch; no libdrm link (the real one is reached through RTLD_NEXT)
# 안드로이드 GPU 장치용 libdrm 검사 심(native/ezsdrm). "GPU 장치" 시험 스위치일 때만 미리 불러옴.
# libdrm에 링크하지 않음(실제 libdrm은 RTLD_NEXT로 부름)
gcc -shared -fPIC -O2 -Wall -Wextra -o "$OUT/libezs_drm.so" native/ezsdrm/ezs_drm.c -ldl -lpthread
strip --strip-unneeded "$OUT/libezs_drm.so"
nm -D "$OUT/libezs_drm.so" | grep -q ' T drmGetDevice2$'
nm -D "$OUT/libezs_drm.so" | grep -q ' T drmPrimeFDToHandle$'

# Crash reporter for diagnosis (native/ezscrash): GPU path check and the web helper in the Zink GL test
# 진단용 크래시 보고기(native/ezscrash): GPU 경로 점검과 Zink GL 시험의 웹 도우미
gcc -shared -fPIC -O2 -Wall -Wextra -o "$OUT/libezs_crash.so" native/ezscrash/ezs_crash.c -ldl
strip --strip-unneeded "$OUT/libezs_crash.so"
nm -D "$OUT/libezs_crash.so" | grep -q ' T sigaction$'
# GLX check program of the GPU path check (native/ezsgltest), links glvnd libGL + libX11
# GPU 경로 점검의 GLX 확인 프로그램(native/ezsgltest), glvnd libGL + libX11에 링크
gcc -O2 -Wall -Wextra -o "$OUT/bin/ezs-gltest" native/ezsgltest/ezs_gltest.c -lGL -lX11 -lpthread
strip --strip-unneeded "$OUT/bin/ezs-gltest"
objdump -p "$OUT/bin/ezs-gltest" | grep NEEDED

dpkg-query -W -f='libasound2-dev ${Version}\n' libasound2-dev > "$OUT/alsa-plugin.version"
echo "SDL $SDL_TAG, SDL3 headers $SDL3_TAG" >> "$OUT/alsa-plugin.version"
ls -l "$OUT"
