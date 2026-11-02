# Third-party notices / 외부 구성 요소 고지

The code written for this project is MIT licensed (`LICENSE`). The components below are built or
bundled by it and keep their own licenses.
이 프로젝트를 위해 작성한 코드는 MIT 라이선스임(`LICENSE`). 아래 구성 요소는 이 프로젝트가 빌드하거나
함께 배포하며, 각자의 라이선스를 따름.

## In the APK / APK 안

| Component / 구성 요소 | License / 라이선스 | Source / 출처 |
|---|---|---|
| PRoot (`libproot.so`, `libproot-loader.so`) | GPL-2.0-or-later | https://github.com/termux/proot, commit and patch hashes in `assets/proot-source.txt`; build script `tools/build-proot-android.sh`, patches `tools/patches/` |
| talloc (inside PRoot) | LGPL-3.0-or-later | single-file copy, URL and SHA-256 in `tools/build-proot-android.sh` |
| PRoot patch parts from steamclienttermux | MIT | `tools/patches/LICENSE.steamclienttermux` |
| SDL2 (`libSDL2-2.0.so.0`, built without libudev) | zlib | https://github.com/libsdl-org/SDL, tag in `tools/build-alsa-plugin.sh` |
| SDL3 headers (used to build `libezs_pad.so`) | zlib | https://github.com/libsdl-org/SDL, tag in `tools/build-alsa-plugin.sh` |
| AMD FidelityFX Super Resolution 1.0 algorithm (EASU, RCAS), ported to GLSL ES in `FramebufferView` | MIT, Copyright (c) 2021 Advanced Micro Devices, Inc. | https://github.com/GPUOpen-Effects/FidelityFX-FSR |
| LSFG frame generation layer (`/opt/ezsteam/lsfg/liblsfg-vk.so`), lsfg-vk 1.0.0 lineage | MIT | https://github.com/GameNative/lsfg-vk-android, commit in `lsfg/source.txt`; build script `tools/build-lsfg.sh` |
| pe-parse, toml11, volk (inside the LSFG layer) | MIT | submodules of the above, commits in `lsfg/source.txt` |
| dxbc (inside the LSFG layer, from DXVK) | zlib | submodule of the above (commit `78ab59a8`); its own repository is gone, so the build takes the same commit's files from https://github.com/AlkaidLab/lsfg-vk-android-legacy (commit in `lsfg/source.txt`) / 원래 저장소가 없어져 같은 커밋의 파일을 위 사본에서 가져옴 |
| Mesa "mesax" bundle (`/opt/ezsteam/mesax`): Zink (`libgallium-*.so`), `libGLX_mesa.so.0`, `libEGL_mesa.so.0`, `libgbm.so.1`, `gbm/dri_gbm.so`, Turnip `libvulkan_freedreno.so` | MIT (Mesa) | https://archive.mesa3d.org, version and SHA-256 in `mesax/source.txt` and `tools/build-turnip-glibc.sh`; build script `tools/build-mesax.sh`; this project's patches `tools/patches/mesa-0*.py` (MIT) |
| Wayland protocol code inside `ezs-wl` (xdg-shell, linux-dmabuf-v1), generated at build time | MIT (wayland-protocols, wayland-scanner output) | Debian `wayland-protocols`, `libwayland-bin` in `tools/build-alsa-plugin.sh` |
| Vulkan headers (build time only: LSFG layer, present layer and probe) | Apache-2.0 OR MIT (Khronos Vulkan-Headers) | https://github.com/KhronosGroup/Vulkan-Headers, tag in `tools/build-lsfg.sh`; Debian `libvulkan-dev` in `tools/build-alsa-plugin.sh` |
| ALSA ioplug interface (`libasound_module_pcm_ezs.so` links to the rootfs' alsa-lib at run time; alsa-lib is not bundled) | LGPL-2.1-or-later (alsa-lib) | Debian `libasound2-dev` in `tools/build-alsa-plugin.sh` |

## Downloaded by the app from this repository's Release / 앱이 이 저장소 Release에서 받는 것

| Component / 구성 요소 | License / 라이선스 | Source / 출처 |
|---|---|---|
| Debian 13 (trixie) arm64 rootfs | each package its own license (GPL, LGPL, MIT, BSD and others) | package list `rootfs.packages.txt` (Release) and `/opt/ezsteam/packages.txt` (rootfs); sources from the Debian archive (https://snapshot.debian.org) |
| Mesa Turnip Vulkan driver (glibc build) | MIT | sources and hashes in the bundle's `source.txt` and `tools/build-turnip-glibc.sh` |

## Installed by the app on the device from the Debian archive / 앱이 기기에서 Debian 저장소로 설치하는 것

Not shipped in the APK or the Release: apt in the rootfs downloads them from Debian's mirrors when
they are missing. Each keeps its own license (copyright file in `/usr/share/doc/<package>/`).
APK·Release에 넣지 않음: 없을 때 rootfs 안 apt가 Debian 미러에서 받음. 각자의 라이선스를 따름(`/usr/share/doc/<패키지>/`의
copyright 파일).

| Packages / 패키지 | Script / 스크립트 | Purpose / 용도 |
|---|---|---|
| Libraries the Steam client and its web helper open (e.g. `libgtk2.0-0t64`, `libnss3`, `libpulse0`, `libpipewire-0.3-0t64`, `libsdl3-0`), plus `lsof`, `curl`, `unzip` and others | `steam-install.sh` (list `PKGS`) | Steam client start / Steam 클라이언트 시작 |
| `xwayland`, `xvfb`, `x11-apps`, `x11-xkb-utils`, `xkb-data`, `fonts-nanum`, `locales` | `tools-install.sh` | screen server, hidden screen for the Visual C++/DirectX installers, screenshot tool, keyboard map, Korean X font, UTF-8 locales / 화면 서버, 설치 프로그램용 숨은 화면, 캡처 도구, 키보드 배치, 한글 X 글꼴, UTF-8 로캘 |
| `dbus-daemon`, `python3-gi`, `gir1.2-glib-2.0`, `tzdata` | `steam-deps.sh` | session system bus and NetworkManager stand-in (`ezs-agent.py`), time zone files / 세션 시스템 버스와 NetworkManager 대역, 시간대 파일 |
| Libraries the Steam client asks for (e.g. `libva2`, `libvdpau1`, `libavcodec61`), glvnd and libdrm for the GPU display path | `steam-deps.sh` | found by soname at each start / 시작마다 soname으로 찾음 |

## Not distributed / 배포하지 않는 것

Steam, Proton, the Steam Linux Runtime, Steamworks Shared redistributables (DirectX, Visual C++),
Lossless Scaling (its `Lossless.dll`, used by the LSFG layer only when the user owns and installs it)
and games are downloaded by each device from Valve's servers through the Steam client; this
project does not host or redistribute them.
Steam, Proton, Steam Linux Runtime, Steamworks Shared 재배포 패키지(DirectX, Visual C++), Lossless Scaling(LSFG 레이어는
사용자가 소유·설치한 경우에만 그 `Lossless.dll`을 씀), 게임은 각
기기가 Steam 클라이언트로 Valve 서버에서 직접 받음. 이 프로젝트는 호스팅하거나 재배포하지 않음.

## File formats and protocols / 파일 형식과 프로토콜

The on-screen control layout reader (`runtime/IcpLayout`) reads the input controls profile format
(.icp) used by Winlator and EZlator; only the format is shared, the code is written for this
project. The bundled default layout (`app/src/main/assets/pads/default.icp`) was made by the
project author.
화면 조작 배치 읽기(`runtime/IcpLayout`)는 Winlator·EZlator의 입력 컨트롤 프로필 형식(.icp)을 읽음. 형식만
같고 코드는 이 프로젝트용으로 작성함. 함께 넣은 기본 배치(`app/src/main/assets/pads/default.icp`)는 이
프로젝트 작성자가 만든 것임.

The PulseAudio server in the app (`runtime/PulseServer`) speaks the PulseAudio native protocol so
that the Steam web helper's libpulse can connect; it was written for this project from the
protocol's facts (frame layout, tagstruct, command numbers), with PipeWire's MIT pulse server and
libpulse read only to confirm those facts. No PulseAudio or PipeWire code is included. The client
side, libpulse, is Debian's `libpulse0` installed on the device (table above).
앱 안 PulseAudio 서버(`runtime/PulseServer`)는 Steam 웹 도우미의 libpulse가 연결하도록 PulseAudio 네이티브 프로토콜로
통신함. 프로토콜 사실(프레임 배치, tagstruct, 명령 번호)로 이 프로젝트용으로 작성했고 PipeWire의 MIT pulse 서버와
libpulse는 그 사실을 확인하는 데만 봄. PulseAudio·PipeWire 코드는 들어 있지 않음. 클라이언트 쪽 libpulse는 기기에
설치되는 Debian `libpulse0`임(위 표).

The NetworkManager stand-in (`ezs-agent.py`) answers the D-Bus interface names and properties that
libnm reads; written for this project, no NetworkManager code is included.
NetworkManager 대역(`ezs-agent.py`)은 libnm이 읽는 D-Bus 인터페이스 이름과 속성에 답함. 이 프로젝트용으로 작성했고
NetworkManager 코드는 들어 있지 않음.
