/*
 * libezs_crash.so: LD_PRELOAD crash reporter for diagnosis (own code, MIT). On SIGSEGV, SIGBUS,
 * SIGILL, SIGFPE, SIGABRT and SIGTRAP it writes one block: process, thread, signal, fault address,
 * the program counter as library + offset (+ symbol when exported) and a short backtrace, to stderr
 * and appended to /tmp/ezs-crash.log (kept across restarts, picked up by the app report). Then the
 * program's own handler runs as before (for example Chromium's crash reporter), or the default action.
 * Programs that install their own handlers (sigaction) do not push this one out: their handler is
 * kept and called after the report.
 * Used only where it is asked for: the GPU path check (gpu-check.sh) and the Steam web helper in the
 * Zink GL test (ezs-webhelper-env.sh). Not used for Wine/Proton.
 * libezs_crash.so: 진단용 LD_PRELOAD 크래시 보고기(자체 코드, MIT). SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT, SIGTRAP을 받으면
 * 프로세스, 스레드, 시그널, 오류 주소, 프로그램 카운터를 라이브러리 + 오프셋(내보낸 심볼이면 이름도)과 짧은 호출 경로로 한
 * 덩어리 써서 stderr와 /tmp/ezs-crash.log(다시 시작해도 남음, 앱 제보에 들어감)에 남김. 그다음 프로그램 자체 처리기(예:
 * Chromium 크래시 보고)가 원래대로 돌거나 기본 동작을 함. 프로그램이 자기 처리기를 설치(sigaction)해도 이것을 밀어내지 않음:
 * 그 처리기는 보관했다가 보고 뒤에 부름.
 * 요청한 곳에서만 씀: GPU 경로 점검(gpu-check.sh)과 Zink GL 시험의 Steam 웹 도우미(ezs-webhelper-env.sh). Wine/Proton에는
 * 쓰지 않음
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <execinfo.h>
#include <fcntl.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

static const int SIGS[] = {SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT, SIGTRAP};
#define NSIG_WATCH (sizeof(SIGS) / sizeof(SIGS[0]))

/* The program's own handlers, kept here / 프로그램 자체 처리기를 여기에 보관함 */
static struct sigaction chained[65];
static int have_chained[65];
static int (*real_sigaction)(int, const struct sigaction *, struct sigaction *);
static volatile sig_atomic_t in_report;

static int watched(int sig)
{
    for (unsigned i = 0; i < NSIG_WATCH; i++)
        if (SIGS[i] == sig) return 1;
    return 0;
}

/* Small formatted write without stdio buffering / stdio 버퍼 없이 짧게 형식화해 씀 */
static void out(int fd, const char *fmt, ...)
{
    char b[512];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(b, sizeof(b), fmt, ap);
    va_end(ap);
    if (n < 0) return;
    if (n > (int)sizeof(b)) n = sizeof(b);
    ssize_t w = write(fd, b, (size_t)n);
    (void)w;
}

static void where(int fd, const char *tag, void *pc)
{
    Dl_info di;
    if (pc && dladdr(pc, &di) && di.dli_fname) {
        const char *lib = strrchr(di.dli_fname, '/');
        lib = lib ? lib + 1 : di.dli_fname;
        uintptr_t off = (uintptr_t)pc - (uintptr_t)di.dli_fbase;
        if (di.dli_sname)
            out(fd, "ezs-crash:   %s %p %s+0x%lx (%s+0x%lx)\n", tag, pc, lib, (unsigned long)off, di.dli_sname,
                (unsigned long)((uintptr_t)pc - (uintptr_t)di.dli_saddr));
        else
            out(fd, "ezs-crash:   %s %p %s+0x%lx\n", tag, pc, lib, (unsigned long)off);
    } else {
        out(fd, "ezs-crash:   %s %p (?)\n", tag, pc);
    }
}

static void report(int fd, int sig, siginfo_t *si, void *ucv)
{
    char comm[32] = "?";
    int cf = open("/proc/self/comm", O_RDONLY | O_CLOEXEC);
    if (cf >= 0) {
        ssize_t n = read(cf, comm, sizeof(comm) - 1);
        comm[n > 0 ? n : 0] = 0;
        char *nl = strchr(comm, '\n');
        if (nl) *nl = 0;
        close(cf);
    }
    out(fd, "ezs-crash: %s pid %d tid %ld signal %d (%s) code %d addr %p\n", comm, getpid(),
        (long)syscall(SYS_gettid), sig, strsignal(sig), si ? si->si_code : 0, si ? si->si_addr : NULL);
    void *pc = NULL;
#if defined(__aarch64__)
    if (ucv) pc = (void *)((ucontext_t *)ucv)->uc_mcontext.pc;
    if (ucv) where(fd, "lr", (void *)((ucontext_t *)ucv)->uc_mcontext.regs[30]);
#elif defined(__x86_64__)
    if (ucv) pc = (void *)((ucontext_t *)ucv)->uc_mcontext.gregs[REG_RIP];
#endif
    where(fd, "pc", pc);
    void *bt[24];
    int n = backtrace(bt, 24);
    for (int i = 0; i < n; i++) where(fd, "bt", bt[i]);
}

static void handler(int sig, siginfo_t *si, void *uc)
{
    if (!in_report) {
        in_report = 1;
        report(2, sig, si, uc);
        int lf = open("/tmp/ezs-crash.log", O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0666);
        if (lf >= 0) {
            report(lf, sig, si, uc);
            close(lf);
        }
        in_report = 0;
    }
    /* Then what the program asked for / 그다음 프로그램이 요청한 처리 */
    struct sigaction *c = have_chained[sig] ? &chained[sig] : NULL;
    if (c && (c->sa_flags & SA_SIGINFO) && c->sa_sigaction) {
        c->sa_sigaction(sig, si, uc);
        return;
    }
    if (c && c->sa_handler != SIG_DFL && c->sa_handler != SIG_IGN && c->sa_handler) {
        c->sa_handler(sig);
        return;
    }
    /* Default action: put it back and deliver again / 기본 동작: 되돌리고 다시 보냄 */
    struct sigaction d;
    memset(&d, 0, sizeof(d));
    d.sa_handler = SIG_DFL;
    real_sigaction(sig, &d, NULL);
    raise(sig);
}

/* Handlers installed by the program are kept, ours stays in place
   프로그램이 설치하는 처리기는 보관하고, 이 처리기는 그대로 둠 */
int sigaction(int sig, const struct sigaction *act, struct sigaction *old)
{
    if (!real_sigaction) real_sigaction = dlsym(RTLD_NEXT, "sigaction");
    if (sig > 0 && sig < 65 && watched(sig)) {
        if (old) {
            if (have_chained[sig]) *old = chained[sig];
            else memset(old, 0, sizeof(*old));
        }
        if (act) {
            chained[sig] = *act;
            have_chained[sig] = 1;
        }
        return 0;
    }
    return real_sigaction(sig, act, old);
}

/* A non-zero exit is reported too (a helper that ends itself leaves no signal); exit code and
   where it was called from / 0이 아닌 종료도 보고함(스스로 끝나는 도우미는 시그널을 남기지 않음): 종료 코드와 부른 위치 */
static void exit_report(const char *how, int code)
{
    if (code == 0) return;
    int fds[2] = {2, -1};
    fds[1] = open("/tmp/ezs-crash.log", O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0666);
    for (int k = 0; k < 2; k++) {
        int fd = fds[k];
        if (fd < 0) continue;
        char comm[32] = "?";
        int cf = open("/proc/self/comm", O_RDONLY | O_CLOEXEC);
        if (cf >= 0) {
            ssize_t n = read(cf, comm, sizeof(comm) - 1);
            comm[n > 0 ? n : 0] = 0;
            char *nl = strchr(comm, '\n');
            if (nl) *nl = 0;
            close(cf);
        }
        out(fd, "ezs-crash: %s pid %d tid %ld %s(%d)\n", comm, getpid(), (long)syscall(SYS_gettid), how, code);
        void *bt[16];
        int n = backtrace(bt, 16);
        for (int i = 1; i < n; i++) where(fd, "bt", bt[i]);
    }
    if (fds[1] >= 0) close(fds[1]);
}

void exit(int code)
{
    static void (*real)(int) __attribute__((noreturn));
    if (!real) real = dlsym(RTLD_NEXT, "exit");
    exit_report("exit", code);
    real(code);
}

void _exit(int code)
{
    static void (*real)(int) __attribute__((noreturn));
    if (!real) real = dlsym(RTLD_NEXT, "_exit");
    exit_report("_exit", code);
    real(code);
}

__attribute__((constructor)) static void init(void)
{
    if (!real_sigaction) real_sigaction = dlsym(RTLD_NEXT, "sigaction");
    if (!real_sigaction) return;
    /* backtrace() loads libgcc on first use: done here, not inside the handler
       backtrace()는 처음 쓸 때 libgcc를 불러옴: 처리기 안이 아니라 여기서 해 둠 */
    void *warm[2];
    backtrace(warm, 2);
    static char stack[64 * 1024];
    stack_t ss = {.ss_sp = stack, .ss_size = sizeof(stack), .ss_flags = 0};
    sigaltstack(&ss, NULL);
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    for (unsigned i = 0; i < NSIG_WATCH; i++) real_sigaction(SIGS[i], &sa, NULL);
}
