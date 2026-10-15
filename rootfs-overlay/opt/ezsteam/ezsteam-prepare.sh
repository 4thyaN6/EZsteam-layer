#!/bin/sh
# Runs inside the rootfs before each Steam start (called by the Android launcher).
# Steam 시작 전마다 rootfs 안에서 실행됨(안드로이드 실행기가 부름)
#
# 1. Links $HOME/.steam/sdkarm64/steamclient.so to the aarch64 steamclient.so of the
#    installed client. Valve's ARM64 lsteamclient loads exactly that path (hardcoded).
#    Valve ARM64 lsteamclient가 고정으로 여는 $HOME/.steam/sdkarm64/steamclient.so를
#    설치된 클라이언트의 aarch64 steamclient.so에 연결함
# 2. Installs/updates the "EZ Proton" compatibility tool.
#    호환 도구 "EZ Proton"을 설치·갱신함
set -u

STEAM_DIR=${EZS_STEAM_DIR:-$HOME/.local/share/Steam}
SHARE=$(cd "$(dirname "$0")" && pwd)
LOG_TAG="ezsteam-prepare:"

mkdir -p "$HOME/.steam" "$STEAM_DIR"

# ~/.steam/root and ~/.steam/steam point at the install dir (Steam's own layout)
# ~/.steam/root, ~/.steam/steam은 설치 폴더를 가리킴(Steam 기본 배치)
for n in root steam; do
    [ -e "$HOME/.steam/$n" ] || ln -sfn "$STEAM_DIR" "$HOME/.steam/$n"
done

# True when the file is a 64-bit ELF for aarch64 (e_machine 0xb7 at offset 18)
# 파일이 aarch64용 64비트 ELF이면 참(오프셋 18의 e_machine 값이 0xb7)
is_aarch64_elf() {
    hdr=$(od -An -tx1 -N20 "$1" 2>/dev/null | tr -d ' \n')
    # 7f454c46 = ELF magic, 02 = 64-bit, bytes 18-19 = b700
    case "$hdr" in
        7f454c4602*) [ "$(printf '%s' "$hdr" | cut -c37-40)" = "b700" ] ;;
        *) return 1 ;;
    esac
}

# Find the aarch64 steamclient.so (folder name of the ARM64 client is not fixed here)
# aarch64 steamclient.so를 찾음(ARM64 클라이언트의 폴더 이름은 여기서 고정하지 않음)
CLIENT=""
find "$STEAM_DIR" -maxdepth 3 -name steamclient.so -type f 2>/dev/null > "$HOME/.steam/.ezs-scan"
while IFS= read -r f; do
    if is_aarch64_elf "$f"; then CLIENT=$f; break; fi
done < "$HOME/.steam/.ezs-scan"
rm -f "$HOME/.steam/.ezs-scan"

# ~/.steam/sdkarm64 is a folder of links: every file of the client's linuxarm64 folder (the client
# starts games through sdkarm64/steam-launch-wrapper), plus steamclient.so and steam-launch-wrapper
# from wherever the client keeps them. Steam's own files are not changed.
# ~/.steam/sdkarm64는 링크 폴더임: 클라이언트 linuxarm64 폴더의 모든 파일(클라이언트는
# sdkarm64/steam-launch-wrapper로 게임을 시작함) + 클라이언트가 둔 위치의 steamclient.so,
# steam-launch-wrapper. Steam 자체 파일은 바꾸지 않음
SDK=$HOME/.steam/sdkarm64
[ -L "$SDK" ] && rm -f "$SDK"
mkdir -p "$SDK"
find "$SDK" -maxdepth 1 -type l ! -exec test -e {} \; -delete 2>/dev/null
if [ -d "$STEAM_DIR/linuxarm64" ]; then
    for f in "$STEAM_DIR"/linuxarm64/*; do
        [ -e "$f" ] && ln -sfn "$f" "$SDK/$(basename "$f")"
    done
fi
if [ ! -e "$SDK/steam-launch-wrapper" ]; then
    w=$(find "$STEAM_DIR" -maxdepth 3 -name steam-launch-wrapper -type f 2>/dev/null | head -n 1)
    [ -n "$w" ] && ln -sfn "$w" "$SDK/steam-launch-wrapper"
fi
if [ -e "$SDK/steam-launch-wrapper" ]; then
    echo "$LOG_TAG launch wrapper -> $(readlink -f "$SDK/steam-launch-wrapper")"
else
    echo "$LOG_TAG steam-launch-wrapper not found yet"
fi

if [ -n "$CLIENT" ]; then
    # Keep an aarch64 steamclient.so from linuxarm64 when there is one / linuxarm64에 aarch64 steamclient.so가 있으면 그것을 유지
    if ! { [ -e "$SDK/steamclient.so" ] && is_aarch64_elf "$(readlink -f "$SDK/steamclient.so")"; }; then
        ln -sfn "$CLIENT" "$SDK/steamclient.so"
    fi
    echo "$LOG_TAG sdkarm64/steamclient.so -> $(readlink -f "$SDK/steamclient.so")"
else
    # First start: the client is not downloaded yet; the next start links it
    # 첫 실행: 아직 클라이언트를 받기 전이라 다음 실행 때 연결함
    echo "$LOG_TAG aarch64 steamclient.so not found yet"
fi

# Compatibility tool (always refreshed so updates of this layer take effect)
# 호환 도구(이 레이어 갱신이 반영되도록 매번 덮어씀)
DEST="$STEAM_DIR/compatibilitytools.d/ezproton"
mkdir -p "$DEST"
for f in compatibilitytool.vdf toolmanifest.vdf ezproton; do
    cp -f "$SHARE/compat/ezproton/$f" "$DEST/$f"
done
chmod 755 "$DEST/ezproton"
echo "$LOG_TAG compat tool -> $DEST"
