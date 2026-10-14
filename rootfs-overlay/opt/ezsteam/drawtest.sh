#!/bin/sh
# Drawing test: virtual X server (Xvfb) + vkcube drawn by Turnip through the X11 software WSI.
# Measures frames per second, then takes screenshots per present path (MIT-SHM / PutImage) with a
# non-Vulkan reference window (xclock), and saves them side by side as ~/ezs-logs/drawtest.png.
# The app runs this as PRoot fake root: the X server needs setuid()/setgid() to succeed before it
# starts xkbcomp, and Android's app seccomp blocks them otherwise.
# 그리기 시험: 가상 X 서버(Xvfb) + Turnip이 X11 소프트웨어 WSI로 그리는 vkcube.
# 초당 프레임을 잰 뒤, Vulkan을 쓰지 않는 기준 창(xclock)과 함께 출력 방식(MIT-SHM / PutImage)별로
# 캡처해 ~/ezs-logs/drawtest.png로 나란히 저장함.
# 앱은 이 시험을 PRoot 가짜 root로 실행함: X 서버는 xkbcomp를 띄우기 전에 setuid()/setgid()가
# 성공해야 하는데, 안드로이드 앱 seccomp이 이를 막기 때문임
set -u
OUT="$HOME/ezs-logs"
mkdir -p "$OUT"
rm -f "$OUT/drawtest.png" /tmp/ezs-shot-*.xwd

command -v Xvfb >/dev/null 2>&1 || { echo "FAIL tools : Xvfb 없음 ([시험 도구 설치] 먼저)"; exit 0; }
command -v vkcube >/dev/null 2>&1 || { echo "FAIL tools : vkcube 없음 (rootfs vulkan-tools)"; exit 0; }
echo "INFO driver : VK_DRIVER_FILES=${VK_DRIVER_FILES:-(없음)} MESA_VK_WSI_DEBUG=${MESA_VK_WSI_DEBUG:-(없음)}"
echo "INFO id : $(id -u):$(id -g)"

# 1. Virtual X server on :99 (1280x720, 24-bit) / 가상 X 서버 :99(1280x720, 24비트)
# Xvfb creates the socket folder only as root: make it here (sticky, writable by all)
# Xvfb는 root일 때만 소켓 폴더를 만들므로 여기서 만듦(sticky, 모두 쓰기 가능)
mkdir -p /tmp/.X11-unix && chmod 1777 /tmp/.X11-unix
# Keyboard map compiler and its output folder must be usable, else Xvfb stops at start
# 키보드 배치 컴파일러와 출력 폴더를 쓸 수 있어야 함. 아니면 Xvfb가 시작 단계에서 멈춤
if ! command -v xkbcomp >/dev/null 2>&1 || [ ! -d /usr/share/X11/xkb ]; then
    echo "FAIL xkb : xkbcomp 또는 xkb-data 없음 ([시험 도구 설치] 다시)"; exit 0
fi
[ -w /var/lib/xkb ] || mkdir -p /var/lib/xkb 2>/dev/null
echo "INFO xkb : xkbcomp $(xkbcomp -version 2>&1 | head -n 1), /var/lib/xkb $( [ -w /var/lib/xkb ] && echo 쓰기가능 || echo 쓰기불가)"

Xvfb :99 -screen 0 1280x720x24 -nolisten tcp -ac >/tmp/ezs-xvfb.log 2>&1 &
XPID=$!
i=0
while [ ! -S /tmp/.X11-unix/X99 ] && [ $i -lt 50 ]; do sleep 0.1; i=$((i + 1)); done
if [ ! -S /tmp/.X11-unix/X99 ]; then
    echo "FAIL xvfb : 시작 안 됨"; head -n 40 /tmp/ezs-xvfb.log; kill $XPID 2>/dev/null; exit 0
fi
echo "OK   xvfb : :99 1280x720x24"
export DISPLAY=:99

# Reference window that does not use Vulkan: if it shows in the screenshot, capture works
# Vulkan을 쓰지 않는 기준 창: 캡처에 보이면 캡처 경로는 정상
xclock -geometry 400x400+760+160 -update 1 >/dev/null 2>&1 &
CPID=$!

# 2. Timed run: 600 frames (startup included) / 시간 측정: 600프레임(시작 시간 포함)
FRAMES=600
t0=$(date +%s.%N)
vkcube --c $FRAMES >/tmp/ezs-vkcube.log 2>&1
rc=$?
t1=$(date +%s.%N)
if [ $rc -ne 0 ]; then
    echo "FAIL vkcube : rc=$rc"; tail -n 20 /tmp/ezs-vkcube.log
else
    echo "OK   vkcube : $FRAMES frames, $(python3 -c "print(f'{$t1-$t0:.2f}')")s, $(python3 -c "print(f'{$FRAMES/($t1-$t0):.1f}')") fps (시작 시간 포함)"
fi

# 3. Screenshots while vkcube keeps running, once per present path of the software WSI:
#    "sw" = MIT-SHM shared memory (default), "sw,noshm" = plain PutImage
# 3. vkcube가 계속 도는 동안 소프트웨어 WSI 출력 방식별로 캡처:
#    "sw" = MIT-SHM 공유 메모리(기본), "sw,noshm" = 일반 PutImage
shot() {
    MESA_VK_WSI_DEBUG=$1 vkcube >/tmp/ezs-vkcube-$2.log 2>&1 &
    VPID=$!
    sleep 3
    xwd -root -silent -display :99 -out /tmp/ezs-shot-$2.xwd 2>/dev/null
    kill $VPID 2>/dev/null
    wait $VPID 2>/dev/null
    if [ -s /tmp/ezs-vkcube-$2.log ]; then
        echo "INFO vkcube-$2 : $(tail -n 3 /tmp/ezs-vkcube-$2.log | tr '\n' ' ')"
    fi
}
shot sw shm
shot sw,noshm noshm

# 4. PNG: both screenshots side by side, with the share of non-black pixels in the cube area
#    (0,0 500x500) and in the reference clock area
# 4. PNG: 두 캡처를 나란히 붙이고, 큐브 영역(0,0 500x500)과 기준 시계 영역의 검지 않은 픽셀 비율 표시
python3 /opt/ezsteam/xwd2png.py "$OUT/drawtest.png" /tmp/ezs-shot-shm.xwd /tmp/ezs-shot-noshm.xwd \
    && echo "OK   shot : $OUT/drawtest.png (왼쪽 shm, 오른쪽 noshm)"

kill $CPID $XPID 2>/dev/null
wait $XPID 2>/dev/null
echo "DONE"
