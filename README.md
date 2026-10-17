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
| `rootfs-overlay/opt/ezsteam/steam-run.sh` | `steamrtarm64/steam -no-cef-sandbox -cef-disable-gpu -chromeosnopreallocate -noverifyfiles` 실행, 종료 코드 42(자체 갱신)면 재시작, 전체 로그 `~/ezs-logs/steam.log`(주요 줄은 앱이 직접 따라 읽어 표시, rootfs 안 tail/grep 프로세스 없음) |
| `rootfs-overlay/opt/ezsteam/speedtest.sh` | [속도 시험]: Valve 업데이트 서버 파일(약 100 MB)로 네트워크만 / 네트워크+파일 받기 속도, 큰 파일 쓰기, 작은 파일 2000개 쓰기 속도 측정(PRoot 영향 확인) |
| `rootfs-overlay/opt/ezsteam/ezsteam-prepare.sh` | Steam 시작 전 준비: `~/.steam/sdkarm64` 링크 폴더(클라이언트 `linuxarm64/*`, `steam-launch-wrapper`, aarch64 `steamclient.so`), EZ Proton 설치 |
| `rootfs-overlay/opt/ezsteam/compat/ezproton/` | 경량 호환 도구: Valve ARM64 Proton을 컨테이너 없이 직접 호출, Proton 로그 `~/ezs-logs/steam-<appid>.log`(앱 로그에 `PROTON` 줄로 표시) |
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
- 프로세스 절감: [CEF 절감: 켬/끔](메인 네 번째 줄, 기본 켬) → steamwebhelper.sh에 Chromium 플래그(`--no-zygote --in-process-gpu --renderer-process-limit=1`, 오디오 서비스 내장) 삽입. 네트워크 서비스 내장은 UI WebSocket 실패(0x3008)로 제외, EZ Proton은 `tabtip.exe` 끔, Steam 모드에서 display.sh가 steam-run.sh로 exec. 화면 [프로세스]: 현재·최대 목록, [Proton]: Proton 로그 마지막 200줄
- 게임 화면 전달: [게임 화면: …](메인 다섯 번째 줄) → `PutImage`(기본, `MESA_VK_WSI_DEBUG=sw,noshm` + DXVK 수직 동기 끔·60fps 제한) / `SHM 동기 끔` / `SHM mailbox` / `SHM fifo`. 근거: Mesa X11 sw 경로의 present id는 MIT-SHM + Present COMPLETE 이벤트로만 올라가고, DXVK는 FIFO에서만 present id를 제한 시간 없이 기다림. [DXVK HUD: 켬/끔] → `DXVK_HUD=fps,frametimes,gpuload`
- 화면 위쪽 둘째 줄: 경과 시간, 게임 화면 방식, CPU 사용률(2초 주기, 100% = 코어 하나): 자식 전체·게임·Xvfb·PRoot. [프로세스] 창에도 CPU% 열
- [로그 저장](화면 위쪽): 안드로이드 파일 선택 창으로 zip 하나 저장(권한 불필요, 자동 전송 없음). 앱 로그, 프로세스·게임 스레드 목록, `~/ezs-logs/*`(steam.log, Proton 로그), `/tmp/ezs-lsof.log`. 파일당 끝부분 32MB까지. 계정 이름이 들어 있을 수 있어 공유 전 확인. [Proton]: Proton 로그가 여러 개면 최신 순 목록에서 고름. 앱 로그는 저장용으로 최근 5000줄(시각 포함, 화면은 300줄). EZ Proton은 같은 게임의 이전 Proton 로그를 `old-steam-<appid>-<시각>.log`로 옮겨 최근 4개 유지(Proton은 시작 때 이전 로그를 지우고, Steam 설치 스크립트도 같은 appid로 실행됨)
- 게임 호환 도구는 반드시 EZ Proton: Valve Proton을 직접 고르면 Steam Linux Runtime 컨테이너(pressure-vessel)를 거치는데 PRoot 안에서 `opendir(/proc/self/root)` 실패로 게임이 바로 끝남(실측). 앱 로그에 HINT 줄로 알림
- 소리: rootfs 안 ALSA 기본 장치 → 자체 플러그인 `native/ezsaudio/pcm_ezs.c`(ioplug, S16_LE 48000Hz 2ch 고정, 앞단 plug가 변환) → UNIX 소켓 `/tmp/ezs-audio.sock` → 앱 `AudioServer`(연결마다 AudioTrack, 저지연 모드). 추가 프로세스 없음. 녹음은 같은 플러그인이 무음을 줌(ALSA null은 /dev/full이 막혀 못 씀). 앱 쪽 실제 지연 50 ms 설정. steam-run.sh가 `~/.asoundrc`를 매번 씀, EZ Proton은 `winepulse.drv=d`로 ALSA 사용. 플러그인은 CI `alsa` 작업(debian:trixie arm64)에서 빌드해 APK assets `ezsteam/lib`로 넣음(rootfs 재다운로드 불필요)
- 게임 컨트롤러: 안드로이드 컨트롤러(버튼 KeyEvent, 스틱·트리거·햇 SOURCE_JOYSTICK MotionEvent)를 앱이 읽어 64바이트 공유 파일 `/tmp/ezs-pad0.mem`(`PadFile`, 메모리 매핑)에 씀 → EZ Proton이 `LD_PRELOAD`로 넣는 `native/ezspad/ezs_pad.c`가 winedevice.exe(winebus) 안에서만 자체 빌드 libSDL2(libudev 없음, `/opt/ezsteam/lib/libSDL2-2.0.so.0`, 같은 soname이라 Wine도 이것을 씀. rootfs SDL2는 udev 없이 햅틱 초기화가 실패해 Wine SDL 버스가 못 뜸)를 열고 조이스틱 udev를 끈 뒤 SDL 가상 컨트롤러(Xbox 360 045e:028e, 축 6, 버튼 15)를 붙임 → Wine SDL 버스가 실제 패드로 보고 게임은 XInput/DirectInput으로 받음. 추가 프로세스·스레드 없음. 컨트롤러 입력은 X 키로 보내지 않음(십자키 포함, 장치가 컨트롤러면 십자키·뒤로 가기도 패드로). 스틱은 창 단계(dispatchGenericMotionEvent)에서 받아 안드로이드가 십자키 반복으로 바꾸지 않게 함. EZlator/Winlator evshim과 같은 설계(MIT), 코드는 새로 작성. CI `alsa` 작업에서 함께 빌드(SDL2는 libsdl-org/SDL `release-2.30.9` 태그 소스) Steam 클라이언트(빅픽처·메뉴)에는 같은 라이브러리가 `steam` 프로세스에서 클라이언트 자체 `libSDL3.so.0`(실측: udev netlink 거부, /dev/input 감시 거부로 컨트롤러 못 찾음)이 불러와지고 조이스틱 하위 시스템이 켜지면 SDL3 가상 게임패드를 붙임(`ezs_pad_steam.c`, SDL3 헤더 `release-3.2.0`만 사용). steam-run.sh가 클라이언트를 이 LD_PRELOAD로 시작
- 시작 화면: [Steam 시작], [화면 1280x800/1920x1200], [바로 실행: 켬/끔](앱을 새로 열 때 바로 Steam, 기본 켬), [관리자 모드](설치·시험 버튼 표시/숨김). rootfs·드라이버·Steam 중 빠진 것이 있으면 관리자 모드를 열고 이유를 로그에 씀
- 화면 터치 패드([패드], 길게 누르면 크기·진하기): Xbox 배치(스틱 2, 십자키, ABXY, LB/RB, LT/RT, BACK/START/가이드, 스틱 두 번 탭 = L3/R3). 자체 설계(`TouchPadView`). 실제 컨트롤러와 같은 가상 패드로 감(`PadFile` 입력원 2개를 합침: 버튼 OR, 스틱은 더 민 쪽, 트리거는 큰 값). 크기는 조작부 크기와 가장자리 거리를 함께 50~180%. 조작부 밖에서 시작한 터치는 아래 화면(포인터·클릭)으로 넘어감
- 글꼴: 앱이 휴대폰 `/system/fonts`를 rootfs `/usr/local/share/fonts/android`에 읽기 전용으로 연결(복사·배포 없음, DroidDeck과 같은 방식). steam-run.sh가 `~/.config/fontconfig/fonts.conf`에서 기본 글꼴 이름마다 DejaVu 뒤에 Noto Sans CJK(KR/JP/SC/TC)를 둠 → 한국어·중국어·일본어 대체 표시
- EZlator와 공용 저장소(시험 빌드, 최종 위치는 EZlator 통합 때 정함): 시작할 때 저장소 권한(READ/WRITE_EXTERNAL_STORAGE, targetSdk 28 기존 방식, EZlator와 같음)을 물어봄. 권한이 있으면
  - [로그 저장] → `Download/.EZlator/Reports/ezsteam_report_<시각>.zip`(이 레이어 것 최근 5개 유지, EZlator `report_*`는 안 건드림). 글은 `LogScrubber`로 Steam 계정·표시 이름(loginusers.vdf), SteamID64, 토큰, 이메일을 가림(EZlator LogScrubber와 같은 규칙). 권한이 없으면 파일 선택 창
  - EZlator Steam 라이브러리 `/storage/emulated/0/EZlator/SteamLibrary`를 rootfs `/mnt/ezlator-library`로 연결, steam-run.sh가 `steam-library.py`로 클라이언트 라이브러리 목록(config/·steamapps/libraryfolders.vdf)에 추가(첫 실행 전에는 다음 시작부터). 그 라이브러리 게임의 Proton prefix는 ezproton이 rootfs 안 `steamapps/compatdata/<appid>`로 돌림
  - 공용 저장소는 flock에 ENOSYS(errno 38)를 돌려줌(실측) → `native/ezscompat/ezs_flock.c`(libezs_compat.so, steam-run.sh가 클라이언트에 LD_PRELOAD)가 ENOSYS/EOPNOTSUPP만 성공으로 바꿈
  - 내부 폴더(files/ezs, files/ezs-data)는 EZlator(imagefs, contents, steam 등)와 이름이 겹치지 않아 그대로 둠
- 뒤로 가기: 실제 장치(키보드·게임패드·마우스)에서 온 뒤로 가기는 X의 Esc로 보냄. 화면 뒤로 가기·[닫기]는 Steam 모드에서 종료를 먼저 물어봄
- 키보드 포커스: 창 관리자가 없으므로 앱(XTestClient)이 터치·키 누름 때 포인터 아래 최상위 창에 SetInputFocus + WM_TAKE_FOCUS를 보냄(Wine은 WM_TAKE_FOCUS를 받아야 창을 활성화함). 창이 바뀔 때만 보내고 앱 로그에 `X 포커스 → 창 0x…`. 새 최상위 창이 나타나면(루트 SubstructureNotify의 MapNotify, override-redirect 제외) 그 창에 바로, 0.3초·1.5초 뒤에 다시 보냄(`(새 창)`). 터치·마우스 누름 때는 같은 창이어도 항상 다시 보냄. 볼륨 키는 안드로이드 미디어 볼륨으로
- 최소 창 관리자 역할(XTestClient): 관리 창이 매핑되면 WM_STATE=Normal, 언매핑되면 Withdrawn을 둠(Wine은 이 속성을 받기 전까지 그 창의 포커스를 무시함). WM_TAKE_FOCUS·SetInputFocus에는 루트 `_EZS_TIME` 속성 변경으로 얻은 실제 서버 시각을 씀(Wine은 CurrentTime=0인 WM_TAKE_FOCUS를 비전경 창에 적용하지 않음)
- 실제 마우스: 이동(버튼 없이도)·휠(버튼 4~7)·버튼(1 왼쪽, 2 가운데, 3 오른쪽, 8/9 뒤로/앞으로)을 X로 보냄. 화면 위 안드로이드 화살표는 숨김(X 커서만 보임). [마우스 잠금]: 포인터 캡처로 상대 이동(시점 회전 게임용), 다시 터치하면 해제
- 화면 배치: 위 = 버튼 줄(화면 폭, 넘치면 옆으로 밀림), 가운데 = 로그(화면이 뜨면 숨김, [로그]로 표시), 아래 왼쪽 = 상태 띠([정보]로 숨김). [숨김]은 [≡] 모서리 버튼만 남김
- 화면 위쪽 막대 `자식 N(최대 M)`: 앱 자식 프로세스 수(안드로이드 한도: 모든 앱 합계 32), 28개 이상이면 로그에 `PROC` 목록. `여유`: 기기 여유 메모리
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
