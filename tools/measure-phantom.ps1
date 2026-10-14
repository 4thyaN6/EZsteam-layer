# Measure Android "phantom" (app child) processes for a baseline snapshot.
# 앱 자식 프로세스(phantom) 개수·종료 기록을 한 시점 기준으로 저장함
#
# Usage / 사용법:
#   .\measure-phantom.ps1 -ClearLog            (before each scenario / 시나리오 시작 전)
#   .\measure-phantom.ps1 -Label app-only
#   .\measure-phantom.ps1 -Label steam-game -Package com.ezlator.app
#   .\measure-phantom.ps1 -Label normal-game -Serial XXXX
#
# Output: .\phantom-logs\<yyyyMMdd-HHmmss>-<Label>\ (raw dumps + summary.txt)
# 결과: 위 폴더에 원본 출력과 summary.txt를 저장함

param(
    # Free-form tag for the measurement moment (app-only / steam-game / normal-game ...)
    # 측정 시점 이름(앱만 / 스팀 게임 / 일반 게임 등)
    [string]$Label = "",

    # App package to filter child processes by owner uid (optional)
    # 자식 프로세스를 소유 uid로 거를 앱 패키지명(선택)
    [string]$Package = "",

    # adb device serial when several devices are attached (optional)
    # 기기가 여러 대일 때 adb 시리얼(선택)
    [string]$Serial = "",

    # Clear logcat and exit; run once before starting the scenario so old kills are not counted
    # logcat만 비우고 끝냄. 시나리오 시작 전에 한 번 실행해 이전 종료 기록이 섞이지 않게 함
    [switch]$ClearLog,

    # adb executable (default: same SDK path as the EZlator scripts; falls back to PATH)
    # adb 실행 파일(기본: EZlator 스크립트와 같은 SDK 경로, 없으면 PATH의 adb)
    [string]$Adb = "D:\Utility\Android\SDK\platform-tools\adb.exe"
)

$ErrorActionPreference = "Stop"

# Build adb argument prefix (adds -s <serial> when given)
# adb 공통 인자 구성(-s 시리얼 지정 시 추가)
if (-not (Test-Path $Adb)) { $Adb = "adb" }
$adbBase = @()
if ($Serial -ne "") { $adbBase += @("-s", $Serial) }

# Run adb and return stdout lines as a string array
# adb를 실행하고 표준출력 줄 배열을 돌려줌
function Invoke-Adb([string[]]$AdbArgs) {
    $out = & $Adb @adbBase @AdbArgs 2>&1
    return @($out | ForEach-Object { "$_" })
}

# Clear-only mode
# 기록 비우기 전용 모드
if ($ClearLog) {
    Invoke-Adb @("logcat", "-c") | Out-Null
    Write-Host "logcat cleared"
    return
}

# Label is required for a measurement
# 측정할 때는 Label이 필요함
if ($Label -eq "") { throw "-Label is required (e.g. -Label app-only)" }

# Prepare output directory
# 결과 폴더 준비
$stamp  = Get-Date -Format "yyyyMMdd-HHmmss"
$outDir = Join-Path (Get-Location) ("phantom-logs\" + $stamp + "-" + $Label)
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# Device facts (model, Android version, SDK level)
# 기기 정보(모델, 안드로이드 버전, SDK 레벨)
$model   = (Invoke-Adb @("shell", "getprop", "ro.product.model")) -join ""
$release = (Invoke-Adb @("shell", "getprop", "ro.build.version.release")) -join ""
$sdk     = (Invoke-Adb @("shell", "getprop", "ro.build.version.sdk")) -join ""
$soc     = (Invoke-Adb @("shell", "getprop", "ro.soc.model")) -join ""

# Developer-option state of the child process restriction (Android 14+ toggle backs this setting)
# 자식 프로세스 제한 설정값(14 이상 개발자 옵션 토글이 쓰는 값)
$monitorFlag = (Invoke-Adb @("shell", "settings", "get", "global", "settings_enable_monitor_phantom_procs")) -join ""
$maxPhantom  = (Invoke-Adb @("shell", "device_config", "get", "activity_manager", "max_phantom_processes")) -join ""

# Full activity process dump (no -a: Android 13 note in handoff doc)
# 전체 프로세스 덤프(-a 없이, 문서 5번의 Android 13 주의 반영)
$procDump = Invoke-Adb @("shell", "dumpsys", "activity", "processes")
$procDump | Set-Content -Encoding UTF8 (Join-Path $outDir "dumpsys-activity-processes.txt")

# Extract the "All Active App Child Processes" block: header line until indentation returns to header level
# "All Active App Child Processes" 구역만 잘라냄: 머리줄부터 들여쓰기가 머리줄 수준으로 돌아오기 전까지
$section = New-Object System.Collections.Generic.List[string]
$inSection = $false
$headerIndent = 0
foreach ($line in $procDump) {
    if (-not $inSection) {
        if ($line -match "All Active App Child Processes") {
            $inSection = $true
            $headerIndent = ($line.Length - $line.TrimStart().Length)
            $section.Add($line)
        }
        continue
    }
    $indent = ($line.Length - $line.TrimStart().Length)
    if ($line.Trim() -ne "" -and $indent -le $headerIndent) { break }
    $section.Add($line)
}
$section | Set-Content -Encoding UTF8 (Join-Path $outDir "child-processes-section.txt")

# Count records in the section (lines naming PhantomProcessRecord)
# 구역 안의 기록 개수 셈(PhantomProcessRecord가 적힌 줄 기준)
$phantomCount = @($section | Where-Object { $_ -match "PhantomProcessRecord" }).Count

# Process list with owner, pid, ppid, rss, name (for uid filtering)
# 소유자·pid·ppid·rss·이름 목록(uid로 거르기용)
$psAll = Invoke-Adb @("shell", "ps", "-A", "-o", "USER,PID,PPID,RSS,NAME")
$psAll | Set-Content -Encoding UTF8 (Join-Path $outDir "ps-all.txt")

# When a package is given, keep only processes owned by the same Linux user as the app
# 패키지를 주면 앱과 같은 리눅스 사용자 소유 프로세스만 남김
$appUser = ""
$appProcs = @()
if ($Package -ne "") {
    $appPid = ((Invoke-Adb @("shell", "pidof", $Package)) -join " ").Trim().Split(" ")[0]
    if ($appPid -ne "") {
        $appUser = ((Invoke-Adb @("shell", "ps", "-o", "USER=", "-p", $appPid)) -join "").Trim()
        $appProcs = @($psAll | Where-Object { $_ -match ("^" + [regex]::Escape($appUser) + "\s") })
        $appProcs | Set-Content -Encoding UTF8 (Join-Path $outDir "ps-app-user.txt")
    }
}

# Kill history from ActivityManager (trim by count / excessive cpu)
# ActivityManager 종료 기록(개수 초과 정리 / CPU 과다)
$amLog = Invoke-Adb @("logcat", "-d", "-s", "ActivityManager")
$phantomLog = @($amLog | Where-Object { $_ -match "PhantomProcessRecord|Trimming phantom processes|excessive cpu" })
$phantomLog | Set-Content -Encoding UTF8 (Join-Path $outDir "logcat-phantom.txt")
$trimCount = @($phantomLog | Where-Object { $_ -match "Trimming phantom processes" }).Count
$cpuCount  = @($phantomLog | Where-Object { $_ -match "excessive cpu" }).Count

# Write the summary
# 요약 기록
$summary = @(
    "label            : $Label",
    "time             : $stamp",
    "model / soc      : $model / $soc",
    "android / sdk    : $release / $sdk",
    "monitor_phantom  : $monitorFlag   (false = restriction off)",
    "max_phantom      : $maxPhantom   (null = default 32)",
    "child records    : $phantomCount   (All Active App Child Processes)",
    "app user         : $appUser",
    "app-user procs   : $($appProcs.Count)   (includes the app process itself)",
    "trim kills       : $trimCount",
    "cpu kills        : $cpuCount"
)
$summary | Set-Content -Encoding UTF8 (Join-Path $outDir "summary.txt")
$summary | ForEach-Object { Write-Host $_ }
Write-Host "saved: $outDir"
