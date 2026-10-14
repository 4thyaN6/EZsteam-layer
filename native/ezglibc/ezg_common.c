/*
 * Config loading, path mapping, logging and symbol lookup shared by all wrappers.
 * 모든 가로채기 함수가 같이 쓰는 설정 읽기·경로 변환·기록·함수 주소 찾기임
 */
#define _GNU_SOURCE
#include "ezg.h"

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

struct ezg_config ezg_cfg = { .log_fd = -1 };

/* Default prefixes mapped into rootfs (Android has none of these, or the wrong ones) */
/* rootfs로 옮길 기본 경로 앞부분(안드로이드에 없거나 내용이 다른 경로들) */
static const char *k_default_prefix =
    "/usr:/lib:/lib64:/bin:/sbin:/etc:/opt:/var:/home:/tmp:/run:/dev/shm";

static pthread_once_t g_once = PTHREAD_ONCE_INIT;

/* Copy with truncation guard; returns 0 on success, -1 if it did not fit */
/* 길이 검사하며 복사함. 들어가면 0, 넘치면 -1 */
static int copy_str(char *dst, size_t dstsz, const char *src)
{
    size_t n = strlen(src);
    if (n >= dstsz) return -1;
    memcpy(dst, src, n + 1);
    return 0;
}

/* Find the file path of this shared object (used to keep LD_PRELOAD for children) */
/* 이 공유 라이브러리 자신의 경로를 찾음(자식 프로세스에 LD_PRELOAD 유지용) */
static void find_self_so(void)
{
    Dl_info info;
    if (dladdr((void *)&find_self_so, &info) && info.dli_fname && info.dli_fname[0] == '/')
        copy_str(ezg_cfg.self_so, sizeof(ezg_cfg.self_so), info.dli_fname);
}

/* Fallback for the program path: first link_map entry name (set when ld.so was invoked directly) */
/* 프로그램 경로 대체값: 첫 link_map 이름(ld.so를 직접 실행하면 채워짐) */
static int phdr_first_cb(struct dl_phdr_info *info, size_t size, void *data)
{
    (void)size;
    if (info->dlpi_name && info->dlpi_name[0] == '/')
        copy_str((char *)data, PATH_MAX, info->dlpi_name);
    return 1; /* stop after the first (main program) / 첫 항목(주 프로그램)만 보고 멈춤 */
}

/* Parse the ':'-separated prefix list */
/* ':'로 구분된 앞부분 목록을 읽음 */
static void parse_prefixes(const char *list)
{
    const char *p = list;
    ezg_cfg.nprefix = 0;
    while (*p && ezg_cfg.nprefix < EZG_MAX_PREFIX) {
        const char *e = strchr(p, ':');
        size_t n = e ? (size_t)(e - p) : strlen(p);
        /* Strip trailing '/' and accept only absolute, non-root entries */
        /* 끝 '/'를 떼고 '/' 하나뿐이 아닌 절대 경로만 받음 */
        while (n > 1 && p[n - 1] == '/') n--;
        if (n > 1 && p[0] == '/' && n < sizeof(ezg_cfg.prefix[0])) {
            memcpy(ezg_cfg.prefix[ezg_cfg.nprefix], p, n);
            ezg_cfg.prefix[ezg_cfg.nprefix][n] = '\0';
            ezg_cfg.prefix_len[ezg_cfg.nprefix] = n;
            ezg_cfg.nprefix++;
        }
        if (!e) break;
        p = e + 1;
    }
}

/* One-time initialisation body */
/* 한 번만 도는 초기화 본체 */
static void init_once(void)
{
    const char *v;

    /* Logging target */
    /* 기록 대상 */
    v = getenv("EZG_LOG");
    if (v && *v) {
        if (strcmp(v, "1") == 0)
            ezg_cfg.log_fd = 2;
        else
            ezg_cfg.log_fd = (int)syscall(SYS_openat, AT_FDCWD, v,
                                          O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
    }

    /* Rootfs (without it the library does nothing but pass calls through) */
    /* rootfs(없으면 아무것도 바꾸지 않고 그대로 넘김) */
    v = getenv("EZG_ROOTFS");
    if (v && v[0] == '/' && copy_str(ezg_cfg.rootfs, sizeof(ezg_cfg.rootfs), v) == 0) {
        ezg_cfg.rootfs_len = strlen(ezg_cfg.rootfs);
        while (ezg_cfg.rootfs_len > 1 && ezg_cfg.rootfs[ezg_cfg.rootfs_len - 1] == '/')
            ezg_cfg.rootfs[--ezg_cfg.rootfs_len] = '\0';
    }

    /* Loader path */
    /* 로더 경로 */
    v = getenv("EZG_LDSO");
    if (v && *v)
        copy_str(ezg_cfg.ldso, sizeof(ezg_cfg.ldso), v);
    else if (ezg_cfg.rootfs_len)
        snprintf(ezg_cfg.ldso, sizeof(ezg_cfg.ldso), "%s/lib/ld-linux-aarch64.so.1", ezg_cfg.rootfs);

    v = getenv("EZG_LIBPATH");
    if (v && *v) copy_str(ezg_cfg.libpath, sizeof(ezg_cfg.libpath), v);

    v = getenv("EZG_REDIRECT");
    parse_prefixes((v && *v) ? v : k_default_prefix);

    /* Skip the Steam Linux Runtime container (see ezg.h) */
    /* Steam Linux Runtime 컨테이너 건너뛰기(ezg.h 참고) */
    v = getenv("EZG_SKIP_SLR");
    ezg_cfg.skip_slr = (v && strcmp(v, "1") == 0);

    find_self_so();

    /* Real program path: take it from the env our exec wrapper set, then drop it so
       a child that is not started through us cannot inherit a stale value */
    /* 실제 프로그램 경로: 우리 exec가 넣은 환경변수에서 가져오고, 우리를 거치지 않은
       자식이 낡은 값을 물려받지 않도록 바로 지움 */
    v = getenv("EZG_SELF_EXE");
    if (v && v[0] == '/') {
        copy_str(ezg_cfg.self_exe, sizeof(ezg_cfg.self_exe), v);
        unsetenv("EZG_SELF_EXE");
    } else {
        dl_iterate_phdr(phdr_first_cb, ezg_cfg.self_exe);
    }

    ezg_cfg.ready = 1;
    ezg_log("init rootfs=%s ldso=%s self_exe=%s prefixes=%d skip_slr=%d\n",
            ezg_cfg.rootfs, ezg_cfg.ldso, ezg_cfg.self_exe, ezg_cfg.nprefix, ezg_cfg.skip_slr);
}

void ezg_init(void)
{
    pthread_once(&g_once, init_once);
}

/* Run init as early as possible (before main) */
/* 가능한 한 일찍(main 전에) 초기화함 */
__attribute__((constructor)) static void ezg_ctor(void)
{
    ezg_init();
}

const char *ezg_map(const char *in, char *buf, size_t bufsz)
{
    int i;
    if (!in || in[0] != '/') return in;
    ezg_init();
    if (!ezg_cfg.rootfs_len) return in;

    /* Already inside rootfs: leave as is */
    /* 이미 rootfs 안 경로면 그대로 둠 */
    if (strncmp(in, ezg_cfg.rootfs, ezg_cfg.rootfs_len) == 0 &&
        (in[ezg_cfg.rootfs_len] == '/' || in[ezg_cfg.rootfs_len] == '\0'))
        return in;

    for (i = 0; i < ezg_cfg.nprefix; i++) {
        size_t n = ezg_cfg.prefix_len[i];
        /* Match whole path components only: "/usr" matches "/usr" and "/usr/x", not "/usrx" */
        /* 경로 단위로만 맞춤: "/usr"는 "/usr", "/usr/x"에 맞고 "/usrx"에는 안 맞음 */
        if (strncmp(in, ezg_cfg.prefix[i], n) == 0 && (in[n] == '/' || in[n] == '\0')) {
            size_t total = ezg_cfg.rootfs_len + strlen(in);
            if (total + 1 > bufsz) {
                ezg_log("map overflow %s\n", in);
                return in;
            }
            memcpy(buf, ezg_cfg.rootfs, ezg_cfg.rootfs_len);
            strcpy(buf + ezg_cfg.rootfs_len, in);
            return buf;
        }
    }
    return in;
}

int ezg_is_self_exe(const char *path)
{
    char own[64];
    if (!path) return 0;
    if (strcmp(path, "/proc/self/exe") == 0 || strcmp(path, "/proc/thread-self/exe") == 0)
        return 1;
    snprintf(own, sizeof(own), "/proc/%d/exe", (int)getpid());
    return strcmp(path, own) == 0;
}

void ezg_log(const char *fmt, ...)
{
    char line[1024];
    int n, pre;
    va_list ap;
    if (ezg_cfg.log_fd < 0) return;
    pre = snprintf(line, sizeof(line), "[ezg %d] ", (int)getpid());
    va_start(ap, fmt);
    n = vsnprintf(line + pre, sizeof(line) - (size_t)pre, fmt, ap);
    va_end(ap);
    if (n < 0) return;
    n += pre;
    if (n > (int)sizeof(line) - 1) n = (int)sizeof(line) - 1;
    /* Raw syscall so a wrapped write() can never recurse */
    /* 가로챈 write()로 되돌아오지 않게 직접 시스템 호출함 */
    syscall(SYS_write, ezg_cfg.log_fd, line, (size_t)n);
}

void *ezg_real(const char *name)
{
    void *p = dlsym(RTLD_NEXT, name);
    if (!p) {
        static const char msg[] = "[ezg] missing libc symbol\n";
        syscall(SYS_write, 2, msg, sizeof(msg) - 1);
        syscall(SYS_write, 2, name, strlen(name));
        syscall(SYS_write, 2, "\n", 1);
        abort();
    }
    return p;
}
