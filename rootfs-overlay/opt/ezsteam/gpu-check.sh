#!/bin/sh
# GPU path check: runs the GL/Vulkan pieces the Zink GL test depends on, one after another, each
# with a time limit, and prints one line per case ("CHECK <case> : PASS|FAIL ..."). Started by
# display.sh in "gpucheck" mode (app: [시험 ▸] [GPU 경로 점검]) on its own X screen, no Steam.
# One run covers what took one Steam start per variant before (2026-10-28: four rounds for four
# variants). Every case runs with the crash reporter (libezs_crash.so), so a crash names the
# library and offset. Full output: ~/ezs-logs/gpu-check.log (in the report).
# GPU 경로 점검: Zink GL 시험이 기대는 GL/Vulkan 부분을 차례로, 각각 시간 제한을 두고 돌려 경우마다 한 줄을 출력함
# ("CHECK <경우> : PASS|FAIL ..."). display.sh "gpucheck" 모드(앱: [시험 ▸] [GPU 경로 점검])가 자체 X 화면에서 띄움, Steam 없음.
# 전에는 변형마다 Steam 시작이 한 번씩 필요했던 것을 한 번에 봄(2026-10-28: 변형 4개에 4번). 모든 경우를 크래시 보고기
# (libezs_crash.so)와 함께 돌려, 죽으면 라이브러리와 오프셋이 나옴. 전체 출력: ~/ezs-logs/gpu-check.log(제보에 들어감)
set -u
MX=/opt/ezsteam/mesax
T=/opt/ezsteam/bin/ezs-gltest
CRASH=/opt/ezsteam/lib/libezs_crash.so
OUT="$HOME/ezs-logs/gpu-check.log"
mkdir -p "$HOME/ezs-logs"
# The previous run is kept (two runs with different switches go into one report)
# 이전 실행 결과는 남김(스위치를 바꿔 두 번 돌린 결과가 제보 하나에 들어감)
[ -s "$OUT" ] && mv -f "$OUT" "$HOME/ezs-logs/gpu-check-prev.log"
: > "$OUT"
: > /tmp/ezs-crash.log
BASE_VK="${VK_DRIVER_FILES:-}"
BASE_WSI="${MESA_VK_WSI_DEBUG:-}"
PASS=0
FAIL=0

say() { echo "$*"; echo "$*" >> "$OUT"; }

say "INFO check : 시작 $(date +%H:%M:%S), 스위치 Zink(점검은 항상 켬) GPU 장치 ${EZS_GPU_NODE:-0} X 서버 ${EZS_XSERVER_RUNNING:-xvfb}, 화면 $DISPLAY, 기본 Vulkan ${BASE_VK:-(없음)}, WSI ${BASE_WSI:-(없음)}"
[ -x "$T" ] || { say "FAIL check : $T 없음"; exit 0; }
[ -f "$MX/lib/libGLX_mesa.so.0" ] || { say "FAIL check : $MX 없음 (CI mesax 묶음)"; exit 0; }
say "INFO check : mesax $(head -n 1 "$MX/source.txt" 2>/dev/null | cut -c1-70), DRM 패치 $(cat "$MX/drm-patch" 2>/dev/null || echo '?'), 시각 패치 $(cat "$MX/timestamp-patch" 2>/dev/null || echo '?'), 디스플레이 패치 $(cat "$MX/display-patch" 2>/dev/null || echo '?'), TFP 패치 $(cat "$MX/tfp-patch" 2>/dev/null || echo '?')"

# Zink environment as in the Steam session (ezs-gpupath.sh) / Steam 세션과 같은 Zink 환경(ezs-gpupath.sh)
EZS_ZINK=1
EZG_P=check
. /opt/ezsteam/ezs-gpupath.sh >> "$OUT" 2>&1
say "INFO check : GL 라이브러리 $(LD_LIBRARY_PATH="${LD_LIBRARY_PATH:-}" ldd "$T" 2>/dev/null | awk '/libGL/ {print $1"="$3}' | tr '\n' ' ')"

# run <case> <env...> -- <command...>: time limit 25 s, crash reporter on, result line kept
# run <경우> <환경...> -- <명령...>: 시간 제한 25초, 크래시 보고기 켬, 결과 줄 보관
run() {
    name=$1
    shift
    envs=""
    while [ $# -gt 0 ] && [ "$1" != "--" ]; do envs="$envs $1"; shift; done
    shift
    log=/tmp/ezs-check-$name.log
    lines_before=$(wc -l < /tmp/ezs-crash.log 2>/dev/null || echo 0)
    # shellcheck disable=SC2086
    env $envs LD_PRELOAD="$CRASH${LD_PRELOAD:+:$LD_PRELOAD}" timeout 25 "$@" > "$log" 2>&1
    rc=$?
    res=$(grep -m1 '^RESULT' "$log" || true)
    if [ $rc -eq 0 ] && { [ -z "$res" ] || echo "$res" | grep -q '^RESULT PASS'; }; then
        PASS=$((PASS + 1))
        say "CHECK $name : PASS ${res#RESULT PASS}"
    else
        FAIL=$((FAIL + 1))
        why=$([ $rc -eq 124 ] && echo "시간 초과" || echo "rc $rc")
        say "CHECK $name : FAIL ($why) ${res#RESULT }"
        # Last lines of the case and any crash report it left / 그 경우의 마지막 줄과 남긴 크래시 보고
        grep -v '^ezs-crash' "$log" | tail -n 12 | sed "s/^/CHECK $name :   /" | tee -a "$OUT"
        tail -n +$((lines_before + 1)) /tmp/ezs-crash.log | head -n 14 | sed "s/^/CHECK $name :   /" | tee -a "$OUT"
    fi
    { echo "---- $name: env$envs, rc $rc"; cat "$log"; } >> "$OUT"
}

# 1. Vulkan alone, the Turnip of the bundle / 1. Vulkan만, 묶음의 Turnip
if command -v vkcube >/dev/null 2>&1; then
    # Turnip and loader start-up messages: vkcube stopped in its device selection with this Turnip
    # while Zink on the same Turnip worked (2026-10-28) / Turnip과 로더 시작 메시지: 이 Turnip으로 vkcube가 장치 선택에서
    # 멈췄고 같은 Turnip 위 Zink는 동작했음(2026-10-28)
    run vk-mesax TU_DEBUG=startup VK_LOADER_DEBUG=error,warn,driver -- vkcube --c 120
    [ -n "$BASE_VK" ] && run vk-default VK_DRIVER_FILES="$BASE_VK" VK_ICD_FILENAMES="$BASE_VK" -- vkcube --c 120
else
    say "INFO check : vkcube 없음, Vulkan 경우 건너뜀"
fi
# 2. GL through Zink, from simple to what Big Picture does (32-bit window, drawing thread, timestamps)
# 2. Zink를 거친 GL, 단순한 것부터 빅픽처가 하는 것까지(32비트 창, 그리기 스레드, 시각 읽기)
run gl-basic -- "$T"
run gl-argb -- "$T" --argb
run gl-argb-thread -- "$T" --argb --thread
run gl-bp-like -- "$T" --argb --thread --timestamp
# 3. Variants of the Big Picture-like case / 3. 빅픽처와 비슷한 경우의 변형
run bp-kopper-off LIBGL_KOPPER_DISABLE=true -- "$T" --argb --thread --timestamp
run bp-present-immediate MESA_VK_WSI_PRESENT_MODE=immediate -- "$T" --argb --thread --timestamp
run bp-present-fifo MESA_VK_WSI_PRESENT_MODE=fifo -- "$T" --argb --thread --timestamp
run bp-wsi-noshm MESA_VK_WSI_DEBUG=sw,noshm -- "$T" --argb --thread --timestamp
[ -n "$BASE_VK" ] && run bp-default-turnip VK_DRIVER_FILES="$BASE_VK" VK_ICD_FILENAMES="$BASE_VK" -- "$T" --argb --thread --timestamp
# 4. As close as possible to Steam's Big Picture composer: screen size 1280x800, 32-bit window,
#    drawing thread that also calls XPending on the same display (Steam's "GL Composer Thr" lost its
#    X connection there: XPending -> _XIOError -> _exit(1), 2026-10-29), web helper settings
#    (kopper on, present immediate), and variants of it
# 4. Steam 빅픽처 합성기에 최대한 가깝게: 화면 크기 1280x800, 32비트 창, 같은 디스플레이에서 XPending도 부르는 그리기 스레드
#    (Steam "GL Composer Thr"가 그곳에서 X 연결을 잃었음: XPending -> _XIOError -> _exit(1), 2026-10-29), 웹 도우미 설정
#    (kopper 켬, present immediate), 그리고 그 변형
BP="--argb --thread --timestamp --render-xpending --size 1280x800 --frames 300"
run steam-exact MESA_VK_WSI_PRESENT_MODE=immediate -- "$T" $BP
run steam-exact-noshm MESA_VK_WSI_PRESENT_MODE=immediate MESA_VK_WSI_DEBUG=sw,noshm -- "$T" $BP
run steam-exact-mailbox MESA_VK_WSI_PRESENT_MODE=mailbox -- "$T" $BP
run steam-exact-kopper-off LIBGL_KOPPER_DISABLE=true -- "$T" $BP
# Swap interval as SDL sets it (vsync on, off, adaptive): changes Zink's present mode at run time
# SDL이 정하는 스왑 간격(vsync 켬, 끔, 적응형): 실행 중에 Zink 출력 방식을 바꿈
# Texture from pixmap as the composer binds X pixmaps (failed in Steam on Xvfb through DRI3)
# 합성기가 X 픽스맵을 묶는 texture-from-pixmap(Xvfb의 Steam에서 DRI3 때문에 실패했음)
run steam-exact-tfp MESA_VK_WSI_PRESENT_MODE=immediate -- "$T" $BP --tfp
run steam-exact-swap1 MESA_VK_WSI_PRESENT_MODE=immediate -- "$T" $BP --swap-interval 1
run steam-exact-swap0 MESA_VK_WSI_PRESENT_MODE=immediate -- "$T" $BP --swap-interval 0
run steam-exact-swap-adaptive MESA_VK_WSI_PRESENT_MODE=immediate -- "$T" $BP --swap-interval -1
say "CHECK 요약 : PASS $PASS, FAIL $FAIL (전체: ~/ezs-logs/gpu-check.log, [제보 저장]에 들어감)"
