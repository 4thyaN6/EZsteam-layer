#!/bin/sh
# Live display test: virtual X server (Xvfb) on :98 whose screen memory is a file
# (-fbdir, XWD format), shown live by the app (DisplayActivity) and driven by the app through XTEST.
# Test windows: vkcube (Turnip), xclock, xeyes (follows the pointer), xcalc (click / key test).
#   display.sh <width> <height>
# Prints "READY <width> <height> <framebuffer file>" when the app may open the screen, then waits
# until the app closes stdin, and stops everything it started.
# Runs as PRoot fake root (Xvfb needs setuid()/setgid() before xkbcomp, see drawtest.sh).
# 실시간 화면 시험: 화면 메모리를 파일(-fbdir, XWD 형식)로 두는 가상 X 서버(Xvfb) :98.
# 앱(DisplayActivity)이 이 파일을 실시간으로 보여 주고 XTEST로 입력을 넣음.
# 시험 창: vkcube(Turnip), xclock, xeyes(포인터를 따라봄), xcalc(클릭·키 시험).
#   display.sh <가로> <세로>
# 앱이 화면을 열어도 될 때 "READY <가로> <세로> <화면 파일>"을 출력하고, 앱이 stdin을 닫을
# 때까지 기다린 뒤 자기가 띄운 것을 모두 끔.
# PRoot 가짜 root로 실행함(Xvfb는 xkbcomp 전에 setuid()/setgid()가 필요, drawtest.sh 참고)
set -u
W=${1:-1280}
H=${2:-720}
D=98
FB=/tmp/ezs-fb

command -v Xvfb >/dev/null 2>&1 || { echo "FAIL tools : Xvfb 없음 ([시험 도구 설치] 먼저)"; exit 0; }
command -v xkbcomp >/dev/null 2>&1 || { echo "FAIL xkb : xkbcomp 없음 ([시험 도구 설치] 다시)"; exit 0; }
echo "INFO driver : VK_DRIVER_FILES=${VK_DRIVER_FILES:-(없음)} MESA_VK_WSI_DEBUG=${MESA_VK_WSI_DEBUG:-(없음)}"

# Clean leftovers of an earlier run that was killed (socket, lock, screen file)
# 강제 종료된 이전 실행의 잔여물 정리(소켓, 잠금 파일, 화면 파일)
mkdir -p /tmp/.X11-unix && chmod 1777 /tmp/.X11-unix
rm -f /tmp/.X11-unix/X$D /tmp/.X$D-lock
rm -rf "$FB" /tmp/ezs-vkcube.pid /tmp/ezs-vkcube-display.log && mkdir -p "$FB"
[ -w /var/lib/xkb ] || mkdir -p /var/lib/xkb 2>/dev/null

# 1. X server; the screen memory is $FB/Xvfb_screen0 (XWD header + 32-bit pixels)
# 1. X 서버. 화면 메모리는 $FB/Xvfb_screen0(XWD 헤더 + 32비트 픽셀)
# -noreset: keep the server state (pointer, screen) when the last client disconnects
# -noreset: 마지막 클라이언트가 끊겨도 서버 상태(포인터, 화면)를 초기화하지 않음
Xvfb :$D -screen 0 ${W}x${H}x24 -fbdir "$FB" -nolisten tcp -ac -noreset >/tmp/ezs-xvfb-display.log 2>&1 &
XPID=$!
i=0
while { [ ! -S /tmp/.X11-unix/X$D ] || [ ! -s "$FB/Xvfb_screen0" ]; } && [ $i -lt 100 ]; do
    sleep 0.1; i=$((i + 1))
done
if [ ! -S /tmp/.X11-unix/X$D ]; then
    echo "FAIL xvfb : 시작 안 됨"; head -n 40 /tmp/ezs-xvfb-display.log; kill $XPID 2>/dev/null; exit 0
fi
echo "OK   xvfb : :$D ${W}x${H}x24, $(ls -l "$FB/Xvfb_screen0" | awk '{print $5}') B"
export DISPLAY=:$D

# 2. Test windows (skipped when the program is missing); positions follow the screen width
# 2. 시험 창(프로그램이 없으면 건너뜀). 위치는 화면 가로에 맞춤
PIDS=""
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

# 3. Wait until the app closes stdin (screen closed), then stop everything
# 3. 앱이 stdin을 닫을 때까지(화면 닫힘) 기다린 뒤 모두 종료
while read -r _; do :; done
kill ${LOOP:-} 2>/dev/null
[ -f /tmp/ezs-vkcube.pid ] && kill "$(cat /tmp/ezs-vkcube.pid)" 2>/dev/null
kill $PIDS $XPID 2>/dev/null
wait $XPID 2>/dev/null
tail -n 3 /tmp/ezs-vkcube-display.log 2>/dev/null | sed 's/^/INFO vkcube : /'
echo "DONE"
