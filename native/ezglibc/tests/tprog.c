/*
 * Host self-test program for libezglibc.so.
 * libezglibc.so 호스트 자체 시험 프로그램임
 *
 * Prints one "OK <name>" or "FAIL <name>" line per check.
 * 확인 항목마다 "OK 이름" 또는 "FAIL 이름" 한 줄을 출력함
 */
#define _GNU_SOURCE
#include <fcntl.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;

static void report(int ok, const char *name, const char *detail)
{
    printf("%s %s%s%s\n", ok ? "OK" : "FAIL", name, detail ? " : " : "", detail ? detail : "");
    fflush(stdout);
}

/* Child mode: only reports /proc/self/exe so the parent can check exec chains */
/* 자식 모드: /proc/self/exe만 출력해 부모가 exec 연쇄를 확인하게 함 */
static int child_mode(const char *tag)
{
    char buf[4096] = {0};
    ssize_t n = readlink("/proc/self/exe", buf, sizeof(buf) - 1);
    printf("CHILD %s exe=%s\n", tag, n > 0 ? buf : "?");

    return 0;
}

int main(int argc, char **argv)
{
    const char *rootfs = getenv("EZG_ROOTFS");
    char buf[4096], want[4096];
    FILE *fp;
    ssize_t n;
    struct stat st;

    if (argc > 1 && strcmp(argv[1], "child") == 0) return child_mode(argc > 2 ? argv[2] : "");

    /* 1. fopen through a mapped prefix / 변환 앞부분을 거친 fopen */
    fp = fopen("/ezgtest/data.txt", "r");
    buf[0] = 0;
    if (fp) { if (!fgets(buf, sizeof(buf), fp)) buf[0] = 0; fclose(fp); }
    report(strncmp(buf, "ezg-data", 8) == 0, "fopen-mapped", NULL);

    /* 2. stat through a mapped prefix / 변환 앞부분을 거친 stat */
    report(stat("/ezgtest/data.txt", &st) == 0, "stat-mapped", NULL);

    /* 3. /proc/self/exe points at the program, not ld.so / /proc/self/exe가 ld.so가 아닌 프로그램을 가리킴 */
    n = readlink("/proc/self/exe", buf, sizeof(buf) - 1);
    buf[n > 0 ? n : 0] = 0;
    snprintf(want, sizeof(want), "%s/ezgtest/tprog", rootfs ? rootfs : "");
    report(strcmp(buf, want) == 0, "self-exe", buf);

    /* 4. realpath(/proc/self/exe) / realpath로 같은 확인 */
    {
        char *r = realpath("/proc/self/exe", NULL);
        report(r && strcmp(r, want) == 0, "realpath-self-exe", r ? r : "(null)");
        free(r);
    }

    /* 5. system() uses the rootfs /bin/sh / system()이 rootfs의 /bin/sh를 씀 */
    report(system("test -f /ezgtest/data.txt && exit 7") == (7 << 8), "system", NULL);

    /* 6. popen() / popen() */
    fp = popen("echo popen-ok", "r");
    buf[0] = 0;
    if (fp) { if (!fgets(buf, sizeof(buf), fp)) buf[0] = 0; report(pclose(fp) == 0, "pclose", NULL); }
    report(strncmp(buf, "popen-ok", 8) == 0, "popen", NULL);

    /* 7. posix_spawn of a glibc program (self) / glibc 프로그램(자기 자신) posix_spawn */
    {
        pid_t pid;
        int status = -1;
        char *av[] = { "tprog-spawned", "child", "spawn", NULL };
        int rc = posix_spawn(&pid, "/ezgtest/tprog", NULL, NULL, av, environ);
        if (rc == 0) waitpid(pid, &status, 0);
        report(rc == 0 && WIFEXITED(status) && WEXITSTATUS(status) == 0, "posix_spawn", NULL);
    }

    /* 8. execvp through PATH into rootfs + shebang script / PATH 검색 + #! 스크립트 */
    {
        pid_t pid = fork();
        int status = -1;
        if (pid == 0) {
            char *av[] = { "script.sh", "arg1", NULL };
            setenv("PATH", "/ezgtest", 1);
            execvp("script.sh", av);
            _exit(99);
        }
        waitpid(pid, &status, 0);
        report(WIFEXITED(status) && WEXITSTATUS(status) == 0, "execvp-shebang", NULL);
    }

    /* 9. shm_open goes to <rootfs>/dev/shm / shm_open이 <rootfs>/dev/shm으로 감 */
    {
        int fd = shm_open("/ezg-test-shm", O_CREAT | O_RDWR, 0600);
        char p[4096];
        snprintf(p, sizeof(p), "%s/dev/shm/ezg-test-shm", rootfs ? rootfs : "");
        report(fd >= 0 && access(p, F_OK) == 0, "shm_open", p);
        if (fd >= 0) close(fd);
        report(shm_unlink("/ezg-test-shm") == 0, "shm_unlink", NULL);
    }

    /* 10. symlink with an absolute mapped target is stored with the rootfs path */
    /* 변환 앞부분 절대 대상 symlink는 rootfs 경로로 저장됨 */
    {
        char t[4096] = {0};
        unlink("/ezgtest/link");
        if (symlink("/ezgtest/data.txt", "/ezgtest/link") != 0) perror("symlink");
        fp = fopen("/ezgtest/link", "r");
        buf[0] = 0;
        if (fp) { if (!fgets(buf, sizeof(buf), fp)) buf[0] = 0; fclose(fp); }
        snprintf(want, sizeof(want), "%s/ezgtest/link", rootfs ? rootfs : "");
        n = (ssize_t)syscall(SYS_readlinkat, AT_FDCWD, want, t, sizeof(t) - 1); /* raw: bypass wrapper / 가로채기 우회 */
        report(strncmp(buf, "ezg-data", 8) == 0, "symlink-follow", t);
    }

    /* 11. AF_UNIX bind/connect on a mapped path / 변환 경로에서 유닉스 소켓 bind·connect */
    {
        struct sockaddr_un a;
        int s1 = socket(AF_UNIX, SOCK_STREAM, 0), s2 = socket(AF_UNIX, SOCK_STREAM, 0), ok;
        char p[4096];
        memset(&a, 0, sizeof(a));
        a.sun_family = AF_UNIX;
        strcpy(a.sun_path, "/ezgtest/sock");
        unlink("/ezgtest/sock");
        ok = bind(s1, (struct sockaddr *)&a, sizeof(a)) == 0 && listen(s1, 1) == 0 &&
             connect(s2, (struct sockaddr *)&a, sizeof(a)) == 0;
        snprintf(p, sizeof(p), "%s/ezgtest/sock", rootfs ? rootfs : "");
        report(ok && access(p, F_OK) == 0, "unix-socket", p);
        close(s1);
        close(s2);
    }
    /* 12. Steam Linux Runtime entry point is skipped (EZG_SKIP_SLR=1): the script itself
       exits 55, so exit 0 proves the command after "--" ran directly */
    /* SLR 진입점 건너뛰기: 스크립트 자체는 55로 끝나므로 0이면 "--" 뒤 명령이 바로 실행된 것임 */
    {
        pid_t pid = fork();
        int status = -1;
        if (pid == 0) {
            char *av[] = { "_v2-entry-point", "--verb=waitforexitandrun", "--",
                           "/ezgtest/tprog", "child", "slr", NULL };
            execv("/ezgtest/_v2-entry-point", av);
            _exit(99);
        }
        waitpid(pid, &status, 0);
        report(WIFEXITED(status) && WEXITSTATUS(status) == 0, "skip-slr",
               WIFEXITED(status) && WEXITSTATUS(status) == 55 ? "entry point ran" : NULL);
    }
    return 0;
}
