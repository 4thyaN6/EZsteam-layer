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
- Steam 웹 도우미(CEF) 프로세스: `--no-zygote --in-process-gpu --renderer-process-limit=1 --disable-site-isolation-trials`로 도우미 4개(browser, renderer, network, storage). Steam이 자체 `--disable-features`/`--enable-features`를 감쌈 스크립트 인자가 아니라 내부에서 넘기므로 기능 목록 합치기는 적용되지 않음(이전 빌드가 넣은 합치기 줄은 ezsteam-prepare.sh가 지움). Steam 옵션 `-cef-single-process`도 도우미 수에 변화 없었음(스위치 제거). 구성이 바뀔 때마다 앱 로그에 `CEF 도우미 N개: browser, renderer, utility(<종류>)…`. 게임 첫 실행에서 도우미 7개로 자식 31개가 되어 exit 137이 있었음
- 관리자 모드 [Steam 화면 GPU](기본 ANGLE Vulkan, 기기 확인 후 기본값으로 바꿈): 끔 → Chromium 선택 → ANGLE Vulkan. 끔이 아니면 steam-run.sh가 `-cef-disable-gpu`를 빼고(`EZS_STEAM_GPU=1`) 웹 도우미에 플래그를 더함. 웹 도우미 출력(Steam/logs/steamwebhelper.log)은 제보에 `steamwebhelper-output.log`로 들어감(IP 가림)
- 관리자 모드 [IO 통계](기본 끔): `EZS_IOSTAT=1`, `PROOT_SYSSTAT=60`. 1분마다 `ezs-io:` 요약, `ezs-io: other 쓰기 상위:`(쓰기 호출이 많은 경로 5개), `PROOT_STAT`(PRoot가 멈춰 처리한 시스템 호출 수와 프로그램·호출별 상위 12개, tools/patches/proot-03), `PROOT_STAT 경로`(프로그램·호출·경로별 상위 10개, 숫자는 `#`, 짧게 사는 설치 프로세스도 집계), `ezs-io: 이름 조회 N회, 경로 M개, 상위:`(클라이언트가 이름으로 가장 많이 찾는 경로 8개, 숫자는 `#`로 묶음, 1024칸 표), `ezs-io: getifaddrs N회`, `ezs-io: socket 계열/프로토콜 N회`
- PRoot ioctl 필터(tools/patches/proot-04): 대기 중 Steam이 초당 약 1700번 ioctl을 불렀고 각각 PRoot를 진입·종료 두 번 멈췄음(PROOT_STAT 측정). PRoot가 고쳐 쓰는 요청(SIOCGIFINDEX, FICLONE, 터미널 TC*2)만 추적하고 나머지는 멈춤 없이 실행함. `PROOT_FULL_IOCTL=1`이면 예전처럼 전부 추적
- PRoot 소켓 필터(tools/patches/proot-05): 대기 중 웹 도우미·Steam·Xvfb의 recvmsg가 초당 약 1000번 PRoot를 멈췄음(PROOT_STAT 측정). PRoot가 처리하는 netlink 소켓을 디스크립터 900~963으로 옮기고, recvmsg/recvfrom은 그 구간만, sendto는 그 구간이나 목적지 주소가 있을 때만 추적. targetSdk와 상관없이 가짜 netlink는 그대로 동작. `PROOT_FULL_RECV=1`이면 예전처럼 전부 추적
- Steam 화면 GPU ANGLE Vulkan: 웹 도우미가 libEGL/libGLESv2(ANGLE), libvulkan, libvulkan_freedreno(Turnip)를 불러온 것 확인(측정)
- 같은 답 재사용(libezs_compat.so, 기본 켬, `EZS_LOOKUP_CACHE=0`이면 끔): 대기 중 클라이언트가 초당 약 55번 장치 확인을 반복함(측정). `/run/udev/control` 없음(ENOENT)은 계속 같은 답, netlink uevent 소켓은 거부되면 같은 오류, `/dev`의 stat·statx는 1초 동안 마지막 결과를 다시 씀. Steam 화면 파일(`Steam/steamui/…`, 대기 중 약 330개를 약 2초마다 access+stat)은 성공·없음 결과를 10초 동안 다시 씀(클라이언트 갱신 때만 바뀌고 갱신하면 다시 시작함). 단, 프로세스 시작 후 120초 동안(자체 설치·갱신 구간)과 그 프로세스가 steamui 아래를 바꾼 뒤 30초 동안은 다시 쓰지 않고 기억도 버림. [IO 통계]에서 `ezs-io: 같은 답 재사용:`, `ezs-io: inotify` 줄로 확인
- 기본 prefix 미리 준비: Steam 세션 동안 앱이 15초마다(게임이 돌지 않을 때) rootfs 파일을 직접 보고, Proton이 있는데 기본 prefix가 없거나 다 받은 Steamworks Shared(appmanifest_228980 StateFlags 4)에 새 DirectX/Visual C++ 설치 스크립트가 생기면 두 번째 PRoot에서 `ezs-baseprefix.sh`(= `ezs-baseprefix.py prepare`)를 스스로 실행함. Proton이 업데이트되면 먼저 물어봄([지금 만들기]/[나중에]). 게임 시작 때의 ezproton은 복사만 하고, 준비 중이면 끝날 때까지 기다림
- 기본 prefix(`/opt/ezsteam/ezs-baseprefix.py`, ezproton이 호출): 새 게임 prefix를 만들 때 깨끗한 Proton prefix에 Steam 공용 재배포(기기의 Steamworks Shared `_CommonRedist`에 있는 DirectX, Visual C++)만 설치한 기본 prefix(`~/.local/share/ezsteam/base-prefix`)를 한 번 만들고, 게임 prefix로 복사함(MachineGuid는 복사본마다 새로). 재배포는 각자의 installscript.vdf 단계대로 실행하고, 성공(exit 0)하면 HasRunKey 아래 단계 이름 DWORD = 1을 씀(Valve 설치 스크립트 문서의 기록 방식, 32/64비트 레지스트리 양쪽). 그래서 복사한 prefix에서는 Steam 설치 스크립트가 그 단계를 건너뜀. 게임 전용 설치 단계는 그대로 Steam이 실행. Proton 버전이 바뀌면 다시 만듦. 이미 완성된 prefix(`version` 파일 있음)는 건드리지 않음. `EZS_BASE_PREFIX=0`이면 끔. 앱 로그 `ezproton: base …` 줄과 안내 띠 "기본 환경 …"
- 새 설치 흐름 보완: Steam 설치 단계에서 클라이언트 첫 갱신 뒤 필요한 시스템 라이브러리(libva2, libvdpau1, libavcodec61, libavutil59)를 미리 설치. 필요 라이브러리 요청은 apt에 설치 후보가 있는 것만(클라이언트 자체 libav*.so.62 등은 Steam이 직접 받음). Steam 첫 실행 전에 steamapps를 만들어 Proton 받기를 첫 로그인부터 요청. 같은 세션 안의 재시작(자체 갱신 exit 42) 전마다 웹 도우미 연결·공유 라이브러리·호환 도구·Proton 요청을 다시 적용
- 기본 prefix: prefix + Visual C++(약 1분)가 끝나면 바로 사용. DirectX는 선택 사항(2026-10-21 결정): 기본은 설치 기록(HasRunKey)만 넣어 Steam이 게임 첫 시작 때 DXSETUP을 돌리지 않고 게임은 Proton 자체 DirectX 부품을 씀. 시작 화면 [DirectX 설치](굵게, 시작 줄 아래)를 누르면 `ezs-baseprefix.sh directx`가 기본 prefix의 준비용 복사본(`base-prefix.dx`)에 실제로 설치해 바꿔 넣고(약 3분 30초), 이미 있는 게임 prefix마다 그 게임이 돌지 않을 때(wineserver 잠금 확인) 설치함(진행은 버튼 옆 막대·단계·경과). 설치된 곳에는 `ezs-directx` 파일, 선택은 `~/.local/share/ezsteam/directx-wanted`로 남아 새 Proton으로 다시 만든 기본 prefix에도 다시 넣음. 게임 시작은 그 게임에 DirectX를 넣는 중이면 기다림. 기본 prefix는 `base-prefix.tmp`에서 만든 뒤 이름을 바꾸므로 Proton 파일의 경로를 고쳐 둠
- DirectX Native 실행: 설치 뒤 [Native 실행] 체크 + (?) 도움말(커서·길게 누름 = 짧은 설명, 누름 = 대화 상자). 끄면(기본) EZ Proton이 DirectX 2010 DLL(d3dx9/10/11, d3dcompiler_33~43, xinput1_1~1_3, xaudio2_0~2_7, x3daudio, xactengine3, xapofx)을 `WINEDLLOVERRIDES …=b`로 Proton 자체 것으로 고정, 켜면 `…=n,b`로 prefix 안 Microsoft 파일을 먼저 씀. 단 XInput 1.1~1.3은 두 모드 모두 Proton 자체(Microsoft xinput1_3은 Wine에 없는 Xbox 드라이버와 통신해 패드가 반응하지 않았음)(지정이 없으면 Wine이 builtin을 불러왔음. 실행 로그 `ezproton: DirectX 2010 DLL native/builtin`. 앱이 `~/.local/share/ezsteam/dx-native`를 쓰고 지움, 다음 게임 시작부터)
- 기본 prefix 준비는 보이지 않는 별도 X 화면 `:97`(Xvfb, 준비하는 동안만)에서 실행: Steam 화면에서는 Visual C++ 설치 창이 Steam 위에 뜨고 키보드 포커스를 가져갔음
- 라이브러리 목록 미리 만들기: 첫 로그인 전에 목록 파일이 없으면 `steamapps/libraryfolders.vdf`를 기본 라이브러리 + EZlator 라이브러리로 씀(클라이언트가 로그인 때 그 파일을 읽고, 없으면 자기 폴더만으로 새로 써서 공유 라이브러리가 재시작 뒤에야 보였음). 그래서 Proton 요청(steamapps 생성)을 먼저 함
- 호환성 도구 미리 정하기: 첫 로그인 전에 `config/config.vdf`가 없으면 기본 호환성 도구 = EZ Proton만 든 파일을 씀(없으면 그 세션 첫 게임이 클라이언트 기본값 Proton Experimental을 받아 Proton Experimental과 Steam Linux Runtime 4.0을 내려받았음)
- Steamworks Shared 사용 판단: StateFlags가 "설치 완료"이고 진행 중·깨진 업데이트가 없으면 씀. "업데이트 필요"만 있으면 이전 버전 파일로 씀(공유 라이브러리에서 Steam이 그 업데이트를 며칠 미뤄 기본 prefix에 재배포가 빠졌음)
- 첫 시작 전 라이브러리 검사: 패키지 목록이 없을 때 CEF·FFmpeg 이름(libcef.so, libav*, libsw*)은 클라이언트 파일 검사가 받으므로 apt에 묻지 않음(새 설치에서 약 18초 + 헷갈리는 경고)
- Steam 언어: 앱이 휴대폰 언어(BCP 47)를 `EZS_LANG`으로 넘기고 `steam-language.py`가 Steam 언어 이름(koreana, english, japanese, schinese/tchinese, brazilian, latam 등)으로 바꿔 `~/.steam/registry.vdf`의 HKCU/Software/Valve/Steam `language`에 시작마다 씀. 모르는 언어면 그대로 둠
- 제보 추가 항목: `storage-usage.txt`(앱 파일·캐시, rootfs, Steam 폴더, 게임·prefix, 기본 prefix, /usr, /var 크기), `dx-usage.txt`(라이브러리별 게임의 .exe/.dll 안에서 DirectX 2010년 6월 런타임 DLL 이름(d3dx9/10/11, d3dcompiler_33~43, xinput1_3, xaudio2_0~7, x3daudio, xact, xapofx)을 ASCII·UTF-16으로 찾고, 게임 폴더에 같은 DLL이 직접 들어 있는지 표시. Windows 8/10 기본 DLL은 세지 않음). 큰 라이브러리면 저장에 시간이 더 걸림
- 제보에 Steam 자체 `logs/console_log.txt`(게임 시작 단계 GameAction 등)와 `logs/compat_log.txt`(호환성 도구 선택)도 넣음(끝부분, 글은 가림 처리)
- 제보 `dll-check.txt`: Proton의 `files/lib/wine/*-windows`, 기본 prefix, 최근 게임 prefix 4개의 system32·syswow64에서 확인 DLL(d3dx9_43, xinput1_3, xaudio2_7, d3dcompiler_43, xinput1_4, xaudio2_9, msvcp140, vcruntime140, d3d11, dxgi)마다 PE 헤더 CPU(x86, x86-64, ARM64, ARM64EC, ARM64X)와 출처(Wine builtin, Wine 자리표시, Wine 아님 = Microsoft 등), 이어서 Proton 로그마다 그중 native/builtin으로 불러온 것
- EZ Proton: Proton을 받는 중에 Steam이 부르면 "not found" 대신 "still downloading"
- EZ Proton: Steam의 그래픽 드라이버 조회(`d3ddriverquery64.exe`, 로그인 때와 게임 시작 때 두 번)는 실행하지 않고 바로 끝냄. 실행할 때마다 prefix 0에 Wine 세션 전체가 게임과 따로 떠, 그 순간 프로세스가 안드로이드 한도를 넘어 세션이 137("Cannot fork")로 종료됐음
- 세션 시작 전 정리: 이 앱 사용자 id의 프로세스 중 앱 프로세스 아래에 없는 것(죽은 세션의 고아)을 멈춤. 세션이 137로 죽은 뒤 남은 X 서버 때문에 다음 시작이 "server already running"으로 실패했음. 앱 로그 `이전 세션에서 남은 프로세스 N개 멈춤`
- 제보 저장은 한 번에 하나만. 10분 넘은 `.zip.part`만 지움(연달아 두 번 저장하자 서로의 파일을 지웠음)
- 제보 저장 후 새 파일과 지운 옛 제보를 함께 미디어 목록에 알림(USB로 연결한 컴퓨터에 지운 제보가 남아 보이거나 새 제보가 늦게 보였음). 앱 로그 `미디어 목록 갱신:`
- 진행 표시: [Steam 시작]의 설치 등 설치 작업 중 시작 줄 아래 진행 패널(현재 단계 `[n/4]`, 움직이는 막대, 전체 비율, 경과 시간, 받은 양을 1초마다 갱신). Steam 화면에서는 첫 Steam 창이 뜨기 전까지 가운데 진행 카드: 시작 중 → 업데이트 받는 중(`Downloading update (X of Y KB)` 기준 %) → 설치 중 → 확인 중 → 적용·다시 시작(exit 42) → 필요한 라이브러리 설치. 첫 Steam 창(X 새 창 포커스)이 뜨면 숨김. 갱신 진행 줄은 단계가 바뀔 때만 앱 로그에 남김. 첫 로그인 뒤 Proton 받기는 위쪽 안내 띠 "게임 실행 환경 준비 1/3: Proton 받는 중 NN%"(appmanifest_4628740 BytesDownloaded/BytesToDownload), 이어지는 기본 prefix(prefix + Visual C++)는 "2/3", 백그라운드 DirectX는 "3/3"(게임 실행 중에는 띠를 숨김). 안내 띠 왼쪽에 작은 스피너
- 게임 실행 환경 안내 띠는 게임 시작이 기본 prefix를 기다릴 때만 "게임 준비 중: … / 다음: 게임 시작"으로 보임. Proton 받기와 미리 하는 기본 prefix 준비는 조용히 돎(EZ Proton이 잠금을 기다리기 시작할 때 `ezproton: base waiting: game start …` 줄을 냄)
- 제보는 `.zip.part`로 쓰고 다 쓰면 `.zip`으로 이름을 바꿈(쓰는 중에 복사한 제보가 잘려 있었음). 저장 시작 때 "로그 저장 중…" 알림
- 첫 실행 준비 안내: 게임 시작 때 설치 스크립트(iscriptevaluator), Proton prefix 만들기, 재배포 패키지 설치(DirectX, Visual C++, .NET, OpenAL, PhysX, XNA, Unreal 필수 구성 요소, msiexec)가 도는 동안 화면 위쪽에 단계·경과 시간·하위 작업 수를 표시. 앱 로그 `첫 실행 준비:` 줄에 시작·단계 변경·끝(총 시간) 기록. 측정: 새 게임 첫 실행에서 prefix 약 30초, DirectX 약 3분(하위 프로세스 101개)
- 커널 추적 표식: Steam 클라이언트가 `/sys/kernel/tracing/trace_marker`에 초당 약 1000번 쓰고 모두 실패했음(라이브러리 화면, 측정). libezs_compat.so가 그 열기를 데스크톱과 같은 EACCES로 거부함(`EZS_TRACE_MARKER=1`이면 그대로 둠)
- Steam 화면 GPU 확인: 그리는 웹 도우미 프로세스의 메모리 맵에서 그래픽 라이브러리(libEGL, libGLESv2, libvulkan, freedreno, SwiftShader)를 30초마다 보고 바뀌면 앱 로그 `CEF 그래픽 라이브러리:`
- 화면 확대 방식: 선형 → 바이큐빅 → 바이큐빅+선명 → FSR 1(EASU + RCAS, AMD FidelityFX FSR 1.0 알고리즘을 GLSL ES로 직접 옮김, MIT). FSR 셰이더나 화면 밖 버퍼가 실패하면 바이큐빅+선명으로 그리고 앱 로그에 남김
- FSR 1 선명도: [확대] 길게 누름 → 0(가장 선명) / 0.2(FSR 기본) / 0.5 / 1.0 / 2.0(약하게) / 선명화 끔(EASU 확대만). RCAS 단 단위라 숫자가 작을수록 선명함. 고르면 FSR 1로 바뀌고 다음에도 유지
- 소프트 키보드 한글: 입력 칸 종류를 추천 없는 텍스트(TYPE_CLASS_TEXT | NO_SUGGESTIONS)로 알려 한글 자판이 뜨게 함(TYPE_NULL에서는 영문만 입력됐음). 조합 중인 글자는 앱이 들고 있다가 확정·조합 끝에 보냄. 키 배치에 없는 글자(한글 음절 등)는 `XTestClient.typeChar`가 예비 X 키코드 240~251에 유니코드 keysym(0x01000000 + 코드 포인트)을 ChangeKeyboardMapping으로 주고 눌렀다 뗌(차례로 쓰고 되돌리지 않음)
- 소프트 키보드: 키보드가 뜨면 마지막으로 누른 곳(보통 입력란)이 키보드 위에 오도록 게임 화면을 필요한 만큼만 올리고, 숨으면 되돌림(안드로이드 11 이상은 창 인셋의 키보드 높이, 이전은 보이는 창 영역)
- 시작 화면: [시작 화면: 빅픽처/일반](빅픽처는 Steam 화면 GPU를 켜 두어도 소프트웨어로 그림: GPU로는 검은 화면, 끄면 나왔음. steam-run.sh가 그 시작에 `-cef-disable-gpu`를 붙이고 GPU용 Chromium 플래그를 뺌)(기본 일반. 빅픽처는 기기에서 검은 화면만 나와 원인 확인 전까지 끔) → steam-run.sh가 `-gamepadui`를 붙임. 한 번이라도 로그인한 기록(`logs/connection_log.txt`의 로그인 응답 `'OK'`)이 있을 때만이며, 그 전(첫 로그인)에는 일반 창. 계정 파일은 읽지 않음. 앱 로그 `INFO steam : 빅픽처는 첫 로그인 뒤부터`
- 새 최상위 창: 크기·위치·깊이를 앱 로그 `X 새 창 0x…`에 남기고, `_NET_WM_STATE_FULLSCREEN`을 요청한 창은 창 관리자처럼 화면 전체 크기로 맞춤(빅픽처가 소리만 나고 화면이 비었던 원인 확인용)
- 설치·준비 작업 중 30초마다 앱 로그에 `… <작업> 진행 중 N분: 받은 양, 최근 30초 Mbps, 자식 수, 가장 바쁜 프로세스(CPU%)` 한 줄(오래 걸리는 단계와 멈춘 단계 구분용)
- 시작 화면: [Steam 시작](빠진 것이 있으면 rootfs → 드라이버 → 시험 도구(Xvfb) → Steam 설치를 빠진 것만 순서대로 진행 패널과 함께 설치한 뒤 바로 Steam, 다 있으면 바로 Steam. 실패한 단계에서 멈춤), [화면 1280x800/1920x1200], [바로 실행: 켬/끔](앱을 새로 열 때 바로 Steam, 기본 켬, 설치가 필요하면 자동으로 하지 않음), [관리자 모드](설치·시험 버튼 표시/숨김, [전체 준비만] = Steam을 열지 않고 설치만)
- 화면 터치 패드([패드], 길게 누르면 크기·간격·진하기, [배치 파일…]): 입력 컨트롤 프로필 파일(.icp, EZlator·Winlator가 내보내는 JSON 형식)대로 배치(`runtime/IcpLayout` 읽기, `TouchPadView` 그리기·입력, 둘 다 자체 작성, 파일 형식만 같음). 위치는 화면 너비·높이 비율, 크기는 화면 너비 1/100 칸 단위(편집기와 같은 비율). 요소: 버튼(원·사각·둥근 사각·정사각, 토글), 십자키, 스틱, 트랙패드(범위 버튼은 아직 없음). 바인딩: 게임패드 버튼·트리거·스틱 방향 → 가상 패드(`PadFile` 터치 입력원, 실제 컨트롤러와 합침), 키보드 → X 키코드, 마우스 버튼·휠·이동 → XTest. 기본 배치는 APK `assets/pads/default.icp`, [배치 파일…] → [배치 파일(.icp) 불러오기]로 고른 파일은 확인 후 `files/ezs-data/pads/current.icp`에 보관, [기본 배치로 되돌리기]. 크기는 파일의 요소 배율에 곱하는 배율 50~180%, 간격은 왼쪽·오른쪽 조작부를 각 쪽 중심에서 벌리는 배율 80~160%(화면 밖으로 나가지 않음). 십자키는 실제 패드처럼 이어진 십자 하나로 그리고 누른 방향만 밝게, 화살표 표시. 기본 배치(2000x1250 화면 기준): LT·LB 그대로, 십자키는 바깥·아래로 10px, L3는 바깥으로 10px, 왼쪽 스틱 중심은 십자키 오른쪽 팔 아래·L3와 같은 높이, BACK은 왼쪽 70px·아래 5px, BACK·START 정중앙에 HOME(가이드 버튼, Steam 홈 + X = 키보드), L3·R3는 가로폭만 절반(이 앱만의 선택 항목 `widthScale` 0.5, 다른 앱은 무시), 오른쪽은 좌우 대칭. 바인딩 `GAMEPAD_BUTTON_MODE`·`GAMEPAD_BUTTON_GUIDE` = 가이드. [십자키·왼쪽 스틱 자리 바꾸기]로 두 조작부 자리를 바꿀 수 있음(십자키는 크기 차이만큼 안쪽으로, L3는 스틱과의 거리를 유지하며 함께 이동). 요소 밖에서 시작한 터치는 아래 화면(포인터·클릭)으로 넘어감
- 글꼴: 앱이 휴대폰 `/system/fonts`를 rootfs `/usr/local/share/fonts/android`에 읽기 전용으로 연결(복사·배포 없음, DroidDeck과 같은 방식). steam-run.sh가 `~/.config/fontconfig/fonts.conf`에서 기본 글꼴 이름마다 DejaVu 뒤에 Noto Sans CJK(KR/JP/SC/TC)를 둠 → 한국어·중국어·일본어 대체 표시
- EZlator와 공용 저장소(시험 빌드, 최종 위치는 EZlator 통합 때 정함): 시작할 때 저장소 권한(READ/WRITE_EXTERNAL_STORAGE, targetSdk 28 기존 방식, EZlator와 같음)을 물어봄. 권한이 있으면
  - [로그 저장] → `Download/.EZlator/Reports/ezsteam_report_<시각>.zip`(이 레이어 것 최근 5개 유지, EZlator `report_*`는 안 건드림). 글은 `LogScrubber`로 Steam 계정·표시 이름(loginusers.vdf), SteamID64, 토큰, 이메일을 가림(EZlator LogScrubber와 같은 규칙). 권한이 없으면 파일 선택 창
  - EZlator Steam 라이브러리 `/storage/emulated/0/EZlator/SteamLibrary`를 rootfs `/mnt/ezlator-library`로 연결, steam-run.sh가 `steam-library.py`로 클라이언트 라이브러리 목록(config/·steamapps/libraryfolders.vdf)에 추가(첫 실행 전에는 다음 시작부터). 그 라이브러리 게임의 Proton prefix는 ezproton이 rootfs 안 `steamapps/compatdata/<appid>`로 돌림
  - 공용 저장소는 flock에 ENOSYS(errno 38)를 돌려줌(실측) → `native/ezscompat/ezs_flock.c`(libezs_compat.so, steam-run.sh가 클라이언트에 LD_PRELOAD)가 ENOSYS/EOPNOTSUPP만 성공으로 바꿈
  - 같은 라이브러리의 `native/ezscompat/ezs_iostat.c`: `steam` 프로세스의 파일 입출력(write/read/open/close/fsync/truncate/fallocate)을 공용 라이브러리(`shared`)와 그 밖(`other`)으로 나눠 1분마다 `ezs-io:` 한 줄(호출 수, MB, 평균·최대 ms, 100 ms 이상 호출 수). 진단 전용, 호출은 그대로 넘김. 기본은 꺼짐, `EZS_IOSTAT=1`이면 켬(느린 다운로드의 원인은 VPN이었음). 공용 라이브러리로 받는 다운로드가 몇 분 뒤 약 3 Mbps로 떨어지는 원인 확인용(rootfs로는 444 Mbps 유지, EZlator 자체 다운로더는 같은 폴더에 제 속도)
  - Steam 시작 전 `steam-compat.py`가 config.vdf의 CompatToolMapping 기본값("0")과 다른 도구를 가리키는 게임별 항목을 `ezproton`으로 바꿈(공유 라이브러리 게임이 기본값 Valve Proton → pressure-vessel `opendir(/proc/self/root)`로 실패했음, 실측). 라이브러리별 appmanifest 수도 로그에 남김
  - `steam-proton.py`: ARM64 Proton(4185400을 요구하는 toolmanifest + proton)이 없으면 `appmanifest_4628740.acf`(StateFlags 6)를 남겨 클라이언트가 Valve 서버에서 받게 함(appid는 시험 기기 설치본 기준, 설치된 기기에서는 appid를 로그로 확인). 앱은 Valve 파일을 갖지 않음
  - 내부 폴더(files/ezs, files/ezs-data)는 EZlator(imagefs, contents, steam 등)와 이름이 겹치지 않아 그대로 둠
- 뒤로 가기: 실제 장치(키보드·게임패드·마우스)에서 온 뒤로 가기는 X의 Esc로 보냄. 화면 뒤로 가기·[닫기]는 Steam 모드에서 종료를 먼저 물어봄
- 키보드 포커스: 창 관리자가 없으므로 앱(XTestClient)이 터치·키 누름 때 포인터 아래 최상위 창에 SetInputFocus + WM_TAKE_FOCUS를 보냄(Wine은 WM_TAKE_FOCUS를 받아야 창을 활성화함). 창이 바뀔 때만 보내고 앱 로그에 `X 포커스 → 창 0x…`. 새 최상위 창이 나타나면(루트 SubstructureNotify의 MapNotify, override-redirect 제외) 그 창에 바로, 0.3초·1.5초 뒤에 다시 보냄(`(새 창)`). 터치·마우스 누름 때는 같은 창이어도 항상 다시 보냄. 볼륨 키는 안드로이드 미디어 볼륨으로
- 최소 창 관리자 역할(XTestClient): 관리 창이 매핑되면 WM_STATE=Normal, 언매핑되면 Withdrawn을 둠(Wine은 이 속성을 받기 전까지 그 창의 포커스를 무시함). WM_TAKE_FOCUS·SetInputFocus에는 루트 `_EZS_TIME` 속성 변경으로 얻은 실제 서버 시각을 씀(Wine은 CurrentTime=0인 WM_TAKE_FOCUS를 비전경 창에 적용하지 않음)
- 실제 마우스: 이동(버튼 없이도)·휠(버튼 4~7)·버튼(1 왼쪽, 2 가운데, 3 오른쪽, 8/9 뒤로/앞으로)을 X로 보냄. 화면 위 안드로이드 화살표는 숨김(X 커서만 보임). [마우스 잠금]: 포인터 캡처로 상대 이동(시점 회전 게임용), 다시 터치하면 해제
- 화면 배치: 위 = 버튼 줄(화면 폭, 넘치면 옆으로 밀림), 가운데 = 로그(화면이 뜨면 숨김, [로그]로 표시), 아래 왼쪽 = 상태 띠([정보]로 숨김). [숨김]은 [≡] 모서리 버튼만 남김
- 제보에 Unity 게임 자체 로그 포함: prefix의 `AppData/LocalLow/<회사>/<게임>/Player.log` 최근 3개를 `unity-<appid>-Player.log`로
- 앱 로그 파일: 화면 세션마다 rootfs `~/ezs-logs/app-session.log`에도 씀(16MB까지), 이전 세션은 `app-session-prev.log`로 보관 → 앱을 다시 연 뒤 저장한 제보에도 이전 세션이 들어감
- 라이브러리 자동 설치: Steam 시작 전 `steam-deps.sh check`가 apt로 줄 수 있는 없는 라이브러리를 찾으면 `/tmp/ezs-deps-request`를 쓰고 `NEED deps` 출력 → 앱이 두 번째 PRoot(가짜 root)에서 `steam-deps.sh install`을 돌리고 파일을 지움(앱 로그 `DEPS`) → steam-run.sh가 최대 10분 기다린 뒤 시작. 새 설치에서 클라이언트 첫 갱신 뒤 libva.so.2, libvdpau.so.1, libavcodec.so.61, libavutil.so.59가 필요했음. 클라이언트 파일 검사(-noverifyfiles 없이 시작)는 steamui.so에 클라이언트 자체 라이브러리(libcef, libav*, libsw*)가 없을 때만, 스크립트 시작당 한 번만 함(시스템 라이브러리만 없을 때 검사가 매번 돌아 steamrt64 링크를 고치고 재시작하기를 6번 반복했음)
- 시작 시간: steamui.so 라이브러리 검사(ldd)는 steamui.so의 크기·시각이 지난번 통과 때와 같으면 건너뜀(`~/.steam/.ezs-steamui-ok`), libavcodec 위치 찾기는 클라이언트 프로그램 폴더(steamrtarm64, linuxarm64)만 봄(Steam 폴더 전체를 훑느라 시작마다 약 12초). 제보에 `Steam/logs/connection_log.txt`(IP 주소는 `<v4>`/`<v6>`로 가림) 포함
- LogScrubber는 `[U:1:n]` 계정 id와 `Using JWT n`도 가림
- 다운로드 서버 진단(`steam-compat.py`, 시작 전): config.vdf의 지역·셀 값, 지난 세션 `logs/connection_log.txt`의 IPv4/IPv6 주소 수와 셀 줄(주소는 `<ip>`로 가림), rootfs 이름 찾기 첫 주소 종류를 `INFO network` 줄로 남김
- 네트워크: Steam 화면을 열 때 기기의 현재 네트워크가 VPN이면 앱 로그에 `네트워크: VPN 켜짐` 한 줄(VPN이 켜져 있으면 Steam 로그인·다운로드 서버가 VPN 위치로 잡힘: 유럽 서버, 약 3 Mbps 실측. 끄면 서울 서버 16곳, 약 300 Mbps). 이전 빌드가 쓴 IPv4 우선 gai.conf는 지워 기본값으로 되돌림
- 세션 유지(`SessionService`): 화면이 열려 있는 동안 포그라운드 서비스(알림 "Steam 실행 중", 누르면 앱으로)와 CPU 깨우기 잠금·Wi-Fi 잠금을 잡아 다른 앱으로 가거나 화면이 꺼져도 다운로드·게임이 백그라운드 제한을 받지 않게 함(포그라운드 서비스가 없을 때 다운로드가 134~147 Mbps에서 3 Mbps로 떨어진 기록 있음, 원인 미확정). 권한은 FOREGROUND_SERVICE, WAKE_LOCK(일반 권한). 앱 로그: `APP 화면 떠남/돌아옴`, 1분마다 `PERF`(CPU, 이 앱 수신 Mbps, 화면 앞/뒤, CPU 최고 클럭, 배터리 온도, 여유 메모리). 제보 device.txt에 `free:`(앱 저장소, 공용 저장소 여유 공간)
- 화면 위쪽 막대 `자식 N(최대 M)`: 앱 자식 프로세스 수(안드로이드 한도: 모든 앱 합계 32), 28개 이상이면 로그에 `PROC` 목록. `여유`: 기기 여유 메모리
- [환경 시험]의 `pthread`, `sys-*` 항목이 FAIL `SIGSYS (seccomp)`이면 PRoot가 해당 호출을 처리하지 못한 것 → rootfs glibc 패치(termux-pacman 방식) 검토
- (선택) 앱 없이: `tools/device-smoke.sh` 머리 주석 참고
- 호스트: `make -C native/ezglibc test` (보관 중인 preload 라이브러리)

## 라이선스
- 이 저장소에서 작성한 코드: MIT(`LICENSE`), 누구나 쓸 수 있음. 함께 빌드·배포하는 외부 구성 요소는 각자의 라이선스를 따르며 `THIRD_PARTY_NOTICES.md`에 정리
- SDL2(직접 빌드)·SDL3 헤더: zlib, FSR 1 알고리즘: MIT(AMD)
- PRoot(termux/proot): GPL-2.0-or-later, 사용 커밋·패치는 APK 안 `assets/proot-source.txt`와 `tools/build-proot-android.sh`에 기록
- PRoot 패치 일부: steamclienttermux(MIT), 원문 `tools/patches/LICENSE.steamclienttermux`
- talloc: LGPL-3.0-or-later(Winlator-Cmod의 단일 파일본, 출처·해시는 `tools/build-proot-android.sh`)
- Mesa(Turnip): MIT, 사용 소스·해시는 묶음 안 `source.txt`와 `tools/build-turnip-glibc.sh`
- rootfs의 Debian 패키지 목록: Release의 `rootfs.packages.txt`, rootfs 안 `/opt/ezsteam/packages.txt`
- Steam 자체 갱신 창 한글: 창이 X 코어 글꼴 `-misc-fixed-medium-r-normal--*-120-*-*-c-*-iso10646-1`에 `XDrawString16`으로 그림(클라이언트 실행 파일 안 이름, 앱 로그 `INFO font`). display.sh가 X 글꼴 경로 맨 앞에 `/tmp/ezs-xfonts`(별칭 → 나눔고딕)를 둠. 휴대폰 Noto Sans CJK(CFF)는 X 서버가 읽지 못해 TrueType인 데비안 `fonts-nanum`(SIL OFL)을 [시험 도구 설치]가 기기에서 받음(앱에 포함하지 않음, 설치 약 33MB). 글꼴 경로로 시작이 실패하면 기본 글꼴로 다시 시작
- 게임 실행 가림막: Steam이 게임을 시작하면(EZ Proton의 설치 스크립트 실행 또는 `waitforexitandrun` 줄) 화면 전체를 "게임 실행 준비 중(: 단계) · 경과"로 가리고, 게임 시작 뒤 폭 320px 이상인 첫 Wine 창(깊이 24, Steam 창은 깊이 32)이 뜨면 닫음. 두 번의 Proton 실행 사이와 마지막 준비 뒤에 Steam 화면이 몇 초 돌아와 멈춘 것처럼 보이던 것을 가림. 터치는 받기만 하고(닫히지 않음) 옆으로 밀면 밀려나며 닫히고, 3분 뒤 저절로 닫힘. 앱 로그 `게임 실행 가림막 닫힘: <이유> (<시간>)`
- Steam 입력과 가상 패드: 게임에 Steam 입력이 켜지면 Steam이 `SDL_GAMECONTROLLER_IGNORE_DEVICES`에 넘겨받는 컨트롤러를 넣고 자기 가상 패드를 주는데, 앱은 `/dev/uinput`을 쓸 수 없어 그 패드가 생기지 않음. 목록에 우리 가상 패드(0x045e/0x028e)가 있으면 게임에 패드가 안 보였으므로(실측) EZ Proton이 그 번호만 빼고 넘김. 앱 로그 `ezproton: pad ignore list: removed 0x045e/0x028e`
- 앞 게임이 남아 있을 때: 다른 게임이 시작되는데 앞 게임 프로세스가 남아 있으면 기본은 알림, 메인 화면 [앞 게임 남으면: 알림/자동 종료]로 앞 게임을 먼저 끝낼 수 있음(두 게임이 안드로이드 자식 프로세스 한도 32개를 넘어 세션 전체가 종료됐음)
- 위쪽 막대 CPU: `게임`은 명령줄의 윈도우 실행 파일 이름으로 찾음(커널 이름은 15자에서 잘려 긴 이름의 게임이 `-`로 나왔음), `Steam 화면`은 웹 도우미 프로세스 합계
- 새 창 포커스: 창이 뜬 뒤 0.1·1·3·8·15·30초에 다시 줌(그사이 포커스가 옮겨 가면 건너뜀)
- 프레임 제한: DX9~11은 `DXVK_CONFIG`의 60, DX12는 EZ Proton이 `VKD3D_FRAME_RATE=60`(vkd3d-proton 2.14+, `EZS_FPS_LIMIT`로 바꿈, 0 = 없음)
- 화면 크기: [화면 …] 버튼으로 1280x800 / 1920x1200. 1024x640·960x600은 시험 뒤 뺌(가로 3040화소 화면에서 3배 넘게 확대되어 FSR 1로도 글씨가 번졌음)
- 동기화 진단: steam-run.sh가 Proton 빌드마다 한 번 `/dev/ntsync` 유무, futex_waitv(449) 허용 여부, wineserver·proton 스크립트의 동기화 이름을 `INFO sync` 줄로 남김(기기 결과: `/dev/ntsync` 없음, futex_waitv `Function not implemented` → fsync·ntsync 모두 쓸 수 없어 server-side synchronization)
- FSR 출력 크기: [확대] 길게 누르기 → 선명도 항목 뒤 [FSR 출력: 화면 크기까지 / 1920x1200까지 + 선형 확대 / 1920x1080까지 + 선형 확대]. 중간 크기를 고르면 EASU·RCAS를 그 크기로 하고 마지막에 선형으로 화면까지 키움(FSR 1 권장 배율 2배 이하로 비교용)
- Steam HID 찾기 시험 스위치(관리자, 기본 끔): 켜면 Steam 클라이언트에만 `SDL_JOYSTICK_HIDAPI=0 SDL_HIDAPI_UDEV=0`(게임에는 넘기지 않음). 빅픽처에서 `Using udev for HIDAPI joystick device discovery`가 분당 12,000~21,000번(일반 창 약 1,700번), PRoot 약 70%(일반 창 10~20%)였음
