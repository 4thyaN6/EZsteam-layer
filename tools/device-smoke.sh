#!/system/bin/sh
# First on-device check of the rootfs through PRoot, run from "adb shell" (no app needed).
# 앱 없이 "adb shell"에서 PRoot로 rootfs를 확인하는 첫 기기 시험임
#
# Layout expected on the device / 기기에 필요한 배치:
#   /data/local/tmp/ezg/rootfs/               extracted rootfs.tar.xz / 푼 rootfs.tar.xz
#   /data/local/tmp/ezg/libproot.so           from the CI artifact "proot" / CI 아티팩트 "proot"
#   /data/local/tmp/ezg/libproot-loader.so    same / 같음
#
#   adb push libproot.so libproot-loader.so rootfs.tar.xz tools/device-smoke.sh /data/local/tmp/ezg/
#   adb shell "mkdir -p /data/local/tmp/ezg/rootfs && tar -xJf /data/local/tmp/ezg/rootfs.tar.xz -C /data/local/tmp/ezg/rootfs"
#   adb shell sh /data/local/tmp/ezg/device-smoke.sh
#   (If the device tar cannot read .xz, extract on the PC and "adb push" the rootfs folder)
#   (기기 tar가 .xz를 못 풀면 PC에서 푼 뒤 rootfs 폴더를 "adb push"로 올림)
#
# Note: adb shell runs as the "shell" user with a different seccomp/SELinux context than an app.
# This checks PRoot + rootfs only; app rules (child process limit, app seccomp) need the real app.
# 참고: adb shell은 앱과 다른 shell 사용자·seccomp·SELinux 환경임. PRoot + rootfs만 확인되고,
# 앱 전용 규칙(자식 프로세스 제한, 앱 seccomp)은 실제 앱([환경 시험])에서 봐야 함

BASE=/data/local/tmp/ezg
ROOT=$BASE/rootfs
chmod 755 $BASE/libproot.so $BASE/libproot-loader.so
mkdir -p $BASE/ptmp $BASE/shm $ROOT/tmp $ROOT/home/steam

# Same PRoot options and environment as GlibcLauncher (app launcher)
# 앱 실행기(GlibcLauncher)와 같은 PRoot 옵션·환경
exec env -i \
    PROOT_LOADER=$BASE/libproot-loader.so PROOT_TMP_DIR=$BASE/ptmp \
    PATH=/usr/local/bin:/usr/bin:/bin HOME=/home/steam USER=steam LANG=C.UTF-8 TMPDIR=/tmp \
    $BASE/libproot.so --kill-on-exit --rootfs=$ROOT --cwd=/home/steam \
        --bind=/dev --bind=/proc --bind=/sys --bind=$BASE/shm:/dev/shm \
        --link2symlink --sysvipc \
        /bin/sh /opt/ezsteam/smoke.sh
