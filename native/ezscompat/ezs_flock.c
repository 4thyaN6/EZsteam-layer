/*
 * ezs_flock: flock() on folders that do not support it (Android shared storage) is reported as
 * granted instead of failing.
 * ezs_flock: flock()을 지원하지 않는 폴더(안드로이드 공용 저장소)에서는 실패 대신 잠금 성공으로 알림.
 *
 * Why: EZlator's Steam library on shared storage (/storage/emulated/0/EZlator/SteamLibrary, seen as
 * /mnt/ezlator-library) returns ENOSYS for flock (measured in steam.log: "flock
 * /mnt/ezlator-library/steamapps/appmanifest_*.acf LOCK_SH failed. errno = 38" for every manifest and
 * game file), and the Steam client could not use the library.
 * 이유: 공용 저장소의 EZlator Steam 라이브러리(/storage/emulated/0/EZlator/SteamLibrary, rootfs에서
 * /mnt/ezlator-library)는 flock에 ENOSYS를 돌려줌(steam.log 실측: 모든 manifest와 게임 파일에서
 * "flock ... LOCK_SH failed. errno = 38"). Steam 클라이언트가 그 라이브러리를 쓰지 못했음.
 *
 * Only ENOSYS / EOPNOTSUPP are turned into success; every other result (locks held by others,
 * bad descriptors) is passed on unchanged. No other process locks these files while Steam runs
 * (EZlator is a separate app and is not running at the same time) (assumption, test builds).
 * ENOSYS / EOPNOTSUPP만 성공으로 바꿈. 그 밖의 결과(다른 쪽이 잡은 잠금, 잘못된 디스크립터)는 그대로
 * 넘김. Steam이 도는 동안 이 파일을 잠그는 다른 프로세스는 없음(EZlator는 다른 앱이고 동시에 돌지
 * 않음)(가정, 시험 빌드).
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdio.h>
#include <sys/file.h>

int flock(int fd, int op)
{
    static int (*real)(int, int);
    static int warned;
    if (!real) real = (int (*)(int, int))dlsym(RTLD_NEXT, "flock");
    int r = real(fd, op);
    if (r < 0 && (errno == ENOSYS || errno == EOPNOTSUPP)) {
        if (!warned) {
            warned = 1;
            fprintf(stderr, "ezs-compat: flock not supported on a shared-storage file, treated as granted\n");
        }
        errno = 0;
        return 0;
    }
    return r;
}
