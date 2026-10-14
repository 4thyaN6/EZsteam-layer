#!/bin/sh
# GPU access check inside the rootfs (PRoot), started by the app's "GPU 확인" button.
# Each line is "OK <item> : detail", "FAIL <item> : detail" or "INFO <item> : detail".
# rootfs(PRoot) 안 GPU 접근 확인. 앱의 "GPU 확인" 버튼이 실행함.
# 각 줄은 "OK 항목 : 내용", "FAIL 항목 : 내용", "INFO 항목 : 내용" 형식임

ok()   { echo "OK   $1 : $2"; }
fail() { echo "FAIL $1 : $2"; }
info() { echo "INFO $1 : $2"; }

# 1. Who we are inside the guest (uid must be the app uid, not root)
#    guest 안에서 본 사용자(uid는 root가 아닌 앱 uid여야 함)
info id "$(id 2>&1)"

# 2. Device nodes the Turnip KGSL backend and buffer sharing may use
#    Turnip KGSL 백엔드와 버퍼 공유가 쓸 수 있는 장치 파일
for n in /dev/kgsl-3d0 /dev/dri /dev/dma_heap /dev/ion; do
    if [ -e "$n" ]; then info node "$(ls -ld "$n" 2>&1)"; else info node "$n 없음"; fi
done
[ -d /dev/dma_heap ] && info dma_heap "$(ls /dev/dma_heap 2>&1 | tr '\n' ' ')"

# 3. Open /dev/kgsl-3d0 read/write and read KGSL_PROP_DEVICE_INFO through an ioctl.
#    This is the first thing Turnip does; PRoot passes ioctl() through unchanged.
#    /dev/kgsl-3d0을 읽기·쓰기로 열고 ioctl로 KGSL_PROP_DEVICE_INFO를 읽음.
#    Turnip이 가장 먼저 하는 동작이며, PRoot는 ioctl()을 바꾸지 않고 그대로 넘김
python3 - <<'EOF'
import ctypes, fcntl, os, struct

def out(tag, item, detail):
    print(f"{tag:<4} {item} : {detail}")

try:
    fd = os.open("/dev/kgsl-3d0", os.O_RDWR | os.O_CLOEXEC)
except OSError as e:
    out("FAIL", "kgsl-open", f"errno={e.errno} {e.strerror}")
    raise SystemExit(0)
out("OK", "kgsl-open", f"fd={fd}")

# struct kgsl_devinfo (arm64): u32 device_id, u32 chip_id, u32 mmu_enabled, (pad),
#   u64 gmem_gpubaseaddr, u32 gpu_id, (pad), u64 gmem_sizebytes  -> 40 bytes
# struct kgsl_device_getproperty (arm64): u32 type, (pad), u64 value ptr, u64 sizebytes -> 24 bytes
# IOCTL_KGSL_DEVICE_GETPROPERTY = _IOWR(0x09, 0x2, struct kgsl_device_getproperty)
# KGSL_PROP_DEVICE_INFO = 1
# 구조체·ioctl 번호는 msm_kgsl.h 기준(arm64 정렬 포함)
devinfo = ctypes.create_string_buffer(40)
req = bytearray(struct.pack("<IxxxxQQ", 1, ctypes.addressof(devinfo), 40))
IOCTL = (3 << 30) | (24 << 16) | (0x09 << 8) | 0x02
try:
    fcntl.ioctl(fd, IOCTL, req, True)
    dev_id, chip_id, mmu, gmem_base, gpu_id, gmem_size = struct.unpack("<IIIxxxxQIxxxxQ", devinfo.raw)
    out("OK", "kgsl-devinfo",
        f"device_id={dev_id} chip_id=0x{chip_id:08x} gpu_id={gpu_id} mmu={mmu} gmem={gmem_size // 1024}KB")
except OSError as e:
    out("FAIL", "kgsl-devinfo", f"errno={e.errno} {e.strerror}")
finally:
    os.close(fd)
EOF

# 4. Vulkan loader and driver, only when installed (vulkan-tools in the rootfs, driver package later)
#    Vulkan 로더·드라이버(rootfs의 vulkan-tools, 드라이버 묶음이 있을 때만)
if command -v vulkaninfo >/dev/null 2>&1; then
    info vk-icd "VK_DRIVER_FILES=${VK_DRIVER_FILES:-(없음)}"
    # No X server is running for this check: hide DISPLAY so vulkaninfo does not try to open
    # a window surface (it aborts with "XCB failed to connect" otherwise)
    # 이 확인에는 X 서버가 없으므로 DISPLAY를 숨김(없으면 vulkaninfo가 창 표면을 만들려다
    # "XCB failed to connect"로 중단됨)
    env -u DISPLAY -u WAYLAND_DISPLAY vulkaninfo --summary 2>&1 | sed -n '/^Devices:/,$p' | head -n 30
    # 5. Proton (vkd3d-proton / DXVK) requirement check, full vulkaninfo saved to ~/ezs-logs
    #    Proton(vkd3d-proton·DXVK) 요구 항목 대조, vulkaninfo 전체는 ~/ezs-logs에 저장
    python3 /opt/ezsteam/vkcheck.py
else
    info vulkaninfo "없음 (rootfs에 vulkan-tools 없음)"
fi
echo "DONE"
