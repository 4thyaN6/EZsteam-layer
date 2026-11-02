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

# udev stand-ins for the web helper (native/ezscompat, same as the client): on store and community
# pages Chromium's gamepad polling thread starts a udev watcher; the refused uevent socket made it
# log "Failed to initialize a udev monitor." and crash at address 0 right after (Gamepad polling,
# SIGSEGV, 2026-11-02 23:38:36), and Steam restarted the whole web helper. With the stand-in the
# watcher is made and simply gets no events. The library is added once; its other parts stay off
# outside the client process "steam"
# 웹 도우미용 udev 대역(native/ezscompat, 클라이언트와 같음): 상점·커뮤니티 페이지에서 Chromium의 게임패드 감시 스레드가 udev
# 감시를 시작하는데, uevent 소켓이 거부되자 "Failed to initialize a udev monitor."를 남기고 바로 주소 0에서 죽었음(Gamepad
# polling, SIGSEGV, 2026-11-02 23:38:36). 그래서 Steam이 웹 도우미 전체를 다시 띄웠음. 대역이 있으면 감시 객체가 만들어지고
# 사건만 오지 않음. 라이브러리는 한 번만 넣음. 나머지 기능은 클라이언트 프로세스 "steam" 밖에서는 꺼져 있음
if [ -f /opt/ezsteam/lib/libezs_compat.so ]; then
    case ":${LD_PRELOAD:-}:" in
        *:/opt/ezsteam/lib/libezs_compat.so:*) ;;
        *) export LD_PRELOAD="${LD_PRELOAD:+$LD_PRELOAD:}/opt/ezsteam/lib/libezs_compat.so" ;;
    esac
    export EZS_UEVENT_STUB=1 EZS_UDEV_CTL_PRESENT=1
    echo "ezs-webhelper: udev 대역 켬 (게임패드 감시 크래시 방지)"
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

# The app's PulseAudio server (PulseServer, mixed with the ALSA path): Chromium under ALSA counts
# only real sound cards, found none and sent WebAudio (the Big Picture UI sounds) to a fake output
# (2026-11-01). Told to the web helper only; games keep ALSA. Without the socket nothing changes.
# 앱의 PulseAudio 서버(PulseServer, ALSA 경로와 함께 섞임): ALSA의 Chromium은 실제 사운드카드만 세고, 하나도 없어
# WebAudio(빅픽처 UI 효과음)를 가짜 출력으로 보냈음(2026-11-01). 웹 도우미에만 알림. 게임은 ALSA 그대로임. 소켓이
# 없으면 바뀌는 것 없음
if [ -S /tmp/ezs-pulse/native ]; then
    export PULSE_SERVER=unix:/tmp/ezs-pulse/native
    echo "ezs-webhelper: 소리 PulseAudio (앱 서버 /tmp/ezs-pulse/native)"
fi
