#!/usr/bin/env python3
# Turnip on kgsl: report VK_EXT_physical_device_drm with the device number of the kgsl fd, only when
# EZS_TU_DRM=1 is set at run time. Zink's DRM screen (used by GBM/EGL for Xwayland glamor and by
# DRI3 clients) picks the Vulkan device whose DRM render number equals the fd it was given; upstream
# Turnip does not expose the extension on kgsl, so Zink finds no device there.
# Own patch (MIT, same as Mesa). Applied by tools/build-mesax.sh to the release tarball.
# Exit 0 = applied, 1 = anchors not found (the build goes on without it, drm-patch=0 in the bundle).
# kgsl의 Turnip: 실행할 때 EZS_TU_DRM=1이 있을 때만 kgsl fd의 장치 번호로 VK_EXT_physical_device_drm을 알림.
# Zink의 DRM 화면(Xwayland glamor용 GBM/EGL, DRI3 클라이언트가 씀)은 받은 fd와 DRM render 번호가 같은 Vulkan 장치를
# 고름. 원본 Turnip은 kgsl에서 이 확장을 내놓지 않아 Zink가 장치를 찾지 못함.
# 자체 패치(Mesa와 같은 MIT). tools/build-mesax.sh가 릴리스 tarball에 적용함.
# 종료 0 = 적용, 1 = 기준 위치를 못 찾음(빌드는 패치 없이 계속, 묶음의 drm-patch=0)
import re
import sys

src = sys.argv[1]
kgsl = src + "/src/freedreno/vulkan/tu_knl_kgsl.cc"
dev = src + "/src/freedreno/vulkan/tu_device.cc"


def fail(msg):
    print("mesa-01-kgsl-drm: " + msg + " (not applied)", file=sys.stderr)
    sys.exit(1)


k = open(kgsl).read()
d = open(dev).read()

# 1. Extension list: also on kgsl when the device number was filled in below
#    확장 목록: 아래에서 장치 번호를 채웠으면 kgsl에서도 켬
ext_re = re.compile(r"\.EXT_physical_device_drm\s*=\s*!is_kgsl\(device->instance\)\s*,")
if len(ext_re.findall(d)) != 1:
    fail("EXT_physical_device_drm line not found once in tu_device.cc")
d = ext_re.sub(".EXT_physical_device_drm = !is_kgsl(device->instance) || device->has_local,", d)

# 2. kgsl load: the render number is the kgsl fd's own device number (the rootfs shows it as
#    /dev/dri/renderD128); no primary node
#    kgsl 불러오기: render 번호는 kgsl fd 자신의 장치 번호(rootfs에서는 /dev/dri/renderD128로 보임). primary 노드 없음
anchor = "   device->local_fd = fd;\n"
if k.count(anchor) != 1:
    fail("'device->local_fd = fd;' not found once in tu_knl_kgsl.cc")
add = anchor + """   /* EZsteam: DRM device numbers for Zink's device match, opt-in */
   if (debug_get_bool_option("EZS_TU_DRM", false)) {
      struct stat ezs_st;
      if (fstat(fd, &ezs_st) == 0 && S_ISCHR(ezs_st.st_mode)) {
         device->has_local = true;
         device->local_major = major(ezs_st.st_rdev);
         device->local_minor = minor(ezs_st.st_rdev);
         device->has_master = false;
         device->master_major = 0;
         device->master_minor = 0;
      }
   }
"""
k = k.replace(anchor, add)

inc = "#include <sys/mman.h>\n"
if k.count(inc) != 1:
    fail("'#include <sys/mman.h>' not found once in tu_knl_kgsl.cc")
k = k.replace(inc, inc + "#include <sys/stat.h>\n#include <sys/sysmacros.h>\n")
if "util/u_debug.h" not in k:
    k = k.replace(inc, inc + '#include "util/u_debug.h"\n')

open(kgsl, "w").write(k)
open(dev, "w").write(d)
print("mesa-01-kgsl-drm: applied")
