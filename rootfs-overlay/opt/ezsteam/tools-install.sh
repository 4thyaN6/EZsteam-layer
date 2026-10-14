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
$APT install -y --no-install-recommends xvfb x11-apps || { echo "FAIL apt-install"; exit 1; }
$APT clean
rm -rf /var/lib/apt/lists/*

after=$(du -sk /usr 2>/dev/null | cut -f1)
echo "OK   tools : xvfb $(Xvfb -version 2>&1 | head -n 1)"
echo "INFO size : /usr +$(( (after - before) / 1024 )) MB"
echo "DONE"
