/*
 * EZ glibc exec layer: LD_PRELOAD library for glibc programs living in an app-private rootfs.
 * 앱 전용 rootfs 안의 glibc 프로그램에 LD_PRELOAD로 올리는 실행·경로 보정 라이브러리임
 *
 * Environment (set by the Android launcher before the first exec):
 * 환경변수(안드로이드 쪽 실행기가 첫 실행 전에 설정함):
 *   EZG_ROOTFS    absolute rootfs path (required)                 / rootfs 절대 경로(필수)
 *   EZG_LDSO      glibc dynamic loader path (default <rootfs>/lib/ld-linux-aarch64.so.1)
 *                 glibc 동적 로더 경로(기본값 위와 같음)
 *   EZG_LIBPATH   --library-path for the loader (optional)        / 로더에 넘길 라이브러리 경로(선택)
 *   EZG_REDIRECT  ':'-separated absolute prefixes mapped into rootfs
 *                 (default /usr:/lib:/lib64:/bin:/sbin:/etc:/opt:/var:/home:/tmp:/run:/srv:/mnt)
 *                 rootfs로 옮길 절대 경로 앞부분 목록(':' 구분)
 *   EZG_SELF_EXE  real program path for /proc/self/exe (set by this library on exec)
 *                 /proc/self/exe 보정용 실제 프로그램 경로(이 라이브러리가 exec 때 설정함)
 *   EZG_LOG       1 = log decisions to stderr, or a file path      / 1이면 stderr, 경로면 파일에 기록
 *   EZG_SKIP_SLR  1 = run the command behind Steam Linux Runtime's "_v2-entry-point ... -- cmd"
 *                 directly (no pressure-vessel container)
 *                 1이면 Steam Linux Runtime의 "_v2-entry-point ... -- 명령"에서 명령만 바로 실행함
 *                 (pressure-vessel 컨테이너 안 씀)
 */
#ifndef EZG_H
#define EZG_H

#include <stddef.h>
#include <limits.h>

/* Maximum number of redirect prefixes */
/* 재지정 앞부분 최대 개수 */
#define EZG_MAX_PREFIX 32

/* Max argv entries we build for a rewritten exec */
/* 바꿔 쓴 exec에서 만들 argv 최대 개수 */
#define EZG_MAX_ARGS 4096

/* Shebang recursion depth limit */
/* #! 스크립트 중첩 한도 */
#define EZG_MAX_SHEBANG 4

/* Global config, filled once by ezg_init() */
/* 전역 설정, ezg_init()에서 한 번 채움 */
struct ezg_config {
    int    ready;                          /* 1 after init / 초기화 끝나면 1 */
    char   rootfs[PATH_MAX];               /* rootfs path without trailing '/' / 끝 '/' 없는 rootfs 경로 */
    size_t rootfs_len;
    char   ldso[PATH_MAX];                 /* glibc loader / glibc 로더 */
    char   libpath[PATH_MAX * 2];          /* optional --library-path / 선택 라이브러리 경로 */
    char   self_so[PATH_MAX];              /* path of this .so (for LD_PRELOAD) / 이 .so 경로 */
    char   self_exe[PATH_MAX];             /* real program path / 실제 프로그램 경로 */
    int    nprefix;
    char   prefix[EZG_MAX_PREFIX][64];     /* redirect prefixes / 재지정 앞부분 */
    size_t prefix_len[EZG_MAX_PREFIX];
    int    log_fd;                         /* -1 = off / -1이면 기록 안 함 */
    int    skip_slr;                       /* 1 = unwrap SLR entry point / 1이면 SLR 진입점 건너뜀 */
};

extern struct ezg_config ezg_cfg;

/* Load config from environment (idempotent) */
/* 환경변수에서 설정을 읽음(여러 번 불러도 한 번만 동작) */
void ezg_init(void);

/* Map an absolute path into rootfs when it matches a prefix; returns `in` or `buf` */
/* 앞부분이 맞는 절대 경로를 rootfs 안으로 바꿈. in 또는 buf를 돌려줌 */
const char *ezg_map(const char *in, char *buf, size_t bufsz);

/* True when path names this process's /proc/.../exe */
/* 경로가 자기 프로세스의 /proc/.../exe이면 참 */
int ezg_is_self_exe(const char *path);

/* Lightweight logger (no malloc, no stdio) */
/* 가벼운 기록 함수(malloc·stdio 안 씀) */
void ezg_log(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

/* Resolve the real libc symbol; aborts with a message if missing */
/* 실제 libc 함수 주소를 찾음. 없으면 메시지를 남기고 중단함 */
void *ezg_real(const char *name);

/* Macro: lazily cached pointer to the next definition of a symbol */
/* 매크로: 다음 정의의 함수 주소를 한 번만 찾아 보관함 */
#define EZG_REAL(type, name) \
    static __typeof__(type) real_##name; \
    if (!real_##name) real_##name = (type)ezg_real(#name)

#endif /* EZG_H */
