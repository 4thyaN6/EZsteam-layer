#!/usr/bin/env python3
# Turnip on kgsl: do not refuse the whole GPU when an application enables VK_KHR_display. Built
# with libdrm (the mesax bundle), Turnip offers the instance extension VK_KHR_display; vkcube enables
# it, and tu_knl_kgsl_load() then fails ("I can't KHR_display", VK_ERROR_INITIALIZATION_FAILED), so
# no GPU is found at all (gpu-check "vk-mesax": "vkEnumeratePhysicalDevices ... failed with error
# code -3", 2026-10-29). The kgsl-only turnip package does not offer the extension, so it never hit
# this. With the patch the GPU is found; there are no displays to list on kgsl (no DRM primary node).
# Own patch (MIT, same as Mesa). Applied by tools/build-mesax.sh only.
# Exit 0 = applied, 1 = anchor not found (build goes on without it).
# kgsl의 Turnip: 앱이 VK_KHR_display를 켰을 때 GPU 전체를 거부하지 않게 함. libdrm과 함께 빌드하면(mesax 묶음) Turnip이
# 인스턴스 확장 VK_KHR_display를 내놓음. vkcube가 이것을 켜면 tu_knl_kgsl_load()가 실패해("I can't KHR_display",
# VK_ERROR_INITIALIZATION_FAILED) GPU를 하나도 찾지 못함(gpu-check "vk-mesax": "vkEnumeratePhysicalDevices ... failed with
# error code -3", 2026-10-29). kgsl 전용 turnip 묶음은 이 확장을 내놓지 않아 이 문제가 없었음. 패치하면 GPU를 찾고,
# kgsl에는 나열할 디스플레이가 없음(DRM primary 노드 없음).
# 자체 패치(Mesa와 같은 MIT). tools/build-mesax.sh에서만 적용함.
# 종료 0 = 적용, 1 = 기준 위치를 못 찾음(빌드는 패치 없이 계속)
import re
import sys

kgsl = sys.argv[1] + "/src/freedreno/vulkan/tu_knl_kgsl.cc"
k = open(kgsl).read()
r = re.compile(r"[ \t]*if \(instance->vk\.enabled_extensions\.KHR_display\) \{\s*"
               r"return vk_errorf\(instance, VK_ERROR_INITIALIZATION_FAILED,\s*\"I can't KHR_display\"\);\s*\}\n")
if len(r.findall(k)) != 1:
    print("mesa-03-kgsl-khr-display: KHR_display refusal not found once (not applied)", file=sys.stderr)
    sys.exit(1)
k = r.sub("   /* EZsteam: VK_KHR_display enabled is not a reason to drop the GPU on kgsl */\n", k)
open(kgsl, "w").write(k)
print("mesa-03-kgsl-khr-display: applied")
