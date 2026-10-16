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
    # Missing libraries of the (possibly just updated) client, report only
    # (방금 갱신됐을 수 있는) 클라이언트의 없는 라이브러리, 보고만 함
    sh /opt/ezsteam/steam-deps.sh check
    echo "INFO steam : 시작 $n ($(date +%H:%M:%S))"
    "$BIN" -no-cef-sandbox -cef-disable-gpu -chromeosnopreallocate -noverifyfiles >>"$LOG" 2>&1
    rc=$?
    echo "INFO steam : 종료 rc=$rc ($(date +%H:%M:%S))"
    # 42: restart after a self update (at most 5 times in a row) / 42: 자체 갱신 후 재시작(연속 5회까지)
    if [ $rc -eq 42 ] && [ $n -lt 6 ]; then continue; fi
    break
done
tail -n 20 "$LOG" | sed 's/^/LOG   /'
echo "DONE steam"
