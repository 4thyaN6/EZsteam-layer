#!/bin/sh
# Prepares the base prefix ahead of time (started by the app while the Steam session runs, as user
# steam): same Proton environment as compat/ezproton gives a game, then
# "ezs-baseprefix.py prepare". Output lines start with "ezproton: base".
# 기본 prefix를 미리 준비함(Steam 세션이 도는 동안 앱이 사용자 steam으로 시작함): compat/ezproton이
# 게임에 주는 것과 같은 Proton 환경을 만든 뒤 "ezs-baseprefix.py prepare"를 실행함. 출력 줄은
# "ezproton: base"로 시작함
set -u
export HOME=/home/steam
STEAM_DIR=$HOME/.local/share/Steam
# Proton needs the client folder (Steam's own DLLs for the prefix come from there)
# Proton은 클라이언트 폴더가 필요함(prefix용 Steam 자체 DLL을 거기서 가져옴)
export STEAM_COMPAT_CLIENT_INSTALL_PATH="$STEAM_DIR"
# Same Wine settings as compat/ezproton / compat/ezproton과 같은 Wine 설정
export PROTON_LOG=1
export PROTON_LOG_DIR="$HOME/ezs-logs"
export WINEDEBUG="+timestamp,+pid,+loaddll,err+all,fixme-all"
export WINEDLLOVERRIDES="tabtip.exe=d;winepulse.drv=d"
mkdir -p "$PROTON_LOG_DIR"
cd "$HOME" || exit 0

# Own invisible X screen :97 for the installers: on the Steam screen the Visual C++ installer
# windows came up over Steam and took the keyboard focus (report 2026-10-20). One extra process
# only while this runs.
# 설치 프로그램용 보이지 않는 X 화면 :97. Steam 화면에서는 Visual C++ 설치 창이 Steam 위에 뜨고 키보드
# 포커스를 가져갔음(2026-10-20 제보). 이 스크립트가 도는 동안만 프로세스 1개 추가
XPID=""
if command -v Xvfb >/dev/null 2>&1; then
    rm -f /tmp/.X97-lock /tmp/.X11-unix/X97
    Xvfb :97 -screen 0 1024x768x24 -nolisten tcp -ac >/tmp/ezs-xvfb-base.log 2>&1 &
    XPID=$!
    # Wait for its socket (up to 5 s) / 소켓이 생길 때까지 기다림(최대 5초)
    i=0
    while [ ! -e /tmp/.X11-unix/X97 ] && [ $i -lt 50 ]; do sleep 0.1; i=$((i + 1)); done
    if [ -e /tmp/.X11-unix/X97 ]; then
        export DISPLAY=:97
    else
        echo "ezproton: base hidden screen not ready, installers use the Steam screen" >&2
    fi
fi
python3 /opt/ezsteam/ezs-baseprefix.py prepare "$@"
rc=$?
[ -n "$XPID" ] && kill "$XPID" 2>/dev/null
exit $rc
