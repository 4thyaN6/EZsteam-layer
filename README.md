# ezsteam-layer

안드로이드 앱 안에서 PRoot로 glibc aarch64 rootfs의 프로그램(리눅스 Steam ARM64, Valve ARM64 Proton 등)을 실행하기 위한 실험용 레이어임. 루트·개발자 옵션 불필요. EZlator 런타임으로 옮길 것을 전제로 함.

## 왜 PRoot인가
- 안드로이드 앱 seccomp은 `rseq`, `set_robust_list`, `faccessat2`, `fchmodat2`, `openat2` 등을 막고, 막힌 호출은 SIGSYS로 프로세스를 종료함
- glibc 2.35 이상은 프로그램 시작 단계에서 `rseq`·`set_robust_list`를 부름 → PRoot 없이 로더를 직접 실행하면 `/bin/sh`도 시작 직후 종료됨(같은 필터를 건 시험에서 확인)
- PRoot는 이 SIGSYS를 받아 ENOSYS로 돌려주므로 glibc가 대체 경로로 계속 동작함(같은 시험에서 스레드 생성·`faccessat2` 경로·자식 exec 정상)
- PRoot는 rootfs를 `/`로 보이게 하므로 Valve 파일(`/lib/ld-linux-aarch64.so.1`, 고정 경로)을 고치지 않고 그대로 씀
- 늘어나는 프로세스는 PRoot 추적 프로세스 1개. 자식 프로세스 한도(모든 앱 합계 32개)는 PRoot와 관계없이 적용됨

## 구성
| 경로 | 역할 |
|---|---|
| `runtime/` | **분리된 Steam 런타임 모듈**(Android 라이브러리, 패키지 `app.ezsteam.runtime`). 호스트 앱은 API만 부름 |
| `runtime/.../GlibcLauncher.java` | PRoot 명령·환경변수 구성, guest 프로그램·스크립트 실행, Steam 준비 |
| `runtime/.../RootfsDownloader.java` | GitHub Release에서 `rootfs.tar.xz` 받기(sha256 확인, 버튼 누를 때만) |
| `runtime/.../TarExtractor.java`, `RootfsInstaller.java` | rootfs를 `files/ezs`에 풂(링크·권한 유지, 홈 폴더 보존). 원본은 Release 파일 또는 개발용 asset |
| `runtime/.../ChildProcessCounter.java` | 앱에서 시작된 프로세스 목록(/proc) |
| `runtime/.../DriverInstaller.java` | glibc Vulkan 드라이버 묶음 받기·설치(`files/ezs-data/drivers/<이름>`)·적용(guest `/opt/ezdrv`, `VK_DRIVER_FILES`, 묶음 `env`) |
| `runtime/.../XvfbFramebuffer.java` | Xvfb `-fbdir` 화면 파일(XWD) 매핑, 픽셀(B G R X 32비트) 직접 접근 |
| `runtime/.../FramebufferView.java` | 위 화면을 OpenGL ES 3 텍스처로 매 프레임 올려 비율 유지 표시. 확대 방식 선형 / 바이큐빅(Catmull-Rom) / 바이큐빅 + CAS 방식 선명화, fps·업로드 시간·내용 변경 수, 소프트 키보드 텍스트 → 키 이벤트 |
| `runtime/.../XTestClient.java`, `XKeys.java` | X11 소켓 직접 통신으로 XTEST 포인터·버튼·키 입력 주입(Xlib 불필요), 안드로이드 키 → X 키코드(evdev + 8) |
| `runtime/src/main/jniLibs/arm64-v8a/` | `libproot.so`, `libproot-loader.so`(CI가 만듦, 저장소에는 없음) |
| `app/` | 시험용 호스트 앱(targetSdk 28): rootfs 설치, 환경 시험, 자식 프로세스 수, Steam 준비, PRoot 상태, 실시간 화면(`DisplayActivity`) |
| `.github/workflows/build.yml` | NDK로 PRoot, arm64 실행기에서 rootfs, 이어서 APK 빌드. 태그 빌드는 Release에 올림 |
| `tools/build-proot-android.sh` | termux/proot 고정 커밋 + `tools/patches/proot-*.patch` + talloc 단일 파일본을 NDK(API 26)로 빌드 |
| `tools/patches/` | PRoot 패치: robust-list·SysV 세마포어 에뮬레이션(steamclienttermux, MIT, Valve ARM64 `steamclient.so`가 `get_robust_list`를 검사), `PROOT_CRASH_LOG` 크래시 위치 출력. 출처·라이선스는 `tools/patches/README.md` |
| `tools/build-rootfs.sh` | Debian 13 trixie arm64 + python3 + Vulkan 로더·vulkaninfo + Turnip 런타임 라이브러리로 `rootfs.tar.xz`·sha256·버전·패키지 목록 생성 |
| `tools/build-turnip-glibc.sh` | Mesa 26.2.4 Turnip(KGSL + X11, glibc aarch64)을 trixie 컨테이너에서 빌드해 `turnip.tar.xz` 묶음 생성 |
| `tools/measure-phantom.ps1` | 자식 프로세스 수·종료 기록 측정(Windows PowerShell + adb) |
| `tools/fix-abs-symlinks.sh` | rootfs 절대 심볼릭 링크를 상대 링크로 변환 |
| `tools/device-smoke.sh` | `adb shell`에서 앱 없이 PRoot + rootfs 확인 |
| `rootfs-overlay/opt/ezsteam/smoke.sh` | 환경 시험(스레드 생성, seccomp 차단 호출 항목 포함) |
| `rootfs-overlay/opt/ezsteam/gpucheck.sh` | GPU 확인: `/dev/kgsl-3d0` 열기, KGSL 장치 정보 ioctl, 장치 파일, `vulkaninfo --summary` |
| `rootfs-overlay/opt/ezsteam/vkcheck.py` | vulkaninfo 전체 결과를 `~/ezs-logs/vulkaninfo.txt`로 저장하고 vkd3d-proton·DXVK 요구 항목을 OK/FAIL/UNKNOWN으로 표시 |
| `rootfs-overlay/opt/ezsteam/tools-install.sh` | [시험 도구 설치]: PRoot 가짜 root(`--root-id`) + 기기 DNS로 `apt-get install xvfb x11-apps`(기본 rootfs에는 넣지 않음) |
| `rootfs-overlay/opt/ezsteam/drawtest.sh`, `xwd2png.py` | [그리기 시험]: Xvfb `:99` + Turnip `vkcube` 600프레임, fps, `xwd` 캡처를 `~/ezs-logs/drawtest.png`로 저장해 앱에 표시 |
| `rootfs-overlay/opt/ezsteam/display.sh` | [화면 WxH]: Xvfb `:98` `-fbdir` + 시험 창(vkcube, xclock, xeyes, xcalc, keyview), 앱이 stdin을 닫으면 종료 |
| `rootfs-overlay/opt/ezsteam/keyview.py` | 키 확인 창(libX11 ctypes): 키보드 포커스를 갖고 받은 X 키코드·키 이름을 창과 앱 로그(`KEY`)에 표시 |
| `rootfs-overlay/opt/ezsteam/steam-install.sh` | [Steam 설치]: 사용자 steam(uid 1000), 클라이언트·CEF 라이브러리(apt), Valve 업데이트 서버(`client-update.steamstatic.com/steam_client_linuxarm64`)에서 기기가 직접 부트스트랩 받기(매니페스트 sha2 대조), `~/.local/share/Steam`에 풀기 |
| `rootfs-overlay/opt/ezsteam/steam-deps.sh` | 설치된 클라이언트 ELF 파일에 ldd → 없는 라이브러리 목록, `install` 모드에서 이름 규칙(libfoo-1.2.so.3 → libfoo-1.2-3/…t64)으로 Debian 패키지를 찾아 설치 |
| `rootfs-overlay/opt/ezsteam/bin/lsof` | `/usr/bin/lsof` 자리에 설치(dpkg-divert, 클라이언트가 절대 경로로 실행). Steam의 `lsof -i TCP@127.0.0.1:포트` 질의만 steamwebhelper 네트워크 프로세스로 답함(안드로이드가 소켓 주인을 숨김), 나머지는 원본 `lsof.distrib`. 호출 기록 `/tmp/ezs-lsof.log` |
| `rootfs-overlay/opt/ezsteam/steam-run.sh` | `steamrtarm64/steam -no-cef-sandbox -cef-disable-gpu -chromeosnopreallocate -noverifyfiles` 실행, 종료 코드 42(자체 갱신)면 재시작, 전체 로그 `~/ezs-logs/steam.log` |
| `rootfs-overlay/opt/ezsteam/ezsteam-prepare.sh` | Steam 시작 전 준비: `~/.steam/sdkarm64/steamclient.so` 연결, EZ Proton 설치 |
| `rootfs-overlay/opt/ezsteam/compat/ezproton/` | 경량 호환 도구: Valve ARM64 Proton을 컨테이너 없이 직접 호출 |
| `native/ezglibc/` | 이전 방식의 `libezglibc.so`(LD_PRELOAD 경로 변환). 실행에는 쓰지 않음. 나중 개선 작업용으로 CI에서 빌드·자체 시험만 유지 |
| `tools/build-ezglibc-aarch64.sh` | 위 라이브러리 교차 빌드(보관용) |

## Steam → Valve Proton(ARM64) 실행 경로
Valve ARM64 Proton(`toolmanifest_arm64.vdf`)은 `require_tool_appid 4185400`(Steam Linux Runtime 4.0 - Arm64)을 요구함. SLR 컨테이너(pressure-vessel, bwrap)는 쓰지 않고, 그 역할(리눅스 라이브러리 제공)을 이 rootfs가 맡음(SLR 4와 같은 Debian 13 기반).
- **"EZ Proton" 선택**: `require_tool_appid`가 없는 호환 도구가 Steam이 받은 Valve ARM64 Proton(`steamapps/common/*/`, 4185400 요구 폴더)의 `proton`을 바로 실행함

공통 필수 조건
- `$HOME/.steam/sdkarm64/steamclient.so` = aarch64 `steamclient.so`(Valve lsteamclient가 이 경로를 고정으로 엶) → `ezsteam-prepare.sh`가 연결
- rootfs에 `python3`(`proton`은 `#!/usr/bin/env python3`)
- Steam이 넘기는 `STEAM_COMPAT_DATA_PATH`, `STEAM_COMPAT_CLIENT_INSTALL_PATH`(없으면 `proton`이 멈춤)
- Valve Proton 파일은 각 기기의 Steam이 공식 서버에서 받음(이 저장소는 포함·재배포하지 않음)

## 고정 작업 순서 (Windows)
1. 받은 라운드 zip 안의 `ezsteam-layer` 폴더를 `D:\AndroidStudioProject\`에 덮어쓰기
2. `ezs-push.bat` 더블클릭 → 커밋 메시지 입력(Enter = update) → 업로드
3. GitHub Actions가 모두 초록이 되면 `ezs-install.bat` 더블클릭 → 최신 APK를 받아 태블릿에 덮어 설치(최신이 아니면 기다리라고 알려 줌)
4. 앱 첫 줄 `app build=<커밋 7자리>`로 새 APK인지 확인
- 최신 APK 고정 주소: 사전 출시 `apk-latest`(main 빌드마다 CI가 교체). rootfs·드라이버용 `releases/latest`에는 영향 없음
- CI 서명: 저장소 비밀값 `DEBUG_KEYSTORE_B64`(PC의 debug.keystore)로 고정 → 삭제 없이 덮어 설치

## 빌드 (Windows + Android Studio)
- 기준: Android Studio Otter 3 Feature Drop 2025.2.3, AGP 8.13.0, Gradle 8.13(wrapper), compileSdk 36, targetSdk 28, Java 17
- PRoot: CI 아티팩트 `proot`의 `out/jni/*.so`를 `runtime/src/main/jniLibs/arm64-v8a/`에 넣음(없으면 빌드 때 경고, 앱은 실행 불가). 직접 만들려면 리눅스/WSL에서 `ANDROID_NDK=<NDK 경로> sh tools/build-proot-android.sh`
- 호스트 앱은 `packaging { jniLibs { useLegacyPackaging true } }` 필요(`libproot.so`를 nativeLibraryDir로 풀어 실행)
- rootfs는 EZlator imagefs처럼 GitHub Release에서 받음: `gradle.properties`의 `ezsteam.rootfsBaseUrl`에 `https://github.com/<소유자>/<저장소>/releases/latest/download/` 기재(CI는 자동 기재). 첫 Release는 `v*` 태그를 올리면 CI가 만듦
- rootfs는 APK에 넣지 않음(CI도 넣지 않음). Release 자산: `rootfs.tar.xz`, `rootfs.tar.xz.sha256`, `rootfs.version`, `rootfs.packages.txt`, 드라이버 `turnip.tar.xz`, `turnip.tar.xz.sha256`, `turnip.version`
- Release는 사전 출시(prerelease)로 두지 않음: `releases/latest` 주소가 사전 출시를 찾지 않음
- 개발용으로 `app/src/main/assets/rootfs.tar.xz`(+ `rootfs.version`)를 넣으면 받기 대신 그것을 씀
- `.gitattributes`로 스크립트 LF 유지(Windows에서 받아도 rootfs 안 스크립트가 깨지지 않게)

## 런타임 분리 원칙
- 호스트 앱(시험 앱, 나중에 EZlator)은 `:runtime` 모듈을 의존성으로 붙이고 API만 부름. 런타임 코드를 호스트 패키지로 옮기지 않음
- 저장 위치도 분리: rootfs `files/ezs`, PRoot 임시·`/dev/shm`·보관 `files/ezs-data` (EZlator `files/imagefs`와 겹치지 않음)
- rootfs 주소(`ROOTFS_BASE_URL`), targetSdk, 화면, 추가 바인드(X 서버 소켓 등)는 호스트가 정함(`GlibcLauncher.Config.binds`)

## 조건·제약
- targetSdk 28 이하(앱 저장소 파일 exec)
- PRoot 옵션: `--kill-on-exit`, `/dev` `/proc` `/sys` 바인드, `files/ezs-data/shm` → `/dev/shm`, `--link2symlink`(앱 저장소는 하드 링크 거부), `--sysvipc`(System V IPC 흉내)
- rootfs 경로는 짧게 유지: 유닉스 소켓 경로 제한 107바이트
- Steam은 root 실행을 거부하므로 PRoot `-0`(가짜 root)는 쓰지 않고 `--change-id=1000:1000`을 씀(apt 설치에만 `-0`)

## 시험
- 앱: GitHub Actions `build` → `apk` 아티팩트 설치 → [PRoot 상태] → [rootfs 설치] → [환경 시험] → [자식 프로세스] → [Steam 준비]
- GPU: [드라이버 설치](Release의 `turnip.tar.xz`) → [GPU 확인]. KGSL 단독 Turnip은 X11 출력에 `MESA_VK_WSI_DEBUG=sw`가 필요해 묶음 `env`로 넣음
- 실시간 화면: [시험 도구 설치] 후 [화면 1280x800] 등 → 한 손가락 = 왼쪽 버튼(탭·끌기), 두 손가락 탭 = 오른쪽 클릭, 두 손가락 세로 끌기 = 휠, [키보드] = 소프트 키보드(입력은 keyview 창과 위쪽 막대 `키`에 표시), [확대] = 확대 방식 전환, 뒤로 가기·[닫기] = 종료
- Steam: [시험 도구 설치] → [Steam 설치] → [Steam 실행 1280x800]. 화면은 Xvfb `:98`, 실행 id는 PRoot `--change-id=1000:1000`(가짜 사용자 steam, Steam은 root 거부, Xvfb의 setuid/setgid도 처리됨). 첫 실행은 클라이언트 자체 갱신(수백 MB)으로 오래 걸림. 갱신 후 라이브러리 부족으로 멈추면 [Steam 설치]를 다시 누름(부트스트랩은 건너뛰고 없는 라이브러리만 설치)
- [환경 시험]의 `pthread`, `sys-*` 항목이 FAIL `SIGSYS (seccomp)`이면 PRoot가 해당 호출을 처리하지 못한 것 → rootfs glibc 패치(termux-pacman 방식) 검토
- (선택) 앱 없이: `tools/device-smoke.sh` 머리 주석 참고
- 호스트: `make -C native/ezglibc test` (보관 중인 preload 라이브러리)

## 라이선스
- 이 저장소: GPL-3.0 예정(LICENSE 파일은 저장소 생성 때 추가)
- PRoot(termux/proot): GPL-2.0-or-later, 사용 커밋·패치는 APK 안 `assets/proot-source.txt`와 `tools/build-proot-android.sh`에 기록
- PRoot 패치 일부: steamclienttermux(MIT), 원문 `tools/patches/LICENSE.steamclienttermux`
- talloc: LGPL-3.0-or-later(Winlator-Cmod의 단일 파일본, 출처·해시는 `tools/build-proot-android.sh`)
- Mesa(Turnip): MIT, 사용 소스·해시는 묶음 안 `source.txt`와 `tools/build-turnip-glibc.sh`
- rootfs의 Debian 패키지 목록: Release의 `rootfs.packages.txt`, rootfs 안 `/opt/ezsteam/packages.txt`
