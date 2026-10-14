#!/bin/sh
# Prepare the rootfs for Valve's Linux ARM64 Steam client and download its bootstrap from Valve's
# own update server (client-update.steamstatic.com). Nothing from Valve is stored in this project:
# the device downloads it directly. Runs as PRoot fake root on a user action only.
#   steam-install.sh [stable|publicbeta]
# Steps: "steam" user entry (uid 1000), libraries for the client and its web helper (CEF),
# libvpx.so.6 name, bootstrap zip (manifest "steam_client_linuxarm64", sha256 checked when the
# manifest has it), unpacked to ~/.local/share/Steam with the folder links the client expects.
# The client downloads the rest (several hundred MB) itself on its first start.
# Valve Linux ARM64 Steam 클라이언트용 rootfs 준비 + Valve 자체 업데이트 서버
# (client-update.steamstatic.com)에서 부트스트랩 받기. 이 프로젝트는 Valve 파일을 보관하지 않고
# 기기가 직접 받음. 사용자 조작 때만 PRoot 가짜 root로 실행함.
#   steam-install.sh [stable|publicbeta]
# 순서: "steam" 사용자 항목(uid 1000), 클라이언트·웹 도우미(CEF)용 라이브러리, libvpx.so.6 이름,
# 부트스트랩 zip(매니페스트 "steam_client_linuxarm64", 매니페스트에 sha256이 있으면 확인),
# ~/.local/share/Steam에 풀고 클라이언트가 기대하는 폴더 링크 생성.
# 나머지(수백 MB)는 클라이언트가 첫 실행 때 스스로 받음
set -u
CHANNEL=${1:-stable}
BASE=https://client-update.steamstatic.com
case "$CHANNEL" in
    publicbeta) MANIFEST=steam_client_publicbeta_linuxarm64 ;;
    *) MANIFEST=steam_client_linuxarm64; CHANNEL=stable ;;
esac
STEAM_HOME=/home/steam
STEAM_DIR=$STEAM_HOME/.local/share/Steam
TMP=$STEAM_HOME/.cache/ezs-steam
export DEBIAN_FRONTEND=noninteractive
APT="apt-get -o APT::Sandbox::User=root -o Dpkg::Use-Pty=0 -q"

# 1. "steam" user (uid/gid 1000) so the client running with PRoot -i 1000:1000 has a name and home
# 1. "steam" 사용자(uid/gid 1000): PRoot -i 1000:1000으로 실행하는 클라이언트가 이름·홈을 갖게 함
grep -q '^steam:' /etc/group || echo 'steam:x:1000:' >> /etc/group
grep -q '^steam:' /etc/passwd || echo 'steam:x:1000:1000:Steam:/home/steam:/bin/sh' >> /etc/passwd
echo "OK   user : $(grep '^steam:' /etc/passwd)"

# 2. Packages. lsof: the client finds its UI port with it (UI does not connect without).
#    gtk2, ibus, libvpx, nss, SDL2: listed by the ARM64 client packagings. The rest: libraries the
#    Chromium-based web helper commonly loads. Each missing name is reported, the rest still installs.
# 2. 패키지. lsof: 클라이언트가 UI 포트를 찾는 데 씀(없으면 UI가 연결되지 않음).
#    gtk2, ibus, libvpx, nss, SDL2: ARM64 클라이언트 패키징들에 나온 항목. 나머지: Chromium 기반
#    웹 도우미가 흔히 여는 라이브러리. 없는 이름은 알려 주고 나머지는 그대로 설치함
PKGS="lsof unzip curl ca-certificates xdg-utils xdg-user-dirs procps file
libgtk2.0-0t64 libibus-1.0-5 libvpx9 libnss3 libnspr4 libsdl2-2.0-0 libopenal1 libusb-1.0-0
libatk1.0-0t64 libatk-bridge2.0-0t64 libcups2t64 libdrm2 libgbm1 libxcomposite1 libxdamage1
libxrandr2 libxfixes3 libxkbcommon0 libxss1 libxtst6 libxi6 libxcursor1 libxinerama1
libasound2t64 libpulse0 libpango-1.0-0 libcairo2 libdbus-1-3 libudev1 libgl1 libegl1
libfontconfig1 fonts-dejavu-core"
echo "INFO resolv : $(grep -c nameserver /etc/resolv.conf 2>/dev/null) nameserver"
before=$(du -sk /usr 2>/dev/null | cut -f1)
$APT update || { echo "FAIL apt-update : 네트워크·DNS 확인"; exit 1; }
# shellcheck disable=SC2086
if ! $APT install -y --no-install-recommends $PKGS; then
    # Retry one by one to find the names this release does not have
    # 이 배포판에 없는 이름을 찾으려고 하나씩 다시 시도함
    for p in $PKGS; do
        $APT install -y --no-install-recommends "$p" >/dev/null 2>&1 || echo "FAIL pkg : $p"
    done
fi
$APT clean
rm -rf /var/lib/apt/lists/*
after=$(du -sk /usr 2>/dev/null | cut -f1)
echo "INFO size : /usr +$(( (after - before) / 1024 )) MB"

# 3. The client asks for libvpx.so.6; trixie ships libvpx.so.9 (same link the packagings make)
# 3. 클라이언트는 libvpx.so.6을 찾고 trixie에는 libvpx.so.9가 있음(패키징들과 같은 링크)
LIBDIR=/usr/lib/aarch64-linux-gnu
if [ ! -e $LIBDIR/libvpx.so.6 ] && [ -e $LIBDIR/libvpx.so.9 ]; then
    ln -s libvpx.so.9 $LIBDIR/libvpx.so.6 && echo "OK   libvpx : libvpx.so.6 -> libvpx.so.9"
fi

# 4. Bootstrap from Valve's update server / 4. Valve 업데이트 서버에서 부트스트랩 받기
mkdir -p "$TMP" "$STEAM_DIR"
if ! curl -fsSL --retry 3 -o "$TMP/manifest" "$BASE/$MANIFEST"; then
    echo "FAIL manifest : $BASE/$MANIFEST"; exit 1
fi
# Read "file" and "sha2" of the bins_linuxarm64_linuxarm64 entry (Valve KeyValues text)
# bins_linuxarm64_linuxarm64 항목의 "file", "sha2"를 읽음(Valve KeyValues 텍스트)
eval "$(python3 - "$TMP/manifest" <<'EOF'
import re, sys
t = open(sys.argv[1], encoding="utf-8", errors="replace").read()
m = re.search(r'"bins_linuxarm64_linuxarm64"\s*\{([^{}]*)\}', t)
body = m.group(1) if m else ""
f = re.search(r'"file"\s+"([^"]+)"', body) or re.search(r'"file"\s+"(bins_linuxarm64_linuxarm64\.zip[^"]*)"', t)
s = re.search(r'"sha2"\s+"([0-9a-fA-F]{64})"', body)
v = re.search(r'"version"\s+"([^"]+)"', t)
print(f"ZIP='{f.group(1) if f else ''}'")
print(f"SHA2='{s.group(1).lower() if s else ''}'")
print(f"VER='{v.group(1) if v else '?'}'")
EOF
)"
if [ -z "$ZIP" ]; then
    echo "FAIL manifest : bins_linuxarm64_linuxarm64 항목 없음"; head -c 600 "$TMP/manifest"; exit 1
fi
echo "INFO manifest : $CHANNEL version=$VER file=$ZIP"
echo "INFO download : 받는 중 (약 100 MB)"
curl -fsSL --retry 3 -o "$TMP/bins.zip" "$BASE/$ZIP" && [ -s "$TMP/bins.zip" ] \
    || { echo "FAIL download : $BASE/$ZIP"; exit 1; }
got=$(sha256sum "$TMP/bins.zip" | cut -c1-64)
if [ -n "$SHA2" ]; then
    [ "$got" = "$SHA2" ] || { echo "FAIL sha256 : $got != $SHA2"; rm -f "$TMP/bins.zip"; exit 1; }
    echo "OK   sha256 : $got"
else
    echo "INFO sha256 : $got (매니페스트에 sha2 없음, 대조 생략)"
fi

# 5. Unpack and create the layout the client expects / 5. 풀고 클라이언트가 기대하는 배치를 만듦
unzip -oq "$TMP/bins.zip" -d "$STEAM_DIR" || { echo "FAIL unzip"; exit 1; }
rm -rf "$TMP"
cd "$STEAM_DIR" || exit 1
[ -e steamrt64 ] || ln -s steamrtarm64 steamrt64
touch .steam-enable-steamrt64-client
mkdir -p package
if [ "$CHANNEL" = publicbeta ]; then echo publicbeta > package/beta; else rm -f package/beta; fi
# Executables named by the ARM64 client packagings (wherever they are in the zip)
# ARM64 클라이언트 패키징들이 실행 권한을 주는 파일(zip 안 위치와 무관하게)
find . -maxdepth 3 -type f \( -name steam -o -name steamwebhelper -o -name steamwebhelper.sh \
    -o -name gldriverquery -o -name vulkandriverquery -o -name steamsysinfo -o -name steam.sh \) \
    -exec chmod 755 {} +
mkdir -p "$STEAM_HOME/.steam"
for n in root steam; do ln -sfn "$STEAM_DIR" "$STEAM_HOME/.steam/$n"; done
ln -sfn "$STEAM_DIR/steamrt64" "$STEAM_HOME/.steam/bin64"
ln -sfn "$STEAM_DIR/steamrtarm64" "$STEAM_HOME/.steam/steamrtarm64"

if [ -f steamrtarm64/steam ]; then
    echo "OK   steam : $(du -sh "$STEAM_DIR" | cut -f1) $(file -b steamrtarm64/steam | cut -c1-60)"
else
    echo "FAIL steam : steamrtarm64/steam 없음 (zip 구성이 예상과 다름)"; ls "$STEAM_DIR" | head -n 20
fi
echo "DONE"
