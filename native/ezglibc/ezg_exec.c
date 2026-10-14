/*
 * exec / spawn wrappers.
 * exec·spawn 계열 가로채기임
 *
 * Android has no /lib/ld-linux-aarch64.so.1, so the kernel cannot start a glibc ELF by itself.
 * Every glibc target is rewritten to:  <ldso> [--library-path P] --argv0 <argv0> <real path> args...
 * 안드로이드에는 /lib/ld-linux-aarch64.so.1이 없어 커널이 glibc ELF를 직접 못 띄움.
 * glibc 대상은 모두 위 형태(로더가 실제 프로그램을 여는 방식)로 바꿔 실행함
 *
 * Also handled: "#!" scripts (interpreter mapped into rootfs), bionic targets (LD_PRELOAD removed),
 * static ELFs (run as is), foreign-arch ELFs (optional EZG_X86_LOADER, e.g. FEXInterpreter).
 * 그 밖에 #! 스크립트(해석기를 rootfs로), bionic 대상(LD_PRELOAD 제거), 정적 ELF(그대로),
 * 다른 아키텍처 ELF(EZG_X86_LOADER가 있으면 그것으로) 처리함
 */
#define _GNU_SOURCE
#include "ezg.h"

#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <spawn.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;

/* Machine id of this build, used to tell native ELFs from foreign ones */
/* 이 빌드의 기계 번호. 같은 아키텍처 ELF인지 가르는 데 씀 */
#if defined(__aarch64__)
#define EZG_HOST_MACHINE EM_AARCH64
#elif defined(__x86_64__)
#define EZG_HOST_MACHINE EM_X86_64
#else
#error "unsupported host architecture"
#endif

/* Size of the string pool used for rewritten env entries */
/* 바꾼 환경변수 문자열을 담을 공간 크기 */
#define EZG_POOL 65536

/* Everything a rewritten exec needs; allocated with mmap so it is safe in vfork children */
/* 바꿔 쓴 exec에 필요한 것 전부. vfork 자식에서도 안전하도록 mmap으로 잡음 */
struct exec_plan {
    char  path[PATH_MAX];                  /* file actually passed to execve / 실제 execve 대상 */
    char  self_exe[PATH_MAX];              /* real program path for EZG_SELF_EXE / 실제 프로그램 경로 */
    char  shebang[EZG_MAX_SHEBANG][PATH_MAX + 256]; /* interpreter lines / 해석기 줄 보관 */
    char  unwrap_path[PATH_MAX];           /* PATH result after SLR unwrap / SLR 건너뛴 뒤 PATH 검색 결과 */
    char *argv[EZG_MAX_ARGS];
    char *envp[EZG_MAX_ARGS];
    int   bionic;                          /* 1 = drop LD_* vars / 1이면 LD_* 변수 제거 */
    int   glibc;                           /* 1 = add our env / 1이면 우리 환경변수 추가 */
    char  pool[EZG_POOL];
    size_t pool_used;
};

/* What kind of file a path is */
/* 파일 종류 판별 결과 */
enum target_kind {
    T_UNKNOWN = 0,   /* unreadable or not ELF/script / 못 읽음 또는 ELF·스크립트 아님 */
    T_SCRIPT,        /* "#!" script / #! 스크립트 */
    T_STATIC,        /* ELF without PT_INTERP / PT_INTERP 없는 ELF */
    T_GLIBC,         /* native ELF with a non-Android interpreter / 안드로이드 아닌 해석기를 쓰는 ELF */
    T_BIONIC,        /* native ELF using the Android linker / 안드로이드 링커를 쓰는 ELF */
    T_FOREIGN        /* ELF for another machine / 다른 아키텍처 ELF */
};

static struct exec_plan *plan_alloc(void)
{
    void *p = (void *)syscall(SYS_mmap, NULL, sizeof(struct exec_plan),
                              PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (p == MAP_FAILED) return NULL;
    return (struct exec_plan *)p;
}

static void plan_free(struct exec_plan *pl)
{
    if (pl) syscall(SYS_munmap, pl, sizeof(*pl));
}

/* Copy a string into the plan pool; returns NULL when full */
/* 문자열을 계획 공간에 복사함. 가득 차면 NULL */
static char *pool_dup(struct exec_plan *pl, const char *a, const char *b)
{
    size_t na = strlen(a), nb = b ? strlen(b) : 0;
    char *d;
    if (pl->pool_used + na + nb + 1 > EZG_POOL) return NULL;
    d = pl->pool + pl->pool_used;
    memcpy(d, a, na);
    if (nb) memcpy(d + na, b, nb);
    d[na + nb] = '\0';
    pl->pool_used += na + nb + 1;
    return d;
}

/* Raw read of the first bytes of a file (avoids our own wrappers) */
/* 파일 앞부분을 직접 읽음(우리 가로채기를 거치지 않음) */
static ssize_t read_head(const char *path, void *buf, size_t n, int *fd_out)
{
    int fd = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC);
    ssize_t r;
    if (fd < 0) return -1;
    r = (ssize_t)syscall(SYS_pread64, fd, buf, n, (off_t)0);
    *fd_out = fd;
    return r;
}

/* Classify a file; for scripts copies the "#!" line into line_out */
/* 파일 종류를 판별함. 스크립트면 #! 줄을 line_out에 복사함 */
static enum target_kind classify(const char *path, char *line_out, size_t line_sz)
{
    unsigned char head[512];
    int fd = -1;
    enum target_kind kind = T_UNKNOWN;
    ssize_t n = read_head(path, head, sizeof(head), &fd);

    if (n < 4) goto out;

    if (head[0] == '#' && head[1] == '!') {
        size_t i = 2, j = 0;
        while (i < (size_t)n && head[i] != '\n' && j + 1 < line_sz) line_out[j++] = (char)head[i++];
        line_out[j] = '\0';
        kind = T_SCRIPT;
        goto out;
    }

    if (memcmp(head, ELFMAG, SELFMAG) != 0) goto out;

    /* Only 64-bit little-endian ELFs are considered native */
    /* 64비트 리틀엔디언 ELF만 같은 아키텍처 후보로 봄 */
    if (head[EI_CLASS] != ELFCLASS64 || (size_t)n < sizeof(Elf64_Ehdr)) {
        kind = T_FOREIGN;
        goto out;
    }
    {
        Elf64_Ehdr eh;
        int k;
        memcpy(&eh, head, sizeof(eh));
        if (eh.e_machine != EZG_HOST_MACHINE) {
            kind = T_FOREIGN;
            goto out;
        }
        kind = T_STATIC;
        /* Walk program headers looking for PT_INTERP */
        /* 프로그램 헤더에서 PT_INTERP를 찾음 */
        for (k = 0; k < eh.e_phnum && k < 256; k++) {
            Elf64_Phdr ph;
            off_t off = (off_t)eh.e_phoff + (off_t)k * eh.e_phentsize;
            if (syscall(SYS_pread64, fd, &ph, sizeof(ph), off) != (long)sizeof(ph)) break;
            if (ph.p_type == PT_INTERP) {
                char interp[256] = {0};
                size_t len = ph.p_filesz < sizeof(interp) - 1 ? ph.p_filesz : sizeof(interp) - 1;
                syscall(SYS_pread64, fd, interp, len, (off_t)ph.p_offset);
                /* Android linker lives under /system or /apex and is named linker/linker64 */
                /* 안드로이드 링커는 /system 또는 /apex 아래 linker·linker64 이름임 */
                if (strstr(interp, "/linker") && (strncmp(interp, "/system/", 8) == 0 ||
                                                   strncmp(interp, "/apex/", 6) == 0))
                    kind = T_BIONIC;
                else
                    kind = T_GLIBC;
                break;
            }
        }
    }
out:
    if (fd >= 0) syscall(SYS_close, fd);
    return kind;
}

/* Return value of NAME in an env array, or NULL */
/* 환경 배열에서 NAME 값을 찾음. 없으면 NULL */
static const char *env_get(char *const envp[], const char *name)
{
    size_t n = strlen(name);
    int i;
    if (!envp) return NULL;
    for (i = 0; envp[i]; i++)
        if (strncmp(envp[i], name, n) == 0 && envp[i][n] == '=') return envp[i] + n + 1;
    return NULL;
}

/* True when the entry is NAME=... */
/* 항목이 NAME=... 형태면 참 */
static int env_is(const char *entry, const char *name)
{
    size_t n = strlen(name);
    return strncmp(entry, name, n) == 0 && entry[n] == '=';
}

/* Build the env array for the final exec */
/* 최종 exec에 넘길 환경 배열을 만듦 */
static int build_env(struct exec_plan *pl, char *const envp[])
{
    int i, o = 0;
    const char *preload = env_get(envp, "LD_PRELOAD");

    for (i = 0; envp && envp[i] && o < EZG_MAX_ARGS - 16; i++) {
        const char *e = envp[i];
        /* Values we rebuild below are skipped here */
        /* 아래에서 다시 만드는 값은 여기서 건너뜀 */
        if (env_is(e, "EZG_SELF_EXE") || env_is(e, "LD_PRELOAD")) continue;
        if (pl->bionic && env_is(e, "LD_LIBRARY_PATH")) continue;
        pl->envp[o++] = (char *)e;
    }

    if (pl->glibc) {
        /* Keep our config alive even if the caller cleared the environment */
        /* 호출자가 환경을 비웠어도 우리 설정이 이어지도록 다시 넣음 */
        if (!env_get(envp, "EZG_ROOTFS") && ezg_cfg.rootfs_len)
            pl->envp[o++] = pool_dup(pl, "EZG_ROOTFS=", ezg_cfg.rootfs);
        if (!env_get(envp, "EZG_LDSO") && ezg_cfg.ldso[0])
            pl->envp[o++] = pool_dup(pl, "EZG_LDSO=", ezg_cfg.ldso);
        if (!env_get(envp, "EZG_LIBPATH") && ezg_cfg.libpath[0])
            pl->envp[o++] = pool_dup(pl, "EZG_LIBPATH=", ezg_cfg.libpath);
        if (!env_get(envp, "EZG_SKIP_SLR") && ezg_cfg.skip_slr)
            pl->envp[o++] = pool_dup(pl, "EZG_SKIP_SLR=", "1");
        if (pl->self_exe[0])
            pl->envp[o++] = pool_dup(pl, "EZG_SELF_EXE=", pl->self_exe);

        /* LD_PRELOAD: ours first, then whatever the program asked for (e.g. an overlay library) */
        /* LD_PRELOAD: 우리 것을 맨 앞에, 그 뒤에 프로그램이 넣은 것(오버레이 라이브러리 등)을 둠 */
        if (ezg_cfg.self_so[0]) {
            if (preload && *preload && !strstr(preload, ezg_cfg.self_so)) {
                char *v = pool_dup(pl, "LD_PRELOAD=", ezg_cfg.self_so);
                if (v) {
                    pl->pool_used--; /* drop NUL to append / NUL을 지우고 이어 붙임 */
                    if (!pool_dup(pl, ":", preload)) v = NULL;
                }
                pl->envp[o++] = v;
            } else if (preload && *preload) {
                pl->envp[o++] = pool_dup(pl, "LD_PRELOAD=", preload);
            } else {
                pl->envp[o++] = pool_dup(pl, "LD_PRELOAD=", ezg_cfg.self_so);
            }
        }
    } else if (!pl->bionic && preload) {
        /* Static / unknown targets: keep LD_PRELOAD untouched */
        /* 정적·알 수 없는 대상: LD_PRELOAD를 그대로 둠 */
        pl->envp[o++] = pool_dup(pl, "LD_PRELOAD=", preload);
    }

    /* A NULL from a full pool would silently cut the env; treat as failure */
    /* 공간이 차서 NULL이 들어가면 환경이 잘리므로 실패로 처리함 */
    for (i = 0; i < o; i++)
        if (!pl->envp[i]) return -1;
    pl->envp[o] = NULL;
    return 0;
}

/* Split a "#!" line into interpreter and optional single argument (Linux semantics) */
/* #! 줄을 해석기와 인자 하나로 나눔(리눅스 커널 규칙과 같음) */
static void split_shebang(char *line, char **interp, char **arg)
{
    char *p = line, *e;
    while (*p == ' ' || *p == '\t') p++;
    *interp = p;
    while (*p && *p != ' ' && *p != '\t') p++;
    *arg = NULL;
    if (*p) {
        *p++ = '\0';
        while (*p == ' ' || *p == '\t') p++;
        e = p + strlen(p);
        while (e > p && (e[-1] == ' ' || e[-1] == '\t' || e[-1] == '\r')) *--e = '\0';
        if (*p) *arg = p;
    }
}

static int search_path(const char *file, char *const envp[], char *out, size_t outsz);

/* True when the path is Steam Linux Runtime's entry point script */
/* 경로가 Steam Linux Runtime 진입점 스크립트이면 참 */
static int is_slr_entry(const char *p)
{
    const char *b = strrchr(p, '/');
    b = b ? b + 1 : p;
    return strcmp(b, "_v2-entry-point") == 0;
}

/*
 * Turn (path, argv, envp) into the plan to run. Returns 0, or -1 with errno set.
 * (path, argv, envp)를 실제 실행 계획으로 바꿈. 성공 0, 실패 -1(errno 설정)
 */
static int plan_build(struct exec_plan *pl, const char *path, char *const argv[], char *const envp[])
{
    char mapbuf[PATH_MAX];
    char *cur_argv[EZG_MAX_ARGS];
    const char *cur_path = path;
    const char *x86_loader = getenv("EZG_X86_LOADER");
    int argc = 0, depth = 0, i;

    ezg_init();
    for (; argv && argv[argc] && argc < EZG_MAX_ARGS - 8; argc++) cur_argv[argc] = argv[argc];
    cur_argv[argc] = NULL;
    if (argv && argv[argc]) { errno = E2BIG; return -1; }

    for (;;) {
        const char *real;
        enum target_kind kind;

        /* Steam runs "<SLR>/_v2-entry-point --verb=... -- <proton> <verb> <game>": keep only
           what follows "--" so Valve's Proton runs without the pressure-vessel container */
        /* Steam은 "<SLR>/_v2-entry-point --verb=... -- <proton> <동작> <게임>"으로 부름:
           "--" 뒤만 남겨 Valve Proton을 pressure-vessel 컨테이너 없이 실행함 */
        if (ezg_cfg.skip_slr && is_slr_entry(cur_path)) {
            int k, dd = -1;
            for (k = 1; k < argc; k++)
                if (strcmp(cur_argv[k], "--") == 0) { dd = k; break; }
            if (dd > 0 && dd + 1 < argc) {
                ezg_log("skip SLR %s -> %s\n", cur_path, cur_argv[dd + 1]);
                /* Shift the tail (including the NULL terminator) to the front */
                /* 꼬리(NULL 끝 표시 포함)를 앞으로 당김 */
                for (k = dd + 1; k <= argc; k++) cur_argv[k - dd - 1] = cur_argv[k];
                argc -= dd + 1;
                cur_path = cur_argv[0];
                if (!strchr(cur_path, '/')) {
                    if (search_path(cur_path, envp, pl->unwrap_path, sizeof(pl->unwrap_path)) != 0)
                        return -1;
                    cur_path = pl->unwrap_path;
                }
                if (++depth >= EZG_MAX_SHEBANG) { errno = ELOOP; return -1; }
                continue;
            }
            ezg_log("SLR entry without \"--\", running as is: %s\n", cur_path);
        }

        real = ezg_map(cur_path, mapbuf, sizeof(mapbuf));
        if (strlen(real) >= sizeof(pl->path)) { errno = ENAMETOOLONG; return -1; }
        strcpy(pl->path, real);
        if (depth >= EZG_MAX_SHEBANG) { errno = ELOOP; return -1; }

        kind = classify(pl->path, pl->shebang[depth], sizeof(pl->shebang[depth]));
        ezg_log("exec %s -> %s kind=%d\n", cur_path, pl->path, (int)kind);

        if (kind == T_SCRIPT) {
            /* New argv: interp [arg] <script path as given> argv[1..] */
            /* 새 argv: 해석기 [인자] <받은 그대로의 스크립트 경로> 원래 argv[1..] */
            char *interp, *arg;
            int k = 0, extra;
            split_shebang(pl->shebang[depth], &interp, &arg);
            if (!*interp) { errno = ENOEXEC; return -1; }
            extra = arg ? 3 : 2;
            if (argc + extra >= EZG_MAX_ARGS - 8) { errno = E2BIG; return -1; }
            /* Shift original args (skip old argv[0]) right */
            /* 원래 인자(옛 argv[0] 제외)를 오른쪽으로 밂 */
            for (i = argc; i >= 1; i--) cur_argv[i - 1 + extra] = cur_argv[i];
            cur_argv[k++] = interp;
            if (arg) cur_argv[k++] = arg;
            cur_argv[k++] = (char *)cur_path;
            argc = argc - 1 + extra;
            if (argc < extra) argc = extra; /* argv was empty / argv가 비었던 경우 */
            cur_argv[argc] = NULL;
            cur_path = interp;
            depth++;
            continue;
        }

        if (kind == T_FOREIGN && x86_loader && *x86_loader) {
            /* Hand a foreign ELF to the configured loader: <loader> <path> argv[1..] */
            /* 다른 아키텍처 ELF는 지정한 로더로 넘김: <로더> <경로> 원래 argv[1..] */
            if (argc + 2 >= EZG_MAX_ARGS - 8) { errno = E2BIG; return -1; }
            for (i = argc; i >= 1; i--) cur_argv[i + 1] = cur_argv[i];
            cur_argv[0] = (char *)x86_loader;
            cur_argv[1] = pool_dup(pl, pl->path, NULL);
            if (!cur_argv[1]) { errno = E2BIG; return -1; }
            argc += 1;
            if (argc < 2) argc = 2;
            cur_argv[argc] = NULL;
            cur_path = x86_loader;
            depth++;
            continue;
        }

        if (kind == T_GLIBC) {
            /* <ldso> [--library-path P] --argv0 <argv0> <path> argv[1..] */
            /* 로더 경유 형태로 바꿈 */
            int k = 0;
            if (!ezg_cfg.ldso[0]) { errno = ENOEXEC; return -1; }
            if (argc + 6 >= EZG_MAX_ARGS) { errno = E2BIG; return -1; }
            pl->argv[k++] = ezg_cfg.ldso;
            if (ezg_cfg.libpath[0]) {
                pl->argv[k++] = "--library-path";
                pl->argv[k++] = ezg_cfg.libpath;
            }
            pl->argv[k++] = "--argv0";
            pl->argv[k++] = (argc > 0 && cur_argv[0]) ? cur_argv[0] : (char *)cur_path;
            pl->argv[k] = pool_dup(pl, pl->path, NULL);
            if (!pl->argv[k++]) { errno = E2BIG; return -1; }
            for (i = 1; i < argc; i++) pl->argv[k++] = cur_argv[i];
            pl->argv[k] = NULL;
            /* /proc/self/exe must be absolute: prefix cwd for relative paths */
            /* /proc/self/exe 값은 절대 경로여야 하므로 상대 경로면 현재 폴더를 붙임 */
            if (pl->path[0] != '/') {
                char cwd[PATH_MAX];
                if (syscall(SYS_getcwd, cwd, sizeof(cwd)) > 0 &&
                    strlen(cwd) + strlen(pl->path) + 2 <= sizeof(pl->self_exe))
                    snprintf(pl->self_exe, sizeof(pl->self_exe), "%s/%s", cwd, pl->path);
            } else {
                strcpy(pl->self_exe, pl->path);
            }
            strcpy(pl->path, ezg_cfg.ldso);
            pl->glibc = 1;
        } else {
            /* Static, bionic or unknown: run the (mapped) file directly */
            /* 정적·bionic·알 수 없음: (변환한) 파일을 그대로 실행함 */
            for (i = 0; i < argc; i++) pl->argv[i] = cur_argv[i];
            pl->argv[argc] = NULL;
            pl->bionic = (kind == T_BIONIC);
        }

        /* Interpreter strings live in pl->shebang, argv strings in the caller: both outlive exec */
        /* 해석기 문자열은 pl->shebang에, argv 문자열은 호출자 쪽에 있어 exec까지 유지됨 */
        if (build_env(pl, envp) != 0) { errno = E2BIG; return -1; }
        return 0;
    }
}

/* ---------------------------------------------------------------------------------- */
/* execve family                                                                       */
/* execve 계열                                                                          */
/* ---------------------------------------------------------------------------------- */

int execve(const char *path, char *const argv[], char *const envp[])
{
    EZG_REAL(int (*)(const char *, char *const[], char *const[]), execve);
    struct exec_plan *pl;
    int saved;
    ezg_init();
    if (!ezg_cfg.rootfs_len) return real_execve(path, argv, envp);
    pl = plan_alloc();
    if (!pl) return real_execve(path, argv, envp);
    if (plan_build(pl, path, argv, envp) != 0) {
        saved = errno;
        plan_free(pl);
        errno = saved;
        return -1;
    }
    real_execve(pl->path, pl->argv, pl->envp);
    saved = errno;
    ezg_log("execve failed %s errno=%d\n", pl->path, saved);
    plan_free(pl);
    errno = saved;
    return -1;
}

int execv(const char *path, char *const argv[])
{
    return execve(path, argv, environ);
}

/* PATH search with rootfs mapping; writes the chosen path into out */
/* rootfs 변환을 포함한 PATH 검색. 찾은 경로를 out에 씀 */
static int search_path(const char *file, char *const envp[], char *out, size_t outsz)
{
    const char *path_env = env_get(envp, "PATH");
    const char *p;
    char cand[PATH_MAX], mapbuf[PATH_MAX];
    int saw_eacces = 0;

    if (!file || !*file) { errno = ENOENT; return -1; }
    if (strchr(file, '/')) {
        if (strlen(file) >= outsz) { errno = ENAMETOOLONG; return -1; }
        strcpy(out, file);
        return 0;
    }
    if (!path_env) path_env = "/usr/local/bin:/usr/bin:/bin";

    for (p = path_env;; ) {
        const char *e = strchr(p, ':');
        size_t n = e ? (size_t)(e - p) : strlen(p);
        int len = (n == 0) ? snprintf(cand, sizeof(cand), "%s", file)
                           : snprintf(cand, sizeof(cand), "%.*s/%s", (int)n, p, file);
        if (len > 0 && (size_t)len < sizeof(cand)) {
            const char *real = ezg_map(cand, mapbuf, sizeof(mapbuf));
            if (syscall(SYS_faccessat, AT_FDCWD, real, X_OK) == 0) {
                /* Return the unmapped name: plan_build maps it again and keeps argv semantics */
                /* 변환 전 이름을 돌려줌: plan_build가 다시 변환하며 argv 의미를 유지함 */
                if ((size_t)len >= outsz) { errno = ENAMETOOLONG; return -1; }
                strcpy(out, cand);
                return 0;
            }
            if (errno == EACCES) saw_eacces = 1;
        }
        if (!e) break;
        p = e + 1;
    }
    errno = saw_eacces ? EACCES : ENOENT;
    return -1;
}

int execvpe(const char *file, char *const argv[], char *const envp[])
{
    char found[PATH_MAX];
    if (search_path(file, envp, found, sizeof(found)) != 0) return -1;
    return execve(found, argv, envp);
}

int execvp(const char *file, char *const argv[])
{
    return execvpe(file, argv, environ);
}

/* Collect variadic exec arguments into an array (stack, bounded) */
/* 가변 인자 exec의 인자를 배열로 모음(스택, 개수 제한) */
#define COLLECT_ARGS(first, ap, arr, want_env, envout)                         \
    do {                                                                       \
        int _n = 0;                                                            \
        arr[_n++] = (char *)(first);                                           \
        if (first) {                                                           \
            char *_a;                                                          \
            while ((_a = va_arg(ap, char *)) != NULL && _n < 1023) arr[_n++] = _a; \
        }                                                                      \
        arr[_n] = NULL;                                                        \
        if (want_env) *(envout) = va_arg(ap, char **);                         \
    } while (0)

int execl(const char *path, const char *arg, ...)
{
    char *args[1024];
    va_list ap;
    va_start(ap, arg);
    COLLECT_ARGS(arg, ap, args, 0, (char ***)0);
    va_end(ap);
    return execve(path, args, environ);
}

int execlp(const char *file, const char *arg, ...)
{
    char *args[1024];
    va_list ap;
    va_start(ap, arg);
    COLLECT_ARGS(arg, ap, args, 0, (char ***)0);
    va_end(ap);
    return execvpe(file, args, environ);
}

int execle(const char *path, const char *arg, ...)
{
    char *args[1024];
    char **envp = NULL;
    va_list ap;
    va_start(ap, arg);
    COLLECT_ARGS(arg, ap, args, 1, &envp);
    va_end(ap);
    return execve(path, args, envp);
}

/* ---------------------------------------------------------------------------------- */
/* posix_spawn family (glibc runs __execve internally, so these must be wrapped too)    */
/* posix_spawn 계열(glibc 내부가 __execve를 직접 불러 따로 가로채야 함)                  */
/* ---------------------------------------------------------------------------------- */

typedef int (*spawn_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                        const posix_spawnattr_t *, char *const[], char *const[]);

static int spawn_common(spawn_fn real, pid_t *pid, const char *path,
                        const posix_spawn_file_actions_t *fa, const posix_spawnattr_t *at,
                        char *const argv[], char *const envp[])
{
    struct exec_plan *pl = plan_alloc();
    int rc;
    if (!pl) return ENOMEM;
    if (plan_build(pl, path, argv, envp ? envp : environ) != 0) {
        rc = errno;
        plan_free(pl);
        return rc;
    }
    rc = real(pid, pl->path, fa, at, pl->argv, pl->envp);
    plan_free(pl);
    return rc;
}

int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *fa,
                const posix_spawnattr_t *at, char *const argv[], char *const envp[])
{
    EZG_REAL(spawn_fn, posix_spawn);
    ezg_init();
    if (!ezg_cfg.rootfs_len) return real_posix_spawn(pid, path, fa, at, argv, envp);
    return spawn_common(real_posix_spawn, pid, path, fa, at, argv, envp);
}

int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *fa,
                 const posix_spawnattr_t *at, char *const argv[], char *const envp[])
{
    EZG_REAL(spawn_fn, posix_spawn);
    EZG_REAL(spawn_fn, posix_spawnp);
    char found[PATH_MAX];
    ezg_init();
    if (!ezg_cfg.rootfs_len) return real_posix_spawnp(pid, file, fa, at, argv, envp);
    if (search_path(file, envp ? envp : environ, found, sizeof(found)) != 0) return errno;
    /* Resolved path goes through plain posix_spawn (no second PATH search) */
    /* 찾은 경로는 일반 posix_spawn으로 넘김(PATH 재검색 없음) */
    return spawn_common(real_posix_spawn, pid, found, fa, at, argv, envp);
}

/* Paths inside spawn file actions are opened by the child with internal calls: map them now */
/* spawn 파일 동작의 경로는 자식이 내부 호출로 열므로 여기서 미리 변환함 */
int posix_spawn_file_actions_addopen(posix_spawn_file_actions_t *fa, int fd, const char *path,
                                     int oflag, mode_t mode)
{
    EZG_REAL(int (*)(posix_spawn_file_actions_t *, int, const char *, int, mode_t),
             posix_spawn_file_actions_addopen);
    char buf[PATH_MAX];
    /* glibc copies the path string, so a stack buffer is fine */
    /* glibc가 경로 문자열을 복사해 두므로 스택 버퍼로 충분함 */
    return real_posix_spawn_file_actions_addopen(fa, fd, ezg_map(path, buf, sizeof(buf)), oflag, mode);
}

/* ---------------------------------------------------------------------------------- */
/* system / popen (glibc spawns /bin/sh internally; Android has no /bin/sh)              */
/* system·popen(glibc가 내부에서 /bin/sh를 띄우는데 안드로이드에는 /bin/sh가 없음)           */
/* ---------------------------------------------------------------------------------- */

int system(const char *cmd)
{
    EZG_REAL(int (*)(const char *), system);
    struct sigaction sa_ign, sa_int, sa_quit;
    sigset_t block, old;
    posix_spawnattr_t attr;
    pid_t pid;
    int status = -1, rc;
    char *argv[] = { "sh", "-c", (char *)cmd, NULL };

    ezg_init();
    if (!ezg_cfg.rootfs_len) return real_system(cmd);
    if (!cmd) {
        /* Shell availability check */
        /* 셸이 있는지 확인 */
        char buf[PATH_MAX];
        return syscall(SYS_faccessat, AT_FDCWD, ezg_map("/bin/sh", buf, sizeof(buf)), X_OK) == 0;
    }

    /* POSIX: ignore SIGINT/SIGQUIT and block SIGCHLD while waiting */
    /* POSIX 규칙: 기다리는 동안 SIGINT·SIGQUIT 무시, SIGCHLD 막음 */
    memset(&sa_ign, 0, sizeof(sa_ign));
    sa_ign.sa_handler = SIG_IGN;
    sigemptyset(&sa_ign.sa_mask);
    sigaction(SIGINT, &sa_ign, &sa_int);
    sigaction(SIGQUIT, &sa_ign, &sa_quit);
    sigemptyset(&block);
    sigaddset(&block, SIGCHLD);
    sigprocmask(SIG_BLOCK, &block, &old);

    /* Child restores default signal handling and the old mask */
    /* 자식은 기본 신호 처리와 이전 마스크로 되돌림 */
    posix_spawnattr_init(&attr);
    {
        sigset_t def;
        sigemptyset(&def);
        if (sa_int.sa_handler != SIG_IGN) sigaddset(&def, SIGINT);
        if (sa_quit.sa_handler != SIG_IGN) sigaddset(&def, SIGQUIT);
        posix_spawnattr_setsigdefault(&attr, &def);
        posix_spawnattr_setsigmask(&attr, &old);
        posix_spawnattr_setflags(&attr, POSIX_SPAWN_SETSIGDEF | POSIX_SPAWN_SETSIGMASK);
    }
    rc = posix_spawn(&pid, "/bin/sh", NULL, &attr, argv, environ);
    posix_spawnattr_destroy(&attr);

    if (rc == 0) {
        while (waitpid(pid, &status, 0) < 0) {
            if (errno != EINTR) { status = -1; break; }
        }
    } else {
        /* Same as a shell that could not run: exit status 127 */
        /* 셸을 못 띄운 경우와 같게 종료 코드 127 */
        status = 127 << 8;
    }

    sigaction(SIGINT, &sa_int, NULL);
    sigaction(SIGQUIT, &sa_quit, NULL);
    sigprocmask(SIG_SETMASK, &old, NULL);
    return status;
}

/* popen bookkeeping: FILE* -> child pid */
/* popen 기록: FILE* -> 자식 pid */
struct popen_ent { FILE *fp; pid_t pid; };
#define EZG_MAX_POPEN 64
static struct popen_ent g_popen[EZG_MAX_POPEN];
static pthread_mutex_t g_popen_lock = PTHREAD_MUTEX_INITIALIZER;

/* Placeholder marking a reserved slot */
/* 자리 예약 표시 */
#define POPEN_RESERVED ((FILE *)1)

/* Free a reserved slot after a failed popen */
/* popen 실패 시 예약한 자리를 풂 */
static void popen_release(int slot)
{
    pthread_mutex_lock(&g_popen_lock);
    g_popen[slot].fp = NULL;
    pthread_mutex_unlock(&g_popen_lock);
}

FILE *popen(const char *cmd, const char *mode)
{
    EZG_REAL(FILE *(*)(const char *, const char *), popen);
    posix_spawn_file_actions_t fa;
    int fds[2], reading, cloexec, slot = -1, i, rc;
    pid_t pid;
    FILE *fp;
    char *argv[] = { "sh", "-c", (char *)cmd, NULL };

    ezg_init();
    if (!ezg_cfg.rootfs_len) return real_popen(cmd, mode);
    if (!mode || (mode[0] != 'r' && mode[0] != 'w')) { errno = EINVAL; return NULL; }
    reading = (mode[0] == 'r');
    cloexec = (strchr(mode, 'e') != NULL);

    /* Reserve a slot with a placeholder so concurrent popen calls cannot take it */
    /* 동시에 부른 popen이 같은 자리를 못 쓰도록 임시 표시로 자리를 잡아 둠 */
    pthread_mutex_lock(&g_popen_lock);
    for (i = 0; i < EZG_MAX_POPEN; i++) if (!g_popen[i].fp) { slot = i; break; }
    if (slot >= 0) g_popen[slot].fp = POPEN_RESERVED;
    pthread_mutex_unlock(&g_popen_lock);
    if (slot < 0) { errno = EMFILE; return NULL; }

    if (pipe2(fds, O_CLOEXEC) != 0) { popen_release(slot); return NULL; }

    /* Child end becomes stdin or stdout; dup2 clears CLOEXEC on the target */
    /* 자식 쪽 끝을 표준입력 또는 표준출력으로 붙임. dup2는 대상의 CLOEXEC를 지움 */
    posix_spawn_file_actions_init(&fa);
    posix_spawn_file_actions_adddup2(&fa, reading ? fds[1] : fds[0], reading ? 1 : 0);
    rc = posix_spawn(&pid, "/bin/sh", &fa, NULL, argv, environ);
    posix_spawn_file_actions_destroy(&fa);

    close(reading ? fds[1] : fds[0]);
    if (rc != 0) {
        close(reading ? fds[0] : fds[1]);
        popen_release(slot);
        errno = rc;
        return NULL;
    }

    /* Parent end: drop CLOEXEC unless "e" was requested */
    /* 부모 쪽 끝: "e"를 안 줬으면 CLOEXEC를 뗌 */
    if (!cloexec) fcntl(reading ? fds[0] : fds[1], F_SETFD, 0);
    fp = fdopen(reading ? fds[0] : fds[1], reading ? "r" : "w");
    if (!fp) {
        close(reading ? fds[0] : fds[1]);
        waitpid(pid, NULL, 0);
        popen_release(slot);
        return NULL;
    }

    pthread_mutex_lock(&g_popen_lock);
    g_popen[slot].fp = fp;
    g_popen[slot].pid = pid;
    pthread_mutex_unlock(&g_popen_lock);
    return fp;
}

int pclose(FILE *fp)
{
    EZG_REAL(int (*)(FILE *), pclose);
    pid_t pid = -1;
    int i, status;

    pthread_mutex_lock(&g_popen_lock);
    for (i = 0; i < EZG_MAX_POPEN; i++) {
        if (g_popen[i].fp == fp) {
            pid = g_popen[i].pid;
            g_popen[i].fp = NULL;
            break;
        }
    }
    pthread_mutex_unlock(&g_popen_lock);

    /* Not ours: hand to glibc */
    /* 우리가 연 것이 아니면 glibc에 넘김 */
    if (pid < 0) return real_pclose(fp);

    fclose(fp);
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    return status;
}
