#!/bin/sh
# Cross-build libezglibc.so for the device (aarch64 glibc), from Ubuntu or WSL Ubuntu.
# 기기용(aarch64 glibc) libezglibc.so를 Ubuntu 또는 WSL Ubuntu에서 교차 빌드함
#
#   sudo apt install gcc-aarch64-linux-gnu make     (once / 처음 한 번)
#   sh tools/build-ezglibc-aarch64.sh
#
# The result needs glibc 2.34 or newer in the rootfs (pthread/dl symbols moved into libc).
# 결과물은 rootfs의 glibc가 2.34 이상이어야 함(pthread·dl 함수가 libc로 합쳐진 버전)
set -eu

HERE="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$HERE/native/ezglibc"
OUT="$HERE/out/aarch64"

# Separate object dir so host test objects are not mixed in
# 호스트 시험용 오브젝트와 섞이지 않게 따로 빌드함
make -C "$SRC" clean
make -C "$SRC" CC=aarch64-linux-gnu-gcc
mkdir -p "$OUT"
cp "$SRC/libezglibc.so" "$OUT/libezglibc.so"
make -C "$SRC" clean

# Show the highest glibc symbol version required
# 필요한 glibc 최고 버전을 표시함
aarch64-linux-gnu-objdump -T "$OUT/libezglibc.so" | grep -o 'GLIBC_[0-9.]*' | sort -Vu | tail -1
echo "built: $OUT/libezglibc.so (kept for later work, not used at run time / 보관용, 실행에는 쓰지 않음)"
