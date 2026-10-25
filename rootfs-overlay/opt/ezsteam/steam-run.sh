#!/bin/sh
# Start Valve's Linux ARM64 Steam client on the current X display (display.sh steam mode).
# Runs as the fake user steam (PRoot -i 1000:1000; Steam refuses root). Flags as used by the
# Android proot reports: no CEF sandbox (needs user namespaces), web helper without GPU.
# Exit code 42 = the client updated itself and asks for a restart.
# Full output: ~/ezs-logs/steam.log; key lines go to stdout (app log) with the prefix "STEAM".
# Valve Linux ARM64 Steam 클라이언트를 현재 X 디스플레이에 띄움(display.sh steam 모드).
# 가짜 사용자 steam으로 실행함(PRoot -i 1000:1000, Steam은 root 거부). 플래그는 안드로이드 proot
# 사례와 같음: CEF 샌드박스 끔(사용자 네임스페이스 필요), 웹 도우미 GPU 끔.
# 종료 코드 42 = 클라이언트가 스스로 갱신하고 재시작을 요청함.
# 전체 출력은 ~/ezs-logs/steam.log, 주요 줄은 "STEAM" 접두어로 stdout(앱 로그)에 나감
set -u
STEAM_DIR=$HOME/.local/share/Steam
BIN=$STEAM_DIR/steamrtarm64/steam
LOG=$HOME/ezs-logs/steam.log
mkdir -p "$HOME/ezs-logs"
: > "$LOG"

[ -x "$BIN" ] || { echo "FAIL steam : $BIN 없음 ([Steam 설치] 먼저)"; exit 0; }
echo "INFO id : $(id)"
sh /opt/ezsteam/ezsteam-prepare.sh

# lsof wrapper first in PATH (see /opt/ezsteam/bin/lsof) / lsof 감쌈을 PATH 맨 앞에 둠(/opt/ezsteam/bin/lsof 참고)
export PATH=/opt/ezsteam/bin:$PATH
# Vulkan present mode for games through Turnip's X11 software output: mailbox instead of the
# FIFO that DXVK asks for (the Android proot Steam reference setup sets the same; a game stopped
# right after creating its FIFO swapchain here). Set the variable beforehand to override.
# Turnip X11 소프트웨어 출력으로 그리는 게임의 Vulkan 표시 방식: DXVK가 요청하는 FIFO 대신 mailbox
# (안드로이드 proot Steam 참고 구성도 같은 값을 씀. 여기서는 FIFO 스왑체인을 만든 직후 게임이 멈췄음).
# 미리 값을 정해 두면 그 값을 씀
export MESA_VK_WSI_PRESENT_MODE="${MESA_VK_WSI_PRESENT_MODE:-mailbox}"
# The app sets these from its [게임 화면] option (MainActivity.WSI_MODES)
# 앱이 [게임 화면] 옵션(MainActivity.WSI_MODES)에 따라 정함
echo "INFO vulkan : MESA_VK_WSI_PRESENT_MODE=$MESA_VK_WSI_PRESENT_MODE MESA_VK_WSI_DEBUG=${MESA_VK_WSI_DEBUG:-(없음)}"
echo "INFO dxvk : DXVK_CONFIG=${DXVK_CONFIG:-(없음)} DXVK_HUD=${DXVK_HUD:-(없음)}"
# Folder the client writes crash dumps to / 클라이언트가 크래시 덤프를 쓰는 폴더
mkdir -p /tmp/dumps
# Private session D-Bus started before Steam: when the client's updater starts D-Bus itself, the
# bus inherits the updater's pipe and the launch waits minutes (reported by steamclienttermux)
# Steam보다 먼저 띄우는 전용 세션 D-Bus: 클라이언트 갱신기가 D-Bus를 직접 띄우면 버스가
# 갱신기 파이프를 물려받아 시작이 몇 분 늦어짐(steamclienttermux 보고)
if command -v dbus-daemon >/dev/null 2>&1; then
    # Address written to a file, not a pipe: the forked daemon may keep the descriptor open
    # 주소는 파이프가 아닌 파일로 받음: fork된 데몬이 디스크립터를 계속 열어 둘 수 있음
    rm -f /tmp/ezs-dbus.addr
    dbus-daemon --session --fork --print-address=3 3>/tmp/ezs-dbus.addr >/dev/null 2>&1
    addr=$(head -n 1 /tmp/ezs-dbus.addr 2>/dev/null)
    if [ -n "$addr" ]; then
        export DBUS_SESSION_BUS_ADDRESS="$addr"
        echo "OK   dbus : $addr"
    else
        echo "WARN dbus : 세션 버스 시작 실패"
    fi
else
    echo "WARN dbus : dbus-daemon 없음 ([Steam 설치] 다시)"
fi

# Sound: ALSA default device -> EZSteam plugin -> app socket (/tmp/ezs-audio.sock) -> Android
# AudioTrack. Wine (winealsa) and other ALSA programs then find one playback device; recording
# gets silence from the same plugin (ALSA's "null" needs /dev/full, which Android denies; measured).
# The plugin comes with the APK (/opt/ezsteam/lib, CI build of
# native/ezsaudio). Without it the file is removed and programs keep ALSA's own default.
# 소리: ALSA 기본 장치 -> EZSteam 플러그인 -> 앱 소켓(/tmp/ezs-audio.sock) -> 안드로이드 AudioTrack.
# Wine(winealsa)과 다른 ALSA 프로그램이 재생 장치 하나를 찾게 됨. 녹음은 같은 플러그인이 무음을 줌
# (ALSA "null"은 /dev/full이 필요한데 안드로이드가 막음, 실측).
# 플러그인은 APK에 들어 있음(/opt/ezsteam/lib, native/ezsaudio의 CI 빌드). 없으면 파일을 지우고
# ALSA 기본값을 그대로 씀
EZS_ALSA=/opt/ezsteam/lib/libasound_module_pcm_ezs.so
if [ -f "$EZS_ALSA" ]; then
    cat > "$HOME/.asoundrc" <<EOF
# Written by /opt/ezsteam/steam-run.sh at each Steam start (changes here are replaced)
# /opt/ezsteam/steam-run.sh가 Steam 시작 때마다 씀(여기 고친 내용은 바뀜)
pcm_type.ezs {
    lib "$EZS_ALSA"
}
pcm.!default {
    type plug
    slave {
        pcm { type ezs socket "/tmp/ezs-audio.sock" }
        format S16_LE
        rate 48000
        channels 2
    }
}
EOF
    echo "OK   audio : ALSA 기본 장치 -> 앱 소리 출력 (/tmp/ezs-audio.sock)"
else
    rm -f "$HOME/.asoundrc"
    echo "WARN audio : $EZS_ALSA 없음 (소리 없음)"
fi

# Key lines of the log are followed by the app itself (DisplayActivity), not by tail/grep processes
# here: every extra process counts against Android's limit of 32 for all apps together
# 로그의 주요 줄은 여기의 tail/grep 프로세스가 아니라 앱(DisplayActivity)이 직접 따라 읽음:
# 추가 프로세스는 모두 안드로이드의 전체 앱 합계 32개 한도에 포함됨
: > /tmp/ezs-lsof.log
# Files of a removed input diagnosis (earlier builds); left behind they were mistaken for current
# logs in the saved zip
# 제거한 입력 진단의 파일(이전 빌드). 남아 있으면 저장한 zip에서 현재 로그로 오인됨
rm -f "$HOME/ezs-logs/steam-input-probe.log" "$HOME/ezs-logs/steam-input-scan.txt"

# EZlator's Steam library shared with this client (bound by the app at /mnt/ezlator-library when
# the storage permission is given; test builds): registered as an extra library folder, so games
# already downloaded there are found instead of downloaded again. Proton prefixes stay inside the
# rootfs (see compat/ezproton): shared storage cannot hold the prefix's symbolic links
# 이 클라이언트와 공유하는 EZlator Steam 라이브러리(저장소 권한이 있으면 앱이 /mnt/ezlator-library에
# 연결함, 시험 빌드): 추가 라이브러리 폴더로 등록해 그곳에 이미 받은 게임을 다시 받지 않고 찾게 함.
# Proton prefix는 rootfs 안에 둠(compat/ezproton 참고): 공용 저장소는 prefix의 심볼릭 링크를 담을 수 없음
# Client settings applied before each start: shared library, compat tool, Proton request. Run
# again before each restart of the same session: the first start of a fresh install updates the
# client (exit 42) and only then are its settings files there, so a library registered only once
# appeared after the next app start (user report 2026-10-19)
# 시작 전마다 적용하는 클라이언트 설정: 공유 라이브러리, 호환 도구, Proton 요청. 같은 세션의 재시작
# 전에도 다시 실행함: 새 설치의 첫 시작은 클라이언트를 갱신하고(exit 42) 그 뒤에야 설정 파일이 생기므로,
# 한 번만 등록하면 라이브러리가 다음 앱 시작 때 나타났음(사용자 보고 2026-10-19)
client_settings() {
    # Proton request first: it makes steamapps on a fresh install, where steam-library.py then
    # writes the library list before the first sign-in
    # Proton 요청을 먼저 함: 새 설치에서 steamapps를 만들고, 그 안에 steam-library.py가 첫 로그인 전에
    # 라이브러리 목록을 씀
    # Valve's ARM64 Proton requested from the client when missing (steam-proton.py; downloaded by the
    # client from Valve's servers, nothing shipped)
    # 없으면 클라이언트에 Valve ARM64 Proton 받기를 요청함(steam-proton.py. 클라이언트가 Valve 서버에서 받음,
    # 앱에 넣어 배포하지 않음)
    python3 /opt/ezsteam/steam-proton.py "$STEAM_DIR" 2>&1 || echo "WARN proton : Proton 받기 요청 실패"
    if [ -n "${EZS_SHARED_LIBRARY:-}" ] && [ -d "$EZS_SHARED_LIBRARY" ]; then
        python3 /opt/ezsteam/steam-library.py "$STEAM_DIR" "$EZS_SHARED_LIBRARY" 2>&1 \
            || echo "WARN library : $EZS_SHARED_LIBRARY 등록 실패"
    fi

    # Every Windows game on EZ Proton (Valve's Proton container cannot run under PRoot, see
    # steam-compat.py), plus the list of libraries with their installed games (diagnosis)
    # 모든 Windows 게임을 EZ Proton으로(Valve Proton 컨테이너는 PRoot에서 못 돎, steam-compat.py 참고),
    # 라이브러리별 설치된 게임 목록도 남김(진단)
    python3 /opt/ezsteam/steam-compat.py "$STEAM_DIR" 2>&1 || echo "WARN compat : 호환성 도구 설정 실패"
    # Client language from the phone's language (EZS_LANG from the app, steam-language.py)
    # 휴대폰 언어로 클라이언트 언어를 정함(앱이 준 EZS_LANG, steam-language.py)
    python3 /opt/ezsteam/steam-language.py 2>&1 || echo "WARN lang : Steam 언어 설정 실패"
}
client_settings

# Game controller in the Steam client (Big Picture, menus): the same preload library that gives Wine
# its pad attaches it to the client's own SDL3 when loaded into the client (native/ezspad)
# Steam 클라이언트(빅픽처, 메뉴)의 게임 컨트롤러: Wine에 패드를 주는 같은 preload 라이브러리가
# 클라이언트에 들어가면 클라이언트 자체 SDL3에 패드를 붙임(native/ezspad)
EZS_CLIENT_PRELOAD=
[ -f /opt/ezsteam/lib/libezs_pad.so ] && EZS_CLIENT_PRELOAD=/opt/ezsteam/lib/libezs_pad.so
# flock on the shared library (Android shared storage answers ENOSYS) reported as granted
# (native/ezscompat); the client and everything it starts inherit it
# 공유 라이브러리의 flock(안드로이드 공용 저장소는 ENOSYS를 돌려줌)을 성공으로 알림(native/ezscompat).
# 클라이언트와 클라이언트가 띄우는 모든 것이 물려받음
if [ -f /opt/ezsteam/lib/libezs_compat.so ]; then
    EZS_CLIENT_PRELOAD="${EZS_CLIENT_PRELOAD:+$EZS_CLIENT_PRELOAD:}/opt/ezsteam/lib/libezs_compat.so"
fi

# Fonts: the phone's fonts are bound to /usr/local/share/fonts/android by the app (read-only).
# A per-user fontconfig file puts the CJK families after DejaVu for each generic name, so Latin text
# keeps DejaVu and Korean/Chinese/Japanese characters fall back to Noto Sans CJK. Written at every
# start (changes in the file are replaced)
# 글꼴: 앱이 휴대폰 글꼴을 /usr/local/share/fonts/android에 연결함(읽기 전용). 사용자 fontconfig 파일이
# 각 기본 이름에서 CJK 글꼴을 DejaVu 뒤에 두어, 라틴 문자는 DejaVu로, 한국어·중국어·일본어 글자는
# Noto Sans CJK로 대체 표시됨. 시작할 때마다 씀(파일을 고쳐도 바뀜)
EZS_FONTS=/usr/local/share/fonts/android
mkdir -p "$HOME/.config/fontconfig"
cat > "$HOME/.config/fontconfig/fonts.conf" <<'EZSFONTS'
<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<!-- Written by /opt/ezsteam/steam-run.sh / /opt/ezsteam/steam-run.sh가 씀 -->
<fontconfig>
  <dir>/usr/local/share/fonts/android</dir>
  <alias>
    <family>sans-serif</family>
    <prefer><family>DejaVu Sans</family><family>Noto Sans CJK KR</family><family>Noto Sans CJK JP</family>
      <family>Noto Sans CJK SC</family><family>Noto Sans CJK TC</family></prefer>
  </alias>
  <alias>
    <family>serif</family>
    <prefer><family>DejaVu Serif</family><family>Noto Serif CJK KR</family><family>Noto Sans CJK KR</family></prefer>
  </alias>
  <alias>
    <family>monospace</family>
    <prefer><family>DejaVu Sans Mono</family><family>Noto Sans CJK KR</family></prefer>
  </alias>
  <alias>
    <family>Arial</family>
    <prefer><family>DejaVu Sans</family><family>Noto Sans CJK KR</family></prefer>
  </alias>
</fontconfig>
EZSFONTS
if [ -d "$EZS_FONTS" ]; then
    echo "OK   fonts : 휴대폰 글꼴 $(ls "$EZS_FONTS" | wc -l)개, CJK $(ls "$EZS_FONTS" | grep -ci cjk)개"
else
    echo "WARN fonts : $EZS_FONTS 없음 (한국어 표시 안 될 수 있음)"
fi


cd "$STEAM_DIR" || exit 0
n=0
while :; do
    n=$((n + 1))
    # Restart after a self update: the client may have replaced its own files (web helper
    # wrapper, launch wrapper) and written its first settings files, so the hooks and settings
    # are applied again (the first session of a fresh install ran with an unhooked web helper:
    # 9 helper processes, 2026-10-19)
    # 자체 갱신 후 재시작: 클라이언트가 자기 파일(웹 도우미 감쌈 스크립트, 실행 감쌈)을 바꾸고 첫 설정 파일을
    # 썼을 수 있으므로 연결과 설정을 다시 적용함(새 설치 첫 세션이 연결 안 된 웹 도우미로 돌았음: 도우미
    # 프로세스 9개, 2026-10-19)
    if [ $n -gt 1 ]; then
        sh /opt/ezsteam/ezsteam-prepare.sh
        client_settings
    fi
    # Missing libraries of the (possibly just updated) client, report only
    # (방금 갱신됐을 수 있는) 클라이언트의 없는 라이브러리, 보고만 함
    # Missing libraries apt can provide: steam-deps.sh writes a request file and prints
    # "NEED deps"; the app installs them as root (Steam runs as a user and cannot) and removes
    # the file. Wait for that, at most 10 minutes. After the client's first self update a fresh
    # install needed libva.so.2, libvdpau.so.1, libavcodec.so.61, libavutil.so.59 (2026-10-18)
    # apt가 줄 수 있는 없는 라이브러리: steam-deps.sh가 요청 파일을 쓰고 "NEED deps"를 출력함. 앱이
    # root로 설치하고(Steam은 일반 사용자라 못 함) 파일을 지움. 그것을 최대 10분 기다림. 새 설치에서는
    # 클라이언트 첫 자체 갱신 뒤 libva.so.2, libvdpau.so.1, libavcodec.so.61, libavutil.so.59가 필요했음
    # (2026-10-18)
    REQ=/tmp/ezs-deps-request
    rm -f "$REQ"
    EZS_DEPS_REQUEST=$REQ sh /opt/ezsteam/steam-deps.sh check
    if [ -f "$REQ" ]; then
        w=0
        while [ -f "$REQ" ] && [ $w -lt 300 ]; do sleep 2; w=$((w + 1)); done
        if [ -f "$REQ" ]; then
            rm -f "$REQ"
            echo "WARN deps : 앱의 라이브러리 설치를 기다리다 시간 초과 (10분)"
        else
            sh /opt/ezsteam/steam-deps.sh check
        fi
    fi
    # File check of the client: skipped (-noverifyfiles, faster start) only while the client UI
    # library loads. A fresh install from the current bootstrap stopped at once with "dlmopen
    # steamui.so failed: libavcodec.so.62" right after "Verification skipped" (tester device): the
    # bootstrap lacks parts that the client's own check fetches. Then the check runs instead
    # 클라이언트 파일 검사: 클라이언트 UI 라이브러리가 불러와지는 동안만 건너뜀(-noverifyfiles, 시작이 빠름).
    # 지금의 부트스트랩으로 새로 설치하면 "Verification skipped" 직후 "dlmopen steamui.so failed:
    # libavcodec.so.62"로 바로 멈췄음(테스터 기기): 부트스트랩에 없는 부분을 클라이언트 자체 검사가 받아옴.
    # 그 경우에는 검사를 함
    VERIFY=-noverifyfiles
    UI=$STEAM_DIR/steamrtarm64/steamui.so
    # The check is skipped while steamui.so is the same file (size and time) that passed last
    # time: it took about 12 s of every start under PRoot (app log, 2026-10-18)
    # steamui.so가 지난번 통과한 파일(크기·시각)과 같으면 검사를 건너뜀: PRoot에서 시작마다 약 12초가
    # 걸렸음(앱 로그, 2026-10-18)
    UI_OK=$HOME/.steam/.ezs-steamui-ok
    ui_id=$(stat -c '%s %Y' "$UI" 2>/dev/null)
    if [ -f "$UI" ] && [ "$ui_id" != "$(cat "$UI_OK" 2>/dev/null)" ]; then
        libdirs=$(find "$STEAM_DIR/steamrtarm64" "$STEAM_DIR/linuxarm64" -maxdepth 4 -name '*.so*' -type f 2>/dev/null \
            | xargs -r -n1 dirname | sort -u | paste -sd: -)
        ui_missing=$(LD_LIBRARY_PATH="$libdirs" ldd "$UI" 2>/dev/null | awk '/not found/ {print $1}' | sort -u | tr '\n' ' ')
        # The file check is only for the client's own libraries (FFmpeg, CEF), and at most once
        # per start of this script: with system libraries missing (libva.so.2) it ran at every
        # restart, and each check "repaired" steamrt64 (a link to steamrtarm64, sizes differ from
        # the manifest) and restarted the client again, 6 times in a row (2026-10-18)
        # 파일 검사는 클라이언트 자체 라이브러리(FFmpeg, CEF)가 없을 때만, 이 스크립트 시작당 한 번만 함:
        # 시스템 라이브러리(libva.so.2)가 없을 때 재시작마다 검사가 돌았고, 검사할 때마다 steamrt64
        # (steamrtarm64로의 링크, 매니페스트와 크기가 다름)를 "고치고" 클라이언트를 다시 시작해 6회 연속
        # 반복했음(2026-10-18)
        own_missing=$(echo "$ui_missing" | tr ' ' '\n' | grep -E '^(libcef|libav|libsw)' | tr '\n' ' ')
        if [ -n "$own_missing" ] && [ -z "${VERIFIED:-}" ]; then
            VERIFY=
            VERIFIED=1
            rm -f "$UI_OK"
            echo "INFO steam : steamui.so에 없는 클라이언트 라이브러리($own_missing) → 파일 검사·받기를 켜고 시작"
        elif [ -n "$ui_missing" ]; then
            rm -f "$UI_OK"
            echo "WARN steam : steamui.so에 없는 라이브러리($ui_missing), 파일 검사 없이 시작"
        else
            mkdir -p "$HOME/.steam" && echo "$ui_id" > "$UI_OK"
        fi
    fi
    # Web helper (draws the Steam screen): after the client update of 2026-10-22 it stopped with
    # "steamwebhelper: error while loading shared libraries: libcef.so" every 10 s and the Steam
    # screen never came up, while steamui.so still loaded (user report). Checked with the web
    # helper's own library search (no extra path), once per web helper file (size and time): when
    # the client's own libraries are missing, the file check runs on this start. The places a
    # libcef.so exists are logged for diagnosis.
    # 웹 도우미(Steam 화면을 그림): 2026-10-22 클라이언트 갱신 뒤 "steamwebhelper: error while loading
    # shared libraries: libcef.so"로 10초마다 멈추고 Steam 화면이 뜨지 않았음. steamui.so는 그대로
    # 불러와졌음(사용자 제보). 웹 도우미 자체 라이브러리 검색(추가 경로 없음)으로 웹 도우미 파일(크기·시각)당
    # 한 번 확인함. 클라이언트 자체 라이브러리가 없으면 이번 시작에 파일 검사를 함. libcef.so가 있는 곳은
    # 진단용으로 남김
    WHB=$STEAM_DIR/steamrtarm64/steamwebhelper
    WH_DONE=$HOME/.steam/.ezs-webhelper-checked
    wh_id=$(stat -c '%s %Y' "$WHB" 2>/dev/null)
    if [ -f "$WHB" ] && [ "$wh_id" != "$(cat "$WH_DONE" 2>/dev/null)" ]; then
        wh_missing=$(ldd "$WHB" 2>/dev/null | awk '/not found/ {print $1}' | sort -u | tr '\n' ' ')
        if [ -n "$wh_missing" ]; then
            echo "WARN steam : Steam 화면 도우미에 없는 라이브러리($wh_missing)"
            # Each libcef.so: path -> real file, or "없음" for a broken link
            # libcef.so마다: 경로 -> 실제 파일, 끊어진 링크면 "없음"
            find "$STEAM_DIR/steamrtarm64" "$STEAM_DIR/linuxarm64" -name 'libcef.so*' 2>/dev/null | head -n 5 \
                | while IFS= read -r f; do
                    echo "INFO steam :   $f -> $(readlink -e "$f" 2>/dev/null || echo 없음)"
                done
            if echo " $wh_missing" | grep -qE ' (libcef|libav|libsw)' && [ -z "${VERIFIED:-}" ]; then
                VERIFY=
                VERIFIED=1
                echo "INFO steam : → 파일 검사·받기를 켜고 시작 (이 도우미 파일당 한 번)"
            fi
        fi
        mkdir -p "$HOME/.steam" && echo "$wh_id" > "$WH_DONE"
    fi
    # Where the client's own video libraries are, if any (diagnosis). Only the client's program
    # folders: the whole Steam folder holds every installed game and took seconds to walk
    # 클라이언트 자체 영상 라이브러리 위치(진단). 클라이언트 프로그램 폴더만 봄: Steam 폴더 전체에는 설치된
    # 게임이 모두 있어 훑는 데 몇 초가 걸렸음
    av=$(find "$STEAM_DIR/steamrtarm64" "$STEAM_DIR/linuxarm64" -maxdepth 4 -name 'libavcodec.so*' -print -quit 2>/dev/null)
    echo "INFO ffmpeg : ${av:-클라이언트 폴더에 libavcodec 없음}"
    # Diagnosis: which X text functions the client binary names, once per binary (size and time).
    # The self-update window ("Using update UI: xwin") showed Korean as boxes while the Steam
    # screen did not; whether it draws with X core fonts, font sets or Xft decides the fix
    # (user report 2026-10-22, not found in public sources)
    # 진단: 클라이언트 실행 파일이 이름으로 가진 X 글자 그리기 함수, 실행 파일(크기·시각)당 한 번.
    # 자체 갱신 창("Using update UI: xwin")은 한국어가 □로 나왔고 Steam 화면은 아니었음. X 코어 글꼴,
    # 글꼴 세트, Xft 중 무엇으로 그리는지에 따라 고치는 방법이 달라짐(사용자 제보 2026-10-22, 공개 자료에 없음)
    # Diagnosis for faster Wine synchronization, once per Proton build (time of its proton
    # script): wineserver used about 50 % CPU with "wineserver: using server-side
    # synchronization" in every Proton log (2026-10-23). Whether /dev/ntsync exists, whether
    # futex_waitv (syscall 449, fsync) is allowed under the app's seccomp filter and PRoot (a
    # call with no waiters answers EINVAL when it is), and which sync switches this Proton build
    # knows. Tried in a child process: a blocked call can kill the caller (SIGSYS).
    # 더 빠른 Wine 동기화 진단, Proton 빌드(proton 스크립트 시각)당 한 번: 모든 Proton 로그에
    # "wineserver: using server-side synchronization"이 있었고 wineserver가 CPU 약 50%를 썼음
    # (2026-10-23). /dev/ntsync가 있는지, 앱 seccomp 필터와 PRoot 아래에서 futex_waitv(시스템 호출 449,
    # fsync)가 허용되는지(기다릴 것 없이 부르면 허용될 때 EINVAL), 이 Proton 빌드가 아는 동기화 스위치.
    # 자식 프로세스에서 시험함: 막힌 호출은 부른 쪽을 죽일 수 있음(SIGSYS)
    PDIR="$STEAM_DIR/steamapps/common/Proton 11.0 (ARM64)"
    # The result is kept in a file and printed at every start: printed only once, it fell into a
    # session that the next report no longer held (2026-10-23)
    # 결과는 파일에 두고 시작할 때마다 출력함: 한 번만 출력했더니 다음 제보에 없는 세션에 들어가 버렸음(2026-10-23)
    SYNC_DONE=$HOME/.steam/.ezs-synccheck
    SYNC_OUT=$HOME/.steam/ezs-synccheck.txt
    sync_id=$(stat -c '%Y' "$PDIR/proton" 2>/dev/null)
    if [ -n "$sync_id" ] && { [ "$sync_id" != "$(cat "$SYNC_DONE" 2>/dev/null)" ] || [ ! -s "$SYNC_OUT" ]; }; then
      mkdir -p "$HOME/.steam"
      {
        nt=$([ -e /dev/ntsync ] && echo 있음 || echo 없음)
        fw=$(python3 -c 'import ctypes,os
l=ctypes.CDLL(None,use_errno=True)
r=l.syscall(449,0,0,0,0,0)
print(r, os.strerror(ctypes.get_errno()))' 2>&1 | tail -n 1)
        rc=$?
        [ -z "$fw" ] && fw="응답 없음(종료 $rc)"
        ws=$(strings -a "$PDIR/files/bin-arm64/wineserver" 2>/dev/null | grep -iE 'fsync|esync|ntsync|futex_waitv' | sort -u | head -n 6 | tr '\n' '|')
        echo "INFO sync : /dev/ntsync $nt, futex_waitv(449): $fw"
        echo "INFO sync : wineserver 안 동기화 이름: ${ws:-없음}"
        grep -nE 'FSYNC|ESYNC|NTSYNC|fsync|esync|ntsync' "$PDIR/proton" 2>/dev/null | head -n 8 | cut -c1-160 \
            | sed 's/^/INFO sync : proton /'
      } > "$SYNC_OUT.tmp" 2>&1 && mv -f "$SYNC_OUT.tmp" "$SYNC_OUT"
        mkdir -p "$HOME/.steam" && echo "$sync_id" > "$SYNC_DONE"
    fi
    [ -n "$sync_id" ] || echo "INFO sync : Proton 없음 ($PDIR)"
    [ -s "$SYNC_OUT" ] && cat "$SYNC_OUT"
    # LSFG check (once per driver bundle, printed every start): the device extensions the layer
    # needs, from vulkaninfo, and whether the user's Lossless.dll is installed
    # LSFG 확인(드라이버 묶음당 한 번, 시작마다 출력): 레이어가 필요로 하는 장치 확장(vulkaninfo)과
    # 사용자의 Lossless.dll 설치 여부
    LSFG_OUT=$HOME/.steam/ezs-lsfgcheck.txt
    lsfg_id="$(ls -l --time-style=+%s /opt/ezdrv 2>/dev/null | md5sum | cut -c1-12) $(stat -c %Y /opt/ezsteam/lsfg/liblsfg-vk.so 2>/dev/null)"
    if [ "$lsfg_id" != "$(cat "$HOME/.steam/.ezs-lsfgcheck" 2>/dev/null)" ] || [ ! -s "$LSFG_OUT" ]; then
        mkdir -p "$HOME/.steam"
        {
            vi=$(vulkaninfo 2>/dev/null)
            ext=""
            for e in VK_KHR_external_memory_fd VK_KHR_external_semaphore_fd VK_EXT_robustness2 VK_KHR_shader_float16_int8; do
                echo "$vi" | grep -q "$e" && ext="$ext $e=있음" || ext="$ext $e=없음"
            done
            f16=$(echo "$vi" | grep -m1 -E 'shaderFloat16 *=' | tr -s ' ')
            echo "INFO lsfg : 장치 확장$ext, ${f16:-shaderFloat16 확인 안 됨}"
            echo "INFO lsfg : 레이어 $([ -f /opt/ezsteam/lsfg/liblsfg-vk.so ] && echo 있음 || echo 없음)"
        } > "$LSFG_OUT.tmp" 2>&1 && mv -f "$LSFG_OUT.tmp" "$LSFG_OUT"
        echo "$lsfg_id" > "$HOME/.steam/.ezs-lsfgcheck"
    fi
    [ -s "$LSFG_OUT" ] && cat "$LSFG_OUT"
    ldll=$(ls "$STEAM_DIR/steamapps/common/Lossless Scaling/Lossless.dll" "/mnt/ezlator-library/steamapps/common/Lossless Scaling/Lossless.dll" 2>/dev/null | head -n 1)
    echo "INFO lsfg : Lossless.dll ${ldll:-없음 (Steam에서 Lossless Scaling 설치 시 사용 가능)}"
    # Zero-copy game frame path check, every start (about a second): Vulkan export/import of GPU
    # buffers and fences here, the EGL half in the app (PresentProbe). Decides how the present
    # layer and the compositor are built. Limited to 25 s so Steam's start is never held up.
    # 0복사 게임 프레임 경로 확인, 시작마다(약 1초): 여기서는 GPU 버퍼·펜스의 Vulkan 내보내기/가져오기,
    # 앱(PresentProbe)에서는 EGL 쪽. present 레이어와 컴포지터를 어떻게 만들지 정함. Steam 시작을 막지
    # 않도록 25초로 제한함
    if [ -x /opt/ezsteam/bin/ezs-present-probe ]; then
        pout=$(timeout 25 /opt/ezsteam/bin/ezs-present-probe 2>&1)
        prc=$?
        echo "$pout" | head -n 80
        # 124 = timeout ran out; other codes are a crash of the tool / 124 = 시간 초과, 그 밖은 도구 비정상 종료
        [ "$prc" -eq 0 ] || echo "INFO present : 확인 도구 끝 코드 $prc$([ "$prc" -eq 124 ] && echo ' (시간 초과)')"
    else
        echo "INFO present : 확인 도구 없음"
    fi
    # UTF-8 locale for the client (without it: C locale, Korean text input lost)
    # 클라이언트용 UTF-8 로캘(없으면 C 로캘이 되어 한글 입력이 사라짐)
    # Missing ones are made here once when the locale sources are in the rootfs (package
    # "locales", from [시험 도구 설치]); the 2026-10-25 report still had none although the button
    # step existed, so the start itself now makes them and says which part is missing.
    # 없으면 rootfs에 로캘 원본(패키지 "locales", [시험 도구 설치])이 있을 때 여기서 한 번 만듦. 2026-10-25
    # 제보에 버튼 단계가 있었는데도 로캘이 없었으므로 시작 자체가 만들고 어느 부분이 없는지 알림
    for L in en_US ko_KR; do
        locale -a 2>/dev/null | grep -qi "^$L\.utf-\?8\$" && continue
        if ! command -v localedef >/dev/null 2>&1; then
            echo "WARN locale : $L.UTF-8 없음, localedef 없음"
        elif [ ! -f "/usr/share/i18n/locales/$L" ] || ! ls /usr/share/i18n/charmaps/UTF-8* >/dev/null 2>&1; then
            echo "WARN locale : $L.UTF-8 없음, 로캘 원본 없음(locales 패키지, [시험 도구 설치]를 다시 누르면 받음)"
        elif localedef --no-archive -i "$L" -c -f UTF-8 "$L.UTF-8" 2>/tmp/ezs-localedef.err; then
            echo "OK   locale : $L.UTF-8 만듦"
        else
            echo "FAIL locale : $L.UTF-8 만들기 실패: $(tail -n 1 /tmp/ezs-localedef.err 2>/dev/null)"
        fi
    done
    if locale -a 2>/dev/null | grep -qi '^en_US\.utf-\?8$'; then
        echo "INFO locale : en_US.UTF-8 있음$(locale -a 2>/dev/null | grep -qi '^ko_KR\.utf-\?8$' && echo ', ko_KR.UTF-8 있음')"
    else
        echo "WARN locale : en_US.UTF-8 없음 (Steam이 C 로캘로 돌아 한글 입력이 안 될 수 있음)"
    fi
    FONT_DONE=$HOME/.steam/.ezs-fontcheck
    bin_id=$(stat -c '%s %Y' "$BIN" 2>/dev/null)
    if [ -f "$BIN" ] && [ "$bin_id" != "$(cat "$FONT_DONE" 2>/dev/null)" ] && command -v strings >/dev/null 2>&1; then
        fx=$(strings -a "$BIN" 2>/dev/null \
            | grep -oE 'XCreateFontSet|Xutf8DrawString|XmbDrawString|XwcDrawString|XftDrawStringUtf8|XftFontOpen[A-Za-z]*|XLoadQueryFont|XDrawString16|XDrawString|XDrawImageString|FcFontMatch|-misc-fixed[^ ]*|iso10646-1' \
            | sort -u | tr '\n' ' ')
        echo "INFO font : 클라이언트 X 글자 함수: ${fx:-없음}"
        mkdir -p "$HOME/.steam" && echo "$bin_id" > "$FONT_DONE"
    fi
    # Steam UI drawing: software by default (-cef-disable-gpu); with EZS_STEAM_GPU=1 (app test
    # switch) the web helper may use the GPU, with the Chromium flags the app puts in EZS_CEF_FLAGS
    # Steam 화면 그리기: 기본은 소프트웨어(-cef-disable-gpu). EZS_STEAM_GPU=1(앱 시험 스위치)이면 웹
    # 도우미가 GPU를 쓸 수 있음. Chromium 플래그는 앱이 EZS_CEF_FLAGS에 넣음
    GPUARG=-cef-disable-gpu
    [ "${EZS_STEAM_GPU:-0}" = 1 ] && GPUARG=
    # Big Picture (the controller-friendly full screen UI) when the app asks for it
    # 앱이 요청하면 빅픽처(컨트롤러용 전체 화면 UI)로 시작
    # Only after an account has signed in once (a sign-in answered 'OK' in logs/connection_log.txt):
    # before that the normal window opens for the first sign-in, because Big Picture without a
    # signed-in account was of no use (user report 2026-10-20). The earlier test, AllowAutoLogin "1"
    # in config/loginusers.vdf, stayed false although Steam signed in by itself at every start
    # (connection_log, 2026-10-21), so Big Picture never started. Account files are not read.
    # 계정이 한 번이라도 로그인한 뒤에만(logs/connection_log.txt에 로그인 응답 'OK'). 그 전에는 첫 로그인을
    # 위해 일반 창을 엶. 로그인된 계정 없이 빅픽처로는 쓸 수 없었음(사용자 보고 2026-10-20). 이전 조건
    # (config/loginusers.vdf의 AllowAutoLogin "1")은 Steam이 시작마다 스스로 로그인했는데도 계속 거짓이라
    # 빅픽처가 한 번도 시작되지 않았음(connection_log, 2026-10-21). 계정 파일은 읽지 않음
    BPARG=
    if [ "${EZS_STEAM_BIGPICTURE:-0}" = 1 ]; then
        if grep -q "RecvMsgClientLogOnResponse() : .* 'OK'" "$STEAM_DIR/logs/connection_log.txt" 2>/dev/null; then
            BPARG=-gamepadui
        else
            echo "INFO steam : 빅픽처는 첫 로그인 뒤부터 (로그인 기록 없음, 이번에는 일반 창)"
        fi
    fi
    # Big Picture draws in software: with the Steam UI GPU on (ANGLE Vulkan and "Chromium 선택")
    # it stayed a black screen with sound, with the GPU off it showed (user report 2026-10-22).
    # The GPU Chromium flags are taken out of the web helper flags for this start only.
    # 빅픽처는 소프트웨어로 그림: Steam 화면 GPU를 켜면(ANGLE Vulkan, "Chromium 선택") 소리만 나고 검은
    # 화면이었고, 끄면 나왔음(사용자 제보 2026-10-22). 이번 시작에 한해 웹 도우미 플래그에서 GPU용
    # Chromium 플래그를 뺌
    if [ -n "$BPARG" ] && [ -z "$GPUARG" ]; then
        GPUARG=-cef-disable-gpu
        EZS_CEF_FLAGS=$(echo "${EZS_CEF_FLAGS:-}" | tr ' ' '\n' \
            | grep -vE '^--(ignore-gpu-blocklist|use-gl=.*|use-angle=.*)$' | paste -sd' ' -)
        export EZS_CEF_FLAGS
        echo "INFO steam : 빅픽처는 Steam 화면 GPU 없이 시작 (GPU로는 검은 화면)"
    fi
    echo "INFO steam : 시작 $n ($(date +%H:%M:%S)) 화면 GPU=$([ -z "$GPUARG" ] && echo 1 || echo 0) 빅픽처=$([ -n "$BPARG" ] && echo 1 || echo 0)"
    # Test switch from the app: the client's SDL stops its HIDAPI controller drivers and does not
    # use udev to find HID devices (udev does not work under PRoot; Big Picture retried it
    # 12,000-21,000 times a minute). Our pad reaches the client through an SDL virtual joystick,
    # not HIDAPI. ezproton gives games their defaults back.
    # 앱의 시험 스위치: 클라이언트 SDL이 HIDAPI 컨트롤러 드라이버를 쓰지 않고, HID 장치를 찾을 때 udev를 쓰지
    # 않음(PRoot 아래에서 udev는 동작하지 않고, 빅픽처는 분당 12,000~21,000번 다시 시도했음). 우리 패드는
    # HIDAPI가 아니라 SDL 가상 조이스틱으로 클라이언트에 들어감. 게임에는 ezproton이 기본값을 돌려줌
    if [ "${EZS_STEAM_HID_LOW:-0}" = 1 ]; then
        # SDL_HIDAPI_LIBUSB=0 too: the 2026-10-25 counts showed the client loading libusb again and
        # again (ld.so.cache, libusb-1.0.so.0 twice, /dev/bus/usb, statfs /sys, mountinfo, about
        # 330 times a second in Big Picture); libusb cannot start without /dev/bus/usb and the
        # uevent socket, so SDL's HID layer kept retrying it (inference)
        # SDL_HIDAPI_LIBUSB=0도: 2026-10-25 집계에서 클라이언트가 libusb를 계속 다시 불러옴(ld.so.cache,
        # libusb-1.0.so.0 두 번, /dev/bus/usb, statfs /sys, mountinfo, 빅픽처에서 초당 약 330번). libusb는
        # /dev/bus/usb와 uevent 소켓 없이 시작하지 못하므로 SDL HID 층이 계속 다시 시도한 것(추론)
        # EZS_UEVENT_STUB=1: the client preload (native/ezscompat) hands SDL a quiet socket in
        # place of the refused udev monitor socket, so SDL_hid_init() succeeds once instead of
        # failing and being retried about 500 times a second (2026-10-25: console_log "udev disabled
        # by SDL_HINT_HIDAPI_UDEV" / "libusb disabled ..." 1,000 lines a second, mountinfo + uevent
        # socket 30,000 a minute)
        # EZS_UEVENT_STUB=1: 클라이언트 preload(native/ezscompat)가 거부된 udev 감시 소켓 대신 조용한 소켓을 SDL에
        # 줘서 SDL_hid_init()이 실패 후 초당 약 500번 다시 시도하지 않고 한 번에 성공하게 함(2026-10-25:
        # console_log "udev disabled by SDL_HINT_HIDAPI_UDEV" / "libusb disabled ..." 초당 1,000줄, mountinfo +
        # uevent 소켓 분당 3만 번)
        export SDL_JOYSTICK_HIDAPI=0 SDL_HIDAPI_UDEV=0 SDL_HIDAPI_LIBUSB=0 EZS_UEVENT_STUB=1
        echo "INFO steam : HID 찾기 줄임(시험): SDL_JOYSTICK_HIDAPI=0 SDL_HIDAPI_UDEV=0 SDL_HIDAPI_LIBUSB=0 EZS_UEVENT_STUB=1"
    fi
    LD_PRELOAD="${LD_PRELOAD:+$LD_PRELOAD:}$EZS_CLIENT_PRELOAD" \
        "$BIN" -no-cef-sandbox $GPUARG $BPARG -chromeosnopreallocate $VERIFY ${EZS_STEAM_EXTRA:-} >>"$LOG" 2>&1
    rc=$?
    echo "INFO steam : 종료 rc=$rc ($(date +%H:%M:%S))"
    # 42: restart after a self update (at most 5 times in a row) / 42: 자체 갱신 후 재시작(연속 5회까지)
    if [ $rc -eq 42 ] && [ $n -lt 6 ]; then continue; fi
    break
done
tail -n 20 "$LOG" | sed 's/^/LOG   /'
echo "DONE steam"
