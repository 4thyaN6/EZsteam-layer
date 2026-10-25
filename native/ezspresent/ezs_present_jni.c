/*
 * App side of the game frame path check (libezs_present.so, Android NDK, loaded by
 * app.ezsteam.runtime.PresentProbe). Listens on rootfs + EZP_GUEST_SOCKET; for each connection of
 * ezs-present-probe it answers the steps described in ezs_present_probe.c with its own EGL context
 * on the calling thread (a 16x16 pbuffer, never on screen), then sends back a text report that
 * the probe prints into the Steam log. The same socket and message format are used later by the
 * compositor. Nothing leaves the device.
 * 게임 프레임 경로 확인의 앱 쪽(libezs_present.so, 안드로이드 NDK, app.ezsteam.runtime.PresentProbe가
 * 불러옴). rootfs + EZP_GUEST_SOCKET에서 기다림. ezs-present-probe 연결마다 ezs_present_probe.c에 적은
 * 단계에 답함. 부르는 스레드에서 자기 EGL 컨텍스트(16x16 pbuffer, 화면에 안 나옴)를 씀. 끝나면 글 보고를
 * 돌려주고 probe가 Steam 로그에 찍음. 같은 소켓과 메시지 형식을 나중에 컴포지터가 씀.
 * 기기 밖으로 나가는 데이터 없음
 */
/* accept4 on glibc for local syntax checks; bionic has it anyway
 * 로컬 문법 확인 때 glibc의 accept4용. bionic은 원래 있음 */
#define _GNU_SOURCE
#include <jni.h>

#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include "ezs_present_proto.h"

/* Same result bits as the probe / probe와 같은 결과 비트 */
#define R_A_EGL     0x01u
#define R_B_EGL     0x02u
#define R_VE_FENCE  0x04u
#define R_B_CPU     0x08u

/* Result of check C (layout of a GPU-only AHB that Vulkan reads right), EZP_MOD_INVALID = not
 * known; compression test switch; frame limit for layers (-1 = not set)
 * 확인 C 결과(Vulkan이 맞게 읽는 GPU 전용 AHB 배치), EZP_MOD_INVALID = 모름. 압축 시험 스위치.
 * 레이어용 프레임 제한(-1 = 정하지 않음) */
static volatile uint64_t g_gpu_mod = EZP_MOD_INVALID;
static volatile int g_compress;
static volatile int g_fps_app = -1;

/* ---- report text / 보고 글 ---- */
static char rep[EZP_MAX_PACKET - 128];
static size_t rep_len;

static void say(const char *fmt, ...)
{
    if (rep_len >= sizeof rep - 2) return;
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(rep + rep_len, sizeof rep - rep_len - 1, fmt, ap);
    va_end(ap);
    if (n < 0) return;
    rep_len += (size_t)n < sizeof rep - rep_len - 1 ? (size_t)n : sizeof rep - rep_len - 2;
    rep[rep_len++] = '\n';
    rep[rep_len] = 0;
}

/* ---- socket (same helpers as the probe) / 소켓(probe와 같은 도우미) ---- */
static int send_msg(int s, struct ezp_msg *m, const int *fds, int nfds, const void *extra, size_t extra_len)
{
    m->magic = EZP_MAGIC;
    struct iovec iov[2] = {{m, sizeof *m}, {(void *)extra, extra_len}};
    char cbuf[CMSG_SPACE(sizeof(int) * EZP_MAX_FDS)];
    struct msghdr h;
    memset(&h, 0, sizeof h);
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

static ssize_t recv_msg(int s, struct ezp_msg *m, int *fds, int *nfds)
{
    char pkt[512];
    char cbuf[CMSG_SPACE(sizeof(int) * EZP_MAX_FDS)];
    struct iovec iov = {pkt, sizeof pkt};
    struct msghdr h;
    memset(&h, 0, sizeof h);
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
    if (n < (ssize_t)sizeof *m) return n == 0 ? 0 : -1;
    memcpy(m, pkt, sizeof *m);
    return m->magic == EZP_MAGIC ? n : -1;
}

/* ---- EGL / GL ---- */
static EGLDisplay dpy = EGL_NO_DISPLAY;
static EGLContext ctx = EGL_NO_CONTEXT;
static EGLSurface surf = EGL_NO_SURFACE;
static PFNEGLCREATEIMAGEKHRPROC pCreateImage;
static PFNEGLDESTROYIMAGEKHRPROC pDestroyImage;
static PFNGLEGLIMAGETARGETTEXTURE2DOESPROC pImageTarget;
static PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC pClientBuffer;
static PFNEGLCREATESYNCKHRPROC pCreateSync;
static PFNEGLDESTROYSYNCKHRPROC pDestroySync;
static PFNEGLCLIENTWAITSYNCKHRPROC pClientWait;
static PFNEGLDUPNATIVEFENCEFDANDROIDPROC pDupFence;
static int has_dmabuf_import, has_dmabuf_mod, has_native_fence;
static GLuint ext_prog;

static int has_ext(const char *list, const char *name)
{
    if (!list) return 0;
    size_t l = strlen(name);
    for (const char *p = list; (p = strstr(p, name)); p += l)
        if ((p == list || p[-1] == ' ') && (p[l] == ' ' || p[l] == 0)) return 1;
    return 0;
}

static int egl_open(void)
{
    dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLint ma = 0, mi = 0;
    if (dpy == EGL_NO_DISPLAY || !eglInitialize(dpy, &ma, &mi)) {
        say("EGL 초기화 실패 (0x%x)", eglGetError());
        return -1;
    }
    const EGLint ca[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RED_SIZE, 8,
                         EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
    EGLConfig cfg;
    EGLint nc = 0;
    if (!eglChooseConfig(dpy, ca, &cfg, 1, &nc) || nc < 1) {
        say("EGL 설정 없음 (0x%x)", eglGetError());
        return -1;
    }
    const EGLint cx[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, cx);
    const EGLint pa[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
    surf = eglCreatePbufferSurface(dpy, cfg, pa);
    if (ctx == EGL_NO_CONTEXT || surf == EGL_NO_SURFACE || !eglMakeCurrent(dpy, surf, surf, ctx)) {
        say("EGL 컨텍스트 실패 (0x%x)", eglGetError());
        return -1;
    }
    const char *ee = eglQueryString(dpy, EGL_EXTENSIONS);
    const char *ge = (const char *)glGetString(GL_EXTENSIONS);
    has_dmabuf_import = has_ext(ee, "EGL_EXT_image_dma_buf_import");
    has_dmabuf_mod = has_ext(ee, "EGL_EXT_image_dma_buf_import_modifiers");
    has_native_fence = has_ext(ee, "EGL_ANDROID_native_fence_sync");
    say("EGL %d.%d, GL %s", ma, mi, (const char *)glGetString(GL_RENDERER));
    say("EGL 확장 dma_buf_import=%s dma_buf_import_modifiers=%s get_native_client_buffer=%s "
        "image_native_buffer=%s native_fence_sync=%s wait_sync=%s",
        has_dmabuf_import ? "있음" : "없음", has_dmabuf_mod ? "있음" : "없음",
        has_ext(ee, "EGL_ANDROID_get_native_client_buffer") ? "있음" : "없음",
        has_ext(ee, "EGL_ANDROID_image_native_buffer") ? "있음" : "없음", has_native_fence ? "있음" : "없음",
        has_ext(ee, "EGL_KHR_wait_sync") ? "있음" : "없음");
    say("GL 확장 OES_EGL_image=%s OES_EGL_image_external=%s", has_ext(ge, "GL_OES_EGL_image") ? "있음" : "없음",
        has_ext(ge, "GL_OES_EGL_image_external") ? "있음" : "없음");
    pCreateImage = (PFNEGLCREATEIMAGEKHRPROC)eglGetProcAddress("eglCreateImageKHR");
    pDestroyImage = (PFNEGLDESTROYIMAGEKHRPROC)eglGetProcAddress("eglDestroyImageKHR");
    pImageTarget = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)eglGetProcAddress("glEGLImageTargetTexture2DOES");
    pClientBuffer = (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC)eglGetProcAddress("eglGetNativeClientBufferANDROID");
    pCreateSync = (PFNEGLCREATESYNCKHRPROC)eglGetProcAddress("eglCreateSyncKHR");
    pDestroySync = (PFNEGLDESTROYSYNCKHRPROC)eglGetProcAddress("eglDestroySyncKHR");
    pClientWait = (PFNEGLCLIENTWAITSYNCKHRPROC)eglGetProcAddress("eglClientWaitSyncKHR");
    pDupFence = (PFNEGLDUPNATIVEFENCEFDANDROIDPROC)eglGetProcAddress("eglDupNativeFenceFDANDROID");
    return 0;
}

static void egl_close(void)
{
    if (ext_prog) glDeleteProgram(ext_prog);
    ext_prog = 0;
    if (dpy != EGL_NO_DISPLAY) {
        eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (surf != EGL_NO_SURFACE) eglDestroySurface(dpy, surf);
        if (ctx != EGL_NO_CONTEXT) eglDestroyContext(dpy, ctx);
    }
    surf = EGL_NO_SURFACE;
    ctx = EGL_NO_CONTEXT;
    dpy = EGL_NO_DISPLAY;
}

/* Wait on an Android sync fd through EGL; EGL takes the fd on success
 * 안드로이드 sync fd를 EGL로 기다림. 성공하면 fd는 EGL 것이 됨 */
static int wait_fence(int fd, const char *what)
{
    if (fd < 0) return 0;
    if (!has_native_fence || !pCreateSync || !pClientWait) {
        say("%s: sync fd 받았으나 EGL native_fence_sync 없음", what);
        close(fd);
        return 0;
    }
    const EGLint a[] = {EGL_SYNC_NATIVE_FENCE_FD_ANDROID, fd, EGL_NONE};
    EGLSyncKHR s = pCreateSync(dpy, EGL_SYNC_NATIVE_FENCE_ANDROID, a);
    if (s == EGL_NO_SYNC_KHR) {
        say("%s: sync fd를 EGL 펜스로 만들기 실패 (0x%x)", what, eglGetError());
        close(fd);
        return 0;
    }
    EGLint r = pClientWait(dpy, s, EGL_SYNC_FLUSH_COMMANDS_BIT_KHR, 2000000000ll);
    pDestroySync(dpy, s);
    say("%s: Vulkan sync fd EGL 대기 %s", what, r == EGL_CONDITION_SATISFIED_KHR ? "성공" : "실패/시간 초과");
    return r == EGL_CONDITION_SATISFIED_KHR;
}

static GLuint shader(GLenum t, const char *src)
{
    GLuint s = glCreateShader(t);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    return s;
}

/* Draw an external-OES texture into the bound framebuffer (for images GL can't attach directly)
 * external-OES 텍스처를 묶인 프레임버퍼에 그림(GL이 바로 붙이지 못하는 이미지용) */
static void draw_external(GLuint tex)
{
    if (!ext_prog) {
        GLuint v = shader(GL_VERTEX_SHADER, "attribute vec2 p; varying vec2 t;"
                                            "void main(){ t = p * 0.5 + 0.5; gl_Position = vec4(p, 0.0, 1.0); }");
        GLuint f = shader(GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\n"
                                              "precision mediump float; varying vec2 t; uniform samplerExternalOES s;"
                                              "void main(){ gl_FragColor = texture2D(s, t); }");
        ext_prog = glCreateProgram();
        glAttachShader(ext_prog, v);
        glAttachShader(ext_prog, f);
        glBindAttribLocation(ext_prog, 0, "p");
        glLinkProgram(ext_prog);
        glDeleteShader(v);
        glDeleteShader(f);
    }
    static const GLfloat q[] = {-1, -1, 1, -1, -1, 1, 1, 1};
    glUseProgram(ext_prog);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex);
    glUniform1i(glGetUniformLocation(ext_prog, "s"), 0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, q);
    glEnableVertexAttribArray(0);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

static int px_match(const uint8_t *p, uint32_t pat)
{
    for (int ch = 0; ch < 4; ch++) {
        int a = p[ch], b = (int)((pat >> (8 * ch)) & 0xff);
        if (a - b > 1 || b - a > 1) return 0;
    }
    return 1;
}

/* Read an EGLImage back through GL and compare with pat: first as a 2D texture attached to a
 * framebuffer (what the compositor would sample), else drawn as external-OES
 * EGLImage를 GL로 다시 읽어 pat과 비교함: 먼저 프레임버퍼에 붙인 2D 텍스처(컴포지터가 쓸 방식), 안 되면
 * external-OES로 그림 */
static int read_image(EGLImageKHR img, int w, int h, uint32_t pat, const char *what)
{
    if (!pImageTarget) {
        say("%s: glEGLImageTargetTexture2DOES 없음", what);
        return 0;
    }
    GLuint tex, fbo, out = 0;
    uint8_t px[3][4];
    memset(px, 0, sizeof px);
    glGenTextures(1, &tex);
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    while (glGetError() != GL_NO_ERROR) {
    }
    glBindTexture(GL_TEXTURE_2D, tex);
    pImageTarget(GL_TEXTURE_2D, (GLeglImageOES)img);
    int how = 0;
    if (glGetError() == GL_NO_ERROR) {
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) how = 1;
    }
    if (!how) {
        /* External path: sample into a plain RGBA texture / external 경로: 일반 RGBA 텍스처로 그림 */
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex);
        while (glGetError() != GL_NO_ERROR) {
        }
        pImageTarget(GL_TEXTURE_EXTERNAL_OES, (GLeglImageOES)img);
        if (glGetError() == GL_NO_ERROR) {
            glGenTextures(1, &out);
            glBindTexture(GL_TEXTURE_2D, out);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, out, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
                glViewport(0, 0, w, h);
                draw_external(tex);
                how = 2;
            }
        }
    }
    int ok = 0;
    if (how) {
        glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px[0]);
        glReadPixels(w / 2, h / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px[1]);
        glReadPixels(w - 1, h - 1, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px[2]);
        ok = px_match(px[0], pat) && px_match(px[1], pat) && px_match(px[2], pat);
        say("%s: GL 읽기(%s) %s (%02x%02x%02x%02x, 기대 %08x)", what, how == 1 ? "2D 텍스처" : "external",
            ok ? "일치" : "불일치", px[1][3], px[1][2], px[1][1], px[1][0], pat);
    } else {
        say("%s: GL에서 이미지를 텍스처로 쓰지 못함 (0x%x)", what, glGetError());
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &tex);
    if (out) glDeleteTextures(1, &out);
    return ok;
}

/* A: Vulkan-exported dma-buf through EGL_EXT_image_dma_buf_import / A: Vulkan이 내보낸 dma-buf를 EGL로 */
static uint32_t on_a(const struct ezp_msg *m, int *fds, int nfds)
{
    uint32_t res = 0;
    int sfd = (m->flags & 1u) && nfds > 1 ? fds[1] : -1;
    if (sfd >= 0) {
        if (wait_fence(sfd, "A")) res |= R_VE_FENCE;
    } else {
        usleep(300000); /* no fence: give the GPU time / 펜스 없음: GPU에 시간을 줌 */
    }
    if (nfds < 1) {
        say("A: dma-buf 디스크립터를 받지 못함 (PRoot 전달 문제 가능)");
        return res;
    }
    say("A: dma-buf 받음 %ux%u, 줄 간격 %u, modifier 0x%llx", m->w, m->h, m->stride,
        (unsigned long long)m->modifier);
    if (!has_dmabuf_import || !pCreateImage) {
        say("A: EGL dma-buf 가져오기 없음");
        close(fds[0]);
        return res;
    }
    EGLint a[32];
    int k = 0;
    a[k++] = EGL_WIDTH; a[k++] = (EGLint)m->w;
    a[k++] = EGL_HEIGHT; a[k++] = (EGLint)m->h;
    a[k++] = EGL_LINUX_DRM_FOURCC_EXT; a[k++] = (EGLint)m->fourcc;
    a[k++] = EGL_DMA_BUF_PLANE0_FD_EXT; a[k++] = fds[0];
    a[k++] = EGL_DMA_BUF_PLANE0_OFFSET_EXT; a[k++] = (EGLint)m->offset;
    a[k++] = EGL_DMA_BUF_PLANE0_PITCH_EXT; a[k++] = (EGLint)m->stride;
    if (has_dmabuf_mod && m->modifier != EZP_MOD_INVALID) {
        a[k++] = EGL_DMA_BUF_PLANE0_MODIFIER_LO_EXT; a[k++] = (EGLint)(m->modifier & 0xffffffffu);
        a[k++] = EGL_DMA_BUF_PLANE0_MODIFIER_HI_EXT; a[k++] = (EGLint)(m->modifier >> 32);
    }
    a[k++] = EGL_NONE;
    EGLImageKHR img = pCreateImage(dpy, EGL_NO_CONTEXT, EGL_LINUX_DMA_BUF_EXT, (EGLClientBuffer)NULL, a);
    /* EGL keeps its own reference / EGL은 자기 참조를 따로 가짐 */
    close(fds[0]);
    if (img == EGL_NO_IMAGE_KHR) {
        say("A: EGL dma-buf 이미지 만들기 실패 (0x%x)", eglGetError());
        return res;
    }
    if (read_image(img, (int)m->w, (int)m->h, m->pattern, "A")) res |= R_A_EGL;
    pDestroyImage(dpy, img);
    return res;
}

/* B: the planned path. Allocate the AHB, CPU-write the probe's pattern, send it
 * B: 계획한 경로. AHB를 만들고 probe의 무늬를 CPU로 쓰고 보냄 */
static AHardwareBuffer *on_b_req(int s, const struct ezp_msg *m)
{
    AHardwareBuffer_Desc d;
    memset(&d, 0, sizeof d);
    d.width = m->w;
    d.height = m->h;
    d.layers = 1;
    d.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    /* GPU write (game) + GPU sample (compositor). The CPU flags are only for this check; the real
     * path will try without them (they may force an uncompressed layout)
     * GPU 쓰기(게임) + GPU 읽기(컴포지터). CPU 플래그는 이 확인용. 실제 경로는 빼고 시험할 예정
     * (압축 없는 배치를 강제할 수 있음) */
    d.usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
              AHARDWAREBUFFER_USAGE_CPU_READ_RARELY | AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY;
    AHardwareBuffer *ahb = NULL;
    int r = AHardwareBuffer_allocate(&d, &ahb);
    struct ezp_msg h;
    memset(&h, 0, sizeof h);
    h.type = EZP_B_BUF;
    if (r != 0 || !ahb) {
        say("B: AHardwareBuffer 만들기 실패 (%d)", r);
        send_msg(s, &h, NULL, 0, NULL, 0);
        return NULL;
    }
    AHardwareBuffer_Desc got;
    AHardwareBuffer_describe(ahb, &got);
    void *p = NULL;
    r = AHardwareBuffer_lock(ahb, AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY, -1, NULL, &p);
    if (r == 0 && p) {
        for (uint32_t y = 0; y < got.height; y++) {
            uint32_t *row = (uint32_t *)p + (size_t)y * got.stride;
            for (uint32_t x = 0; x < got.width; x++) row[x] = m->pattern;
        }
        AHardwareBuffer_unlock(ahb, NULL);
    } else {
        say("B: AHB CPU 쓰기 잠금 실패 (%d)", r);
    }
    say("B: AHB 만듦 %ux%u, 줄 간격 %u픽셀", got.width, got.height, got.stride);
    h.w = got.width;
    h.h = got.height;
    h.stride = got.stride * 4;
    h.fourcc = EZP_FOURCC_ABGR8888;
    h.pattern = m->pattern;
    h.flags = 1;
    send_msg(s, &h, NULL, 0, NULL, 0);
    /* Public NDK call that sends the buffer's descriptors over the socket
     * 버퍼 디스크립터를 소켓으로 보내는 공개 NDK 호출 */
    r = AHardwareBuffer_sendHandleToUnixSocket(ahb, s);
    if (r != 0) say("B: AHB 핸들 보내기 실패 (%d)", r);
    return ahb;
}

/* B done: the probe's Vulkan drew PAT_B2 into the AHB; show it through EGL like the compositor
 * B 끝: probe의 Vulkan이 AHB에 PAT_B2를 그렸음. 컴포지터처럼 EGL로 보여 줌 */
static uint32_t on_b_done(const struct ezp_msg *m, int *fds, int nfds, AHardwareBuffer *ahb)
{
    uint32_t res = 0;
    if (m->r0 == 1 || !ahb) {
        for (int i = 0; i < nfds; i++) close(fds[i]);
        say("B: Vulkan 쪽이 그리지 못함 — 표시 시험 건너뜀");
        return 0;
    }
    if ((m->flags & 1u) && nfds > 0) {
        if (wait_fence(fds[0], "B")) res |= R_VE_FENCE;
    } else {
        usleep(300000);
    }
    if (pClientBuffer && pCreateImage) {
        EGLClientBuffer cb = pClientBuffer(ahb);
        const EGLint a[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
        EGLImageKHR img = cb ? pCreateImage(dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, cb, a) : EGL_NO_IMAGE_KHR;
        if (img == EGL_NO_IMAGE_KHR) {
            say("B: AHB EGL 이미지 만들기 실패 (0x%x)", eglGetError());
        } else {
            if (read_image(img, (int)m->w, (int)m->h, m->pattern, "B")) res |= R_B_EGL;
            pDestroyImage(dpy, img);
        }
    } else {
        say("B: eglGetNativeClientBufferANDROID 없음");
    }
    void *p = NULL;
    if (AHardwareBuffer_lock(ahb, AHARDWAREBUFFER_USAGE_CPU_READ_RARELY, -1, NULL, &p) == 0 && p) {
        AHardwareBuffer_Desc d;
        AHardwareBuffer_describe(ahb, &d);
        const uint8_t *c = (const uint8_t *)((uint32_t *)p + (size_t)(d.height / 2) * d.stride + d.width / 2);
        int ok = px_match(c, m->pattern);
        say("B: CPU 읽기 %s", ok ? "일치" : "불일치");
        if (ok) res |= R_B_CPU;
        AHardwareBuffer_unlock(ahb, NULL);
    }
    return res;
}

/* E: GL fence for Vulkan to wait on / E: Vulkan이 기다릴 GL 펜스 */
static void send_e(int s)
{
    int fd = -1;
    if (has_native_fence && pCreateSync && pDupFence) {
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        EGLSyncKHR sy = pCreateSync(dpy, EGL_SYNC_NATIVE_FENCE_ANDROID, NULL);
        glFlush();
        if (sy != EGL_NO_SYNC_KHR) {
            fd = pDupFence(dpy, sy);
            pDestroySync(dpy, sy);
        }
        if (fd < 0) say("E: GL 펜스 fd 만들기 실패 (0x%x)", eglGetError());
    } else {
        say("E: EGL native_fence_sync 없음");
    }
    struct ezp_msg m;
    memset(&m, 0, sizeof m);
    m.type = EZP_E_FENCE;
    int f[1] = {fd};
    send_msg(s, &m, f, 1, NULL, 0);
    if (fd >= 0) close(fd);
}

/* C: GPU-only AHB (no CPU usage bits), cleared with GL to the probe's colour, then sent
 * C: GPU 전용 AHB(CPU 용도 비트 없음)를 probe 색으로 GL에서 칠한 뒤 보냄 */
static AHardwareBuffer *on_c_req(int s, const struct ezp_msg *m)
{
    struct ezp_msg h;
    memset(&h, 0, sizeof h);
    h.type = EZP_C_BUF;
    AHardwareBuffer_Desc d;
    memset(&d, 0, sizeof d);
    d.width = m->w;
    d.height = m->h;
    d.layers = 1;
    d.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    d.usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
    AHardwareBuffer *ahb = NULL;
    int ok = ctx != EGL_NO_CONTEXT && pClientBuffer && pCreateImage && pImageTarget &&
             AHardwareBuffer_allocate(&d, &ahb) == 0 && ahb;
    if (ok) {
        EGLClientBuffer cb = pClientBuffer(ahb);
        const EGLint a[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
        EGLImageKHR img = cb ? pCreateImage(dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, cb, a) : EGL_NO_IMAGE_KHR;
        ok = img != EGL_NO_IMAGE_KHR;
        if (ok) {
            GLuint tex, fbo;
            glGenTextures(1, &tex);
            glBindTexture(GL_TEXTURE_2D, tex);
            pImageTarget(GL_TEXTURE_2D, (GLeglImageOES)img);
            glGenFramebuffers(1, &fbo);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
            ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
            if (ok) {
                glViewport(0, 0, (GLsizei)m->w, (GLsizei)m->h);
                glClearColor((m->pattern & 0xff) / 255.0f, ((m->pattern >> 8) & 0xff) / 255.0f,
                             ((m->pattern >> 16) & 0xff) / 255.0f, ((m->pattern >> 24) & 0xff) / 255.0f);
                glClear(GL_COLOR_BUFFER_BIT);
                glFinish();
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(1, &fbo);
            glDeleteTextures(1, &tex);
            pDestroyImage(dpy, img);
        }
    }
    if (!ok) {
        say("C: GPU 전용 AHB 만들기/칠하기 실패");
        send_msg(s, &h, NULL, 0, NULL, 0);
        if (ahb) AHardwareBuffer_release(ahb);
        return NULL;
    }
    AHardwareBuffer_Desc got;
    AHardwareBuffer_describe(ahb, &got);
    h.w = got.width;
    h.h = got.height;
    h.stride = got.stride * 4;
    h.flags = 1;
    say("C: GPU 전용 AHB 만듦, 줄 간격 %u픽셀, GL로 칠함", got.stride);
    send_msg(s, &h, NULL, 0, NULL, 0);
    AHardwareBuffer_sendHandleToUnixSocket(ahb, s);
    return ahb;
}

/* One probe connection; its first message was already read by the caller
 * probe 연결 하나. 첫 메시지는 부른 쪽이 이미 읽었음 */
static uint32_t session(int s, const struct ezp_msg *first)
{
    uint32_t res = 0;
    AHardwareBuffer *ahb = NULL, *cahb = NULL;
    if (egl_open() != 0) {
        /* Still answer, so the probe does not wait / 그래도 답해 probe가 기다리지 않게 함 */
    }
    int done = 0;
    int use_first = first != NULL;
    while (!done) {
        struct ezp_msg m;
        int fds[EZP_MAX_FDS], nfds = 0;
        ssize_t n;
        if (use_first) {
            m = *first;
            n = (ssize_t)sizeof m;
            use_first = 0;
        } else {
            n = recv_msg(s, &m, fds, &nfds);
        }
        if (n <= 0) {
            say("소켓: probe 메시지 받기 끝 (%s)", n == 0 ? "연결 닫힘" : strerror(errno));
            for (int i = 0; i < nfds; i++) close(fds[i]);
            break;
        }
        switch (m.type) {
        case EZP_HELLO:
            say("probe 연결, 형식 %u", m.w);
            break;
        case EZP_A_BUF:
            if (ctx != EGL_NO_CONTEXT) res |= on_a(&m, fds, nfds);
            else for (int i = 0; i < nfds; i++) close(fds[i]);
            break;
        case EZP_B_REQ:
            ahb = on_b_req(s, &m);
            break;
        case EZP_B_DONE:
            if (ctx != EGL_NO_CONTEXT) {
                res |= on_b_done(&m, fds, nfds, ahb);
            } else {
                for (int i = 0; i < nfds; i++) close(fds[i]);
                say("B: EGL 컨텍스트 없음 — 표시 시험 건너뜀");
            }
            send_e(s);
            break;
        case EZP_E_FENCE_DONE:
            say("E: Vulkan이 GL 펜스 기다림 %s", m.flags ? "성공" : "실패");
            break;
        case EZP_C_REQ:
            if (cahb) AHardwareBuffer_release(cahb);
            cahb = on_c_req(s, &m);
            break;
        case EZP_C_DONE:
            g_gpu_mod = m.modifier;
            say("C: GPU 전용 AHB 배치 %s (linear %s, 압축 %s)",
                m.modifier == EZP_MOD_INVALID ? "모름" : m.modifier == EZP_MOD_QCOM_COMPRESSED ? "Qualcomm 압축" : "linear",
                (m.flags & 1u) ? "맞음" : "안 맞음", (m.flags & 2u) ? "맞음" : "안 맞음");
            done = 1;
            break;
        default:
            for (int i = 0; i < nfds; i++) close(fds[i]);
            say("소켓: 알 수 없는 메시지 %u", m.type);
            break;
        }
    }
    if (ahb) AHardwareBuffer_release(ahb);
    if (cahb) AHardwareBuffer_release(cahb);
    egl_close();
    return res;
}


/* ===================== compositor side of the game frame path =====================
 * ===================== 게임 프레임 경로의 컴포지터 쪽 =====================
 * One thread per present-layer connection owns the socket reads; the GL thread of the view
 * (FramebufferView) takes the newest frame each display refresh, waits for its GPU fence on the
 * GPU (eglWaitSyncKHR, no CPU wait), samples it, and hands the previous one back with a GL fence.
 * Buffers: AHardwareBuffer RGBA8 with CPU usage bits so gralloc keeps them linear (the layout the
 * probe confirmed with Vulkan); the game writes them, the compositor only reads.
 * present 레이어 연결마다 스레드 하나가 소켓 읽기를 맡음. 뷰(FramebufferView)의 GL 스레드는 화면 갱신마다
 * 가장 새 프레임을 가져와 GPU 펜스를 GPU에서 기다리고(eglWaitSyncKHR, CPU 대기 없음) 샘플한 뒤, 이전 것을
 * GL 펜스와 함께 돌려줌. 버퍼: CPU 용도 비트를 넣은 AHardwareBuffer RGBA8(gralloc이 linear로 유지, probe가
 * Vulkan으로 확인한 배치). 게임이 쓰고 컴포지터는 읽기만 함
 */
#include <GLES3/gl3.h>
#include <pthread.h>

#define MAX_SC 8
struct csc {
    int used, alive, sock, id;
    uint32_t w, h, count, order, window, pid;
    AHardwareBuffer *ahb[EZP_MAX_BUFFERS];
    EGLImageKHR img[EZP_MAX_BUFFERS];
    GLuint tex[EZP_MAX_BUFFERS];
    int queued, queued_fence, shown, to_release;
    int64_t last_ns;
    int rx, ry, rw, rh; /* window rect in X screen pixels, rw 0 = unknown / X 화면 화소 창 위치, rw 0 = 모름 */
    uint64_t frames, shown_frames;
    pthread_mutex_t wmu; /* socket writes / 소켓 쓰기 */
};
static struct csc g_sc[MAX_SC];
static pthread_mutex_t g_cmu = PTHREAD_MUTEX_INITIALIZER;
static int g_next_id = 1;
/* GL-thread state / GL 스레드 상태 */
static int g_cur = -1; /* slot shown last frame / 지난 프레임에 보인 슬롯 */
static PFNEGLCREATEIMAGEKHRPROC cCreateImage;
static PFNEGLDESTROYIMAGEKHRPROC cDestroyImage;
static PFNGLEGLIMAGETARGETTEXTURE2DOESPROC cImageTarget;
static PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC cClientBuffer;
static PFNEGLCREATESYNCKHRPROC cCreateSync;
static PFNEGLDESTROYSYNCKHRPROC cDestroySync;
static PFNEGLWAITSYNCKHRPROC cWaitSync;
static PFNEGLCLIENTWAITSYNCKHRPROC cClientWait;
static PFNEGLDUPNATIVEFENCEFDANDROIDPROC cDupFence;

static int64_t mono_ns(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t)t.tv_sec * 1000000000ll + t.tv_nsec;
}

static void sc_send(struct csc *c, uint32_t type, uint32_t idx, int fd)
{
    struct ezp_msg m;
    memset(&m, 0, sizeof m);
    m.type = type;
    m.r0 = idx;
    m.flags = fd >= 0 ? 1u : 0u;
    int f[1] = {fd};
    pthread_mutex_lock(&c->wmu);
    send_msg(c->sock, &m, f, 1, NULL, 0);
    pthread_mutex_unlock(&c->wmu);
}

/* Reader thread of one layer connection / 레이어 연결 하나의 읽기 스레드 */
static void *sc_reader(void *arg)
{
    struct csc *c = arg;
    struct timeval tv = {0, 0};
    setsockopt(c->sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    for (;;) {
        struct ezp_msg m;
        int fds[EZP_MAX_FDS], nf = 0;
        ssize_t n = recv_msg(c->sock, &m, fds, &nf);
        if (n <= 0) {
            for (int i = 0; i < nf; i++) close(fds[i]);
            break;
        }
        int fence = (m.flags & 1u) && nf > 0 ? fds[0] : -1;
        for (int i = fence >= 0 ? 1 : 0; i < nf; i++) close(fds[i]);
        if (m.type != EZP_PRESENT || m.r0 >= c->count) {
            if (fence >= 0) close(fence);
            continue;
        }
        pthread_mutex_lock(&g_cmu);
        int drop = c->queued;
        int drop_fence = c->queued_fence;
        c->queued = (int)m.r0;
        c->queued_fence = fence;
        c->last_ns = mono_ns();
        c->frames++;
        pthread_mutex_unlock(&g_cmu);
        /* A newer frame came before the old one was shown: give the old one back at once
         * (mailbox: no waiting for the game, no tearing) / 이전 것이 보이기 전에 새 프레임이 옴: 이전 것을
         * 바로 돌려줌(mailbox: 게임은 기다리지 않고 찢김도 없음) */
        if (drop >= 0) {
            if (drop_fence >= 0) close(drop_fence);
            sc_send(c, EZP_RELEASE, (uint32_t)drop, -1);
        }
    }
    pthread_mutex_lock(&g_cmu);
    c->alive = 0;
    if (c->queued_fence >= 0) close(c->queued_fence);
    c->queued_fence = -1;
    c->queued = -1;
    pthread_mutex_unlock(&g_cmu);
    return NULL;
}

/* New layer connection: allocate and send the buffers, start its reader
 * 새 레이어 연결: 버퍼를 만들어 보내고 읽기 스레드를 시작함 */
static void comp_start(int s, const struct ezp_msg *m, char *line, size_t cap)
{
    uint32_t n = m->pattern;
    if (n < 1 || n > EZP_MAX_BUFFERS || m->w == 0 || m->h == 0 || m->w > 8192 || m->h > 8192) {
        snprintf(line, cap, "E 잘못된 swapchain 요청 %ux%u 버퍼 %u", m->w, m->h, n);
        struct ezp_msg f;
        memset(&f, 0, sizeof f);
        f.type = EZP_SC_BUF;
        send_msg(s, &f, NULL, 0, NULL, 0);
        close(s);
        return;
    }
    pthread_mutex_lock(&g_cmu);
    struct csc *c = NULL;
    for (int i = 0; i < MAX_SC; i++)
        if (!g_sc[i].used) { c = &g_sc[i]; break; }
    if (c) {
        memset(c, 0, sizeof *c);
        c->used = 1;
        c->id = g_next_id++;
        c->queued = c->shown = c->to_release = -1;
        c->queued_fence = -1;
    }
    pthread_mutex_unlock(&g_cmu);
    if (!c) {
        snprintf(line, cap, "E swapchain 자리 없음");
        struct ezp_msg f;
        memset(&f, 0, sizeof f);
        f.type = EZP_SC_BUF;
        send_msg(s, &f, NULL, 0, NULL, 0);
        close(s);
        return;
    }
    c->sock = s;
    c->w = m->w;
    c->h = m->h;
    c->order = m->flags;
    c->window = m->r0;
    c->pid = m->r1;
    pthread_mutex_init(&c->wmu, NULL);
    uint32_t stride = 0;
    int ok = 1;
    /* Which step failed, for the log / 어느 단계가 실패했는지(로그용) */
    char why[96] = "";
    for (uint32_t i = 0; i < n && ok; i++) {
        AHardwareBuffer_Desc d;
        memset(&d, 0, sizeof d);
        d.width = m->w;
        d.height = m->h;
        d.layers = 1;
        d.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        /* GPU write + sample; CPU bits keep gralloc's layout linear (what the probe verified)
         * GPU 쓰기 + 샘플. CPU 비트는 gralloc 배치를 linear로 유지(probe가 확인한 것) */
        /* Compression test switch: GPU-only buffers in the layout check C found (Qualcomm
         * compressed or linear); otherwise CPU bits that keep it linear
         * 압축 시험 스위치: 확인 C가 찾은 배치(Qualcomm 압축 또는 linear)로 GPU 전용 버퍼. 아니면 linear를
         * 유지하는 CPU 비트 */
        uint64_t mod = g_gpu_mod;
        int gpu_only = g_compress && mod != EZP_MOD_INVALID;
        d.usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
        if (!gpu_only) d.usage |= AHARDWAREBUFFER_USAGE_CPU_READ_RARELY | AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY;
        struct ezp_msg h;
        memset(&h, 0, sizeof h);
        h.type = EZP_SC_BUF;
        h.r0 = i;
        h.modifier = gpu_only ? mod : EZP_MOD_LINEAR;
        int ar = AHardwareBuffer_allocate(&d, &c->ahb[i]);
        if (ar != 0 || !c->ahb[i]) {
            ok = 0;
            snprintf(why, sizeof why, "버퍼 %u 만들기 (%d)", i, ar);
            send_msg(s, &h, NULL, 0, NULL, 0);
            break;
        }
        AHardwareBuffer_Desc got;
        AHardwareBuffer_describe(c->ahb[i], &got);
        stride = got.stride * 4;
        h.stride = stride;
        h.w = got.width;
        h.h = got.height;
        h.flags = 1;
        if (send_msg(s, &h, NULL, 0, NULL, 0) != 0) {
            ok = 0;
            snprintf(why, sizeof why, "버퍼 %u 머리 보내기 (%s)", i, strerror(errno));
        } else {
            int sr = AHardwareBuffer_sendHandleToUnixSocket(c->ahb[i], s);
            if (sr != 0) {
                ok = 0;
                snprintf(why, sizeof why, "버퍼 %u 핸들 보내기 (%d)", i, sr);
            }
        }
        c->count = i + 1;
    }
    if (!ok) {
        snprintf(line, cap, "E %ux%u: %s 실패 (레이어가 끊었으면 레이어 쪽 로그 참고)", m->w, m->h, why);
        for (uint32_t i = 0; i < EZP_MAX_BUFFERS; i++)
            if (c->ahb[i]) AHardwareBuffer_release(c->ahb[i]);
        close(s);
        pthread_mutex_lock(&g_cmu);
        c->used = 0;
        pthread_mutex_unlock(&g_cmu);
        return;
    }
    c->alive = 1;
    pthread_t t;
    pthread_attr_t at;
    pthread_attr_init(&at);
    pthread_attr_setdetachstate(&at, PTHREAD_CREATE_DETACHED);
    if (pthread_create(&t, &at, sc_reader, c) != 0) {
        c->alive = 0;
    }
    pthread_attr_destroy(&at);
    /* Current frame limit for the new swapchain / 새 swapchain에 지금 프레임 제한 */
    if (g_fps_app >= 0) {
        struct ezp_msg f;
        memset(&f, 0, sizeof f);
        f.type = EZP_SET_FPS;
        f.pattern = (uint32_t)g_fps_app;
        pthread_mutex_lock(&c->wmu);
        send_msg(s, &f, NULL, 0, NULL, 0);
        pthread_mutex_unlock(&c->wmu);
    }
    snprintf(line, cap, "S %d %u %u %u %u %u %u %u %s", c->id, c->w, c->h, c->count, m->fourcc, c->window, c->pid,
             stride, g_compress && g_gpu_mod == EZP_MOD_QCOM_COMPRESSED ? "압축" : "linear");
}

static void gl_procs(void)
{
    if (cCreateImage) return;
    cCreateImage = (PFNEGLCREATEIMAGEKHRPROC)eglGetProcAddress("eglCreateImageKHR");
    cDestroyImage = (PFNEGLDESTROYIMAGEKHRPROC)eglGetProcAddress("eglDestroyImageKHR");
    cImageTarget = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)eglGetProcAddress("glEGLImageTargetTexture2DOES");
    cClientBuffer = (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC)eglGetProcAddress("eglGetNativeClientBufferANDROID");
    cCreateSync = (PFNEGLCREATESYNCKHRPROC)eglGetProcAddress("eglCreateSyncKHR");
    cDestroySync = (PFNEGLDESTROYSYNCKHRPROC)eglGetProcAddress("eglDestroySyncKHR");
    cWaitSync = (PFNEGLWAITSYNCKHRPROC)eglGetProcAddress("eglWaitSyncKHR");
    cClientWait = (PFNEGLCLIENTWAITSYNCKHRPROC)eglGetProcAddress("eglClientWaitSyncKHR");
    cDupFence = (PFNEGLDUPNATIVEFENCEFDANDROIDPROC)eglGetProcAddress("eglDupNativeFenceFDANDROID");
}

/* Free the GL side and the buffers of a finished connection (GL thread)
 * 끝난 연결의 GL 쪽과 버퍼를 놓음(GL 스레드) */
static void sc_cleanup(struct csc *c, EGLDisplay dpy_)
{
    for (uint32_t i = 0; i < EZP_MAX_BUFFERS; i++) {
        if (c->tex[i]) glDeleteTextures(1, &c->tex[i]);
        if (c->img[i] && cDestroyImage) cDestroyImage(dpy_, c->img[i]);
        if (c->ahb[i]) AHardwareBuffer_release(c->ahb[i]);
        c->tex[i] = 0;
        c->img[i] = NULL;
        c->ahb[i] = NULL;
    }
    close(c->sock);
    pthread_mutex_destroy(&c->wmu);
    c->used = 0;
}

/* Texture of one buffer, made once (GL thread) / 버퍼 하나의 텍스처, 한 번만 만듦(GL 스레드) */
static GLuint sc_texture(struct csc *c, int i, EGLDisplay d)
{
    if (c->tex[i]) return c->tex[i];
    if (!cCreateImage || !cImageTarget || !cClientBuffer) return 0;
    if (!c->img[i]) {
        EGLClientBuffer cb = cClientBuffer(c->ahb[i]);
        const EGLint a[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
        c->img[i] = cb ? cCreateImage(d, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, cb, a) : EGL_NO_IMAGE_KHR;
        if (c->img[i] == EGL_NO_IMAGE_KHR) {
            c->img[i] = NULL;
            return 0;
        }
    }
    GLuint t;
    glGenTextures(1, &t);
    glBindTexture(GL_TEXTURE_2D, t);
    cImageTarget(GL_TEXTURE_2D, (GLeglImageOES)c->img[i]);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    /* The view's shaders read colours as .bgr (Xvfb's B G R X bytes). A game frame in B G R A
     * order already matches; R G B A order gets red and blue swapped here so the same shaders work.
     * 뷰 셰이더는 색을 .bgr로 읽음(Xvfb의 B G R X 바이트). B G R A 순서 게임 프레임은 그대로 맞고,
     * R G B A 순서는 여기서 빨강·파랑을 바꿔 같은 셰이더로 그림 */
    if (c->order == EZP_ORDER_RGBA) {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_R, GL_BLUE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_B, GL_RED);
    }
    c->tex[i] = t;
    return t;
}

/* GPU-side wait on a game sync fd (EGL owns the fd on success) / 게임 sync fd를 GPU 쪽에서 기다림 */
static void gpu_wait(EGLDisplay d, int fd)
{
    if (fd < 0) return;
    if (!cCreateSync) {
        close(fd);
        return;
    }
    const EGLint a[] = {EGL_SYNC_NATIVE_FENCE_FD_ANDROID, fd, EGL_NONE};
    EGLSyncKHR s = cCreateSync(d, EGL_SYNC_NATIVE_FENCE_ANDROID, a);
    if (s == EGL_NO_SYNC_KHR) {
        close(fd);
        return;
    }
    if (!cWaitSync || cWaitSync(d, s, 0) != EGL_TRUE) {
        if (cClientWait) cClientWait(d, s, 0, 100000000ll);
    }
    cDestroySync(d, s);
}

/*
 * Frame for this refresh (GL thread). out: tex, w, h, rx, ry, rw, rh, id, new(1 = a new game frame).
 * Returns 1 when a game frame should be shown.
 * 이번 갱신의 프레임(GL 스레드). out: tex, w, h, rx, ry, rw, rh, id, new(1 = 새 게임 프레임).
 * 게임 프레임을 보여야 하면 1
 */
static int comp_frame(jint o[9])
{
    EGLDisplay d = eglGetCurrentDisplay();
    if (d == EGL_NO_DISPLAY) return 0;
    gl_procs();
    int64_t now = mono_ns();
    pthread_mutex_lock(&g_cmu);
    /* Forget finished connections that are no longer shown / 더 보이지 않는 끝난 연결을 정리 */
    for (int i = 0; i < MAX_SC; i++)
        if (g_sc[i].used && !g_sc[i].alive && i != g_cur) sc_cleanup(&g_sc[i], d);
    /* Newest presenter among live connections. A game that stops presenting for a while (loading)
     * keeps its last frame on screen instead of falling back to its empty X window.
     * 살아 있는 연결 중 가장 늦게 present한 것. 잠시 present를 멈춘 게임(로딩)은 빈 X 창으로 돌아가지
     * 않고 마지막 프레임을 그대로 보임 */
    (void)now;
    int best = -1;
    for (int i = 0; i < MAX_SC; i++) {
        struct csc *c = &g_sc[i];
        if (!c->used || !c->alive || (c->shown < 0 && c->queued < 0)) continue;
        if (best < 0 || c->last_ns > g_sc[best].last_ns) best = i;
    }
    if (g_cur >= 0 && g_cur != best) {
        /* Switched away: the old connection's shown buffer goes back / 다른 것으로 바뀜: 이전 표시 버퍼를 돌려줌 */
        struct csc *o = &g_sc[g_cur];
        if (o->used && o->shown >= 0) {
            int idx = o->shown;
            o->shown = -1;
            if (o->alive) {
                pthread_mutex_unlock(&g_cmu);
                sc_send(o, EZP_RELEASE, (uint32_t)idx, -1);
                pthread_mutex_lock(&g_cmu);
            }
        }
        if (o->used && !o->alive) sc_cleanup(o, d);
    }
    g_cur = best;
    if (best < 0) {
        pthread_mutex_unlock(&g_cmu);
        return 0;
    }
    struct csc *c = &g_sc[best];
    int fresh = 0, fence = -1;
    if (c->queued >= 0) {
        if (c->shown >= 0) c->to_release = c->shown;
        c->shown = c->queued;
        fence = c->queued_fence;
        c->queued = -1;
        c->queued_fence = -1;
        c->shown_frames++;
        fresh = 1;
    }
    int idx = c->shown;
    o[0] = 0;
    o[1] = (jint)c->w;
    o[2] = (jint)c->h;
    o[3] = c->rx;
    o[4] = c->ry;
    o[5] = c->rw;
    o[6] = c->rh;
    o[7] = c->id;
    o[8] = fresh;
    pthread_mutex_unlock(&g_cmu);
    gpu_wait(d, fence);
    o[0] = (jint)sc_texture(c, idx, d);
    return o[0] ? 1 : 0;
}

JNIEXPORT jint JNICALL Java_app_ezsteam_runtime_PresentProbe_nativeFrame(JNIEnv *env, jclass cls, jintArray out)
{
    (void)cls;
    jint o[9] = {0};
    int r = comp_frame(o);
    if (r) (*env)->SetIntArrayRegion(env, out, 0, 9, o);
    return r;
}

/* After the frame is drawn: give the previously shown buffer back with a GL fence (GL thread)
 * 프레임을 그린 뒤: 이전에 보인 버퍼를 GL 펜스와 함께 돌려줌(GL 스레드) */
static void comp_frame_done(void)
{
    pthread_mutex_lock(&g_cmu);
    struct csc *c = g_cur >= 0 ? &g_sc[g_cur] : NULL;
    int idx = c && c->used && c->alive ? c->to_release : -1;
    if (c) c->to_release = -1;
    pthread_mutex_unlock(&g_cmu);
    if (idx < 0) return;
    int fd = -1;
    EGLDisplay d = eglGetCurrentDisplay();
    if (cCreateSync && cDupFence && d != EGL_NO_DISPLAY) {
        EGLSyncKHR s = cCreateSync(d, EGL_SYNC_NATIVE_FENCE_ANDROID, NULL);
        glFlush();
        if (s != EGL_NO_SYNC_KHR) {
            fd = cDupFence(d, s);
            cDestroySync(d, s);
        }
    }
    if (fd < 0) glFinish(); /* no fence: make sure GL is done reading / 펜스 없음: GL이 다 읽었는지 확실히 함 */
    sc_send(c, EZP_RELEASE, (uint32_t)idx, fd);
    if (fd >= 0) close(fd);
}

JNIEXPORT void JNICALL Java_app_ezsteam_runtime_PresentProbe_nativeFrameDone(JNIEnv *env, jclass cls)
{
    (void)env;
    (void)cls;
    comp_frame_done();
}

/* New GL context (view recreated): old textures are gone with it (GL thread)
 * 새 GL 컨텍스트(뷰 다시 만듦): 이전 텍스처는 함께 사라짐(GL 스레드) */
JNIEXPORT void JNICALL Java_app_ezsteam_runtime_PresentProbe_nativeGlReset(JNIEnv *env, jclass cls)
{
    (void)env;
    (void)cls;
    pthread_mutex_lock(&g_cmu);
    for (int i = 0; i < MAX_SC; i++)
        for (int k = 0; k < EZP_MAX_BUFFERS; k++) g_sc[i].tex[k] = 0;
    pthread_mutex_unlock(&g_cmu);
}

/* Window rect of a connection in X screen pixels (from the app's X connection)
 * 연결의 창 위치(X 화면 화소, 앱 X 연결에서 구함) */
JNIEXPORT void JNICALL Java_app_ezsteam_runtime_PresentProbe_nativeSetRect(JNIEnv *env, jclass cls, jint id, jint x,
                                                                          jint y, jint w, jint h)
{
    (void)env;
    (void)cls;
    pthread_mutex_lock(&g_cmu);
    for (int i = 0; i < MAX_SC; i++)
        if (g_sc[i].used && g_sc[i].id == id) {
            g_sc[i].rx = x;
            g_sc[i].ry = y;
            g_sc[i].rw = w;
            g_sc[i].rh = h;
        }
    pthread_mutex_unlock(&g_cmu);
}

/* Frame limit for all layer connections, now and for new ones (0 = none)
 * 모든 레이어 연결의 프레임 제한, 지금과 새 연결 모두(0 = 없음) */
JNIEXPORT void JNICALL Java_app_ezsteam_runtime_PresentProbe_nativeSetFps(JNIEnv *env, jclass cls, jint fps)
{
    (void)env;
    (void)cls;
    g_fps_app = fps < 0 ? 0 : fps;
    pthread_mutex_lock(&g_cmu);
    for (int i = 0; i < MAX_SC; i++)
        if (g_sc[i].used && g_sc[i].alive) {
            struct ezp_msg f;
            memset(&f, 0, sizeof f);
            f.type = EZP_SET_FPS;
            f.pattern = (uint32_t)g_fps_app;
            pthread_mutex_lock(&g_sc[i].wmu);
            send_msg(g_sc[i].sock, &f, NULL, 0, NULL, 0);
            pthread_mutex_unlock(&g_sc[i].wmu);
        }
    pthread_mutex_unlock(&g_cmu);
}

/* Compression test switch (next swapchains) / 압축 시험 스위치(다음 swapchain부터) */
JNIEXPORT void JNICALL Java_app_ezsteam_runtime_PresentProbe_nativeSetCompress(JNIEnv *env, jclass cls, jboolean on)
{
    (void)env;
    (void)cls;
    g_compress = on ? 1 : 0;
}

/* Layout check C found: 0 linear, 1 Qualcomm compressed, -1 not known / 확인 C 결과: 0 linear, 1 압축, -1 모름 */
JNIEXPORT jint JNICALL Java_app_ezsteam_runtime_PresentProbe_nativeGpuLayout(JNIEnv *env, jclass cls)
{
    (void)env;
    (void)cls;
    uint64_t m = g_gpu_mod;
    return m == EZP_MOD_INVALID ? -1 : m == EZP_MOD_QCOM_COMPRESSED ? 1 : 0;
}

/* Live connections: id, window, presents so far, frames shown so far (4 ints each)
 * 살아 있는 연결: id, 창, 지금까지 present 수, 지금까지 보인 수(각 4개) */
JNIEXPORT jintArray JNICALL Java_app_ezsteam_runtime_PresentProbe_nativeConnections(JNIEnv *env, jclass cls)
{
    (void)cls;
    jint v[MAX_SC * 4];
    int n = 0;
    pthread_mutex_lock(&g_cmu);
    for (int i = 0; i < MAX_SC; i++)
        if (g_sc[i].used && g_sc[i].alive) {
            v[n++] = g_sc[i].id;
            v[n++] = (jint)g_sc[i].window;
            v[n++] = (jint)g_sc[i].frames;
            v[n++] = (jint)g_sc[i].shown_frames;
        }
    pthread_mutex_unlock(&g_cmu);
    jintArray a = (*env)->NewIntArray(env, n);
    if (a && n) (*env)->SetIntArrayRegion(env, a, 0, n, v);
    return a;
}

JNIEXPORT jint JNICALL Java_app_ezsteam_runtime_PresentProbe_nativeListen(JNIEnv *env, jclass cls, jstring path)
{
    (void)cls;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (!p) return -ENOMEM;
    unlink(p);
    int s = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    int err = 0;
    if (s >= 0) {
        struct sockaddr_un a;
        memset(&a, 0, sizeof a);
        a.sun_family = AF_UNIX;
        snprintf(a.sun_path, sizeof a.sun_path, "%s", p);
        if (bind(s, (struct sockaddr *)&a, sizeof a) != 0 || listen(s, 2) != 0) {
            err = errno;
            close(s);
            s = -1;
        }
    } else {
        err = errno;
    }
    (*env)->ReleaseStringUTFChars(env, path, p);
    return s >= 0 ? s : -err;
}

/* Blocks for one connection. A probe is answered here and its report returned; a present layer
 * (first message EZP_SWAPCHAIN) gets its own thread and "S ..." is returned for the log; null when
 * the listener is closed.
 * 연결 하나를 기다림. probe는 여기서 답하고 보고를 돌려줌. present 레이어(첫 메시지 EZP_SWAPCHAIN)는
 * 자기 스레드를 받고 로그용 "S ..."를 돌려줌. 듣는 소켓이 닫히면 null */
JNIEXPORT jstring JNICALL Java_app_ezsteam_runtime_PresentProbe_nativeServe(JNIEnv *env, jclass cls, jint lfd)
{
    (void)cls;
    int c;
    for (;;) {
        c = accept4(lfd, NULL, NULL, SOCK_CLOEXEC);
        if (c >= 0) break;
        if (errno != EINTR && errno != ECONNABORTED) return NULL;
    }
    struct timeval tv = {8, 0};
    setsockopt(c, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    struct ezp_msg first;
    int ffds[EZP_MAX_FDS], fn = 0;
    ssize_t fr = recv_msg(c, &first, ffds, &fn);
    for (int i = 0; i < fn; i++) close(ffds[i]);
    if (fr <= 0) {
        close(c);
        return (*env)->NewStringUTF(env, "E 첫 메시지 없음");
    }
    if (first.type == EZP_SWAPCHAIN) {
        char line[160];
        comp_start(c, &first, line, sizeof line);
        return (*env)->NewStringUTF(env, line);
    }
    rep_len = 0;
    rep[0] = 0;
    uint32_t res = session(c, &first);
    struct ezp_msg m;
    memset(&m, 0, sizeof m);
    m.type = EZP_REPORT;
    m.flags = res;
    m.len = (uint32_t)rep_len;
    send_msg(c, &m, NULL, 0, rep, rep_len);
    close(c);
    return (*env)->NewStringUTF(env, rep);
}

JNIEXPORT void JNICALL Java_app_ezsteam_runtime_PresentProbe_nativeClose(JNIEnv *env, jclass cls, jint lfd)
{
    (void)env;
    (void)cls;
    shutdown(lfd, SHUT_RDWR);
    close(lfd);
}
