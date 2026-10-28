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
 *   ezs-gltest [--argb] [--thread] [--timestamp] [--render-xpending] [--swap-interval N] [--frames N] [--size WxH]
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
    printf("RESULT %s frames %d in %.2f s (%.1f fps) xerrors %d\n", xerrors ? "PASS-with-xerrors" : "PASS", drawn,
           dt, dt > 0 ? drawn / dt : 0.0, xerrors);
    if (xerrors) printf("last xerror code %d request %d\n", last_xerror, last_xreq);
    glXDestroyContext(dpy, ctx);
    XDestroyWindow(dpy, win);
    XCloseDisplay(dpy);
    return 0;
}
