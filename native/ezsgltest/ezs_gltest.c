/*
 * ezs-gltest: small GLX check used by the GPU path check (gpu-check.sh). Own code, MIT. It does in a
 * few seconds what Steam's Big Picture compositor does when its window first appears: a GLX window
 * (optionally 32-bit with alpha, like SDL_WINDOW_TRANSPARENT), drawing and glXSwapBuffers in a
 * loop, optionally from a second thread while the main thread reads X events on the same display
 * (Steam's CCompositorGLThread), optionally reading GL_TIMESTAMP every frame.
 * Prints one result line: "RESULT PASS ..." or "RESULT FAIL <reason> ...". An X connection failure
 * (Xlib I/O error) and X protocol errors are reported, not hidden.
 * ezs-gltest: GPU 경로 점검(gpu-check.sh)이 쓰는 작은 GLX 확인 도구. 자체 코드, MIT. Steam 빅픽처 합성기가 창이 처음 보일 때 하는
 * 일을 몇 초 안에 재현함: GLX 창(선택: SDL_WINDOW_TRANSPARENT처럼 알파가 있는 32비트), 그리기와 glXSwapBuffers 반복, 선택하면
 * 주 스레드가 같은 디스플레이에서 X 이벤트를 읽는 동안 두 번째 스레드에서 그림(Steam의 CCompositorGLThread), 선택하면 프레임마다
 * GL_TIMESTAMP를 읽음.
 * 결과 한 줄을 출력함: "RESULT PASS ..." 또는 "RESULT FAIL <이유> ...". X 연결 실패(Xlib 입출력 오류)와 X 프로토콜 오류는 숨기지
 * 않고 알림
 *
 *   ezs-gltest [--argb] [--thread] [--timestamp] [--render-xpending] [--swap-interval N] [--tfp] [--frames N] [--size WxH]
 * --render-xpending: the drawing thread also calls XPending on the same display every frame, as
 * Steam's "GL Composer Thr" does (its X connection failed there: XPending -> _XEventsQueued ->
 * _XIOError, 2026-10-29) / 그리기 스레드도 프레임마다 같은 디스플레이에서 XPending을 부름. Steam "GL Composer Thr"와
 * 같음(그곳에서 X 연결이 실패했음: XPending -> _XEventsQueued -> _XIOError, 2026-10-29)
 */
#define _GNU_SOURCE
#include <GL/gl.h>
#include <GL/glx.h>
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#ifndef GL_TIMESTAMP
#define GL_TIMESTAMP 0x8E28
#endif

static Display *dpy;
static Window win;
static GLXContext ctx;
static int frames = 180, W = 640, H = 400, use_argb, use_thread, use_ts, render_xpending;
/* --swap-interval N: glXSwapIntervalEXT(N) after make-current, as SDL_GL_SetSwapInterval does
   (-99 = not called) / --swap-interval N: make-current 뒤 glXSwapIntervalEXT(N), SDL_GL_SetSwapInterval과 같음(-99 = 안 부름) */
static int swap_interval = -99;
/* --tfp: every frame an X pixmap is drawn with core X and bound as a texture (GLX_EXT_texture_from_pixmap),
   as Steam's Big Picture composer does (it failed there on Xvfb: kopper asked for the pixmap through
   DRI3, 2026-10-29) / --tfp: 프레임마다 core X로 X 픽스맵을 그리고 텍스처로 묶음(GLX_EXT_texture_from_pixmap). Steam 빅픽처
   합성기와 같음(Xvfb에서 그곳이 실패했음: kopper가 DRI3로 픽스맵을 요청, 2026-10-29) */
static int use_tfp;
static int tfp_fail;
static volatile int done_drawing, drawn, xerrors;
static int last_xerror, last_xreq;

static double now_s(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec + t.tv_nsec / 1e9;
}

/* X protocol errors: counted and the first ones printed / X 프로토콜 오류: 세고 처음 몇 개를 출력함 */
static int on_xerror(Display *d, XErrorEvent *e)
{
    (void)d;
    xerrors++;
    last_xerror = e->error_code;
    last_xreq = e->request_code;
    if (xerrors <= 5) printf("XERROR code %d request %d.%d\n", e->error_code, e->request_code, e->minor_code);
    fflush(stdout);
    return 0;
}

/* X connection lost: the failure seen in the Steam web helper ("Fatal IO error 11")
   X 연결 끊김: Steam 웹 도우미에서 본 실패("Fatal IO error 11") */
static int on_xio(Display *d)
{
    (void)d;
    printf("RESULT FAIL x-connection-lost after %d frames\n", drawn);
    fflush(stdout);
    _exit(3);
    return 0;
}

static void draw_loop(void)
{
    if (!glXMakeCurrent(dpy, win, ctx)) {
        printf("RESULT FAIL make-current\n");
        fflush(stdout);
        _exit(4);
    }
    if (drawn == 0) {
        printf("GL renderer: %s\nGL version: %s\n", (const char *)glGetString(GL_RENDERER),
               (const char *)glGetString(GL_VERSION));
        fflush(stdout);
    }
    /* Texture-from-pixmap set-up / texture-from-pixmap 준비 */
    Pixmap pm = 0;
    GLXPixmap gpm = 0;
    GC gc = 0;
    GLuint tex = 0;
    void (*bind_tex)(Display *, GLXDrawable, int, const int *) = NULL;
    void (*release_tex)(Display *, GLXDrawable, int) = NULL;
    if (use_tfp) {
        bind_tex = (void (*)(Display *, GLXDrawable, int, const int *))glXGetProcAddressARB((const GLubyte *)"glXBindTexImageEXT");
        release_tex = (void (*)(Display *, GLXDrawable, int))glXGetProcAddressARB((const GLubyte *)"glXReleaseTexImageEXT");
        int pattrs[] = {GLX_DRAWABLE_TYPE, GLX_PIXMAP_BIT, GLX_BIND_TO_TEXTURE_RGBA_EXT, True, GLX_RED_SIZE, 8,
                        GLX_GREEN_SIZE, 8, GLX_BLUE_SIZE, 8, GLX_ALPHA_SIZE, 8, None};
        int pn = 0;
        GLXFBConfig *pcfgs = glXChooseFBConfig(dpy, DefaultScreen(dpy), pattrs, &pn);
        XVisualInfo *pvi = pn > 0 ? glXGetVisualFromFBConfig(dpy, pcfgs[0]) : NULL;
        if (!bind_tex || !release_tex || !pvi) {
            printf("RESULT FAIL tfp-unsupported (bind %d, configs %d)\n", bind_tex != NULL, pn);
            fflush(stdout);
            _exit(5);
        }
        pm = XCreatePixmap(dpy, win, 256, 256, (unsigned)pvi->depth);
        int tattrs[] = {GLX_TEXTURE_TARGET_EXT, GLX_TEXTURE_2D_EXT, GLX_TEXTURE_FORMAT_EXT, GLX_TEXTURE_FORMAT_RGBA_EXT, None};
        gpm = glXCreatePixmap(dpy, pcfgs[0], pm, tattrs);
        gc = XCreateGC(dpy, pm, 0, NULL);
        printf("tfp pixmap depth %d\n", pvi->depth);
        XFree(pvi);
        XFree(pcfgs);
        glGenTextures(1, &tex);
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    }
    if (swap_interval != -99 && drawn == 0) {
        void (*si)(Display *, GLXDrawable, int) =
            (void (*)(Display *, GLXDrawable, int))glXGetProcAddressARB((const GLubyte *)"glXSwapIntervalEXT");
        if (si) {
            si(dpy, win, swap_interval);
            printf("glXSwapIntervalEXT(%d)\n", swap_interval);
        } else {
            printf("glXSwapIntervalEXT missing\n");
        }
    }
    void (*get64)(GLenum, GLint64 *) = (void (*)(GLenum, GLint64 *))glXGetProcAddressARB((const GLubyte *)"glGetInteger64v");
    for (int i = 0; i < frames; i++) {
        float t = (float)i / (float)frames;
        glViewport(0, 0, W, H);
        glClearColor(t, 0.2f, 1.0f - t, use_argb ? 0.8f : 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glBegin(GL_TRIANGLES);
        glColor3f(1, 0, 0);
        glVertex2f(-0.6f, -0.6f);
        glColor3f(0, 1, 0);
        glVertex2f(0.6f, -0.6f);
        glColor3f(0, 0, 1);
        glVertex2f(0.0f, 0.6f);
        glEnd();
        if (use_ts && get64) {
            GLint64 ts = 0;
            get64(GL_TIMESTAMP, &ts);
            if (i == 0) printf("GL_TIMESTAMP first %lld\n", (long long)ts);
        }
        if (use_tfp) {
            /* Core X drawing into the pixmap, then sample it in GL / 픽스맵에 core X로 그린 뒤 GL에서 읽음 */
            XSetForeground(dpy, gc, 0xff000000u | (unsigned long)(i * 0x010203u & 0xffffffu));
            XFillRectangle(dpy, pm, gc, 0, 0, 256, 256);
            XSync(dpy, False);
            glEnable(GL_TEXTURE_2D);
            glBindTexture(GL_TEXTURE_2D, tex);
            bind_tex(dpy, gpm, GLX_FRONT_LEFT_EXT, NULL);
            /* White, so the texture colour is not tinted by the triangle's last colour
               흰색: 삼각형의 마지막 색으로 텍스처 색이 물들지 않게 함 */
            glColor3f(1, 1, 1);
            glBegin(GL_QUADS);
            glTexCoord2f(0, 0); glVertex2f(-0.9f, -0.9f);
            glTexCoord2f(1, 0); glVertex2f(-0.4f, -0.9f);
            glTexCoord2f(1, 1); glVertex2f(-0.4f, -0.4f);
            glTexCoord2f(0, 1); glVertex2f(-0.9f, -0.4f);
            glEnd();
            release_tex(dpy, gpm, GLX_FRONT_LEFT_EXT);
            glDisable(GL_TEXTURE_2D);
            if (i == frames / 2) {
                /* The colour drawn by X must reach the texture / X가 그린 색이 텍스처까지 와야 함 */
                unsigned char px[4] = {0, 0, 0, 0};
                glReadPixels(W / 8, H / 8, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
                unsigned long want = (unsigned long)(i * 0x010203u & 0xffffffu);
                printf("tfp sample rgb %02x%02x%02x, drawn %06lx\n", px[0], px[1], px[2], want);
                if (px[0] == 0 && px[1] == 0 && px[2] == 0 && want != 0) tfp_fail = 1;
            }
        }
        glXSwapBuffers(dpy, win);
        if (render_xpending) XPending(dpy);
        drawn = i + 1;
    }
    glFinish();
    glXMakeCurrent(dpy, None, NULL);
}

static void *thread_main(void *arg)
{
    (void)arg;
    draw_loop();
    done_drawing = 1;
    return NULL;
}

int main(int argc, char **argv)
{
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--argb")) use_argb = 1;
        else if (!strcmp(argv[i], "--thread")) use_thread = 1;
        else if (!strcmp(argv[i], "--timestamp")) use_ts = 1;
        else if (!strcmp(argv[i], "--render-xpending")) render_xpending = 1;
        else if (!strcmp(argv[i], "--swap-interval") && i + 1 < argc) swap_interval = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--tfp")) use_tfp = 1;
        else if (!strcmp(argv[i], "--frames") && i + 1 < argc) frames = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--size") && i + 1 < argc) sscanf(argv[++i], "%dx%d", &W, &H);
    }
    setvbuf(stdout, NULL, _IOLBF, 0);
    /* Like SDL: threads on for Xlib / SDL처럼 Xlib 스레드 사용을 켬 */
    XInitThreads();
    dpy = XOpenDisplay(NULL);
    if (!dpy) {
        printf("RESULT FAIL no-display\n");
        return 2;
    }
    XSetErrorHandler(on_xerror);
    XSetIOErrorHandler(on_xio);
    int scr = DefaultScreen(dpy);

    /* Frame buffer config: RGBA8 double-buffered; with --argb the one whose visual has depth 32
       프레임 버퍼 구성: RGBA8 이중 버퍼. --argb면 비주얼 깊이가 32인 것 */
    int attrs[] = {GLX_X_RENDERABLE, True, GLX_DRAWABLE_TYPE, GLX_WINDOW_BIT, GLX_RENDER_TYPE, GLX_RGBA_BIT,
                   GLX_RED_SIZE, 8, GLX_GREEN_SIZE, 8, GLX_BLUE_SIZE, 8, GLX_ALPHA_SIZE, use_argb ? 8 : 0,
                   GLX_DOUBLEBUFFER, True, None};
    int n = 0;
    GLXFBConfig *cfgs = glXChooseFBConfig(dpy, scr, attrs, &n);
    if (!cfgs || n == 0) {
        printf("RESULT FAIL no-fbconfig\n");
        return 2;
    }
    GLXFBConfig cfg = cfgs[0];
    XVisualInfo *vi = NULL;
    for (int i = 0; i < n; i++) {
        XVisualInfo *v = glXGetVisualFromFBConfig(dpy, cfgs[i]);
        if (!v) continue;
        if (!use_argb || v->depth == 32) {
            cfg = cfgs[i];
            vi = v;
            break;
        }
        XFree(v);
    }
    XFree(cfgs);
    if (!vi) {
        printf("RESULT FAIL no-visual%s\n", use_argb ? " (depth 32)" : "");
        return 2;
    }
    printf("visual 0x%lx depth %d\n", (unsigned long)vi->visualid, vi->depth);
    XSetWindowAttributes swa;
    memset(&swa, 0, sizeof(swa));
    swa.colormap = XCreateColormap(dpy, RootWindow(dpy, scr), vi->visual, AllocNone);
    swa.border_pixel = 0;
    swa.background_pixel = 0;
    swa.event_mask = ExposureMask | StructureNotifyMask;
    win = XCreateWindow(dpy, RootWindow(dpy, scr), 0, 0, (unsigned)W, (unsigned)H, 0, vi->depth, InputOutput,
                        vi->visual, CWColormap | CWBorderPixel | CWBackPixel | CWEventMask, &swa);
    XStoreName(dpy, win, "ezs-gltest");
    XMapWindow(dpy, win);
    XSync(dpy, False);
    ctx = glXCreateNewContext(dpy, cfg, GLX_RGBA_TYPE, NULL, True);
    if (!ctx) {
        printf("RESULT FAIL no-context\n");
        return 2;
    }

    double t0 = now_s();
    if (use_thread) {
        pthread_t th;
        pthread_create(&th, NULL, thread_main, NULL);
        /* Main thread keeps reading X events meanwhile / 그동안 주 스레드는 X 이벤트를 계속 읽음 */
        while (!done_drawing) {
            while (XPending(dpy)) {
                XEvent ev;
                XNextEvent(dpy, &ev);
            }
            usleep(2000);
        }
        pthread_join(th, NULL);
    } else {
        draw_loop();
    }
    double dt = now_s() - t0;
    XSync(dpy, False);
    if (tfp_fail) {
        printf("RESULT FAIL tfp-empty-texture frames %d\n", drawn);
        return 6;
    }
    printf("RESULT %s frames %d in %.2f s (%.1f fps) xerrors %d\n", xerrors ? "PASS-with-xerrors" : "PASS", drawn,
           dt, dt > 0 ? drawn / dt : 0.0, xerrors);
    if (xerrors) printf("last xerror code %d request %d\n", last_xerror, last_xreq);
    glXDestroyContext(dpy, ctx);
    XDestroyWindow(dpy, win);
    XCloseDisplay(dpy);
    return 0;
}
