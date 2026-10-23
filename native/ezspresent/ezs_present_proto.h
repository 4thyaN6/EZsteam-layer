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
};

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
