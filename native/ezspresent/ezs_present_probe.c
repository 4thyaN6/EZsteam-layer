/*
 * ezs-present-probe: checks, on the real device, every step the zero-copy game frame path needs,
 * before the present layer and the compositor are built on it. Planned path ("1-b"): the app
 * allocates AHardwareBuffers, the game's Vulkan (Turnip, KGSL) draws straight into them, the app
 * shows them with EGL; fences pass both ways. No CPU copy, no extra GPU copy.
 *   ezs-present-probe            (inside the rootfs; the app listens on EZP_GUEST_SOCKET)
 * Steps, each reported as one "INFO present :" line:
 *   1. device files (/dev/kgsl-3d0, /dev/dma_heap/system) and Vulkan device extensions
 *   2. what Turnip says it can export / import (dma-buf, DRM modifiers, sync fd)
 *   A. Vulkan exports a dma-buf image, app imports it with EGL_EXT_image_dma_buf_import
 *   B. app allocates an AHardwareBuffer, Vulkan imports it, reads the app's pixels, draws its
 *      own; app shows it through EGL (the planned path)
 *   E. app's GL fence is waited on by Vulkan
 * The app adds its own lines (EGL extensions, import results) and both go into one report.
 * Nothing leaves the device.
 *
 * ezs-present-probe: 0복사 게임 프레임 경로에 필요한 모든 단계를 실제 기기에서 확인함. present 레이어와
 * 컴포지터를 그 위에 만들기 전 단계임. 계획한 경로("1-b"): 앱이 AHardwareBuffer를 만들고, 게임의
 * Vulkan(Turnip, KGSL)이 그곳에 바로 그리며, 앱이 EGL로 보여 줌. 펜스는 양방향으로 오감. CPU 복사 없음,
 * 추가 GPU 복사 없음
 *   ezs-present-probe            (rootfs 안. 앱이 EZP_GUEST_SOCKET에서 기다림)
 * 단계마다 "INFO present :" 한 줄로 보고함:
 *   1. 장치 파일(/dev/kgsl-3d0, /dev/dma_heap/system)과 Vulkan 장치 확장
 *   2. Turnip이 내보내기/가져오기 가능하다고 답하는 것(dma-buf, DRM modifier, sync fd)
 *   A. Vulkan이 dma-buf 이미지를 내보내고 앱이 EGL_EXT_image_dma_buf_import로 가져옴
 *   B. 앱이 AHardwareBuffer를 만들고 Vulkan이 가져와 앱이 쓴 픽셀을 읽고 자기 것을 그림. 앱이 EGL로
 *      보여 줌(계획한 경로)
 *   E. 앱의 GL 펜스를 Vulkan이 기다림
 * 앱이 자기 줄(EGL 확장, 가져오기 결과)을 더해 하나의 보고로 합침. 기기 밖으로 나가는 데이터 없음
 */
#define _GNU_SOURCE
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

#include "ezs_present_proto.h"

#define P(...) do { printf("INFO present : " __VA_ARGS__); putchar('\n'); fflush(stdout); } while (0)

/* Size of the test images / 시험 이미지 크기 */
#define TW 256
#define TH 128

/* Patterns (R | G<<8 | B<<16 | A<<24) / 무늬 */
#define PAT_A  0xff563412u
#define PAT_B1 0xff1155aau
#define PAT_B2 0xff654321u

/* Result bits shared with the app (REPORT flags) / 앱과 공유하는 결과 비트(REPORT flags) */
#define R_A_EGL     0x01u /* app: A image shown through EGL dma-buf import / 앱: A 이미지 EGL 표시 */
#define R_B_EGL     0x02u /* app: B image (Vulkan drew) shown through EGL AHB / 앱: B 이미지 EGL 표시 */
#define R_VE_FENCE  0x04u /* app: Vulkan sync fd waited in EGL / 앱: Vulkan sync fd를 EGL에서 기다림 */
#define R_B_CPU     0x08u /* app: B image read back by CPU lock / 앱: B 이미지 CPU 읽기 일치 */

/* ---- Vulkan entry points / Vulkan 함수 ---- */
static PFN_vkGetInstanceProcAddr gipa;
#define IFN(n) static PFN_##n n
IFN(vkCreateInstance); IFN(vkEnumeratePhysicalDevices); IFN(vkGetPhysicalDeviceProperties);
IFN(vkEnumerateDeviceExtensionProperties); IFN(vkGetPhysicalDeviceQueueFamilyProperties);
IFN(vkGetPhysicalDeviceMemoryProperties); IFN(vkGetPhysicalDeviceImageFormatProperties2);
IFN(vkGetPhysicalDeviceFormatProperties2); IFN(vkGetPhysicalDeviceExternalSemaphoreProperties);
IFN(vkCreateDevice); IFN(vkGetDeviceProcAddr); IFN(vkDestroyInstance);
IFN(vkGetDeviceQueue); IFN(vkCreateCommandPool); IFN(vkAllocateCommandBuffers);
IFN(vkBeginCommandBuffer); IFN(vkEndCommandBuffer); IFN(vkResetCommandBuffer);
IFN(vkCmdPipelineBarrier); IFN(vkCmdClearColorImage); IFN(vkCmdCopyImageToBuffer);
IFN(vkQueueSubmit); IFN(vkQueueWaitIdle); IFN(vkCreateFence); IFN(vkWaitForFences); IFN(vkResetFences);
IFN(vkDestroyFence); IFN(vkCreateImage); IFN(vkDestroyImage); IFN(vkGetImageMemoryRequirements);
IFN(vkAllocateMemory); IFN(vkFreeMemory); IFN(vkBindImageMemory); IFN(vkGetImageSubresourceLayout);
IFN(vkCreateBuffer); IFN(vkDestroyBuffer); IFN(vkGetBufferMemoryRequirements); IFN(vkBindBufferMemory);
IFN(vkMapMemory); IFN(vkUnmapMemory); IFN(vkCreateSemaphore); IFN(vkDestroySemaphore);
IFN(vkDestroyDevice); IFN(vkDestroyCommandPool);
static PFN_vkGetMemoryFdKHR pGetMemoryFd;
static PFN_vkGetMemoryFdPropertiesKHR pGetMemoryFdProperties;
static PFN_vkGetSemaphoreFdKHR pGetSemaphoreFd;
static PFN_vkImportSemaphoreFdKHR pImportSemaphoreFd;
static PFN_vkGetImageDrmFormatModifierPropertiesEXT pGetImageDrmMod;

static VkInstance inst;
static VkPhysicalDevice phys;
static VkDevice dev;
static VkQueue queue;
static uint32_t qfam;
static VkCommandPool pool;
static VkCommandBuffer cmd;
static VkFence fence;
static VkPhysicalDeviceMemoryProperties memprops;

/* Which device extensions exist / 있는 장치 확장 */
static int has_mem_fd, has_dmabuf, has_drmmod, has_sem_fd, has_foreign, has_fmtlist, has_ahb;
/* Turnip answers / Turnip 답 */
static int can_export_dmabuf, can_import_dmabuf, can_export_syncfd, can_import_syncfd;
/* Use the DRM-modifier tiling (explicit row pitch on import) only when Turnip accepted it
 * Turnip이 받아들였을 때만 DRM modifier 배치(가져올 때 줄 간격 명시)를 씀 */
static int use_drm;
/* Memory planes of the Qualcomm compressed modifier for R8G8B8A8 (0 = not offered)
 * R8G8B8A8용 Qualcomm 압축 modifier의 메모리 평면 수(0 = 제공 안 함) */
static uint32_t ubwc_planes;

static const char *vkr(VkResult r)
{
    static char b[24];
    snprintf(b, sizeof b, "VkResult %d", (int)r);
    return b;
}

/* Check one device file: open read-write, report errno / 장치 파일 하나 확인: 읽기쓰기 열기, errno 보고 */
static void check_node(const char *path)
{
    int fd = open(path, O_RDWR | O_CLOEXEC);
    if (fd >= 0) {
        P("%s 열림", path);
        close(fd);
    } else {
        P("%s 열기 실패 (%s)", path, strerror(errno));
    }
}

static int find_mem(uint32_t bits, VkMemoryPropertyFlags want)
{
    for (uint32_t i = 0; i < memprops.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (memprops.memoryTypes[i].propertyFlags & want) == want) return (int)i;
    for (uint32_t i = 0; i < memprops.memoryTypeCount; i++)
        if (bits & (1u << i)) return (int)i;
    return -1;
}

/* Load instance-level and device-level functions / 인스턴스·장치 함수 불러오기 */
static int load_vulkan(void)
{
    void *lib = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);
    if (!lib) {
        P("Vulkan 로더 없음 (%s)", dlerror());
        return -1;
    }
    gipa = (PFN_vkGetInstanceProcAddr)dlsym(lib, "vkGetInstanceProcAddr");
    vkCreateInstance = (PFN_vkCreateInstance)gipa(NULL, "vkCreateInstance");
    return gipa && vkCreateInstance ? 0 : -1;
}

#define LI(n) n = (PFN_##n)gipa(inst, #n)
#define LD(n) n = (PFN_##n)vkGetDeviceProcAddr(dev, #n)

/* Step 1 + 2: instance, device facts, capability answers / 1+2단계: 인스턴스, 장치 사실, 가능 여부 답 */
static int open_device(void)
{
    VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO, NULL, "ezs-present-probe", 1, "ezs", 1,
                             VK_API_VERSION_1_2};
    VkInstanceCreateInfo ici = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, NULL, 0, &app, 0, NULL, 0, NULL};
    VkResult r = vkCreateInstance(&ici, NULL, &inst);
    if (r != VK_SUCCESS) {
        P("vkCreateInstance 실패 (%s)", vkr(r));
        return -1;
    }
    LI(vkEnumeratePhysicalDevices); LI(vkGetPhysicalDeviceProperties); LI(vkEnumerateDeviceExtensionProperties);
    LI(vkGetPhysicalDeviceQueueFamilyProperties); LI(vkGetPhysicalDeviceMemoryProperties);
    LI(vkGetPhysicalDeviceImageFormatProperties2); LI(vkGetPhysicalDeviceFormatProperties2);
    LI(vkGetPhysicalDeviceExternalSemaphoreProperties); LI(vkCreateDevice); LI(vkGetDeviceProcAddr);
    LI(vkDestroyInstance);
    uint32_t n = 1;
    r = vkEnumeratePhysicalDevices(inst, &n, &phys);
    if ((r != VK_SUCCESS && r != VK_INCOMPLETE) || n == 0) {
        P("Vulkan 장치 없음 (%s)", vkr(r));
        return -1;
    }
    VkPhysicalDeviceProperties pp;
    vkGetPhysicalDeviceProperties(phys, &pp);
    P("장치 %s, API %u.%u.%u", pp.deviceName, VK_API_VERSION_MAJOR(pp.apiVersion),
      VK_API_VERSION_MINOR(pp.apiVersion), VK_API_VERSION_PATCH(pp.apiVersion));
    vkGetPhysicalDeviceMemoryProperties(phys, &memprops);

    /* Device extensions this path may use / 이 경로가 쓸 수 있는 장치 확장 */
    uint32_t ne = 0;
    vkEnumerateDeviceExtensionProperties(phys, NULL, &ne, NULL);
    VkExtensionProperties *ext = calloc(ne ? ne : 1, sizeof *ext);
    vkEnumerateDeviceExtensionProperties(phys, NULL, &ne, ext);
    struct { const char *name; int *flag; } want[] = {
        {VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME, &has_mem_fd},
        {VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME, &has_dmabuf},
        {VK_EXT_IMAGE_DRM_FORMAT_MODIFIER_EXTENSION_NAME, &has_drmmod},
        {VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME, &has_sem_fd},
        {VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME, &has_foreign},
        {VK_KHR_IMAGE_FORMAT_LIST_EXTENSION_NAME, &has_fmtlist},
        {"VK_ANDROID_external_memory_android_hardware_buffer", &has_ahb},
    };
    char line[1024] = "";
    for (size_t w = 0; w < sizeof want / sizeof want[0]; w++) {
        for (uint32_t i = 0; i < ne; i++)
            if (!strcmp(ext[i].extensionName, want[w].name)) *want[w].flag = 1;
        size_t l = strlen(line);
        snprintf(line + l, sizeof line - l, " %s=%s", want[w].name + 3, *want[w].flag ? "있음" : "없음");
    }
    free(ext);
    P("장치 확장%s", line);

    /* Export / import answers for an R8G8B8A8 colour image as dma-buf (linear, and the DRM-modifier
     * tiling with the linear modifier) / R8G8B8A8 색 이미지의 dma-buf 내보내기·가져오기 답(linear, 그리고
     * linear modifier를 쓴 DRM modifier 배치) */
    VkImageUsageFlags usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                              VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    for (int t = 0; t < 2; t++) {
        if (t == 1 && !has_drmmod) break;
        VkPhysicalDeviceImageDrmFormatModifierInfoEXT modinfo = {
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_DRM_FORMAT_MODIFIER_INFO_EXT, NULL, EZP_MOD_LINEAR,
            VK_SHARING_MODE_EXCLUSIVE, 0, NULL};
        VkPhysicalDeviceExternalImageFormatInfo ext_info = {
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO, t ? &modinfo : NULL,
            VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
        VkPhysicalDeviceImageFormatInfo2 fi = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2, &ext_info,
                                               VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_TYPE_2D,
                                               t ? VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT : VK_IMAGE_TILING_LINEAR,
                                               usage, 0};
        VkExternalImageFormatProperties ext_props = {VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES, NULL, {0}};
        VkImageFormatProperties2 fp = {VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2, &ext_props, {{0, 0, 0}, 0, 0, 0, 0}};
        r = has_dmabuf ? vkGetPhysicalDeviceImageFormatProperties2(phys, &fi, &fp) : VK_ERROR_EXTENSION_NOT_PRESENT;
        VkExternalMemoryFeatureFlags f = ext_props.externalMemoryProperties.externalMemoryFeatures;
        int ex = r == VK_SUCCESS && (f & VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT);
        int im = r == VK_SUCCESS && (f & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT);
        int ded = r == VK_SUCCESS && (f & VK_EXTERNAL_MEMORY_FEATURE_DEDICATED_ONLY_BIT);
        P("dma-buf 이미지 (%s): %s, 내보내기 %s, 가져오기 %s%s", t ? "DRM modifier linear" : "linear",
          r == VK_SUCCESS ? "지원" : vkr(r), ex ? "가능" : "불가", im ? "가능" : "불가", ded ? ", 전용 할당만" : "");
        if (ex) can_export_dmabuf = 1;
        if (im) can_import_dmabuf = 1;
        if (t == 1 && (ex || im)) use_drm = 1;
    }
    /* DRM modifiers Turnip offers for R8G8B8A8 (linear 0x0, Qualcomm compressed 0x5000000000000001)
     * Turnip이 R8G8B8A8에 주는 DRM modifier(linear 0x0, Qualcomm 압축 0x5000000000000001) */
    if (has_drmmod) {
        VkDrmFormatModifierPropertiesEXT mods[16];
        VkDrmFormatModifierPropertiesListEXT ml = {VK_STRUCTURE_TYPE_DRM_FORMAT_MODIFIER_PROPERTIES_LIST_EXT, NULL,
                                                   16, mods};
        VkFormatProperties2 fp2 = {VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_2, &ml, {0}};
        vkGetPhysicalDeviceFormatProperties2(phys, VK_FORMAT_R8G8B8A8_UNORM, &fp2);
        char ml_s[512] = "";
        for (uint32_t i = 0; i < ml.drmFormatModifierCount && i < 16; i++) {
            size_t l = strlen(ml_s);
            snprintf(ml_s + l, sizeof ml_s - l, " 0x%llx(평면 %u)", (unsigned long long)mods[i].drmFormatModifier,
                     mods[i].drmFormatModifierPlaneCount);
            if (mods[i].drmFormatModifier == EZP_MOD_QCOM_COMPRESSED) ubwc_planes = mods[i].drmFormatModifierPlaneCount;
        }
        P("R8G8B8A8 DRM modifier %u개:%s", ml.drmFormatModifierCount, ml_s);
    }
    /* sync fd (Android fence) export / import answers / sync fd(안드로이드 펜스) 내보내기·가져오기 답 */
    VkPhysicalDeviceExternalSemaphoreInfo si = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO, NULL,
                                                VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT};
    VkExternalSemaphoreProperties sp = {VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES, NULL, 0, 0, 0};
    vkGetPhysicalDeviceExternalSemaphoreProperties(phys, &si, &sp);
    can_export_syncfd = has_sem_fd && (sp.externalSemaphoreFeatures & VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT);
    can_import_syncfd = has_sem_fd && (sp.externalSemaphoreFeatures & VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT);
    P("sync fd 세마포어: 내보내기 %s, 가져오기 %s", can_export_syncfd ? "가능" : "불가",
      can_import_syncfd ? "가능" : "불가");

    /* Device with one graphics queue and the extensions that exist / 그래픽 큐 하나와 있는 확장으로 장치 */
    uint32_t nq = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(phys, &nq, NULL);
    VkQueueFamilyProperties qp[16];
    if (nq > 16) nq = 16;
    vkGetPhysicalDeviceQueueFamilyProperties(phys, &nq, qp);
    qfam = 0;
    for (uint32_t i = 0; i < nq; i++)
        if (qp[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) { qfam = i; break; }
    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, NULL, 0, qfam, 1, &prio};
    const char *en[8];
    uint32_t nen = 0;
    if (has_mem_fd) en[nen++] = VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME;
    if (has_dmabuf) en[nen++] = VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME;
    if (has_drmmod) en[nen++] = VK_EXT_IMAGE_DRM_FORMAT_MODIFIER_EXTENSION_NAME;
    if (has_drmmod && has_fmtlist && VK_API_VERSION_MINOR(pp.apiVersion) < 2)
        en[nen++] = VK_KHR_IMAGE_FORMAT_LIST_EXTENSION_NAME;
    if (has_sem_fd) en[nen++] = VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME;
    if (has_foreign) en[nen++] = VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME;
    VkDeviceCreateInfo dci = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, NULL, 0, 1, &qci, 0, NULL, nen, en, NULL};
    r = vkCreateDevice(phys, &dci, NULL, &dev);
    if (r != VK_SUCCESS) {
        P("vkCreateDevice 실패 (%s)", vkr(r));
        return -1;
    }
    LD(vkGetDeviceQueue); LD(vkCreateCommandPool); LD(vkAllocateCommandBuffers); LD(vkBeginCommandBuffer);
    LD(vkEndCommandBuffer); LD(vkResetCommandBuffer); LD(vkCmdPipelineBarrier); LD(vkCmdClearColorImage);
    LD(vkCmdCopyImageToBuffer); LD(vkQueueSubmit); LD(vkQueueWaitIdle); LD(vkCreateFence); LD(vkWaitForFences);
    LD(vkResetFences); LD(vkDestroyFence); LD(vkCreateImage); LD(vkDestroyImage);
    LD(vkGetImageMemoryRequirements); LD(vkAllocateMemory); LD(vkFreeMemory); LD(vkBindImageMemory);
    LD(vkGetImageSubresourceLayout); LD(vkCreateBuffer); LD(vkDestroyBuffer); LD(vkGetBufferMemoryRequirements);
    LD(vkBindBufferMemory); LD(vkMapMemory); LD(vkUnmapMemory); LD(vkCreateSemaphore); LD(vkDestroySemaphore);
    LD(vkDestroyDevice); LD(vkDestroyCommandPool);
    pGetMemoryFd = (PFN_vkGetMemoryFdKHR)vkGetDeviceProcAddr(dev, "vkGetMemoryFdKHR");
    pGetMemoryFdProperties = (PFN_vkGetMemoryFdPropertiesKHR)vkGetDeviceProcAddr(dev, "vkGetMemoryFdPropertiesKHR");
    pGetSemaphoreFd = (PFN_vkGetSemaphoreFdKHR)vkGetDeviceProcAddr(dev, "vkGetSemaphoreFdKHR");
    pImportSemaphoreFd = (PFN_vkImportSemaphoreFdKHR)vkGetDeviceProcAddr(dev, "vkImportSemaphoreFdKHR");
    pGetImageDrmMod = (PFN_vkGetImageDrmFormatModifierPropertiesEXT)vkGetDeviceProcAddr(
        dev, "vkGetImageDrmFormatModifierPropertiesEXT");
    vkGetDeviceQueue(dev, qfam, 0, &queue);
    VkCommandPoolCreateInfo pci = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, NULL,
                                   VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, qfam};
    vkCreateCommandPool(dev, &pci, NULL, &pool);
    VkCommandBufferAllocateInfo cai = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, NULL, pool,
                                       VK_COMMAND_BUFFER_LEVEL_PRIMARY, 1};
    vkAllocateCommandBuffers(dev, &cai, &cmd);
    VkFenceCreateInfo fci = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, NULL, 0};
    vkCreateFence(dev, &fci, NULL, &fence);
    return 0;
}

/* Queue family that stands for "outside this device" (other process / API)
 * "이 장치 밖"(다른 프로세스·API)을 뜻하는 큐 패밀리 */
static uint32_t outside_family(void)
{
    return has_foreign ? VK_QUEUE_FAMILY_FOREIGN_EXT : VK_QUEUE_FAMILY_EXTERNAL;
}

static void barrier(VkImage img, VkImageLayout from, VkImageLayout to, uint32_t srcq, uint32_t dstq,
                    VkAccessFlags sa, VkAccessFlags da)
{
    VkImageMemoryBarrier b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, NULL, sa, da, from, to, srcq, dstq, img,
                              {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, NULL,
                         0, NULL, 1, &b);
}

static VkClearColorValue color_of(uint32_t pat)
{
    VkClearColorValue c;
    for (int i = 0; i < 4; i++) c.float32[i] = (float)((pat >> (8 * i)) & 0xff) / 255.0f;
    return c;
}

/* Submit the recorded command buffer; with sem != 0 also signal it. Waits for the fence only when
 * wait is set / 기록한 명령을 제출함. sem이 있으면 그것도 신호함. wait일 때만 펜스를 기다림 */
static VkResult submit(VkSemaphore wait_sem, VkSemaphore sig_sem, int wait)
{
    VkPipelineStageFlags st = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    VkSubmitInfo s = {VK_STRUCTURE_TYPE_SUBMIT_INFO, NULL, wait_sem ? 1u : 0u, &wait_sem, &st,
                      cmd ? 1u : 0u, &cmd, sig_sem ? 1u : 0u, &sig_sem};
    vkResetFences(dev, 1, &fence);
    VkResult r = vkQueueSubmit(queue, 1, &s, fence);
    if (r == VK_SUCCESS && wait) r = vkWaitForFences(dev, 1, &fence, VK_TRUE, 3000000000ull);
    return r;
}

/* Exportable sync-fd semaphore, or 0 / 내보낼 수 있는 sync fd 세마포어, 없으면 0 */
static VkSemaphore export_sem(void)
{
    if (!can_export_syncfd || !pGetSemaphoreFd) return VK_NULL_HANDLE;
    VkExportSemaphoreCreateInfo e = {VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO, NULL,
                                     VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT};
    VkSemaphoreCreateInfo c = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO, &e, 0};
    VkSemaphore s = VK_NULL_HANDLE;
    return vkCreateSemaphore(dev, &c, NULL, &s) == VK_SUCCESS ? s : VK_NULL_HANDLE;
}

static int sem_fd(VkSemaphore s)
{
    if (!s) return -1;
    int fd = -1;
    VkSemaphoreGetFdInfoKHR gi = {VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR, NULL, s,
                                  VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT};
    VkResult r = pGetSemaphoreFd(dev, &gi, &fd);
    if (r != VK_SUCCESS) {
        P("sync fd 꺼내기 실패 (%s)", vkr(r));
        return -1;
    }
    return fd;
}

/* Copy the image to a host buffer and compare three pixels with pat (±1 per channel)
 * 이미지를 호스트 버퍼로 복사하고 픽셀 세 개를 pat과 비교함(채널당 ±1) */
static int readback_matches(VkImage img, VkImageLayout from, uint32_t srcq, uint32_t pat, uint32_t *got)
{
    VkBufferCreateInfo bci = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, NULL, 0, (VkDeviceSize)TW * TH * 4,
                              VK_BUFFER_USAGE_TRANSFER_DST_BIT, VK_SHARING_MODE_EXCLUSIVE, 0, NULL};
    VkBuffer buf;
    if (vkCreateBuffer(dev, &bci, NULL, &buf) != VK_SUCCESS) return 0;
    VkMemoryRequirements mr;
    vkGetBufferMemoryRequirements(dev, buf, &mr);
    int mt = find_mem(mr.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, NULL, mr.size, (uint32_t)mt};
    VkDeviceMemory mem;
    if (mt < 0 || vkAllocateMemory(dev, &mai, NULL, &mem) != VK_SUCCESS) {
        vkDestroyBuffer(dev, buf, NULL);
        return 0;
    }
    vkBindBufferMemory(dev, buf, mem, 0);
    VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, NULL,
                                   VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, NULL};
    vkResetCommandBuffer(cmd, 0);
    vkBeginCommandBuffer(cmd, &bi);
    /* Acquire from outside (or plain layout change) / 밖에서 넘겨받음(또는 배치만 바꿈) */
    barrier(img, from, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, srcq, srcq == VK_QUEUE_FAMILY_IGNORED ? srcq : qfam,
            0, VK_ACCESS_TRANSFER_READ_BIT);
    VkBufferImageCopy c = {0, 0, 0, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {0, 0, 0}, {TW, TH, 1}};
    vkCmdCopyImageToBuffer(cmd, img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buf, 1, &c);
    vkEndCommandBuffer(cmd);
    VkResult r = submit(VK_NULL_HANDLE, VK_NULL_HANDLE, 1);
    int ok = 0;
    void *p = NULL;
    if (r == VK_SUCCESS && vkMapMemory(dev, mem, 0, VK_WHOLE_SIZE, 0, &p) == VK_SUCCESS) {
        const uint32_t *px = p;
        uint32_t at[3] = {px[0], px[(TH / 2) * TW + TW / 2], px[TH * TW - 1]};
        ok = 1;
        for (int i = 0; i < 3; i++)
            for (int ch = 0; ch < 4; ch++) {
                int a = (at[i] >> (8 * ch)) & 0xff, b = (pat >> (8 * ch)) & 0xff;
                if (a - b > 1 || b - a > 1) ok = 0;
            }
        if (got) *got = at[1];
        vkUnmapMemory(dev, mem);
    }
    vkDestroyBuffer(dev, buf, NULL);
    vkFreeMemory(dev, mem, NULL);
    return ok;
}

/* ---- socket / 소켓 ---- */
static int send_msg(int s, struct ezp_msg *m, const int *fds, int nfds, const void *extra, size_t extra_len)
{
    m->magic = EZP_MAGIC;
    struct iovec iov[2] = {{m, sizeof *m}, {(void *)extra, extra_len}};
    char cbuf[CMSG_SPACE(sizeof(int) * EZP_MAX_FDS)];
    struct msghdr h = {0};
    h.msg_iov = iov;
    h.msg_iovlen = extra_len ? 2 : 1;
    int n = 0;
    for (int i = 0; i < nfds; i++)
        if (fds[i] >= 0) n++;
    if (n) {
        memset(cbuf, 0, sizeof cbuf);
        h.msg_control = cbuf;
        h.msg_controllen = CMSG_SPACE(sizeof(int) * n);
        struct cmsghdr *c = CMSG_FIRSTHDR(&h);
        c->cmsg_level = SOL_SOCKET;
        c->cmsg_type = SCM_RIGHTS;
        c->cmsg_len = CMSG_LEN(sizeof(int) * n);
        int *d = (int *)CMSG_DATA(c), k = 0;
        for (int i = 0; i < nfds; i++)
            if (fds[i] >= 0) d[k++] = fds[i];
    }
    return sendmsg(s, &h, MSG_NOSIGNAL) < 0 ? -1 : 0;
}

/* Receive one packet; descriptors into fds (count in *nfds) / 패킷 하나 받기, 디스크립터는 fds로 */
static ssize_t recv_pkt(int s, void *buf, size_t cap, int *fds, int *nfds)
{
    char cbuf[CMSG_SPACE(sizeof(int) * EZP_MAX_FDS)];
    struct iovec iov = {buf, cap};
    struct msghdr h = {0};
    h.msg_iov = &iov;
    h.msg_iovlen = 1;
    h.msg_control = cbuf;
    h.msg_controllen = sizeof cbuf;
    ssize_t n = recvmsg(s, &h, MSG_CMSG_CLOEXEC);
    *nfds = 0;
    if (n < 0) return -1;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&h); c; c = CMSG_NXTHDR(&h, c))
        if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS) {
            int k = (int)((c->cmsg_len - CMSG_LEN(0)) / sizeof(int));
            for (int i = 0; i < k && *nfds < EZP_MAX_FDS; i++) fds[(*nfds)++] = ((int *)CMSG_DATA(c))[i];
        }
    if (h.msg_flags & MSG_CTRUNC) P("소켓: 디스크립터 일부 잘림");
    return n;
}

static ssize_t recv_msg(int s, struct ezp_msg *m, uint32_t want, int *fds, int *nfds, char *extra, size_t cap)
{
    static char pkt[EZP_MAX_PACKET];
    ssize_t n = recv_pkt(s, pkt, sizeof pkt, fds, nfds);
    if (n < (ssize_t)sizeof *m) return -1;
    memcpy(m, pkt, sizeof *m);
    if (m->magic != EZP_MAGIC || m->type != want) return -1;
    size_t el = (size_t)n - sizeof *m;
    if (extra && cap) {
        if (el >= cap) el = cap - 1;
        memcpy(extra, pkt + sizeof *m, el);
        extra[el] = 0;
    }
    return n;
}

static int connect_app(void)
{
    const char *path = getenv("EZS_PRESENT_SOCK");
    if (!path || !*path) path = EZP_GUEST_SOCKET;
    int s = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (s < 0) return -1;
    struct sockaddr_un a = {0};
    a.sun_family = AF_UNIX;
    snprintf(a.sun_path, sizeof a.sun_path, "%s", path);
    if (connect(s, (struct sockaddr *)&a, sizeof a) != 0) {
        P("앱 연결 실패 %s (%s) — 앱 쪽 시험은 건너뜀", path, strerror(errno));
        close(s);
        return -1;
    }
    /* Never hang Steam's start: 8 s per reply / Steam 시작을 막지 않음: 답마다 8초 */
    struct timeval tv = {8, 0};
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    return s;
}

/* Colour image for dma-buf sharing. Export: linear layout chosen by Turnip. Import: the given row
 * pitch (explicit DRM modifier layout when available, else plain linear and compare the pitch).
 * dma-buf 공유용 색 이미지. 내보내기: Turnip이 정한 linear 배치. 가져오기: 주어진 줄 간격(DRM modifier
 * 명시 배치가 있으면 그것, 없으면 일반 linear 후 간격 비교) */
static VkImage make_image_mod(int import, uint32_t pitch, uint64_t modifier)
{
    VkSubresourceLayout pl = {0, 0, pitch, 0, 0};
    uint64_t lin = EZP_MOD_LINEAR;
    VkImageDrmFormatModifierExplicitCreateInfoEXT exp = {
        VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_EXPLICIT_CREATE_INFO_EXT, NULL, modifier, 1, &pl};
    VkImageDrmFormatModifierListCreateInfoEXT lst = {
        VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_LIST_CREATE_INFO_EXT, NULL, 1, &lin};
    VkExternalMemoryImageCreateInfo emi = {VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO, NULL,
                                           VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
    if (use_drm) emi.pNext = import ? (void *)&exp : (void *)&lst;
    VkImageCreateInfo ci = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, &emi, 0, VK_IMAGE_TYPE_2D, VK_FORMAT_R8G8B8A8_UNORM,
                            {TW, TH, 1}, 1, 1, VK_SAMPLE_COUNT_1_BIT,
                            use_drm ? VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT : VK_IMAGE_TILING_LINEAR,
                            VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                            VK_SHARING_MODE_EXCLUSIVE, 0, NULL, VK_IMAGE_LAYOUT_UNDEFINED};
    VkImage img = VK_NULL_HANDLE;
    VkResult r = vkCreateImage(dev, &ci, NULL, &img);
    if (r != VK_SUCCESS) {
        P("%s 이미지 만들기 실패 (%s)", import ? "가져오기" : "내보내기", vkr(r));
        return VK_NULL_HANDLE;
    }
    return img;
}

static VkImage make_image(int import, uint32_t pitch)
{
    return make_image_mod(import, pitch, EZP_MOD_LINEAR);
}

static void image_layout(VkImage img, uint32_t *pitch, uint32_t *offset, uint64_t *mod)
{
    VkImageSubresource sub = {use_drm ? VK_IMAGE_ASPECT_MEMORY_PLANE_0_BIT_EXT : VK_IMAGE_ASPECT_COLOR_BIT, 0, 0};
    VkSubresourceLayout l;
    vkGetImageSubresourceLayout(dev, img, &sub, &l);
    *pitch = (uint32_t)l.rowPitch;
    *offset = (uint32_t)l.offset;
    *mod = EZP_MOD_LINEAR;
    if (use_drm && pGetImageDrmMod) {
        VkImageDrmFormatModifierPropertiesEXT mp = {VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_PROPERTIES_EXT, NULL, 0};
        if (pGetImageDrmMod(dev, img, &mp) == VK_SUCCESS) *mod = mp.drmFormatModifier;
    }
}

/* A: export a Vulkan-drawn dma-buf to the app / A: Vulkan이 그린 dma-buf를 앱으로 내보냄 */
static void test_a(int s)
{
    if (!can_export_dmabuf || !pGetMemoryFd) {
        P("A (Vulkan 내보내기): 건너뜀 — 내보내기 불가");
        return;
    }
    VkImage img = make_image(0, 0);
    if (!img) return;
    VkMemoryRequirements mr;
    vkGetImageMemoryRequirements(dev, img, &mr);
    VkMemoryDedicatedAllocateInfo ded = {VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO, NULL, img, VK_NULL_HANDLE};
    VkExportMemoryAllocateInfo exp = {VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO, &ded,
                                      VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
    int mt = find_mem(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, &exp, mr.size, (uint32_t)mt};
    VkDeviceMemory mem = VK_NULL_HANDLE;
    VkResult r = vkAllocateMemory(dev, &mai, NULL, &mem);
    if (r != VK_SUCCESS) {
        P("A: 내보낼 메모리 할당 실패 (%s)", vkr(r));
        vkDestroyImage(dev, img, NULL);
        return;
    }
    vkBindImageMemory(dev, img, mem, 0);
    int fd = -1;
    VkMemoryGetFdInfoKHR gi = {VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR, NULL, mem,
                               VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
    r = pGetMemoryFd(dev, &gi, &fd);
    uint32_t pitch, offset;
    uint64_t mod;
    image_layout(img, &pitch, &offset, &mod);
    off_t size = fd >= 0 ? lseek(fd, 0, SEEK_END) : -1;
    P("A: dma-buf 꺼내기 %s, 크기 %lld, 줄 간격 %u, 시작 %u, modifier 0x%llx", r == VK_SUCCESS ? "성공" : vkr(r),
      (long long)size, pitch, offset, (unsigned long long)mod);
    if (r == VK_SUCCESS) {
        /* Clear to PAT_A, release to outside, signal an exportable semaphore (not waited here: the
         * app's wait on the sync fd is part of the test) / PAT_A로 지우고 밖으로 넘기고 내보낼 세마포어를
         * 신호함(여기서는 기다리지 않음: 앱이 sync fd를 기다리는 것이 시험의 일부) */
        VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, NULL,
                                       VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, NULL};
        vkResetCommandBuffer(cmd, 0);
        vkBeginCommandBuffer(cmd, &bi);
        barrier(img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_QUEUE_FAMILY_IGNORED,
                VK_QUEUE_FAMILY_IGNORED, 0, VK_ACCESS_TRANSFER_WRITE_BIT);
        VkClearColorValue c = color_of(PAT_A);
        VkImageSubresourceRange rg = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdClearColorImage(cmd, img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &c, 1, &rg);
        barrier(img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, qfam, outside_family(),
                VK_ACCESS_TRANSFER_WRITE_BIT, 0);
        vkEndCommandBuffer(cmd);
        VkSemaphore sem = export_sem();
        r = submit(VK_NULL_HANDLE, sem, 0);
        int sfd = r == VK_SUCCESS ? sem_fd(sem) : -1;
        if (s >= 0) {
            struct ezp_msg m = {0};
            m.type = EZP_A_BUF;
            m.w = TW; m.h = TH; m.stride = pitch; m.offset = offset;
            m.fourcc = EZP_FOURCC_ABGR8888; m.modifier = mod; m.pattern = PAT_A;
            m.flags = sfd >= 0 ? 1u : 0u;
            int fds[2] = {fd, sfd};
            if (send_msg(s, &m, fds, 2, NULL, 0) != 0) P("A: 앱으로 보내기 실패 (%s)", strerror(errno));
            else P("A: 앱으로 보냄 (sync fd %s)", sfd >= 0 ? "있음" : "없음");
        }
        vkWaitForFences(dev, 1, &fence, VK_TRUE, 3000000000ull);
        /* Vulkan itself reads its own export back (sanity) / Vulkan이 자기 내보낸 것을 다시 읽음(점검) */
        uint32_t got = 0;
        int ok = readback_matches(img, VK_IMAGE_LAYOUT_GENERAL, outside_family(), PAT_A, &got);
        P("A: Vulkan 자체 읽기 %s (0x%08x)", ok ? "일치" : "불일치", got);
        if (sfd >= 0) close(sfd);
        if (sem) vkDestroySemaphore(dev, sem, NULL);
    }
    /* The app holds its own reference to the dma-buf; ours can go after it answers (REPORT)
     * 앱은 dma-buf를 따로 잡고 있음. 우리 쪽은 앱이 답한 뒤(REPORT) 놓아도 됨 */
    if (fd >= 0) close(fd);
    vkQueueWaitIdle(queue);
    vkDestroyImage(dev, img, NULL);
    vkFreeMemory(dev, mem, NULL);
}

/* B: import the app's AHardwareBuffer, read, draw (the planned path) / B: 앱 AHB 가져와 읽고 그림(계획 경로) */
static int test_b(int s, int *b_import_ok, int *b_read_ok)
{
    *b_import_ok = *b_read_ok = 0;
    if (s < 0) return 0;
    struct ezp_msg m = {0};
    m.type = EZP_B_REQ;
    m.w = TW; m.h = TH; m.fourcc = EZP_FOURCC_ABGR8888; m.pattern = PAT_B1;
    if (send_msg(s, &m, NULL, 0, NULL, 0) != 0) return 0;
    /* From here every way out sends B_DONE, so the app never waits for nothing
     * 여기부터는 어느 길로 나가도 B_DONE을 보내 앱이 헛되이 기다리지 않음 */
    int fds[EZP_MAX_FDS], nfds = 0;
    int result = 0;
    struct ezp_msg h;
    if (recv_msg(s, &h, EZP_B_BUF, fds, &nfds, NULL, 0) < 0) {
        P("B: 앱 AHB 머리 받기 실패 (%s)", strerror(errno));
        goto out;
    }
    if (h.flags == 0) {
        P("B: 앱이 AHB를 만들지 못함 — 앱 줄 참고");
        goto out;
    }
    for (int i = 0; i < nfds; i++) close(fds[i]);
    nfds = 0;
    /* The app's next packet is AHardwareBuffer_sendHandleToUnixSocket's (public NDK call): its
     * descriptors are the buffer's; the dma-buf is the one big enough for the image
     * 앱의 다음 패킷은 AHardwareBuffer_sendHandleToUnixSocket(공개 NDK 호출)의 것: 디스크립터가 버퍼의 것임.
     * dma-buf는 이미지를 담을 만큼 큰 것 */
    static char pkt[EZP_MAX_PACKET];
    ssize_t n = recv_pkt(s, pkt, sizeof pkt, fds, &nfds);
    if (n < 0) {
        P("B: AHB 핸들 받기 실패 (%s)", strerror(errno));
        goto out;
    }
    int buf_fd = -1;
    char sizes[256] = "";
    for (int i = 0; i < nfds; i++) {
        off_t sz = lseek(fds[i], 0, SEEK_END);
        size_t l = strlen(sizes);
        snprintf(sizes + l, sizeof sizes - l, " %lld", (long long)sz);
        if (buf_fd < 0 && sz >= (off_t)h.stride * TH) buf_fd = fds[i];
    }
    P("B: AHB 핸들 %zd바이트, 디스크립터 %d개 (크기%s), 줄 간격 %u", n, nfds, sizes, h.stride);
    if (buf_fd < 0 || !can_import_dmabuf || !pGetMemoryFdProperties) {
        P("B: 가져오기 건너뜀 — %s", buf_fd < 0 ? "버퍼 디스크립터 없음" : "가져오기 불가");
        goto out;
    }
    VkMemoryFdPropertiesKHR fp = {VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR, NULL, 0};
    VkResult r = pGetMemoryFdProperties(dev, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, buf_fd, &fp);
    if (r != VK_SUCCESS || !fp.memoryTypeBits) {
        P("B: dma-buf 메모리 형식 조회 실패 (%s, bits 0x%x)", vkr(r), fp.memoryTypeBits);
        goto out;
    }
    VkImage img = make_image(1, h.stride);
    if (!img) goto out;
    uint32_t pitch, offset;
    uint64_t mod;
    image_layout(img, &pitch, &offset, &mod);
    if (pitch != h.stride) P("B: 줄 간격 다름 (Vulkan %u, AHB %u) — 그림이 어긋날 수 있음", pitch, h.stride);
    VkMemoryRequirements mr;
    vkGetImageMemoryRequirements(dev, img, &mr);
    VkMemoryDedicatedAllocateInfo ded = {VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO, NULL, img, VK_NULL_HANDLE};
    /* Vulkan owns the descriptor it imports: give it a duplicate / Vulkan이 가져온 디스크립터를 가짐: 복제본을 줌 */
    VkImportMemoryFdInfoKHR imp = {VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR, &ded,
                                   VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, dup(buf_fd)};
    int mt = find_mem(mr.memoryTypeBits & fp.memoryTypeBits, 0);
    VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, &imp, mr.size, (uint32_t)mt};
    VkDeviceMemory mem = VK_NULL_HANDLE;
    r = mt < 0 ? VK_ERROR_FORMAT_NOT_SUPPORTED : vkAllocateMemory(dev, &mai, NULL, &mem);
    if (r != VK_SUCCESS) {
        P("B: AHB 가져오기 실패 (%s)", vkr(r));
        if (imp.fd >= 0) close(imp.fd);
        vkDestroyImage(dev, img, NULL);
        goto out;
    }
    r = vkBindImageMemory(dev, img, mem, 0);
    *b_import_ok = r == VK_SUCCESS;
    P("B: AHB 가져오기 %s (modifier 0x%llx)", r == VK_SUCCESS ? "성공" : vkr(r), (unsigned long long)mod);
    if (r == VK_SUCCESS) {
        /* Read what the app's CPU wrote (PAT_B1) / 앱 CPU가 쓴 것(PAT_B1) 읽기 */
        uint32_t got = 0;
        *b_read_ok = readback_matches(img, VK_IMAGE_LAYOUT_GENERAL, outside_family(), PAT_B1, &got);
        P("B: 앱이 쓴 픽셀 Vulkan 읽기 %s (0x%08x, 기대 0x%08x)", *b_read_ok ? "일치" : "불일치", got, PAT_B1);
        /* Draw PAT_B2 as a game would, hand back to outside with a sync fd
         * 게임처럼 PAT_B2를 그리고 sync fd와 함께 밖으로 돌려줌 */
        VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, NULL,
                                       VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, NULL};
        vkResetCommandBuffer(cmd, 0);
        vkBeginCommandBuffer(cmd, &bi);
        barrier(img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, VK_ACCESS_TRANSFER_READ_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT);
        VkClearColorValue c = color_of(PAT_B2);
        VkImageSubresourceRange rg = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdClearColorImage(cmd, img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &c, 1, &rg);
        barrier(img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, qfam, outside_family(),
                VK_ACCESS_TRANSFER_WRITE_BIT, 0);
        vkEndCommandBuffer(cmd);
        VkSemaphore sem = export_sem();
        r = submit(VK_NULL_HANDLE, sem, 0);
        int sfd = r == VK_SUCCESS ? sem_fd(sem) : -1;
        struct ezp_msg d = {0};
        d.type = EZP_B_DONE;
        d.w = TW; d.h = TH; d.pattern = PAT_B2; d.flags = sfd >= 0 ? 1u : 0u;
        int sf[1] = {sfd};
        send_msg(s, &d, sf, 1, NULL, 0);
        P("B: Vulkan이 AHB에 그림 %s, 앱으로 알림 (sync fd %s)", r == VK_SUCCESS ? "제출" : vkr(r),
          sfd >= 0 ? "있음" : "없음");
        vkWaitForFences(dev, 1, &fence, VK_TRUE, 3000000000ull);
        if (sfd >= 0) close(sfd);
        if (sem) vkDestroySemaphore(dev, sem, NULL);
        result = 1;
    }
    vkQueueWaitIdle(queue);
    vkDestroyImage(dev, img, NULL);
    vkFreeMemory(dev, mem, NULL);
out:
    for (int i = 0; i < nfds; i++) close(fds[i]);
    if (!result) {
        /* Tell the app B ended without drawing / 그리지 못하고 B가 끝났음을 앱에 알림 */
        struct ezp_msg d = {0};
        d.type = EZP_B_DONE;
        d.r0 = 1;
        send_msg(s, &d, NULL, 0, NULL, 0);
    }
    return result;
}

/* E: wait in Vulkan on the app's GL fence / E: 앱 GL 펜스를 Vulkan에서 기다림 */
static int test_e(int s)
{
    if (s < 0) return 0;
    int fds[EZP_MAX_FDS], nfds = 0;
    struct ezp_msg m;
    if (recv_msg(s, &m, EZP_E_FENCE, fds, &nfds, NULL, 0) < 0) {
        P("E: 앱 펜스 받기 실패 (%s)", strerror(errno));
        return 0;
    }
    int ok = 0;
    if (nfds < 1) {
        P("E: 앱이 펜스를 만들지 못함 — 앱 줄 참고");
    } else if (!can_import_syncfd || !pImportSemaphoreFd) {
        P("E: 건너뜀 — sync fd 가져오기 불가");
        close(fds[0]);
    } else {
        VkSemaphoreCreateInfo c = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO, NULL, 0};
        VkSemaphore sem;
        vkCreateSemaphore(dev, &c, NULL, &sem);
        VkImportSemaphoreFdInfoKHR ii = {VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR, NULL, sem,
                                         VK_SEMAPHORE_IMPORT_TEMPORARY_BIT, VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT,
                                         fds[0]};
        VkResult r = pImportSemaphoreFd(dev, &ii);
        if (r != VK_SUCCESS) {
            P("E: 펜스 가져오기 실패 (%s)", vkr(r));
            close(fds[0]);
        } else {
            VkCommandBuffer keep = cmd;
            cmd = VK_NULL_HANDLE; /* empty submit that only waits / 기다리기만 하는 빈 제출 */
            r = submit(sem, VK_NULL_HANDLE, 1);
            cmd = keep;
            ok = r == VK_SUCCESS;
            P("E: 앱 GL 펜스를 Vulkan이 기다림 %s", ok ? "성공" : vkr(r));
        }
        vkDestroySemaphore(dev, sem, NULL);
    }
    struct ezp_msg d = {0};
    d.type = EZP_E_FENCE_DONE;
    d.flags = (uint32_t)ok;
    send_msg(s, &d, NULL, 0, NULL, 0);
    return ok;
}

/*
 * C: layout of a GPU-only AHardwareBuffer (no CPU usage bits, so gralloc may compress it). The app
 * clears it with GL; Vulkan imports it once as linear and once as Qualcomm compressed and reads it
 * back. The layout that reads right is what the compositor may use with its compression test
 * switch (less memory traffic per frame).
 * C: GPU 전용 AHardwareBuffer 배치(CPU 용도 비트가 없어 gralloc이 압축할 수 있음). 앱이 GL로 칠하고,
 * Vulkan이 linear로 한 번, Qualcomm 압축으로 한 번 가져와 다시 읽음. 맞게 읽히는 배치를 컴포지터가 압축 시험
 * 스위치에서 씀(프레임당 메모리 이동 감소)
 */
#define PAT_C 0xff197f2au
static void test_c(int s)
{
    if (s < 0) return;
    struct ezp_msg m = {0};
    m.type = EZP_C_REQ;
    m.w = TW; m.h = TH; m.pattern = PAT_C;
    if (send_msg(s, &m, NULL, 0, NULL, 0) != 0) return;
    int fds[EZP_MAX_FDS], nfds = 0;
    uint32_t ok = 0;
    uint64_t pick = EZP_MOD_INVALID;
    struct ezp_msg h;
    if (recv_msg(s, &h, EZP_C_BUF, fds, &nfds, NULL, 0) < 0 || h.flags == 0) {
        P("C: 앱이 GPU 전용 AHB를 만들지 못함 — 앱 줄 참고");
        goto out;
    }
    for (int i = 0; i < nfds; i++) close(fds[i]);
    static char pkt[EZP_MAX_PACKET];
    ssize_t n = recv_pkt(s, pkt, sizeof pkt, fds, &nfds);
    int buf_fd = -1;
    for (int i = 0; n >= 0 && i < nfds; i++)
        if (buf_fd < 0 && lseek(fds[i], 0, SEEK_END) >= (off_t)h.stride * TH) buf_fd = fds[i];
    if (buf_fd < 0 || !pGetMemoryFdProperties || !use_drm) {
        P("C: 건너뜀 — %s", buf_fd < 0 ? "버퍼 디스크립터 없음" : "DRM modifier 가져오기 불가");
        goto out;
    }
    uint64_t cand[2] = {EZP_MOD_LINEAR, EZP_MOD_QCOM_COMPRESSED};
    for (int k = 0; k < 2; k++) {
        if (k == 1 && ubwc_planes != 1) {
            P("C: Qualcomm 압축 modifier 시험 안 함 (평면 %u)", ubwc_planes);
            continue;
        }
        VkMemoryFdPropertiesKHR fp = {VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR, NULL, 0};
        VkResult r = pGetMemoryFdProperties(dev, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, buf_fd, &fp);
        VkImage img = r == VK_SUCCESS ? make_image_mod(1, h.stride, cand[k]) : VK_NULL_HANDLE;
        if (!img) {
            P("C: modifier 0x%llx 이미지 만들기 실패", (unsigned long long)cand[k]);
            continue;
        }
        VkMemoryRequirements mr;
        vkGetImageMemoryRequirements(dev, img, &mr);
        VkMemoryDedicatedAllocateInfo ded = {VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO, NULL, img, VK_NULL_HANDLE};
        VkImportMemoryFdInfoKHR imp = {VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR, &ded,
                                       VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, dup(buf_fd)};
        int mt = find_mem(mr.memoryTypeBits & fp.memoryTypeBits, 0);
        VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, &imp, mr.size, (uint32_t)mt};
        VkDeviceMemory mem = VK_NULL_HANDLE;
        r = mt < 0 ? VK_ERROR_FORMAT_NOT_SUPPORTED : vkAllocateMemory(dev, &mai, NULL, &mem);
        if (r != VK_SUCCESS && imp.fd >= 0) close(imp.fd);
        if (r == VK_SUCCESS) r = vkBindImageMemory(dev, img, mem, 0);
        uint32_t got = 0;
        int match = r == VK_SUCCESS && readback_matches(img, VK_IMAGE_LAYOUT_GENERAL, outside_family(), PAT_C, &got);
        P("C: GPU 전용 AHB를 modifier 0x%llx로 읽기 %s (0x%08x, 기대 0x%08x%s)", (unsigned long long)cand[k],
          r != VK_SUCCESS ? "가져오기 실패" : match ? "일치" : "불일치", got, PAT_C, r != VK_SUCCESS ? "" : "");
        if (match) {
            ok |= k == 0 ? 1u : 2u;
            /* Compressed wins when it reads right (that is the point) / 압축이 맞으면 압축을 씀(그게 목적) */
            if (k == 1 || pick == EZP_MOD_INVALID) pick = cand[k];
        }
        vkQueueWaitIdle(queue);
        vkDestroyImage(dev, img, NULL);
        if (mem) vkFreeMemory(dev, mem, NULL);
    }
out:
    for (int i = 0; i < nfds; i++) close(fds[i]);
    struct ezp_msg d = {0};
    d.type = EZP_C_DONE;
    d.flags = ok;
    d.modifier = pick;
    send_msg(s, &d, NULL, 0, NULL, 0);
    P("C: 결론 GPU 전용 버퍼 배치 %s", pick == EZP_MOD_INVALID ? "모름 (압축 시험 스위치를 켜도 linear 그대로)"
                                       : pick == EZP_MOD_QCOM_COMPRESSED ? "Qualcomm 압축 (압축 시험 가능)"
                                                                         : "linear (gralloc이 압축하지 않음)");
}

int main(void)
{
    check_node("/dev/kgsl-3d0");
    check_node("/dev/dma_heap/system");
    if (load_vulkan() != 0 || open_device() != 0) {
        P("결론: Vulkan 장치를 열지 못해 판단 불가");
        return 0;
    }
    int s = connect_app();
    if (s >= 0) {
        struct ezp_msg h = {0};
        h.type = EZP_HELLO;
        h.w = EZP_VERSION;
        send_msg(s, &h, NULL, 0, NULL, 0);
    }
    test_a(s);
    int b_imp = 0, b_read = 0;
    int b = test_b(s, &b_imp, &b_read);
    int e = test_e(s);
    test_c(s);
    uint32_t app = 0;
    if (s >= 0) {
        static char text[EZP_MAX_PACKET];
        int fds[EZP_MAX_FDS], nfds = 0;
        struct ezp_msg rep;
        if (recv_msg(s, &rep, EZP_REPORT, fds, &nfds, text, sizeof text) >= 0) {
            app = rep.flags;
            /* App lines as they came, one "INFO present : 앱 ..." each / 앱 줄을 그대로, 줄마다 하나 */
            for (char *line = strtok(text, "\n"); line; line = strtok(NULL, "\n")) P("앱 %s", line);
        } else {
            P("앱 보고 받기 실패 (%s)", strerror(errno));
        }
        for (int i = 0; i < nfds; i++) close(fds[i]);
        close(s);
    }
    /* Verdict in order of the plan: 1-b first, then the fallbacks
     * 계획 순서대로 결론: 1-b 먼저, 그다음 대안 */
    int path_b = b && b_imp && b_read && (app & R_B_EGL);
    int path_a = (app & R_A_EGL) != 0;
    if (path_b)
        P("결론: 1-b 가능 (앱 AHB에 Vulkan이 직접 그림, EGL 표시) — 펜스 Vulkan→앱 %s, 앱→Vulkan %s",
          (app & R_VE_FENCE) ? "됨" : "안 됨(CPU 대기로 대체)", e ? "됨" : "안 됨(CPU 대기로 대체)");
    else if (path_a)
        P("결론: 1-b 불가, A 경로 가능 (Vulkan 내보내기 → EGL dma-buf, 레이어 GPU 복사 1회)");
    else
        P("결론: 0복사 경로 불가 — 위 줄의 실패 지점 확인 필요");
    vkDestroyFence(dev, fence, NULL);
    vkDestroyCommandPool(dev, pool, NULL);
    vkDestroyDevice(dev, NULL);
    vkDestroyInstance(inst, NULL);
    return 0;
}
