/*
 * ezs-wl: minimal Wayland compositor inside the rootfs for one rootful Xwayland (the X server of the
 * Steam session when the app's "Xwayland" screen mode is on). Own code, MIT; written from the
 * public Wayland protocol descriptions (wayland.xml, xdg-shell, linux-dmabuf v1).
 * Xwayland draws its whole X screen into one toplevel surface; every committed frame is copied
 * into an XWD-format screen file laid out like Xvfb's -fbdir file, so the app shows it with the
 * same reader it uses for Xvfb (nothing changes on the app side). With --rdev the compositor also
 * offers linux-dmabuf v4 with the GPU node as main device, so Xwayland can turn on glamor and DRI3
 * (GPU drawing for X clients); those buffers are read back through mmap (linear dma-bufs).
 * Globals: wl_compositor 4, wl_shm (libwayland), wl_output 3, xdg_wm_base 2, zwp_linux_dmabuf_v1 4.
 * No seat: input goes to the X server through XTEST as with Xvfb (rootful Xwayland keeps XTEST).
 * ezs-wl: rootful Xwayland 하나를 위한 rootfs 안의 최소 Wayland 컴포지터(앱의 "Xwayland" 화면 방식을 켰을 때 Steam
 * 세션의 X 서버). 자체 코드, MIT. 공개 Wayland 프로토콜 설명(wayland.xml, xdg-shell, linux-dmabuf v1)으로 작성.
 * Xwayland는 X 화면 전체를 toplevel 표면 하나에 그림. 커밋된 프레임마다 Xvfb -fbdir 파일과 같은 배치의 XWD 형식 화면
 * 파일로 복사해, 앱이 Xvfb용과 같은 읽기 코드로 보여 줌(앱 쪽은 바뀌는 것 없음). --rdev를 주면 GPU 노드를 주 장치로 한
 * linux-dmabuf v4도 제공해 Xwayland가 glamor와 DRI3(X 클라이언트의 GPU 그리기)를 켤 수 있음. 그 버퍼는 mmap으로 읽어 옴
 * (linear dma-buf). 전역: wl_compositor 4, wl_shm(libwayland), wl_output 3, xdg_wm_base 2, zwp_linux_dmabuf_v1 4.
 * seat 없음: 입력은 Xvfb 때처럼 XTEST로 X 서버에 감(rootful Xwayland는 XTEST를 그대로 둠)
 *
 *   ezs-wl --socket NAME --fb FILE --size WxH [--rdev MAJ:MIN] [--hz N]
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/dma-buf.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <time.h>
#include <unistd.h>

#include <wayland-server.h>
#include "xdg-shell-server-protocol.h"
#include "linux-dmabuf-v1-server-protocol.h"

/* DRM fourcc codes and modifiers used here / 여기서 쓰는 DRM fourcc 코드와 modifier */
#define FOURCC(a, b, c, d) ((uint32_t)(a) | ((uint32_t)(b) << 8) | ((uint32_t)(c) << 16) | ((uint32_t)(d) << 24))
#define FMT_XRGB8888 FOURCC('X', 'R', '2', '4')
#define FMT_ARGB8888 FOURCC('A', 'R', '2', '4')
#define FMT_XBGR8888 FOURCC('X', 'B', '2', '4')
#define FMT_ABGR8888 FOURCC('A', 'B', '2', '4')
#define MOD_LINEAR 0ull
#define MOD_INVALID 0x00ffffffffffffffull

static int W = 1280, H = 800, HZ = 60;
static dev_t rdev;
static int have_rdev;
static struct wl_display *dpy;

/* ---- screen file (XWD, like Xvfb -fbdir) / 화면 파일(XWD, Xvfb -fbdir과 같음) ---- */

static uint8_t *fb_pix;
static size_t fb_bpl;

static void put_be32(uint8_t *p, uint32_t v)
{
    p[0] = v >> 24;
    p[1] = v >> 16;
    p[2] = v >> 8;
    p[3] = v;
}

/* XWD header (25 CARD32 fields, most significant byte first, then the window name), 32 bpp
   ZPixmap pixels B,G,R,X in memory like Xvfb at depth 24 / XWD 헤더(CARD32 25개, 상위 바이트 먼저, 그다음 창
   이름), 32bpp ZPixmap 픽셀은 깊이 24의 Xvfb처럼 메모리에 B,G,R,X */
static int fb_open(const char *path)
{
    static const char name[] = "ezs-wl";
    uint32_t hsize = 100 + sizeof(name);
    hsize = (hsize + 3) & ~3u;
    fb_bpl = (size_t)W * 4;
    size_t total = hsize + fb_bpl * H;
    char tmp[4096];
    snprintf(tmp, sizeof(tmp), "%s.tmp", path);
    int fd = open(tmp, O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) return -1;
    if (ftruncate(fd, (off_t)total) < 0) {
        close(fd);
        return -1;
    }
    uint8_t *m = mmap(NULL, total, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (m == MAP_FAILED) return -1;
    uint32_t f[25] = {
        hsize, 7, 2, 24, (uint32_t)W, (uint32_t)H, 0, 0 /* LSBFirst */, 32, 0, 32, 32,
        (uint32_t)fb_bpl, 4 /* TrueColor */, 0xff0000, 0xff00, 0xff, 8, 256, 0,
        (uint32_t)W, (uint32_t)H, 0, 0, 0,
    };
    for (int i = 0; i < 25; i++) put_be32(m + i * 4, f[i]);
    memcpy(m + 100, name, sizeof(name));
    fb_pix = m + hsize;
    /* The app waits for a non-empty file: renamed in place once complete / 앱은 비지 않은 파일을 기다림: 다 쓴 뒤 이름을 바꿈 */
    if (rename(tmp, path) < 0) return -1;
    return 0;
}

/* Copy one buffer into the screen file, converting the format where needed
   버퍼 하나를 화면 파일로 복사함. 필요하면 형식을 바꿈 */
static void fb_copy(const uint8_t *src, int sw, int sh, size_t stride, uint32_t fmt)
{
    int w = sw < W ? sw : W, h = sh < H ? sh : H;
    int swap = fmt == FMT_XBGR8888 || fmt == FMT_ABGR8888;
    for (int y = 0; y < h; y++) {
        const uint8_t *s = src + (size_t)y * stride;
        uint8_t *d = fb_pix + (size_t)y * fb_bpl;
        if (!swap) {
            memcpy(d, s, (size_t)w * 4);
        } else {
            for (int x = 0; x < w; x++) {
                d[x * 4] = s[x * 4 + 2];
                d[x * 4 + 1] = s[x * 4 + 1];
                d[x * 4 + 2] = s[x * 4];
                d[x * 4 + 3] = s[x * 4 + 3];
            }
        }
    }
}

/* ---- dma-buf buffers / dma-buf 버퍼 ---- */

struct dmabuf {
    struct wl_resource *res;
    int fd;
    uint32_t offset, stride, fmt;
    uint64_t modifier;
    int w, h;
    void *map;
    size_t map_len;
};

static void dmabuf_free(struct dmabuf *b)
{
    if (b->map) munmap(b->map, b->map_len);
    if (b->fd >= 0) close(b->fd);
    free(b);
}

static void buffer_destroy(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static const struct wl_buffer_interface buffer_impl = { .destroy = buffer_destroy };

static void dmabuf_res_destroy(struct wl_resource *r)
{
    dmabuf_free(wl_resource_get_user_data(r));
}

static struct dmabuf *dmabuf_from(struct wl_resource *r)
{
    if (!wl_resource_instance_of(r, &wl_buffer_interface, &buffer_impl)) return NULL;
    return wl_resource_get_user_data(r);
}

/* Read a linear dma-buf through mmap, inside a CPU-access sync bracket
   CPU 접근 동기화 안에서 mmap으로 linear dma-buf를 읽음 */
static int dmabuf_copy(struct dmabuf *b)
{
    if (b->modifier != MOD_LINEAR && b->modifier != MOD_INVALID) return -1;
    if (!b->map) {
        b->map_len = (size_t)b->offset + (size_t)b->stride * b->h;
        b->map = mmap(NULL, b->map_len, PROT_READ, MAP_SHARED, b->fd, 0);
        if (b->map == MAP_FAILED) {
            b->map = NULL;
            return -1;
        }
    }
    struct dma_buf_sync s = { DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ };
    ioctl(b->fd, DMA_BUF_IOCTL_SYNC, &s);
    fb_copy((const uint8_t *)b->map + b->offset, b->w, b->h, b->stride, b->fmt);
    s.flags = DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ;
    ioctl(b->fd, DMA_BUF_IOCTL_SYNC, &s);
    return 0;
}

/* ---- surfaces / 표면 ---- */

struct surface {
    struct wl_resource *res;
    struct wl_resource *pending;
    struct wl_listener pending_gone;
    struct wl_list frames; /* wl_callback resources, link via wl_resource_get_link */
    struct wl_list pending_frames;
    int toplevel;
};

static long commits, shm_frames, dma_frames, failed_frames;

static void pending_gone(struct wl_listener *l, void *data)
{
    (void)data;
    struct surface *s = wl_container_of(l, s, pending_gone);
    s->pending = NULL;
    wl_list_remove(&s->pending_gone.link);
    wl_list_init(&s->pending_gone.link);
}

static void surf_destroy(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static void surf_attach(struct wl_client *c, struct wl_resource *r, struct wl_resource *buf, int32_t x, int32_t y)
{
    (void)c;
    (void)x;
    (void)y;
    struct surface *s = wl_resource_get_user_data(r);
    wl_list_remove(&s->pending_gone.link);
    wl_list_init(&s->pending_gone.link);
    s->pending = buf;
    if (buf) wl_resource_add_destroy_listener(buf, &s->pending_gone);
}

static void surf_damage(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w, int32_t h)
{
    (void)c; (void)r; (void)x; (void)y; (void)w; (void)h;
}

/* Frame callbacks sit in the surface lists through their resource link. libwayland does not take a
   resource out of such a list when it is destroyed, so the destroy handler does; without it the
   list keeps the freed callback and the next tick sends to freed memory (compositor crash, the
   client sees the socket close: Xwayland "failed to read Wayland events: Broken pipe")
   프레임 콜백은 리소스 link로 표면 목록에 들어감. libwayland는 리소스를 지울 때 이 목록에서 빼 주지 않으므로 destroy
   처리기가 뺌. 빼지 않으면 목록에 해제된 콜백이 남아 다음 박자에 해제된 메모리로 보내게 됨(컴포지터가 죽고 클라이언트는
   소켓이 닫힌 것으로 봄: Xwayland "failed to read Wayland events: Broken pipe") */
static void frame_cb_destroy(struct wl_resource *cb)
{
    wl_list_remove(wl_resource_get_link(cb));
}

static void surf_frame(struct wl_client *c, struct wl_resource *r, uint32_t id)
{
    struct surface *s = wl_resource_get_user_data(r);
    struct wl_resource *cb = wl_resource_create(c, &wl_callback_interface, 1, id);
    if (!cb) {
        wl_client_post_no_memory(c);
        return;
    }
    /* Linked right away, so the destroy handler always finds a valid link
       바로 목록에 넣으므로 destroy 처리기는 항상 유효한 link를 만남 */
    wl_list_insert(s->pending_frames.prev, wl_resource_get_link(cb));
    wl_resource_set_implementation(cb, NULL, NULL, frame_cb_destroy);
}

static void surf_region(struct wl_client *c, struct wl_resource *r, struct wl_resource *reg)
{
    (void)c; (void)r; (void)reg;
}

static void surf_commit(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    struct surface *s = wl_resource_get_user_data(r);
    struct wl_resource *buf = s->pending;
    commits++;
    if (buf && s->toplevel) {
        struct wl_shm_buffer *shm = wl_shm_buffer_get(buf);
        struct dmabuf *db;
        if (shm) {
            uint32_t f = wl_shm_buffer_get_format(shm);
            uint32_t fmt = f == WL_SHM_FORMAT_ARGB8888 ? FMT_ARGB8888 : f == WL_SHM_FORMAT_XRGB8888 ? FMT_XRGB8888 : f;
            wl_shm_buffer_begin_access(shm);
            fb_copy(wl_shm_buffer_get_data(shm), wl_shm_buffer_get_width(shm), wl_shm_buffer_get_height(shm),
                    (size_t)wl_shm_buffer_get_stride(shm), fmt);
            wl_shm_buffer_end_access(shm);
            shm_frames++;
        } else if ((db = dmabuf_from(buf)) != NULL) {
            if (dmabuf_copy(db) == 0) {
                dma_frames++;
            } else {
                if (failed_frames++ == 0)
                    printf("ezs-wl: dma-buf 읽기 실패 (modifier 0x%llx, %s)\n", (unsigned long long)db->modifier,
                           strerror(errno));
            }
        }
        if (shm_frames + dma_frames == 1) {
            printf("ezs-wl: 첫 프레임 (%s)\n", shm ? "공유 메모리" : "dma-buf");
            fflush(stdout);
        }
    }
    if (buf) {
        wl_buffer_send_release(buf);
        wl_list_remove(&s->pending_gone.link);
        wl_list_init(&s->pending_gone.link);
        s->pending = NULL;
    }
    wl_list_insert_list(s->frames.prev, &s->pending_frames);
    wl_list_init(&s->pending_frames);
}

static void surf_transform(struct wl_client *c, struct wl_resource *r, int32_t t)
{
    (void)c; (void)r; (void)t;
}

static void surf_scale(struct wl_client *c, struct wl_resource *r, int32_t sc)
{
    (void)c; (void)r; (void)sc;
}

static const struct wl_surface_interface surface_impl = {
    .destroy = surf_destroy,
    .attach = surf_attach,
    .damage = surf_damage,
    .frame = surf_frame,
    .set_opaque_region = surf_region,
    .set_input_region = surf_region,
    .commit = surf_commit,
    .set_buffer_transform = surf_transform,
    .set_buffer_scale = surf_scale,
    .damage_buffer = surf_damage,
};

static struct surface *surfaces[64];

static void surface_res_destroy(struct wl_resource *r)
{
    struct surface *s = wl_resource_get_user_data(r);
    struct wl_resource *cb, *tmp;
    /* Each destroy unlinks itself (frame_cb_destroy); the safe walk keeps the next entry first
       지울 때마다 스스로 목록에서 빠짐(frame_cb_destroy). safe 순회가 다음 항목을 먼저 잡아 둠 */
    wl_resource_for_each_safe(cb, tmp, &s->frames) wl_resource_destroy(cb);
    wl_resource_for_each_safe(cb, tmp, &s->pending_frames) wl_resource_destroy(cb);
    wl_list_remove(&s->pending_gone.link);
    for (int i = 0; i < 64; i++)
        if (surfaces[i] == s) surfaces[i] = NULL;
    free(s);
}

static void comp_create_surface(struct wl_client *c, struct wl_resource *r, uint32_t id)
{
    struct surface *s = calloc(1, sizeof(*s));
    if (!s) {
        wl_client_post_no_memory(c);
        return;
    }
    s->res = wl_resource_create(c, &wl_surface_interface, wl_resource_get_version(r), id);
    if (!s->res) {
        free(s);
        wl_client_post_no_memory(c);
        return;
    }
    wl_list_init(&s->frames);
    wl_list_init(&s->pending_frames);
    wl_list_init(&s->pending_gone.link);
    s->pending_gone.notify = pending_gone;
    wl_resource_set_implementation(s->res, &surface_impl, s, surface_res_destroy);
    for (int i = 0; i < 64; i++)
        if (!surfaces[i]) {
            surfaces[i] = s;
            break;
        }
}

static void region_destroy(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static void region_rect(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w, int32_t h)
{
    (void)c; (void)r; (void)x; (void)y; (void)w; (void)h;
}

static const struct wl_region_interface region_impl = {
    .destroy = region_destroy, .add = region_rect, .subtract = region_rect,
};

static void comp_create_region(struct wl_client *c, struct wl_resource *r, uint32_t id)
{
    struct wl_resource *reg = wl_resource_create(c, &wl_region_interface, wl_resource_get_version(r), id);
    if (!reg) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(reg, &region_impl, NULL, NULL);
}

static const struct wl_compositor_interface compositor_impl = {
    .create_surface = comp_create_surface, .create_region = comp_create_region,
};

static void bind_compositor(struct wl_client *c, void *data, uint32_t ver, uint32_t id)
{
    (void)data;
    struct wl_resource *r = wl_resource_create(c, &wl_compositor_interface, ver, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &compositor_impl, NULL, NULL);
}

/* ---- wl_output ---- */

static void output_release(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static const struct wl_output_interface output_impl = { .release = output_release };

static void bind_output(struct wl_client *c, void *data, uint32_t ver, uint32_t id)
{
    (void)data;
    struct wl_resource *r = wl_resource_create(c, &wl_output_interface, ver, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &output_impl, NULL, NULL);
    wl_output_send_geometry(r, 0, 0, 0, 0, WL_OUTPUT_SUBPIXEL_UNKNOWN, "EZsteam", "screen", WL_OUTPUT_TRANSFORM_NORMAL);
    wl_output_send_mode(r, WL_OUTPUT_MODE_CURRENT | WL_OUTPUT_MODE_PREFERRED, W, H, HZ * 1000);
    if (ver >= 2) wl_output_send_scale(r, 1);
    if (ver >= 2) wl_output_send_done(r);
}

/* ---- xdg-shell (only what a rootful Xwayland window needs) / xdg-shell(rootful Xwayland 창에 필요한 것만) ---- */

static void res_destroy(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static void tl_set_parent(struct wl_client *c, struct wl_resource *r, struct wl_resource *p) { (void)c; (void)r; (void)p; }
static void tl_set_string(struct wl_client *c, struct wl_resource *r, const char *s) { (void)c; (void)r; (void)s; }
static void tl_menu(struct wl_client *c, struct wl_resource *r, struct wl_resource *seat, uint32_t serial, int32_t x, int32_t y)
{ (void)c; (void)r; (void)seat; (void)serial; (void)x; (void)y; }
static void tl_move(struct wl_client *c, struct wl_resource *r, struct wl_resource *seat, uint32_t serial)
{ (void)c; (void)r; (void)seat; (void)serial; }
static void tl_resize(struct wl_client *c, struct wl_resource *r, struct wl_resource *seat, uint32_t serial, uint32_t e)
{ (void)c; (void)r; (void)seat; (void)serial; (void)e; }
static void tl_size(struct wl_client *c, struct wl_resource *r, int32_t w, int32_t h) { (void)c; (void)r; (void)w; (void)h; }
static void tl_plain(struct wl_client *c, struct wl_resource *r) { (void)c; (void)r; }
static void tl_fullscreen(struct wl_client *c, struct wl_resource *r, struct wl_resource *o) { (void)c; (void)r; (void)o; }

static const struct xdg_toplevel_interface toplevel_impl = {
    .destroy = res_destroy,
    .set_parent = tl_set_parent,
    .set_title = tl_set_string,
    .set_app_id = tl_set_string,
    .show_window_menu = tl_menu,
    .move = tl_move,
    .resize = tl_resize,
    .set_max_size = tl_size,
    .set_min_size = tl_size,
    .set_maximized = tl_plain,
    .unset_maximized = tl_plain,
    .set_fullscreen = tl_fullscreen,
    .unset_fullscreen = tl_plain,
    .set_minimized = tl_plain,
};

static uint32_t serial_next = 1;

static void xs_get_toplevel(struct wl_client *c, struct wl_resource *r, uint32_t id)
{
    struct surface *s = wl_resource_get_user_data(r);
    struct wl_resource *t = wl_resource_create(c, &xdg_toplevel_interface, wl_resource_get_version(r), id);
    if (!t) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(t, &toplevel_impl, NULL, NULL);
    if (s) s->toplevel = 1;
    /* The size of the screen, activated; then the surface configure / 화면 크기, 활성 상태. 그다음 표면 configure */
    struct wl_array states;
    wl_array_init(&states);
    uint32_t *st = wl_array_add(&states, sizeof(uint32_t));
    if (st) *st = XDG_TOPLEVEL_STATE_ACTIVATED;
    xdg_toplevel_send_configure(t, W, H, &states);
    wl_array_release(&states);
    xdg_surface_send_configure(r, serial_next++);
    printf("ezs-wl: Xwayland 창 (%dx%d)\n", W, H);
    fflush(stdout);
}

static void popup_grab(struct wl_client *c, struct wl_resource *r, struct wl_resource *seat, uint32_t serial)
{ (void)c; (void)r; (void)seat; (void)serial; }
static void popup_reposition(struct wl_client *c, struct wl_resource *r, struct wl_resource *pos, uint32_t token)
{ (void)c; (void)r; (void)pos; (void)token; }

static const struct xdg_popup_interface popup_impl = {
    .destroy = res_destroy, .grab = popup_grab, .reposition = popup_reposition,
};

static void xs_get_popup(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *parent,
                         struct wl_resource *pos)
{
    (void)parent;
    (void)pos;
    struct wl_resource *p = wl_resource_create(c, &xdg_popup_interface, wl_resource_get_version(r), id);
    if (!p) {
        wl_client_post_no_memory(c);
        return;
    }
    /* Never shown (rootful Xwayland has no popups); dismissed at once
       보여 주지 않음(rootful Xwayland에는 팝업 없음). 바로 닫힘을 알림 */
    wl_resource_set_implementation(p, &popup_impl, NULL, NULL);
    xdg_popup_send_popup_done(p);
}

static void xs_geometry(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w, int32_t h)
{ (void)c; (void)r; (void)x; (void)y; (void)w; (void)h; }
static void xs_ack(struct wl_client *c, struct wl_resource *r, uint32_t serial) { (void)c; (void)r; (void)serial; }

static const struct xdg_surface_interface xdg_surface_impl = {
    .destroy = res_destroy,
    .get_toplevel = xs_get_toplevel,
    .get_popup = xs_get_popup,
    .set_window_geometry = xs_geometry,
    .ack_configure = xs_ack,
};

static void pos_size(struct wl_client *c, struct wl_resource *r, int32_t w, int32_t h) { (void)c; (void)r; (void)w; (void)h; }
static void pos_rect(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w, int32_t h)
{ (void)c; (void)r; (void)x; (void)y; (void)w; (void)h; }
static void pos_u32(struct wl_client *c, struct wl_resource *r, uint32_t v) { (void)c; (void)r; (void)v; }
static void pos_off(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y) { (void)c; (void)r; (void)x; (void)y; }

static const struct xdg_positioner_interface positioner_impl = {
    .destroy = res_destroy,
    .set_size = pos_size,
    .set_anchor_rect = pos_rect,
    .set_anchor = pos_u32,
    .set_gravity = pos_u32,
    .set_constraint_adjustment = pos_u32,
    .set_offset = pos_off,
};

static void wm_create_positioner(struct wl_client *c, struct wl_resource *r, uint32_t id)
{
    struct wl_resource *p = wl_resource_create(c, &xdg_positioner_interface, wl_resource_get_version(r), id);
    if (!p) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(p, &positioner_impl, NULL, NULL);
}

static void wm_get_xdg_surface(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *surf)
{
    struct wl_resource *x = wl_resource_create(c, &xdg_surface_interface, wl_resource_get_version(r), id);
    if (!x) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(x, &xdg_surface_impl, wl_resource_get_user_data(surf), NULL);
}

static void wm_pong(struct wl_client *c, struct wl_resource *r, uint32_t serial) { (void)c; (void)r; (void)serial; }

static const struct xdg_wm_base_interface wm_impl = {
    .destroy = res_destroy,
    .create_positioner = wm_create_positioner,
    .get_xdg_surface = wm_get_xdg_surface,
    .pong = wm_pong,
};

static void bind_wm(struct wl_client *c, void *data, uint32_t ver, uint32_t id)
{
    (void)data;
    struct wl_resource *r = wl_resource_create(c, &xdg_wm_base_interface, ver, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &wm_impl, NULL, NULL);
}

/* ---- linux-dmabuf v4 ---- */

struct params {
    int fd;
    uint32_t offset, stride;
    uint64_t modifier;
    int used;
};

static void params_res_destroy(struct wl_resource *r)
{
    struct params *p = wl_resource_get_user_data(r);
    if (p->fd >= 0) close(p->fd);
    free(p);
}

static void params_add(struct wl_client *c, struct wl_resource *r, int32_t fd, uint32_t plane, uint32_t offset,
                       uint32_t stride, uint32_t mod_hi, uint32_t mod_lo)
{
    (void)c;
    struct params *p = wl_resource_get_user_data(r);
    /* One plane only (linear RGB) / 평면 하나만(linear RGB) */
    if (plane != 0 || p->fd >= 0) {
        close(fd);
        wl_resource_post_error(r, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_PLANE_IDX, "only one plane is supported");
        return;
    }
    p->fd = fd;
    p->offset = offset;
    p->stride = stride;
    p->modifier = ((uint64_t)mod_hi << 32) | mod_lo;
}

static struct wl_resource *params_make(struct wl_client *c, struct wl_resource *r, uint32_t id, int32_t w, int32_t h,
                                       uint32_t fmt)
{
    struct params *p = wl_resource_get_user_data(r);
    if (p->used || p->fd < 0) return NULL;
    p->used = 1;
    struct dmabuf *b = calloc(1, sizeof(*b));
    if (!b) return NULL;
    b->fd = p->fd;
    p->fd = -1;
    b->offset = p->offset;
    b->stride = p->stride;
    b->modifier = p->modifier;
    b->fmt = fmt;
    b->w = w;
    b->h = h;
    b->res = wl_resource_create(c, &wl_buffer_interface, 1, id);
    if (!b->res) {
        dmabuf_free(b);
        return NULL;
    }
    wl_resource_set_implementation(b->res, &buffer_impl, b, dmabuf_res_destroy);
    return b->res;
}

static void params_create(struct wl_client *c, struct wl_resource *r, int32_t w, int32_t h, uint32_t fmt, uint32_t flags)
{
    (void)flags;
    struct wl_resource *b = params_make(c, r, 0, w, h, fmt);
    if (b) zwp_linux_buffer_params_v1_send_created(r, b);
    else zwp_linux_buffer_params_v1_send_failed(r);
}

static void params_create_immed(struct wl_client *c, struct wl_resource *r, uint32_t id, int32_t w, int32_t h,
                                uint32_t fmt, uint32_t flags)
{
    (void)flags;
    if (!params_make(c, r, id, w, h, fmt))
        wl_resource_post_error(r, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_WL_BUFFER, "cannot import");
}

static const struct zwp_linux_buffer_params_v1_interface params_impl = {
    .destroy = res_destroy,
    .add = params_add,
    .create = params_create,
    .create_immed = params_create_immed,
};

static void dm_create_params(struct wl_client *c, struct wl_resource *r, uint32_t id)
{
    struct params *p = calloc(1, sizeof(*p));
    if (!p) {
        wl_client_post_no_memory(c);
        return;
    }
    p->fd = -1;
    struct wl_resource *pr = wl_resource_create(c, &zwp_linux_buffer_params_v1_interface, wl_resource_get_version(r), id);
    if (!pr) {
        free(p);
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(pr, &params_impl, p, params_res_destroy);
}

/* Format table: XRGB/ARGB8888, LINEAR only. The compositor reads buffers through mmap, so a tiled
   or compressed layout (possible with the implicit INVALID modifier) would come out as noise
   형식 표: XRGB/ARGB8888, LINEAR만. 컴포지터가 mmap으로 버퍼를 읽으므로 타일·압축 배치(암시 INVALID modifier면
   가능)는 노이즈로 보이게 됨 */
struct fmt_entry {
    uint32_t format, pad;
    uint64_t modifier;
};
static const struct fmt_entry formats[] = {
    { FMT_XRGB8888, 0, MOD_LINEAR }, { FMT_ARGB8888, 0, MOD_LINEAR },
};

static int format_table_fd(void)
{
    int fd = memfd_create("ezs-wl-formats", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (fd < 0) return -1;
    if (write(fd, formats, sizeof(formats)) != (ssize_t)sizeof(formats)) {
        close(fd);
        return -1;
    }
    fcntl(fd, F_ADD_SEALS, F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_WRITE | F_SEAL_SEAL);
    return fd;
}

static const struct zwp_linux_dmabuf_feedback_v1_interface feedback_impl = { .destroy = res_destroy };

static void send_feedback(struct wl_client *c, struct wl_resource *r, uint32_t id)
{
    struct wl_resource *f = wl_resource_create(c, &zwp_linux_dmabuf_feedback_v1_interface, wl_resource_get_version(r), id);
    if (!f) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(f, &feedback_impl, NULL, NULL);
    int fd = format_table_fd();
    if (fd < 0) {
        wl_client_post_no_memory(c);
        return;
    }
    zwp_linux_dmabuf_feedback_v1_send_format_table(f, fd, sizeof(formats));
    close(fd);
    struct wl_array dev;
    wl_array_init(&dev);
    dev_t *d = wl_array_add(&dev, sizeof(dev_t));
    if (d) *d = rdev;
    zwp_linux_dmabuf_feedback_v1_send_main_device(f, &dev);
    zwp_linux_dmabuf_feedback_v1_send_tranche_target_device(f, &dev);
    wl_array_release(&dev);
    zwp_linux_dmabuf_feedback_v1_send_tranche_flags(f, 0);
    struct wl_array idx;
    wl_array_init(&idx);
    for (uint16_t i = 0; i < sizeof(formats) / sizeof(formats[0]); i++) {
        uint16_t *p = wl_array_add(&idx, sizeof(uint16_t));
        if (p) *p = i;
    }
    zwp_linux_dmabuf_feedback_v1_send_tranche_formats(f, &idx);
    wl_array_release(&idx);
    zwp_linux_dmabuf_feedback_v1_send_tranche_done(f);
    zwp_linux_dmabuf_feedback_v1_send_done(f);
}

static void dm_default_feedback(struct wl_client *c, struct wl_resource *r, uint32_t id)
{
    send_feedback(c, r, id);
}

static void dm_surface_feedback(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *s)
{
    (void)s;
    send_feedback(c, r, id);
}

static const struct zwp_linux_dmabuf_v1_interface dmabuf_impl = {
    .destroy = res_destroy,
    .create_params = dm_create_params,
    .get_default_feedback = dm_default_feedback,
    .get_surface_feedback = dm_surface_feedback,
};

static void bind_dmabuf(struct wl_client *c, void *data, uint32_t ver, uint32_t id)
{
    (void)data;
    struct wl_resource *r = wl_resource_create(c, &zwp_linux_dmabuf_v1_interface, ver, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &dmabuf_impl, NULL, NULL);
    printf("ezs-wl: linux-dmabuf 연결 (주 장치 %u:%u)\n", major(rdev), minor(rdev));
    fflush(stdout);
}

/* ---- frame pacing and status / 프레임 박자와 상태 ---- */

static struct wl_event_source *tick;

static uint32_t now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

/* Frame callbacks are answered on a fixed tick (the display rate), which paces Xwayland
   프레임 콜백은 고정 박자(화면 주사율)로 답함. 이것이 Xwayland의 박자를 맞춤 */
static int on_tick(void *data)
{
    (void)data;
    static uint32_t last_report;
    uint32_t t = now_ms();
    for (int i = 0; i < 64; i++) {
        struct surface *s = surfaces[i];
        if (!s) continue;
        struct wl_resource *cb, *tmp;
        /* done, then destroy: the destroy handler takes the callback out of s->frames
           done을 보낸 뒤 지움: destroy 처리기가 콜백을 s->frames에서 뺌 */
        wl_resource_for_each_safe(cb, tmp, &s->frames)
        {
            wl_callback_send_done(cb, t);
            wl_resource_destroy(cb);
        }
    }
    if (t - last_report >= 60000) {
        if (last_report)
            printf("ezs-wl: 1분 커밋 %ld, 공유 메모리 프레임 %ld, dma-buf 프레임 %ld, 실패 %ld\n", commits, shm_frames,
                   dma_frames, failed_frames);
        fflush(stdout);
        commits = shm_frames = dma_frames = failed_frames = 0;
        last_report = t;
    }
    wl_event_source_timer_update(tick, 1000 / HZ);
    return 0;
}

int main(int argc, char **argv)
{
    const char *sock = NULL, *fbpath = NULL;
    for (int i = 1; i + 1 < argc; i += 2) {
        if (!strcmp(argv[i], "--socket")) sock = argv[i + 1];
        else if (!strcmp(argv[i], "--fb")) fbpath = argv[i + 1];
        else if (!strcmp(argv[i], "--size")) sscanf(argv[i + 1], "%dx%d", &W, &H);
        else if (!strcmp(argv[i], "--hz")) HZ = atoi(argv[i + 1]);
        else if (!strcmp(argv[i], "--rdev")) {
            unsigned ma, mi;
            if (sscanf(argv[i + 1], "%u:%u", &ma, &mi) == 2 && (ma || mi)) {
                rdev = makedev(ma, mi);
                have_rdev = 1;
            }
        }
    }
    if (!sock || !fbpath || W <= 0 || H <= 0 || HZ <= 0) {
        fprintf(stderr, "usage: ezs-wl --socket NAME --fb FILE --size WxH [--rdev MAJ:MIN] [--hz N]\n");
        return 2;
    }
    if (fb_open(fbpath) < 0) {
        printf("ezs-wl: 화면 파일을 만들지 못함 %s (%s)\n", fbpath, strerror(errno));
        return 1;
    }
    dpy = wl_display_create();
    if (!dpy || wl_display_add_socket(dpy, sock) < 0) {
        printf("ezs-wl: 소켓 %s 를 만들지 못함 (%s)\n", sock, strerror(errno));
        return 1;
    }
    wl_display_init_shm(dpy);
    wl_global_create(dpy, &wl_compositor_interface, 4, NULL, bind_compositor);
    wl_global_create(dpy, &wl_output_interface, 3, NULL, bind_output);
    wl_global_create(dpy, &xdg_wm_base_interface, 2, NULL, bind_wm);
    if (have_rdev) wl_global_create(dpy, &zwp_linux_dmabuf_v1_interface, 4, NULL, bind_dmabuf);
    tick = wl_event_loop_add_timer(wl_display_get_event_loop(dpy), on_tick, NULL);
    wl_event_source_timer_update(tick, 1000 / HZ);
    printf("ezs-wl: 시작 %s %dx%d %dHz, dma-buf %s\n", sock, W, H, HZ, have_rdev ? "켬" : "끔");
    fflush(stdout);
    wl_display_run(dpy);
    wl_display_destroy(dpy);
    return 0;
}
