/*
 * ezs_iostat: file I/O timing of the Steam client, split by where the file lives (EZlator's Steam
 * library on shared storage vs. everything else). Diagnosis only: every call is passed on
 * unchanged; once a minute one summary line goes to stderr (steam.log, app log "ezs-io:").
 * ezs_iostat: Steam 클라이언트의 파일 입출력 시간을 파일 위치별(공용 저장소의 EZlator Steam 라이브러리
 * / 그 밖)로 잼. 진단 전용: 모든 호출은 그대로 넘기며, 1분마다 요약 한 줄을 stderr(steam.log, 앱 로그
 * "ezs-io:")에 씀.
 *
 * Why: downloads into the EZlator library start at 145 Mbps and fall to about 3 Mbps after a few
 * minutes (content_log.txt, 2026-10-17 and 2026-10-18), into the rootfs library they stay at
 * 444 Mbps (2026-10-15), while EZlator's own downloader writes into the same shared folder at full
 * speed (user report). So shared storage alone is not slow; this shows which call of the client
 * (write, fsync, open, close, truncate, ...) takes the time there.
 * 이유: EZlator 라이브러리로 받는 다운로드는 145 Mbps로 시작해 몇 분 뒤 약 3 Mbps로 떨어지고
 * (content_log.txt, 2026-10-17, 2026-10-18), rootfs 라이브러리로는 444 Mbps를 유지했음(2026-10-15).
 * EZlator 자체 다운로더는 같은 공용 폴더에 제 속도로 씀(사용자 보고). 그래서 공용 저장소 자체가 느린
 * 것은 아님. 클라이언트의 어느 호출(write, fsync, open, close, truncate 등)이 그곳에서 시간을 쓰는지
 * 보여 줌.
 *
 * Classification: after an open of any kind the descriptor's path is read from /proc/self/fd
 * (PRoot shows the guest path); descriptors this file did not see opened are classified at their
 * first use the same way. Paths under EZS_SHARED_LIBRARY (default /mnt/ezlator-library) count as
 * "shared". Sockets, pipes and other files count as "other" (sockets are left out of the summary).
 * 분류: 어떤 open이든 끝나면 /proc/self/fd에서 디스크립터 경로를 읽음(PRoot는 게스트 경로를 보여 줌).
 * 열리는 것을 보지 못한 디스크립터는 처음 쓰일 때 같은 방식으로 분류함. EZS_SHARED_LIBRARY(기본
 * /mnt/ezlator-library) 아래 경로는 "shared". 소켓·파이프·그 밖의 파일은 "other"(소켓은 요약에서 뺌).
 *
 * Top writers: for "other" files the busiest paths by write calls are listed after each summary
 * ("ezs-io: other 쓰기 상위:"), to find what makes the client's many small writes (100k-230k per
 * minute, 2026-10-18) and part of its idle CPU use.
 * 쓰기 상위: "other" 파일은 요약 뒤에 쓰기 호출이 많은 경로를 나열함("ezs-io: other 쓰기 상위:").
 * 클라이언트의 잦은 작은 쓰기(분당 10만~23만 회, 2026-10-18)와 대기 중 CPU 사용 일부의 출처를 찾기 위함.
 *
 * Trace marker: opens of the kernel trace marker are refused with EACCES (see trace_marker()),
 * always, not only with EZS_IOSTAT=1.
 * 추적 표식: 커널 추적 표식 열기는 EACCES로 거부함(trace_marker() 참고). EZS_IOSTAT=1과 상관없이 항상.
 *
 * Cost: one clock read before and after each counted call (vDSO, no system call) and one readlink
 * per new descriptor. Only in the client process "steam". Off by default, on with EZS_IOSTAT=1
 * (the slow downloads it was made for came from a VPN, not from file I/O, 2026-10-18).
 * 비용: 센 호출마다 앞뒤로 시계 읽기 1번(vDSO, 시스템 호출 아님), 새 디스크립터마다 readlink 1번.
 * 클라이언트 프로세스 "steam"에서만 동작. 기본은 꺼짐, EZS_IOSTAT=1이면 켬
 * (이것을 만든 계기인 느린 다운로드는 파일 입출력이 아니라 VPN 때문이었음, 2026-10-18).
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
#include <sys/types.h>
#include <sys/stat.h>
#include <ifaddrs.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>

/* Descriptor kinds / 디스크립터 종류 */
enum { K_UNKNOWN = 0, K_SHARED = 1, K_FILE = 2, K_SOCKET = 3 };
/* Counted operations / 세는 동작 */
enum { OP_WRITE, OP_READ, OP_OPEN, OP_CLOSE, OP_SYNC, OP_TRUNC, OP_ALLOC, OP_COUNT };
static const char *const OP_NAME[OP_COUNT] = { "write", "read", "open", "close", "sync", "trunc", "alloc" };

#define MAX_FD 65536
static unsigned char kind[MAX_FD];

/* Per place (0 shared, 1 other file) and operation: calls, bytes, ns, max ns, calls >= 100 ms
 * 위치(0 shared, 1 그 밖의 파일)·동작별: 호출 수, 바이트, ns, 최대 ns, 100 ms 이상 호출 수 */
struct stat_t { uint64_t n, bytes, ns, max, slow; };
static struct stat_t st[2][OP_COUNT];

static int enabled = -1;
static char prefix[256] = "/mnt/ezlator-library";
static size_t prefix_len;
static uint64_t last_report;
/* Summary interval, EZS_IOSTAT_SEC (default 60 s) / 요약 주기, EZS_IOSTAT_SEC(기본 60초) */
static uint64_t interval_ns = 60000000000ull;

static uint64_t now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

static int on(void)
{
    if (enabled < 0) {
        const char *e = getenv("EZS_IOSTAT");
        const char *p = getenv("EZS_SHARED_LIBRARY");
        if (p && *p && strlen(p) < sizeof(prefix)) strcpy(prefix, p);
        const char *sec = getenv("EZS_IOSTAT_SEC");
        if (sec && atoi(sec) > 0) interval_ns = (uint64_t)atoi(sec) * 1000000000ull;
        prefix_len = strlen(prefix);
        last_report = now_ns();
        // Only the client process itself ("steam"), which runs the downloads; its helper
        // programs (steamwebhelper and others) are left untouched
        // 다운로드를 하는 클라이언트 프로세스("steam")에서만 켬. 보조 프로그램(steamwebhelper 등)은
        // 건드리지 않음
        enabled = e && strcmp(e, "1") == 0 && strcmp(program_invocation_short_name, "steam") == 0;
    }
    return enabled;
}

static unsigned short path_slot(const char *path);
static unsigned short slot_of[MAX_FD];

/* Kind of a descriptor from its /proc/self/fd link / /proc/self/fd 링크로 디스크립터 종류를 정함 */
static int classify(int fd)
{
    char link[32], path[512];
    if (fd < 0 || fd >= MAX_FD) return K_FILE;
    snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
    int save = errno;
    ssize_t n = readlink(link, path, sizeof(path) - 1);
    errno = save;
    int k = K_FILE;
    if (n > 0) {
        path[n] = 0;
        if (strncmp(path, prefix, prefix_len) == 0 && (path[prefix_len] == '/' || path[prefix_len] == 0)) k = K_SHARED;
        else if (path[0] != '/') k = K_SOCKET; /* socket:[...], pipe:[...], anon_inode:... */
        else slot_of[fd] = path_slot(path);
    }
    kind[fd] = (unsigned char)k;
    return k;
}

static int kind_of(int fd)
{
    if (fd < 0 || fd >= MAX_FD) return K_FILE;
    int k = kind[fd];
    return k ? k : classify(fd);
}

/*
 * Path table for "other" files: each descriptor keeps its path's slot (+1; 0 = not looked up).
 * Slots are added under a lock at open/first use; counters are atomic. When the table is mostly
 * full at a report it is emptied and descriptors look their path up again at their next write.
 * "other" 파일용 경로 표: 디스크립터마다 경로 칸 번호(+1, 0 = 아직 안 찾음)를 둠. 칸은 open이나 첫 사용 때
 * 잠금 안에서 추가하고 카운터는 원자적으로 셈. 요약 때 표가 거의 차 있으면 비우고, 디스크립터는 다음
 * 쓰기 때 경로를 다시 찾음
 */
#define MAX_PATHS 128
#define TOP_PATHS 5
/* n, bytes: writes; qa, qs, qo: lookups by name (access, stat, open) of the client
 * n, bytes: 쓰기. qa, qs, qo: 이름으로 찾은 횟수(access, stat, open) */
struct path_t { char p[160]; uint64_t n, bytes, qa, qs, qo; };
static struct path_t paths[MAX_PATHS];
static int npaths;
static pthread_mutex_t plock = PTHREAD_MUTEX_INITIALIZER;

/* Slot (+1) for a path; the last slot collects paths once the table is full
 * 경로의 칸 번호(+1). 표가 차면 마지막 칸이 나머지 경로를 모음 */
static unsigned short path_slot(const char *path)
{
    size_t n = strlen(path);
    // Long paths keep their tail (the file name tells most) / 긴 경로는 뒤쪽을 남김(파일 이름이 가장 많이 알려 줌)
    char key[160];
    if (n >= sizeof(key)) snprintf(key, sizeof(key), "...%s", path + n - (sizeof(key) - 4));
    else memcpy(key, path, n + 1);
    pthread_mutex_lock(&plock);
    int i;
    for (i = 0; i < npaths; i++)
        if (strcmp(paths[i].p, key) == 0) break;
    if (i == npaths) {
        if (npaths < MAX_PATHS - 1) {
            strcpy(paths[npaths].p, key);
            paths[npaths].n = paths[npaths].bytes = paths[npaths].qa = paths[npaths].qs = paths[npaths].qo = 0;
            npaths++;
        } else {
            i = MAX_PATHS - 1;
            strcpy(paths[i].p, "(그 밖의 경로)");
        }
    }
    pthread_mutex_unlock(&plock);
    return (unsigned short)(i + 1);
}

/* Count one write on an "other" file by its path / "other" 파일 쓰기 하나를 경로별로 셈 */
static int classify(int fd);
static void path_add(int fd, uint64_t bytes)
{
    if (fd < 0 || fd >= MAX_FD) return;
    unsigned short sl = slot_of[fd];
    if (!sl) {
        classify(fd);
        sl = slot_of[fd];
        if (!sl) return;
    }
    __atomic_add_fetch(&paths[sl - 1].n, 1, __ATOMIC_RELAXED);
    __atomic_add_fetch(&paths[sl - 1].bytes, bytes, __ATOMIC_RELAXED);
}

static void report(uint64_t t);
static int build_lookups(char *out, size_t size);
static uint64_t ifaddrs_calls;
/* socket() calls by family and protocol (netlink: protocol number) / 계열·프로토콜별 socket() 호출 수(netlink는 프로토콜 번호) */
#define SOCK_KINDS 8
static struct { int domain, proto; uint64_t n; } socks[SOCK_KINDS];

/*
 * Lookup table (paths looked up by name): separate from the write table and larger, hashed, and
 * emptied at every summary. Digit runs become "#" (/proc/123/stat -> /proc/#/stat) so a scan
 * over numbered entries shows as one pattern. Why: with the shared 128-slot table about 39k of
 * 43k lookups a minute fell into the overflow slot (2026-10-18).
 * 조회 표(이름으로 찾은 경로): 쓰기 표와 따로 두고 더 크게, 해시로, 요약 때마다 비움. 숫자 묶음은 "#"로
 * 바꿈(/proc/123/stat -> /proc/#/stat). 번호 붙은 항목을 훑는 것이 한 형태로 보이게 함. 이유: 같이 쓰던
 * 128칸 표에서는 분당 조회 약 4만 3천 회 중 약 3만 9천 회가 넘침 칸으로 들어갔음(2026-10-18)
 */
#define LOOK_SLOTS 1024
#define LOOK_TOP 8
struct look_t { char p[120]; uint32_t a, s, o; };
static struct look_t looks[LOOK_SLOTS];
static int nlooks;
static uint64_t look_overflow;
static pthread_mutex_t llock = PTHREAD_MUTEX_INITIALIZER;

/*
 * Top TOP_PATHS slots as one line (caller holds plock); what 0 = writes, 1 = lookups by name.
 * Returns the number of entries shown.
 * 상위 TOP_PATHS개 칸을 한 줄로 만듦(호출한 쪽이 plock을 잡고 있음). what 0 = 쓰기, 1 = 이름 조회.
 * 보여 준 항목 수를 돌려줌
 */
static int build_top(char *out, size_t size, const char *title, int what)
{
    int tl = snprintf(out, size, "%s", title), shown = 0;
    int pick[TOP_PATHS];
    for (int r = 0; r < TOP_PATHS; r++) {
        pick[r] = -1;
        uint64_t best = 0;
        // Every slot is checked (unused ones count 0), the overflow slot included
        // 모든 칸을 봄(안 쓰는 칸은 0회), 넘침 칸 포함
        for (int i = 0; i < MAX_PATHS; i++) {
            int used = 0;
            for (int q = 0; q < r; q++) used |= pick[q] == i;
            uint64_t v = what ? paths[i].qa + paths[i].qs + paths[i].qo : paths[i].n;
            if (!used && v > best) {
                pick[r] = i;
                best = v;
            }
        }
        if (pick[r] < 0) break;
        struct path_t *pp = &paths[pick[r]];
        if (what)
            tl += snprintf(out + tl, size - (size_t)tl, " %s %llu회(a %llu s %llu o %llu);", pp->p,
                           (unsigned long long)best, (unsigned long long)pp->qa,
                           (unsigned long long)pp->qs, (unsigned long long)pp->qo);
        else
            tl += snprintf(out + tl, size - (size_t)tl, " %s %llu회 %.2fMB;", pp->p,
                           (unsigned long long)best, pp->bytes / 1e6);
        shown++;
        if (tl >= (int)size - 1) break;
    }
    if (shown && tl < (int)size && out[tl - 1] == ';') out[tl - 1] = 0;
    return shown;
}

/* Add one call to the statistics / 호출 하나를 통계에 더함 */
static void count(int k, int op, uint64_t t0, uint64_t bytes)
{
    if (k == K_SOCKET || k == K_UNKNOWN) return;
    uint64_t t1 = now_ns(), d = t1 - t0;
    struct stat_t *s = &st[k == K_SHARED ? 0 : 1][op];
    __atomic_add_fetch(&s->n, 1, __ATOMIC_RELAXED);
    __atomic_add_fetch(&s->bytes, bytes, __ATOMIC_RELAXED);
    __atomic_add_fetch(&s->ns, d, __ATOMIC_RELAXED);
    if (d >= 100000000ull) __atomic_add_fetch(&s->slow, 1, __ATOMIC_RELAXED);
    uint64_t m = __atomic_load_n(&s->max, __ATOMIC_RELAXED);
    while (d > m && !__atomic_compare_exchange_n(&s->max, &m, d, 1, __ATOMIC_RELAXED, __ATOMIC_RELAXED)) {}
    uint64_t last = __atomic_load_n(&last_report, __ATOMIC_RELAXED);
    if (t1 - last >= interval_ns && __atomic_compare_exchange_n(&last_report, &last, t1, 0, __ATOMIC_RELAXED, __ATOMIC_RELAXED))
        report(t1 - last);
}

/*
 * One line per place with activity: per operation calls, MB, average and max ms, calls >= 100 ms;
 * counters start over after each line
 * 활동이 있는 위치마다 한 줄: 동작별 호출 수, MB, 평균·최대 ms, 100 ms 이상 호출 수. 줄마다 카운터를
 * 새로 시작함
 */
static void report(uint64_t span)
{
    static const char *const PLACE[2] = { "shared", "other" };
    for (int p = 0; p < 2; p++) {
        char line[1024];
        int len = snprintf(line, sizeof(line), "ezs-io: %s %.0fs pid %d:", PLACE[p], span / 1e9, (int)getpid());
        int any = 0;
        for (int op = 0; op < OP_COUNT; op++) {
            struct stat_t s;
            s.n = __atomic_exchange_n(&st[p][op].n, 0, __ATOMIC_RELAXED);
            s.bytes = __atomic_exchange_n(&st[p][op].bytes, 0, __ATOMIC_RELAXED);
            s.ns = __atomic_exchange_n(&st[p][op].ns, 0, __ATOMIC_RELAXED);
            s.max = __atomic_exchange_n(&st[p][op].max, 0, __ATOMIC_RELAXED);
            s.slow = __atomic_exchange_n(&st[p][op].slow, 0, __ATOMIC_RELAXED);
            if (!s.n) continue;
            any = 1;
            len += snprintf(line + len, sizeof(line) - (size_t)len,
                            " %s %llu회 %.1fMB 평균 %.2fms 최대 %.0fms 느림 %llu,",
                            OP_NAME[op], (unsigned long long)s.n, s.bytes / 1e6, s.ns / 1e6 / s.n, s.max / 1e6,
                            (unsigned long long)s.slow);
            if (len >= (int)sizeof(line) - 1) break;
        }
        if (!any) continue;
        if (len > 0 && len < (int)sizeof(line) && line[len - 1] == ',') line[len - 1] = 0;
        fprintf(stderr, "%s\n", line);
    }
    // Busiest "other" paths by write calls, then the paths the client looks up most by name, and
    // the network interface lookups. The text is built under the lock and printed after it
    // (printing writes to stderr, which comes back through write() and may need the lock)
    // 쓰기 호출이 많은 "other" 경로, 클라이언트가 이름으로 가장 많이 찾는 경로, 네트워크 인터페이스 조회 수.
    // 글은 잠금 안에서 만들고 잠금 밖에서 출력함(출력이 stderr에 쓰며 write()를 다시 거쳐 잠금이 필요할 수 있음)
    char top[1400], look[1600];
    pthread_mutex_lock(&plock);
    int shown = build_top(top, sizeof(top), "ezs-io: other 쓰기 상위:", 0);
    for (int i = 0; i < MAX_PATHS; i++) paths[i].n = paths[i].bytes = paths[i].qa = paths[i].qs = paths[i].qo = 0;
    // Mostly full: start the table over; descriptors look their path up again
    // 거의 찼으면 표를 새로 시작함. 디스크립터는 경로를 다시 찾음
    if (npaths >= MAX_PATHS * 3 / 4) {
        npaths = 0;
        memset(slot_of, 0, sizeof(slot_of));
    }
    pthread_mutex_unlock(&plock);
    if (shown) fprintf(stderr, "%s\n", top);
    if (build_lookups(look, sizeof(look))) fprintf(stderr, "%s\n", look);
    uint64_t ifa = __atomic_exchange_n(&ifaddrs_calls, 0, __ATOMIC_RELAXED);
    if (ifa) fprintf(stderr, "ezs-io: getifaddrs %llu회\n", (unsigned long long)ifa);
    // socket() by family (1 unix, 2 inet, 10 inet6, 16 netlink with its protocol: 0 route,
    // 15 kobject uevent = udev monitor) / 계열별 socket()(1 unix, 2 inet, 10 inet6, 16 netlink와
    // 그 프로토콜: 0 route, 15 kobject uevent = udev 감시)
    char sl[400];
    int sn = snprintf(sl, sizeof(sl), "ezs-io: socket");
    int sany = 0;
    for (int i = 0; i < SOCK_KINDS; i++) {
        uint64_t c = __atomic_exchange_n(&socks[i].n, 0, __ATOMIC_RELAXED);
        if (!c) continue;
        sany = 1;
        sn += snprintf(sl + sn, sizeof(sl) - (size_t)sn, " 계열 %d/프로토콜 %d %llu회,", socks[i].domain,
                       socks[i].proto, (unsigned long long)c);
        if (sn >= (int)sizeof(sl) - 1) break;
    }
    if (sany) {
        if (sn < (int)sizeof(sl) && sl[sn - 1] == ',') sl[sn - 1] = 0;
        fprintf(stderr, "%s\n", sl);
    }
}

/* Next symbol of a given name / 같은 이름의 다음 심볼 */
/* (the second argument documents the signature; the type comes from the libc declaration)
 * (두 번째 인자는 시그니처 설명용, 형은 libc 선언에서 가져옴) */
#define REAL(name, type)                                                       \
    static __typeof__(&name) real_##name;                                      \
    if (!real_##name) real_##name = (__typeof__(&name))dlsym(RTLD_NEXT, #name)

/* ---- open family: classify the new descriptor / open 계열: 새 디스크립터를 분류함 ---- */

/*
 * Kernel trace marker (ftrace): the client opens it and writes about 1000 markers a second, all
 * failing (62k writes per minute with 0 bytes on the library screen, 2026-10-18). On a desktop
 * the file is root-only and the open fails with EACCES; the same answer is given here so the
 * client skips its markers. EZS_TRACE_MARKER=1 leaves the open alone.
 * 커널 추적 표식(ftrace): 클라이언트가 이것을 열고 초당 약 1000번 표식을 쓰며 모두 실패함(라이브러리
 * 화면에서 분당 6만 2천 번, 0바이트, 2026-10-18). 데스크톱에서는 이 파일이 root 전용이라 open이
 * EACCES로 실패하므로 여기서도 같은 답을 줘 클라이언트가 표식을 건너뛰게 함. EZS_TRACE_MARKER=1이면
 * 건드리지 않음
 */
static int trace_marker(const char *path)
{
    static int keep = -1;
    if (!path) return 0;
    size_t n = strlen(path);
    static const char tail[] = "/tracing/trace_marker";
    if (n < sizeof(tail) - 1 || strcmp(path + n - (sizeof(tail) - 1), tail) != 0) return 0;
    if (keep < 0) {
        const char *e = getenv("EZS_TRACE_MARKER");
        keep = e && strcmp(e, "1") == 0;
    }
    if (keep) return 0;
    errno = EACCES;
    return 1;
}

static void lookup(const char *path, int kind);

static int after_open(int fd, uint64_t t0)
{
    if (fd >= 0 && on()) count(classify(fd), OP_OPEN, t0, 0);
    return fd;
}

static mode_t mode_arg(int flags, va_list ap)
{
    return (flags & O_CREAT) || (flags & __O_TMPFILE) == __O_TMPFILE ? (mode_t)va_arg(ap, int) : 0;
}

int open(const char *path, int flags, ...)
{
    REAL(open, int (*)(const char *, int, ...));
    if (trace_marker(path)) return -1;
    lookup(path, 2);
    va_list ap; va_start(ap, flags); mode_t m = mode_arg(flags, ap); va_end(ap);
    uint64_t t0 = now_ns();
    return after_open(real_open(path, flags, m), t0);
}

int open64(const char *path, int flags, ...)
{
    REAL(open64, int (*)(const char *, int, ...));
    if (trace_marker(path)) return -1;
    lookup(path, 2);
    va_list ap; va_start(ap, flags); mode_t m = mode_arg(flags, ap); va_end(ap);
    uint64_t t0 = now_ns();
    return after_open(real_open64(path, flags, m), t0);
}

int openat(int dir, const char *path, int flags, ...)
{
    REAL(openat, int (*)(int, const char *, int, ...));
    if (trace_marker(path)) return -1;
    lookup(path, 2);
    va_list ap; va_start(ap, flags); mode_t m = mode_arg(flags, ap); va_end(ap);
    uint64_t t0 = now_ns();
    return after_open(real_openat(dir, path, flags, m), t0);
}

int openat64(int dir, const char *path, int flags, ...)
{
    REAL(openat64, int (*)(int, const char *, int, ...));
    if (trace_marker(path)) return -1;
    lookup(path, 2);
    va_list ap; va_start(ap, flags); mode_t m = mode_arg(flags, ap); va_end(ap);
    uint64_t t0 = now_ns();
    return after_open(real_openat64(dir, path, flags, m), t0);
}

/* stdio opens go to libc's internal open, so the trace marker is refused here too
 * stdio 열기는 libc 내부 open으로 가므로 추적 표식은 여기서도 거부함 */
FILE *fopen(const char *path, const char *mode)
{
    REAL(fopen, FILE *(*)(const char *, const char *));
    if (trace_marker(path)) return NULL;
    return real_fopen(path, mode);
}

FILE *fopen64(const char *path, const char *mode)
{
    REAL(fopen64, FILE *(*)(const char *, const char *));
    if (trace_marker(path)) return NULL;
    return real_fopen64(path, mode);
}

int close(int fd)
{
    REAL(close, int (*)(int));
    if (!on()) return real_close(fd);
    int k = kind_of(fd);
    uint64_t t0 = now_ns();
    int r = real_close(fd);
    int save = errno;
    if (fd >= 0 && fd < MAX_FD) kind[fd] = K_UNKNOWN, slot_of[fd] = 0;
    count(k, OP_CLOSE, t0, 0);
    errno = save;
    return r;
}

/* ---- data calls / 데이터 호출 ---- */

#define TIMED(op, fd, call, bytes_expr)                      \
    do {                                                     \
        if (!on()) return call;                              \
        int k_ = kind_of(fd);                                \
        uint64_t t0_ = now_ns();                             \
        __typeof__(call) r_ = call;                          \
        int save_ = errno;                                   \
        count(k_, op, t0_, r_ > 0 ? (uint64_t)(bytes_expr) : 0); \
        if (op == OP_WRITE && k_ == K_FILE)                  \
            path_add(fd, r_ > 0 ? (uint64_t)(bytes_expr) : 0); \
        errno = save_;                                       \
        return r_;                                           \
    } while (0)

ssize_t write(int fd, const void *b, size_t n)
{
    REAL(write, ssize_t (*)(int, const void *, size_t));
    TIMED(OP_WRITE, fd, real_write(fd, b, n), r_);
}

ssize_t pwrite(int fd, const void *b, size_t n, off_t o)
{
    REAL(pwrite, ssize_t (*)(int, const void *, size_t, off_t));
    TIMED(OP_WRITE, fd, real_pwrite(fd, b, n, o), r_);
}

ssize_t pwrite64(int fd, const void *b, size_t n, off64_t o)
{
    REAL(pwrite64, ssize_t (*)(int, const void *, size_t, off64_t));
    TIMED(OP_WRITE, fd, real_pwrite64(fd, b, n, o), r_);
}

ssize_t writev(int fd, const struct iovec *v, int c)
{
    REAL(writev, ssize_t (*)(int, const struct iovec *, int));
    TIMED(OP_WRITE, fd, real_writev(fd, v, c), r_);
}

ssize_t pwritev(int fd, const struct iovec *v, int c, off_t o)
{
    REAL(pwritev, ssize_t (*)(int, const struct iovec *, int, off_t));
    TIMED(OP_WRITE, fd, real_pwritev(fd, v, c, o), r_);
}

ssize_t read(int fd, void *b, size_t n)
{
    REAL(read, ssize_t (*)(int, void *, size_t));
    TIMED(OP_READ, fd, real_read(fd, b, n), r_);
}

ssize_t pread(int fd, void *b, size_t n, off_t o)
{
    REAL(pread, ssize_t (*)(int, void *, size_t, off_t));
    TIMED(OP_READ, fd, real_pread(fd, b, n, o), r_);
}

ssize_t pread64(int fd, void *b, size_t n, off64_t o)
{
    REAL(pread64, ssize_t (*)(int, void *, size_t, off64_t));
    TIMED(OP_READ, fd, real_pread64(fd, b, n, o), r_);
}

/* ---- sync, size, space / 동기화, 크기, 공간 ---- */

int fsync(int fd)
{
    REAL(fsync, int (*)(int));
    TIMED(OP_SYNC, fd, real_fsync(fd), 0);
}

int fdatasync(int fd)
{
    REAL(fdatasync, int (*)(int));
    TIMED(OP_SYNC, fd, real_fdatasync(fd), 0);
}

int ftruncate(int fd, off_t len)
{
    REAL(ftruncate, int (*)(int, off_t));
    TIMED(OP_TRUNC, fd, real_ftruncate(fd, len), 0);
}

int ftruncate64(int fd, off64_t len)
{
    REAL(ftruncate64, int (*)(int, off64_t));
    TIMED(OP_TRUNC, fd, real_ftruncate64(fd, len), 0);
}

int fallocate(int fd, int mode, off_t o, off_t len)
{
    REAL(fallocate, int (*)(int, int, off_t, off_t));
    TIMED(OP_ALLOC, fd, real_fallocate(fd, mode, o, len), 0);
}

int fallocate64(int fd, int mode, off64_t o, off64_t len)
{
    REAL(fallocate64, int (*)(int, int, off64_t, off64_t));
    TIMED(OP_ALLOC, fd, real_fallocate64(fd, mode, o, len), 0);
}

int posix_fallocate(int fd, off_t o, off_t len)
{
    REAL(posix_fallocate, int (*)(int, off_t, off_t));
    TIMED(OP_ALLOC, fd, real_posix_fallocate(fd, o, len), 0);
}

int posix_fallocate64(int fd, off64_t o, off64_t len)
{
    REAL(posix_fallocate64, int (*)(int, off64_t, off64_t));
    TIMED(OP_ALLOC, fd, real_posix_fallocate64(fd, o, len), 0);
}

/* ---- lookups by name: which paths the client keeps checking / 이름으로 찾기: 클라이언트가 계속 확인하는 경로 ---- */

/* Count one lookup of a path (kind 0 access, 1 stat, 2 open) / 경로 찾기 하나를 셈(kind 0 access, 1 stat, 2 open) */
static void lookup(const char *path, int kind)
{
    if (!path || !*path || !on()) return;
    // Normalised key (digit runs -> '#', long paths keep their tail) and its FNV-1a hash
    // 정규화한 키(숫자 묶음 -> '#', 긴 경로는 뒤쪽 유지)와 FNV-1a 해시
    char key[120];
    size_t n = strlen(path);
    const char *src = n >= sizeof(key) - 4 ? path + n - (sizeof(key) - 8) : path;
    size_t k = 0;
    if (src != path) { memcpy(key, "...", 3); k = 3; }
    for (; *src && k < sizeof(key) - 1; src++) {
        if (*src >= '0' && *src <= '9') {
            if (k == 0 || key[k - 1] != '#') key[k++] = '#';
        } else {
            key[k++] = *src;
        }
    }
    key[k] = 0;
    uint32_t h = 2166136261u;
    for (size_t i = 0; i < k; i++) h = (h ^ (unsigned char)key[i]) * 16777619u;
    pthread_mutex_lock(&llock);
    struct look_t *e = NULL;
    for (int probe = 0; probe < LOOK_SLOTS; probe++) {
        struct look_t *c = &looks[(h + (uint32_t)probe) & (LOOK_SLOTS - 1)];
        if (!c->p[0]) {
            // New entry while the table is at most 7/8 full / 표가 7/8 이하일 때만 새 항목
            if (nlooks < LOOK_SLOTS * 7 / 8) {
                memcpy(c->p, key, k + 1);
                c->a = c->s = c->o = 0;
                nlooks++;
                e = c;
            }
            break;
        }
        if (strcmp(c->p, key) == 0) { e = c; break; }
    }
    if (e) {
        if (kind == 0) e->a++;
        else if (kind == 1) e->s++;
        else e->o++;
    } else {
        look_overflow++;
    }
    pthread_mutex_unlock(&llock);
}

/* Top lookups as one line, then the table is emptied / 조회 상위를 한 줄로 만든 뒤 표를 비움 */
static int build_lookups(char *out, size_t size)
{
    pthread_mutex_lock(&llock);
    uint64_t total = look_overflow;
    for (int i = 0; i < LOOK_SLOTS; i++) total += looks[i].a + looks[i].s + looks[i].o;
    int tl = snprintf(out, size, "ezs-io: 이름 조회 %llu회, 경로 %d개, 상위:", (unsigned long long)total, nlooks);
    int pick[LOOK_TOP], shown = 0;
    for (int r = 0; r < LOOK_TOP && total; r++) {
        pick[r] = -1;
        uint32_t best = 0;
        for (int i = 0; i < LOOK_SLOTS; i++) {
            int used = 0;
            for (int q = 0; q < r; q++) used |= pick[q] == i;
            uint32_t v = looks[i].a + looks[i].s + looks[i].o;
            if (!used && v > best) { pick[r] = i; best = v; }
        }
        if (pick[r] < 0) break;
        struct look_t *e = &looks[pick[r]];
        tl += snprintf(out + tl, size - (size_t)tl, " %s %u(a %u s %u o %u);", e->p, best, e->a, e->s, e->o);
        shown++;
        if (tl >= (int)size - 1) break;
    }
    if (look_overflow && tl < (int)size - 40)
        tl += snprintf(out + tl, size - (size_t)tl, " 표 넘침 %llu;", (unsigned long long)look_overflow);
    if (tl > 0 && tl < (int)size && out[tl - 1] == ';') out[tl - 1] = 0;
    memset(looks, 0, sizeof(looks));
    nlooks = 0;
    look_overflow = 0;
    pthread_mutex_unlock(&llock);
    return shown;
}

int access(const char *path, int mode)
{
    REAL(access, int (*)(const char *, int));
    lookup(path, 0);
    return real_access(path, mode);
}

int faccessat(int dir, const char *path, int mode, int flags)
{
    REAL(faccessat, int (*)(int, const char *, int, int));
    lookup(path, 0);
    return real_faccessat(dir, path, mode, flags);
}

int eaccess(const char *path, int mode)
{
    REAL(eaccess, int (*)(const char *, int));
    lookup(path, 0);
    return real_eaccess(path, mode);
}

int stat(const char *path, struct stat *b)
{
    REAL(stat, int (*)(const char *, struct stat *));
    lookup(path, 1);
    return real_stat(path, b);
}

int lstat(const char *path, struct stat *b)
{
    REAL(lstat, int (*)(const char *, struct stat *));
    lookup(path, 1);
    return real_lstat(path, b);
}

int fstatat(int dir, const char *path, struct stat *b, int flags)
{
    REAL(fstatat, int (*)(int, const char *, struct stat *, int));
    lookup(path, 1);
    return real_fstatat(dir, path, b, flags);
}

int stat64(const char *path, struct stat64 *b)
{
    REAL(stat64, int (*)(const char *, struct stat64 *));
    lookup(path, 1);
    return real_stat64(path, b);
}

int lstat64(const char *path, struct stat64 *b)
{
    REAL(lstat64, int (*)(const char *, struct stat64 *));
    lookup(path, 1);
    return real_lstat64(path, b);
}

int fstatat64(int dir, const char *path, struct stat64 *b, int flags)
{
    REAL(fstatat64, int (*)(int, const char *, struct stat64 *, int));
    lookup(path, 1);
    return real_fstatat64(dir, path, b, flags);
}

int statx(int dir, const char *path, int flags, unsigned int mask, struct statx *b)
{
    REAL(statx, int (*)(int, const char *, int, unsigned int, struct statx *));
    lookup(path, 1);
    return real_statx(dir, path, flags, mask, b);
}

/*
 * Programs built against glibc older than 2.33 call __xstat/__lxstat/__fxstatat instead of
 * stat/lstat/fstatat (compat symbols, not found by dlsym); on aarch64 the structure is the same,
 * so they are answered with fstatat.
 * glibc 2.33보다 오래된 glibc로 빌드한 프로그램은 stat/lstat/fstatat 대신 __xstat/__lxstat/__fxstatat를
 * 부름(호환 심볼이라 dlsym으로 찾지 못함). aarch64에서는 구조체가 같으므로 fstatat로 답함
 */
static int compat_stat(int dir, const char *path, struct stat *b, int flags)
{
    REAL(fstatat, int (*)(int, const char *, struct stat *, int));
    lookup(path, 1);
    return real_fstatat(dir, path, b, flags);
}

int __xstat(int ver, const char *path, struct stat *b) { (void)ver; return compat_stat(AT_FDCWD, path, b, 0); }
int __lxstat(int ver, const char *path, struct stat *b) { (void)ver; return compat_stat(AT_FDCWD, path, b, AT_SYMLINK_NOFOLLOW); }
int __fxstatat(int ver, int dir, const char *path, struct stat *b, int flags) { (void)ver; return compat_stat(dir, path, b, flags); }
int __xstat64(int ver, const char *path, struct stat64 *b) { (void)ver; return compat_stat(AT_FDCWD, path, (struct stat *)b, 0); }
int __lxstat64(int ver, const char *path, struct stat64 *b) { (void)ver; return compat_stat(AT_FDCWD, path, (struct stat *)b, AT_SYMLINK_NOFOLLOW); }
int __fxstatat64(int ver, int dir, const char *path, struct stat64 *b, int flags) { (void)ver; return compat_stat(dir, path, (struct stat *)b, flags); }

/* Network interface list (netlink socket + messages under PRoot) / 네트워크 인터페이스 목록(PRoot 아래에서 netlink 소켓 + 메시지) */
int getifaddrs(struct ifaddrs **out)
{
    REAL(getifaddrs, int (*)(struct ifaddrs **));
    if (on()) __atomic_add_fetch(&ifaddrs_calls, 1, __ATOMIC_RELAXED);
    return real_getifaddrs(out);
}

/* Count socket() by family / 계열별 socket() 수를 셈 */
int socket(int domain, int type, int proto)
{
    REAL(socket, int (*)(int, int, int));
    if (on()) {
        int pr = domain == 16 ? proto : 0;
        // Fixed slots are claimed once per kind (a race only splits a count) / 종류마다 칸을 한 번 잡음(경합해도 수가 나뉠 뿐임)
        for (int i = 0; i < SOCK_KINDS; i++) {
            if (socks[i].n && socks[i].domain == domain && socks[i].proto == pr) {
                __atomic_add_fetch(&socks[i].n, 1, __ATOMIC_RELAXED);
                break;
            }
            if (!socks[i].n) {
                socks[i].domain = domain;
                socks[i].proto = pr;
                __atomic_add_fetch(&socks[i].n, 1, __ATOMIC_RELAXED);
                break;
            }
        }
    }
    return real_socket(domain, type, proto);
}
