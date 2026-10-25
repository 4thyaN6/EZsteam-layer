/*
 * Messages between the rootfs side (Vulkan, glibc) and the app side (EGL / AHardwareBuffer,
 * bionic) of the game frame path. One message = one SOCK_SEQPACKET packet, descriptors travel
 * as SCM_RIGHTS with it. Both sides are aarch64 LP64, so the struct layout is the same.
 * Used now by the check tool (ezs-present-probe) and later by the present layer and the
 * compositor, so the wire format is shared from the start.
 * 게임 프레임 경로의 rootfs 쪽(Vulkan, glibc)과 앱 쪽(EGL / AHardwareBuffer, bionic) 사이 메시지.
 * 메시지 하나 = SOCK_SEQPACKET 패킷 하나, 디스크립터는 SCM_RIGHTS로 함께 감. 양쪽 모두 aarch64 LP64라
 * 구조체 배치가 같음. 지금은 확인 도구(ezs-present-probe)가 쓰고 나중에 present 레이어와 컴포지터가
 * 그대로 씀. 그래서 형식을 처음부터 공유함
 */
#ifndef EZS_PRESENT_PROTO_H
#define EZS_PRESENT_PROTO_H

#include <stdint.h>

/* Guest path of the socket (host: rootfs + this path) / 소켓 guest 경로(실제: rootfs + 이 경로) */
#define EZP_GUEST_SOCKET "/tmp/ezs-present.sock"
/* 'EZPR' little-endian / 리틀엔디언 'EZPR' */
#define EZP_MAGIC 0x52505a45u
#define EZP_VERSION 1u
#define EZP_MAX_FDS 8
#define EZP_MAX_PACKET 16384

/* DRM fourcc of R8G8B8A8 bytes in memory (R, G, B, A) / 메모리 바이트 순서 R,G,B,A의 DRM fourcc */
#define EZP_FOURCC_ABGR8888 0x34324241u
#define EZP_MOD_LINEAR 0ull
#define EZP_MOD_INVALID 0x00ffffffffffffffull

enum ezp_type {
    /* probe -> app: hello, version / 인사, 버전 */
    EZP_HELLO = 1,
    /* probe -> app: Vulkan-exported dma-buf (fd 0) + optional sync fd (fd 1), w h stride offset
     * fourcc modifier pattern / Vulkan이 내보낸 dma-buf(fd 0) + 선택 sync fd(fd 1) */
    EZP_A_BUF = 2,
    /* probe -> app: please allocate an AHardwareBuffer w x h / AHardwareBuffer w x h를 만들어 달라 */
    EZP_B_REQ = 3,
    /* app -> probe: header of the AHB (stride bytes, pattern written by CPU); the next packet is
     * AHardwareBuffer_sendHandleToUnixSocket's own / AHB 머리(stride 바이트, CPU가 쓴 무늬). 다음
     * 패킷은 AHardwareBuffer_sendHandleToUnixSocket 자체 패킷 */
    EZP_B_BUF = 4,
    /* probe -> app: Vulkan drew pattern into the AHB, optional sync fd (fd 0)
     * Vulkan이 AHB에 무늬를 그렸음, 선택 sync fd(fd 0) */
    EZP_B_DONE = 5,
    /* app -> probe: GL native fence fd (fd 0) for Vulkan to wait on / Vulkan이 기다릴 GL 펜스 fd */
    EZP_E_FENCE = 6,
    /* probe -> app: result of waiting on that fence (flags 1 = ok) / 그 펜스 대기 결과 */
    EZP_E_FENCE_DONE = 7,
    /* app -> probe: report text (len bytes after the header) / 보고 글(머리 뒤 len 바이트) */
    EZP_REPORT = 8,

    /* ---- game frame path (present layer <-> app compositor) / 게임 프레임 경로(present 레이어 <-> 앱 컴포지터) ---- */
    /* layer -> app, first packet of a layer connection: w h, pattern = buffer count, fourcc = VkFormat,
     * flags = byte order (EZP_ORDER_*), r0 = X window (0 = unknown), r1 = pid
     * 레이어 -> 앱, 레이어 연결의 첫 패킷: w h, pattern = 버퍼 수, fourcc = VkFormat, flags = 바이트 순서,
     * r0 = X 창(0 = 모름), r1 = pid */
    EZP_SWAPCHAIN = 9,
    /* app -> layer, once per buffer: r0 = index, stride = row bytes, modifier = DRM layout,
     * flags 1 = ok (0 = failed, the layer falls back); the next packet is
     * AHardwareBuffer_sendHandleToUnixSocket's
     * 앱 -> 레이어, 버퍼마다: r0 = 번호, stride = 줄 바이트, modifier = DRM 배치, flags 1 = 성공(0 = 실패,
     * 레이어는 기존 경로로).
     * 다음 패킷은 AHardwareBuffer_sendHandleToUnixSocket 패킷 */
    EZP_SC_BUF = 10,
    /* layer -> app: r0 = index just presented, flags 1 = sync fd attached (GPU done -> signalled)
     * 레이어 -> 앱: r0 = 방금 present한 번호, flags 1 = sync fd 첨부(GPU 끝나면 신호) */
    EZP_PRESENT = 11,
    /* app -> layer: r0 = index the compositor no longer reads, flags 1 = sync fd attached (GL done)
     * 앱 -> 레이어: r0 = 컴포지터가 더 읽지 않는 번호, flags 1 = sync fd 첨부(GL 끝나면 신호) */
    EZP_RELEASE = 12,
    /* app -> layer: frame limit, pattern = frames per second (0 = none); paced in the layer
     * 앱 -> 레이어: 프레임 제한, pattern = 초당 프레임(0 = 없음). 레이어가 간격을 맞춤 */
    EZP_SET_FPS = 13,

    /* ---- check C: GPU-only AHardwareBuffer layout (compression) / 확인 C: GPU 전용 AHB 배치(압축) ---- */
    /* probe -> app: allocate an AHB without CPU usage and clear it with GL to pattern
     * probe -> 앱: CPU 용도 없는 AHB를 만들고 GL로 pattern 색을 칠해 달라 */
    EZP_C_REQ = 14,
    /* app -> probe: header (stride, flags 1 = ok), then the AHB handle packet / 머리, 그다음 AHB 핸들 패킷 */
    EZP_C_BUF = 15,
    /* probe -> app: which layout read back right: flags 1 = linear, 2 = Qualcomm compressed;
     * modifier = the one to use (EZP_MOD_INVALID = none) / 어느 배치로 맞게 읽혔는지: flags 1 = linear,
     * 2 = Qualcomm 압축. modifier = 쓸 것(EZP_MOD_INVALID = 없음) */
    EZP_C_DONE = 16,
};

/* Qualcomm compressed (UBWC) DRM modifier / Qualcomm 압축(UBWC) DRM modifier */
#define EZP_MOD_QCOM_COMPRESSED 0x0500000000000001ull

/* Byte order of a 32-bit game frame in memory / 32비트 게임 프레임의 메모리 바이트 순서 */
#define EZP_ORDER_BGRA 0u
#define EZP_ORDER_RGBA 1u
/* Most buffers per swapchain / swapchain당 최대 버퍼 수 */
#define EZP_MAX_BUFFERS 8

struct ezp_msg {
    uint32_t magic, type, w, h;
    uint32_t stride;  /* bytes per row / 한 줄 바이트 */
    uint32_t offset;  /* plane offset / 평면 시작 위치 */
    uint32_t fourcc;
    uint32_t pattern; /* RGBA bytes packed as R | G<<8 | B<<16 | A<<24 / RGBA 바이트를 묶은 값 */
    uint32_t flags;
    uint32_t len;     /* bytes of text after the header (REPORT) / 머리 뒤 글 바이트 수 */
    uint32_t r0, r1;
    uint64_t modifier;
};

#endif
