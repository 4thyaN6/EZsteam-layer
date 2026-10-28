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
    # Zink and Mesa messages to the helper output / Zink와 Mesa 메시지를 도우미 출력으로
    export MESA_DEBUG=1
    echo "ezs-webhelper: Zink kopper=${EZS_WH_KOPPER:-1} present=$MESA_VK_WSI_PRESENT_MODE wsi=${MESA_VK_WSI_DEBUG:-} LIBGL_KOPPER_DISABLE=${LIBGL_KOPPER_DISABLE:-(없음)}"
fi
