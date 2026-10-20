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

## Downloaded by the app from this repository's Release / 앱이 이 저장소 Release에서 받는 것

| Component / 구성 요소 | License / 라이선스 | Source / 출처 |
|---|---|---|
| Debian 13 (trixie) arm64 rootfs | each package its own license (GPL, LGPL, MIT, BSD and others) | package list `rootfs.packages.txt` (Release) and `/opt/ezsteam/packages.txt` (rootfs); sources from the Debian archive (https://snapshot.debian.org) |
| Mesa Turnip Vulkan driver (glibc build) | MIT | sources and hashes in the bundle's `source.txt` and `tools/build-turnip-glibc.sh` |

## Not distributed / 배포하지 않는 것

Steam, Proton, the Steam Linux Runtime, Steamworks Shared redistributables (DirectX, Visual C++)
and games are downloaded by each device from Valve's servers through the Steam client; this
project does not host or redistribute them.
Steam, Proton, Steam Linux Runtime, Steamworks Shared 재배포 패키지(DirectX, Visual C++), 게임은 각
기기가 Steam 클라이언트로 Valve 서버에서 직접 받음. 이 프로젝트는 호스팅하거나 재배포하지 않음.

## File formats / 파일 형식

The on-screen control layout reader (`runtime/IcpLayout`) reads the input controls profile format
(.icp) used by Winlator and EZlator; only the format is shared, the code is written for this
project. The bundled default layout (`app/src/main/assets/pads/default.icp`) was made by the
project author.
화면 조작 배치 읽기(`runtime/IcpLayout`)는 Winlator·EZlator의 입력 컨트롤 프로필 형식(.icp)을 읽음. 형식만
같고 코드는 이 프로젝트용으로 작성함. 함께 넣은 기본 배치(`app/src/main/assets/pads/default.icp`)는 이
프로젝트 작성자가 만든 것임.
