#!/bin/sh
# Live display test: virtual X server (Xvfb) on :98 whose screen memory is a file
# (-fbdir, XWD format), shown live by the app (DisplayActivity) and driven by the app through XTEST.
# Test windows: vkcube (Turnip), xclock, xeyes (follows the pointer), xcalc (click test),
# keyview.py (key test, holds the keyboard focus).
#   display.sh <width> <height> [test|steam]
# Mode "steam": no test windows; starts the Steam client (steam-run.sh) once the screen is ready.
# Prints "READY <width> <height> <framebuffer file>" when the app may open the screen, then waits
# until the app closes stdin, and stops everything it started.
# Runs with PRoot fake ids (Xvfb needs setuid()/setgid() before xkbcomp, see drawtest.sh):
# fake root in test mode, fake user steam (-i 1000:1000) in steam mode (Steam refuses root).
# 실시간 화면 시험: 화면 메모리를 파일(-fbdir, XWD 형식)로 두는 가상 X 서버(Xvfb) :98.
# 앱(DisplayActivity)이 이 파일을 실시간으로 보여 주고 XTEST로 입력을 넣음.
# 시험 창: vkcube(Turnip), xclock, xeyes(포인터를 따라봄), xcalc(클릭 시험),
# keyview.py(키 시험, 키보드 포커스를 가짐).
#   display.sh <가로> <세로> [test|steam]
# "steam" 모드: 시험 창 없이, 화면이 준비되면 Steam 클라이언트(steam-run.sh)를 띄움.
# 앱이 화면을 열어도 될 때 "READY <가로> <세로> <화면 파일>"을 출력하고, 앱이 stdin을 닫을
# 때까지 기다린 뒤 자기가 띄운 것을 모두 끔.
# PRoot 가짜 id로 실행함(Xvfb는 xkbcomp 전에 setuid()/setgid()가 필요, drawtest.sh 참고):
# test 모드는 가짜 root, steam 모드는 가짜 사용자 steam(-i 1000:1000, Steam은 root 거부)
set -u
W=${1:-1280}
H=${2:-720}
MODE=${3:-test}
D=98
FB=/tmp/ezs-fb

command -v Xvfb >/dev/null 2>&1 || { echo "FAIL tools : Xvfb 없음 ([시험 도구 설치] 먼저)"; exit 0; }
command -v xkbcomp >/dev/null 2>&1 || { echo "FAIL xkb : xkbcomp 없음 ([시험 도구 설치] 다시)"; exit 0; }
echo "INFO driver : VK_DRIVER_FILES=${VK_DRIVER_FILES:-(없음)} MESA_VK_WSI_DEBUG=${MESA_VK_WSI_DEBUG:-(없음)}"

# Clean leftovers of an earlier run that was killed (socket, lock, screen file)
# 강제 종료된 이전 실행의 잔여물 정리(소켓, 잠금 파일, 화면 파일)
mkdir -p /tmp/.X11-unix && chmod 1777 /tmp/.X11-unix 2>/dev/null
rm -f /tmp/.X11-unix/X$D /tmp/.X$D-lock
rm -rf "$FB" /tmp/ezs-vkcube.pid /tmp/ezs-vkcube-display.log && mkdir -p "$FB"
[ -w /var/lib/xkb ] || mkdir -p /var/lib/xkb 2>/dev/null

# 1. X server; the screen memory is $FB/Xvfb_screen0 (XWD header + 32-bit pixels)
# 1. X 서버. 화면 메모리는 $FB/Xvfb_screen0(XWD 헤더 + 32비트 픽셀)
# -noreset: keep the server state (pointer, screen) when the last client disconnects
# -noreset: 마지막 클라이언트가 끊겨도 서버 상태(포인터, 화면)를 초기화하지 않음
# Korean in X core fonts: the Steam self-update window ("Using update UI: xwin") draws with
# XDrawString16 in "-misc-fixed-medium-r-normal--*-120-*-*-c-*-iso10646-1" (names found in the
# client binary, app log "INFO font", 2026-10-23), one font without fallback, so Korean came out
# as boxes. A font folder put first in the X font path answers that name with a Korean TrueType
# font (an alias). The phone's Noto Sans CJK cannot be used: the X server's font loader refused it
# (CFF outlines, BadName on OpenFont), TrueType fonts loaded (tested with Xvfb 21, 2026-10-23).
# NanumGothic comes from Debian's fonts-nanum (SIL OFL), installed by [시험 도구 설치] on the
# device; nothing is shipped with the app.
# X 코어 글꼴의 한글: Steam 자체 갱신 창("Using update UI: xwin")은 XDrawString16으로
# "-misc-fixed-medium-r-normal--*-120-*-*-c-*-iso10646-1" 글꼴에 그림(클라이언트 실행 파일 안 이름,
# 앱 로그 "INFO font", 2026-10-23). 대체 글꼴 없이 글꼴 하나만 써서 한글이 □로 나왔음. X 글꼴 경로 맨
# 앞의 글꼴 폴더가 그 이름에 한글 TrueType 글꼴로 답함(별칭). 휴대폰 Noto Sans CJK는 쓸 수 없음: X 서버
# 글꼴 읽기가 거부했고(CFF 외곽선, OpenFont BadName) TrueType은 읽혔음(Xvfb 21로 시험, 2026-10-23).
# 나눔고딕은 데비안 fonts-nanum(SIL OFL)이며 기기에서 [시험 도구 설치]가 설치함. 앱에 넣어 배포하지 않음
XFP=""
KFONT=""
# NanumGothic first, any other Nanum TrueType file otherwise / 나눔고딕 먼저, 없으면 다른 나눔 TrueType 파일
KFONT=$(ls /usr/share/fonts/truetype/nanum/NanumGothic.ttf /usr/share/fonts/truetype/nanum/*Gothic*.ttf \
    /usr/share/fonts/truetype/nanum/*.ttf 2>/dev/null | head -n 1)
if [ -n "$KFONT" ]; then
    XF=/tmp/ezs-xfonts
    rm -rf "$XF" && mkdir -p "$XF" && ln -s "$KFONT" "$XF/ko.ttf"
    printf '1\nko.ttf -ezsteam-ko-medium-r-normal--0-0-0-0-p-0-iso10646-1\n' > "$XF/fonts.dir"
    printf '%s\n' '-misc-fixed-medium-r-normal--13-120-75-75-c-70-iso10646-1 "-ezsteam-ko-medium-r-normal--13-*-*-*-p-*-iso10646-1"' > "$XF/fonts.alias"
    XFP="$XF,catalogue:/etc/X11/fontpath.d,built-ins"
else
    echo "INFO font : X 코어 글꼴용 한글 글꼴 없음 (Steam 갱신 창 한글이 □로 나옴, [시험 도구 설치]를 다시 누르면 받음)"
fi
start_xvfb() {
    # $1: font path, empty = the server's own / $1: 글꼴 경로, 비면 서버 기본값
    Xvfb :$D -screen 0 ${W}x${H}x24 -fbdir "$FB" -nolisten tcp -ac -noreset ${1:+-fp "$1"} >/tmp/ezs-xvfb-display.log 2>&1 &
    XPID=$!
    i=0
    while { [ ! -S /tmp/.X11-unix/X$D ] || [ ! -s "$FB/Xvfb_screen0" ]; } && [ $i -lt 100 ]; do
        sleep 0.1; i=$((i + 1))
    done
}
start_xvfb "$XFP"
if [ ! -S /tmp/.X11-unix/X$D ] && [ -n "$XFP" ]; then
    # Started without the Korean font folder if the font path was refused
    # 글꼴 경로가 거부되면 한글 글꼴 폴더 없이 다시 시작함
    echo "WARN font : X 글꼴 경로로 시작 실패, 기본 글꼴로 다시 시작"
    head -n 5 /tmp/ezs-xvfb-display.log
    kill $XPID 2>/dev/null
    rm -f /tmp/.X11-unix/X$D /tmp/.X$D-lock
    rm -rf "$FB" && mkdir -p "$FB"
    XFP=""
    start_xvfb ""
fi
[ -n "$XFP" ] && echo "INFO font : X 코어 글꼴 한글 연결 ${KFONT##*/} (misc-fixed iso10646 별칭)"
if [ ! -S /tmp/.X11-unix/X$D ]; then
    echo "FAIL xvfb : 시작 안 됨"; head -n 40 /tmp/ezs-xvfb-display.log; kill $XPID 2>/dev/null; exit 0
fi
echo "INFO id : $(id -u):$(id -g) mode=$MODE"
echo "OK   xvfb : :$D ${W}x${H}x24, $(ls -l "$FB/Xvfb_screen0" | awk '{print $5}') B"
export DISPLAY=:$D

PIDS=""
if [ "$MODE" = steam ]; then
    # 2a. Steam client; its own output is filtered by steam-run.sh
    # 2a. Steam 클라이언트. 출력 거르기는 steam-run.sh가 함
    echo "READY $W $H $FB/Xvfb_screen0"
    # This shell becomes the Steam run script (one process less; the app ends everything on close)
    # 이 셸이 Steam 실행 스크립트로 바뀜(프로세스 1개 절약, 닫을 때는 앱이 모두 종료시킴)
    exec sh /opt/ezsteam/steam-run.sh
else
# 2. Test windows (skipped when the program is missing); positions follow the screen width
# 2. 시험 창(프로그램이 없으면 건너뜀). 위치는 화면 가로에 맞춤
start() {
    if command -v "$1" >/dev/null 2>&1; then
        "$@" >/dev/null 2>&1 &
        PIDS="$PIDS $!"
    else
        echo "INFO skip : $1 없음"
    fi
}
start xclock -geometry 300x300+$((W - 320))+20 -update 1
start xeyes -geometry 300x200+520+20
start xcalc -geometry +520+260
# Key check window (takes the keyboard focus); its lines also go to the app log
# 키 확인 창(키보드 포커스를 가져감). 받은 키 줄은 앱 로그에도 나옴
python3 /opt/ezsteam/keyview.py 20 520 2>&1 | sed -u 's/^/KEY  /' &
PIDS="$PIDS $!"

# vkcube keeps drawing (500x500 at 0,0); restarted if it ends
# vkcube는 계속 그림(0,0 위치 500x500). 끝나면 다시 띄움
if command -v vkcube >/dev/null 2>&1; then
    # The current vkcube pid is kept in a file so the stop step can end it (no pkill in the rootfs)
    # 현재 vkcube pid를 파일에 남겨 종료 단계에서 끌 수 있게 함(rootfs에 pkill 없음)
    ( while [ -S /tmp/.X11-unix/X$D ]; do
          vkcube >>/tmp/ezs-vkcube-display.log 2>&1 &
          echo $! >/tmp/ezs-vkcube.pid
          wait $!
          sleep 1
      done ) &
    LOOP=$!
else
    echo "INFO skip : vkcube 없음"
fi

echo "READY $W $H $FB/Xvfb_screen0"
fi

# 3. Wait until the app closes stdin (screen closed), then stop everything
#    (Steam processes left over are ended by PRoot --kill-on-exit when this script ends)
# 3. 앱이 stdin을 닫을 때까지(화면 닫힘) 기다린 뒤 모두 종료
#    (남은 Steam 프로세스는 이 스크립트가 끝나면 PRoot --kill-on-exit가 정리함)
while read -r _; do :; done
kill ${LOOP:-} 2>/dev/null
[ -f /tmp/ezs-vkcube.pid ] && kill "$(cat /tmp/ezs-vkcube.pid)" 2>/dev/null
kill $PIDS $XPID 2>/dev/null
wait $XPID 2>/dev/null
tail -n 3 /tmp/ezs-vkcube-display.log 2>/dev/null | sed 's/^/INFO vkcube : /'
echo "DONE"
