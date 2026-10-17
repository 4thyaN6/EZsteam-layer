#!/bin/sh
# Find shared libraries the installed Steam client needs but the rootfs lacks (ldd over the
# client's ELF files), and in "install" mode install the Debian packages that provide them.
# The client updates itself on its first start, so this is run again after that update.
#   steam-deps.sh check     -> report only (any user)
#   steam-deps.sh install   -> report + apt install (PRoot fake root, network)
# Package names are guessed from the library name (libfoo-1.2.so.3 -> libfoo-1.2-3 / ...t64,
# libbar.so.4 -> libbar4 / libbar4t64); the first name apt knows is installed.
# 설치된 Steam 클라이언트가 필요로 하지만 rootfs에 없는 공유 라이브러리를 찾음(클라이언트 ELF
# 파일에 ldd), "install" 모드에서는 그것을 제공하는 Debian 패키지를 설치함.
# 클라이언트는 첫 시작 때 스스로 갱신하므로 갱신 뒤에 다시 실행함.
#   steam-deps.sh check     -> 보고만 함(아무 사용자)
#   steam-deps.sh install   -> 보고 + apt 설치(PRoot 가짜 root, 네트워크)
# 패키지 이름은 라이브러리 이름에서 추정함(libfoo-1.2.so.3 -> libfoo-1.2-3 / ...t64,
# libbar.so.4 -> libbar4 / libbar4t64). apt가 아는 첫 이름을 설치함
set -u
MODE=${1:-check}
STEAM_DIR=/home/steam/.local/share/Steam
APT="apt-get -o APT::Sandbox::User=root -o Dpkg::Use-Pty=0 -q"
[ -d "$STEAM_DIR/steamrtarm64" ] || { echo "INFO deps : 클라이언트 없음"; exit 0; }

# List missing sonames. Folders of the client that hold libraries are put on the search path so
# libraries shipped with the client are not counted as missing.
# 없는 soname 목록. 클라이언트 안 라이브러리 폴더를 검색 경로에 넣어 클라이언트가 함께 주는
# 라이브러리는 없는 것으로 세지 않음
scan() {
    # (no empty entry: an empty LD_LIBRARY_PATH entry means the current folder)
    # (빈 항목 없음: LD_LIBRARY_PATH의 빈 항목은 현재 폴더를 뜻함)
    libdirs=$(find "$STEAM_DIR/steamrtarm64" "$STEAM_DIR/linuxarm64" -maxdepth 3 -name '*.so*' -type f \
        2>/dev/null | xargs -r -n1 dirname | sort -u | paste -sd: -)
    find "$STEAM_DIR/steamrtarm64" "$STEAM_DIR/linuxarm64" -maxdepth 3 -type f \
        \( -name '*.so*' -o -name steam -o -name steamwebhelper -o -name '*query' -o -name steamsysinfo \) \
        2>/dev/null | while IFS= read -r f; do
        # ELF files only (magic 7f 45 4c 46) / ELF 파일만(매직 7f 45 4c 46)
        [ "$(od -An -tx1 -N4 "$f" 2>/dev/null | tr -d ' \n')" = 7f454c46 ] || continue
        LD_LIBRARY_PATH="$libdirs" ldd "$f" 2>/dev/null | awk '/not found/ {print $1}'
    done | sort -u
}

missing=$(scan)
# Libraries the client ships itself somewhere deeper in its folder (e.g. libcef.so and its video
# libraries) are not missing: the client loads them from there. A tester got FAIL lines for
# libcef.so / libav*.so.* that apt cannot provide
# 클라이언트가 자기 폴더의 더 깊은 곳에 직접 가진 라이브러리(예: libcef.so와 영상 라이브러리)는 빠진 것이
# 아님: 클라이언트가 그곳에서 불러옴. 테스터에게 apt로 줄 수 없는 libcef.so / libav*.so.* FAIL 줄이 나왔음
own=$(echo "$missing" | while IFS= read -r so; do
    [ -n "$so" ] && [ -n "$(find "$STEAM_DIR" -name "$so" \( -type f -o -type l \) -print -quit 2>/dev/null)" ] && echo "$so"
done)
if [ -n "$own" ]; then
    echo "INFO deps : 클라이언트가 직접 가진 라이브러리(설치 불필요): $(echo "$own" | tr '\n' ' ')"
    missing=$(echo "$missing" | grep -vxF "$own")
fi
if [ -z "$missing" ]; then
    echo "OK   deps : 없는 라이브러리 없음"
    exit 0
fi
echo "INFO deps : 없는 라이브러리 $(echo "$missing" | wc -l)개: $(echo "$missing" | tr '\n' ' ')"
if [ "$MODE" != install ]; then
    echo "WARN deps : [Steam 설치]를 다시 누르면 위 라이브러리를 찾아 설치함"
    exit 0
fi

# Package lists may have been removed by an earlier cleanup / 이전 정리로 패키지 목록이 없을 수 있음
ls /var/lib/apt/lists/*Packages >/dev/null 2>&1 || $APT update >/dev/null || { echo "FAIL apt-update"; exit 1; }

# Candidate package names per soname / soname별 패키지 이름 후보
echo "$missing" | python3 -c '
import re, sys
for so in sys.stdin.read().split():
    m = re.match(r"(lib.+?)\.so\.(\d+)", so)
    if not m:
        print(so); continue
    base, ver = m.group(1).lower(), m.group(2)
    sep = "-" if base[-1].isdigit() else ""
    c = [base + sep + ver, base + sep + ver + "t64", base + "-" + ver, base + "-" + ver + "t64"]
    print(so, *dict.fromkeys(c))
' | while read -r so cands; do
    pkg=""
    for c in $cands; do
        if apt-cache show "$c" >/dev/null 2>&1; then pkg=$c; break; fi
    done
    if [ -z "$pkg" ]; then
        echo "FAIL deps : $so 패키지 이름을 찾지 못함 (후보: ${cands:-없음})"
    elif $APT install -y --no-install-recommends "$pkg" >/dev/null 2>&1; then
        echo "OK   deps : $so <- $pkg"
    else
        echo "FAIL deps : $so <- $pkg 설치 실패"
    fi
done

left=$(scan)
[ -n "$own" ] && [ -n "$left" ] && left=$(echo "$left" | grep -vxF "$own")
if [ -z "$left" ]; then
    echo "OK   deps : 모두 해결"
else
    echo "WARN deps : 남은 라이브러리: $(echo "$left" | tr '\n' ' ')"
fi
