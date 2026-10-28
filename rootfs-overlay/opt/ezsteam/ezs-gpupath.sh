# GPU display path test switches, sourced by steam-run.sh (Steam client) and EZ Proton (games):
#   EZG_P=<log prefix> . /opt/ezsteam/ezs-gpupath.sh   (dash passes no arguments to ".")
# Each part acts only when its switch is on (set by the app from MainActivity [시험]); with all
# switches off nothing here changes the environment. Safe to source twice (no double entries).
#   EZS_ZINK=1      OpenGL through Zink on Vulkan: Mesa from /opt/ezsteam/mesax in front (GLX and EGL
#                   through Debian's glvnd), Zink as the only gallium driver
#   EZS_GPU_NODE=1  the Android GPU is also /dev/dri/renderD128: libdrm shim preloaded, Turnip from the
#                   same bundle (built with libdrm, DRM numbers with EZS_TU_DRM=1); with the Xwayland
#                   screen server too, X11 DRI3 is used (no software present for Vulkan, no kopper DRI2)
# GPU 화면 경로 시험 스위치. steam-run.sh(Steam 클라이언트)와 EZ Proton(게임)이 불러옴:
#   EZG_P=<로그 접두어> . /opt/ezsteam/ezs-gpupath.sh   (dash는 "."에 인자를 넘기지 않음)
# 각 부분은 그 스위치가 켜졌을 때만 동작함(앱이 MainActivity [시험]에 따라 정함). 스위치가 모두 꺼져 있으면 여기서
# 환경을 바꾸지 않음. 두 번 불러도 됨(항목이 겹치지 않음).
#   EZS_ZINK=1      OpenGL을 Vulkan 위 Zink로: /opt/ezsteam/mesax의 Mesa를 앞에 둠(GLX와 EGL은 Debian glvnd를 거침),
#                   gallium 드라이버는 Zink만
#   EZS_GPU_NODE=1  안드로이드 GPU가 /dev/dri/renderD128로도 보임: libdrm 심을 미리 불러오고, 같은 묶음의 Turnip을 씀
#                   (libdrm과 함께 빌드, EZS_TU_DRM=1이면 DRM 번호 알림). Xwayland 화면 방식도 켜졌으면 X11 DRI3를 씀
#                   (Vulkan 소프트웨어 출력 안 씀, kopper DRI2 안 씀)

EZG_P=${EZG_P:-gpupath}
EZG_MX=/opt/ezsteam/mesax
EZG_SHIM=/opt/ezsteam/lib/libezs_drm.so

# Put a value first in a colon list once / 콜론 목록 맨 앞에 값을 한 번만 넣음
ezg_prepend() {
    eval "ezg_cur=\${$1:-}"
    case ":$ezg_cur:" in
        *":$2:"*) ;;
        *) eval "export $1=\"\$2\${ezg_cur:+:\$ezg_cur}\"" ;;
    esac
}
# Add a value at the end of a colon list once / 콜론 목록 끝에 값을 한 번만 넣음
ezg_append() {
    eval "ezg_cur=\${$1:-}"
    case ":$ezg_cur:" in
        *":$2:"*) ;;
        *) eval "export $1=\"\${ezg_cur:+\$ezg_cur:}\$2\"" ;;
    esac
}

EZG_DRI3=0
if [ "${EZS_GPU_NODE:-0}" = 1 ] && [ -c /dev/dri/renderD128 ] && [ "${EZS_XSERVER_RUNNING:-xvfb}" = xwayland ]; then
    EZG_DRI3=1
fi

if [ "${EZS_ZINK:-0}" = 1 ]; then
    if [ -f "$EZG_MX/lib/libGLX_mesa.so.0" ]; then
        ezg_prepend LD_LIBRARY_PATH "$EZG_MX/lib"
        # glvnd picks libGLX_mesa.so.0 by name (found first in the bundle) and EGL from this file only
        # glvnd는 이름으로 libGLX_mesa.so.0을 고르고(묶음에서 먼저 찾음) EGL은 이 파일로만 고름
        export __GLX_VENDOR_LIBRARY_NAME=mesa
        export __EGL_VENDOR_LIBRARY_FILENAMES="$EZG_MX/egl.json"
        export MESA_LOADER_DRIVER_OVERRIDE=zink GALLIUM_DRIVER=zink
        export LIBGL_DRIVERS_PATH="$EZG_MX/lib/dri" GBM_BACKENDS_PATH="$EZG_MX/lib/gbm"
        if [ $EZG_DRI3 = 1 ]; then
            unset LIBGL_KOPPER_DRI2
        else
            # No DRI3 on this X server: Zink presents through the Vulkan swapchain (kopper)
            # 이 X 서버에 DRI3 없음: Zink가 Vulkan 스왑체인으로 출력함(kopper)
            export LIBGL_KOPPER_DRI2=true
        fi
        # Web helper: Steam's wrapper turns kopper off; take it back for Zink (ezsteam-prepare.sh hook)
        # 웹 도우미: Steam 감쌈 스크립트가 kopper를 끔. Zink에서는 다시 켬(ezsteam-prepare.sh 고리)
        export EZS_WH_KOPPER=1
        # Zink with the Turnip of the same bundle (patched: no calibrated timestamps on kgsl). With
        # only the Mesa libraries swapped, Zink still ran on the installed turnip package and kept
        # calling the kgsl timestamp read that is not written there ("vkGetCalibratedTimestampsEXT
        # failed" with "시각 패치 1" in the log, 2026-10-28). Games get the same Turnip while Zink is on.
        # Zink를 같은 묶음의 Turnip과 씀(패치됨: kgsl에서 calibrated timestamps 없음). Mesa 라이브러리만 바꾸면 Zink가 설치된
        # turnip 묶음 위에서 돌아, 그곳에 구현되지 않은 kgsl 시각 읽기를 계속 불렀음(로그에 "시각 패치 1"인데
        # "vkGetCalibratedTimestampsEXT failed", 2026-10-28). Zink를 켠 동안 게임도 같은 Turnip을 씀
        if [ -f "$EZG_MX/icd.json" ]; then
            export VK_DRIVER_FILES="$EZG_MX/icd.json" VK_ICD_FILENAMES="$EZG_MX/icd.json"
        fi
        echo "INFO zink : $EZG_P: Zink GL 켬 ($(cat "$EZG_MX/source.txt" 2>/dev/null | head -n 1 | cut -c1-60)), DRI3 $([ $EZG_DRI3 = 1 ] && echo 씀 || echo 안 씀), 웹 도우미 kopper 켬, Vulkan ${VK_DRIVER_FILES:-(기본)}, 시각 패치 $(cat "$EZG_MX/timestamp-patch" 2>/dev/null || echo 없음)"
    else
        echo "WARN zink : $EZG_P: Zink GL 켬이나 $EZG_MX 없음 → 기존 GL"
    fi
fi

if [ "${EZS_GPU_NODE:-0}" = 1 ]; then
    if [ -c /dev/dri/renderD128 ] && [ -f "$EZG_SHIM" ] && [ -f "$EZG_MX/icd.json" ]; then
        ezg_append LD_PRELOAD "$EZG_SHIM"
        export VK_DRIVER_FILES="$EZG_MX/icd.json" VK_ICD_FILENAMES="$EZG_MX/icd.json" EZS_TU_DRM=1
        if [ $EZG_DRI3 = 1 ]; then
            # Vulkan frames through DRI3/Present to Xwayland (GPU buffers) instead of software copies
            # Vulkan 프레임을 소프트웨어 복사 대신 DRI3/Present로 Xwayland에 보냄(GPU 버퍼)
            unset MESA_VK_WSI_DEBUG
        fi
        echo "INFO drm : $EZG_P: GPU 장치 켬 (Turnip mesax, 심, 패치 $(cat "$EZG_MX/drm-patch" 2>/dev/null || echo ?)), MESA_VK_WSI_DEBUG=${MESA_VK_WSI_DEBUG:-(없음)}"
    else
        echo "WARN drm : $EZG_P: GPU 장치 켬이나 준비 안 됨 (노드 $([ -c /dev/dri/renderD128 ] && echo 있음 || echo 없음), 심 $([ -f "$EZG_SHIM" ] && echo 있음 || echo 없음), mesax $([ -f "$EZG_MX/icd.json" ] && echo 있음 || echo 없음))"
    fi
fi
