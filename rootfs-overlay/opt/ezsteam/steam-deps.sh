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

# Packages for the session's system bus and NetworkManager stand-in (Steam's connection header and
# Settings > Internet need NetworkManager; ezs-netstate.py, steam-run.sh): dbus-daemon, python3-gi.
# Not in older rootfs builds, so they are installed here the same way as missing libraries
# 세션 시스템 버스와 NetworkManager 대역용 패키지(Steam 연결 표시와 설정 > 인터넷에 NetworkManager가 필요함.
# ezs-netstate.py, steam-run.sh): dbus-daemon, python3-gi. 이전 rootfs에는 없어 없는 라이브러리와 같은 방식으로 여기서 설치함
EXTRA_PKGS="dbus-daemon python3-gi gir1.2-glib-2.0"
extra_missing() {
    command -v dbus-daemon >/dev/null 2>&1 && python3 -c 'import gi; gi.require_version("Gio", "2.0"); from gi.repository import Gio' 2>/dev/null \
        || echo "$EXTRA_PKGS"
}
extra=$(extra_missing)
if [ -n "$extra" ]; then
    if [ "$MODE" = install ]; then
        ls /var/lib/apt/lists/*Packages >/dev/null 2>&1 || $APT update >/dev/null || echo "FAIL apt-update"
        if out=$($APT install -y --no-install-recommends $extra 2>&1); then
            echo "OK   deps : 세션 버스·네트워크 대역 패키지 설치 ($extra)"
        else
            echo "FAIL deps : 세션 버스·네트워크 대역 패키지 설치 실패 ($extra)"
            echo "$out" | tail -n 3 | sed 's/^/FAIL deps :   /'
        fi
    elif [ -n "${EZS_DEPS_REQUEST:-}" ]; then
        : > "$EZS_DEPS_REQUEST"
        echo "NEED deps : $extra"
        exit 0
    else
        echo "WARN deps : 세션 버스·네트워크 대역 패키지 없음 ($extra)"
    fi
fi

# First Debian package apt knows for a soname, empty when none (works as any user)
# soname에 대해 apt가 아는 첫 데비안 패키지, 없으면 빈 값(아무 사용자로나 동작)
# A package apt can really install: known AND with an install candidate. "apt-cache show" also
# answers for names only referenced by other packages (libavcodec62 on trixie: "has no
# installation candidate", a fresh install failed on it, 2026-10-19)
# apt가 실제로 설치할 수 있는 패키지: 알려져 있고 설치 후보가 있음. "apt-cache show"는 다른 패키지가
# 언급만 하는 이름에도 답함(trixie의 libavcodec62: "설치 후보 없음". 새 설치가 이것으로 실패했음, 2026-10-19)
installable() {
    cand=$(apt-cache policy "$1" 2>/dev/null | awk '/Candidate:/ {print $2; exit}')
    [ -n "$cand" ] && [ "$cand" != "(none)" ]
}

pkg_for() {
    echo "$1" | python3 -c '
import re, sys
so = sys.stdin.read().strip()
m = re.match(r"(lib.+?)\.so\.(\d+)", so)
if m:
    base, ver = m.group(1).lower(), m.group(2)
    sep = "-" if base[-1].isdigit() else ""
    print(" ".join(dict.fromkeys([base + sep + ver, base + sep + ver + "t64", base + "-" + ver, base + "-" + ver + "t64"])))
' | tr ' ' '\n' | while IFS= read -r c; do
        [ -n "$c" ] && installable "$c" && { echo "$c"; break; }
    done
}
# Libraries the client ships itself somewhere deeper in its folder (e.g. libcef.so and its video
# libraries) are not missing: the client loads them from there. A tester got FAIL lines for
# libcef.so / libav*.so.* that apt cannot provide
# 클라이언트가 자기 폴더의 더 깊은 곳에 직접 가진 라이브러리(예: libcef.so와 영상 라이브러리)는 빠진 것이
# 아님: 클라이언트가 그곳에서 불러옴. 테스터에게 apt로 줄 수 없는 libcef.so / libav*.so.* FAIL 줄이 나왔음
# Only a real file counts (a link must lead to a file, -xtype f): a broken link must not hide a
# library that is really gone (Steam screen did not start on libcef.so, 2026-10-22)
# 실제 파일만 셈(링크는 파일로 이어져야 함, -xtype f): 끊어진 링크가 정말 없어진 라이브러리를 가리면 안 됨
# (libcef.so 때문에 Steam 화면이 뜨지 않았음, 2026-10-22)
own=$(echo "$missing" | while IFS= read -r so; do
    [ -n "$so" ] && [ -n "$(find "$STEAM_DIR" -name "$so" -xtype f -print -quit 2>/dev/null)" ] && echo "$so"
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
    # Libraries apt can provide (a package name it knows): the app installs them as root and
    # removes the request file (steam-run.sh waits for it). The client's own libraries (FFmpeg 8,
    # CEF) have no Debian package and are left to the client's file check.
    # apt가 줄 수 있는 라이브러리(아는 패키지 이름이 있음): 앱이 root로 설치하고 요청 파일을 지움
    # (steam-run.sh가 기다림). 클라이언트 자체 라이브러리(FFmpeg 8, CEF)는 데비안 패키지가 없어
    # 클라이언트 파일 검사에 맡김
    if ls /var/lib/apt/lists/*Packages >/dev/null 2>&1; then
        req=$(echo "$missing" | while IFS= read -r so; do
            [ -n "$so" ] && [ -n "$(pkg_for "$so")" ] && echo "$so"
        done | tr '\n' ' ')
    else
        # No package lists (removed by a cleanup): let install mode refresh them and decide.
        # CEF and FFmpeg names are left out: before the client's first self update they are the
        # client's own parts that its file check fetches; asking apt for them cost about 18 s and
        # ended in a confusing warning (fresh install, 2026-10-20). The FFmpeg libraries Debian
        # does provide are installed by steam-install.sh beforehand.
        # 패키지 목록 없음(정리로 지워짐): install 모드가 목록을 새로 받고 판단하게 함.
        # CEF와 FFmpeg 이름은 뺌: 클라이언트 첫 자체 갱신 전에는 그 파일 검사가 받아오는 클라이언트 자체
        # 부품임. apt에 물어보느라 약 18초가 걸렸고 헷갈리는 경고로 끝났음(새 설치, 2026-10-20). 데비안이
        # 주는 FFmpeg 라이브러리는 steam-install.sh가 미리 설치함
        client=$(echo "$missing" | grep -E '^(libcef\.so|libav[a-z]*\.so|libsw[a-z]*\.so)' || true)
        [ -n "$client" ] && echo "INFO deps : 클라이언트 파일 검사가 받을 라이브러리: $(echo "$client" | tr '\n' ' ')"
        req=$(echo "$missing" | grep -vE '^(libcef\.so|libav[a-z]*\.so|libsw[a-z]*\.so)' | tr '\n' ' ')
    fi
    if [ -n "$req" ] && [ -n "${EZS_DEPS_REQUEST:-}" ]; then
        : > "$EZS_DEPS_REQUEST"
        echo "NEED deps : $req"
    elif [ -n "$req" ]; then
        echo "WARN deps : [Steam 설치]를 다시 누르면 위 라이브러리를 찾아 설치함"
    fi
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
        if installable "$c"; then pkg=$c; break; fi
    done
    # No Debian package: CEF and FFmpeg 8 names come with the client's own packages (its file
    # check fetches them, steam-run.sh). Older FFmpeg names (e.g. libavcodec.so.61) do exist in
    # Debian and are installed like any other library.
    # 데비안 패키지 없음: CEF와 FFmpeg 8 이름은 클라이언트 자체 패키지로 옴(파일 검사가 받아옴,
    # steam-run.sh). 이전 FFmpeg 이름(예: libavcodec.so.61)은 데비안에 있어 다른 라이브러리처럼 설치함
    if [ -z "$pkg" ]; then
        case "$so" in
            libcef.so*|libav*.so*|libsw*.so*)
                echo "INFO deps : $so 는 Steam 클라이언트가 직접 받음 (첫 실행 때 파일 검사)"
                continue
                ;;
        esac
        echo "FAIL deps : $so 패키지 이름을 찾지 못함 (후보: ${cands:-없음})"
    elif out=$($APT install -y --no-install-recommends "$pkg" 2>&1); then
        echo "OK   deps : $so <- $pkg"
    else
        # Last apt lines, so the reason is in the report / 이유가 제보에 남도록 apt 마지막 줄
        echo "FAIL deps : $so <- $pkg 설치 실패 (id $(id -u))"
        echo "$out" | tail -n 3 | sed 's/^/FAIL deps :   /'
    fi
done

left=$(scan)
[ -n "$own" ] && [ -n "$left" ] && left=$(echo "$left" | grep -vxF "$own")
if [ -z "$left" ]; then
    echo "OK   deps : 모두 해결"
else
    echo "WARN deps : 남은 라이브러리: $(echo "$left" | tr '\n' ' ')"
fi
