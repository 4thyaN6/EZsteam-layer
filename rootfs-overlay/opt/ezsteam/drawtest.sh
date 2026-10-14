#!/bin/sh
# Drawing test: virtual X server (Xvfb) + vkcube drawn by Turnip through the X11 software WSI
# (MESA_VK_WSI_DEBUG=sw from the driver package). Measures frames per second and saves a
# screenshot as ~/ezs-logs/drawtest.png for the app to show.
# 그리기 시험: 가상 X 서버(Xvfb) + Turnip이 X11 소프트웨어 WSI로 그리는 vkcube
# (드라이버 묶음의 MESA_VK_WSI_DEBUG=sw). 초당 프레임을 재고 앱이 보여 줄 화면 캡처를
# ~/ezs-logs/drawtest.png로 저장함
set -u
OUT="$HOME/ezs-logs"
mkdir -p "$OUT"
rm -f "$OUT/drawtest.png" /tmp/ezs-shot.xwd

command -v Xvfb >/dev/null 2>&1 || { echo "FAIL tools : Xvfb 없음 ([시험 도구 설치] 먼저)"; exit 0; }
command -v vkcube >/dev/null 2>&1 || { echo "FAIL tools : vkcube 없음 (rootfs vulkan-tools)"; exit 0; }
echo "INFO driver : VK_DRIVER_FILES=${VK_DRIVER_FILES:-(없음)} MESA_VK_WSI_DEBUG=${MESA_VK_WSI_DEBUG:-(없음)}"

# 1. Virtual X server on :99 (1280x720, 24-bit) / 가상 X 서버 :99(1280x720, 24비트)
Xvfb :99 -screen 0 1280x720x24 -nolisten tcp -ac >/tmp/ezs-xvfb.log 2>&1 &
XPID=$!
i=0
while [ ! -S /tmp/.X11-unix/X99 ] && [ $i -lt 50 ]; do sleep 0.1; i=$((i + 1)); done
if [ ! -S /tmp/.X11-unix/X99 ]; then
    echo "FAIL xvfb : 시작 안 됨"; tail -n 20 /tmp/ezs-xvfb.log; kill $XPID 2>/dev/null; exit 0
fi
echo "OK   xvfb : :99 1280x720x24"
export DISPLAY=:99

# 2. Timed run: 600 frames / 시간 측정 실행: 600프레임
FRAMES=600
t0=$(date +%s.%N)
vkcube --c $FRAMES >/tmp/ezs-vkcube.log 2>&1 &
VPID=$!
# Screenshot while the cube is on screen / 큐브가 떠 있는 동안 화면 캡처
sleep 2
xwd -root -silent -display :99 -out /tmp/ezs-shot.xwd 2>/dev/null
wait $VPID
rc=$?
t1=$(date +%s.%N)
if [ $rc -ne 0 ]; then
    echo "FAIL vkcube : rc=$rc"; tail -n 20 /tmp/ezs-vkcube.log
else
    fps=$(python3 -c "print(f'{$FRAMES/($t1-$t0):.1f}')")
    echo "OK   vkcube : $FRAMES frames, $(python3 -c "print(f'{$t1-$t0:.2f}')")s, ${fps} fps (시작 시간 포함)"
fi

# 3. Screenshot to PNG / 화면 캡처를 PNG로
if [ -s /tmp/ezs-shot.xwd ]; then
    python3 /opt/ezsteam/xwd2png.py /tmp/ezs-shot.xwd "$OUT/drawtest.png" && echo "OK   shot : $OUT/drawtest.png"
else
    echo "FAIL shot : xwd 캡처 없음"
fi

kill $XPID 2>/dev/null
wait $XPID 2>/dev/null
echo "DONE"
