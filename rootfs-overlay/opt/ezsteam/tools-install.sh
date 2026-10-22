#!/bin/sh
# Install the optional test tools into the rootfs with apt (run as PRoot fake root, user action only).
# xvfb = virtual X server (no screen), x11-apps = xwd screenshot tool. They stay until the rootfs
# is reinstalled; the base rootfs stays small because they are not in it.
# 선택 시험 도구를 apt로 rootfs에 설치함(PRoot 가짜 root로 실행, 사용자 조작 때만).
# xvfb = 가상 X 서버(화면 없음), x11-apps = xwd 화면 캡처 도구. rootfs를 다시 설치할 때까지 남음.
# 기본 rootfs에는 넣지 않아 기본 용량이 커지지 않음
set -u
export DEBIAN_FRONTEND=noninteractive
# apt drops to the "_apt" user for downloads, which a fake root cannot do: stay root
# apt는 받기 때 "_apt" 사용자로 바꾸는데 가짜 root는 못 하므로 root로 유지함
APT="apt-get -o APT::Sandbox::User=root -o Dpkg::Use-Pty=0 -q"

echo "INFO resolv : $(grep -c nameserver /etc/resolv.conf 2>/dev/null) nameserver"
before=$(du -sk /usr 2>/dev/null | cut -f1)

$APT update || { echo "FAIL apt-update : 네트워크·DNS 확인"; exit 1; }
# x11-xkb-utils (xkbcomp) and xkb-data are named explicitly: Xvfb compiles the keyboard map with
# them at start and cannot run without
# x11-xkb-utils(xkbcomp), xkb-data는 명시함: Xvfb가 시작할 때 키보드 배치를 이것으로 컴파일하며
# 없으면 실행되지 않음
# fonts-nanum: Korean TrueType font for X core fonts (Steam self-update window, display.sh); the
# phone's CJK font is CFF, which the X server cannot load
# fonts-nanum: X 코어 글꼴용 한글 TrueType 글꼴(Steam 자체 갱신 창, display.sh). 휴대폰 CJK 글꼴은 CFF라
# X 서버가 읽지 못함
$APT install -y --no-install-recommends xvfb x11-apps x11-xkb-utils xkb-data fonts-nanum || { echo "FAIL apt-install"; exit 1; }
# Folder for the compiled keyboard map, writable by the app user who runs Xvfb
# 컴파일된 키보드 배치 폴더(Xvfb를 실행하는 앱 사용자가 쓸 수 있어야 함)
mkdir -p /var/lib/xkb && chmod 1777 /var/lib/xkb
$APT clean
rm -rf /var/lib/apt/lists/*

after=$(du -sk /usr 2>/dev/null | cut -f1)
# Installed versions (Xvfb has no -version option) / 설치된 버전(Xvfb에는 -version 옵션이 없음)
echo "OK   tools : $(dpkg-query -W -f='${Package} ${Version}  ' xvfb x11-apps x11-xkb-utils fonts-nanum 2>/dev/null)"
echo "INFO size : /usr +$(( (after - before) / 1024 )) MB"
echo "DONE"
