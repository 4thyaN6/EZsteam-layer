#!/usr/bin/env python3
# Compare the Vulkan device against what Proton's D3D layers need, from the full vulkaninfo text.
# Prints "OK/FAIL/UNKNOWN <group> <item> : detail" lines and saves the full vulkaninfo output.
# 전체 vulkaninfo 텍스트로 Vulkan 장치를 Proton D3D 계층의 요구 항목과 대조함.
# "OK/FAIL/UNKNOWN 그룹 항목 : 내용" 줄을 출력하고 vulkaninfo 전체 결과를 저장함
#
# Requirement sources (project docs research_proton11_turnip_compat.md, context_gpu_driver_layer.md):
# 요구 항목 출처(프로젝트 문서 research_proton11_turnip_compat.md, context_gpu_driver_layer.md):
#   vkd3d-proton README: Vulkan 1.3, descriptor indexing with UpdateAfterBind 1,000,000+,
#     samplerMirrorClampToEdge, shaderDrawParameters, VK_EXT_robustness2, VK_KHR_push_descriptor;
#     strongly recommended: mutable_descriptor_type, descriptor_buffer
#   DXVK wiki (3.x list): Vulkan 1.4, maintenance5/6, robustness2, transform_feedback, depth_clip_enable
#   DroidDeck PR #6 (Mali, rejected by DXVK): multiViewport, shaderCullDistance,
#     textureCompressionBC, robustBufferAccess2
import os
import re
import subprocess
import sys

OUT_DIR = os.path.expanduser("~/ezs-logs")
OUT_FILE = os.path.join(OUT_DIR, "vulkaninfo.txt")

# Full vulkaninfo without an X display (no surface section needed here)
# X 디스플레이 없이 vulkaninfo 전체 실행(여기서는 표면 정보 불필요)
env = dict(os.environ)
env.pop("DISPLAY", None)
env.pop("WAYLAND_DISPLAY", None)
try:
    text = subprocess.run(["vulkaninfo"], env=env, capture_output=True, text=True, timeout=60).stdout
except Exception as e:  # missing tool, timeout / 도구 없음, 시간 초과
    print(f"FAIL vk vulkaninfo : {e}")
    sys.exit(0)
os.makedirs(OUT_DIR, exist_ok=True)
with open(OUT_FILE, "w") as f:
    f.write(text)

# "name = value" pairs (first occurrence wins) and listed extension names
# "이름 = 값" 쌍(처음 나온 값 사용)과 나열된 확장 이름
values = {}
for m in re.finditer(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.+?)\s*$", text, re.M):
    values.setdefault(m.group(1), m.group(2))
exts = set(re.findall(r"^\s*(VK_[A-Za-z0-9_]+)\s*:\s*extension revision", text, re.M))


def line(tag, group, item, detail):
    print(f"{tag:<7} {group:<8} {item} : {detail}")


def feature(group, name):
    """Boolean feature must be true / 불리언 기능이 true여야 함"""
    v = values.get(name)
    if v is None:
        line("UNKNOWN", group, name, "vulkaninfo에 없음")
    else:
        line("OK" if v.lower() == "true" else "FAIL", group, name, v)


def extension(group, *names):
    """Any one of the extension names must be present / 확장 이름 중 하나가 있어야 함"""
    have = [n for n in names if n in exts]
    line("OK" if have else "FAIL", group, " | ".join(names), have[0] if have else "없음")


def limit(group, name, minimum):
    """Numeric limit must reach the minimum / 수치 한도가 최소값 이상이어야 함"""
    v = values.get(name)
    try:
        n = int(v.split()[0], 0)
    except (AttributeError, ValueError):
        line("UNKNOWN", group, name, f"값 없음 ({v})")
        return
    line("OK" if n >= minimum else "FAIL", group, name, f"{n} (필요 {minimum} 이상)")


def api(group, major, minor):
    """Device apiVersion must reach major.minor / 장치 apiVersion이 major.minor 이상이어야 함"""
    v = values.get("apiVersion", "")
    m = re.match(r"(\d+)\.(\d+)", v)
    if not m:
        line("UNKNOWN", group, "apiVersion", v or "없음")
        return
    ok = (int(m.group(1)), int(m.group(2))) >= (major, minor)
    line("OK" if ok else "FAIL", group, f"apiVersion >= {major}.{minor}", v)


print(f"INFO    vk       device : {values.get('deviceName', '?')} / {values.get('driverInfo', '?')}")

# vkd3d-proton (D3D12) required / vkd3d-proton(D3D12) 필수
api("vkd3d", 1, 3)
feature("vkd3d", "samplerMirrorClampToEdge")
feature("vkd3d", "shaderDrawParameters")
extension("vkd3d", "VK_EXT_robustness2", "VK_KHR_robustness2")
extension("vkd3d", "VK_KHR_push_descriptor")
for f in ("descriptorIndexing", "descriptorBindingSampledImageUpdateAfterBind",
          "descriptorBindingStorageImageUpdateAfterBind", "descriptorBindingStorageBufferUpdateAfterBind",
          "descriptorBindingUniformTexelBufferUpdateAfterBind",
          "descriptorBindingStorageTexelBufferUpdateAfterBind", "runtimeDescriptorArray",
          "descriptorBindingPartiallyBound", "descriptorBindingVariableDescriptorCount"):
    feature("vkd3d", f)
for l in ("maxPerStageDescriptorUpdateAfterBindSampledImages",
          "maxPerStageDescriptorUpdateAfterBindStorageImages",
          "maxDescriptorSetUpdateAfterBindSampledImages"):
    limit("vkd3d", l, 1000000)

# vkd3d-proton strongly recommended / vkd3d-proton 강력 권장
extension("vkd3d권장", "VK_EXT_mutable_descriptor_type", "VK_VALVE_mutable_descriptor_type")
extension("vkd3d권장", "VK_EXT_descriptor_buffer")

# DXVK (D3D9-11) / DXVK(D3D9~11)
api("dxvk", 1, 3)
feature("dxvk", "robustBufferAccess2")
feature("dxvk", "nullDescriptor")
extension("dxvk", "VK_EXT_transform_feedback")
extension("dxvk", "VK_EXT_depth_clip_enable")
extension("dxvk3.x", "VK_KHR_maintenance5")
extension("dxvk3.x", "VK_KHR_maintenance6")
# Features DXVK refused on Mali (DroidDeck PR #6) / Mali에서 DXVK가 거부한 기능(DroidDeck PR #6)
for f in ("multiViewport", "shaderCullDistance", "textureCompressionBC"):
    feature("dxvk", f)

# Reference only: shader stages and blend features that Direct3D 11 class games use
# (not taken from a DXVK requirement list)
# 참고용: Direct3D 11급 게임이 쓰는 셰이더 단계·블렌드 기능(DXVK 요구 목록에서 가져온 것 아님)
for f in ("geometryShader", "tessellationShader", "depthClamp", "dualSrcBlend", "independentBlend"):
    feature("참고", f)

print(f"INFO    vk       saved : {OUT_FILE} ({len(text.splitlines())} lines)")
