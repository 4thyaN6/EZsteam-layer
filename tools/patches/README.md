# PRoot patches / PRoot 패치

Applied in file-name order by `tools/build-proot-android.sh` on top of termux/proot
`b8c1f5c5c6730dc9d521d5bd5ce1cfeedecde087`.
`tools/build-proot-android.sh`가 termux/proot `b8c1f5c5c6730dc9d521d5bd5ce1cfeedecde087` 위에
파일 이름 순서로 적용함.

| File / 파일 | Origin / 출처 | Purpose / 목적 |
|---|---|---|
| `proot-01-sysvipc-robust-list.patch` | steamclienttermux (MIT, Hunter Davis and contributors), commit `dee92b8432424e873011542e6926b12651bc823c`, the `src/extension/sysvipc/*` part of `patches/proot-steam-android.patch`, unmodified | Emulates `set_robust_list`/`get_robust_list` (Android app seccomp blocks `set_robust_list`; Valve's ARM64 `steamclient.so` checks `get_robust_list` and stops when the head is NULL), fuller SysV semaphore semantics (last pid, SETALL, GETNCNT/GETZCNT, waking blocked `semop` callers). `set_robust_list`/`get_robust_list` 에뮬레이션(안드로이드 앱 seccomp이 `set_robust_list`를 막음, Valve ARM64 `steamclient.so`는 `get_robust_list` 결과를 검사해 head가 NULL이면 멈춤), SysV 세마포어 동작 보강(마지막 pid, SETALL, GETNCNT/GETZCNT, 막힌 `semop` 깨우기) |
| `proot-02-crash-log.patch` | this project, adapted from the same steamclienttermux patch (`src/tracee/event.c` part) / 이 프로젝트, 같은 steamclienttermux 패치의 `src/tracee/event.c` 부분을 맞춤 | With `PROOT_CRASH_LOG` set, prints the signal, fault address, registers, the mapping of `ip`/`lr` and the command line of a crashing guest program to stderr. `PROOT_CRASH_LOG`가 있으면 죽은 게스트 프로그램의 시그널, 오류 주소, 레지스터, `ip`/`lr`이 속한 매핑, 명령줄을 stderr로 출력 |

The MIT license text of steamclienttermux is in `LICENSE.steamclienttermux`.
PRoot itself is GPL-2.0-or-later; the patched result is distributed under it.
steamclienttermux의 MIT 라이선스 원문은 `LICENSE.steamclienttermux`에 있음.
PRoot 자체는 GPL-2.0-or-later이며 패치 결과물도 그 조건으로 배포함.
