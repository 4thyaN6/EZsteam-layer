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

# Key lines of the log, live / 로그의 주요 줄을 실시간으로 보냄
tail -n 0 -F "$LOG" 2>/dev/null | grep --line-buffered -iE \
    'error|fail|fatal|denied|missing|not found|update|download|restart|webhelper|login|exit' \
    | sed -u 's/^/STEAM /' &
TAILPID=$!

cd "$STEAM_DIR" || exit 0
n=0
while :; do
    n=$((n + 1))
    echo "INFO steam : 시작 $n ($(date +%H:%M:%S))"
    "$BIN" -no-cef-sandbox -cef-disable-gpu -chromeosnopreallocate -noverifyfiles >>"$LOG" 2>&1
    rc=$?
    echo "INFO steam : 종료 rc=$rc ($(date +%H:%M:%S))"
    # 42: restart after a self update (at most 5 times in a row) / 42: 자체 갱신 후 재시작(연속 5회까지)
    if [ $rc -eq 42 ] && [ $n -lt 6 ]; then continue; fi
    break
done
kill $TAILPID 2>/dev/null
tail -n 20 "$LOG" | sed 's/^/LOG   /'
echo "DONE steam"
