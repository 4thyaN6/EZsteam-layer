/*
 * futex_waitv stand-in for Wine's fsync (LD_PRELOAD, Wine processes and wineserver only).
 * Wine의 fsync용 futex_waitv 대체(LD_PRELOAD, Wine 프로세스와 wineserver에만).
 *
 * Why: on this device /dev/ntsync is missing and futex_waitv (syscall 449) answers ENOSYS, so
 * Proton falls back to server-side synchronization and wineserver used about 50 % of a core
 * (device check, 2026-10-23). Valve's wine (proton_11.0) calls futex_waitv and the futex wake
 * through glibc's syscall() wrapper (dlls/ntdll/unix/fsync.c, server/fsync.c), so syscall() is
 * wrapped here:
 *  - futex_waitv with one futex  -> FUTEX_WAIT_BITSET on that futex (absolute timeout, clock as
 *    asked); the waker's own FUTEX_WAKE reaches it directly
 *  - futex_waitv with more       -> wait on one shared "doorbell" word: read it, check every
 *    futex value, sleep on the doorbell if none changed; every shared FUTEX_WAKE also bumps
 *    and wakes the doorbell (only when someone sleeps on it). Reading the doorbell before the
 *    checks means no wake can be lost.
 *  - the probe call with no futexes -> EINVAL, like a kernel that has futex_waitv
 *  - everything else passes through unchanged
 * The doorbell lives in /dev/shm (bound to an app folder), shared by the server and clients.
 * Off unless EZ Proton puts this library into LD_PRELOAD (test switch in the app).
 *
 * 이유: 이 기기에는 /dev/ntsync가 없고 futex_waitv(시스템 호출 449)가 ENOSYS를 돌려줘, Proton이
 * server-side 동기화로 돌아가 wineserver가 코어 하나의 약 50%를 썼음(기기 확인, 2026-10-23). Valve
 * wine(proton_11.0)은 futex_waitv와 futex 깨우기를 glibc syscall() 감쌈으로 부름(dlls/ntdll/unix/
 * fsync.c, server/fsync.c). 그래서 여기서 syscall()을 감쌈:
 *  - futex 하나의 futex_waitv -> 그 futex에 FUTEX_WAIT_BITSET(절대 시각 제한, 요청한 시계). 깨우는 쪽의
 *    FUTEX_WAKE가 바로 닿음
 *  - 여러 개                   -> 공유 "초인종" 값 하나에서 기다림: 그것을 읽고, 모든 futex 값을 확인하고,
 *    바뀐 것이 없으면 초인종에서 잠. 공유 FUTEX_WAKE마다 초인종도 올리고 깨움(잠든 쪽이 있을 때만).
 *    확인보다 초인종을 먼저 읽으므로 깨움을 놓치지 않음
 *  - futex 없는 시험 호출        -> futex_waitv가 있는 커널처럼 EINVAL
 *  - 나머지는 그대로 넘김
 * 초인종은 /dev/shm(앱 폴더에 연결됨)에 있어 서버와 클라이언트가 함께 씀.
 * EZ Proton이 이 라이브러리를 LD_PRELOAD에 넣을 때만 동작함(앱의 시험 스위치)
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/futex.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#ifndef __NR_futex_waitv
#define __NR_futex_waitv 449
#endif

/* Kernel layout of one entry / 항목 하나의 커널 구조 */
struct ezs_waitv {
    uint64_t val;
    uint64_t uaddr;
    uint32_t flags;
    uint32_t reserved;
};

/* Shared page: doorbell sequence and the number of doorbell sleepers
 * 공유 페이지: 초인종 순번과 초인종에서 자는 수 */
struct ezs_bell {
    uint32_t seq;
    uint32_t sleepers;
};

static struct ezs_bell *bell;
static int bell_tried;

/* Raw syscall without going through ourselves / 자기 자신을 거치지 않는 원래 시스템 호출 */
static long raw(long n, long a, long b, long c, long d, long e, long f)
{
    static long (*next)(long, ...);
    if (!next) next = (long (*)(long, ...))dlsym(RTLD_NEXT, "syscall");
    return next(n, a, b, c, d, e, f);
}

/* Map the doorbell once (first use) / 초인종을 한 번 매핑(처음 쓸 때) */
static struct ezs_bell *get_bell(void)
{
    if (bell || bell_tried) return bell;
    bell_tried = 1;
    int fd = open("/dev/shm/ezs-fsync-bell", O_RDWR | O_CREAT | O_CLOEXEC, 0666);
    if (fd < 0) return NULL;
    struct stat st;
    if (fstat(fd, &st) == 0 && st.st_size < (off_t)sizeof(struct ezs_bell)) {
        if (ftruncate(fd, 4096) != 0) {
            close(fd);
            return NULL;
        }
    }
    void *p = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (p == MAP_FAILED) return NULL;
    bell = (struct ezs_bell *)p;
    return bell;
}

/* Absolute-timeout wait on one 32-bit futex (shared), clock as given
 * 32비트 futex 하나에서 절대 시각까지 기다림(공유), 시계는 주어진 것 */
static long wait_abs(uint32_t *addr, uint32_t val, const struct timespec *end, int clock_id)
{
    int op = FUTEX_WAIT_BITSET | (clock_id == CLOCK_REALTIME ? FUTEX_CLOCK_REALTIME : 0);
    return raw(SYS_futex, (long)addr, op, (long)val, (long)end, 0, (long)FUTEX_BITSET_MATCH_ANY);
}

static long emulate_waitv(const struct ezs_waitv *w, long count, long flags, const struct timespec *end,
                          long clock_id)
{
    (void)flags;
    /* Probe (Wine's startup check) or bad call: EINVAL like the real call
     * 시험 호출(Wine 시작 확인) 또는 잘못된 호출: 실제 호출처럼 EINVAL */
    if (!w || count <= 0 || count > 128) {
        errno = EINVAL;
        return -1;
    }
    if (count == 1) {
        long r = wait_abs((uint32_t *)(uintptr_t)w[0].uaddr, (uint32_t)w[0].val, end, (int)clock_id);
        return r == 0 ? 0 : -1; /* errno from the call: EAGAIN, ETIMEDOUT, EINTR / 호출의 errno 그대로 */
    }
    struct ezs_bell *b = get_bell();
    if (!b) {
        errno = ENOSYS;
        return -1;
    }
    /* Value already different at entry: EAGAIN like the kernel / 들어올 때 이미 다르면 커널처럼 EAGAIN */
    for (long i = 0; i < count; i++) {
        if (__atomic_load_n((uint32_t *)(uintptr_t)w[i].uaddr, __ATOMIC_SEQ_CST) != (uint32_t)w[i].val) {
            errno = EAGAIN;
            return -1;
        }
    }
    __atomic_add_fetch(&b->sleepers, 1, __ATOMIC_SEQ_CST);
    long ret = -1;
    int err = 0;
    for (;;) {
        uint32_t seq = __atomic_load_n(&b->seq, __ATOMIC_SEQ_CST);
        long hit = -1;
        for (long i = 0; i < count; i++) {
            if (__atomic_load_n((uint32_t *)(uintptr_t)w[i].uaddr, __ATOMIC_SEQ_CST) != (uint32_t)w[i].val) {
                hit = i;
                break;
            }
        }
        if (hit >= 0) {
            ret = hit;
            break;
        }
        long r = wait_abs(&b->seq, seq, end, (int)clock_id);
        if (r != 0 && errno == ETIMEDOUT) {
            err = ETIMEDOUT;
            break;
        }
        if (r != 0 && errno == EINTR) {
            err = EINTR;
            break;
        }
        /* woken or doorbell moved on: check again / 깨어남 또는 초인종이 넘어감: 다시 확인 */
    }
    __atomic_sub_fetch(&b->sleepers, 1, __ATOMIC_SEQ_CST);
    if (ret < 0) errno = err;
    return ret;
}

long syscall(long number, ...)
{
    va_list ap;
    va_start(ap, number);
    long a = va_arg(ap, long), b = va_arg(ap, long), c = va_arg(ap, long);
    long d = va_arg(ap, long), e = va_arg(ap, long), f = va_arg(ap, long);
    va_end(ap);
    if (number == __NR_futex_waitv)
        return emulate_waitv((const struct ezs_waitv *)a, b, c, (const struct timespec *)d, e);
    long r = raw(number, a, b, c, d, e, f);
    /* Shared FUTEX_WAKE (op 1, what Wine's fsync uses): ring the doorbell for multi-waiters
     * 공유 FUTEX_WAKE(op 1, Wine fsync가 씀): 여러 개를 기다리는 쪽을 위해 초인종을 울림 */
    if (number == SYS_futex && (b & FUTEX_CMD_MASK) == FUTEX_WAKE && !(b & FUTEX_PRIVATE_FLAG)) {
        int saved = errno;
        struct ezs_bell *bl = get_bell();
        if (bl) {
            __atomic_add_fetch(&bl->seq, 1, __ATOMIC_SEQ_CST);
            if (__atomic_load_n(&bl->sleepers, __ATOMIC_SEQ_CST) != 0)
                raw(SYS_futex, (long)&bl->seq, FUTEX_WAKE, INT_MAX, 0, 0, 0);
        }
        errno = saved;
    }
    return r;
}
