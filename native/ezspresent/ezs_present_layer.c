/*
 * VK_LAYER_EZS_present: hands a game's frames to the app compositor without any copy.
 * The game's swapchain is replaced by buffers the app allocates (AHardwareBuffer, linear RGBA8,
 * passed as dma-buf over EZP_GUEST_SOCKET); the game's GPU draws straight into them and each
 * present sends the buffer number plus a sync fd. The app samples the buffer with EGL and gives it
 * back with its own GL fence. The X11 software output (GPU -> CPU -> Xvfb -> CPU -> GL upload) is
 * not used for that game any more. Checked first on the device by ezs-present-probe (path "1-b").
 * Turned on by EZ Proton for games only (VK_INSTANCE_LAYERS, last = next to the driver). When
 * anything is missing (extension, app socket, buffer import) the real swapchain is used as before.
 *
 * VK_LAYER_EZS_present: 게임 프레임을 복사 없이 앱 컴포지터로 넘김. 게임 swapchain을 앱이 만든 버퍼
 * (AHardwareBuffer, linear RGBA8, EZP_GUEST_SOCKET으로 dma-buf 전달)로 바꿈. 게임 GPU가 그곳에 바로 그리고,
 * present마다 버퍼 번호와 sync fd를 보냄. 앱은 EGL로 읽은 뒤 자기 GL 펜스와 함께 돌려줌. 그 게임에는 X11
 * 소프트웨어 출력(GPU -> CPU -> Xvfb -> CPU -> GL 업로드)을 더 쓰지 않음. 기기에서 ezs-present-probe로 먼저
 * 확인한 경로("1-b"). EZ Proton이 게임에만 켬(VK_INSTANCE_LAYERS 마지막 = 드라이버 바로 위). 확장, 앱 소켓,
 * 버퍼 가져오기 중 하나라도 없으면 지금처럼 실제 swapchain을 씀
 */
#define _GNU_SOURCE
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>

#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include "ezs_present_proto.h"

#define EXPORT __attribute__((visibility("default")))
#define LOG(...) do { fprintf(stderr, "ezs-present: " __VA_ARGS__); fputc('\n', stderr); } while (0)

/* Window-system structs, declared here so no X11 headers are needed (same layout as Vulkan's)
 * 창 시스템 구조체. X11 헤더 없이 쓰려고 여기서 선언함(Vulkan 것과 같은 배치) */
typedef struct {
    VkStructureType sType;
    const void *pNext;
    VkFlags flags;
    void *dpy;
    unsigned long window;
} EzsXlibSurfaceCreateInfo;
typedef struct {
    VkStructureType sType;
    const void *pNext;
    VkFlags flags;
    void *connection;
    uint32_t window;
} EzsXcbSurfaceCreateInfo;
typedef VkResult (*PFN_ezsCreateSurface)(VkInstance, const void *, const VkAllocationCallbacks *, VkSurfaceKHR *);

/* Dispatch key of a dispatchable handle (loader's table pointer) / 디스패치 키(로더 표 포인터) */
static void *key_of(const void *h)
{
    return *(void *const *)h;
}

static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;

/* ---- instance / 인스턴스 ---- */
struct inst {
    void *key;
    VkInstance h;
    PFN_vkGetInstanceProcAddr gipa;
    PFN_vkDestroyInstance DestroyInstance;
    PFN_vkEnumerateDeviceExtensionProperties EnumerateDeviceExtensionProperties;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR GetSurfaceFormats;
    PFN_vkGetPhysicalDeviceSurfaceFormats2KHR GetSurfaceFormats2;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR GetSurfaceCaps;
    PFN_vkGetPhysicalDeviceSurfaceCapabilities2KHR GetSurfaceCaps2;
    PFN_vkGetPhysicalDeviceFormatProperties2 GetFormatProps2;
    PFN_ezsCreateSurface CreateXlibSurface, CreateXcbSurface;
};
#define MAX_INST 8
static struct inst *g_inst[MAX_INST];

static struct inst *inst_of(const void *h)
{
    void *k = key_of(h);
    struct inst *r = NULL;
    pthread_mutex_lock(&g_mu);
    for (int i = 0; i < MAX_INST; i++)
        if (g_inst[i] && g_inst[i]->key == k) r = g_inst[i];
    /* Physical devices normally share the instance key; else the only instance
     * 물리 장치는 보통 인스턴스 키를 같이 씀. 아니면 유일한 인스턴스 */
    if (!r)
        for (int i = 0; i < MAX_INST; i++)
            if (g_inst[i]) { r = g_inst[i]; break; }
    pthread_mutex_unlock(&g_mu);
    return r;
}

/* Surface -> X window, for placing the frame in the app / 표면 -> X 창, 앱에서 프레임 위치용 */
#define MAX_SURF 32
static struct { VkSurfaceKHR s; uint32_t win; } g_surf[MAX_SURF];

static void surf_put(VkSurfaceKHR s, uint32_t win)
{
    pthread_mutex_lock(&g_mu);
    int slot = -1;
    for (int i = 0; i < MAX_SURF; i++) {
        if (g_surf[i].s == s) { slot = i; break; }
        if (slot < 0 && !g_surf[i].s) slot = i;
    }
    if (slot >= 0) { g_surf[slot].s = s; g_surf[slot].win = win; }
    pthread_mutex_unlock(&g_mu);
}

static uint32_t surf_win(VkSurfaceKHR s)
{
    uint32_t w = 0;
    pthread_mutex_lock(&g_mu);
    for (int i = 0; i < MAX_SURF; i++)
        if (g_surf[i].s == s) w = g_surf[i].win;
    pthread_mutex_unlock(&g_mu);
    return w;
}

/* ---- device / 장치 ---- */
struct dev {
    void *key;
    VkDevice h;
    VkPhysicalDevice phys;
    struct inst *in;
    PFN_vkGetDeviceProcAddr gdpa;
    PFN_vkSetDeviceLoaderData set_data;
    int ok;        /* all extensions this path needs are enabled / 이 경로에 필요한 확장이 모두 켜짐 */
    int fence_fd;  /* VK_KHR_external_fence_fd enabled / 켜짐 */
    int foreign;   /* VK_EXT_queue_family_foreign enabled / 켜짐 */
    struct { VkQueue q; uint32_t fam; } queues[16];
#define DF(n) PFN_vk##n n
    DF(DestroyDevice); DF(GetDeviceQueue); DF(GetDeviceQueue2);
    DF(CreateSwapchainKHR); DF(DestroySwapchainKHR); DF(GetSwapchainImagesKHR); DF(AcquireNextImageKHR);
    DF(AcquireNextImage2KHR); DF(QueuePresentKHR); DF(GetSwapchainStatusKHR); DF(WaitForPresentKHR);
    DF(ReleaseSwapchainImagesEXT);
    DF(CreateImage); DF(DestroyImage); DF(GetImageMemoryRequirements); DF(AllocateMemory); DF(FreeMemory);
    DF(BindImageMemory); DF(GetMemoryFdPropertiesKHR); DF(ImportSemaphoreFdKHR); DF(ImportFenceFdKHR);
    DF(GetSemaphoreFdKHR); DF(CreateSemaphore); DF(DestroySemaphore); DF(CreateCommandPool);
    DF(DestroyCommandPool); DF(AllocateCommandBuffers); DF(BeginCommandBuffer); DF(EndCommandBuffer);
    DF(CmdPipelineBarrier); DF(QueueSubmit);
#undef DF
};
#define MAX_DEV 8
static struct dev *g_dev[MAX_DEV];

static struct dev *dev_of(const void *h)
{
    void *k = key_of(h);
    struct dev *r = NULL;
    pthread_mutex_lock(&g_mu);
    for (int i = 0; i < MAX_DEV; i++)
        if (g_dev[i] && g_dev[i]->key == k) r = g_dev[i];
    pthread_mutex_unlock(&g_mu);
    return r;
}

static uint32_t queue_family(struct dev *d, VkQueue q)
{
    for (int i = 0; i < 16; i++)
        if (d->queues[i].q == q) return d->queues[i].fam;
    return 0;
}

/* ---- our swapchain / 우리 swapchain ---- */
struct buf {
    VkImage img;
    VkDeviceMemory mem;
    VkSemaphore done;      /* exportable, signalled by the present submit / 내보낼 수 있음, present 제출이 신호 */
    VkCommandBuffer rel;   /* release to the outside (foreign queue family) / 밖으로 넘김 */
    int state;             /* 0 free, 1 game, 2 app / 0 비어 있음, 1 게임, 2 앱 */
    int fence;             /* app's GL fence for this buffer, -1 none / 이 버퍼의 앱 GL 펜스, -1 없음 */
};
struct swc {
    struct dev *d;
    int sock;
    int dead;
    uint32_t count, w, h;
    VkCommandPool pool;
    uint32_t pool_fam;
    struct buf b[EZP_MAX_BUFFERS];
    pthread_mutex_t mu;
};
#define MAX_SWC 32
static struct swc *g_swc[MAX_SWC];

static struct swc *swc_of(VkSwapchainKHR h)
{
    struct swc *r = NULL;
    pthread_mutex_lock(&g_mu);
    for (int i = 0; i < MAX_SWC; i++)
        if (g_swc[i] && (VkSwapchainKHR)(uintptr_t)g_swc[i] == h) r = g_swc[i];
    pthread_mutex_unlock(&g_mu);
    return r;
}

/* ---- socket / 소켓 ---- */
static int send_msg(int s, struct ezp_msg *m, int fd)
{
    m->magic = EZP_MAGIC;
    struct iovec iov = {m, sizeof *m};
    char cbuf[CMSG_SPACE(sizeof(int))];
    struct msghdr h;
    memset(&h, 0, sizeof h);
    h.msg_iov = &iov;
    h.msg_iovlen = 1;
    if (fd >= 0) {
        memset(cbuf, 0, sizeof cbuf);
        h.msg_control = cbuf;
        h.msg_controllen = sizeof cbuf;
        struct cmsghdr *c = CMSG_FIRSTHDR(&h);
        c->cmsg_level = SOL_SOCKET;
        c->cmsg_type = SCM_RIGHTS;
        c->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(c), &fd, sizeof fd);
    }
    return sendmsg(s, &h, MSG_NOSIGNAL) < 0 ? -1 : 0;
}

/* One packet; descriptors into fds (count in *nfds) / 패킷 하나, 디스크립터는 fds로 */
static ssize_t recv_pkt(int s, void *buf, size_t cap, int *fds, int *nfds)
{
    char cbuf[CMSG_SPACE(sizeof(int) * EZP_MAX_FDS)];
    struct iovec iov = {buf, cap};
    struct msghdr h;
    memset(&h, 0, sizeof h);
    h.msg_iov = &iov;
    h.msg_iovlen = 1;
    h.msg_control = cbuf;
    h.msg_controllen = sizeof cbuf;
    ssize_t n = recvmsg(s, &h, MSG_CMSG_CLOEXEC);
    *nfds = 0;
    if (n <= 0) return n == 0 ? -1 : n;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&h); c; c = CMSG_NXTHDR(&h, c))
        if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS) {
            int k = (int)((c->cmsg_len - CMSG_LEN(0)) / sizeof(int));
            for (int i = 0; i < k && *nfds < EZP_MAX_FDS; i++) fds[(*nfds)++] = ((int *)CMSG_DATA(c))[i];
        }
    return n;
}

static int connect_app(void)
{
    const char *path = getenv("EZS_PRESENT_SOCK");
    if (!path || !*path) path = EZP_GUEST_SOCKET;
    int s = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (s < 0) return -1;
    struct sockaddr_un a;
    memset(&a, 0, sizeof a);
    a.sun_family = AF_UNIX;
    snprintf(a.sun_path, sizeof a.sun_path, "%s", path);
    if (connect(s, (struct sockaddr *)&a, sizeof a) != 0) {
        LOG("앱 연결 실패 %s (%s) — 기존 화면 경로 사용", path, strerror(errno));
        close(s);
        return -1;
    }
    return s;
}

/* 32-bit formats we can share, and their byte order / 공유할 수 있는 32비트 형식과 바이트 순서 */
static int format_order(VkFormat f)
{
    switch (f) {
    case VK_FORMAT_B8G8R8A8_UNORM:
    case VK_FORMAT_B8G8R8A8_SRGB:
        return (int)EZP_ORDER_BGRA;
    case VK_FORMAT_R8G8B8A8_UNORM:
    case VK_FORMAT_R8G8B8A8_SRGB:
    case VK_FORMAT_A8B8G8R8_UNORM_PACK32:
    case VK_FORMAT_A8B8G8R8_SRGB_PACK32:
        return (int)EZP_ORDER_RGBA;
    default:
        return -1;
    }
}

/* Image usages a linear shared image can take on this GPU (checked on the device by the probe:
 * colour attachment, sampled, transfer) / 이 GPU에서 linear 공유 이미지가 받을 수 있는 용도(기기에서
 * probe로 확인: 색 첨부, 샘플, 전송) */
#define SHARED_USAGE (VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | \
                      VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | \
                      VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT)

/* ================= instance-level hooks / 인스턴스 수준 가로채기 ================= */

static VKAPI_ATTR VkResult VKAPI_CALL ezs_CreateInstance(const VkInstanceCreateInfo *ci,
                                                         const VkAllocationCallbacks *a, VkInstance *out)
{
    VkLayerInstanceCreateInfo *li = (VkLayerInstanceCreateInfo *)ci->pNext;
    while (li && !(li->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO && li->function == VK_LAYER_LINK_INFO))
        li = (VkLayerInstanceCreateInfo *)li->pNext;
    if (!li) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr next = li->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    li->u.pLayerInfo = li->u.pLayerInfo->pNext;
    PFN_vkCreateInstance create = (PFN_vkCreateInstance)next(VK_NULL_HANDLE, "vkCreateInstance");
    VkResult r = create(ci, a, out);
    if (r != VK_SUCCESS) return r;
    struct inst *in = calloc(1, sizeof *in);
    in->key = key_of(*out);
    in->h = *out;
    in->gipa = next;
#define IL(f, n) in->f = (void *)next(*out, n)
    IL(DestroyInstance, "vkDestroyInstance");
    IL(EnumerateDeviceExtensionProperties, "vkEnumerateDeviceExtensionProperties");
    IL(GetSurfaceFormats, "vkGetPhysicalDeviceSurfaceFormatsKHR");
    IL(GetSurfaceFormats2, "vkGetPhysicalDeviceSurfaceFormats2KHR");
    IL(GetSurfaceCaps, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
    IL(GetSurfaceCaps2, "vkGetPhysicalDeviceSurfaceCapabilities2KHR");
    IL(GetFormatProps2, "vkGetPhysicalDeviceFormatProperties2");
    IL(CreateXlibSurface, "vkCreateXlibSurfaceKHR");
    IL(CreateXcbSurface, "vkCreateXcbSurfaceKHR");
#undef IL
    pthread_mutex_lock(&g_mu);
    for (int i = 0; i < MAX_INST; i++)
        if (!g_inst[i]) { g_inst[i] = in; break; }
    pthread_mutex_unlock(&g_mu);
    return VK_SUCCESS;
}

static VKAPI_ATTR void VKAPI_CALL ezs_DestroyInstance(VkInstance h, const VkAllocationCallbacks *a)
{
    struct inst *in = inst_of(h);
    if (!in) return;
    PFN_vkDestroyInstance f = in->DestroyInstance;
    pthread_mutex_lock(&g_mu);
    for (int i = 0; i < MAX_INST; i++)
        if (g_inst[i] == in) g_inst[i] = NULL;
    pthread_mutex_unlock(&g_mu);
    free(in);
    if (f) f(h, a);
}

static VKAPI_ATTR VkResult VKAPI_CALL ezs_CreateXlibSurfaceKHR(VkInstance h, const void *ci,
                                                               const VkAllocationCallbacks *a, VkSurfaceKHR *s)
{
    struct inst *in = inst_of(h);
    if (!in || !in->CreateXlibSurface) return VK_ERROR_EXTENSION_NOT_PRESENT;
    VkResult r = in->CreateXlibSurface(h, ci, a, s);
    if (r == VK_SUCCESS) surf_put(*s, (uint32_t)((const EzsXlibSurfaceCreateInfo *)ci)->window);
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL ezs_CreateXcbSurfaceKHR(VkInstance h, const void *ci,
                                                              const VkAllocationCallbacks *a, VkSurfaceKHR *s)
{
    struct inst *in = inst_of(h);
    if (!in || !in->CreateXcbSurface) return VK_ERROR_EXTENSION_NOT_PRESENT;
    VkResult r = in->CreateXcbSurface(h, ci, a, s);
    if (r == VK_SUCCESS) surf_put(*s, ((const EzsXcbSurfaceCreateInfo *)ci)->window);
    return r;
}

/* Only the 8-bit sRGB-space formats the shared buffer carries (no HDR / 10-bit)
 * 공유 버퍼가 담는 8비트 sRGB 공간 형식만(HDR / 10비트 제외) */
static int keep_format(const VkSurfaceFormatKHR *f)
{
    return format_order(f->format) >= 0 && f->colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
}

static VKAPI_ATTR VkResult VKAPI_CALL ezs_GetPhysicalDeviceSurfaceFormatsKHR(VkPhysicalDevice p, VkSurfaceKHR s,
                                                                             uint32_t *n, VkSurfaceFormatKHR *out)
{
    struct inst *in = inst_of(p);
    if (!in || !in->GetSurfaceFormats) return VK_ERROR_INITIALIZATION_FAILED;
    uint32_t all = 0;
    VkResult r = in->GetSurfaceFormats(p, s, &all, NULL);
    if (r != VK_SUCCESS) return r;
    VkSurfaceFormatKHR *tmp = calloc(all ? all : 1, sizeof *tmp);
    r = in->GetSurfaceFormats(p, s, &all, tmp);
    uint32_t k = 0;
    for (uint32_t i = 0; i < all; i++)
        if (keep_format(&tmp[i])) tmp[k++] = tmp[i];
    if (k == 0) { /* nothing shareable: report what the driver has / 공유 가능한 것 없음: 드라이버 것 그대로 */
        k = all;
        in->GetSurfaceFormats(p, s, &all, tmp);
    }
    if (!out) {
        *n = k;
        r = VK_SUCCESS;
    } else {
        uint32_t c = *n < k ? *n : k;
        memcpy(out, tmp, c * sizeof *tmp);
        *n = c;
        r = c < k ? VK_INCOMPLETE : VK_SUCCESS;
    }
    free(tmp);
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL ezs_GetPhysicalDeviceSurfaceFormats2KHR(VkPhysicalDevice p,
                                                                              const VkPhysicalDeviceSurfaceInfo2KHR *si,
                                                                              uint32_t *n, VkSurfaceFormat2KHR *out)
{
    struct inst *in = inst_of(p);
    if (!in || !in->GetSurfaceFormats2) return VK_ERROR_INITIALIZATION_FAILED;
    uint32_t all = 0;
    VkResult r = in->GetSurfaceFormats2(p, si, &all, NULL);
    if (r != VK_SUCCESS) return r;
    VkSurfaceFormat2KHR *tmp = calloc(all ? all : 1, sizeof *tmp);
    for (uint32_t i = 0; i < all; i++) tmp[i].sType = VK_STRUCTURE_TYPE_SURFACE_FORMAT_2_KHR;
    r = in->GetSurfaceFormats2(p, si, &all, tmp);
    uint32_t k = 0;
    for (uint32_t i = 0; i < all; i++)
        if (keep_format(&tmp[i].surfaceFormat)) tmp[k++] = tmp[i];
    if (k == 0) k = all;
    if (!out) {
        *n = k;
        r = VK_SUCCESS;
    } else {
        /* Keep the caller's pNext chains, copy only the format / 호출한 쪽 pNext는 두고 형식만 복사 */
        uint32_t c = *n < k ? *n : k;
        for (uint32_t i = 0; i < c; i++) out[i].surfaceFormat = tmp[i].surfaceFormat;
        *n = c;
        r = c < k ? VK_INCOMPLETE : VK_SUCCESS;
    }
    free(tmp);
    return r;
}

static void fix_caps(VkSurfaceCapabilitiesKHR *c)
{
    c->supportedUsageFlags &= SHARED_USAGE;
    c->supportedUsageFlags |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
}

static VKAPI_ATTR VkResult VKAPI_CALL ezs_GetPhysicalDeviceSurfaceCapabilitiesKHR(VkPhysicalDevice p, VkSurfaceKHR s,
                                                                                  VkSurfaceCapabilitiesKHR *c)
{
    struct inst *in = inst_of(p);
    if (!in || !in->GetSurfaceCaps) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult r = in->GetSurfaceCaps(p, s, c);
    if (r == VK_SUCCESS) fix_caps(c);
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL ezs_GetPhysicalDeviceSurfaceCapabilities2KHR(
    VkPhysicalDevice p, const VkPhysicalDeviceSurfaceInfo2KHR *si, VkSurfaceCapabilities2KHR *c)
{
    struct inst *in = inst_of(p);
    if (!in || !in->GetSurfaceCaps2) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult r = in->GetSurfaceCaps2(p, si, c);
    if (r == VK_SUCCESS) fix_caps(&c->surfaceCapabilities);
    return r;
}

/* ================= device creation / 장치 만들기 ================= */

static VKAPI_ATTR VkResult VKAPI_CALL ezs_CreateDevice(VkPhysicalDevice p, const VkDeviceCreateInfo *ci,
                                                       const VkAllocationCallbacks *a, VkDevice *out)
{
    VkLayerDeviceCreateInfo *li = (VkLayerDeviceCreateInfo *)ci->pNext;
    while (li && !(li->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO && li->function == VK_LAYER_LINK_INFO))
        li = (VkLayerDeviceCreateInfo *)li->pNext;
    if (!li) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr ngipa = li->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr ngdpa = li->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    li->u.pLayerInfo = li->u.pLayerInfo->pNext;
    /* Loader callback that gives our own command buffers their dispatch table
     * 우리 명령 버퍼에 디스패치 표를 붙여 주는 로더 콜백 */
    PFN_vkSetDeviceLoaderData set_data = NULL;
    for (VkLayerDeviceCreateInfo *c = (VkLayerDeviceCreateInfo *)ci->pNext; c; c = (VkLayerDeviceCreateInfo *)c->pNext)
        if (c->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO && c->function == VK_LOADER_DATA_CALLBACK)
            set_data = c->u.pfnSetDeviceLoaderData;
    struct inst *in = inst_of(p);
    PFN_vkCreateDevice create = (PFN_vkCreateDevice)ngipa(in ? in->h : VK_NULL_HANDLE, "vkCreateDevice");

    /* Add the extensions the shared buffers need, when the GPU has them
     * 공유 버퍼에 필요한 확장을 GPU에 있으면 더함 */
    static const char *const need[] = {
        VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME, VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME,
        VK_EXT_IMAGE_DRM_FORMAT_MODIFIER_EXTENSION_NAME, VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME,
        VK_KHR_EXTERNAL_FENCE_FD_EXTENSION_NAME, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
        VK_KHR_IMAGE_FORMAT_LIST_EXTENSION_NAME,
    };
    enum { N_NEED = sizeof need / sizeof need[0] };
    int have[N_NEED] = {0};
    uint32_t ne = 0;
    VkExtensionProperties *ext = NULL;
    if (in && in->EnumerateDeviceExtensionProperties) {
        in->EnumerateDeviceExtensionProperties(p, NULL, &ne, NULL);
        ext = calloc(ne ? ne : 1, sizeof *ext);
        in->EnumerateDeviceExtensionProperties(p, NULL, &ne, ext);
    }
    for (int k = 0; k < N_NEED; k++)
        for (uint32_t i = 0; i < ne; i++)
            if (!strcmp(ext[i].extensionName, need[k])) have[k] = 1;
    free(ext);
    const char **names = calloc(ci->enabledExtensionCount + N_NEED, sizeof *names);
    uint32_t nn = 0;
    for (uint32_t i = 0; i < ci->enabledExtensionCount; i++) names[nn++] = ci->ppEnabledExtensionNames[i];
    for (int k = 0; k < N_NEED; k++) {
        if (!have[k]) continue;
        int dup = 0;
        for (uint32_t i = 0; i < nn; i++)
            if (!strcmp(names[i], need[k])) dup = 1;
        if (!dup) names[nn++] = need[k];
    }
    VkDeviceCreateInfo mod = *ci;
    mod.enabledExtensionCount = nn;
    mod.ppEnabledExtensionNames = names;
    VkResult r = create(p, &mod, a, out);
    if (r != VK_SUCCESS) {
        /* Retry exactly as the game asked / 게임이 요청한 그대로 다시 시도 */
        r = create(p, ci, a, out);
        for (int k = 0; k < N_NEED; k++) have[k] = 0;
    }
    free(names);
    if (r != VK_SUCCESS) return r;

    struct dev *d = calloc(1, sizeof *d);
    d->key = key_of(*out);
    d->h = *out;
    d->phys = p;
    d->in = in;
    d->gdpa = ngdpa;
    d->set_data = set_data;
    d->ok = have[0] && have[1] && have[2] && have[3] && set_data;
    d->fence_fd = have[4];
    d->foreign = have[5];
#define DL(n) d->n = (PFN_vk##n)ngdpa(*out, "vk" #n)
    DL(DestroyDevice); DL(GetDeviceQueue); DL(GetDeviceQueue2);
    DL(CreateSwapchainKHR); DL(DestroySwapchainKHR); DL(GetSwapchainImagesKHR); DL(AcquireNextImageKHR);
    DL(AcquireNextImage2KHR); DL(QueuePresentKHR); DL(GetSwapchainStatusKHR); DL(WaitForPresentKHR);
    DL(ReleaseSwapchainImagesEXT);
    DL(CreateImage); DL(DestroyImage); DL(GetImageMemoryRequirements); DL(AllocateMemory); DL(FreeMemory);
    DL(BindImageMemory); DL(GetMemoryFdPropertiesKHR); DL(ImportSemaphoreFdKHR); DL(ImportFenceFdKHR);
    DL(GetSemaphoreFdKHR); DL(CreateSemaphore); DL(DestroySemaphore); DL(CreateCommandPool);
    DL(DestroyCommandPool); DL(AllocateCommandBuffers); DL(BeginCommandBuffer); DL(EndCommandBuffer);
    DL(CmdPipelineBarrier); DL(QueueSubmit);
#undef DL
    if (!d->GetMemoryFdPropertiesKHR || !d->ImportSemaphoreFdKHR || !d->GetSemaphoreFdKHR) d->ok = 0;
    if (!d->ImportFenceFdKHR) d->fence_fd = 0;
    LOG("장치 준비: 직접 전달 %s (fence fd %s)", d->ok ? "가능" : "불가 — 기존 화면 경로", d->fence_fd ? "있음" : "없음");
    pthread_mutex_lock(&g_mu);
    for (int i = 0; i < MAX_DEV; i++)
        if (!g_dev[i]) { g_dev[i] = d; break; }
    pthread_mutex_unlock(&g_mu);
    return VK_SUCCESS;
}

static VKAPI_ATTR void VKAPI_CALL ezs_DestroyDevice(VkDevice h, const VkAllocationCallbacks *a)
{
    struct dev *d = dev_of(h);
    if (!d) return;
    PFN_vkDestroyDevice f = d->DestroyDevice;
    pthread_mutex_lock(&g_mu);
    for (int i = 0; i < MAX_DEV; i++)
        if (g_dev[i] == d) g_dev[i] = NULL;
    pthread_mutex_unlock(&g_mu);
    free(d);
    f(h, a);
}

/* Remember each queue's family (needed for the release barrier) / 큐마다 패밀리를 기억(넘김 장벽에 필요) */
static void note_queue(struct dev *d, VkQueue q, uint32_t fam)
{
    pthread_mutex_lock(&g_mu);
    for (int i = 0; i < 16; i++) {
        if (d->queues[i].q == q) break;
        if (!d->queues[i].q) {
            d->queues[i].q = q;
            d->queues[i].fam = fam;
            break;
        }
    }
    pthread_mutex_unlock(&g_mu);
}

static VKAPI_ATTR void VKAPI_CALL ezs_GetDeviceQueue(VkDevice h, uint32_t fam, uint32_t idx, VkQueue *q)
{
    struct dev *d = dev_of(h);
    d->GetDeviceQueue(h, fam, idx, q);
    if (*q) note_queue(d, *q, fam);
}

static VKAPI_ATTR void VKAPI_CALL ezs_GetDeviceQueue2(VkDevice h, const VkDeviceQueueInfo2 *qi, VkQueue *q)
{
    struct dev *d = dev_of(h);
    d->GetDeviceQueue2(h, qi, q);
    if (*q) note_queue(d, *q, qi->queueFamilyIndex);
}

/* ================= swapchain / swapchain ================= */

static void swc_free(struct swc *sc)
{
    struct dev *d = sc->d;
    for (uint32_t i = 0; i < sc->count; i++) {
        struct buf *b = &sc->b[i];
        if (b->img) d->DestroyImage(d->h, b->img, NULL);
        if (b->mem) d->FreeMemory(d->h, b->mem, NULL);
        if (b->done) d->DestroySemaphore(d->h, b->done, NULL);
        if (b->fence >= 0) close(b->fence);
    }
    if (sc->pool) d->DestroyCommandPool(d->h, sc->pool, NULL);
    if (sc->sock >= 0) close(sc->sock);
    pthread_mutex_destroy(&sc->mu);
    free(sc);
}

/* Build our swapchain on app buffers; NULL when anything is missing (caller falls back)
 * 앱 버퍼로 우리 swapchain을 만듦. 하나라도 없으면 NULL(호출한 쪽이 기존 경로로) */
static struct swc *swc_create(struct dev *d, const VkSwapchainCreateInfoKHR *ci)
{
    int order = format_order(ci->imageFormat);
    if (!d->ok || order < 0 || ci->imageArrayLayers != 1 || (ci->imageUsage & ~SHARED_USAGE)) {
        LOG("직접 전달 안 함 (형식 %d, 용도 0x%x, 장치 %s) — 기존 화면 경로", (int)ci->imageFormat,
            (unsigned)ci->imageUsage, d->ok ? "가능" : "불가");
        return NULL;
    }
    int s = connect_app();
    if (s < 0) return NULL;
    struct swc *sc = calloc(1, sizeof *sc);
    sc->d = d;
    sc->sock = s;
    pthread_mutex_init(&sc->mu, NULL);
    sc->w = ci->imageExtent.width;
    sc->h = ci->imageExtent.height;
    /* One shown + one waiting + at least one for the game, so the game never waits on the app
     * 하나는 표시 중 + 하나는 대기 + 게임용 최소 하나. 게임이 앱을 기다리지 않게 함 */
    uint32_t n = ci->minImageCount < 3 ? 3 : ci->minImageCount;
    if (n > EZP_MAX_BUFFERS) n = EZP_MAX_BUFFERS;
    for (uint32_t i = 0; i < EZP_MAX_BUFFERS; i++) sc->b[i].fence = -1;

    struct ezp_msg m;
    memset(&m, 0, sizeof m);
    m.type = EZP_SWAPCHAIN;
    m.w = sc->w;
    m.h = sc->h;
    m.pattern = n;
    m.fourcc = (uint32_t)ci->imageFormat;
    m.flags = (uint32_t)order;
    m.r0 = surf_win(ci->surface);
    m.r1 = (uint32_t)getpid();
    if (send_msg(s, &m, -1) != 0) goto fail;

    /* Formats the image may be viewed as (mutable-format swapchains) / 이미지를 볼 수 있는 형식(가변 형식 swapchain) */
    const VkImageFormatListCreateInfo *fl = NULL;
    for (const VkBaseInStructure *p = ci->pNext; p; p = p->pNext)
        if (p->sType == VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO) fl = (const VkImageFormatListCreateInfo *)p;

    struct timeval tv = {10, 0};
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    for (uint32_t i = 0; i < n; i++) {
        char pkt[EZP_MAX_PACKET];
        int fds[EZP_MAX_FDS], nf = 0;
        ssize_t k = recv_pkt(s, pkt, sizeof pkt, fds, &nf);
        for (int j = 0; j < nf; j++) close(fds[j]);
        struct ezp_msg h;
        if (k < (ssize_t)sizeof h) goto fail;
        memcpy(&h, pkt, sizeof h);
        if (h.magic != EZP_MAGIC || h.type != EZP_SC_BUF || h.flags != 1 || h.r0 != i) {
            LOG("앱이 버퍼 %u을 만들지 못함 — 기존 화면 경로", i);
            goto fail;
        }
        /* AHardwareBuffer_sendHandleToUnixSocket packet: the dma-buf is the descriptor big enough
         * for the image / AHardwareBuffer_sendHandleToUnixSocket 패킷: dma-buf는 이미지를 담을 만큼 큰 것 */
        k = recv_pkt(s, pkt, sizeof pkt, fds, &nf);
        if (k < 0) goto fail;
        int buf_fd = -1;
        for (int j = 0; j < nf; j++) {
            off_t sz = lseek(fds[j], 0, SEEK_END);
            if (buf_fd < 0 && sz >= (off_t)h.stride * sc->h) buf_fd = fds[j];
        }
        VkResult r = VK_ERROR_INVALID_EXTERNAL_HANDLE;
        if (buf_fd >= 0) {
            /* Explicit linear layout with the AHB row pitch / AHB 줄 간격으로 linear 배치를 명시 */
            VkSubresourceLayout pl = {0, 0, h.stride, 0, 0};
            VkImageFormatListCreateInfo flc;
            VkImageDrmFormatModifierExplicitCreateInfoEXT exp = {
                VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_EXPLICIT_CREATE_INFO_EXT, NULL, EZP_MOD_LINEAR, 1, &pl};
            if (fl) {
                flc = *fl;
                flc.pNext = NULL;
                exp.pNext = &flc;
            }
            VkExternalMemoryImageCreateInfo emi = {VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO, &exp,
                                                   VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
            VkImageCreateFlags fl_bits = 0;
            if (ci->flags & VK_SWAPCHAIN_CREATE_MUTABLE_FORMAT_BIT_KHR) fl_bits |= VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT;
            VkImageCreateInfo ic = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, &emi, fl_bits, VK_IMAGE_TYPE_2D,
                                    ci->imageFormat, {sc->w, sc->h, 1}, 1, 1, VK_SAMPLE_COUNT_1_BIT,
                                    VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT, ci->imageUsage, ci->imageSharingMode,
                                    ci->queueFamilyIndexCount, ci->pQueueFamilyIndices, VK_IMAGE_LAYOUT_UNDEFINED};
            r = d->CreateImage(d->h, &ic, NULL, &sc->b[i].img);
            VkMemoryFdPropertiesKHR fp = {VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR, NULL, 0};
            if (r == VK_SUCCESS)
                r = d->GetMemoryFdPropertiesKHR(d->h, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, buf_fd, &fp);
            if (r == VK_SUCCESS) {
                VkMemoryRequirements mr;
                d->GetImageMemoryRequirements(d->h, sc->b[i].img, &mr);
                uint32_t bits = mr.memoryTypeBits & fp.memoryTypeBits, mt = 0;
                while (mt < 32 && !(bits & (1u << mt))) mt++;
                VkMemoryDedicatedAllocateInfo ded = {VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO, NULL,
                                                     sc->b[i].img, VK_NULL_HANDLE};
                /* Vulkan owns the descriptor it imports: a duplicate / Vulkan이 가져온 디스크립터를 가짐: 복제본 */
                VkImportMemoryFdInfoKHR imp = {VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR, &ded,
                                               VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, dup(buf_fd)};
                VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, &imp, mr.size, mt};
                r = mt < 32 ? d->AllocateMemory(d->h, &mai, NULL, &sc->b[i].mem) : VK_ERROR_FORMAT_NOT_SUPPORTED;
                if (r != VK_SUCCESS && imp.fd >= 0) close(imp.fd);
                if (r == VK_SUCCESS) r = d->BindImageMemory(d->h, sc->b[i].img, sc->b[i].mem, 0);
            }
        }
        for (int j = 0; j < nf; j++) close(fds[j]);
        if (r != VK_SUCCESS) {
            LOG("버퍼 %u 가져오기 실패 (VkResult %d) — 기존 화면 경로", i, (int)r);
            sc->count = i + 1;
            goto fail;
        }
        /* Semaphore the present submit signals, exported as a sync fd for the app
         * present 제출이 신호하는 세마포어. 앱에 sync fd로 내보냄 */
        VkExportSemaphoreCreateInfo es = {VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO, NULL,
                                          VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT};
        VkSemaphoreCreateInfo sci = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO, &es, 0};
        if (d->CreateSemaphore(d->h, &sci, NULL, &sc->b[i].done) != VK_SUCCESS) {
            sc->count = i + 1;
            goto fail;
        }
        sc->count = i + 1;
    }
    tv.tv_sec = 0;
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    pthread_mutex_lock(&g_mu);
    int slot = -1;
    for (int i = 0; i < MAX_SWC; i++)
        if (!g_swc[i]) { slot = i; g_swc[i] = sc; break; }
    pthread_mutex_unlock(&g_mu);
    if (slot < 0) goto fail;
    LOG("직접 전달 swapchain %ux%u, 버퍼 %u개, 형식 %d, 창 0x%x", sc->w, sc->h, sc->count, (int)ci->imageFormat, m.r0);
    return sc;
fail:
    swc_free(sc);
    return NULL;
}

static VKAPI_ATTR VkResult VKAPI_CALL ezs_CreateSwapchainKHR(VkDevice h, const VkSwapchainCreateInfoKHR *ci,
                                                             const VkAllocationCallbacks *a, VkSwapchainKHR *out)
{
    struct dev *d = dev_of(h);
    struct swc *old = swc_of(ci->oldSwapchain);
    if (getenv("EZS_PRESENT_OFF") == NULL) {
        struct swc *sc = swc_create(d, ci);
        if (sc) {
            if (old) old->dead = 1;
            *out = (VkSwapchainKHR)(uintptr_t)sc;
            return VK_SUCCESS;
        }
    }
    /* Real swapchain; never hand our handle to the driver / 실제 swapchain. 우리 핸들은 드라이버에 넘기지 않음 */
    VkSwapchainCreateInfoKHR c = *ci;
    if (old) c.oldSwapchain = VK_NULL_HANDLE;
    return d->CreateSwapchainKHR(h, &c, a, out);
}

static VKAPI_ATTR void VKAPI_CALL ezs_DestroySwapchainKHR(VkDevice h, VkSwapchainKHR s, const VkAllocationCallbacks *a)
{
    struct dev *d = dev_of(h);
    struct swc *sc = swc_of(s);
    if (!sc) {
        d->DestroySwapchainKHR(h, s, a);
        return;
    }
    pthread_mutex_lock(&g_mu);
    for (int i = 0; i < MAX_SWC; i++)
        if (g_swc[i] == sc) g_swc[i] = NULL;
    pthread_mutex_unlock(&g_mu);
    /* Closing the socket tells the app to drop its buffers / 소켓을 닫으면 앱이 버퍼를 버림 */
    swc_free(sc);
}

static VKAPI_ATTR VkResult VKAPI_CALL ezs_GetSwapchainImagesKHR(VkDevice h, VkSwapchainKHR s, uint32_t *n, VkImage *out)
{
    struct dev *d = dev_of(h);
    struct swc *sc = swc_of(s);
    if (!sc) return d->GetSwapchainImagesKHR(h, s, n, out);
    if (!out) {
        *n = sc->count;
        return VK_SUCCESS;
    }
    uint32_t c = *n < sc->count ? *n : sc->count;
    for (uint32_t i = 0; i < c; i++) out[i] = sc->b[i].img;
    *n = c;
    return c < sc->count ? VK_INCOMPLETE : VK_SUCCESS;
}

/* Read RELEASE messages waiting on the socket; wait up to ms (-1 forever, 0 not at all)
 * 소켓에 온 RELEASE를 읽음. ms까지 기다림(-1 무한, 0 안 기다림) */
static int pump(struct swc *sc, int ms)
{
    struct pollfd p = {sc->sock, POLLIN, 0};
    int got = 0;
    for (;;) {
        int r = poll(&p, 1, got ? 0 : ms);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) return got;
        if (p.revents & (POLLHUP | POLLERR)) {
            sc->dead = 1;
            return got;
        }
        char pkt[256];
        int fds[EZP_MAX_FDS], nf = 0;
        ssize_t k = recv_pkt(sc->sock, pkt, sizeof pkt, fds, &nf);
        if (k < (ssize_t)sizeof(struct ezp_msg)) {
            for (int j = 0; j < nf; j++) close(fds[j]);
            sc->dead = 1;
            return got;
        }
        struct ezp_msg m;
        memcpy(&m, pkt, sizeof m);
        int fence = (m.flags & 1u) && nf > 0 ? fds[0] : -1;
        for (int j = (fence >= 0 ? 1 : 0); j < nf; j++) close(fds[j]);
        if (m.type == EZP_RELEASE && m.r0 < sc->count) {
            pthread_mutex_lock(&sc->mu);
            struct buf *b = &sc->b[m.r0];
            if (b->fence >= 0) close(b->fence);
            b->fence = fence;
            b->state = 0;
            pthread_mutex_unlock(&sc->mu);
            got++;
        } else if (fence >= 0) {
            close(fence);
        }
    }
}

static int64_t now_ms(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

/* Signal the game's semaphore / fence with the app's GL fence (or at once) by importing a sync fd;
 * no queue work, so no clash with the game's own queue use
 * 앱 GL 펜스(없으면 즉시)로 게임 세마포어·펜스를 신호함: sync fd를 가져오기로 넣음. 큐 작업이 없어 게임 자체
 * 큐 사용과 겹치지 않음 */
static VkResult signal_acquire(struct dev *d, int fence_fd, VkSemaphore sem, VkFence fence)
{
    VkResult r = VK_SUCCESS;
    if (sem) {
        VkImportSemaphoreFdInfoKHR ii = {VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR, NULL, sem,
                                         VK_SEMAPHORE_IMPORT_TEMPORARY_BIT, VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT,
                                         fence_fd >= 0 ? dup(fence_fd) : -1};
        r = d->ImportSemaphoreFdKHR(d->h, &ii);
        if (r != VK_SUCCESS && ii.fd >= 0) close(ii.fd);
    }
    if (r == VK_SUCCESS && fence) {
        if (!d->fence_fd) return VK_ERROR_DEVICE_LOST;
        VkImportFenceFdInfoKHR fi = {VK_STRUCTURE_TYPE_IMPORT_FENCE_FD_INFO_KHR, NULL, fence,
                                     VK_FENCE_IMPORT_TEMPORARY_BIT, VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT,
                                     fence_fd >= 0 ? dup(fence_fd) : -1};
        r = d->ImportFenceFdKHR(d->h, &fi);
        if (r != VK_SUCCESS && fi.fd >= 0) close(fi.fd);
    }
    return r;
}

static VkResult acquire(struct dev *d, struct swc *sc, uint64_t timeout, VkSemaphore sem, VkFence fence, uint32_t *idx)
{
    int64_t end = timeout == UINT64_MAX ? -1 : now_ms() + (int64_t)(timeout / 1000000);
    for (;;) {
        if (sc->dead) return VK_ERROR_OUT_OF_DATE_KHR;
        pthread_mutex_lock(&sc->mu);
        int pick = -1;
        for (uint32_t i = 0; i < sc->count; i++)
            if (sc->b[i].state == 0) { pick = (int)i; break; }
        int ff = -1;
        if (pick >= 0) {
            sc->b[pick].state = 1;
            ff = sc->b[pick].fence;
            sc->b[pick].fence = -1;
        }
        pthread_mutex_unlock(&sc->mu);
        if (pick >= 0) {
            VkResult r = signal_acquire(d, ff, sem, fence);
            if (ff >= 0) close(ff);
            *idx = (uint32_t)pick;
            return r;
        }
        int wait;
        if (end < 0) {
            wait = 1000;
        } else {
            int64_t left = end - now_ms();
            if (left <= 0) return timeout == 0 ? VK_NOT_READY : VK_TIMEOUT;
            wait = left > 1000 ? 1000 : (int)left;
        }
        pump(sc, wait);
    }
}

static VKAPI_ATTR VkResult VKAPI_CALL ezs_AcquireNextImageKHR(VkDevice h, VkSwapchainKHR s, uint64_t timeout,
                                                              VkSemaphore sem, VkFence fence, uint32_t *idx)
{
    struct dev *d = dev_of(h);
    struct swc *sc = swc_of(s);
    if (!sc) return d->AcquireNextImageKHR(h, s, timeout, sem, fence, idx);
    pump(sc, 0);
    return acquire(d, sc, timeout, sem, fence, idx);
}

static VKAPI_ATTR VkResult VKAPI_CALL ezs_AcquireNextImage2KHR(VkDevice h, const VkAcquireNextImageInfoKHR *ai,
                                                               uint32_t *idx)
{
    struct dev *d = dev_of(h);
    struct swc *sc = swc_of(ai->swapchain);
    if (!sc) return d->AcquireNextImage2KHR(h, ai, idx);
    pump(sc, 0);
    return acquire(d, sc, ai->timeout, ai->semaphore, ai->fence, idx);
}

/* Record the release barrier of every buffer for a queue family (once per family)
 * 큐 패밀리용으로 버퍼마다 넘김 장벽을 기록(패밀리당 한 번) */
static VkResult prepare_release(struct dev *d, struct swc *sc, uint32_t fam)
{
    if (sc->pool && sc->pool_fam == fam) return VK_SUCCESS;
    if (sc->pool) {
        d->DestroyCommandPool(d->h, sc->pool, NULL);
        sc->pool = VK_NULL_HANDLE;
    }
    VkCommandPoolCreateInfo pci = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, NULL, 0, fam};
    VkResult r = d->CreateCommandPool(d->h, &pci, NULL, &sc->pool);
    if (r != VK_SUCCESS) return r;
    sc->pool_fam = fam;
    for (uint32_t i = 0; i < sc->count; i++) {
        VkCommandBufferAllocateInfo cai = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, NULL, sc->pool,
                                           VK_COMMAND_BUFFER_LEVEL_PRIMARY, 1};
        r = d->AllocateCommandBuffers(d->h, &cai, &sc->b[i].rel);
        if (r != VK_SUCCESS) return r;
        d->set_data(d->h, sc->b[i].rel);
        VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, NULL, 0, NULL};
        d->BeginCommandBuffer(sc->b[i].rel, &bi);
        /* Hand the finished frame to the outside: writes made visible, ownership to the foreign
         * family / 끝난 프레임을 밖으로 넘김: 쓰기를 보이게 하고 소유권을 외부 패밀리로 */
        VkImageMemoryBarrier b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, NULL,
                                  VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                                      VK_ACCESS_TRANSFER_WRITE_BIT,
                                  0, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, fam,
                                  d->foreign ? VK_QUEUE_FAMILY_FOREIGN_EXT : VK_QUEUE_FAMILY_EXTERNAL,
                                  sc->b[i].img, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
        d->CmdPipelineBarrier(sc->b[i].rel, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                              0, 0, NULL, 0, NULL, 1, &b);
        r = d->EndCommandBuffer(sc->b[i].rel);
        if (r != VK_SUCCESS) return r;
    }
    return VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL ezs_QueuePresentKHR(VkQueue q, const VkPresentInfoKHR *pi)
{
    struct dev *d = dev_of(q);
    uint32_t ours = 0;
    for (uint32_t i = 0; i < pi->swapchainCount; i++)
        if (swc_of(pi->pSwapchains[i])) ours++;
    if (!ours) return d->QueuePresentKHR(q, pi);

    /* Present fences (VK_EXT/KHR_swapchain_maintenance1): signalled with our submit, so the game
     * knows when its wait semaphores may be reused / present 펜스(swapchain_maintenance1): 우리 제출로
     * 신호함. 게임이 대기 세마포어를 언제 다시 써도 되는지 알게 함 */
    const VkFence *pfences = NULL;
    for (const VkBaseInStructure *p = pi->pNext; p; p = p->pNext)
        if (p->sType == VK_STRUCTURE_TYPE_SWAPCHAIN_PRESENT_FENCE_INFO_EXT)
            pfences = ((const VkSwapchainPresentFenceInfoEXT *)p)->pFences;

    uint32_t fam = queue_family(d, q);
    VkPipelineStageFlags stages[32];
    uint32_t nw = pi->waitSemaphoreCount < 32 ? pi->waitSemaphoreCount : 32;
    for (uint32_t i = 0; i < nw; i++) stages[i] = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    int waited = 0;
    VkResult ret = VK_SUCCESS;
    for (uint32_t i = 0; i < pi->swapchainCount; i++) {
        struct swc *sc = swc_of(pi->pSwapchains[i]);
        if (!sc) continue;
        uint32_t idx = pi->pImageIndices[i];
        VkResult r = (sc->dead || idx >= sc->count) ? VK_ERROR_OUT_OF_DATE_KHR : prepare_release(d, sc, fam);
        if (r == VK_SUCCESS) {
            VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO, NULL, waited ? 0u : nw, pi->pWaitSemaphores, stages,
                               1, &sc->b[idx].rel, 1, &sc->b[idx].done};
            r = d->QueueSubmit(q, 1, &si, pfences ? pfences[i] : VK_NULL_HANDLE);
            waited = 1;
        }
        int fd = -1;
        if (r == VK_SUCCESS) {
            VkSemaphoreGetFdInfoKHR gi = {VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR, NULL, sc->b[idx].done,
                                          VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT};
            r = d->GetSemaphoreFdKHR(d->h, &gi, &fd);
        }
        if (r == VK_SUCCESS) {
            pthread_mutex_lock(&sc->mu);
            sc->b[idx].state = 2;
            pthread_mutex_unlock(&sc->mu);
            struct ezp_msg m;
            memset(&m, 0, sizeof m);
            m.type = EZP_PRESENT;
            m.r0 = idx;
            m.flags = fd >= 0 ? 1u : 0u;
            if (send_msg(sc->sock, &m, fd) != 0) {
                sc->dead = 1;
                r = VK_ERROR_OUT_OF_DATE_KHR;
            }
        } else if (r != VK_ERROR_OUT_OF_DATE_KHR) {
            LOG("present 실패 (VkResult %d)", (int)r);
            sc->dead = 1;
            r = VK_ERROR_OUT_OF_DATE_KHR;
        }
        if (fd >= 0) close(fd);
        if (pi->pResults) pi->pResults[i] = r;
        if (r != VK_SUCCESS) ret = r;
    }
    /* Real swapchains in the same call: their waits were taken by our submit (same queue, in order)
     * 같은 호출 안의 실제 swapchain: 대기는 우리 제출이 이미 받음(같은 큐, 순서대로) */
    if (ours < pi->swapchainCount) {
        VkSwapchainKHR rs[32];
        uint32_t ri[32];
        VkResult rr[32];
        uint32_t k = 0;
        for (uint32_t i = 0; i < pi->swapchainCount && k < 32; i++)
            if (!swc_of(pi->pSwapchains[i])) {
                rs[k] = pi->pSwapchains[i];
                ri[k++] = pi->pImageIndices[i];
            }
        VkPresentInfoKHR p2 = {VK_STRUCTURE_TYPE_PRESENT_INFO_KHR, NULL, 0, NULL, k, rs, ri, rr};
        VkResult r = d->QueuePresentKHR(q, &p2);
        if (r != VK_SUCCESS && ret == VK_SUCCESS) ret = r;
        if (pi->pResults)
            for (uint32_t i = 0, j = 0; i < pi->swapchainCount && j < k; i++)
                if (!swc_of(pi->pSwapchains[i])) pi->pResults[i] = rr[j++];
    }
    return ret;
}

static VKAPI_ATTR VkResult VKAPI_CALL ezs_GetSwapchainStatusKHR(VkDevice h, VkSwapchainKHR s)
{
    struct dev *d = dev_of(h);
    struct swc *sc = swc_of(s);
    if (!sc) return d->GetSwapchainStatusKHR ? d->GetSwapchainStatusKHR(h, s) : VK_SUCCESS;
    return sc->dead ? VK_ERROR_OUT_OF_DATE_KHR : VK_SUCCESS;
}

/* Present wait: frames are shown as soon as the app has them (no vsync wait for the game)
 * present 대기: 앱이 받는 즉시 보여 줌(게임이 수직 동기를 기다리지 않음) */
static VKAPI_ATTR VkResult VKAPI_CALL ezs_WaitForPresentKHR(VkDevice h, VkSwapchainKHR s, uint64_t id, uint64_t timeout)
{
    struct dev *d = dev_of(h);
    struct swc *sc = swc_of(s);
    if (!sc) return d->WaitForPresentKHR ? d->WaitForPresentKHR(h, s, id, timeout) : VK_SUCCESS;
    return sc->dead ? VK_ERROR_OUT_OF_DATE_KHR : VK_SUCCESS;
}

/* Acquired but never presented images go back to the free list / 받았지만 present하지 않은 이미지는 다시 비움 */
static VKAPI_ATTR VkResult VKAPI_CALL ezs_ReleaseSwapchainImagesEXT(VkDevice h, const VkReleaseSwapchainImagesInfoEXT *ri)
{
    struct dev *d = dev_of(h);
    struct swc *sc = swc_of(ri->swapchain);
    if (!sc) return d->ReleaseSwapchainImagesEXT ? d->ReleaseSwapchainImagesEXT(h, ri) : VK_SUCCESS;
    pthread_mutex_lock(&sc->mu);
    for (uint32_t i = 0; i < ri->imageIndexCount; i++)
        if (ri->pImageIndices[i] < sc->count && sc->b[ri->pImageIndices[i]].state == 1)
            sc->b[ri->pImageIndices[i]].state = 0;
    pthread_mutex_unlock(&sc->mu);
    return VK_SUCCESS;
}

/* ================= dispatch / 디스패치 ================= */

static PFN_vkVoidFunction device_hook(const char *n)
{
#define H(name) if (!strcmp(n, "vk" #name)) return (PFN_vkVoidFunction)ezs_##name
    H(GetDeviceQueue); H(GetDeviceQueue2); H(DestroyDevice);
    H(CreateSwapchainKHR); H(DestroySwapchainKHR); H(GetSwapchainImagesKHR); H(AcquireNextImageKHR);
    H(AcquireNextImage2KHR); H(QueuePresentKHR); H(GetSwapchainStatusKHR); H(WaitForPresentKHR);
    H(ReleaseSwapchainImagesEXT);
#undef H
    if (!strcmp(n, "vkReleaseSwapchainImagesKHR")) return (PFN_vkVoidFunction)ezs_ReleaseSwapchainImagesEXT;
    return NULL;
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL ezs_GetDeviceProcAddr(VkDevice h, const char *n)
{
    PFN_vkVoidFunction f = device_hook(n);
    if (!strcmp(n, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)ezs_GetDeviceProcAddr;
    struct dev *d = h ? dev_of(h) : NULL;
    if (!d) return NULL;
    PFN_vkVoidFunction next = d->gdpa(h, n);
    /* Hook only what the driver itself has, so feature checks by name still answer truthfully
     * 드라이버에 있는 것만 가로챔. 이름으로 기능을 확인하는 코드가 그대로 맞는 답을 받게 함 */
    return f && next ? f : next;
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL ezs_GetInstanceProcAddr(VkInstance h, const char *n)
{
#define H(name) if (!strcmp(n, "vk" #name)) return (PFN_vkVoidFunction)ezs_##name
    H(GetInstanceProcAddr); H(CreateInstance); H(DestroyInstance); H(CreateDevice); H(GetDeviceProcAddr);
#undef H
    struct inst *in = h ? inst_of(h) : NULL;
    if (!in) return NULL;
    PFN_vkVoidFunction next = in->gipa(h, n);
    if (!next) return NULL;
#define H(name) if (!strcmp(n, "vk" #name)) return (PFN_vkVoidFunction)ezs_##name
    H(CreateXlibSurfaceKHR); H(CreateXcbSurfaceKHR);
    H(GetPhysicalDeviceSurfaceFormatsKHR); H(GetPhysicalDeviceSurfaceFormats2KHR);
    H(GetPhysicalDeviceSurfaceCapabilitiesKHR); H(GetPhysicalDeviceSurfaceCapabilities2KHR);
#undef H
    PFN_vkVoidFunction f = device_hook(n);
    return f ? f : next;
}

EXPORT VKAPI_ATTR VkResult VKAPI_CALL ezs_NegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface *v)
{
    if (v->loaderLayerInterfaceVersion < 2) return VK_ERROR_INITIALIZATION_FAILED;
    v->loaderLayerInterfaceVersion = 2;
    v->pfnGetInstanceProcAddr = ezs_GetInstanceProcAddr;
    v->pfnGetDeviceProcAddr = ezs_GetDeviceProcAddr;
    v->pfnGetPhysicalDeviceProcAddr = NULL;
    return VK_SUCCESS;
}
