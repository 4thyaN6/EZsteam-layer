/*
 * ezs-xcm: minimal X compositing manager for the rootful Xwayland screen (test switch "xcm").
 * Why: Steam's Big Picture window is a 32-bit (ARGB) window in every Steam UI GPU mode
 * (app log "X 새 창 … 깊이 32"), and nothing on our screen blends it: the 24-bit root takes its
 * RGB as it is, so the transparent parts above/below the main menu panel showed whatever the
 * renderer left there (black with SW compositing, white with Chromium GL, random colour blocks
 * with ANGLE Vulkan, menushot 2026-10-30 23:58, also in the X server's own picture). gamescope
 * (DroidDeck) blends such windows; this program does the same one job, to check the cause:
 * every top-level window is redirected (Composite, manual), and on each change the windows are
 * drawn bottom to top onto black, ARGB windows with their alpha (Render "Over"), into the
 * Composite overlay window. The drawing runs in the X server (Render on glamor = GPU).
 * Own design (MIT); no code taken from xcompmgr, picom or gamescope.
 * Stage A of the plan: if the fault goes away, the same job moves into ezs-wl (stage B,
 * rootless Xwayland), so this process exists only while the test switch is on.
 *
 * ezs-xcm: rootful Xwayland 화면용 최소 X 합성 관리자(시험 스위치 "xcm").
 * 이유: Steam 빅픽처 창은 모든 Steam 화면 GPU 방식에서 32비트(ARGB) 창인데(앱 로그 "X 새 창 … 깊이 32"),
 * 우리 화면에는 그것을 섞어 주는 것이 없음. 24비트 루트가 RGB를 그대로 받으므로 메인 메뉴 패널 위·아래의 투명한
 * 부분에 렌더러가 남긴 값이 보였음(합성 SW 검정, Chromium GL 흰색, ANGLE Vulkan 무작위 색 블록, menushot
 * 2026-10-30 23:58, X 서버 자체 그림에서도). gamescope(DroidDeck)는 이런 창을 섞음. 이 프로그램은 원인 확인을
 * 위해 그 한 가지 일만 함: 모든 최상위 창을 리디렉트(Composite, 수동)하고, 바뀔 때마다 창들을 아래에서 위로 검정
 * 위에 그림. ARGB 창은 알파를 반영함(Render "Over"). 결과는 Composite 오버레이 창에 그림. 그리기는 X 서버 안에서
 * 돌아감(glamor 위 Render = GPU). 자체 설계(MIT). xcompmgr·picom·gamescope 코드는 가져오지 않음.
 * 계획의 A 단계: 깨짐이 사라지면 같은 일을 ezs-wl로 옮김(B 단계, rootless Xwayland). 그래서 이 프로세스는
 * 시험 스위치가 켜진 동안에만 있음
 */
#include <X11/Xlib.h>
#include <X11/Xatom.h>
#include <X11/extensions/Xcomposite.h>
#include <X11/extensions/Xdamage.h>
#include <X11/extensions/Xfixes.h>
#include <X11/extensions/Xrender.h>
#include <X11/extensions/shape.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* One top-level window as last seen / 마지막으로 본 최상위 창 하나 */
struct win {
    Window id;
    int x, y, w, h, bw;
    int mapped, input_only, argb;
    Picture pic;
    Damage damage;
};

static Display *dpy;
static Window root, overlay;
static int scr_w, scr_h;
static struct win wins[256];
static int nwins;
static int tree_dirty = 1, paint_dirty = 1;
static int damage_event;
static Picture back_pic, overlay_pic;
static Pixmap back_pix;

/* Statistics for the per-minute line / 1분 줄용 통계 */
static long paints, events;
static double paint_ms_sum, paint_ms_max;
static int argb_seen_logged;

static double now_ms(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000.0 + t.tv_nsec / 1e6;
}

/* Windows vanish between a request and its use: such errors are expected and ignored
   창은 요청과 사용 사이에 사라질 수 있음: 그런 오류는 예상된 것이라 무시함 */
static int on_error(Display *d, XErrorEvent *e)
{
    (void)d;
    (void)e;
    return 0;
}

static void win_release(struct win *w)
{
    if (w->pic) XRenderFreePicture(dpy, w->pic);
    if (w->damage) XDamageDestroy(dpy, w->damage);
    w->pic = 0;
    w->damage = 0;
}

/* Re-read the stacking order and window attributes after a structure change
   구조가 바뀐 뒤 쌓임 순서와 창 속성을 다시 읽음 */
static void refresh_tree(void)
{
    Window r, p, *kids = NULL;
    unsigned int n = 0;
    struct win old[256];
    int nold = nwins;
    memcpy(old, wins, sizeof(struct win) * (size_t)nwins);
    nwins = 0;
    if (!XQueryTree(dpy, root, &r, &p, &kids, &n)) return;
    for (unsigned int i = 0; i < n && nwins < 256; i++) {
        if (kids[i] == overlay) continue;
        XWindowAttributes a;
        if (!XGetWindowAttributes(dpy, kids[i], &a)) continue;
        struct win w = {0};
        w.id = kids[i];
        /* Keep the picture and damage of a window already known / 이미 아는 창의 그림·damage는 그대로 씀 */
        for (int k = 0; k < nold; k++) {
            if (old[k].id == kids[i]) {
                w.pic = old[k].pic;
                w.damage = old[k].damage;
                old[k].id = 0;
                break;
            }
        }
        w.x = a.x;
        w.y = a.y;
        w.w = a.width;
        w.h = a.height;
        w.bw = a.border_width;
        w.mapped = a.map_state == IsViewable;
        w.input_only = a.class == InputOnly;
        XRenderPictFormat *f = w.input_only ? NULL : XRenderFindVisualFormat(dpy, a.visual);
        w.argb = f && f->type == PictTypeDirect && f->direct.alphaMask;
        if (w.mapped && !w.input_only && f && !w.pic) {
            XRenderPictureAttributes pa = {0};
            pa.subwindow_mode = IncludeInferiors;
            w.pic = XRenderCreatePicture(dpy, w.id, f, CPSubwindowMode, &pa);
            w.damage = XDamageCreate(dpy, w.id, XDamageReportNonEmpty);
            printf("ezs-xcm: 창 0x%lx %dx%d 깊이 %d%s\n", (unsigned long)w.id, w.w, w.h, a.depth,
                   w.argb ? " (알파 합성)" : "");
            if (w.argb && !argb_seen_logged) {
                argb_seen_logged = 1;
                printf("INFO xcm : ARGB 창을 알파로 합성 시작\n");
            }
            fflush(stdout);
        }
        if (!w.mapped && w.pic) win_release(&w);
        wins[nwins++] = w;
    }
    if (kids) XFree(kids);
    /* Windows that are gone / 사라진 창 */
    for (int k = 0; k < nold; k++) {
        if (old[k].id) win_release(&old[k]);
    }
    tree_dirty = 0;
}

/* Draw all windows onto black, then the result onto the overlay window
   모든 창을 검정 위에 그린 뒤, 결과를 오버레이 창에 그림 */
static void paint(void)
{
    double t0 = now_ms();
    XRenderColor black = {0, 0, 0, 0xffff};
    XRenderFillRectangle(dpy, PictOpSrc, back_pic, &black, 0, 0, (unsigned)scr_w, (unsigned)scr_h);
    for (int i = 0; i < nwins; i++) {
        struct win *w = &wins[i];
        if (!w->mapped || !w->pic) continue;
        int ww = w->w + 2 * w->bw, hh = w->h + 2 * w->bw;
        /* ARGB windows blend with their alpha, others replace / ARGB 창은 알파로 섞고, 나머지는 덮어씀 */
        XRenderComposite(dpy, w->argb ? PictOpOver : PictOpSrc, w->pic, None, back_pic,
                         0, 0, 0, 0, w->x, w->y, (unsigned)ww, (unsigned)hh);
    }
    XRenderComposite(dpy, PictOpSrc, back_pic, None, overlay_pic, 0, 0, 0, 0, 0, 0,
                     (unsigned)scr_w, (unsigned)scr_h);
    XFlush(dpy);
    double ms = now_ms() - t0;
    paints++;
    paint_ms_sum += ms;
    if (ms > paint_ms_max) paint_ms_max = ms;
    paint_dirty = 0;
}

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IOLBF, 0);
    dpy = XOpenDisplay(NULL);
    if (!dpy) {
        printf("FAIL xcm : X 서버에 연결 못 함 (DISPLAY=%s)\n", getenv("DISPLAY") ? getenv("DISPLAY") : "");
        return 1;
    }
    XSetErrorHandler(on_error);
    int ev, er, maj = 0, min = 0;
    if (!XCompositeQueryExtension(dpy, &ev, &er) || !XCompositeQueryVersion(dpy, &maj, &min) || (maj == 0 && min < 3)) {
        printf("FAIL xcm : Composite 0.3 이상 없음 (%d.%d)\n", maj, min);
        return 1;
    }
    if (!XDamageQueryExtension(dpy, &damage_event, &er) || !XRenderQueryExtension(dpy, &ev, &er) ||
        !XFixesQueryExtension(dpy, &ev, &er)) {
        printf("FAIL xcm : Damage/Render/XFixes 없음\n");
        return 1;
    }
    int scr = DefaultScreen(dpy);
    root = RootWindow(dpy, scr);
    scr_w = DisplayWidth(dpy, scr);
    scr_h = DisplayHeight(dpy, scr);

    /* Announce a compositing manager (_NET_WM_CM_S0), as gamescope and other compositors do;
       a second one is refused. "--no-cm-selection" skips it: with the announcement the Steam UI's
       GPU window surface failed ("eglCreateWindowSurface failed with error EGL_BAD_CONFIG", xcmtest
       2026-10-31 13:48) and the UI ran like software compositing (Steam UI 190%, 21 frames/s), so the
       blending is tested once without it (Chromium reads only this selection, not the redirection)
       합성 관리자임을 알림(_NET_WM_CM_S0). gamescope 등 합성기가 하는 것과 같음. 두 번째 것은 거부함.
       "--no-cm-selection"이면 알리지 않음: 알렸을 때 Steam 화면의 GPU 창 표면 만들기가 실패했고("eglCreateWindowSurface
       failed with error EGL_BAD_CONFIG", xcmtest 2026-10-31 13:48) 화면이 소프트웨어 합성처럼 돌았음(Steam 화면
       190%, 초당 21프레임). 그래서 알리지 않고 섞기만 한 번 시험함(Chromium은 리디렉트가 아니라 이 선택만 읽음) */
    int announce = !(argc > 1 && strcmp(argv[1], "--no-cm-selection") == 0);
    char name[32];
    snprintf(name, sizeof name, "_NET_WM_CM_S%d", scr);
    Atom cm = XInternAtom(dpy, name, False);
    if (XGetSelectionOwner(dpy, cm) != None) {
        printf("FAIL xcm : 이미 다른 합성 관리자가 있음\n");
        return 1;
    }
    if (announce) {
        Window owner = XCreateSimpleWindow(dpy, root, -1, -1, 1, 1, 0, 0, 0);
        XSetSelectionOwner(dpy, cm, owner, CurrentTime);
    }

    XGrabServer(dpy);
    XCompositeRedirectSubwindows(dpy, root, CompositeRedirectManual);
    XSelectInput(dpy, root, SubstructureNotifyMask | ExposureMask | StructureNotifyMask);
    XUngrabServer(dpy);

    /* Overlay window: shown above everything, input passes through (empty input shape), so the
       app's XTEST input still reaches the windows below / 오버레이 창: 모든 것 위에 보이고 입력은 통과함(빈 입력
       모양). 앱의 XTEST 입력이 아래 창에 그대로 닿음 */
    overlay = XCompositeGetOverlayWindow(dpy, root);
    XserverRegion empty = XFixesCreateRegion(dpy, NULL, 0);
    XFixesSetWindowShapeRegion(dpy, overlay, ShapeInput, 0, 0, empty);
    XFixesDestroyRegion(dpy, empty);

    XWindowAttributes ra;
    XGetWindowAttributes(dpy, root, &ra);
    XRenderPictFormat *rf = XRenderFindVisualFormat(dpy, ra.visual);
    overlay_pic = XRenderCreatePicture(dpy, overlay, rf, 0, NULL);
    /* Back buffer, so a frame never shows half drawn / 반쯤 그린 프레임이 보이지 않게 뒤 버퍼를 둠 */
    back_pix = XCreatePixmap(dpy, root, (unsigned)scr_w, (unsigned)scr_h, (unsigned)ra.depth);
    back_pic = XRenderCreatePicture(dpy, back_pix, rf, 0, NULL);
    printf("INFO xcm : 합성 관리자 시작 %dx%d, Composite %d.%d, 루트 깊이 %d, %s\n", scr_w, scr_h, maj, min, ra.depth,
           announce ? "_NET_WM_CM 알림" : "_NET_WM_CM 알리지 않음");

    double last_stat = now_ms();
    struct pollfd pfd = {ConnectionNumber(dpy), POLLIN, 0};
    for (;;) {
        while (XPending(dpy)) {
            XEvent e;
            XNextEvent(dpy, &e);
            events++;
            if (e.type == damage_event + XDamageNotify) {
                /* Whole window repainted; the change itself is not tracked (test stage)
                   창 전체를 다시 그림. 바뀐 영역 자체는 따지지 않음(시험 단계) */
                XDamageSubtract(dpy, ((XDamageNotifyEvent *)&e)->damage, None, None);
                paint_dirty = 1;
            } else if (e.type == CreateNotify || e.type == DestroyNotify || e.type == MapNotify ||
                       e.type == UnmapNotify || e.type == ConfigureNotify || e.type == ReparentNotify ||
                       e.type == CirculateNotify || e.type == Expose) {
                tree_dirty = 1;
                paint_dirty = 1;
            }
        }
        if (tree_dirty) refresh_tree();
        if (paint_dirty) paint();
        double t = now_ms();
        if (t - last_stat >= 60000) {
            int argb = 0, shown = 0;
            for (int i = 0; i < nwins; i++) {
                if (wins[i].mapped && wins[i].pic) {
                    shown++;
                    argb += wins[i].argb;
                }
            }
            printf("INFO xcm : 1분 합성 %ld번(평균 %.2fms 최대 %.2fms, 요청 기준), 이벤트 %ld, 보이는 창 %d(ARGB %d)\n",
                   paints, paints ? paint_ms_sum / paints : 0.0, paint_ms_max, events, shown, argb);
            paints = events = 0;
            paint_ms_sum = paint_ms_max = 0;
            last_stat = t;
        }
        poll(&pfd, 1, 1000);
    }
}
