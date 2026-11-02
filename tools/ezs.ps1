# Fixed helper for this project: upload changes, then install the newest test APK.
# Run through ezs-push.bat / ezs-install.bat (double-click) in the project folder.
# 이 프로젝트 고정 도우미: 수정본 업로드, 최신 시험 APK 설치.
# 프로젝트 폴더의 ezs-push.bat / ezs-install.bat(더블클릭)로 실행함
param(
    # push = upload, install = download and install / push = 업로드, install = 받아서 설치
    # check = GPU path check on the tablet + report, report = save and pull a report (adb, no MTP),
    # steam = fresh Steam start (switches as set in the app), wait, then report
    # check = 태블릿에서 GPU 경로 점검 + 제보, report = 제보 저장 후 가져오기(adb, MTP 안 씀),
    # steam = Steam 새로 시작(스위치는 앱에서 정한 그대로), 기다린 뒤 제보
    # compare = Steam UI GPU modes on Xwayland, same measured spans each, tablet untouched
    # compare = Xwayland에서 Steam 화면 GPU 방식들을 같은 측정 구간으로 비교, 태블릿 손대지 않음
    # release = tag the pushed commit with "v" + versionName of app/build.gradle (test release:
    # rootfs, driver and APK go to GitHub Releases, where the app downloads them)
    # release = 올린 커밋에 "v" + app/build.gradle의 versionName 태그를 붙임(테스트 릴리스:
    # rootfs, 드라이버, APK가 GitHub Releases에 올라가고 앱은 거기서 받음)
    [Parameter(Mandatory = $true)][ValidateSet('push', 'install', 'check', 'report', 'steam', 'compare', 'release')][string]$Cmd,
    # steam: seconds to wait after the start before the report / steam: 시작 뒤 제보까지 기다리는 초
    [int]$Wait = 90,
    # Display path for steam: '' = as set in the app, xwayland = Zink GL + GPU device (set through
    # the app's switch files, kept afterwards). The screen server is always Xwayland (Xvfb removed)
    # steam의 화면 경로: '' = 앱에서 정한 그대로, xwayland = Zink GL + GPU 장치(앱의 스위치 파일로 정하고, 끝난 뒤에도
    # 그대로 둠). 화면 서버는 항상 Xwayland임(Xvfb 제거)
    [ValidateSet('', 'xwayland')][string]$Path = ''
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
    # No Explorer window: the path is printed above (windows piled up per test)
    # 탐색기 창 안 띄움: 경로는 위에 찍음(시험마다 창이 쌓였음)
    return $local
}

if ($Cmd -eq 'report') {
    Get-Report | Out-Null
    return
}

# The app's test switch files (MainActivity [시험]), written with run-as (debug APK)
# 앱의 시험 스위치 파일(MainActivity [시험]), run-as로 씀(디버그 APK)
$SwDir = 'files/ezs/home/steam/.local/share/ezsteam'
function Get-Switches {
    $have = @(Invoke-Adb shell run-as $Pkg ls $SwDir)
    return @('zink-gl', 'gpu-node') | Where-Object { $have -contains $_ }
}
function Set-Switches([string[]]$On) {
    Invoke-Adb shell run-as $Pkg mkdir -p $SwDir | Out-Null
    foreach ($n in @('zink-gl', 'gpu-node')) {
        if ($On -contains $n) { Invoke-Adb shell run-as $Pkg touch "$SwDir/$n" | Out-Null }
        else { Invoke-Adb shell run-as $Pkg rm -f "$SwDir/$n" | Out-Null }
    }
    Write-Host "스위치: $(if ($On) { $On -join ', ' } else { '모두 끔' })"
}
# The former "xwayland" switch file is ignored by the app now (Xwayland always) / 예전 "xwayland" 스위치 파일은 이제 앱이 무시함(항상 Xwayland)
$PathSwitches = @{ 'xwayland' = @('zink-gl', 'gpu-node') }

# One GPU path check run; returns its log lines / GPU 경로 점검 한 번, 그 로그 줄을 돌려줌
function Invoke-Check {
    # The previous result moves aside first, so only the new run's log can carry "CHECK 요약"
    # 이전 결과를 먼저 옮겨 둠: "CHECK 요약"은 새 실행의 로그에만 있게 됨
    Invoke-Adb shell run-as $Pkg mv -f $GpuLog ($GpuLog -replace 'gpu-check\.log$', 'gpu-check-prev.log') | Out-Null
    Invoke-Adb shell am force-stop $Pkg | Out-Null
    $o = Invoke-Adb shell am start -n "$Pkg/.MainActivity" --es ezs.run gpucheck
    if (($o -join ' ') -match 'Error') { Write-Host ($o -join "`n"); throw '앱 시작 실패 (태블릿 연결·USB 디버깅 확인)' }
    Write-Host 'GPU 경로 점검 시작, 결과 기다리는 중 (최대 5분)...'
    $txt = @()
    for ($i = 0; $i -lt 150; $i++) {
        Start-Sleep -Seconds 2
        $txt = @(Invoke-Adb shell run-as $Pkg cat $GpuLog)
        if (($txt -join "`n") -match 'CHECK 요약') { break }
    }
    if (($txt -join "`n") -notmatch 'CHECK 요약') { Write-Host '점검이 끝나지 않음 (5분)' }
    $txt | Where-Object { $_ -match '^(CHECK|INFO check|FAIL check|WARN)' -and $_ -notmatch ':   ' } | ForEach-Object { Write-Host $_ }
    Write-Host ''
}

if ($Cmd -eq 'steam') {
    if ($Path) { Set-Switches $PathSwitches[$Path] }
    # Fresh Steam start, the tablet untouched; the report after $Wait seconds (Big Picture failures
    # showed within about 15 s of the start, 2026-10-28)
    # Steam 새로 시작, 태블릿은 손대지 않음. $Wait초 뒤 제보(빅픽처 실패는 시작 뒤 약 15초 안에 나왔음, 2026-10-28)
    Invoke-Adb shell am force-stop $Pkg | Out-Null
    $o = Invoke-Adb shell am start -n "$Pkg/.MainActivity" --es ezs.run steam
    if (($o -join ' ') -match 'Error') { Write-Host ($o -join "`n"); throw '앱 시작 실패 (태블릿 연결·USB 디버깅 확인)' }
    Write-Host "Steam 시작, ${Wait}초 기다린 뒤 제보를 가져옴..."
    Start-Sleep -Seconds $Wait
    Get-Report | Out-Null
    return
}

# Pictures for the Steam menu fault, at three steps: X server (xwd), screen file (app input, a
# burst of frames over time), Android screen (adb screencap, three in a row). Files land in
# .\reports\<folder>, one result line per picture set
# Steam 메뉴 깨짐용 캡처, 세 단계: X 서버(xwd), 화면 파일(앱 입력, 시간에 따른 프레임 묶음), 안드로이드 화면
# (adb screencap, 연달아 세 장). 파일은 .\reports\<폴더>, 캡처 묶음마다 결과 한 줄
$SessionLog = 'files/ezs/home/steam/ezs-logs/app-session.log'
function Get-Shot([string]$Label, [string]$Dir, [int]$Burst = 1500, [bool]$X = $true, [int]$Caps = 3) {
    $xs = if ($X) { 'true' } else { 'false' }
    Invoke-Adb shell am broadcast -a app.ezsteam.SHOT --es label $Label --ei burst $Burst --ez x $xs | Out-Null
    # Android screen while the burst runs / 프레임 묶음을 찍는 동안의 안드로이드 화면
    for ($k = 1; $k -le $Caps; $k++) {
        Invoke-Adb shell screencap -p "$Reports/shot-$Label-android-$k.png" | Out-Null
    }
    $line = $null
    for ($i = 0; $i -lt 45 -and -not $line; $i++) {
        Start-Sleep -Seconds 2
        # ASCII pattern only (Korean in adb arguments is not safe on Windows): "SHOT label 끝: ..."
        # adb 인자에는 ASCII 패턴만(Windows에서 adb 인자의 한국어는 안전하지 않음): "SHOT label 끝: ..."
        $line = Invoke-Adb shell "run-as $Pkg grep -a -E 'SHOT $Label [^ :]+: ' $SessionLog" | Select-Object -Last 1
    }
    if (-not $line) { $line = "SHOT $Label 끝: 90초 안에 끝나지 않음" }
    # Every file of this set, whatever the frame count / 프레임 수와 상관없이 이 묶음의 파일 전부
    foreach ($n in @(Invoke-Adb shell "ls $Reports" | Where-Object { $_ -like "shot-$Label-*.png" })) {
        Invoke-Adb pull "$Reports/$n" (Join-Path $Dir $n) | Out-Null
        Invoke-Adb shell rm -f "$Reports/$n" | Out-Null
    }
    Write-Host "   $line"
    return $line
}

# Shared steps of the unattended GPU runs / 사람 없이 도는 GPU 실행의 공통 단계
# Fresh Steam start in a Steam UI GPU mode, then 60 s to settle / Steam 화면 GPU 방식으로 Steam 새로 시작, 60초 안정
# $Size "WxH" = X screen size for this start only (not saved in the app)
# $Size "WxH" = 이번 시작에만 쓰는 X 화면 크기(앱에 저장 안 함)
function Start-SteamMode([int]$Mode, [string]$Size = '') {
    Invoke-Adb shell am force-stop $Pkg | Out-Null
    $extra = @()
    if ($Size) { $extra = @('--es', 'ezs.size', $Size) }
    $o = Invoke-Adb shell am start -n "$Pkg/.MainActivity" --es ezs.run steam --ei ezs.gpumode $Mode @extra
    if (($o -join ' ') -match 'Error') { Write-Host ($o -join "`n"); throw '앱 시작 실패 (태블릿 연결·USB 디버깅 확인)' }
    Start-Sleep -Seconds 60
}
# One measured span; arrow keys (left/right through the home row) when $Keys. Returns its line
# 측정 구간 하나. $Keys면 화살표 키(홈 줄 좌우). 그 줄을 돌려줌
function Measure-Span([string]$Label, [int]$Sec, [bool]$Keys) {
    Invoke-Adb shell am broadcast -a app.ezsteam.MEASURE --es phase start --es label $Label | Out-Null
    if ($Keys) {
        Invoke-Adb shell "e=`$(( `$(date +%s) + $Sec )); while [ `$(date +%s) -lt `$e ]; do input keyevent 22; sleep 0.3; input keyevent 21; sleep 0.3; done" | Out-Null
    } else {
        Start-Sleep -Seconds $Sec
    }
    Invoke-Adb shell am broadcast -a app.ezsteam.MEASURE --es phase end --es label $Label | Out-Null
    Start-Sleep -Seconds 3
    # Quoted as one shell string: the label and the colon stay one grep pattern
    # 셸 문자열 하나로 넘김: 라벨과 콜론이 grep 패턴 하나로 남음
    return @(Invoke-Adb shell "run-as $Pkg grep -a 'MEASURE ${Label}:' $SessionLog" | Select-Object -Last 1)
}
# Menu check: four guide presses with frames after each (the menu state is not assumed); the
# lines carry the band noise score of the menu fault. Run right after the start: the fault showed
# when the menu was opened 60 s after the start (2026-10-31 13:46, 14:16) and not after a minute of
# arrow-key moves (nine runs, 2026-10-31 16:02-16:33)
# 메뉴 확인: 가이드 네 번, 누를 때마다 프레임 기록(메뉴 상태는 가정하지 않음). 줄에 메뉴 깨짐 띠 잡음 점수가 있음.
# 시작 직후에 실행함: 시작 60초 뒤 메뉴를 열었을 때 깨짐이 나왔고(2026-10-31 13:46, 14:16), 화살표 이동 1분 뒤에는
# 나오지 않았음(9회, 2026-10-31 16:02~16:33)
# $Presses: guide presses (12 were needed: the fault shows from the second or third opening on,
# the first is always clean, user 2026-11-01). With many presses one picture per press, no burst
# $Presses: 가이드 누름 수(12번이 필요했음: 깨짐은 두 번째·세 번째 열 때부터 나오고 첫 번째는 늘 정상, 사용자
# 2026-11-01). 누름이 많을 때는 누를 때마다 한 장, 묶음 없음
function Test-Menu([string]$Tag, [string]$Dir, [int]$Presses = 4) {
    $out = @()
    $burst = if ($Presses -gt 4) { 0 } else { 1500 }
    for ($r = 1; $r -le $Presses; $r++) {
        Invoke-Adb shell am broadcast -a app.ezsteam.GUIDE | Out-Null
        Start-Sleep -Seconds 4
        $out += Get-Shot "$Tag-g$r" $Dir $burst $false 1
    }
    return $out
}

if ($Cmd -eq 'compare') {
    # For each Steam UI GPU mode (1 Chromium choice = GL through Zink, 2 ANGLE Vulkan, 3 software
    # compositing): fresh start, 60 s idle span, 60 s arrow-key span on the home screen (before any
    # menu is opened), then the menu check (band noise). Switches and the GPU mode are put back.
    # About 10 minutes
    # Steam 화면 GPU 방식마다(1 Chromium 선택 = Zink 위 GL, 2 ANGLE Vulkan, 3 소프트웨어 합성): 새로 시작, 대기 60초 구간,
    # 홈 화면에서 화살표 키 60초 구간(메뉴를 열기 전), 그다음 메뉴 확인(띠 잡음). 스위치와 GPU 방식은 되돌림. 약 10분
    $saved = @(Get-Switches)
    $prefs = (Invoke-Adb shell run-as $Pkg cat shared_prefs/ezsteam.xml) -join "`n"
    $orig = -1
    if ($prefs -match 'name="steam_gpu" value="(\d+)"') { $orig = [int]$Matches[1] }
    Set-Switches $PathSwitches['xwayland']
    $dir = Join-Path $Repo "reports\compare_$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss')"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $modes = @(@{ Id = 1; Tag = 'chromium-gl' }, @{ Id = 2; Tag = 'angle-vulkan' }, @{ Id = 3; Tag = 'sw-compositing' })
    $results = @()
    try {
        foreach ($m in $modes) {
            Write-Host "== $($m.Tag): Steam 새로 시작, 60초 안정"
            Start-SteamMode $m.Id
            Write-Host '   대기 60초 측정'
            $results += Measure-Span "$($m.Tag)-idle" 60 $false
            Write-Host '   화살표 키 이동 60초 측정'
            $results += Measure-Span "$($m.Tag)-move" 60 $true
            Write-Host '   메뉴 확인'
            $results += Test-Menu $m.Tag $dir
        }
        Get-Report | Out-Null
    } finally {
        Invoke-Adb shell am force-stop $Pkg | Out-Null
        Set-Switches $saved
        if ($orig -ge 0) {
            Invoke-Adb shell am start -n "$Pkg/.MainActivity" --es ezs.run setgpu --ei ezs.gpumode $orig | Out-Null
            Write-Host "Steam 화면 GPU 방식 되돌림 ($orig)"
        }
    }
    Write-Host ''
    Write-Host "== 결과 (그림: $dir)"
    $results | ForEach-Object { Write-Host $_ }
    $results | Set-Content -Encoding UTF8 (Join-Path $dir 'result.txt')
    return
}

if ($Cmd -eq 'check') {
    # The display path (Zink GL + GPU device on Xwayland) once, the app's own switches put back
    # afterwards (the Xvfb pass went with Xvfb, 2026-11-01)
    # 화면 경로(Xwayland 위 Zink GL + GPU 장치)를 한 번 점검하고, 끝나면 앱의 스위치를 되돌림(Xvfb 회차는 Xvfb와 함께
    # 없앰, 2026-11-01)
    $saved = @(Get-Switches)
    Write-Host '== Zink GL + GPU 장치 + Xwayland'
    Set-Switches $PathSwitches['xwayland']
    Invoke-Check
    Get-Report | Out-Null
    Set-Switches $saved
    return
}

if ($Cmd -eq 'release') {
    # Only a clean folder whose commit is already on GitHub gets a tag
    # 바뀐 파일이 없고 커밋이 이미 GitHub에 올라간 상태에서만 태그를 붙임
    if (git status --porcelain) { throw '올리지 않은 변경이 있음: 먼저 ezs-push.bat 실행' }
    git fetch origin
    $head = (git rev-parse HEAD).Trim()
    $remote = (git rev-parse '@{u}').Trim()
    if ($head -ne $remote) { throw '이 커밋이 아직 GitHub에 없음: 먼저 ezs-push.bat 실행' }
    # Version from app/build.gradle (major.minor.patch) / app/build.gradle의 버전(메이저.마이너.패치)
    $gradle = Get-Content (Join-Path $Repo 'app\build.gradle') -Raw
    if ($gradle -notmatch "versionName '(\d+\.\d+\.\d+)'") { throw 'app/build.gradle에서 versionName을 찾지 못함' }
    $tag = "v$($Matches[1])"
    # A tag that already exists on GitHub is never moved / GitHub에 이미 있는 태그는 옮기지 않음
    if (git ls-remote --tags origin "refs/tags/$tag") { throw "$tag 태그가 이미 있음: versionName을 올린 뒤 다시 실행" }
    $short = (git rev-parse --short=7 HEAD).Trim()
    $ok = Read-Host "$short 커밋에 $tag 태그(테스트 버전)를 붙여 올림. 진행하려면 y"
    if ($ok -ne 'y') { Write-Host '취소함'; return }
    git tag -a $tag -m "$tag (test build)"
    git push origin $tag
    if ($LASTEXITCODE -ne 0) { throw 'git push 실패 (태그)' }
    Write-Host ''
    Write-Host "태그 올림: $tag ($short)"
    Write-Host 'GitHub Actions(태그 빌드)가 모두 초록이 되면 ezs-install.bat 실행 (드라이버도 함께 받음)'
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

# install: only when the published APK was built from the current commit. It waits for it: right
# after ezs-push.bat the build is still running, and a finished build was sometimes not seen at
# once (the release download address answered with the previous file for a while, user report
# 2026-11-02). So the version file is read with a cache-busting query every 20 s (up to 60 min),
# and the APK itself comes through the GitHub API by asset id, which always names the newest file
# install: 올라온 APK가 지금 커밋으로 빌드된 것일 때만 설치함. 그때까지 기다림: ezs-push.bat 직후에는 빌드가 아직
# 돌고 있고, 끝난 빌드도 바로 보이지 않을 때가 있었음(릴리스 받기 주소가 한동안 이전 파일로 답함, 사용자 제보
# 2026-11-02). 그래서 버전 파일을 캐시를 피하는 질의로 20초마다(최대 60분) 읽고, APK 자체는 GitHub API에서 자산
# 번호로 받음(항상 최신 파일을 가리킴)
$sha = (git rev-parse --short=7 HEAD).Trim()
$tmp = Join-Path $env:TEMP 'ezsteam-apk'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$Api = 'https://api.github.com/repos/4thyaN6/EZsteam-layer/releases/tags/apk-latest'
$NoCache = @{ 'Cache-Control' = 'no-cache'; 'Pragma' = 'no-cache' }
# Version of the published APK, '' when none yet / 올라온 APK의 버전, 아직 없으면 ''
function Get-PublishedVersion {
    try {
        $q = [DateTime]::UtcNow.Ticks
        Invoke-WebRequest "$Url/apk.version?t=$q" -OutFile "$tmp\apk.version" -UseBasicParsing -Headers $NoCache
        return (Get-Content "$tmp\apk.version" -Raw).Trim()
    } catch {
        return ''
    }
}
$start = Get-Date
$built = Get-PublishedVersion
$lastNote = [DateTime]::MinValue
while ($built -ne $sha) {
    $min = [int]((Get-Date) - $start).TotalMinutes
    if ($min -ge 60) {
        Write-Host "60분 동안 최신 APK가 올라오지 않음: 저장소 $sha / 올라온 APK $built (Actions 실패 여부 확인)"
        return
    }
    # One line a minute, so the window shows it is still waiting / 1분에 한 줄: 아직 기다리는 중임을 보임
    if (((Get-Date) - $lastNote).TotalSeconds -ge 60) {
        Write-Host "최신 APK 기다리는 중 ($min분): 저장소 $sha / 올라온 APK $(if ($built) { $built } else { '없음' })"
        $lastNote = Get-Date
    }
    Start-Sleep -Seconds 20
    $built = Get-PublishedVersion
}
$apk = "$tmp\ezsteam-$built.apk"
# The APK through the API (asset id of the current file); the download address as a fallback
# (the API allows 60 unauthenticated requests an hour)
# API로 APK를 받음(지금 파일의 자산 번호). 실패하면 받기 주소로(API는 인증 없이 시간당 60번)
$got = $false
try {
    $rel = Invoke-RestMethod $Api -Headers $NoCache
    $asset = $rel.assets | Where-Object { $_.name -eq 'app-debug.apk' } | Select-Object -First 1
    if ($asset) {
        Invoke-WebRequest $asset.url -OutFile $apk -UseBasicParsing -Headers @{ 'Accept' = 'application/octet-stream' }
        $got = $true
    }
} catch {
    Write-Host "API로 받기 실패, 받기 주소로 받음: $($_.Exception.Message)"
}
if (-not $got) {
    $q = [DateTime]::UtcNow.Ticks
    Invoke-WebRequest "$Url/app-debug.apk?t=$q" -OutFile $apk -UseBasicParsing -Headers $NoCache
}
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
# Driver package too: the app installs it only when the Release has a different version
# 드라이버 묶음도: Release 버전이 다를 때만 앱이 설치함
Invoke-Adb shell am start -n "$Pkg/.MainActivity" --es ezs.run driver | Out-Null
Write-Host '드라이버 묶음 확인 요청 보냄 (새 버전이면 앱이 받아 설치, 앱 로그 "설치 완료: turnip" 또는 "이미 같은 버전")'
Write-Host ''
Write-Host "설치 완료. 앱 첫 줄이 'app build=$built'인지 확인"
