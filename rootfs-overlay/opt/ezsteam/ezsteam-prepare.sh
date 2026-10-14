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

if [ -n "$CLIENT" ]; then
    mkdir -p "$HOME/.steam/sdkarm64"
    ln -sfn "$CLIENT" "$HOME/.steam/sdkarm64/steamclient.so"
    echo "$LOG_TAG sdkarm64 -> $CLIENT"
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
