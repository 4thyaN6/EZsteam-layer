#!/bin/sh
# Build the glibc aarch64 rootfs published on GitHub Release (downloaded by the app, not in the APK).
# GitHub Release에 올릴 glibc aarch64 rootfs를 만듦(앱이 내려받음, APK에는 넣지 않음)
#
#   sh tools/build-rootfs.sh <output dir>
#
# The rootfs is run through PRoot (shipped in the APK), so no preload library is put inside.
# Base is Debian 13 trixie, the same release Steam Linux Runtime 4 (steamrt4) is based on,
# because this rootfs stands in for that runtime when Valve's ARM64 Proton runs.
# rootfs는 APK에 든 PRoot로 실행하므로 preload 라이브러리를 넣지 않음.
# 기반은 Debian 13 trixie: Valve ARM64 Proton 실행 때 이 rootfs가 Steam Linux Runtime 4(steamrt4)를
# 대신하므로, steamrt4와 같은 기반 릴리스로 맞춤
#
# Output / 결과물:
#   rootfs.tar.xz          rootfs archive / rootfs 압축 파일
#   rootfs.tar.xz.sha256   checksum checked by RootfsDownloader / RootfsDownloader가 확인하는 체크섬
#   rootfs.version         version string / 버전 문자열
#   rootfs.packages.txt    Debian package list (license reference) / Debian 패키지 목록(라이선스 확인용)
#
# Needs docker able to run linux/arm64 (native arm64 host, or binfmt/qemu on x86_64).
# linux/arm64 컨테이너를 돌릴 수 있는 docker가 필요함(arm64 호스트 또는 x86_64 + binfmt)
set -eu

OUT="$1"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="${EZS_BASE_IMAGE:-debian:trixie}"
PKGS="python3 binutils ca-certificates"
WORK="$(mktemp -d)"
NAME="ezs-rootfs-$$"

# Use sudo only when not root (tar needs root to keep ownership and special bits)
# root가 아닐 때만 sudo 사용(tar가 소유자·특수 비트를 유지하려면 root 필요)
SUDO=""
[ "$(id -u)" = "0" ] || SUDO="sudo"

# 1. Base system with the packages Proton needs from the host
#    Proton이 호스트에서 쓰는 패키지를 넣은 기본 시스템
# Package list is written inside the image so the rootfs carries it too (/opt/ezsteam/packages.txt)
# 패키지 목록을 이미지 안에서 기록해 rootfs에도 남김(/opt/ezsteam/packages.txt)
# Docs/man/info are removed to save space, but each package's copyright file is kept (license notice)
# 용량을 줄이려고 문서·man·info를 지우되 패키지별 copyright 파일은 남김(라이선스 고지)
docker run --platform linux/arm64 --name "$NAME" "$IMAGE" sh -c "
    apt-get update &&
    apt-get install -y --no-install-recommends $PKGS &&
    apt-get clean && rm -rf /var/lib/apt/lists/* &&
    mkdir -p /opt/ezsteam &&
    dpkg-query -W -f='\${Package}\t\${Version}\n' > /opt/ezsteam/packages.txt &&
    rm -rf /usr/share/man /usr/share/info &&
    find /usr/share/doc -mindepth 1 ! -type d ! -name copyright -delete &&
    find /usr/share/doc -mindepth 1 -type d -empty -delete"
docker export "$NAME" -o "$WORK/base.tar"
docker rm "$NAME" >/dev/null

# 2. Unpack, make absolute symlinks relative (PRoot resolves both, but relative links also
#    work when the tree is inspected from outside, e.g. by the app's installer)
#    풀고 절대 링크를 상대 링크로 바꿈(PRoot는 둘 다 풀지만, 상대 링크는 앱 설치기처럼
#    밖에서 볼 때도 맞게 동작함)
mkdir "$WORK/r"
$SUDO tar -xpf "$WORK/base.tar" -C "$WORK/r"
$SUDO sh "$HERE/tools/fix-abs-symlinks.sh" "$WORK/r" 2>/dev/null

# 3. Layer files: Steam helper scripts, folders the layer expects
#    레이어 파일: Steam 보조 스크립트, 레이어가 쓰는 폴더
$SUDO mkdir -p "$WORK/r/dev/shm" "$WORK/r/home/steam" "$WORK/r/run/user/ezg"
$SUDO cp -r "$HERE/rootfs-overlay/opt/ezsteam" "$WORK/r/opt/"
$SUDO chmod 755 "$WORK/r/opt/ezsteam/"*.sh "$WORK/r/opt/ezsteam/compat/ezproton/ezproton"

# Docker leaves bind-mount placeholders; the app writes resolv.conf later if needed
# docker가 남긴 마운트 자리 파일 정리. resolv.conf는 필요하면 앱이 나중에 씀
$SUDO rm -f "$WORK/r/.dockerenv"
$SUDO sh -c ": > '$WORK/r/etc/resolv.conf'; : > '$WORK/r/etc/hostname'"

# 4. Pack (device/fifo nodes are skipped by the app's extractor anyway)
#    묶기(장치·FIFO 항목은 앱 압축 풀기에서 어차피 건너뜀)
mkdir -p "$OUT"
$SUDO tar --numeric-owner -cf "$OUT/rootfs.tar" -C "$WORK/r" .
$SUDO chown "$(id -u):$(id -g)" "$OUT/rootfs.tar"
cp "$WORK/r/opt/ezsteam/packages.txt" "$OUT/rootfs.packages.txt"

# 5. Compress with xz (decoded on the device by XZ for Java 1.9, same as EZlator), then checksum
#    xz로 압축(기기에서는 EZlator와 같은 XZ for Java 1.9로 풂) 후 체크섬 생성
#    -9: 64 MiB dictionary, needs about 65 MiB of memory to decode / 풀 때 약 65MiB 메모리 필요
rm -f "$OUT/rootfs.tar.xz"
xz -T0 -9 "$OUT/rootfs.tar"
( cd "$OUT" && sha256sum rootfs.tar.xz > rootfs.tar.xz.sha256 )

# Version string = base image + date + git commit (if any)
# 버전 문자열 = 기본 이미지 + 날짜 + git 커밋(있으면)
REV="$(git -C "$HERE" rev-parse --short HEAD 2>/dev/null || echo local)"
echo "$IMAGE-$(date -u +%Y%m%d%H%M)-$REV" > "$OUT/rootfs.version"

$SUDO rm -rf "$WORK"
ls -l "$OUT"
