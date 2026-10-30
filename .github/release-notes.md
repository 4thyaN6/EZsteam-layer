> **테스트 버전 (test build)** — 개발 중 시험용 빌드임. 동작이 바뀌거나 깨질 수 있고, 다음 버전과 설정·데이터 호환을 보장하지 않음.
> This is a test build for development. Behaviour may change or break, and settings or data may not carry over to the next version.

## 이 릴리스의 파일 / Files

| 파일 | 내용 |
|---|---|
| `app-debug.apk` | 앱(디버그 서명) / the app (debug-signed) |
| `rootfs.tar.xz` (+ `.sha256`, `.version`, `.packages.txt`) | Debian trixie arm64 rootfs, 앱이 처음 실행할 때 받음 / downloaded by the app on first start |
| `turnip.tar.xz` (+ `.sha256`, `.version`) | Mesa Turnip(Vulkan) glibc 빌드, 앱의 [드라이버 설치]가 받음 / downloaded by the app's driver install |

## 이번 버전의 주요 변경 / Main change

- Turnip 드라이버 묶음 갱신: KGSL 타임스탬프 보정 패치(`tools/patches/mesa-02-kgsl-timestamp.py`) 적용
  Turnip driver package updated with the KGSL timestamp patch
- 그 밖의 변경은 커밋 기록 참고 / other changes: see the commit history

## 알림 / Notes

- 이 릴리스에는 Valve·Microsoft 파일이 들어 있지 않음. Steam 클라이언트와 Proton은 각 기기가 공식 서버에서 직접 받음
  No Valve or Microsoft files are included. Each device downloads the Steam client and Proton from the official servers itself.
- 루트·개발자 옵션 없이 동작하는 것을 목표로 함 / Designed to run without root or developer options.
- 이 저장소의 자체 코드는 MIT. 포함된 외부 구성 요소의 출처·라이선스는 `tools/patches/README.md`와 `rootfs.packages.txt` 참고
  Own code is MIT. For third-party parts see `tools/patches/README.md` and `rootfs.packages.txt`.
