# Fixed helper for this project: upload changes, then install the newest test APK.
# Run through ezs-push.bat / ezs-install.bat (double-click) in the project folder.
# 이 프로젝트 고정 도우미: 수정본 업로드, 최신 시험 APK 설치.
# 프로젝트 폴더의 ezs-push.bat / ezs-install.bat(더블클릭)로 실행함
param(
    # push = upload, install = download and install / push = 업로드, install = 받아서 설치
    # check = GPU path check on the tablet + report, report = save and pull a report (adb, no MTP)
    # check = 태블릿에서 GPU 경로 점검 + 제보, report = 제보 저장 후 가져오기(adb, MTP 안 씀)
    [Parameter(Mandatory = $true)][ValidateSet('push', 'install', 'check', 'report')][string]$Cmd
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
# Korean output of adb shell (UTF-8) shown as it is / adb 셸의 한국어 출력(UTF-8)을 그대로 보이게 함
[Console]::OutputEncoding = [Text.Encoding]::UTF8

# ---- Tablet stays on the cable: everything through adb, Windows Explorer (MTP) is not used ----
# (Explorer froze while reports were copied over MTP, 2026-10-28)
# ---- 태블릿은 케이블에 꽂아 둔 채: 모두 adb로, Windows 탐색기(MTP)는 쓰지 않음 ----
# (MTP로 제보를 복사하는 동안 탐색기가 멈췄음, 2026-10-28)
$Pkg = 'app.ezsteam'
$Reports = '/sdcard/Download/.EZlator/Reports'
$GpuLog = 'files/ezs/home/steam/ezs-logs/gpu-check.log'

function Invoke-Adb {
    # adb output as text lines, stderr included, never stopping on it
    # adb 출력을 글 줄로, stderr 포함, 그것 때문에 멈추지 않음
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $o = (& $Adb @args 2>&1 | ForEach-Object { "$_" })
    $ErrorActionPreference = $prev
    return $o
}

function Get-ReportNames {
    return @(Invoke-Adb shell "ls $Reports 2>/dev/null" | Where-Object { $_ -match '^ezsteam_report_.*\.zip$' })
}

# Ask the running session for a report, wait for the new file, pull it into .\reports
# 실행 중인 세션에 제보를 요청하고, 새 파일을 기다려 .\reports로 가져옴
function Get-Report {
    $before = Get-ReportNames
    Invoke-Adb shell am broadcast -a app.ezsteam.SAVE_REPORT | Out-Null
    Write-Host '제보 저장 요청 보냄 (세션 화면이 열려 있어야 함), 기다리는 중...'
    $new = $null
    for ($i = 0; $i -lt 90 -and -not $new; $i++) {
        Start-Sleep -Seconds 2
        $new = Get-ReportNames | Where-Object { $before -notcontains $_ } | Select-Object -Last 1
    }
    if (-not $new) {
        Write-Host '새 제보가 생기지 않음: 앱의 Steam/점검 화면이 열려 있는지 확인'
        return $null
    }
    # Size steady before pulling / 가져오기 전에 크기가 멈췄는지 확인
    $last = ''
    for ($i = 0; $i -lt 10; $i++) {
        $size = (Invoke-Adb shell "stat -c %s $Reports/$new") -join ''
        if ($size -eq $last) { break }
        $last = $size
        Start-Sleep -Seconds 1
    }
    $dir = Join-Path $Repo 'reports'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $local = Join-Path $dir $new
    Invoke-Adb pull "$Reports/$new" $local | Out-Null
    if (-not (Test-Path $local)) {
        Write-Host "가져오기 실패: $new"
        return $null
    }
    Write-Host "제보: $local ($([math]::Round((Get-Item $local).Length / 1KB)) KB)"
    # Show it in Explorer on the PC disk (not the tablet) / PC 디스크에서 탐색기로 보여 줌(태블릿 아님)
    try { Start-Process explorer.exe "/select,`"$local`"" } catch { }
    return $local
}

if ($Cmd -eq 'report') {
    Get-Report | Out-Null
    return
}

if ($Cmd -eq 'check') {
    # Fresh start of the app straight into the GPU path check (switches as set in the app)
    # 앱을 새로 시작해 바로 GPU 경로 점검으로(스위치는 앱에서 정한 그대로)
    $t0 = [int][double]::Parse((Invoke-Adb shell 'date +%s') -join '')
    Invoke-Adb shell am force-stop $Pkg | Out-Null
    $o = Invoke-Adb shell am start -n "$Pkg/.MainActivity" --es ezs.run gpucheck
    if (($o -join ' ') -match 'Error') { Write-Host ($o -join "`n"); throw '앱 시작 실패 (태블릿 연결·USB 디버깅 확인)' }
    Write-Host 'GPU 경로 점검 시작, 결과 기다리는 중 (최대 5분)...'
    $done = $false
    for ($i = 0; $i -lt 150 -and -not $done; $i++) {
        Start-Sleep -Seconds 2
        $m = (Invoke-Adb shell run-as $Pkg stat -c %Y $GpuLog) -join ''
        if ($m -match '^\d+$' -and [int]$m -ge $t0) {
            $txt = Invoke-Adb shell run-as $Pkg cat $GpuLog
            if (($txt -join "`n") -match 'CHECK 요약') { $done = $true }
        }
    }
    if (-not $done) { Write-Host '점검이 끝나지 않음 (5분). 지금 상태로 제보를 가져옴' }
    $txt | Where-Object { $_ -match '^(CHECK|INFO check|FAIL check)' -and $_ -notmatch ':   ' } | ForEach-Object { Write-Host $_ }
    Write-Host ''
    Get-Report | Out-Null
    return
}

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
# Each output item as plain text: Out-String wraps long error lines at the console width,
# which split "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in two
# 출력 항목을 일반 문자열로 바꿈: Out-String은 긴 오류 줄을 창 폭에서 끊어
# "INSTALL_FAILED_UPDATE_INCOMPATIBLE"이 둘로 나뉨
$out = (& $Adb install -r $apk 2>&1 | ForEach-Object { "$_" }) -join "`n"
Write-Host $out
if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
    # Installed app has a different signing key: only uninstall + install works (app data is lost,
    # rootfs and driver are downloaded again in the app)
    # 설치된 앱의 서명 키가 다름: 삭제 후 설치만 가능(앱 데이터가 지워지며 rootfs·드라이버는 앱에서 다시 받음)
    $ans = Read-Host '서명이 달라 덮어 설치 불가. 삭제 후 설치할까요? (y/n)'
    if ($ans -ne 'y') { return }
    & $Adb uninstall app.ezsteam
    $out = (& $Adb install $apk 2>&1 | ForEach-Object { "$_" }) -join "`n"
    Write-Host $out
}
if ($out -notmatch 'Success') { throw 'adb install 실패 (태블릿 연결·USB 디버깅 확인)' }
Write-Host ''
Write-Host "설치 완료. 앱 첫 줄이 'app build=$built'인지 확인"
