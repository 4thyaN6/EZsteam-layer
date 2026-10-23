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

/* One probe connection / probe 연결 하나 */
static uint32_t session(int s)
{
    uint32_t res = 0;
    AHardwareBuffer *ahb = NULL;
    if (egl_open() != 0) {
        /* Still answer, so the probe does not wait / 그래도 답해 probe가 기다리지 않게 함 */
    }
    int done = 0;
    while (!done) {
        struct ezp_msg m;
        int fds[EZP_MAX_FDS], nfds = 0;
        ssize_t n = recv_msg(s, &m, fds, &nfds);
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
            done = 1;
            break;
        default:
            for (int i = 0; i < nfds; i++) close(fds[i]);
            say("소켓: 알 수 없는 메시지 %u", m.type);
            break;
        }
    }
    if (ahb) AHardwareBuffer_release(ahb);
    egl_close();
    return res;
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

/* Blocks for one probe connection, answers it, returns the report (null when the listener is
 * closed) / probe 연결 하나를 기다려 답하고 보고를 돌려줌(듣는 소켓이 닫히면 null) */
JNIEXPORT jstring JNICALL Java_app_ezsteam_runtime_PresentProbe_nativeServe(JNIEnv *env, jclass cls, jint lfd)
{
    (void)cls;
    int c = accept4(lfd, NULL, NULL, SOCK_CLOEXEC);
    if (c < 0) return NULL;
    struct timeval tv = {8, 0};
    setsockopt(c, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    rep_len = 0;
    rep[0] = 0;
    uint32_t res = session(c);
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
