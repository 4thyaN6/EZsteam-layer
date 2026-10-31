#!/bin/sh
# Live display test: virtual X server (Xvfb) on :98 whose screen memory is a file
# (-fbdir, XWD format), shown live by the app (DisplayActivity) and driven by the app through XTEST.
# Test windows: vkcube (Turnip), xclock, xeyes (follows the pointer), xcalc (click test),
# keyview.py (key test, holds the keyboard focus).
#   display.sh <width> <height> [test|steam|gpucheck]
# Mode "steam": no test windows; starts the Steam client (steam-run.sh) once the screen is ready.
# Prints "READY <width> <height> <framebuffer file>" when the app may open the screen, then waits
# until the app closes stdin, and stops everything it started.
# Runs with PRoot fake ids (Xvfb needs setuid()/setgid() before xkbcomp, see drawtest.sh):
# fake root in test mode, fake user steam (-i 1000:1000) in steam mode (Steam refuses root).
# 실시간 화면 시험: 화면 메모리를 파일(-fbdir, XWD 형식)로 두는 가상 X 서버(Xvfb) :98.
# 앱(DisplayActivity)이 이 파일을 실시간으로 보여 주고 XTEST로 입력을 넣음.
# 시험 창: vkcube(Turnip), xclock, xeyes(포인터를 따라봄), xcalc(클릭 시험),
# keyview.py(키 시험, 키보드 포커스를 가짐).
#   display.sh <가로> <세로> [test|steam|gpucheck]
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

# GPU path check runs without Steam, so nothing else installs what its switches need (Xwayland,
# glvnd front ends): done here as fake root when something is missing (network, once)
# GPU 경로 점검은 Steam 없이 돌아서 스위치에 필요한 것(Xwayland, glvnd 앞단)을 설치하는 곳이 없음: 빠진 것이 있으면 여기서
# 가짜 root로 설치함(네트워크, 한 번)
if [ "$MODE" = gpucheck ]; then
    SWD=/home/steam/.local/share/ezsteam
    need=0
    if [ -f "$SWD/xwayland" ] && ! command -v Xwayland >/dev/null 2>&1; then need=1; fi
    if { [ -f "$SWD/zink-gl" ] || [ -f "$SWD/gpu-node" ]; } && [ ! -e /usr/lib/aarch64-linux-gnu/libGLX.so.0 ]; then need=1; fi
    if [ $need = 1 ]; then
        echo "INFO deps : GPU 경로 점검에 필요한 패키지 설치 (네트워크 사용)"
        sh /opt/ezsteam/steam-deps.sh install 2>&1 | grep -E '^(OK|FAIL|WARN) ' | sed 's/^/DEPS /'
    fi
fi
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
# (ls sorts its whole output, so the preferred file is tested on its own first: one device got
# NanumBarunGothic from a single ls, 2026-10-23)
# (ls는 출력 전체를 정렬하므로 원하는 파일을 먼저 따로 확인함: 한 번의 ls로는 NanumBarunGothic이 골라졌음, 2026-10-23)
KFONT=/usr/share/fonts/truetype/nanum/NanumGothic.ttf
[ -f "$KFONT" ] || KFONT=$(ls /usr/share/fonts/truetype/nanum/*.ttf 2>/dev/null | head -n 1)
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
# 1b. Screen mode "Xwayland" (app test switch, EZS_XSERVER=xwayland): our guest Wayland compositor
#     ezs-wl writes the same screen file the app reads for Xvfb, and a rootful Xwayland draws into
#     it (XTEST input as with Xvfb). With the GPU device switch (EZS_GPU_NODE=1, /dev/dri/renderD128
#     bound to the Android GPU) the compositor offers dma-buf on that node, so Xwayland can use glamor
#     (Zink from /opt/ezsteam/mesax) and give X clients DRI3. Falls back to Xvfb when anything is missing.
# 1b. 화면 방식 "Xwayland"(앱 시험 스위치, EZS_XSERVER=xwayland): 자체 rootfs 안 Wayland 컴포지터 ezs-wl이 앱이
#     Xvfb용으로 읽는 것과 같은 화면 파일을 쓰고, rootful Xwayland가 그곳에 그림(입력은 Xvfb처럼 XTEST).
#     GPU 장치 스위치(EZS_GPU_NODE=1, /dev/dri/renderD128을 안드로이드 GPU에 바인드)가 켜지면 컴포지터가 그 노드로
#     dma-buf를 제공해, Xwayland가 glamor(/opt/ezsteam/mesax의 Zink)를 쓰고 X 클라이언트에 DRI3를 줄 수 있음.
#     하나라도 없으면 Xvfb로 돌아감
MX=/opt/ezsteam/mesax
WL=/opt/ezsteam/bin/ezs-wl
WPID=""
CPID=""
start_xwayland() {
    # $1: font path / $1: 글꼴 경로
    # Own runtime folder for the Wayland socket, passed only to ezs-wl and Xwayland (Steam keeps its own)
    # Wayland 소켓용 자체 런타임 폴더. ezs-wl과 Xwayland에만 넘김(Steam은 자기 것을 그대로 씀)
    XR=/tmp/ezs-xdg
    mkdir -p "$XR" && chmod 700 "$XR"
    WSOCK=ezs-wl-$D
    rm -f "$XR/$WSOCK" "$XR/$WSOCK.lock"
    RDEV=""
    GPUOK=0
    if [ "${EZS_GPU_NODE:-0}" = 1 ] && [ -c /dev/dri/renderD128 ]; then
        # Device number in decimal major:minor (stat prints hex) / 장치 번호를 10진 major:minor로(stat은 16진 출력)
        RDEV=$(printf '%d:%d' "0x$(stat -c %t /dev/dri/renderD128)" "0x$(stat -c %T /dev/dri/renderD128)")
        if [ -f "$MX/lib/libEGL_mesa.so.0" ] && [ -f /opt/ezsteam/lib/libezs_drm.so ]; then GPUOK=1; fi
        echo "INFO drm : /dev/dri/renderD128 = $RDEV, mesax $([ -f "$MX/lib/libEGL_mesa.so.0" ] && echo 있음 || echo 없음), 심 $([ -f /opt/ezsteam/lib/libezs_drm.so ] && echo 있음 || echo 없음), 패치 $(cat "$MX/drm-patch" 2>/dev/null || echo '?')"
    elif [ "${EZS_GPU_NODE:-0}" = 1 ]; then
        echo "WARN drm : GPU 장치 켬인데 /dev/dri/renderD128 없음 (바인드 실패)"
    fi
    [ $GPUOK = 1 ] || RDEV=""
    XDG_RUNTIME_DIR="$XR" "$WL" --socket "$WSOCK" --fb "$FB/Xvfb_screen0" --size ${W}x${H} ${RDEV:+--rdev "$RDEV"} &
    WPID=$!
    i=0
    while { [ ! -S "$XR/$WSOCK" ] || [ ! -s "$FB/Xvfb_screen0" ]; } && [ $i -lt 50 ]; do
        sleep 0.1; i=$((i + 1))
    done
    if [ ! -S "$XR/$WSOCK" ]; then
        echo "WARN wayland : ezs-wl 시작 안 됨"
        kill $WPID 2>/dev/null; WPID=""
        return 1
    fi
    if [ $GPUOK = 1 ]; then
        # glamor through Zink on the GPU node: Mesa from mesax, libdrm checks through the shim
        # GPU 노드 위 Zink로 glamor: Mesa는 mesax, libdrm 검사는 심으로
        env -u MESA_VK_WSI_DEBUG XDG_RUNTIME_DIR="$XR" WAYLAND_DISPLAY="$WSOCK" \
            LD_PRELOAD="/opt/ezsteam/lib/libezs_drm.so${LD_PRELOAD:+:$LD_PRELOAD}" \
            LD_LIBRARY_PATH="$MX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
            __EGL_VENDOR_LIBRARY_FILENAMES="$MX/egl.json" GBM_BACKENDS_PATH="$MX/lib/gbm" \
            LIBGL_DRIVERS_PATH="$MX/lib/dri" MESA_LOADER_DRIVER_OVERRIDE=zink GALLIUM_DRIVER=zink \
            VK_DRIVER_FILES="$MX/icd.json" VK_ICD_FILENAMES="$MX/icd.json" EZS_TU_DRM=1 \
            Xwayland :$D -geometry ${W}x${H} -nolisten tcp -ac -noreset -verbose 3 ${1:+-fp "$1"} >/tmp/ezs-xvfb-display.log 2>&1 &
    else
        # Shared memory buffers only (no GPU node) / 공유 메모리 버퍼만(GPU 노드 없음)
        XDG_RUNTIME_DIR="$XR" WAYLAND_DISPLAY="$WSOCK" Xwayland :$D -geometry ${W}x${H} -shm -nolisten tcp -ac -noreset ${1:+-fp "$1"} \
            >/tmp/ezs-xvfb-display.log 2>&1 &
    fi
    XPID=$!
    i=0
    while [ ! -S /tmp/.X11-unix/X$D ] && [ $i -lt 100 ]; do
        sleep 0.1; i=$((i + 1))
    done
    if [ ! -S /tmp/.X11-unix/X$D ]; then
        echo "WARN wayland : Xwayland 시작 안 됨"
        head -n 20 /tmp/ezs-xvfb-display.log
        kill $XPID $WPID 2>/dev/null; WPID=""
        return 1
    fi
    # What glamor did (GPU drawing of the X server) / glamor 결과(X 서버의 GPU 그리기)
    sleep 0.5
    # "-verbose 3" (GPU branch) also lists the extensions the server starts (DRI3, Present, GLX) and
    # what glamor chose / "-verbose 3"(GPU 쪽)은 서버가 켠 확장(DRI3, Present, GLX)과 glamor가 고른 것도 출력함
    grep -i -E 'glamor|gbm|egl|dri3|present|glx|zink|drm' /tmp/ezs-xvfb-display.log | head -n 20 | sed 's/^/INFO xwayland : /'
    echo "INFO wayland : Xwayland :$D ${W}x${H}, $([ $GPUOK = 1 ] && echo "GPU 노드 $RDEV (glamor 시도)" || echo "공유 메모리")"
    # X compositing manager test (switch file "xcm", written by ezs-xcmtest.bat): blends 32-bit
    # windows such as Steam's Big Picture window with their alpha, to check whether the broken
    # Steam menu bands come from alpha that nothing applies (one more process while it is on)
    # X 합성 관리자 시험(스위치 파일 "xcm", ezs-xcmtest.bat가 씀): Steam 빅픽처 창 같은 32비트 창을 알파로 섞음.
    # 깨진 Steam 메뉴 띠가 아무도 적용하지 않은 알파 때문인지 확인함(켜진 동안 프로세스 1개 더)
    # Switch file "xcm-nosel" instead: the same without announcing a compositing manager
    # 스위치 파일 "xcm-nosel"이면: 합성 관리자임을 알리지 않고 같은 일을 함
    XCMSW=/home/steam/.local/share/ezsteam
    XCMARG=""
    [ -f "$XCMSW/xcm-nosel" ] && XCMARG="--no-cm-selection"
    if { [ -f "$XCMSW/xcm" ] || [ -f "$XCMSW/xcm-nosel" ]; } && [ -x /opt/ezsteam/bin/ezs-xcm ]; then
        DISPLAY=:$D /opt/ezsteam/bin/ezs-xcm $XCMARG &
        CPID=$!
        echo "INFO xcm : 시험 스위치 켬, ezs-xcm 시작 (pid $CPID) $XCMARG"
    fi
    return 0
}
XSERVER=xvfb
if [ "${EZS_XSERVER:-xvfb}" = xwayland ]; then
    if ! command -v Xwayland >/dev/null 2>&1; then
        echo "WARN wayland : Xwayland 없음 → Xvfb ([시험 도구 설치]나 Steam 시작의 라이브러리 설치가 받음)"
    elif [ ! -x "$WL" ]; then
        echo "WARN wayland : ezs-wl 없음 → Xvfb"
    elif start_xwayland "$XFP"; then
        XSERVER=xwayland
    else
        echo "WARN wayland : Xwayland 경로 실패 → Xvfb"
        rm -f /tmp/.X11-unix/X$D /tmp/.X$D-lock
        rm -rf "$FB" && mkdir -p "$FB"
    fi
fi
[ $XSERVER = xvfb ] && start_xvfb "$XFP"
if [ $XSERVER = xvfb ] && [ ! -S /tmp/.X11-unix/X$D ] && [ -n "$XFP" ]; then
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
    # Xwayland gone after it started: say whether the compositor ended too (rc above 128 = signal rc-128)
    # Xwayland이 뜬 뒤 사라짐: 컴포지터도 끝났는지 알림(rc가 128보다 크면 신호 번호 = rc-128)
    if [ $XSERVER = xwayland ] && [ -n "$WPID" ] && ! kill -0 "$WPID" 2>/dev/null; then
        wait "$WPID"; WRC=$?
        echo "WARN wayland : ezs-wl 끝남 rc=$WRC$([ $WRC -gt 128 ] && echo " (신호 $((WRC - 128)))")"
    fi
    echo "FAIL xvfb : 시작 안 됨"; head -n 40 /tmp/ezs-xvfb-display.log; kill $XPID $WPID 2>/dev/null; exit 0
fi
echo "INFO id : $(id -u):$(id -g) mode=$MODE"
echo "OK   xvfb : :$D ${W}x${H}x24, $(ls -l "$FB/Xvfb_screen0" | awk '{print $5}') B, 서버 $XSERVER"
# For steam-run.sh and EZ Proton: which X server is running / steam-run.sh와 EZ Proton용: 어떤 X 서버인지
export EZS_XSERVER_RUNNING=$XSERVER
export DISPLAY=:$D

PIDS=""
if [ "$MODE" = steam ]; then
    # 2a. Steam client; its own output is filtered by steam-run.sh
    # 2a. Steam 클라이언트. 출력 거르기는 steam-run.sh가 함
    echo "READY $W $H $FB/Xvfb_screen0"
    # This shell becomes the Steam run script (one process less; the app ends everything on close)
    # 이 셸이 Steam 실행 스크립트로 바뀜(프로세스 1개 절약, 닫을 때는 앱이 모두 종료시킴)
    exec sh /opt/ezsteam/steam-run.sh
elif [ "$MODE" = gpucheck ]; then
    # 2b. GPU path check: no test windows, the checks draw their own (gpu-check.sh)
    # 2b. GPU 경로 점검: 시험 창 없음, 점검이 자기 창을 그림(gpu-check.sh)
    echo "READY $W $H $FB/Xvfb_screen0"
    sh /opt/ezsteam/gpu-check.sh &
    PIDS="$PIDS $!"
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
kill $PIDS $CPID $XPID $WPID 2>/dev/null
wait $XPID 2>/dev/null
tail -n 3 /tmp/ezs-vkcube-display.log 2>/dev/null | sed 's/^/INFO vkcube : /'
echo "DONE"
