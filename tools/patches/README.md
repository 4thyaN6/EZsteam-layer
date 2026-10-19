# PRoot patches / PRoot 패치

Applied in file-name order by `tools/build-proot-android.sh` on top of termux/proot
`b8c1f5c5c6730dc9d521d5bd5ce1cfeedecde087`.
`tools/build-proot-android.sh`가 termux/proot `b8c1f5c5c6730dc9d521d5bd5ce1cfeedecde087` 위에
파일 이름 순서로 적용함.

| File / 파일 | Origin / 출처 | Purpose / 목적 |
|---|---|---|
| `proot-01-sysvipc-robust-list.patch` | steamclienttermux (MIT, Hunter Davis and contributors), commit `dee92b8432424e873011542e6926b12651bc823c`, the `src/extension/sysvipc/*` part of `patches/proot-steam-android.patch`, unmodified | Emulates `set_robust_list`/`get_robust_list` (Android app seccomp blocks `set_robust_list`; Valve's ARM64 `steamclient.so` checks `get_robust_list` and stops when the head is NULL), fuller SysV semaphore semantics (last pid, SETALL, GETNCNT/GETZCNT, waking blocked `semop` callers). `set_robust_list`/`get_robust_list` 에뮬레이션(안드로이드 앱 seccomp이 `set_robust_list`를 막음, Valve ARM64 `steamclient.so`는 `get_robust_list` 결과를 검사해 head가 NULL이면 멈춤), SysV 세마포어 동작 보강(마지막 pid, SETALL, GETNCNT/GETZCNT, 막힌 `semop` 깨우기) |
| `proot-02-crash-log.patch` | this project, adapted from the same steamclienttermux patch (`src/tracee/event.c` part) / 이 프로젝트, 같은 steamclienttermux 패치의 `src/tracee/event.c` 부분을 맞춤 | With `PROOT_CRASH_LOG` set, prints the signal, fault address, registers, the mapping of `ip`/`lr` and the command line of a crashing guest program to stderr. `PROOT_CRASH_LOG`가 있으면 죽은 게스트 프로그램의 시그널, 오류 주소, 레지스터, `ip`/`lr`이 속한 매핑, 명령줄을 stderr로 출력 |
| `proot-03-syscall-stat.patch` | this project / 이 프로젝트 | With `PROOT_SYSSTAT=<seconds>` set, counts every system call PRoot stops for per program name and syscall and prints one `PROOT_STAT` line of the busiest pairs to stderr every `<seconds>` (diagnosis of PRoot's own CPU use). A second `PROOT_STAT 경로` line lists the busiest program + call + guest path (digits folded to `#`). `PROOT_SYSSTAT=<초>`가 있으면 PRoot가 멈춰 처리하는 시스템 호출을 프로그램 이름·호출별로 세어 `<초>`마다 가장 많은 짝을 `PROOT_STAT` 한 줄로 stderr에 씀(PRoot 자체 CPU 사용 진단). 둘째 줄 `PROOT_STAT 경로`는 프로그램 + 호출 + 게스트 경로(숫자는 `#`) 상위를 보여 줌 |
| `proot-04-ioctl-filter.patch` | this project / 이 프로젝트 | The seccomp filter sends `ioctl` to PRoot only for the requests PRoot rewrites (SIOCGIFINDEX, FICLONE, terminal TC*2/TCSAFLUSH forms); GPU, sound, input and other ioctls run without stopping PRoot. `PROOT_FULL_IOCTL` set restores tracing every ioctl. seccomp 필터가 PRoot가 고쳐 쓰는 요청(SIOCGIFINDEX, FICLONE, 터미널 TC*2/TCSAFLUSH 형태)만 PRoot로 보냄. GPU·소리·입력 등 나머지 ioctl은 PRoot를 멈추지 않고 실행됨. `PROOT_FULL_IOCTL`이 있으면 예전처럼 모든 ioctl을 추적함 |
| `proot-05-recv-send-filter.patch` | this project / 이 프로젝트 | Netlink sockets PRoot watches (fake or real NETLINK_ROUTE) are moved to descriptors 900-963 right after `socket()` (dup3 + close, close-on-exec kept). The seccomp filter then sends `recvmsg`/`recvfrom` to PRoot only for that range, and `sendto` only for that range or when a destination address is given (Unix socket path translation). Other socket traffic (web helper, X server) runs without stopping PRoot. `PROOT_FULL_RECV` set restores tracing all of them. PRoot가 지켜보는 netlink 소켓(가짜 또는 실제 NETLINK_ROUTE)을 `socket()` 직후 디스크립터 900~963으로 옮김(dup3 + close, close-on-exec 유지). seccomp 필터는 `recvmsg`/`recvfrom`을 그 구간에서만, `sendto`는 그 구간이거나 목적지 주소가 있을 때(유닉스 소켓 경로 변환)만 PRoot로 보냄. 나머지 소켓 통신(웹 도우미, X 서버)은 PRoot를 멈추지 않음. `PROOT_FULL_RECV`가 있으면 예전처럼 전부 추적함 |

The MIT license text of steamclienttermux is in `LICENSE.steamclienttermux`.
PRoot itself is GPL-2.0-or-later; the patched result is distributed under it.
steamclienttermux의 MIT 라이선스 원문은 `LICENSE.steamclienttermux`에 있음.
PRoot 자체는 GPL-2.0-or-later이며 패치 결과물도 그 조건으로 배포함.
