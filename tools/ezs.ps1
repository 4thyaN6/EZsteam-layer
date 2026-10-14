# Fixed helper for this project: upload changes, then install the newest test APK.
# Run through ezs-push.bat / ezs-install.bat (double-click) in the project folder.
# 이 프로젝트 고정 도우미: 수정본 업로드, 최신 시험 APK 설치.
# 프로젝트 폴더의 ezs-push.bat / ezs-install.bat(더블클릭)로 실행함
param(
    # push = upload, install = download and install / push = 업로드, install = 받아서 설치
    [Parameter(Mandatory = $true)][ValidateSet('push', 'install')][string]$Cmd
)

$ErrorActionPreference = 'Stop'
# Project folder = parent of this tools folder / 프로젝트 폴더 = 이 tools 폴더의 상위
$Repo = Split-Path $PSScriptRoot -Parent
# adb: default SDK path, otherwise the one on PATH / adb: 기본 SDK 경로, 없으면 PATH의 adb
$Adb = 'D:\Utility\Android\SDK\platform-tools\adb.exe'
if (-not (Test-Path $Adb)) { $Adb = 'adb' }
# Fixed address of the newest main-branch APK (pre-release "apk-latest", updated by CI)
# main 최신 APK의 고정 주소(CI가 갱신하는 사전 출시 "apk-latest")
$Url = 'https://github.com/4thyaN6/EZsteam-layer/releases/download/apk-latest'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

Set-Location $Repo

if ($Cmd -eq 'push') {
    # Upload everything that changed in the project folder
    # 프로젝트 폴더에서 바뀐 것을 모두 올림
    git add .
    git diff --cached --quiet
    if ($LASTEXITCODE -eq 0) {
        Write-Host '올릴 변경이 없음 (zip을 이 폴더에 덮어썼는지 확인)'
        return
    }
    $msg = Read-Host '커밋 메시지 (그냥 Enter = update)'
    if ([string]::IsNullOrWhiteSpace($msg)) { $msg = 'update' }
    git commit -m $msg
    git push
    if ($LASTEXITCODE -ne 0) { throw 'git push 실패' }
    $sha = (git rev-parse --short=7 HEAD).Trim()
    Write-Host ''
    Write-Host "업로드 완료: $sha"
    Write-Host 'GitHub Actions가 모두 초록이 되면 ezs-install.bat 실행'
    return
}

# install: only when the published APK was built from the current commit
# install: 올라온 APK가 지금 커밋으로 빌드된 것일 때만 설치
$sha = (git rev-parse --short=7 HEAD).Trim()
$tmp = Join-Path $env:TEMP 'ezsteam-apk'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
try {
    Invoke-WebRequest "$Url/apk.version" -OutFile "$tmp\apk.version" -UseBasicParsing
} catch {
    Write-Host 'apk-latest가 아직 없음: 첫 main 빌드가 끝날 때까지 기다린 뒤 다시 실행'
    return
}
$built = (Get-Content "$tmp\apk.version" -Raw).Trim()
if ($built -ne $sha) {
    Write-Host "아직 최신 APK가 아님: 저장소 $sha / 올라온 APK $built"
    Write-Host 'Actions가 끝날 때까지 기다린 뒤 다시 실행'
    return
}
$apk = "$tmp\ezsteam-$built.apk"
Invoke-WebRequest "$Url/app-debug.apk" -OutFile $apk -UseBasicParsing
Write-Host "설치: $apk"
# adb writes failures to stderr; in Windows PowerShell 5.1 a redirected stderr line becomes an
# error record, so keep going instead of stopping on it
# adb는 실패를 stderr로 씀. Windows PowerShell 5.1은 리디렉션된 stderr 줄을 오류로 바꾸므로 멈추지 않게 함
$ErrorActionPreference = 'Continue'
$out = (& $Adb install -r $apk 2>&1 | Out-String)
Write-Host $out
if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
    # Installed app has a different signing key: only uninstall + install works (app data is lost,
    # rootfs and driver are downloaded again in the app)
    # 설치된 앱의 서명 키가 다름: 삭제 후 설치만 가능(앱 데이터가 지워지며 rootfs·드라이버는 앱에서 다시 받음)
    $ans = Read-Host '서명이 달라 덮어 설치 불가. 삭제 후 설치할까요? (y/n)'
    if ($ans -ne 'y') { return }
    & $Adb uninstall app.ezsteam
    $out = (& $Adb install $apk 2>&1 | Out-String)
    Write-Host $out
}
if ($out -notmatch 'Success') { throw 'adb install 실패 (태블릿 연결·USB 디버깅 확인)' }
Write-Host ''
Write-Host "설치 완료. 앱 첫 줄이 'app build=$built'인지 확인"
