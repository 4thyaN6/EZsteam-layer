/*
 * libezs_drm.so: LD_PRELOAD shim that lets the Android GPU device (/dev/kgsl-3d0, shown inside the
 * rootfs as /dev/dri/renderD128 when the app's "GPU device" switch is on) pass the libdrm checks
 * done by Xwayland glamor, GBM and Zink. Own code, MIT; only the public libdrm ABI (xf86drm.h
 * struct layouts and function signatures) is followed. Everything that is not the GPU device goes
 * to the real libdrm unchanged.
 *   - drmGetNodeTypeFromFd: the GPU fd is a render node
 *   - drmGetDevice / drmGetDevice2 / drmGetDeviceFromDevId / drmGetDevices2: one platform device
 *     whose render node is /dev/dri/renderD128
 *   - drmGetDeviceNameFromFd2 / drmGetRenderDeviceNameFromFd: that node path
 *   - drmPrimeFDToHandle / drmPrimeHandleToFD / drmCloseBufferHandle / GEM_CLOSE: kgsl has no GEM
 *     handles, so handles are numbers kept here for dma-buf fds (the same buffer gets the same number)
 * libezs_drm.so: 안드로이드 GPU 장치(/dev/kgsl-3d0, 앱의 "GPU 장치" 스위치를 켜면 rootfs 안에서 /dev/dri/renderD128로
 * 보임)가 Xwayland glamor, GBM, Zink가 하는 libdrm 검사를 통과하게 하는 LD_PRELOAD 심. 자체 코드, MIT. 공개 libdrm ABI
 * (xf86drm.h 구조체 배치와 함수 형태)만 따름. GPU 장치가 아닌 것은 모두 실제 libdrm으로 그대로 보냄.
 *   - drmGetNodeTypeFromFd: GPU fd는 render 노드
 *   - drmGetDevice / drmGetDevice2 / drmGetDeviceFromDevId / drmGetDevices2: render 노드가 /dev/dri/renderD128인
 *     platform 장치 하나
 *   - drmGetDeviceNameFromFd2 / drmGetRenderDeviceNameFromFd: 그 노드 경로
 *   - drmPrimeFDToHandle / drmPrimeHandleToFD / drmCloseBufferHandle / GEM_CLOSE: kgsl에는 GEM 핸들이 없어 dma-buf fd용
 *     번호를 여기서 관리함(같은 버퍼는 같은 번호)
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <unistd.h>

/* Overridable for a host test only (-DEZS_NODE=...) / 호스트 시험에서만 바꿀 수 있음(-DEZS_NODE=...) */
#ifndef EZS_NODE
#define EZS_NODE "/dev/dri/renderD128"
#endif
#ifndef EZS_GPU
#define EZS_GPU "/dev/kgsl-3d0"
#endif

/* libdrm ABI (xf86drm.h) / libdrm ABI(xf86drm.h) */
#define DRM_NODE_PRIMARY 0
#define DRM_NODE_RENDER 2
#define DRM_NODE_MAX 3
#define DRM_BUS_PLATFORM 2
#define DRM_PLATFORM_DEVICE_NAME_LEN 512
#define DRM_CLOEXEC O_CLOEXEC

typedef struct {
    char fullname[DRM_PLATFORM_DEVICE_NAME_LEN];
} drmPlatformBusInfo;

typedef struct {
    char **compatible;
} drmPlatformDeviceInfo;

typedef struct _drmDevice {
    char **nodes;
    int available_nodes;
    int bustype;
    union {
        void *pci;
        void *usb;
        drmPlatformBusInfo *platform;
        void *host1x;
    } businfo;
    union {
        void *pci;
        void *usb;
        drmPlatformDeviceInfo *platform;
        void *host1x;
    } deviceinfo;
} drmDevice, *drmDevicePtr;

struct gem_close {
    uint32_t handle;
    uint32_t pad;
};
#define EZS_GEM_CLOSE _IOW('d', 0x09, struct gem_close)

static void *next(const char *name)
{
    return dlsym(RTLD_NEXT, name);
}

/* ---- which fd / dev_t is the GPU / 어떤 fd·dev_t가 GPU인지 ---- */

static pthread_once_t once = PTHREAD_ONCE_INIT;
static dev_t gpu_rdev;
static int gpu_known;

static void find_gpu(void)
{
    struct stat st;
    if (stat(EZS_NODE, &st) == 0 && S_ISCHR(st.st_mode)) {
        gpu_rdev = st.st_rdev;
        gpu_known = 1;
    } else if (stat(EZS_GPU, &st) == 0 && S_ISCHR(st.st_mode)) {
        gpu_rdev = st.st_rdev;
        gpu_known = 1;
    }
    if (gpu_known && getenv("EZS_DRM_DEBUG"))
        fprintf(stderr, "ezs-drm: GPU 장치 %u:%u\n", major(gpu_rdev), minor(gpu_rdev));
}

static int is_gpu_rdev(dev_t d)
{
    pthread_once(&once, find_gpu);
    return gpu_known && d == gpu_rdev;
}

/* Only the kgsl device itself; a real DRM node (if any) is left to libdrm
   kgsl 장치 자체만. 실제 DRM 노드가 있으면 libdrm에 맡김 */
static int is_gpu_fd(int fd)
{
    struct stat st;
    if (fd < 0 || fstat(fd, &st) != 0 || !S_ISCHR(st.st_mode)) return 0;
    return is_gpu_rdev(st.st_rdev);
}

/* ---- fake device, laid out the way the real drmFreeDevice frees it: one block for the device,
   node table, node name and bus/device info, plus the platform "compatible" list and each of its
   strings in their own allocations (drmFreeDevice frees those first for DRM_BUS_PLATFORM). A
   single block for everything made drmFreeDevice abort in Xwayland ("free(): invalid pointer",
   from Zink's device match, 2026-10-29)
   가짜 장치. 실제 drmFreeDevice가 해제하는 방식대로 배치함: 장치, 노드 표, 노드 이름, 버스/장치 정보는 한 블록이고, platform
   "compatible" 목록과 그 문자열은 각각 따로 할당함(DRM_BUS_PLATFORM이면 drmFreeDevice가 그것들을 먼저 해제함). 전부 한
   블록에 두었더니 Xwayland에서 drmFreeDevice가 중단됐음("free(): invalid pointer", Zink 장치 맞춤에서, 2026-10-29) ---- */

static drmDevicePtr fake_device(void)
{
    size_t sz = sizeof(drmDevice) + sizeof(char *) * DRM_NODE_MAX + sizeof(EZS_NODE) + sizeof(drmPlatformBusInfo) +
                sizeof(drmPlatformDeviceInfo);
    uint8_t *m = calloc(1, sz);
    char **list = calloc(2, sizeof(char *));
    char *compat = strdup("qcom,adreno");
    if (!m || !list || !compat) {
        free(m);
        free(list);
        free(compat);
        return NULL;
    }
    drmDevicePtr d = (drmDevicePtr)m;
    uint8_t *p = m + sizeof(drmDevice);
    d->nodes = (char **)p;
    p += sizeof(char *) * DRM_NODE_MAX;
    d->nodes[DRM_NODE_RENDER] = (char *)p;
    memcpy(p, EZS_NODE, sizeof(EZS_NODE));
    p += sizeof(EZS_NODE);
    d->available_nodes = 1 << DRM_NODE_RENDER;
    d->bustype = DRM_BUS_PLATFORM;
    d->businfo.platform = (drmPlatformBusInfo *)p;
    snprintf(d->businfo.platform->fullname, DRM_PLATFORM_DEVICE_NAME_LEN, "/soc/kgsl-3d0");
    p += sizeof(drmPlatformBusInfo);
    d->deviceinfo.platform = (drmPlatformDeviceInfo *)p;
    list[0] = compat;
    list[1] = NULL;
    d->deviceinfo.platform->compatible = list;
    return d;
}

int drmGetNodeTypeFromFd(int fd)
{
    if (is_gpu_fd(fd)) return DRM_NODE_RENDER;
    int (*f)(int) = next("drmGetNodeTypeFromFd");
    if (!f) {
        errno = ENOSYS;
        return -1;
    }
    return f(fd);
}

int drmGetDevice2(int fd, uint32_t flags, drmDevicePtr *device)
{
    if (device && is_gpu_fd(fd)) {
        *device = fake_device();
        return *device ? 0 : -ENOMEM;
    }
    int (*f)(int, uint32_t, drmDevicePtr *) = next("drmGetDevice2");
    return f ? f(fd, flags, device) : -ENOSYS;
}

int drmGetDevice(int fd, drmDevicePtr *device)
{
    if (device && is_gpu_fd(fd)) {
        *device = fake_device();
        return *device ? 0 : -ENOMEM;
    }
    int (*f)(int, drmDevicePtr *) = next("drmGetDevice");
    return f ? f(fd, device) : -ENOSYS;
}

int drmGetDeviceFromDevId(dev_t dev, uint32_t flags, drmDevicePtr *device)
{
    if (device && is_gpu_rdev(dev)) {
        *device = fake_device();
        return *device ? 0 : -ENOMEM;
    }
    int (*f)(dev_t, uint32_t, drmDevicePtr *) = next("drmGetDeviceFromDevId");
    return f ? f(dev, flags, device) : -ENOSYS;
}

/* The real list has no DRM devices here (no /sys/class/drm), so the GPU is added at the end
   여기서는 실제 목록에 DRM 장치가 없으므로(/sys/class/drm 없음) GPU를 끝에 더함 */
int drmGetDevices2(uint32_t flags, drmDevicePtr devices[], int max_devices)
{
    int (*f)(uint32_t, drmDevicePtr *, int) = next("drmGetDevices2");
    int n = f ? f(flags, devices, max_devices) : 0;
    if (n < 0) n = 0;
    pthread_once(&once, find_gpu);
    if (!gpu_known) return n;
    if (!devices) return n + 1;
    if (n >= max_devices) return n;
    devices[n] = fake_device();
    return devices[n] ? n + 1 : n;
}

int drmGetDevices(drmDevicePtr devices[], int max_devices)
{
    return drmGetDevices2(0, devices, max_devices);
}

char *drmGetDeviceNameFromFd2(int fd)
{
    if (is_gpu_fd(fd)) return strdup(EZS_NODE);
    char *(*f)(int) = next("drmGetDeviceNameFromFd2");
    return f ? f(fd) : NULL;
}

char *drmGetRenderDeviceNameFromFd(int fd)
{
    if (is_gpu_fd(fd)) return strdup(EZS_NODE);
    char *(*f)(int) = next("drmGetRenderDeviceNameFromFd");
    return f ? f(fd) : NULL;
}

/* ---- buffer handles for dma-buf fds / dma-buf fd용 버퍼 핸들 ---- */

#define MAX_HANDLES 4096

struct entry {
    uint32_t handle;
    int fd;          /* own dup of the dma-buf / 자체 보관 dma-buf 복제본 */
    dev_t dev;
    ino_t ino;       /* identifies the buffer / 버퍼 식별 */
    unsigned refs;
};

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static struct entry table[MAX_HANDLES];
static uint32_t handle_seq;

static struct entry *find_handle(uint32_t h)
{
    for (int i = 0; i < MAX_HANDLES; i++)
        if (table[i].handle == h && table[i].fd >= 0 && table[i].handle) return &table[i];
    return NULL;
}

int drmPrimeFDToHandle(int fd, int prime_fd, uint32_t *handle)
{
    if (!is_gpu_fd(fd)) {
        int (*f)(int, int, uint32_t *) = next("drmPrimeFDToHandle");
        return f ? f(fd, prime_fd, handle) : -ENOSYS;
    }
    struct stat st;
    if (fstat(prime_fd, &st) != 0) return -errno;
    pthread_mutex_lock(&lock);
    struct entry *free_slot = NULL;
    for (int i = 0; i < MAX_HANDLES; i++) {
        struct entry *e = &table[i];
        if (e->handle && e->dev == st.st_dev && e->ino == st.st_ino) {
            /* Same buffer, same handle (GEM rule) / 같은 버퍼는 같은 핸들(GEM 규칙) */
            e->refs++;
            *handle = e->handle;
            pthread_mutex_unlock(&lock);
            return 0;
        }
        if (!e->handle && !free_slot) free_slot = e;
    }
    if (!free_slot) {
        pthread_mutex_unlock(&lock);
        return -ENOSPC;
    }
    int own = fcntl(prime_fd, F_DUPFD_CLOEXEC, 0);
    if (own < 0) {
        int err = errno;
        pthread_mutex_unlock(&lock);
        return -err;
    }
    if (++handle_seq == 0) handle_seq = 1;
    free_slot->handle = handle_seq;
    free_slot->fd = own;
    free_slot->dev = st.st_dev;
    free_slot->ino = st.st_ino;
    free_slot->refs = 1;
    *handle = free_slot->handle;
    pthread_mutex_unlock(&lock);
    return 0;
}

int drmPrimeHandleToFD(int fd, uint32_t handle, uint32_t flags, int *prime_fd)
{
    if (!is_gpu_fd(fd)) {
        int (*f)(int, uint32_t, uint32_t, int *) = next("drmPrimeHandleToFD");
        return f ? f(fd, handle, flags, prime_fd) : -ENOSYS;
    }
    pthread_mutex_lock(&lock);
    struct entry *e = find_handle(handle);
    int out = -1, err = ENOENT;
    if (e) {
        out = fcntl(e->fd, (flags & DRM_CLOEXEC) ? F_DUPFD_CLOEXEC : F_DUPFD, 0);
        err = errno;
    }
    pthread_mutex_unlock(&lock);
    if (out < 0) return -err;
    *prime_fd = out;
    return 0;
}

static int release(uint32_t handle)
{
    pthread_mutex_lock(&lock);
    struct entry *e = find_handle(handle);
    if (!e) {
        pthread_mutex_unlock(&lock);
        return -EINVAL;
    }
    /* GEM_CLOSE drops the handle whatever the import count / GEM_CLOSE는 가져온 횟수와 상관없이 핸들을 없앰 */
    close(e->fd);
    memset(e, 0, sizeof(*e));
    e->fd = -1;
    pthread_mutex_unlock(&lock);
    return 0;
}

int drmCloseBufferHandle(int fd, uint32_t handle)
{
    if (is_gpu_fd(fd)) return release(handle);
    int (*f)(int, uint32_t) = next("drmCloseBufferHandle");
    return f ? f(fd, handle) : -ENOSYS;
}

int drmIoctl(int fd, unsigned long request, void *arg)
{
    if (request == EZS_GEM_CLOSE && arg && is_gpu_fd(fd)) {
        int r = release(((struct gem_close *)arg)->handle);
        if (r < 0) {
            errno = -r;
            return -1;
        }
        return 0;
    }
    int (*f)(int, unsigned long, void *) = next("drmIoctl");
    if (!f) {
        errno = ENOSYS;
        return -1;
    }
    return f(fd, request, arg);
}

__attribute__((constructor)) static void init_table(void)
{
    for (int i = 0; i < MAX_HANDLES; i++) table[i].fd = -1;
}
