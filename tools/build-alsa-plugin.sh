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
# Output / 결과물: libasound_module_pcm_ezs.so, libezs_pad.so, alsa-plugin.version
set -eu
OUT="${1:-out-alsa}"

apt-get update -q
apt-get install -y -q --no-install-recommends gcc libc6-dev libasound2-dev libsdl2-dev binutils

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
# Controller library: SDL2 headers only, SDL2 itself is loaded at run time (dlopen), so it needs
# only glibc + libdl; the constructor must be present
# 컨트롤러 라이브러리: SDL2 헤더만 씀. SDL2는 실행 중에 불러오므로(dlopen) glibc + libdl만
# 필요함. 생성자가 들어 있어야 함
gcc -shared -fPIC -O2 -Wall -Wextra \
    -o "$OUT/libezs_pad.so" native/ezspad/ezs_pad.c -ldl
strip --strip-unneeded "$OUT/libezs_pad.so"
objdump -p "$OUT/libezs_pad.so" | grep NEEDED
if objdump -p "$OUT/libezs_pad.so" | grep NEEDED | grep -q SDL; then
    echo "libezs_pad.so must not link SDL2 directly" >&2
    exit 1
fi

dpkg-query -W -f='libasound2-dev ${Version}\n' libasound2-dev > "$OUT/alsa-plugin.version"
dpkg-query -W -f='libsdl2-dev ${Version}\n' libsdl2-dev >> "$OUT/alsa-plugin.version"
ls -l "$OUT"
