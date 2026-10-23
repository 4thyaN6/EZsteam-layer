#!/bin/sh
# Build the app side of the game frame path (native/ezspresent/ezs_present_jni.c ->
# libezs_present.so) for Android arm64 with the NDK, API 26 like the runtime module's minSdk.
# Only NDK system libraries are linked (EGL, GLESv2, nativewindow, log).
#   ANDROID_NDK=<ndk dir> sh tools/build-present-jni.sh <out dir>
# 게임 프레임 경로의 앱 쪽(native/ezspresent/ezs_present_jni.c -> libezs_present.so)을 NDK로 안드로이드
# arm64용으로 빌드함. API 26은 런타임 모듈 minSdk와 같음. NDK 시스템 라이브러리만 연결함(EGL, GLESv2,
# nativewindow, log)
#   ANDROID_NDK=<ndk 폴더> sh tools/build-present-jni.sh <출력 폴더>
set -eu
OUT=${1:?out dir}
API=26
NDK="${ANDROID_NDK:-${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}}"
[ -n "$NDK" ] || { echo "ANDROID_NDK not set" >&2; exit 1; }
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$BIN/aarch64-linux-android$API-clang"
mkdir -p "$OUT"
"$CC" -shared -fPIC -O2 -Wall -Wextra -Werror=implicit-function-declaration \
    -Wl,-z,max-page-size=16384 \
    -o "$OUT/libezs_present.so" native/ezspresent/ezs_present_jni.c \
    -lEGL -lGLESv2 -lnativewindow -llog
"$BIN/llvm-strip" --strip-unneeded "$OUT/libezs_present.so"
# The three JNI entry points must be exported / JNI 진입점 세 개가 내보내져야 함
for f in nativeListen nativeServe nativeClose; do
    "$BIN/llvm-nm" -D "$OUT/libezs_present.so" | grep -q " T Java_app_ezsteam_runtime_PresentProbe_$f\$"
done
"$BIN/llvm-readelf" -d "$OUT/libezs_present.so" | grep NEEDED
echo "ndk: $(basename "$NDK"), API $API" > "$OUT/present-jni.version"
