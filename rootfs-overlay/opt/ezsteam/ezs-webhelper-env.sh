# Environment for the Steam web helper, sourced by Steam's wrapper (steamrtarm64/steamwebhelper.sh,
# bash) right before it starts the helper; the hook line is added by ezsteam-prepare.sh. Acts only
# with the Zink GL test switch (EZS_ZINK=1); otherwise nothing changes.
# Steam 웹 도우미용 환경. Steam 감쌈 스크립트(steamrtarm64/steamwebhelper.sh, bash)가 도우미를 띄우기 직전에 불러옴.
# 고리 줄은 ezsteam-prepare.sh가 넣음. Zink GL 시험 스위치(EZS_ZINK=1)일 때만 동작하고, 아니면 바뀌는 것 없음
#
# Facts behind it (2026-10-28, Zink on Xvfb, Big Picture):
#   - the wrapper sets LIBGL_KOPPER_DISABLE=true; with it the GL window was made ("Loaded GL 4.6",
#     "mode: OpenGL window") and the helper died when the window was shown ("Fatal IO error 11 on
#     X server :98")
#   - with kopper back on, Chromium's GPU drew through Zink (eglGetMscRateANGLE messages about every
#     17 ms) and the helper ended about 1 s after the window was shown, with no message
# 근거 사실(2026-10-28, Xvfb 위 Zink, 빅픽처):
#   - 감쌈 스크립트가 LIBGL_KOPPER_DISABLE=true를 줌. 이때 GL 창은 만들어졌고("Loaded GL 4.6", "mode: OpenGL window")
#     창이 보이는 순간 도우미가 죽었음("Fatal IO error 11 on X server :98")
#   - kopper를 다시 켜자 Chromium GPU가 Zink로 그렸고(약 17 ms마다 eglGetMscRateANGLE 메시지) 창이 보인 지 약 1초 뒤
#     메시지 없이 도우미가 끝났음

if [ "${EZS_ZINK:-0}" = 1 ]; then
    # Keep the output of the previous helper start (the wrapper overwrites steamwebhelper.log on
    # every start, and Steam restarts a helper that died, so the failing start was lost)
    # 이전 도우미 시작의 출력을 남김(감쌈 스크립트가 시작마다 steamwebhelper.log를 덮어쓰고, Steam은 죽은 도우미를
    # 다시 띄우므로 실패한 시작분이 사라졌음)
    EZS_WH_LOG="$HOME/.steam/steam/logs/steamwebhelper.log"
    [ -s "$EZS_WH_LOG" ] && cp -f "$EZS_WH_LOG" "${EZS_WH_LOG%.log}-prev.log"
    # Kopper (Zink presents through the Vulkan swapchain) unless EZS_WH_KOPPER=0
    # kopper(Zink가 Vulkan 스왑체인으로 출력)를 켬. EZS_WH_KOPPER=0이면 Steam 기본(끔)
    [ "${EZS_WH_KOPPER:-1}" = 1 ] && unset LIBGL_KOPPER_DISABLE
    # Present without the WSI queue thread (immediate): the session default (mailbox, for games)
    # runs a second thread on the helper's X connection; one less thing on that connection
    # (inference: the X connection failure). EZS_WH_PRESENT overrides it
    # WSI 대기열 스레드 없이 출력(immediate): 세션 기본값(mailbox, 게임용)은 도우미의 X 연결 위에서 스레드를 하나 더
    # 돌림. 그 연결에서 하는 일을 하나 줄임(추론: X 연결 실패). EZS_WH_PRESENT로 바꿀 수 있음
    export MESA_VK_WSI_PRESENT_MODE="${EZS_WH_PRESENT:-immediate}"
    # Crash reporter: a crash or a non-zero exit of a helper process names library + offset in
    # /tmp/ezs-crash.log (the helper ended with no message, 2026-10-28)
    # 크래시 보고기: 도우미 프로세스가 죽거나 0이 아닌 코드로 끝나면 /tmp/ezs-crash.log에 라이브러리 + 오프셋을 남김
    # (도우미가 메시지 없이 끝났음, 2026-10-28)
    if [ -f /opt/ezsteam/lib/libezs_crash.so ]; then
        export LD_PRELOAD="/opt/ezsteam/lib/libezs_crash.so${LD_PRELOAD:+:$LD_PRELOAD}"
    fi
    # Zink and Mesa messages to the helper output / Zink와 Mesa 메시지를 도우미 출력으로
    export MESA_DEBUG=1
    echo "ezs-webhelper: Zink kopper=${EZS_WH_KOPPER:-1} present=$MESA_VK_WSI_PRESENT_MODE wsi=${MESA_VK_WSI_DEBUG:-} LIBGL_KOPPER_DISABLE=${LIBGL_KOPPER_DISABLE:-(없음)} LIBGL_DRI3_DISABLE=${LIBGL_DRI3_DISABLE:-(없음)}"
fi

# Test variant for the web helper, written by the PC helper (ezs-whtest.bat) and removed by it
# afterwards; absent = no change. KEY=VALUE lines, exported (e.g. TU_DEBUG=sysmem). The line
# EZS_WH_FLAGS_ADD=... is appended to the Chromium flags (EZS_CEF_FLAGS, expanded on the launch
# line after this file). Why: the Steam menu bands broke with ANGLE Vulkan in the X server's own
# picture (menushot 2026-10-30 23:58), so the fix is looked for on the helper side, one variant at
# a time
# 웹 도우미 시험 변형. PC 도우미(ezs-whtest.bat)가 쓰고 끝나면 지움. 없으면 바뀌는 것 없음. KEY=VALUE 줄을
# export함(예: TU_DEBUG=sysmem). EZS_WH_FLAGS_ADD=... 줄은 Chromium 플래그(EZS_CEF_FLAGS, 이 파일 다음 실행 줄에서
# 펼쳐짐)에 덧붙임. 이유: ANGLE Vulkan에서 Steam 메뉴 띠가 X 서버 자체 그림에서 깨졌으므로(menushot
# 2026-10-30 23:58) 도우미 쪽에서 해결책을 하나씩 찾음
EZS_WH_TEST="$HOME/.local/share/ezsteam/wh-test.env"
if [ -f "$EZS_WH_TEST" ]; then
    set -a
    . "$EZS_WH_TEST"
    set +a
    [ -n "${EZS_WH_FLAGS_ADD:-}" ] && EZS_CEF_FLAGS="${EZS_CEF_FLAGS:+$EZS_CEF_FLAGS }$EZS_WH_FLAGS_ADD"
    # EZS_WH_NOBLUR=1: DevTools port on 127.0.0.1:8080 and the style injector that turns off the
    # blur behind the header/footer bars (ezs-cefcss.py, ends with this helper). Test only: while
    # it runs, any program on the device can reach the port
    # EZS_WH_NOBLUR=1: 127.0.0.1:8080 DevTools 포트와 머리줄·안내줄 뒤 흐림을 끄는 스타일 주입기(ezs-cefcss.py, 이
    # 도우미와 함께 끝남). 시험 전용: 도는 동안 기기의 어떤 프로그램이든 포트에 닿을 수 있음
    # EZS_WH_CSS=<rule> does the same with that rule instead of the blur rule
    # EZS_WH_CSS=<규칙>이면 흐림 규칙 대신 그 규칙으로 같은 일을 함
    if [ "${EZS_WH_NOBLUR:-0}" = 1 ] || [ -n "${EZS_WH_CSS:-}" ]; then
        EZS_CEF_FLAGS="${EZS_CEF_FLAGS:+$EZS_CEF_FLAGS }--remote-debugging-address=127.0.0.1 --remote-debugging-port=8080"
        python3 /opt/ezsteam/ezs-cefcss.py &
    fi
    echo "ezs-webhelper: 시험 변형 $(tr '\n' ' ' < "$EZS_WH_TEST")"
fi

# Steam menu background through ezs-agent (it made the two FIFOs): the web helper's fds 3 and 4
# become Chromium's DevTools pipe (--remote-debugging-pipe), the agent gives the main menu and
# quick access pages an opaque black page background (their transparent part is not cleared with
# GPU compositing, whtest 2026-11-01). No port, no extra process. Skipped when fd 3 or 4 is
# already open (whatever Steam passes there stays untouched) or with the switch file "no-menubg"
# ezs-agent를 통한 Steam 메뉴 바탕(에이전트가 FIFO 둘을 만들었음): 웹 도우미의 fd 3·4가 Chromium DevTools 파이프
# (--remote-debugging-pipe)가 되고, 에이전트가 메인 메뉴·빠른 메뉴 페이지에 불투명 검정 페이지 바탕을 줌(GPU 합성에서
# 투명한 부분이 지워지지 않음, whtest 2026-11-01). 포트 없음, 프로세스 추가 없음. fd 3이나 4가 이미 열려 있으면(Steam이
# 거기 넘기는 것은 건드리지 않음) 또는 스위치 파일 "no-menubg"면 건너뜀
if [ -p /tmp/ezs-devtools.in ] && [ -p /tmp/ezs-devtools.out ] \
        && [ ! -f "$HOME/.local/share/ezsteam/no-menubg" ]; then
    if [ -e /proc/$$/fd/3 ] || [ -e /proc/$$/fd/4 ]; then
        echo "ezs-webhelper: 메뉴 바탕 건너뜀 (fd 3/4 사용 중)"
    else
        exec 3<>/tmp/ezs-devtools.in 4<>/tmp/ezs-devtools.out
        EZS_CEF_FLAGS="${EZS_CEF_FLAGS:+$EZS_CEF_FLAGS }--remote-debugging-pipe"
        echo "ezs-webhelper: 메뉴 바탕 DevTools 파이프 연결"
    fi
fi
