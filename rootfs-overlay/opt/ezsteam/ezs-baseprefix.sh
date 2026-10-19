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
exec python3 /opt/ezsteam/ezs-baseprefix.py prepare "$@"
