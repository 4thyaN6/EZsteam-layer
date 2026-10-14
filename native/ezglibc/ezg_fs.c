/*
 * Filesystem wrappers: map absolute paths (/usr, /etc, /home ...) into the rootfs.
 * 파일 시스템 가로채기: 절대 경로(/usr, /etc, /home 등)를 rootfs 안으로 바꿈
 *
 * Note: glibc calls many of these internally without going through the PLT
 * (e.g. fopen -> __open). That is why the stdio / dir / stat-compat entry points
 * are wrapped as well, not only the syscall-level ones.
 * 참고: glibc 내부 호출(fopen -> __open 등)은 PLT를 거치지 않아 가로챌 수 없음.
 * 그래서 시스템 호출 수준 함수만이 아니라 stdio·디렉터리·옛 stat 진입점도 같이 가로챔
 */
#define _GNU_SOURCE
#include "ezg.h"

#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/statvfs.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <stddef.h>
#include <unistd.h>
#include <utime.h>

/* Declare a local buffer and the mapped path for argument P */
/* 인자 P에 대한 지역 버퍼와 변환된 경로를 선언함 */
#define MAP(var, p) char var##_buf[PATH_MAX]; const char *var = ezg_map((p), var##_buf, sizeof(var##_buf))

/* Read the optional mode argument of open-like calls */
/* open 계열의 선택 인자 mode를 읽음 */
#define OPEN_MODE(flags, last, mode)                                  \
    mode_t mode = 0;                                                  \
    if ((flags) & O_CREAT || ((flags) & O_TMPFILE) == O_TMPFILE) {    \
        va_list _ap; va_start(_ap, last);                             \
        mode = (mode_t)va_arg(_ap, int); va_end(_ap);                 \
    }

/* ---------------------------------------------------------------------------------- */
/* open family / open 계열                                                              */
/* ---------------------------------------------------------------------------------- */

int open(const char *path, int flags, ...)
{
    EZG_REAL(int (*)(const char *, int, ...), open);
    OPEN_MODE(flags, flags, mode);
    MAP(p, path);
    return real_open(p, flags, mode);
}

int open64(const char *path, int flags, ...)
{
    EZG_REAL(int (*)(const char *, int, ...), open64);
    OPEN_MODE(flags, flags, mode);
    MAP(p, path);
    return real_open64(p, flags, mode);
}

int openat(int dirfd, const char *path, int flags, ...)
{
    EZG_REAL(int (*)(int, const char *, int, ...), openat);
    OPEN_MODE(flags, flags, mode);
    MAP(p, path);
    return real_openat(dirfd, p, flags, mode);
}

int openat64(int dirfd, const char *path, int flags, ...)
{
    EZG_REAL(int (*)(int, const char *, int, ...), openat64);
    OPEN_MODE(flags, flags, mode);
    MAP(p, path);
    return real_openat64(dirfd, p, flags, mode);
}

/* _FORTIFY_SOURCE variants (called by binaries built with fortify) */
/* _FORTIFY_SOURCE 변형(fortify로 빌드한 바이너리가 부름) */
int __open_2(const char *path, int flags)
{
    EZG_REAL(int (*)(const char *, int), __open_2);
    MAP(p, path);
    return real___open_2(p, flags);
}

int __open64_2(const char *path, int flags)
{
    EZG_REAL(int (*)(const char *, int), __open64_2);
    MAP(p, path);
    return real___open64_2(p, flags);
}

int __openat_2(int dirfd, const char *path, int flags)
{
    EZG_REAL(int (*)(int, const char *, int), __openat_2);
    MAP(p, path);
    return real___openat_2(dirfd, p, flags);
}

int __openat64_2(int dirfd, const char *path, int flags)
{
    EZG_REAL(int (*)(int, const char *, int), __openat64_2);
    MAP(p, path);
    return real___openat64_2(dirfd, p, flags);
}

int creat(const char *path, mode_t mode)
{
    EZG_REAL(int (*)(const char *, mode_t), creat);
    MAP(p, path);
    return real_creat(p, mode);
}

int creat64(const char *path, mode_t mode)
{
    EZG_REAL(int (*)(const char *, mode_t), creat64);
    MAP(p, path);
    return real_creat64(p, mode);
}

FILE *fopen(const char *path, const char *mode)
{
    EZG_REAL(FILE *(*)(const char *, const char *), fopen);
    MAP(p, path);
    return real_fopen(p, mode);
}

FILE *fopen64(const char *path, const char *mode)
{
    EZG_REAL(FILE *(*)(const char *, const char *), fopen64);
    MAP(p, path);
    return real_fopen64(p, mode);
}

FILE *freopen(const char *path, const char *mode, FILE *fp)
{
    EZG_REAL(FILE *(*)(const char *, const char *, FILE *), freopen);
    MAP(p, path);
    return real_freopen(p, mode, fp);
}

FILE *freopen64(const char *path, const char *mode, FILE *fp)
{
    EZG_REAL(FILE *(*)(const char *, const char *, FILE *), freopen64);
    MAP(p, path);
    return real_freopen64(p, mode, fp);
}

/* ---------------------------------------------------------------------------------- */
/* stat family / stat 계열                                                              */
/* ---------------------------------------------------------------------------------- */

int stat(const char *path, struct stat *st)
{
    EZG_REAL(int (*)(const char *, struct stat *), stat);
    MAP(p, path);
    return real_stat(p, st);
}

int stat64(const char *path, struct stat64 *st)
{
    EZG_REAL(int (*)(const char *, struct stat64 *), stat64);
    MAP(p, path);
    return real_stat64(p, st);
}

int lstat(const char *path, struct stat *st)
{
    EZG_REAL(int (*)(const char *, struct stat *), lstat);
    MAP(p, path);
    return real_lstat(p, st);
}

int lstat64(const char *path, struct stat64 *st)
{
    EZG_REAL(int (*)(const char *, struct stat64 *), lstat64);
    MAP(p, path);
    return real_lstat64(p, st);
}

int fstatat(int dirfd, const char *path, struct stat *st, int flags)
{
    EZG_REAL(int (*)(int, const char *, struct stat *, int), fstatat);
    MAP(p, path);
    return real_fstatat(dirfd, p, st, flags);
}

int fstatat64(int dirfd, const char *path, struct stat64 *st, int flags)
{
    EZG_REAL(int (*)(int, const char *, struct stat64 *, int), fstatat64);
    MAP(p, path);
    return real_fstatat64(dirfd, p, st, flags);
}

/*
 * Old-ABI entry points (binaries linked against glibc < 2.33 call these).
 * Since 2.33 they are compat-only symbols that dlsym cannot return, so they are
 * implemented on top of fstatat64 instead of forwarding. On aarch64 and x86_64 the
 * struct passed by those binaries has the same layout as struct stat64.
 * 옛 ABI 진입점(glibc 2.33 미만으로 링크한 바이너리가 부름).
 * 2.33부터 dlsym으로 못 얻는 호환 전용 심볼이라 넘기지 않고 fstatat64로 직접 구현함.
 * aarch64·x86_64에서는 이 바이너리들이 넘기는 구조체가 struct stat64와 같은 모양임
 */
static int xstat_impl(int dirfd, const char *path, void *st, int flags)
{
    EZG_REAL(int (*)(int, const char *, struct stat64 *, int), fstatat64);
    MAP(p, path);
    return real_fstatat64(dirfd, p, (struct stat64 *)st, flags);
}

int __xstat(int ver, const char *path, struct stat *st)            { (void)ver; return xstat_impl(AT_FDCWD, path, st, 0); }
int __xstat64(int ver, const char *path, struct stat64 *st)        { (void)ver; return xstat_impl(AT_FDCWD, path, st, 0); }
int __lxstat(int ver, const char *path, struct stat *st)           { (void)ver; return xstat_impl(AT_FDCWD, path, st, AT_SYMLINK_NOFOLLOW); }
int __lxstat64(int ver, const char *path, struct stat64 *st)       { (void)ver; return xstat_impl(AT_FDCWD, path, st, AT_SYMLINK_NOFOLLOW); }
int __fxstatat(int ver, int fd, const char *path, struct stat *st, int fl)     { (void)ver; return xstat_impl(fd, path, st, fl); }
int __fxstatat64(int ver, int fd, const char *path, struct stat64 *st, int fl) { (void)ver; return xstat_impl(fd, path, st, fl); }

int statx(int dirfd, const char *path, int flags, unsigned int mask, struct statx *stx)
{
    EZG_REAL(int (*)(int, const char *, int, unsigned int, struct statx *), statx);
    MAP(p, path);
    return real_statx(dirfd, p, flags, mask, stx);
}

int statfs(const char *path, struct statfs *b)
{
    EZG_REAL(int (*)(const char *, struct statfs *), statfs);
    MAP(p, path);
    return real_statfs(p, b);
}

int statfs64(const char *path, struct statfs64 *b)
{
    EZG_REAL(int (*)(const char *, struct statfs64 *), statfs64);
    MAP(p, path);
    return real_statfs64(p, b);
}

int statvfs(const char *path, struct statvfs *b)
{
    EZG_REAL(int (*)(const char *, struct statvfs *), statvfs);
    MAP(p, path);
    return real_statvfs(p, b);
}

int statvfs64(const char *path, struct statvfs64 *b)
{
    EZG_REAL(int (*)(const char *, struct statvfs64 *), statvfs64);
    MAP(p, path);
    return real_statvfs64(p, b);
}

/* ---------------------------------------------------------------------------------- */
/* access / directories / names  -  접근 검사·디렉터리·이름 변경                           */
/* ---------------------------------------------------------------------------------- */

int access(const char *path, int mode)
{
    EZG_REAL(int (*)(const char *, int), access);
    MAP(p, path);
    return real_access(p, mode);
}

int faccessat(int dirfd, const char *path, int mode, int flags)
{
    EZG_REAL(int (*)(int, const char *, int, int), faccessat);
    MAP(p, path);
    return real_faccessat(dirfd, p, mode, flags);
}

int euidaccess(const char *path, int mode)
{
    EZG_REAL(int (*)(const char *, int), euidaccess);
    MAP(p, path);
    return real_euidaccess(p, mode);
}

DIR *opendir(const char *path)
{
    EZG_REAL(DIR *(*)(const char *), opendir);
    MAP(p, path);
    return real_opendir(p);
}

/* scandir opens the directory internally, so it needs its own wrapper */
/* scandir는 내부에서 디렉터리를 열어 따로 가로채야 함 */
int scandir(const char *path, struct dirent ***list,
            int (*sel)(const struct dirent *),
            int (*cmp)(const struct dirent **, const struct dirent **))
{
    EZG_REAL(int (*)(const char *, struct dirent ***, int (*)(const struct dirent *),
                     int (*)(const struct dirent **, const struct dirent **)), scandir);
    MAP(p, path);
    return real_scandir(p, list, sel, cmp);
}

int scandir64(const char *path, struct dirent64 ***list,
              int (*sel)(const struct dirent64 *),
              int (*cmp)(const struct dirent64 **, const struct dirent64 **))
{
    EZG_REAL(int (*)(const char *, struct dirent64 ***, int (*)(const struct dirent64 *),
                     int (*)(const struct dirent64 **, const struct dirent64 **)), scandir64);
    MAP(p, path);
    return real_scandir64(p, list, sel, cmp);
}

int mkdir(const char *path, mode_t mode)
{
    EZG_REAL(int (*)(const char *, mode_t), mkdir);
    MAP(p, path);
    return real_mkdir(p, mode);
}

int mkdirat(int dirfd, const char *path, mode_t mode)
{
    EZG_REAL(int (*)(int, const char *, mode_t), mkdirat);
    MAP(p, path);
    return real_mkdirat(dirfd, p, mode);
}

int rmdir(const char *path)
{
    EZG_REAL(int (*)(const char *), rmdir);
    MAP(p, path);
    return real_rmdir(p);
}

int chdir(const char *path)
{
    EZG_REAL(int (*)(const char *), chdir);
    MAP(p, path);
    return real_chdir(p);
}

int unlink(const char *path)
{
    EZG_REAL(int (*)(const char *), unlink);
    MAP(p, path);
    return real_unlink(p);
}

int unlinkat(int dirfd, const char *path, int flags)
{
    EZG_REAL(int (*)(int, const char *, int), unlinkat);
    MAP(p, path);
    return real_unlinkat(dirfd, p, flags);
}

int remove(const char *path)
{
    EZG_REAL(int (*)(const char *), remove);
    MAP(p, path);
    return real_remove(p);
}

int rename(const char *a, const char *b)
{
    EZG_REAL(int (*)(const char *, const char *), rename);
    MAP(pa, a);
    MAP(pb, b);
    return real_rename(pa, pb);
}

int renameat(int da, const char *a, int db, const char *b)
{
    EZG_REAL(int (*)(int, const char *, int, const char *), renameat);
    MAP(pa, a);
    MAP(pb, b);
    return real_renameat(da, pa, db, pb);
}

int renameat2(int da, const char *a, int db, const char *b, unsigned int flags)
{
    EZG_REAL(int (*)(int, const char *, int, const char *, unsigned int), renameat2);
    MAP(pa, a);
    MAP(pb, b);
    return real_renameat2(da, pa, db, pb, flags);
}

int link(const char *a, const char *b)
{
    EZG_REAL(int (*)(const char *, const char *), link);
    MAP(pa, a);
    MAP(pb, b);
    return real_link(pa, pb);
}

int linkat(int da, const char *a, int db, const char *b, int flags)
{
    EZG_REAL(int (*)(int, const char *, int, const char *, int), linkat);
    MAP(pa, a);
    MAP(pb, b);
    return real_linkat(da, pa, db, pb, flags);
}

/*
 * symlink: the kernel resolves an absolute target against the real root, so an
 * absolute target under a mapped prefix is stored with the rootfs path instead.
 * symlink: 커널은 절대 대상 경로를 실제 루트 기준으로 풀기 때문에, 변환 대상
 * 앞부분으로 시작하는 절대 대상은 rootfs 경로로 바꿔 저장함
 */
int symlink(const char *target, const char *linkpath)
{
    EZG_REAL(int (*)(const char *, const char *), symlink);
    MAP(t, target);
    MAP(l, linkpath);
    return real_symlink(t, l);
}

int symlinkat(const char *target, int dirfd, const char *linkpath)
{
    EZG_REAL(int (*)(const char *, int, const char *), symlinkat);
    MAP(t, target);
    MAP(l, linkpath);
    return real_symlinkat(t, dirfd, l);
}

/* Copy the real program path into a readlink buffer (no NUL, truncated like the kernel) */
/* 실제 프로그램 경로를 readlink 버퍼에 복사함(NUL 없음, 커널처럼 잘라냄) */
static ssize_t self_exe_copy(char *buf, size_t sz)
{
    size_t n = strlen(ezg_cfg.self_exe);
    if (n > sz) n = sz;
    memcpy(buf, ezg_cfg.self_exe, n);
    return (ssize_t)n;
}

ssize_t readlink(const char *path, char *buf, size_t sz)
{
    EZG_REAL(ssize_t (*)(const char *, char *, size_t), readlink);
    ezg_init();
    if (ezg_cfg.self_exe[0] && ezg_is_self_exe(path)) return self_exe_copy(buf, sz);
    {
        MAP(p, path);
        return real_readlink(p, buf, sz);
    }
}

ssize_t readlinkat(int dirfd, const char *path, char *buf, size_t sz)
{
    EZG_REAL(ssize_t (*)(int, const char *, char *, size_t), readlinkat);
    ezg_init();
    if (ezg_cfg.self_exe[0] && ezg_is_self_exe(path)) return self_exe_copy(buf, sz);
    {
        MAP(p, path);
        return real_readlinkat(dirfd, p, buf, sz);
    }
}

/* Fortify variants of readlink / 위 함수의 fortify 변형 */
ssize_t __readlink_chk(const char *path, char *buf, size_t sz, size_t bufsz)
{
    (void)bufsz;
    return readlink(path, buf, sz);
}

ssize_t __readlinkat_chk(int dirfd, const char *path, char *buf, size_t sz, size_t bufsz)
{
    (void)bufsz;
    return readlinkat(dirfd, path, buf, sz);
}

char *realpath(const char *path, char *resolved)
{
    EZG_REAL(char *(*)(const char *, char *), realpath);
    ezg_init();
    if (path && ezg_cfg.self_exe[0] && ezg_is_self_exe(path)) {
        /* realpath resolves /proc/self/exe with an internal readlink: answer directly */
        /* realpath는 내부 readlink로 /proc/self/exe를 풀기 때문에 직접 답함 */
        return real_realpath(ezg_cfg.self_exe, resolved);
    }
    {
        MAP(p, path);
        return real_realpath(p, resolved);
    }
}

char *__realpath_chk(const char *path, char *resolved, size_t resolvedlen)
{
    (void)resolvedlen;
    return realpath(path, resolved);
}

char *canonicalize_file_name(const char *path)
{
    return realpath(path, NULL);
}

/* ---------------------------------------------------------------------------------- */
/* attributes / 속성 변경                                                                */
/* ---------------------------------------------------------------------------------- */

int chmod(const char *path, mode_t mode)
{
    EZG_REAL(int (*)(const char *, mode_t), chmod);
    MAP(p, path);
    return real_chmod(p, mode);
}

int fchmodat(int dirfd, const char *path, mode_t mode, int flags)
{
    EZG_REAL(int (*)(int, const char *, mode_t, int), fchmodat);
    MAP(p, path);
    return real_fchmodat(dirfd, p, mode, flags);
}

int chown(const char *path, uid_t u, gid_t g)
{
    EZG_REAL(int (*)(const char *, uid_t, gid_t), chown);
    MAP(p, path);
    return real_chown(p, u, g);
}

int lchown(const char *path, uid_t u, gid_t g)
{
    EZG_REAL(int (*)(const char *, uid_t, gid_t), lchown);
    MAP(p, path);
    return real_lchown(p, u, g);
}

int fchownat(int dirfd, const char *path, uid_t u, gid_t g, int flags)
{
    EZG_REAL(int (*)(int, const char *, uid_t, gid_t, int), fchownat);
    MAP(p, path);
    return real_fchownat(dirfd, p, u, g, flags);
}

int truncate(const char *path, off_t len)
{
    EZG_REAL(int (*)(const char *, off_t), truncate);
    MAP(p, path);
    return real_truncate(p, len);
}

int truncate64(const char *path, off64_t len)
{
    EZG_REAL(int (*)(const char *, off64_t), truncate64);
    MAP(p, path);
    return real_truncate64(p, len);
}

int utime(const char *path, const struct utimbuf *t)
{
    EZG_REAL(int (*)(const char *, const struct utimbuf *), utime);
    MAP(p, path);
    return real_utime(p, t);
}

int utimes(const char *path, const struct timeval t[2])
{
    EZG_REAL(int (*)(const char *, const struct timeval *), utimes);
    MAP(p, path);
    return real_utimes(p, t);
}

int utimensat(int dirfd, const char *path, const struct timespec t[2], int flags)
{
    EZG_REAL(int (*)(int, const char *, const struct timespec *, int), utimensat);
    MAP(p, path);
    return real_utimensat(dirfd, p, t, flags);
}

int mkfifo(const char *path, mode_t mode)
{
    EZG_REAL(int (*)(const char *, mode_t), mkfifo);
    MAP(p, path);
    return real_mkfifo(p, mode);
}

int mknod(const char *path, mode_t mode, dev_t dev)
{
    EZG_REAL(int (*)(const char *, mode_t, dev_t), mknod);
    MAP(p, path);
    return real_mknod(p, mode, dev);
}

int inotify_add_watch(int fd, const char *path, uint32_t mask)
{
    EZG_REAL(int (*)(int, const char *, uint32_t), inotify_add_watch);
    MAP(p, path);
    return real_inotify_add_watch(fd, p, mask);
}

/* ---------------------------------------------------------------------------------- */
/* dlopen / shared memory  -  라이브러리 열기·공유 메모리                                   */
/* ---------------------------------------------------------------------------------- */

void *dlopen(const char *path, int flags)
{
    EZG_REAL(void *(*)(const char *, int), dlopen);
    if (!path) return real_dlopen(path, flags);
    {
        MAP(p, path);
        return real_dlopen(p, flags);
    }
}

/*
 * shm_open: glibc opens "/dev/shm/<name>" internally; Android has no /dev/shm,
 * so the file is opened through the mapped path (<rootfs>/dev/shm by default).
 * shm_open: glibc가 내부에서 /dev/shm/<이름>을 여는데 안드로이드에는 /dev/shm이 없어
 * 변환된 경로(기본 <rootfs>/dev/shm)로 직접 엶
 */
static int shm_path(const char *name, char *out, size_t outsz)
{
    char tmp[PATH_MAX], mapbuf[PATH_MAX];
    const char *m;
    while (*name == '/') name++;
    if (!*name || strchr(name, '/')) { errno = EINVAL; return -1; }
    if (snprintf(tmp, sizeof(tmp), "/dev/shm/%s", name) >= (int)sizeof(tmp)) {
        errno = ENAMETOOLONG;
        return -1;
    }
    m = ezg_map(tmp, mapbuf, sizeof(mapbuf));
    if (strlen(m) >= outsz) { errno = ENAMETOOLONG; return -1; }
    strcpy(out, m);
    return 0;
}

int shm_open(const char *name, int oflag, mode_t mode)
{
    EZG_REAL(int (*)(const char *, int, mode_t), shm_open);
    EZG_REAL(int (*)(const char *, int, ...), open);
    char path[PATH_MAX];
    ezg_init();
    if (!ezg_cfg.rootfs_len) return real_shm_open(name, oflag, mode);
    if (shm_path(name, path, sizeof(path)) != 0) return -1;
    return real_open(path, oflag | O_NOFOLLOW | O_CLOEXEC, mode);
}

int shm_unlink(const char *name)
{
    EZG_REAL(int (*)(const char *), shm_unlink);
    EZG_REAL(int (*)(const char *), unlink);
    char path[PATH_MAX];
    ezg_init();
    if (!ezg_cfg.rootfs_len) return real_shm_unlink(name);
    if (shm_path(name, path, sizeof(path)) != 0) return -1;
    return real_unlink(path);
}

/* ---------------------------------------------------------------------------------- */
/* AF_UNIX sockets (X11 /tmp/.X11-unix/X0, PulseAudio, Steam IPC)                      */
/* 유닉스 소켓(X11 /tmp/.X11-unix/X0, PulseAudio, Steam 내부 통신)                       */
/* ---------------------------------------------------------------------------------- */

/*
 * Rewrite a filesystem AF_UNIX address into rootfs. Abstract sockets (sun_path[0] == 0)
 * and paths that would not fit into sun_path are left unchanged.
 * 파일 경로형 유닉스 소켓 주소를 rootfs로 바꿈. 추상 소켓(sun_path[0]==0)과
 * sun_path에 안 들어가는 경로는 그대로 둠
 */
static const struct sockaddr *map_sockaddr(const struct sockaddr *addr, socklen_t *len,
                                           struct sockaddr_un *out)
{
    const struct sockaddr_un *un = (const struct sockaddr_un *)addr;
    char path[sizeof(un->sun_path) + 1], mapbuf[PATH_MAX];
    size_t plen;
    const char *m;

    if (!addr || addr->sa_family != AF_UNIX || *len <= offsetof(struct sockaddr_un, sun_path))
        return addr;
    plen = *len - offsetof(struct sockaddr_un, sun_path);
    if (plen > sizeof(un->sun_path)) plen = sizeof(un->sun_path);
    if (plen == 0 || un->sun_path[0] == '\0') return addr;

    memcpy(path, un->sun_path, plen);
    path[plen] = '\0';
    m = ezg_map(path, mapbuf, sizeof(mapbuf));
    if (m == path || strlen(m) >= sizeof(out->sun_path)) {
        if (m != path) ezg_log("socket path too long, unchanged: %s\n", path);
        return addr;
    }
    memset(out, 0, sizeof(*out));
    out->sun_family = AF_UNIX;
    strcpy(out->sun_path, m);
    *len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + strlen(m) + 1);
    return (const struct sockaddr *)out;
}

int connect(int fd, const struct sockaddr *addr, socklen_t len)
{
    EZG_REAL(int (*)(int, const struct sockaddr *, socklen_t), connect);
    struct sockaddr_un tmp;
    const struct sockaddr *a = map_sockaddr(addr, &len, &tmp);
    return real_connect(fd, a, len);
}

int bind(int fd, const struct sockaddr *addr, socklen_t len)
{
    EZG_REAL(int (*)(int, const struct sockaddr *, socklen_t), bind);
    struct sockaddr_un tmp;
    const struct sockaddr *a = map_sockaddr(addr, &len, &tmp);
    return real_bind(fd, a, len);
}
