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
struct path_t { char p[160]; uint64_t n, bytes; };
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
            paths[npaths].n = paths[npaths].bytes = 0;
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
    // Busiest "other" paths by write calls; the text is built under the lock and printed after
    // it (printing writes to stderr, which comes back through write() and may need the lock)
    // 쓰기 호출이 많은 "other" 경로. 글은 잠금 안에서 만들고 잠금 밖에서 출력함(출력이 stderr에 쓰며
    // write()를 다시 거쳐 잠금이 필요할 수 있음)
    char top[1400];
    int tl = snprintf(top, sizeof(top), "ezs-io: other 쓰기 상위:");
    int shown = 0;
    pthread_mutex_lock(&plock);
    uint64_t taken[TOP_PATHS] = { 0 };
    int pick[TOP_PATHS];
    for (int r = 0; r < TOP_PATHS; r++) {
        pick[r] = -1;
        // Every slot is checked (unused ones count 0), the overflow slot included
        // 모든 칸을 봄(안 쓰는 칸은 0회), 넘침 칸 포함
        for (int i = 0; i < MAX_PATHS; i++) {
            int used = 0;
            for (int q = 0; q < r; q++) used |= pick[q] == i;
            if (!used && paths[i].n && (pick[r] < 0 || paths[i].n > taken[r])) {
                pick[r] = i;
                taken[r] = paths[i].n;
            }
        }
        if (pick[r] < 0) break;
        struct path_t *pp = &paths[pick[r]];
        tl += snprintf(top + tl, sizeof(top) - (size_t)tl, " %s %llu회 %.2fMB;", pp->p,
                       (unsigned long long)taken[r], pp->bytes / 1e6);
        shown++;
        if (tl >= (int)sizeof(top) - 1) break;
    }
    for (int i = 0; i < MAX_PATHS; i++) paths[i].n = paths[i].bytes = 0;
    // Mostly full: start the table over; descriptors look their path up again
    // 거의 찼으면 표를 새로 시작함. 디스크립터는 경로를 다시 찾음
    if (npaths >= MAX_PATHS * 3 / 4) {
        npaths = 0;
        memset(slot_of, 0, sizeof(slot_of));
    }
    pthread_mutex_unlock(&plock);
    if (shown) {
        if (tl < (int)sizeof(top) && top[tl - 1] == ';') top[tl - 1] = 0;
        fprintf(stderr, "%s\n", top);
    }
}

/* Next symbol of a given name / 같은 이름의 다음 심볼 */
/* (the second argument documents the signature; the type comes from the libc declaration)
 * (두 번째 인자는 시그니처 설명용, 형은 libc 선언에서 가져옴) */
#define REAL(name, type)                                                       \
    static __typeof__(&name) real_##name;                                      \
    if (!real_##name) real_##name = (__typeof__(&name))dlsym(RTLD_NEXT, #name)

/* ---- open family: classify the new descriptor / open 계열: 새 디스크립터를 분류함 ---- */

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
    va_list ap; va_start(ap, flags); mode_t m = mode_arg(flags, ap); va_end(ap);
    uint64_t t0 = now_ns();
    return after_open(real_open(path, flags, m), t0);
}

int open64(const char *path, int flags, ...)
{
    REAL(open64, int (*)(const char *, int, ...));
    va_list ap; va_start(ap, flags); mode_t m = mode_arg(flags, ap); va_end(ap);
    uint64_t t0 = now_ns();
    return after_open(real_open64(path, flags, m), t0);
}

int openat(int dir, const char *path, int flags, ...)
{
    REAL(openat, int (*)(int, const char *, int, ...));
    va_list ap; va_start(ap, flags); mode_t m = mode_arg(flags, ap); va_end(ap);
    uint64_t t0 = now_ns();
    return after_open(real_openat(dir, path, flags, m), t0);
}

int openat64(int dir, const char *path, int flags, ...)
{
    REAL(openat64, int (*)(int, const char *, int, ...));
    va_list ap; va_start(ap, flags); mode_t m = mode_arg(flags, ap); va_end(ap);
    uint64_t t0 = now_ns();
    return after_open(real_openat64(dir, path, flags, m), t0);
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
