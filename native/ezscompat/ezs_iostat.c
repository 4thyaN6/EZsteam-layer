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
#include <dirent.h>
#include <sys/uio.h>
#include <sys/socket.h>
#include <linux/netlink.h>
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
/* Same-answer cache use, inotify results (see the cache section below) / 같은 답 재사용 횟수, inotify 결과(아래 캐시 부분 참고) */
static uint64_t hit_udev, hit_dev, hit_uevent, uevent_ok, hit_ui;
static uint64_t hit_udev_present;  /* /run/udev/control answered present (EZS_UDEV_CTL_PRESENT) / 있음으로 답한 수 */
static uint64_t uevent_stubbed;    /* stand-in uevent sockets given (EZS_UEVENT_STUB) / 내준 uevent 소켓 대역 수 */
static char ino_fail_path[160];
static int ui_n;              /* Steam UI cache entries / Steam 화면 파일 캐시 항목 수 */
static int uevent_errno;          /* errno of the refused socket, 0 = none / 거부된 소켓의 errno, 0 = 없음 */
static uint64_t ino_init_ok, ino_init_fail, ino_add_ok, ino_add_fail;
static int ino_init_errno, ino_add_errno;
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
    {
        // /run/udev/control answered as present this interval / 이번 주기에 /run/udev/control을 있다고 답한 수
        uint64_t up = __atomic_exchange_n(&hit_udev_present, 0, __ATOMIC_RELAXED);
        if (up) fprintf(stderr, "ezs-io: /run/udev/control 있음으로 답함 %llu회(EZS_UDEV_CTL_PRESENT=1)\n", (unsigned long long)up);
        // Stand-in uevent sockets handed out (total since start) / 내준 uevent 소켓 대역 수(시작 뒤 합계)
        uint64_t us = __atomic_load_n(&uevent_stubbed, __ATOMIC_RELAXED);
        if (us) fprintf(stderr, "ezs-io: uevent 소켓 대역 %llu개(시작 뒤 합계)\n", (unsigned long long)us);
    }
    uint64_t ifa = __atomic_exchange_n(&ifaddrs_calls, 0, __ATOMIC_RELAXED);
    if (ifa) fprintf(stderr, "ezs-io: getifaddrs %llu회\n", (unsigned long long)ifa);
    // Same-answer cache use and inotify results / 같은 답 재사용 횟수와 inotify 결과
    {
        uint64_t hu = __atomic_exchange_n(&hit_udev, 0, __ATOMIC_RELAXED);
        uint64_t hd = __atomic_exchange_n(&hit_dev, 0, __ATOMIC_RELAXED);
        uint64_t he = __atomic_exchange_n(&hit_uevent, 0, __ATOMIC_RELAXED);
        uint64_t uo = __atomic_exchange_n(&uevent_ok, 0, __ATOMIC_RELAXED);
        uint64_t hui = __atomic_exchange_n(&hit_ui, 0, __ATOMIC_RELAXED);
        uint64_t io = __atomic_exchange_n(&ino_init_ok, 0, __ATOMIC_RELAXED);
        uint64_t ifl = __atomic_exchange_n(&ino_init_fail, 0, __ATOMIC_RELAXED);
        uint64_t ao = __atomic_exchange_n(&ino_add_ok, 0, __ATOMIC_RELAXED);
        uint64_t af = __atomic_exchange_n(&ino_add_fail, 0, __ATOMIC_RELAXED);
        if (hu || hd || he || uo || hui)
            fprintf(stderr, "ezs-io: 같은 답 재사용: udev 확인 %llu, /dev 상태 %llu, uevent 소켓 %llu(거부 errno %d), uevent 소켓 성공 %llu, Steam 화면 파일 %llu(경로 %d개)\n",
                    (unsigned long long)hu, (unsigned long long)hd, (unsigned long long)he,
                    __atomic_load_n(&uevent_errno, __ATOMIC_RELAXED), (unsigned long long)uo,
                    (unsigned long long)hui, ui_n);
        if (io || ifl || ao || af)
            fprintf(stderr, "ezs-io: inotify init 성공 %llu 실패 %llu(errno %d), add_watch 성공 %llu 실패 %llu(errno %d, 마지막 실패 경로 %s)\n",
                    (unsigned long long)io, (unsigned long long)ifl, ino_init_errno,
                    (unsigned long long)ao, (unsigned long long)af, ino_add_errno,
                    ino_fail_path[0] ? ino_fail_path : "-");
    }
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
static void ui_changed(const char *path);
/* Opening for writing changes the file / 쓰기용 열기는 파일을 바꿈 */
#define OPEN_WRITES(flags) (((flags) & (O_WRONLY | O_RDWR | O_CREAT | O_TRUNC)) != 0)

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
    if (OPEN_WRITES(flags)) ui_changed(path);
    va_list ap; va_start(ap, flags); mode_t m = mode_arg(flags, ap); va_end(ap);
    uint64_t t0 = now_ns();
    return after_open(real_open(path, flags, m), t0);
}

int open64(const char *path, int flags, ...)
{
    REAL(open64, int (*)(const char *, int, ...));
    if (trace_marker(path)) return -1;
    lookup(path, 2);
    if (OPEN_WRITES(flags)) ui_changed(path);
    va_list ap; va_start(ap, flags); mode_t m = mode_arg(flags, ap); va_end(ap);
    uint64_t t0 = now_ns();
    return after_open(real_open64(path, flags, m), t0);
}

int openat(int dir, const char *path, int flags, ...)
{
    REAL(openat, int (*)(int, const char *, int, ...));
    if (trace_marker(path)) return -1;
    lookup(path, 2);
    if (OPEN_WRITES(flags)) ui_changed(path);
    va_list ap; va_start(ap, flags); mode_t m = mode_arg(flags, ap); va_end(ap);
    uint64_t t0 = now_ns();
    return after_open(real_openat(dir, path, flags, m), t0);
}

int openat64(int dir, const char *path, int flags, ...)
{
    REAL(openat64, int (*)(int, const char *, int, ...));
    if (trace_marker(path)) return -1;
    lookup(path, 2);
    if (OPEN_WRITES(flags)) ui_changed(path);
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
    lookup(path, 2);
    if (mode && (mode[0] != 'r' || strchr(mode, '+'))) ui_changed(path);
    return real_fopen(path, mode);
}

FILE *fopen64(const char *path, const char *mode)
{
    REAL(fopen64, FILE *(*)(const char *, const char *));
    if (trace_marker(path)) return NULL;
    lookup(path, 2);
    if (mode && (mode[0] != 'r' || strchr(mode, '+'))) ui_changed(path);
    return real_fopen64(path, mode);
}

static int stand_in_drop(int fd);

int close(int fd)
{
    REAL(close, int (*)(int));
    // A closed stand-in takes its peer with it / 닫히는 대역은 짝도 함께 닫음
    {
        int peer = stand_in_drop(fd);
        if (peer >= 0) real_close(peer);
    }
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

/*
 * Same-answer caches (on by default, EZS_LOOKUP_CACHE=0 turns them off). The client repeats a
 * device check about 55 times a second while idle (2026-10-18): access("/run/udev/control"),
 * stat("/dev") and a netlink uevent socket. Under PRoot each of these stops the tracer. Answers
 * that cannot change here are given again without a system call:
 * - /run/udev/control: no udev daemon runs in this rootfs, so "missing" (ENOENT) stays true
 * - netlink uevent socket: once the system refuses it, the same error is returned again
 * - stat of /dev: the last successful result is reused for 1 s (device appearance is noticed
 *   at most 1 s later)
 * 같은 답 재사용(기본 켬, EZS_LOOKUP_CACHE=0이면 끔). 클라이언트는 대기 중 장치 확인을 초당 약 55번
 * 반복함(2026-10-18): access("/run/udev/control"), stat("/dev"), netlink uevent 소켓. PRoot 아래에서는
 * 이것이 매번 추적기를 멈춤. 여기서 바뀔 수 없는 답은 시스템 호출 없이 다시 줌:
 * - /run/udev/control: 이 rootfs에는 udev 데몬이 없으므로 "없음"(ENOENT)이 계속 맞음
 * - netlink uevent 소켓: 시스템이 한 번 거부하면 같은 오류를 다시 돌려줌
 * - /dev의 stat: 마지막 성공 결과를 1초 동안 다시 씀(장치가 생긴 것은 최대 1초 늦게 알게 됨)
 */
static int cache_on(void)
{
    static int v = -1;
    if (v < 0) {
        const char *e = getenv("EZS_LOOKUP_CACHE");
        v = !(e && strcmp(e, "0") == 0);
    }
    return v;
}

static int udev_missing;          /* 1: ENOENT seen / ENOENT를 봤음 */
static pthread_mutex_t dlock = PTHREAD_MUTEX_INITIALIZER;
static struct stat dev_st;
static uint64_t dev_st_ns;
static struct statx dev_stx;
static uint64_t dev_stx_ns;
static unsigned int dev_stx_mask;
static int dev_stx_flags;
#define DEV_TTL_NS 1000000000ull

/* inotify results (whether file watching works here) / inotify 결과(여기서 파일 감시가 되는지) */

static int is_udev_ctl(const char *p) { return p && strcmp(p, "/run/udev/control") == 0; }
static int is_dev(const char *p) { return p && (strcmp(p, "/dev") == 0 || strcmp(p, "/dev/") == 0); }

/*
 * Steam UI files (Steam/steamui/...): the client checks about 330 of them (scripts, controller
 * images) with access + stat every 2 s or so while idle (2026-10-18). They change only with a
 * client update, which restarts the client, so results are reused for 10 s (success and
 * "missing" only; other errors always go to the system).
 * Steam 화면 파일(Steam/steamui/...): 클라이언트가 대기 중 약 330개(스크립트, 컨트롤러 그림)를 약 2초마다
 * access + stat으로 확인함(2026-10-18). 클라이언트 갱신 때만 바뀌며 갱신하면 클라이언트가 다시 시작하므로
 * 결과를 10초 동안 다시 씀(성공과 "없음"만. 다른 오류는 항상 시스템으로 보냄)
 */
#define UI_SLOTS 1024
#define UI_TTL_NS 10000000000ull
struct ui_t { char p[200]; int amode, a_r, a_err; uint64_t a_ns; int s_flags, s_r, s_err; uint64_t s_ns; struct stat st; };
static struct ui_t *ui_tab;
static pthread_mutex_t uilock = PTHREAD_MUTEX_INITIALIZER;

static int is_ui(const char *p) { return p && p[0] == '/' && strlen(p) < 200 && strstr(p, "/Steam/steamui/") != NULL; }

/*
 * When the Steam UI cache may answer. Not in the first 120 s of a process (the client bootstraps
 * and updates itself then, writing steamui files and checking them right after), and not for
 * 30 s after this process changed anything under steamui (then the table is also emptied).
 * Steam 화면 파일 캐시가 답해도 되는 때. 프로세스 시작 후 120초 동안은 쓰지 않음(그때 클라이언트가 자체
 * 설치·갱신을 하며 steamui 파일을 쓰고 바로 확인함). 이 프로세스가 steamui 아래를 바꾼 뒤 30초 동안도
 * 쓰지 않음(그때 표도 비움)
 */
static uint64_t load_ns, ui_quiet_until, ui_delay_ns = 120000000000ull;
__attribute__((constructor)) static void ezs_iostat_loaded(void)
{
    load_ns = now_ns();
    // EZS_UI_CACHE_DELAY_SEC: start delay for tests (default 120) / 시험용 시작 지연(기본 120)
    const char *d = getenv("EZS_UI_CACHE_DELAY_SEC");
    if (d && *d) ui_delay_ns = (uint64_t)atoi(d) * 1000000000ull;
}

static int ui_cache_ok(void)
{
    uint64_t now = now_ns();
    return now - load_ns >= ui_delay_ns && now >= __atomic_load_n(&ui_quiet_until, __ATOMIC_RELAXED);
}

/* A change under steamui: forget every cached answer and pause the cache
 * steamui 아래 변경: 캐시한 답을 모두 버리고 캐시를 잠시 멈춤 */
static void ui_changed(const char *path)
{
    if (!is_ui(path)) return;
    __atomic_store_n(&ui_quiet_until, now_ns() + 30000000000ull, __ATOMIC_RELAXED);
    pthread_mutex_lock(&uilock);
    if (ui_tab) memset(ui_tab, 0, UI_SLOTS * sizeof(struct ui_t));
    ui_n = 0;
    pthread_mutex_unlock(&uilock);
}

/* Entry for a path, created if there is room; caller holds uilock / 경로의 항목(자리가 있으면 만듦). 호출한 쪽이 uilock을 잡고 있음 */
static struct ui_t *ui_entry(const char *path)
{
    if (!ui_tab) {
        ui_tab = calloc(UI_SLOTS, sizeof(struct ui_t));
        if (!ui_tab) return NULL;
    }
    uint32_t h = 2166136261u;
    for (const char *c = path; *c; c++) h = (h ^ (unsigned char)*c) * 16777619u;
    for (int probe = 0; probe < UI_SLOTS; probe++) {
        struct ui_t *e = &ui_tab[(h + (uint32_t)probe) & (UI_SLOTS - 1)];
        if (!e->p[0]) {
            if (ui_n >= UI_SLOTS * 7 / 8) return NULL;
            strcpy(e->p, path);
            e->a_ns = e->s_ns = 0;
            ui_n++;
            return e;
        }
        if (strcmp(e->p, path) == 0) return e;
    }
    return NULL;
}

/* access of a Steam UI file through the cache / Steam 화면 파일의 access를 캐시로 처리 */
#define UI_ACCESS(call)                                                            do {                                                                               uint64_t now_ = now_ns();                                                      pthread_mutex_lock(&uilock);                                                   struct ui_t *e_ = ui_entry(path);                                              if (e_ && e_->a_ns && now_ - e_->a_ns < UI_TTL_NS && e_->amode == mode) {             int r_ = e_->a_r, er_ = e_->a_err;                                             pthread_mutex_unlock(&uilock);                                                 if (on()) __atomic_add_fetch(&hit_ui, 1, __ATOMIC_RELAXED);                    if (r_ < 0) errno = er_;                                                       return r_;                                                                 }                                                                              pthread_mutex_unlock(&uilock);                                                 int r_ = call;                                                                 int er_ = errno;                                                               if (r_ == 0 || er_ == ENOENT) {                                                    pthread_mutex_lock(&uilock);                                                   e_ = ui_entry(path);                                                           if (e_) { e_->amode = mode; e_->a_r = r_; e_->a_err = er_; e_->a_ns = now_; }             pthread_mutex_unlock(&uilock);                                             }                                                                              errno = er_;                                                                   return r_;                                                                 } while (0)

/*
 * /run/udev/control reported as present (EZS_UDEV_CTL_PRESENT=1, client process "steam" only).
 * Why (2026-10-25 logs + systemd and SDL3 source): every SDL HID init round of the client goes
 * through libudev's udev_monitor_new_from_netlink() -> device_monitor_new_full(), which checks
 * access("/run/udev/control") and, when it is missing, dev_is_devtmpfs(): stat("/dev") plus
 * open/fstat/close of /proc/self/mountinfo. That was the last part of each round that stopped
 * PRoot (about 30,000 rounds a minute in Big Picture; the client itself opens and closes HID that
 * often, a stand-in socket did not reduce it). Said present, that check is skipped; the netlink
 * socket that follows is still refused (answered from the cache above), so the monitor is not made
 * and nothing else changes. No file is created; other programs still see the real answer.
 * /run/udev/control을 있다고 답함(EZS_UDEV_CTL_PRESENT=1, 클라이언트 프로세스 "steam"에서만).
 * 이유(2026-10-25 로그 + systemd·SDL3 소스): 클라이언트의 SDL HID 초기화마다 libudev의
 * udev_monitor_new_from_netlink() -> device_monitor_new_full()을 거치는데, access("/run/udev/control")를
 * 확인하고 없으면 dev_is_devtmpfs()로 stat("/dev")와 /proc/self/mountinfo 열기·fstat·닫기를 함. 이것이 매 회차에서
 * PRoot를 멈추는 마지막 부분이었음(빅픽처에서 분당 약 3만 회. 클라이언트가 스스로 그만큼 HID를 열고 닫으며 대역
 * 소켓으로는 줄지 않았음). 있다고 답하면 그 확인을 건너뜀. 뒤따르는 netlink 소켓은 여전히 거부되므로(위 캐시가 답함)
 * 감시 객체는 만들어지지 않고 그 밖에는 바뀌는 것이 없음. 파일은 만들지 않으며 다른 프로그램은 실제 답을 봄
 */
static int udev_ctl_present(void)
{
    static int v = -1;
    if (v < 0) {
        const char *e = getenv("EZS_UDEV_CTL_PRESENT");
        v = e && strcmp(e, "1") == 0 && strcmp(program_invocation_short_name, "steam") == 0;
    }
    return v;
}

/* access family with the udev answer cache / udev 답 재사용이 있는 access 계열 */
#define ACCESS_CACHED(call)                                                    \
    do {                                                                       \
        if (cache_on() && is_ui(path) && ui_cache_ok()) UI_ACCESS(call);                        \
        if (cache_on() && is_udev_ctl(path)) {                                 \
            if (udev_ctl_present()) {                                          \
                if (on()) __atomic_add_fetch(&hit_udev_present, 1, __ATOMIC_RELAXED); \
                return 0;                                                      \
            }                                                                  \
            if (__atomic_load_n(&udev_missing, __ATOMIC_RELAXED)) {           \
                if (on()) __atomic_add_fetch(&hit_udev, 1, __ATOMIC_RELAXED); \
                errno = ENOENT;                                                \
                return -1;                                                     \
            }                                                                  \
            int r_ = call;                                                     \
            if (r_ < 0 && errno == ENOENT)                                     \
                __atomic_store_n(&udev_missing, 1, __ATOMIC_RELAXED);          \
            return r_;                                                         \
        }                                                                      \
        return call;                                                           \
    } while (0)

/* stat of /dev through the 1 s cache; other paths go straight on (b is a struct stat, same as
 * struct stat64 on aarch64) / /dev의 stat은 1초 캐시를 거침. 다른 경로는 그대로 넘김(b는 struct
 * stat, aarch64에서는 struct stat64와 같음) */
static int dev_stat(int dir, const char *path, struct stat *b, int flags)
{
    REAL(fstatat, int (*)(int, const char *, struct stat *, int));
    if (cache_on() && is_ui(path) && ui_cache_ok()) {
        // Steam UI file: 10 s cache, same flags only / Steam 화면 파일: 10초 캐시, 같은 flags일 때만
        uint64_t now = now_ns();
        pthread_mutex_lock(&uilock);
        struct ui_t *e = ui_entry(path);
        if (e && e->s_ns && now - e->s_ns < UI_TTL_NS && e->s_flags == flags) {
            int r = e->s_r, er = e->s_err;
            if (r == 0) *b = e->st;
            pthread_mutex_unlock(&uilock);
            if (on()) __atomic_add_fetch(&hit_ui, 1, __ATOMIC_RELAXED);
            if (r < 0) errno = er;
            return r;
        }
        pthread_mutex_unlock(&uilock);
        int r = real_fstatat(dir, path, b, flags);
        int er = errno;
        if (r == 0 || er == ENOENT) {
            pthread_mutex_lock(&uilock);
            e = ui_entry(path);
            if (e) {
                e->s_flags = flags;
                e->s_r = r;
                e->s_err = er;
                e->s_ns = now;
                if (r == 0) e->st = *b;
            }
            pthread_mutex_unlock(&uilock);
        }
        errno = er;
        return r;
    }
    if (!cache_on() || !is_dev(path)) return real_fstatat(dir, path, b, flags);
    uint64_t now = now_ns();
    pthread_mutex_lock(&dlock);
    if (dev_st_ns && now - dev_st_ns < DEV_TTL_NS) {
        *b = dev_st;
        pthread_mutex_unlock(&dlock);
        if (on()) __atomic_add_fetch(&hit_dev, 1, __ATOMIC_RELAXED);
        return 0;
    }
    pthread_mutex_unlock(&dlock);
    int r = real_fstatat(dir, path, b, flags);
    if (r == 0) {
        int save = errno;
        pthread_mutex_lock(&dlock);
        dev_st = *b;
        dev_st_ns = now;
        pthread_mutex_unlock(&dlock);
        errno = save;
    }
    return r;
}

int access(const char *path, int mode)
{
    REAL(access, int (*)(const char *, int));
    lookup(path, 0);
    ACCESS_CACHED(real_access(path, mode));
}

int faccessat(int dir, const char *path, int mode, int flags)
{
    REAL(faccessat, int (*)(int, const char *, int, int));
    lookup(path, 0);
    ACCESS_CACHED(real_faccessat(dir, path, mode, flags));
}

int eaccess(const char *path, int mode)
{
    REAL(eaccess, int (*)(const char *, int));
    lookup(path, 0);
    ACCESS_CACHED(real_eaccess(path, mode));
}

int stat(const char *path, struct stat *b)
{
    REAL(stat, int (*)(const char *, struct stat *));
    lookup(path, 1);
    if (is_dev(path) || is_ui(path)) return dev_stat(AT_FDCWD, path, b, 0);
    return real_stat(path, b);
}

int lstat(const char *path, struct stat *b)
{
    REAL(lstat, int (*)(const char *, struct stat *));
    lookup(path, 1);
    if (is_dev(path) || is_ui(path)) return dev_stat(AT_FDCWD, path, b, AT_SYMLINK_NOFOLLOW);
    return real_lstat(path, b);
}

int fstatat(int dir, const char *path, struct stat *b, int flags)
{
    REAL(fstatat, int (*)(int, const char *, struct stat *, int));
    lookup(path, 1);
    if (is_dev(path) || is_ui(path)) return dev_stat(dir, path, b, flags);
    return real_fstatat(dir, path, b, flags);
}

int stat64(const char *path, struct stat64 *b)
{
    REAL(stat64, int (*)(const char *, struct stat64 *));
    lookup(path, 1);
    if (is_dev(path) || is_ui(path)) return dev_stat(AT_FDCWD, path, (struct stat *)b, 0);
    return real_stat64(path, b);
}

int lstat64(const char *path, struct stat64 *b)
{
    REAL(lstat64, int (*)(const char *, struct stat64 *));
    lookup(path, 1);
    if (is_dev(path) || is_ui(path)) return dev_stat(AT_FDCWD, path, (struct stat *)b, AT_SYMLINK_NOFOLLOW);
    return real_lstat64(path, b);
}

int fstatat64(int dir, const char *path, struct stat64 *b, int flags)
{
    REAL(fstatat64, int (*)(int, const char *, struct stat64 *, int));
    lookup(path, 1);
    if (is_dev(path) || is_ui(path)) return dev_stat(dir, path, (struct stat *)b, flags);
    return real_fstatat64(dir, path, b, flags);
}

int statx(int dir, const char *path, int flags, unsigned int mask, struct statx *b)
{
    REAL(statx, int (*)(int, const char *, int, unsigned int, struct statx *));
    lookup(path, 1);
    if (cache_on() && is_dev(path)) {
        // Same 1 s cache for statx, reused only for the same mask and flags
        // statx도 같은 1초 캐시, 같은 mask·flags일 때만 다시 씀
        uint64_t now = now_ns();
        pthread_mutex_lock(&dlock);
        if (dev_stx_ns && now - dev_stx_ns < DEV_TTL_NS && dev_stx_mask == mask && dev_stx_flags == flags) {
            *b = dev_stx;
            pthread_mutex_unlock(&dlock);
            if (on()) __atomic_add_fetch(&hit_dev, 1, __ATOMIC_RELAXED);
            return 0;
        }
        pthread_mutex_unlock(&dlock);
        int r = real_statx(dir, path, flags, mask, b);
        if (r == 0) {
            int save = errno;
            pthread_mutex_lock(&dlock);
            dev_stx = *b;
            dev_stx_ns = now;
            dev_stx_mask = mask;
            dev_stx_flags = flags;
            pthread_mutex_unlock(&dlock);
            errno = save;
        }
        return r;
    }
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
    lookup(path, 1);
    return dev_stat(dir, path, b, flags);
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

/*
 * Quiet stand-in for a refused uevent socket (EZS_UEVENT_STUB=1, client process "steam" only).
 * Why (2026-10-25 logs + SDL3 source): the client's SDL_hid_init() needs SDL_UDEV_Init(), which
 * fails when udev_monitor_new_from_netlink() gets no socket (Android refuses NETLINK_KOBJECT_UEVENT
 * with EACCES). Then SDL_hid_init() returns -1, its use count stays 0, and every HID enumerate
 * calls it again: about 500 rounds a second in Big Picture (each: /run/udev/control,
 * /proc/self/mountinfo, the uevent socket, two console_log lines), 1,800 PRoot stops a second.
 * An unbound AF_UNIX datagram socket lets the monitor be created; it never becomes readable, so
 * no device events arrive (none could here anyway). The monitor's bind later fails, which SDL
 * ignores. Devices are still found by SDL's scan. Off: the refusal is passed on as before.
 * 거부된 uevent 소켓의 조용한 대역(EZS_UEVENT_STUB=1, 클라이언트 프로세스 "steam"에서만).
 * 이유(2026-10-25 로그 + SDL3 소스): 클라이언트의 SDL_hid_init()은 SDL_UDEV_Init()이 필요한데,
 * udev_monitor_new_from_netlink()가 소켓을 못 얻으면 실패함(안드로이드는 NETLINK_KOBJECT_UEVENT를 EACCES로
 * 거부). 그러면 SDL_hid_init()이 -1을 돌려주고 사용 수가 0으로 남아 HID 찾기마다 다시 부름: 빅픽처에서 초당
 * 약 500번(매번 /run/udev/control, /proc/self/mountinfo, uevent 소켓, console_log 두 줄), PRoot 정지 초당 1,800번.
 * 묶지 않은 AF_UNIX 데이터그램 소켓이면 감시 객체가 만들어짐. 읽을 것이 생기지 않으므로 장치 사건은 오지 않음
 * (여기서는 어차피 올 수 없음). 감시의 bind는 나중에 실패하며 SDL은 그것을 무시함. 장치는 SDL 검색으로 여전히
 * 찾음. 끄면 예전처럼 거부를 그대로 넘김
 */
static int uevent_stub(void)
{
    static int v = -1;
    if (v < 0) {
        const char *e = getenv("EZS_UEVENT_STUB");
        v = e && strcmp(e, "1") == 0 && strcmp(program_invocation_short_name, "steam") == 0;
    }
    return v;
}

/*
 * Stand-in table: each stand-in is one end of an AF_UNIX datagram socket pair; the other end stays
 * open so the stand-in never reads as hung up. The calls libudev makes on a netlink monitor socket
 * are answered here for these descriptors without a system call: bind (sockaddr_nl) succeeds,
 * getsockname gives a netlink address with this process id, setsockopt is accepted. Nothing is
 * ever sent to the pair, so no device events arrive (none could arrive here anyway). The 2026-10-25
 * attempt gave a single unbound socket whose bind failed (-22) and the init rounds went on.
 * 대역 표: 대역은 AF_UNIX 데이터그램 소켓 쌍의 한쪽이고 다른 쪽은 열어 두어 끊긴 것으로 보이지 않게 함.
 * libudev가 netlink 감시 소켓에 하는 호출은 이 디스크립터에 대해 시스템 호출 없이 여기서 답함: bind(sockaddr_nl)
 * 성공, getsockname은 이 프로세스 id의 netlink 주소, setsockopt는 받아들임. 쌍에는 아무것도 보내지 않으므로 장치
 * 사건은 오지 않음(여기서는 어차피 올 수 없음). 2026-10-25 시도는 묶지 않은 소켓 하나라 bind가 실패(-22)했고
 * 초기화 반복이 계속됐음
 */
#define STAND_INS 16
static struct { int fd, peer; } stand_in[STAND_INS];
static pthread_mutex_t silock = PTHREAD_MUTEX_INITIALIZER;
static int stand_in_count;  /* entries in use, read without the lock as a fast "none" check / 사용 중 칸 수 */

static int is_stand_in(int fd)
{
    if (fd < 0 || !__atomic_load_n(&stand_in_count, __ATOMIC_RELAXED)) return 0;
    int hit = 0;
    pthread_mutex_lock(&silock);
    for (int i = 0; i < STAND_INS; i++)
        if (stand_in[i].fd == fd && stand_in[i].peer > 0) { hit = 1; break; }
    pthread_mutex_unlock(&silock);
    return hit;
}

/* Forget a stand-in that is being closed; returns its peer to close, or -1 / 닫히는 대역을 잊고 짝을 돌려줌 */
static int stand_in_drop(int fd)
{
    int peer = -1;
    if (fd < 0 || !__atomic_load_n(&stand_in_count, __ATOMIC_RELAXED)) return -1;
    pthread_mutex_lock(&silock);
    for (int i = 0; i < STAND_INS; i++)
        if (stand_in[i].fd == fd && stand_in[i].peer > 0) {
            peer = stand_in[i].peer;
            stand_in[i].fd = stand_in[i].peer = 0;
            __atomic_sub_fetch(&stand_in_count, 1, __ATOMIC_RELAXED);
            break;
        }
    pthread_mutex_unlock(&silock);
    return peer;
}

static int quiet_socket(int (*real)(int, int, int), int type)
{
    (void)real;
    REAL(socketpair, int (*)(int, int, int, int *));
    int sv[2];
    if (real_socketpair(AF_UNIX, SOCK_DGRAM | (type & (SOCK_CLOEXEC | SOCK_NONBLOCK)), 0, sv) != 0) {
        errno = EACCES;
        return -1;
    }
    int slot = -1;
    pthread_mutex_lock(&silock);
    for (int i = 0; i < STAND_INS; i++)
        if (stand_in[i].peer <= 0) { slot = i; break; }
    if (slot >= 0) {
        stand_in[slot].fd = sv[0];
        stand_in[slot].peer = sv[1];
        __atomic_add_fetch(&stand_in_count, 1, __ATOMIC_RELAXED);
    }
    pthread_mutex_unlock(&silock);
    if (slot < 0) {
        // Table full: refuse as the system did / 표가 참: 시스템처럼 거부함
        REAL(close, int (*)(int));
        real_close(sv[0]);
        real_close(sv[1]);
        errno = EACCES;
        return -1;
    }
    // One line the first time, so the log shows the stand-in was used / 처음 한 번 한 줄
    if (__atomic_fetch_add(&uevent_stubbed, 1, __ATOMIC_RELAXED) == 0)
        fprintf(stderr, "ezs-io: uevent 소켓 대역 사용(EZS_UEVENT_STUB=1, 소켓 쌍 + bind/getsockname/setsockopt 응답)\n");
    return sv[0];
}

/* bind on a stand-in: succeeds without a system call / 대역의 bind: 시스템 호출 없이 성공 */
int bind(int fd, const struct sockaddr *a, socklen_t len)
{
    REAL(bind, int (*)(int, const struct sockaddr *, socklen_t));
    if (is_stand_in(fd)) return 0;
    return real_bind(fd, a, len);
}

/* getsockname on a stand-in: a netlink address of this process / 대역의 getsockname: 이 프로세스의 netlink 주소 */
int getsockname(int fd, struct sockaddr *a, socklen_t *len)
{
    REAL(getsockname, int (*)(int, struct sockaddr *, socklen_t *));
    if (is_stand_in(fd) && a && len) {
        struct sockaddr_nl nl;
        memset(&nl, 0, sizeof nl);
        nl.nl_family = AF_NETLINK;
        nl.nl_pid = (unsigned)getpid();
        socklen_t n = *len < (socklen_t)sizeof nl ? *len : (socklen_t)sizeof nl;
        memcpy(a, &nl, n);
        *len = sizeof nl;
        return 0;
    }
    return real_getsockname(fd, a, len);
}

/* setsockopt on a stand-in: accepted, nothing to set on a socket that never receives
 * 대역의 setsockopt: 받아들임. 아무것도 받지 않는 소켓이라 정할 것이 없음 */
int setsockopt(int fd, int level, int name, const void *v, socklen_t len)
{
    REAL(setsockopt, int (*)(int, int, int, const void *, socklen_t));
    if (is_stand_in(fd)) return 0;
    return real_setsockopt(fd, level, name, v, len);
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
    // Netlink uevent (udev monitor) socket: a refusal is answered again without a system call
    // netlink uevent(udev 감시) 소켓: 거부되면 시스템 호출 없이 같은 답을 다시 줌
    if (domain == 16 /* AF_NETLINK */ && proto == 15 /* NETLINK_KOBJECT_UEVENT */ && cache_on()) {
        int e = __atomic_load_n(&uevent_errno, __ATOMIC_RELAXED);
        if (e) {
            if (on()) __atomic_add_fetch(&hit_uevent, 1, __ATOMIC_RELAXED);
            if (uevent_stub()) return quiet_socket(real_socket, type);
            errno = e;
            return -1;
        }
        int r = real_socket(domain, type, proto);
        if (r < 0 && (errno == EACCES || errno == EPERM || errno == EAFNOSUPPORT || errno == EPROTONOSUPPORT)) {
            __atomic_store_n(&uevent_errno, errno, __ATOMIC_RELAXED);
            if (uevent_stub()) return quiet_socket(real_socket, type);
        } else if (r >= 0 && on())
            __atomic_add_fetch(&uevent_ok, 1, __ATOMIC_RELAXED);
        return r;
    }
    return real_socket(domain, type, proto);
}

/* ---- inotify: does file watching work here (counted only) / inotify: 여기서 파일 감시가 되는지(세기만 함) ---- */

static void ino_result(int r, uint64_t *ok, uint64_t *fail, int *err)
{
    if (!on()) return;
    if (r >= 0) __atomic_add_fetch(ok, 1, __ATOMIC_RELAXED);
    else {
        __atomic_add_fetch(fail, 1, __ATOMIC_RELAXED);
        __atomic_store_n(err, errno, __ATOMIC_RELAXED);
    }
}

int inotify_init(void)
{
    REAL(inotify_init, int (*)(void));
    int r = real_inotify_init();
    int save = errno;
    ino_result(r, &ino_init_ok, &ino_init_fail, &ino_init_errno);
    errno = save;
    return r;
}

int inotify_init1(int flags)
{
    REAL(inotify_init1, int (*)(int));
    int r = real_inotify_init1(flags);
    int save = errno;
    ino_result(r, &ino_init_ok, &ino_init_fail, &ino_init_errno);
    errno = save;
    return r;
}

int inotify_add_watch(int fd, const char *path, uint32_t mask)
{
    REAL(inotify_add_watch, int (*)(int, const char *, uint32_t));
    int r = real_inotify_add_watch(fd, path, mask);
    int save = errno;
    ino_result(r, &ino_add_ok, &ino_add_fail, &ino_add_errno);
    // Which path was refused (diagnosis only; a race may mix two paths) / 어느 경로가 거부됐는지(진단용. 경합하면 두 경로가 섞일 수 있음)
    if (r < 0 && on() && path) snprintf(ino_fail_path, sizeof(ino_fail_path), "%s", path);
    errno = save;
    return r;
}

/* Directory opens and symlink reads count as lookups by name (open / stat column)
 * 디렉터리 열기와 심볼릭 링크 읽기도 이름 조회로 셈(open / stat 칸) */
DIR *opendir(const char *path)
{
    REAL(opendir, DIR *(*)(const char *));
    lookup(path, 2);
    return real_opendir(path);
}

ssize_t readlink(const char *path, char *buf, size_t n)
{
    REAL(readlink, ssize_t (*)(const char *, char *, size_t));
    // classify() reads /proc/self/fd links itself; those are not the client's lookups
    // classify()가 /proc/self/fd 링크를 직접 읽으므로 그것은 클라이언트 조회가 아님
    if (strncmp(path, "/proc/self/fd/", 14) != 0) lookup(path, 1);
    return real_readlink(path, buf, n);
}

/* ---- changes under steamui pause the Steam UI cache / steamui 아래 변경은 Steam 화면 파일 캐시를 멈춤 ---- */

int rename(const char *from, const char *to)
{
    REAL(rename, int (*)(const char *, const char *));
    ui_changed(from);
    ui_changed(to);
    return real_rename(from, to);
}

int renameat(int fd1, const char *from, int fd2, const char *to)
{
    REAL(renameat, int (*)(int, const char *, int, const char *));
    ui_changed(from);
    ui_changed(to);
    return real_renameat(fd1, from, fd2, to);
}

int renameat2(int fd1, const char *from, int fd2, const char *to, unsigned int flags)
{
    REAL(renameat2, int (*)(int, const char *, int, const char *, unsigned int));
    ui_changed(from);
    ui_changed(to);
    return real_renameat2(fd1, from, fd2, to, flags);
}

int unlink(const char *path)
{
    REAL(unlink, int (*)(const char *));
    ui_changed(path);
    return real_unlink(path);
}

int unlinkat(int fd, const char *path, int flags)
{
    REAL(unlinkat, int (*)(int, const char *, int));
    ui_changed(path);
    return real_unlinkat(fd, path, flags);
}

int mkdir(const char *path, mode_t mode)
{
    REAL(mkdir, int (*)(const char *, mode_t));
    ui_changed(path);
    return real_mkdir(path, mode);
}

int mkdirat(int fd, const char *path, mode_t mode)
{
    REAL(mkdirat, int (*)(int, const char *, mode_t));
    ui_changed(path);
    return real_mkdirat(fd, path, mode);
}

int rmdir(const char *path)
{
    REAL(rmdir, int (*)(const char *));
    ui_changed(path);
    return real_rmdir(path);
}

int truncate(const char *path, off_t len)
{
    REAL(truncate, int (*)(const char *, off_t));
    ui_changed(path);
    return real_truncate(path, len);
}
