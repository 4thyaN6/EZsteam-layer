#!/usr/bin/env python3
# Turnip on kgsl: do not offer calibrated timestamps (VK_KHR/EXT_calibrated_timestamps), because the
# kgsl GPU timestamp read behind them is not written: kgsl_device_get_gpu_timestamp() is
# UNREACHABLE(""), which in a release build is __builtin_unreachable() (undefined behaviour).
# Zink calls vkGetCalibratedTimestampsEXT when the extension is there; in the Steam web helper it
# returned VK_ERROR_OUT_OF_HOST_MEMORY and the helper ended right after ("ZINK:
# vkGetCalibratedTimestampsEXT failed", 2026-10-28). Without the extension Zink takes its own path
# (a GPU timestamp query). The function also returns an error instead of falling through.
# Own patch (MIT, same as Mesa). Applied by tools/build-mesax.sh; the default turnip package is not
# changed. Exit 0 = applied, 1 = anchors not found (build goes on without it).
# kgsl의 Turnip: calibrated timestamps(VK_KHR/EXT_calibrated_timestamps)를 내놓지 않음. 그 뒤의 kgsl GPU 시각 읽기가
# 구현되어 있지 않기 때문: kgsl_device_get_gpu_timestamp()가 UNREACHABLE("")이고, 릴리스 빌드에서는
# __builtin_unreachable()(정의되지 않은 동작)임. Zink는 이 확장이 있으면 vkGetCalibratedTimestampsEXT를 부름. Steam 웹
# 도우미에서 VK_ERROR_OUT_OF_HOST_MEMORY가 돌아왔고 바로 뒤 도우미가 끝났음("ZINK: vkGetCalibratedTimestampsEXT failed",
# 2026-10-28). 확장이 없으면 Zink는 자체 경로(GPU 시각 질의)를 씀. 함수도 흘러내리지 않고 오류를 돌려주게 함.
# 자체 패치(Mesa와 같은 MIT). tools/build-mesax.sh가 적용하며 기본 turnip 묶음은 바꾸지 않음.
# 종료 0 = 적용, 1 = 기준 위치를 못 찾음(빌드는 패치 없이 계속)
import re
import sys

src = sys.argv[1]
kgsl = src + "/src/freedreno/vulkan/tu_knl_kgsl.cc"
dev = src + "/src/freedreno/vulkan/tu_device.cc"


def fail(msg):
    print("mesa-02-kgsl-timestamp: " + msg + " (not applied)", file=sys.stderr)
    sys.exit(1)


k = open(kgsl).read()
d = open(dev).read()

# 1. Extension list: off on kgsl / 확장 목록: kgsl에서는 끔
n = 0
for ext in ("KHR_calibrated_timestamps", "EXT_calibrated_timestamps"):
    r = re.compile(r"\." + ext + r"\s*=\s*device->info->props\.has_persistent_counter\s*,")
    if len(r.findall(d)) != 1:
        fail(ext + " line not found once in tu_device.cc")
    d = r.sub("." + ext + " = device->info->props.has_persistent_counter && !is_kgsl(device->instance),", d)
    n += 1

# 2. The kgsl read returns an error (no undefined behaviour) / kgsl 읽기는 오류를 돌려줌(정의되지 않은 동작 없음)
fr = re.compile(r"(kgsl_device_get_gpu_timestamp\(struct tu_device \*dev, uint64_t \*ts\)\s*\{)\s*UNREACHABLE\(\"\"\);\s*return 0;")
if len(fr.findall(k)) != 1:
    fail("kgsl_device_get_gpu_timestamp body not found once in tu_knl_kgsl.cc")
k = fr.sub(r"\1\n   /* EZsteam: no kgsl GPU timestamp read */\n   return -1;", k)

open(kgsl, "w").write(k)
open(dev, "w").write(d)
print("mesa-02-kgsl-timestamp: applied")
