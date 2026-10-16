/*
 * ezs_input_probe: diagnostic preload library for the Steam client process (input devices).
 * ezs_input_probe: Steam 클라이언트 프로세스용 진단 preload 라이브러리(입력 장치).
 *
 * Why: the next step gives the Steam client (Steam Input, Big Picture) a controller fed by the app,
 * the way DroidDeck does on Android (fake Steam Deck controller hidraw + fake udev data). How the
 * Linux ARM64 Steam client looks for controllers in this rootfs (which files, libudev or not, SDL or
 * not) is not known yet, so this library only records it. It changes nothing: every call is passed
 * through and only its path and result are written down.
 * 이유: 다음 단계에서 DroidDeck이 안드로이드에서 하는 방식(가짜 Steam Deck 컨트롤러 hidraw + 가짜 udev
 * 정보)처럼 앱이 넣는 컨트롤러를 Steam 클라이언트(Steam Input, 빅픽처)에 주려 함. 이 rootfs에서 Linux
 * ARM64 Steam 클라이언트가 컨트롤러를 어떻게 찾는지(어떤 파일, libudev 사용 여부, SDL 사용 여부)를 아직
 * 모르므로 이 라이브러리는 그것을 기록만 함. 아무것도 바꾸지 않음: 모든 호출을 그대로 넘기고 경로와
 * 결과만 적음.
 *
 * Active only when /proc/self/exe is named "steam" (the client itself, not steamwebhelper or games).
 * Output: $HOME/ezs-logs/steam-input-probe.log, each distinct (call, path, result) once, at most
 * MAX_LINES lines.
 * /proc/self/exe 이름이 "steam"일 때만 동작(클라이언트 자신, steamwebhelper나 게임은 제외).
 * 출력: $HOME/ezs-logs/steam-input-probe.log, (호출, 경로, 결과) 조합마다 한 번, 최대 MAX_LINES줄.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <dirent.h>
#include <errno.h>
#include <link.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define MAX_LINES 3000
#define SEEN_SLOTS 8192

static int active;          /* 1 in the Steam client process / Steam 클라이언트 프로세스에서 1 */
static int log_fd = -1;
static int lines;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static unsigned long seen[SEEN_SLOTS];
static __thread int in_hook;  /* no logging from inside our own calls / 자기 호출 안에서는 기록 안 함 */

static int (*real_open)(const char *, int, ...);
static int (*real_open64)(const char *, int, ...);
static int (*real_openat)(int, const char *, int, ...);
static int (*real_openat64)(int, const char *, int, ...);
static int (*real___open_2)(const char *, int);
static int (*real___open64_2)(const char *, int);
static DIR *(*real_opendir)(const char *);
static int (*real_inotify_add_watch)(int, const char *, uint32_t);
static int (*real_socket)(int, int, int);
static int (*real_access)(const char *, int);
static int (*real_stat)(const char *, struct stat *);

static FILE *(*real_fopen)(const char *, const char *);

/* Next definitions (libc's) of every hooked function, looked up once before the first use
   훅을 건 모든 함수의 다음 정의(libc 것)를 처음 쓰기 전에 한 번 찾음 */
static void resolve(void)
{
    static int done;
    if (done) return;
#define NEXT(name) real_##name = (typeof(real_##name))dlsym(RTLD_NEXT, #name)
    NEXT(open); NEXT(open64); NEXT(openat); NEXT(openat64); NEXT(__open_2); NEXT(__open64_2);
    NEXT(opendir); NEXT(inotify_add_watch); NEXT(socket); NEXT(access); NEXT(stat);
    NEXT(fopen);
#undef NEXT
    done = 1;
}
#define REAL(name) (resolve(), real_##name)

/* Paths that matter for input devices / 입력 장치와 관련된 경로 */
static int interesting(const char *p)
{
    if (!p) return 0;
    static const char *const keys[] = {
        "/dev/hidraw", "/dev/input", "/dev/uinput", "/dev/bus/usb", "/dev/usb",
        "/sys/", "/run/udev", "udev", "hid", "SDL", "joystick", "js0", "event",
    };
    for (size_t i = 0; i < sizeof(keys) / sizeof(keys[0]); i++)
        if (strstr(p, keys[i])) return 1;
    return 0;
}

/* FNV-1a of call + path + result, to write each combination once
   호출 + 경로 + 결과의 FNV-1a. 조합마다 한 번만 쓰기 위함 */
static unsigned long hash3(const char *a, const char *b, long c)
{
    unsigned long h = 1469598103934665603UL;
    for (const char *s = a; *s; s++) h = (h ^ (unsigned char)*s) * 1099511628211UL;
    for (const char *s = b; s && *s; s++) h = (h ^ (unsigned char)*s) * 1099511628211UL;
    h = (h ^ (unsigned long)c) * 1099511628211UL;
    return h ? h : 1;
}

static void put(const char *call, const char *path, long rc, int err)
{
    if (!active || in_hook || log_fd < 0) return;
    in_hook = 1;
    unsigned long h = hash3(call, path, rc < 0 ? -err : (rc > 0 ? 1 : 0));
    pthread_mutex_lock(&lock);
    int fresh = 0;
    for (unsigned i = 0; i < SEEN_SLOTS; i++) {
        unsigned long *s = &seen[(h + i) % SEEN_SLOTS];
        if (*s == h) break;
        if (*s == 0) { *s = h; fresh = 1; break; }
    }
    if (fresh && lines < MAX_LINES) {
        char buf[768];
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        int n = snprintf(buf, sizeof(buf), "%ld.%03ld %s %s -> %ld%s%s\n",
                         (long)ts.tv_sec, ts.tv_nsec / 1000000, call, path ? path : "(null)", rc,
                         rc < 0 ? " " : "", rc < 0 ? strerror(err) : "");
        if (n > 0) {
            if (n > (int)sizeof(buf)) n = sizeof(buf);
            ssize_t w = write(log_fd, buf, (size_t)n);
            (void)w;
            lines++;
        }
    }
    pthread_mutex_unlock(&lock);
    in_hook = 0;
}

/* Which loaded file provides a symbol (libudev / SDL linked or bundled)
   심볼을 주는 불러온 파일(libudev / SDL 링크 또는 내장 여부) */
static void where(const char *sym)
{
    in_hook = 1;
    void *p = dlsym(RTLD_DEFAULT, sym);
    Dl_info di;
    const char *file = (p && dladdr(p, &di) && di.dli_fname) ? di.dli_fname : "(not loaded)";
    in_hook = 0;
    put("symbol", sym, p ? 1 : 0, 0);
    put("symbol-from", file, 0, 0);
}

static void where_all(void)
{
    where("udev_new");
    where("udev_enumerate_scan_devices");
    where("udev_monitor_new_from_netlink");
    where("SDL_Init");
    where("SDL_JoystickOpen");
    where("hid_init");
}

/* Loaded libraries that matter (dlopen is not hooked: a hooked dlopen would look up bare names
   with this library's search path instead of the caller's), written at 20 s and 90 s
   관련 있는 불러온 라이브러리(dlopen은 훅을 걸지 않음: 훅을 걸면 이름만 준 라이브러리를 호출자가
   아니라 이 라이브러리의 탐색 경로로 찾게 됨). 20초, 90초에 기록 */
static int list_lib(struct dl_phdr_info *info, size_t size, void *data)
{
    (void)size; (void)data;
    if (info->dlpi_name && interesting(info->dlpi_name)) put("loaded", info->dlpi_name, 0, 0);
    return 0;
}

static void *later(void *arg)
{
    (void)arg;
    sleep(20);
    dl_iterate_phdr(list_lib, NULL);
    where_all();
    sleep(70);
    dl_iterate_phdr(list_lib, NULL);
    where_all();
    return NULL;
}

__attribute__((constructor))
static void probe_init(void)
{
    char exe[512];
    ssize_t n = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (n <= 0) return;
    exe[n] = 0;
    const char *base = strrchr(exe, '/');
    base = base ? base + 1 : exe;
    if (strcmp(base, "steam") != 0) return;

    const char *home = getenv("HOME");
    if (!home) return;
    char path[600];
    snprintf(path, sizeof(path), "%s/ezs-logs/steam-input-probe.log", home);
    in_hook = 1;
    log_fd = REAL(open)(path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
    in_hook = 0;
    if (log_fd < 0) return;
    active = 1;
    char head[700];
    int m = snprintf(head, sizeof(head), "=== pid %d exe %s\n", (int)getpid(), exe);
    if (m > 0) { ssize_t w = write(log_fd, head, (size_t)m); (void)w; }
    where_all();
    pthread_t t;
    if (pthread_create(&t, NULL, later, NULL) == 0) pthread_detach(t);
}

/* ---- hooks: pass through, then record / 훅: 그대로 넘기고 기록함 ---- */

static mode_t mode_arg(int flags, va_list ap)
{
    return (flags & (O_CREAT | O_TMPFILE)) ? (mode_t)va_arg(ap, int) : 0;
}

int open(const char *p, int flags, ...)
{
    va_list ap; va_start(ap, flags); mode_t m = mode_arg(flags, ap); va_end(ap);
    int r = REAL(open)(p, flags, m); int e = errno;
    if (active && interesting(p)) put("open", p, r, e);
    errno = e; return r;
}

int open64(const char *p, int flags, ...)
{
    va_list ap; va_start(ap, flags); mode_t m = mode_arg(flags, ap); va_end(ap);
    int r = REAL(open64)(p, flags, m); int e = errno;
    if (active && interesting(p)) put("open", p, r, e);
    errno = e; return r;
}

int openat(int d, const char *p, int flags, ...)
{
    va_list ap; va_start(ap, flags); mode_t m = mode_arg(flags, ap); va_end(ap);
    int r = REAL(openat)(d, p, flags, m); int e = errno;
    if (active && interesting(p)) put("openat", p, r, e);
    errno = e; return r;
}

int openat64(int d, const char *p, int flags, ...)
{
    va_list ap; va_start(ap, flags); mode_t m = mode_arg(flags, ap); va_end(ap);
    int r = REAL(openat64)(d, p, flags, m); int e = errno;
    if (active && interesting(p)) put("openat", p, r, e);
    errno = e; return r;
}

int __open_2(const char *p, int flags)
{
    int r = REAL(__open_2)(p, flags); int e = errno;
    if (active && interesting(p)) put("open", p, r, e);
    errno = e; return r;
}

int __open64_2(const char *p, int flags)
{
    int r = REAL(__open64_2)(p, flags); int e = errno;
    if (active && interesting(p)) put("open", p, r, e);
    errno = e; return r;
}

DIR *opendir(const char *p)
{
    DIR *r = REAL(opendir)(p); int e = errno;
    if (active && interesting(p)) put("opendir", p, r ? 0 : -1, e);
    errno = e; return r;
}

int access(const char *p, int mode)
{
    int r = REAL(access)(p, mode); int e = errno;
    if (active && interesting(p)) put("access", p, r, e);
    errno = e; return r;
}

int stat(const char *p, struct stat *st)
{
    int r = REAL(stat)(p, st); int e = errno;
    if (active && interesting(p)) put("stat", p, r, e);
    errno = e; return r;
}

int inotify_add_watch(int fd, const char *p, uint32_t mask)
{
    int r = REAL(inotify_add_watch)(fd, p, mask); int e = errno;
    if (active && interesting(p)) put("inotify", p, r, e);
    errno = e; return r;
}

int socket(int domain, int type, int proto)
{
    int r = REAL(socket)(domain, type, proto); int e = errno;
    /* Netlink kobject uevent = how libudev/SDL watch for new devices
       netlink kobject uevent = libudev/SDL이 새 장치를 감시하는 방법 */
    if (active && domain == AF_NETLINK) {
        char what[32];
        snprintf(what, sizeof(what), "netlink-proto-%d", proto);
        put("socket", what, r, e);
    }
    errno = e; return r;
}

FILE *fopen(const char *p, const char *mode)
{
    FILE *r = REAL(fopen)(p, mode); int e = errno;
    if (active && interesting(p)) put("fopen", p, r ? 0 : -1, e);
    errno = e; return r;
}
