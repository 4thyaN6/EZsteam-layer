#!/bin/sh
# Build PRoot (termux/proot, GPL-2.0-or-later) for Android arm64 with the NDK, plus its loader.
# Output goes into the runtime module's jniLibs so the APK extracts both into nativeLibraryDir,
# where an app with targetSdk 28 may exec them.
# 안드로이드 arm64용 PRoot(termux/proot, GPL-2.0-or-later)와 로더를 NDK로 빌드함.
# 결과물을 런타임 모듈 jniLibs에 넣어 APK가 nativeLibraryDir로 풀게 하고, 거기서 실행함
#
#   ANDROID_NDK=<ndk dir> sh tools/build-proot-android.sh [output dir]
#
# Output / 결과물:
#   libproot.so          PRoot itself (bionic, talloc linked statically) / PRoot 본체(bionic, talloc 정적 링크)
#   libproot-loader.so   static loader PRoot maps into each tracee (PROOT_LOADER) / 각 프로세스에 올리는 정적 로더
#   proot-source.txt     exact sources used (GPL/LGPL source offer), written to the runtime assets
#                        사용한 소스 정보(GPL·LGPL 소스 제공용), 런타임 assets에 기록
set -eu

# Pinned sources: termux/proot commit and talloc (single-file copy used by Winlator-Cmod, LGPL-3.0-or-later)
# 고정 소스: termux/proot 커밋, talloc(Winlator-Cmod가 쓰는 단일 파일본, LGPL-3.0-or-later)
PROOT_REPO=https://github.com/termux/proot
PROOT_REV=b8c1f5c5c6730dc9d521d5bd5ce1cfeedecde087
TALLOC_BASE=${TALLOC_BASE:-https://raw.githubusercontent.com/coffincolors/winlator/49bfb90fce932107cf6a7c1af62f910e7a1a2bfd/app/src/main/cpp/proot/talloc}
TALLOC_C_SHA256=8d307d64811c3599014592567786e9ba3f4c159ce4a2efac7b67815ab8e642a3
TALLOC_H_SHA256=62fb2e092e7c771ea9fa1af36a001b925d085c6ad3611b77257ae0930a89ae05
API=26

HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$HERE/runtime/src/main/jniLibs/arm64-v8a}"
SRCINFO="${SRCINFO:-$HERE/runtime/src/main/assets/proot-source.txt}"
NDK="${ANDROID_NDK:-${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}}"
[ -n "$NDK" ] || { echo "ANDROID_NDK not set" >&2; exit 1; }
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$BIN/aarch64-linux-android$API-clang"
[ -x "$CC" ] || { echo "compiler not found: $CC" >&2; exit 1; }
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# 1. Sources (talloc checked against pinned hashes)
#    소스 받기(talloc은 고정 해시로 확인)
git clone -q "$PROOT_REPO" "$WORK/proot"
git -C "$WORK/proot" checkout -q "$PROOT_REV"
mkdir -p "$WORK/talloc"
curl -fsSL -o "$WORK/talloc/talloc.c" "$TALLOC_BASE/talloc.c"
curl -fsSL -o "$WORK/talloc/talloc.h" "$TALLOC_BASE/talloc.h"
( cd "$WORK/talloc" && printf '%s  talloc.c\n%s  talloc.h\n' "$TALLOC_C_SHA256" "$TALLOC_H_SHA256" | sha256sum -c - )

# 2. talloc as a static library (stdint.h is not pulled in by this single-file copy)
#    talloc 정적 라이브러리(이 단일 파일본은 stdint.h를 스스로 포함하지 않음)
"$CC" -O2 -fPIC -include stdint.h -c "$WORK/talloc/talloc.c" -o "$WORK/talloc/talloc.o"
"$BIN/llvm-ar" rcs "$WORK/talloc/libtalloc.a" "$WORK/talloc/talloc.o"

# 3. PRoot + 64-bit loader. Flags go through the environment because the makefile appends (+=).
#    HAS_LOADER_32BIT is cleared: no 32-bit ARM programs are run (Valve ARM64 Proton is 64-bit only).
#    PROOT_UNBUNDLE_LOADER keeps the loader as a separate file; the app passes its path in PROOT_LOADER.
#    PRoot와 64비트 로더 빌드. makefile이 +=로 덧붙이므로 플래그는 환경변수로 넘김.
#    32비트 ARM 프로그램은 실행하지 않으므로 HAS_LOADER_32BIT를 비움(Valve ARM64 Proton은 64비트 전용).
#    PROOT_UNBUNDLE_LOADER로 로더를 별도 파일로 두고, 앱이 PROOT_LOADER로 경로를 넘김
CPPFLAGS="-I$WORK/talloc -DARG_MAX=131072" \
LDFLAGS="-L$WORK/talloc" \
make -C "$WORK/proot/src" -j"$(nproc)" \
    CC="$CC" STRIP="$BIN/llvm-strip" OBJCOPY="$BIN/llvm-objcopy" OBJDUMP="$BIN/llvm-objdump" \
    HAS_LOADER_32BIT= \
    PROOT_UNBUNDLE_LOADER=/nonexistent \
    proot loader/loader

# Features PRoot needs on Android: seccomp acceleration and process_vm_* memory access
# 안드로이드에서 필요한 기능: seccomp 가속, process_vm_* 메모리 접근
grep -q '^#define HAVE_SECCOMP_FILTER' "$WORK/proot/src/build.h"
grep -q '^#define HAVE_PROCESS_VM' "$WORK/proot/src/build.h"

# 4. Install under jniLibs names (only lib*.so files are packaged and extracted)
#    jniLibs 이름으로 설치(lib*.so 이름만 APK에 들어가고 풀림)
mkdir -p "$OUT"
cp "$WORK/proot/src/proot" "$OUT/libproot.so"
cp "$WORK/proot/src/loader/loader" "$OUT/libproot-loader.so"
"$BIN/llvm-strip" "$OUT/libproot.so"
chmod 755 "$OUT/libproot.so" "$OUT/libproot-loader.so"

# Only system libraries may be needed by libproot.so (talloc is static)
# libproot.so는 시스템 라이브러리만 필요해야 함(talloc은 정적)
"$BIN/llvm-readelf" -d "$OUT/libproot.so" | grep NEEDED || true

# Source record for the license notice / 라이선스 고지용 소스 기록
mkdir -p "$(dirname "$SRCINFO")"
cat > "$SRCINFO" <<EOF
proot: $PROOT_REPO commit $PROOT_REV (GPL-2.0-or-later)
talloc: $TALLOC_BASE (talloc 2.1.14 single-file copy, LGPL-3.0-or-later)
ndk: $(basename "$NDK"), API $API
EOF
ls -l "$OUT"
