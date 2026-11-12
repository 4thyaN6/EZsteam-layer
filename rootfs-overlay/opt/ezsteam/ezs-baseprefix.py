#!/usr/bin/env python3
# Base prefix: a clean Proton prefix with only Steam's common redistributables installed, made
# once and copied into each new game prefix, so the first start of a game does not create a
# prefix and run the same installers (DirectX alone took about 3 minutes, 2026-10-19) again.
# 기본 prefix: 깨끗한 Proton prefix에 Steam 공용 재배포 패키지만 설치한 것. 한 번 만들어 새 게임
# prefix마다 복사함. 게임 첫 시작 때 prefix를 만들고 같은 설치 프로그램을 다시 돌리지 않게 함(DirectX만
# 약 3분 걸렸음, 2026-10-19)
#
#   python3 ezs-baseprefix.py prepare [<Proton dir>]
#   python3 ezs-baseprefix.py ensure <game compat data dir> <Proton dir>
#   python3 ezs-baseprefix.py directx [<Proton dir>]
#
# prepare: started by the app ahead of time (ezs-baseprefix.sh), right after Steam has downloaded
# Proton and after a game download brought new redistributables, so the work is done before the
# first game start. ensure: called by compat/ezproton before Proton, for every start; does nothing
# when the game prefix is complete (Proton's "version" file present), otherwise copies the base
# (and only builds it there if prepare has not run).
# prepare: 앱이 미리 시작함(ezs-baseprefix.sh). Steam이 Proton을 받은 직후와 게임 다운로드로 새 재배포가
# 생긴 뒤에 돌려, 게임 첫 시작 전에 끝내 둠. ensure: compat/ezproton이 Proton보다 먼저 매 시작마다 부름.
# 게임 prefix가 완성돼 있으면("version" 파일 있음) 아무것도 하지 않고, 아니면 기본 prefix를 복사함
# (prepare가 돌지 않았을 때만 그 자리에서 만듦).
#
# What goes into the base / 기본 prefix에 넣는 것:
# - Proton's own new prefix (its default_pfx copy + wineboot), made by running Proton itself
#   Proton이 직접 만든 새 prefix(default_pfx 복사 + wineboot). Proton을 그대로 실행해 만듦
# - the redistributables Steam keeps in "Steamworks Shared/_CommonRedist" on this device, run
#   with the steps of their own installscript.vdf (process, command, HasRunKey), the same steps
#   Steam's install script runs; after a step succeeds (exit code 0) a DWORD named after the step
#   is set to 1 under its HasRunKey, which is how Steam records it (Valve install script docs),
#   so Steam's install script skips that step in the copied prefixes. Only DirectX and the
#   Visual C++ runtimes: their script format is known from Valve's own files; the others stay
#   with Steam's per-game install as before
#   이 기기의 "Steamworks Shared/_CommonRedist"에 Steam이 받아 둔 재배포 패키지. 각자의
#   installscript.vdf 단계(process, command, HasRunKey)대로 실행함(Steam 설치 스크립트가 돌리는 것과
#   같은 단계). 단계가 성공하면(exit 코드 0) HasRunKey 키 아래에 단계 이름의 DWORD를 1로 정함(Steam이
#   기록하는 방식, Valve 설치 스크립트 문서). 그래서 복사한 prefix에서는 Steam 설치 스크립트가 그 단계를
#   건너뜀. DirectX와 Visual C++ 런타임만: Valve 자체 파일로 스크립트 형식이 확인된 것. 나머지는 지금처럼
#   Steam이 게임마다 설치함
# Not included: game installers, game data, saves; .NET (large, few games) / 넣지 않는 것: 게임
# 설치 프로그램, 게임 데이터, 세이브, .NET(크고 쓰는 게임이 적음)
#
# DirectX is optional (user decision, 2026-10-21): by default only its install record (HasRunKey)
# is written into the base, so Steam does not run DXSETUP at a game's first start and games use
# Proton's own DirectX parts. "directx" mode (the app's [DirectX 설치]) installs it for real: on a
# staging copy of the base that is swapped in (about 3.5 minutes), then in every existing game
# prefix while that game is not running. A prefix that has it carries the file ezs-directx.
# Which DLLs a game then loads (Microsoft's or Proton's) is chosen at start by compat/ezproton
# (the app's "Native" option).
# DirectX는 선택 사항(사용자 결정, 2026-10-21): 기본은 설치 기록(HasRunKey)만 기본 prefix에 써서 게임 첫
# 시작 때 Steam이 DXSETUP을 돌리지 않고, 게임은 Proton 자체 DirectX 부품을 씀. "directx" 모드(앱의
# [DirectX 설치])는 실제로 설치함: 기본 prefix의 준비용 복사본에 설치해 바꿔 넣고(약 3분 30초), 이미 있는
# 게임 prefix마다 그 게임이 돌지 않을 때 설치함. 설치된 prefix에는 ezs-directx 파일이 있음. 그 뒤 게임이
# 어느 DLL(Microsoft 것 또는 Proton 것)을 쓸지는 시작 때 compat/ezproton이 정함(앱의 "Native" 선택)
#
# Nothing of Microsoft or Valve is shipped with the app: the installers are the files Steam
# downloaded to this device. / 앱에는 Microsoft·Valve 파일이 없음. 설치 프로그램은 Steam이 이 기기에
# 받은 파일임
#
# Output lines start with "ezproton: base" (the app shows them and its first-start banner).
# 출력 줄은 "ezproton: base"로 시작함(앱이 보여 주고 첫 시작 안내 띠에 씀).
import fcntl
import json
import os
import re
import shutil
import subprocess
import sys
import time
import uuid

HOME = os.path.expanduser("~")
BASE = os.path.join(HOME, ".local", "share", "ezsteam", "base-prefix")
MARK = "ezs-base.json"
STEAM = os.environ.get("STEAM_COMPAT_CLIENT_INSTALL_PATH") or os.path.join(HOME, ".local", "share", "Steam")
# Libraries that may hold "Steamworks Shared" / "Steamworks Shared"가 있을 수 있는 라이브러리
LIBRARIES = [os.path.join(STEAM, "steamapps"), "/mnt/ezlator-library/steamapps"]
# Redistributables put into the base (folder names under _CommonRedist, lower case)
# 기본 prefix에 넣는 재배포(_CommonRedist 아래 폴더 이름, 소문자)
ONLY = {"directx", "vcredist"}
# Installed in the background after the base is usable / 기본 prefix를 쓸 수 있게 된 뒤 백그라운드로 설치
LATE = {"directx"}
STAGE = BASE + ".dx"
# Asked for by [DirectX 설치]; stays, so a rebuilt base (new Proton) gets DirectX again
# [DirectX 설치]로 요청됨. 계속 남아 다시 만든 기본 prefix(새 Proton)에도 DirectX를 다시 넣음
WANTED = os.path.join(os.path.dirname(BASE), "directx-wanted")
# In a prefix (and the base): DirectX really installed / prefix(와 기본 prefix) 안: DirectX가 실제로 설치됨
DX_DONE = "ezs-directx"
COMPATDATA = os.path.join(STEAM, "steamapps", "compatdata")


def dx_wanted():
    return os.path.isfile(WANTED)


def say(msg):
    print("ezproton: base " + msg, file=sys.stderr, flush=True)


def vdf():
    """VDF parser shared with steam-library.py / steam-library.py와 같은 VDF 해석기"""
    import importlib.util
    here = os.path.dirname(os.path.abspath(__file__))
    spec = importlib.util.spec_from_file_location("ezs_vdf", os.path.join(here, "steam-library.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def unescape(s):
    """VDF string escapes (the parser keeps them as written) / VDF 이스케이프 풀기(해석기는 쓴 그대로 둠)"""
    return s.replace("\\\\", "\\").replace('\\"', '"')


def child(d, name):
    """Case-insensitive key lookup / 대소문자 무시 키 찾기"""
    for k, v in d.items():
        if k.lower() == name.lower():
            return v
    return None


def is_late(step):
    """DirectX step (installed in the background) / DirectX 단계(백그라운드 설치)"""
    return step["id"].split("/")[0].split(os.sep)[0].lower() in LATE


def proton_version(proton_dir):
    try:
        with open(os.path.join(proton_dir, "version"), encoding="utf-8", errors="replace") as f:
            return f.read().strip()
    except OSError:
        return ""


def redist_steps():
    """
    Steps of every installscript.vdf under Steamworks Shared/_CommonRedist (first library that
    has it): list of dicts {id, name, installdir, processes [(exe, args)], hasrun}.
    Steamworks Shared/_CommonRedist 아래 모든 installscript.vdf의 단계(그것이 있는 첫 라이브러리):
    {id, name, installdir, processes [(exe, args)], hasrun} 목록
    """
    v = vdf()
    for lib in LIBRARIES:
        shared = os.path.join(lib, "common", "Steamworks Shared")
        redist = os.path.join(shared, "_CommonRedist")
        if not os.path.isdir(redist) or not shared_ready(lib):
            continue
        steps = []
        for root, dirs, files in os.walk(redist):
            dirs.sort()
            rel = os.path.relpath(root, redist)
            if rel != "." and rel.split(os.sep)[0].lower() not in ONLY:
                continue
            for fn in files:
                if fn.lower() != "installscript.vdf":
                    continue
                path = os.path.join(root, fn)
                try:
                    with open(path, encoding="utf-8", errors="replace") as f:
                        tree = v.parse(f.read())
                except OSError:
                    continue
                script = child(tree, "InstallScript") or tree
                runs = child(script, "Run Process") or {}
                for name, step in runs.items():
                    if not isinstance(step, dict):
                        continue
                    procs = []
                    for n in range(1, 10):
                        exe = child(step, "process %d" % n)
                        if not isinstance(exe, str):
                            break
                        cmd = child(step, "command %d" % n)
                        procs.append((unescape(exe), unescape(cmd) if isinstance(cmd, str) else ""))
                    if not procs:
                        continue
                    hasrun = child(step, "HasRunKey")
                    steps.append({
                        "id": os.path.relpath(path, redist) + "#" + name,
                        "name": name,
                        "installdir": shared,
                        "processes": procs,
                        "hasrun": unescape(hasrun) if isinstance(hasrun, str) else "",
                    })
        return steps
    return []


def shared_ready(lib):
    """
    Steamworks Shared (appid 228980) usable in this library: its install record says installed
    with no update running (files_usable). Installers are not run from a download in progress.
    No record (folder copied by hand) counts as ready.
    이 라이브러리의 Steamworks Shared(appid 228980)를 쓸 수 있는지: 설치 기록이 설치됨이고 진행 중인
    업데이트가 없음(files_usable). 받는 중인 파일로 설치 프로그램을 돌리지 않음. 기록이 없으면(직접 복사한
    폴더) 준비된 것으로 봄
    """
    acf = os.path.join(lib, "appmanifest_228980.acf")
    try:
        with open(acf, encoding="utf-8", errors="replace") as f:
            m = re.search(r'"StateFlags"\s+"(\d+)"', f.read())
    except OSError:
        return True
    if m is None:
        return True
    return files_usable(int(m.group(1)))


# Steam app state bits: 4 fully installed; files missing 32, corrupt 128, update running 256,
# paused 512, started 1024, uninstalling 2048, and the download phases from 65536 up
# Steam 앱 상태 비트: 4 설치 완료. 파일 없음 32, 손상 128, 업데이트 중 256, 일시 정지 512, 시작됨 1024,
# 제거 중 2048, 65536부터는 받기 단계들
BUSY = 32 | 128 | 256 | 512 | 1024 | 2048


def files_usable(flags):
    """
    Installed files can be used: "fully installed" without a running or broken update. "Update
    required" (2) alone is fine: the files of the previous version are complete, and Steam put
    off that update for days (StateFlags 6 in a shared library, 2026-10-20)
    설치된 파일을 쓸 수 있음: 진행 중이거나 깨진 업데이트 없이 "설치 완료". "업데이트 필요"(2)만 있는 것은
    괜찮음: 이전 버전 파일이 온전하고, Steam이 그 업데이트를 며칠 미뤘음(공유 라이브러리에서 StateFlags 6,
    2026-10-20)
    """
    return bool(flags & 4) and not flags & BUSY and flags < 65536


def find_proton():
    """Valve ARM64 Proton folder, same rule as compat/ezproton / compat/ezproton과 같은 규칙의 Proton 폴더"""
    found = None
    common = os.path.join(STEAM, "steamapps", "common")
    try:
        names = sorted(os.listdir(common))
    except OSError:
        return None
    for name in names:
        d = os.path.join(common, name)
        try:
            with open(os.path.join(d, "toolmanifest.vdf"), encoding="utf-8", errors="replace") as f:
                if "4185400" in f.read() and os.path.isfile(os.path.join(d, "proton")):
                    found = d
        except OSError:
            continue
    return found


def unix_exe(installdir, exe):
    """%INSTALLDIR%\\a\\b.exe -> <installdir>/a/b.exe (case of the real folders) / 실제 폴더 대소문자로"""
    rel = re.sub(r"(?i)^%INSTALLDIR%[\\/]*", "", exe).replace("\\", "/")
    path = installdir
    for part in [p for p in rel.split("/") if p]:
        try:
            names = os.listdir(path)
        except OSError:
            return None
        match = next((n for n in names if n.lower() == part.lower()), None)
        if match is None:
            return None
        path = os.path.join(path, match)
    return path


# One step at a time with room for it (2026-11-11): the first session of a new install ran the
# four Visual C++ steps while the Steam client was starting, and on the last step Android killed
# the Steam session's PRoot for going over its 32 child-process limit (all apps together). Each
# step is one Wine session of about 15-20 processes, so in the app's own steps (prepare, directx)
# a step only starts while this app has at most GATE_MAX processes; otherwise it waits, and after
# GATE_WAIT seconds the rest is left for a later run (not marked failed). A game start (ensure) is
# not held up. Only this app's own processes are visible in /proc (Android shows each app its own
# uid only), so other apps' share of the 32 is not seen.
# 여유가 있을 때 한 단계씩(2026-11-11): 새로 설치한 앱의 첫 세션에서 Steam 클라이언트가 켜지는 동안
# Visual C++ 4단계가 돌았고, 마지막 단계에서 안드로이드가 자식 프로세스 한도 32개(모든 앱 합계)를 넘었다며
# Steam 세션의 PRoot를 종료했음. 단계 하나가 Wine 세션 하나(15~20개)라서, 앱 자체 단계(prepare, directx)에서는
# 이 앱의 프로세스가 GATE_MAX개 이하일 때만 단계를 시작함. 아니면 기다리고, GATE_WAIT초가 지나면 나머지를
# 다음 실행으로 미룸(실패로 기록하지 않음). 게임 시작(ensure)은 막지 않음. /proc에는 이 앱 자신의 프로세스만
# 보이므로(안드로이드는 앱마다 자기 uid만 보여 줌) 32개 중 다른 앱 몫은 보이지 않음
GATE = False
# Set when a step was left for later in this run / 이번 실행에서 단계를 다음으로 미뤘으면 정함
deferred = False
# 10, not 14 (device run 2026-11-12): a step added 15-21 processes on top of the count at its
# start (12-13 with Steam up gave peaks 28, 31, 33) / 14가 아닌 10(2026-11-12 기기 실행): 단계가 시작 때
# 수에 15~21개를 더했음(Steam이 떠 있을 때 12~13개에서 최대 28, 31, 33개)
GATE_MAX = int(os.environ.get("EZS_PROC_GATE", "10"))
GATE_WAIT = int(os.environ.get("EZS_PROC_GATE_WAIT", "600"))


def own_procs():
    """Processes of this app's uid, minus the app itself. The owner is compared with /proc/self's
    (both seen through PRoot the same way), because a process with the readproc group sees every
    process (run-as saw 862, 2026-11-11)
    이 앱 uid의 프로세스 수, 앱 자신은 뺌. 소유자를 /proc/self와 비교함(둘 다 PRoot를 같은 방식으로 거쳐
    보임). readproc 그룹이 있는 프로세스는 모든 프로세스를 봄(run-as에서 862개, 2026-11-11)"""
    try:
        uid = os.stat("/proc/self").st_uid
        n = 0
        for name in os.listdir("/proc"):
            if not name.isdigit():
                continue
            try:
                if os.stat("/proc/" + name).st_uid == uid:
                    n += 1
            except OSError:
                pass  # ended meanwhile / 그사이 끝남
        return max(0, n - 1)
    except OSError:
        return 0


# Inside the base lock nothing waits: a step without room ends the pass (busy), the lock is let
# go and the wait happens outside it, so a game start (ensure, same lock) is not held up (Codex
# P2 on #26). The wait time counts from the first wait of the run.
# 기본 prefix 잠금 안에서는 기다리지 않음: 여유가 없는 단계는 그 회차를 끝내고(busy) 잠금을 놓은 뒤
# 잠금 밖에서 기다림. 그래서 게임 시작(ensure, 같은 잠금)이 막히지 않음(#26 Codex P2). 기다린 시간은
# 이번 실행의 첫 대기부터 셈
HOLDING = False
busy = False
wait_t0 = None


# Test switch (development, 2026-11-12): file ~/.local/share/ezsteam/base-test with lines
# "defer_after=N" (after N steps every later step finds no room) and "wait=S" (seconds to wait
# instead of GATE_WAIT). Lets a device run defer a step inside one install script (x86 done, x64
# deferred) without keeping the device busy for 10 minutes. No file = no effect.
# 시험 스위치(개발용, 2026-11-12): ~/.local/share/ezsteam/base-test 파일의 "defer_after=N"(N단계 뒤의
# 모든 단계는 여유가 없는 것으로 봄)과 "wait=S"(GATE_WAIT 대신 기다릴 초). 기기를 10분 동안 바쁘게 두지
# 않고도 한 설치 스크립트 안에서 단계를 미루게 함(x86 끝, x64 미룸). 파일이 없으면 영향 없음
TEST_FILE = os.path.join(os.path.dirname(BASE), "base-test")
TEST_DEFER_AFTER = None
started = 0


def read_test_switch():
    global TEST_DEFER_AFTER, GATE_WAIT
    try:
        with open(TEST_FILE, encoding="utf-8", errors="replace") as f:
            text = f.read()
    except OSError:
        return
    for line in text.splitlines():
        key, _, value = line.strip().partition("=")
        try:
            if key == "defer_after":
                TEST_DEFER_AFTER = int(value)
            elif key == "wait":
                GATE_WAIT = int(value)
        except ValueError:
            pass
    say("test switch: defer after %s step(s), wait %d s" % (TEST_DEFER_AFTER, GATE_WAIT))


def gate_count():
    """Processes counted against GATE_MAX; the test switch makes it full after N steps
    GATE_MAX와 견주는 프로세스 수. 시험 스위치가 있으면 N단계 뒤에는 꽉 찬 것으로 봄"""
    n = own_procs()
    if TEST_DEFER_AFTER is not None and started >= TEST_DEFER_AFTER:
        n = max(n, GATE_MAX + 1)
    return n


# Peak processes of this app's uid, sampled every second while the app's own run goes (the
# session's exit line was lost when the app was closed, 2026-11-11): one line per step and one
# for the run / 앱 자체 실행 동안 1초마다 본 이 앱 uid 프로세스 최대 수(앱을 닫으면 세션 종료 줄이
# 남지 않았음, 2026-11-11): 단계마다 한 줄, 실행 전체 한 줄
peak_step = 0
peak_run = 0


def sample_peaks():
    global peak_step, peak_run
    while True:
        n = own_procs()
        peak_step = max(peak_step, n)
        peak_run = max(peak_run, n)
        time.sleep(1)


def say_peak(what):
    global peak_step
    if GATE:
        say("procs: peak %d during %s" % (max(peak_step, own_procs()), what))
        peak_step = 0


def room(what):
    """True = the step may start now. Without room: inside the lock False with busy set; outside
    it, wait (False = no room in time, deferred)
    True = 지금 단계를 시작해도 됨. 여유가 없으면: 잠금 안에서는 busy를 정하고 False. 잠금 밖에서는
    기다림(False = 제때 여유가 없어 미룸)"""
    global busy, started, peak_step
    if not GATE:
        return True
    if deferred:
        return False
    n = gate_count()
    if n <= GATE_MAX or (not HOLDING and wait_room(what)):
        started += 1
        peak_step = 0
        say("procs: %d before %s" % (own_procs(), what))
        return True
    if HOLDING:
        busy = True
        say("waiting: process room, %d > %d before %s" % (n, GATE_MAX, what))
    return False


def wait_room(what):
    """Wait outside the lock until there is room / 잠금 밖에서 여유가 생길 때까지 기다림"""
    global deferred, wait_t0
    if wait_t0 is None:
        wait_t0 = time.time()
    while True:
        n = gate_count()
        if n <= GATE_MAX:
            say("room: %d process(es), %s after %.0f s" % (n, what, time.time() - wait_t0))
            return True
        if time.time() - wait_t0 > GATE_WAIT:
            deferred = True
            mark_pending()
            say("deferred: no process room for %s (%d process(es))" % (what, n))
            return False
        time.sleep(3)


# Work left for later, kept on disk: the app's own count of new redistributables goes by install
# script, so a script with one step done and one deferred (x86 done, x64 not) looked finished and
# the next start before Steam skipped it (Codex P2 on EZsteam-layer #2). The file is set on a
# deferral and removed once a pass brings the base up to date; the app reads it.
# 다음으로 미룬 일을 디스크에 남김: 앱은 새 재배포를 설치 스크립트 단위로 세므로, 단계 하나는 끝나고 하나는
# 미뤄진 스크립트(x86 끝, x64 미룸)가 끝난 것으로 보여 다음 Steam 전 준비가 건너뛰었음(EZsteam-layer #2
# Codex P2). 미룰 때 만들고, 기본 prefix를 최신으로 맞춘 회차가 지움. 앱이 읽음
PENDING = os.path.join(os.path.dirname(BASE), "base-pending")


def mark_pending():
    try:
        os.makedirs(os.path.dirname(PENDING), exist_ok=True)
        open(PENDING, "w").close()
    except OSError:
        pass


def clear_pending():
    try:
        os.unlink(PENDING)
    except OSError:
        pass


def redist_readable():
    """The redistributable list can be read now: the first library with Steamworks Shared has
    usable files (no update running or paused); no Steamworks Shared anywhere = nothing to install
    재배포 목록을 지금 읽을 수 있음: Steamworks Shared가 있는 첫 라이브러리의 파일을 쓸 수 있음(진행 중이거나
    일시 정지한 업데이트 없음). 어디에도 Steamworks Shared가 없으면 설치할 것이 없음"""
    for lib in LIBRARIES:
        if os.path.isdir(os.path.join(lib, "common", "Steamworks Shared", "_CommonRedist")):
            return shared_ready(lib)
    return True


def clear_pending_if_done(mark):
    """
    Remove base-pending only when the list could be read and every non-DirectX step is in the
    mark (done or failed). While Steamworks Shared is updating, redist_steps() is empty and the
    pass looked complete with the deferred x64 step still missing (Codex P2 on #27).
    목록을 읽을 수 있고 DirectX가 아닌 모든 단계가 표식에 있을 때만(끝남 또는 실패) base-pending을 지움.
    Steamworks Shared를 업데이트하는 동안 redist_steps()가 비어, 미룬 x64 단계가 남았는데도 회차가 끝난
    것으로 보였음(#27 Codex P2)
    """
    if not os.path.isfile(PENDING):
        return
    if not redist_readable():
        say("pending kept: Steamworks Shared not usable now (update running or paused)")
        return
    done = set(mark["steps"]) | set(mark.get("failed", []))
    left = [s["id"] for s in redist_steps() if not is_late(s) and s["id"] not in done]
    if left:
        say("pending kept: %d step(s) left %s" % (len(left), ", ".join(left)))
        return
    clear_pending()


def stepwise_up_to_date(proton_dir):
    """bring_up_to_date in passes under the lock, waiting for room between them outside it; the
    mark, or None / 잠금 안에서 회차별로 bring_up_to_date, 회차 사이의 여유 대기는 잠금 밖에서. 표식 또는 None"""
    global HOLDING, busy
    while True:
        busy = False
        with locked():
            HOLDING = True
            try:
                mark = bring_up_to_date(proton_dir)
            finally:
                HOLDING = False
        if not busy:
            return mark
        if not wait_room("next step"):
            return None


def proton_run(proton_dir, compat, args, env_extra=None):
    """Run "proton run <args...>" on the prefix in <compat>; exit code / exit 코드"""
    env = dict(os.environ)
    env["STEAM_COMPAT_DATA_PATH"] = compat
    # Log name steam-ezsbase.log, not the game's / 로그 이름은 게임 것이 아닌 steam-ezsbase.log
    env["SteamGameId"] = "ezsbase"
    env["SteamAppId"] = "0"
    if env_extra:
        env.update(env_extra)
    p = subprocess.run([os.path.join(proton_dir, "proton"), "run"] + args, env=env)
    return p.returncode


def wait_server(proton_dir, compat):
    """Wait until the prefix's wineserver has exited (registry written to disk)
    prefix의 wineserver가 끝날 때까지 기다림(레지스트리가 디스크에 써짐)"""
    for name in ("files/bin/wineserver", "files/bin-arm64/wineserver", "dist/bin/wineserver"):
        ws = os.path.join(proton_dir, name)
        if os.path.isfile(ws):
            env = dict(os.environ)
            env["WINEPREFIX"] = os.path.join(compat, "pfx")
            subprocess.run([ws, "-w"], env=env)
            return
    time.sleep(5)


def write_hasrun(proton_dir, compat, marks):
    """
    Record finished steps like Steam does: under each HasRunKey a DWORD named after the step = 1.
    Written in both registry views (Steam on 64-bit Windows is a 32-bit program, so its keys are
    under Wow6432Node; the plain path is written too), with one regedit import.
    Steam처럼 끝난 단계를 기록함: 각 HasRunKey 아래 단계 이름의 DWORD = 1. 레지스트리 두 쪽에 모두 씀
    (64비트 윈도우의 Steam은 32비트 프로그램이라 키가 Wow6432Node 아래에 있음. 일반 경로도 씀).
    regedit 가져오기 한 번으로 처리함
    """
    lines = ["Windows Registry Editor Version 5.00", ""]
    for key, value in marks:
        hive, _, path = key.partition("\\")
        if hive.upper() not in ("HKEY_LOCAL_MACHINE", "HKLM") or not path:
            continue
        views = [path]
        if path.lower().startswith("software\\") and "wow6432node" not in path.lower():
            views.append("Software\\Wow6432Node\\" + path[len("software\\"):])
        for v in views:
            lines.append("[HKEY_LOCAL_MACHINE\\" + v + "]")
            lines.append('"%s"=dword:00000001' % value.replace("\\", "\\\\").replace('"', '\\"'))
            lines.append("")
    if len(lines) <= 2:
        return True
    reg = os.path.join(compat, "pfx", "drive_c", "ezs-redist.reg")
    with open(reg, "w", encoding="utf-16") as f:
        f.write("\r\n".join(lines) + "\r\n")
    rc = proton_run(proton_dir, compat, ["regedit", "/S", "C:\\ezs-redist.reg"])
    try:
        os.unlink(reg)
    except OSError:
        pass
    return rc == 0


def read_mark():
    try:
        with open(os.path.join(BASE, MARK), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def write_mark(mark):
    tmp = os.path.join(BASE, MARK + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(mark, f, ensure_ascii=False, indent=1)
    os.replace(tmp, os.path.join(BASE, MARK))


def build(proton_dir, version):
    """New base prefix with Proton itself / Proton으로 새 기본 prefix를 만듦"""
    if not room("build"):
        return False
    say("build start (한 번만): Proton " + version)
    tmp = BASE + ".tmp"
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp)
    # Any Windows command makes Proton create the prefix first / 아무 윈도우 명령이나 실행하면 Proton이 먼저 prefix를 만듦
    rc = proton_run(proton_dir, tmp, ["cmd", "/c", "exit", "0"])
    wait_server(proton_dir, tmp)
    if not os.path.isfile(os.path.join(tmp, "version")):
        say("build failed: Proton did not finish the prefix (rc %d)" % rc)
        shutil.rmtree(tmp, ignore_errors=True)
        return False
    shutil.rmtree(BASE, ignore_errors=True)
    os.replace(tmp, BASE)
    fix_paths(BASE, tmp, False)
    write_mark({"proton": version, "steps": [], "failed": [], "built": time.strftime("%Y-%m-%d %H:%M:%S")})
    say("prefix ready")
    say_peak("build")
    return True


def add_redists(proton_dir, mark, late, target=BASE):
    """
    Run the redistributable steps of one phase (late = DirectX) that the base does not have yet,
    in <target> (the base, or its staging copy for DirectX)
    한 단계(late = DirectX)의 재배포 중 기본 prefix에 아직 없는 것을 <target>(기본 prefix, DirectX는
    그 준비용 복사본)에 실행함
    """
    steps = redist_steps()
    todo = [s for s in steps if is_late(s) == late
            and s["id"] not in mark["steps"] and s["id"] not in mark.get("failed", [])]
    if not steps and not late:
        say("redist: Steamworks Shared 없음 (재배포 없이 사용)")
    for s in todo:
        if not room(s["id"]):
            break
        t0 = time.time()
        say("redist start: " + s["id"])
        ok = True
        for exe, args in s["processes"]:
            path = unix_exe(s["installdir"], exe)
            if path is None:
                say("redist skip: file not found " + exe)
                ok = False
                break
            rc = proton_run(proton_dir, target, [path] + args.split())
            # Same rule as Steam: exit code 0 = done / Steam과 같은 규칙: exit 코드 0 = 완료
            if rc != 0:
                say("redist failed: %s rc %d" % (exe, rc))
                ok = False
                break
        if ok and (not s["hasrun"] or write_hasrun(proton_dir, target, [(s["hasrun"], s["name"])])):
            mark["steps"].append(s["id"])
            say("redist done: %s (%.0f s, %s \\ %s = 1)" % (s["id"], time.time() - t0, s["hasrun"] or "-", s["name"]))
        else:
            # Failed steps are left to Steam's own install in each game, as before
            # 실패한 단계는 지금처럼 게임마다 Steam이 설치하게 둠
            mark.setdefault("failed", []).append(s["id"])
        wait_server(proton_dir, target)
        say_peak(s["id"])
        # The staging copy's mark is written when it is swapped in / 준비용 복사본의 표식은 바꿔 넣을 때 씀
        if target == BASE:
            write_mark(mark)
    return todo


def clear_dir(path):
    """Remove everything inside path / path 안의 것을 모두 지움"""
    for name in os.listdir(path):
        p = os.path.join(path, name)
        if os.path.isdir(p) and not os.path.islink(p):
            shutil.rmtree(p, ignore_errors=True)
        else:
            try:
                os.unlink(p)
            except OSError:
                pass


def copy_prefix(src, dst, new_machine):
    """
    Copy a prefix folder (symbolic links kept, the base's mark left out) and point the paths that
    Proton wrote in its own files at the new place; new_machine gives it its own machine id
    prefix 폴더를 복사함(심볼릭 링크 유지, 기본 prefix 표식은 빼고). Proton이 자체 파일에 적은 경로를 새
    위치로 바꿈. new_machine이면 새 기기 id를 줌
    """
    os.makedirs(dst, exist_ok=True)
    for name in os.listdir(src):
        if name == MARK or name.endswith(".lock"):
            continue
        s, d = os.path.join(src, name), os.path.join(dst, name)
        if os.path.isdir(s) and not os.path.islink(s):
            shutil.copytree(s, d, symlinks=True)
        else:
            shutil.copy2(s, d, follow_symlinks=False)
    fix_paths(dst, src, new_machine)


def fix_paths(prefix, old, new_machine):
    """Replace the old folder path in Proton's own files / Proton 자체 파일의 옛 폴더 경로를 바꿈"""
    for name in ("tracked_files", "config_info", "pfx/system.reg", "pfx/user.reg", "pfx/userdef.reg"):
        p = os.path.join(prefix, name)
        try:
            with open(p, encoding="utf-8", errors="surrogateescape") as f:
                text = f.read()
        except OSError:
            continue
        # The base is built in "<base>.tmp" and renamed, so its files may still name that folder
        # (copies before this fix carried it as "<game>.tmp"); both spellings are replaced
        # 기본 prefix는 "<base>.tmp"에서 만든 뒤 이름을 바꾸므로 파일에 그 폴더 이름이 남아 있을 수 있음
        # (이 수정 전 복사본에는 "<게임>.tmp"로 들어갔음). 두 가지 모두 바꿈
        new = text.replace(old + ".tmp", prefix).replace(old, prefix)
        # New machine id per prefix, as Proton makes for each new prefix
        # Proton이 새 prefix마다 만드는 것처럼 prefix마다 새 기기 id
        if name == "pfx/system.reg" and new_machine:
            new = re.sub(r'("MachineGuid"=")[0-9a-fA-F-]{36}(")', lambda m: m.group(1) + str(uuid.uuid4()) + m.group(2), new)
        if new != text:
            with open(p, "w", encoding="utf-8", errors="surrogateescape") as f:
                f.write(new)


def copy_into(compat):
    """Copy the base into the game's compat data folder / 기본 prefix를 게임 compat data 폴더로 복사함"""
    t0 = time.time()
    # Unfinished prefix (no version file): nothing of the game can be in it yet
    # 완성되지 않은 prefix(version 파일 없음): 게임 것이 아직 있을 수 없음
    clear_dir(compat)
    copy_prefix(BASE, compat, True)
    # Recorded redistributable keys now in the game prefix (check that Steam can see them)
    # 게임 prefix에 들어간 재배포 기록 키 수(Steam이 볼 수 있는지 확인용)
    keys = 0
    try:
        with open(os.path.join(compat, "pfx", "system.reg"), encoding="utf-8", errors="replace") as f:
            keys = sum(1 for line in f if line.startswith("[") and "commonredist" in line.lower())
    except OSError:
        pass
    say("copy done: %s (%.1f s, CommonRedist keys %d)" % (compat, time.time() - t0, keys))


def late_todo(mark):
    """DirectX steps the base does not have yet / 기본 prefix에 아직 없는 DirectX 단계"""
    return [s for s in redist_steps() if is_late(s)
            and s["id"] not in mark["steps"] and s["id"] not in mark.get("failed", [])]


def prefix_lock(compat, blocking):
    """
    Per-game lock held while DirectX is added to that prefix; the game start (ensure) waits on it.
    Returns the open lock file, or None when not blocking and it is taken.
    그 prefix에 DirectX를 더하는 동안 잡는 게임별 잠금. 게임 시작(ensure)이 이것을 기다림. 열린 잠금 파일을
    돌려줌. 기다리지 않을 때 이미 잡혀 있으면 None
    """
    d = os.path.join(os.path.dirname(BASE), "prefix-locks")
    os.makedirs(d, exist_ok=True)
    lock = open(os.path.join(d, re.sub(r"[^A-Za-z0-9]+", "_", compat).strip("_") + ".lock"), "w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | (0 if blocking else fcntl.LOCK_NB))
    except OSError:
        lock.close()
        return None
    return lock


def prefix_busy(compat):
    """
    The prefix's wineserver is running (its game is open): Wine keeps a lock on
    /tmp/.wine-<uid>/server-<dev>-<inode of the prefix>/lock while the server runs
    prefix의 wineserver가 도는 중(게임이 열려 있음): 서버가 도는 동안 Wine이
    /tmp/.wine-<uid>/server-<장치>-<prefix의 inode>/lock을 잠가 둠
    """
    try:
        st = os.stat(os.path.join(compat, "pfx"))
    except OSError:
        return False
    lockfile = "/tmp/.wine-%d/server-%x-%x/lock" % (os.getuid(), st.st_dev, st.st_ino)
    if not os.path.exists(lockfile):
        return False
    try:
        with open(lockfile, "r+") as f:
            fcntl.lockf(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
            fcntl.lockf(f, fcntl.LOCK_UN)
        return False
    except OSError:
        return True


def bring_up_to_date(proton_dir):
    """Build or rebuild the base for this Proton and add new redistributables; caller holds the lock.
    Returns the mark, or None when the base could not be made.
    이 Proton용 기본 prefix를 만들거나 다시 만들고 새 재배포를 더함. 호출한 쪽이 잠금을 잡고 있음.
    표식을 돌려줌. 기본 prefix를 못 만들면 None"""
    version = proton_version(proton_dir)
    mark = read_mark()
    rebuild = mark is None or mark.get("proton") != version or not os.path.isfile(os.path.join(BASE, "version"))
    # Step count for the app's progress card / 앱 진행 카드용 단계 수
    done = set() if rebuild else set(mark["steps"]) | set(mark.get("failed", []))
    n = sum(1 for s in redist_steps() if not is_late(s) and s["id"] not in done)
    say("plan: %d step(s)" % (n + (1 if rebuild else 0)))
    if rebuild:
        if mark is not None:
            say("rebuild: Proton %s -> %s" % (mark.get("proton"), version))
        if not build(proton_dir, version):
            return None
        mark = read_mark()
    add_redists(proton_dir, mark, False)
    # A step had no room: no more Wine in this pass, the DirectX record included (Codex P1 on #26)
    # 여유가 없던 단계가 있음: 이번 회차에는 Wine을 더 돌리지 않음, DirectX 기록 포함(#26 Codex P1)
    if busy or deferred:
        return mark
    mark_late(proton_dir, mark)
    # A base that got DirectX in the background before it became optional: mark it as installed
    # 선택 사항이 되기 전에 백그라운드로 DirectX를 받은 기본 prefix: 설치됨으로 표시
    if any(i.split("/")[0].lower() in LATE for i in mark["steps"]) and not os.path.isfile(os.path.join(BASE, DX_DONE)):
        open(os.path.join(BASE, DX_DONE), "w").close()
    return mark


def mark_late(proton_dir, mark):
    """
    DirectX not installed in the base: write only its install record, so Steam skips DXSETUP in
    games copied from it (same record Steam writes after running it)
    기본 prefix에 DirectX가 설치되지 않음: 설치 기록만 씀. 그래서 복사한 게임에서 Steam이 DXSETUP을
    건너뜀(Steam이 실행 뒤 쓰는 것과 같은 기록)
    """
    todo = [s for s in late_todo(mark) if s["id"] not in mark.get("marked", [])]
    if not todo:
        return
    # The record is a Wine run too / 기록도 Wine 실행임
    if not room("directx record"):
        return
    if write_hasrun(proton_dir, BASE, [(s["hasrun"], s["name"]) for s in todo if s["hasrun"]]):
        mark.setdefault("marked", []).extend(s["id"] for s in todo)
        wait_server(proton_dir, BASE)
        write_mark(mark)
        say("directx: record only (%d step(s)), real install with [DirectX 설치]" % len(todo))


def locked(waiting=None):
    """Exclusive lock on the base (one builder at a time; a game start waits for a running prepare).
    With waiting, that line is printed when the lock is taken by someone else (the app then tells
    the user the game is being prepared)
    기본 prefix의 단독 잠금(한 번에 하나만. 게임 시작은 돌고 있는 prepare를 기다림). waiting이 있으면 잠금을
    다른 쪽이 잡고 있을 때 그 줄을 출력함(앱이 게임 준비 중이라고 알림)"""
    os.makedirs(os.path.dirname(BASE), exist_ok=True)
    lock = open(BASE + ".lock", "w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        if waiting:
            say(waiting)
        fcntl.flock(lock, fcntl.LOCK_EX)
    return lock


def late(proton_dir):
    """
    Background phase: DirectX into a staging copy of the base, swapped in when done; then the
    same steps in game prefixes copied before (dx-pending.txt) that are not running. Runs without
    the base lock except for the short copy and swap, so game starts are not held up.
    백그라운드 단계: 기본 prefix의 준비용 복사본에 DirectX를 넣고 끝나면 바꿔 넣음. 그다음 그 전에 복사한
    게임 prefix(dx-pending.txt) 중 돌지 않는 것에 같은 단계를 실행함. 짧은 복사·교체 때만 기본 prefix
    잠금을 잡아 게임 시작을 막지 않음
    """
    todo, before = [], []
    with locked():
        mark = read_mark()
        if mark is None or not os.path.isfile(os.path.join(BASE, "version")):
            return
        todo = late_todo(mark)
        if todo:
            shutil.rmtree(STAGE, ignore_errors=True)
            copy_prefix(BASE, STAGE, False)
            before = list(mark["steps"])
    if todo:
        say("late start (백그라운드, 게임 바로 실행 가능): " + ", ".join(s["id"] for s in todo))
        t0 = time.time()
        add_redists(proton_dir, mark, True, STAGE)
        if deferred:
            # Not all of DirectX went in: drop the copy, a later run starts it again
            # DirectX가 다 들어가지 않음: 복사본을 버리고 다음 실행에서 다시 시작함
            shutil.rmtree(STAGE, ignore_errors=True)
            return
        with locked():
            now = read_mark()
            # The base changed meanwhile (rebuilt, or new steps): drop the copy, next run retries
            # 그사이 기본 prefix가 바뀜(다시 만들었거나 새 단계): 복사본을 버리고 다음 실행 때 다시 함
            if now is None or now.get("proton") != mark.get("proton") or now["steps"] != before:
                say("late retry: base changed meanwhile")
                shutil.rmtree(STAGE, ignore_errors=True)
                return
            # Swap in and point Proton's files back at the base path
            # 바꿔 넣고 Proton 파일의 경로를 기본 prefix 경로로 되돌림
            old = BASE + ".old"
            shutil.rmtree(old, ignore_errors=True)
            os.replace(BASE, old)
            os.replace(STAGE, BASE)
            fix_paths(BASE, STAGE, False)
            open(os.path.join(BASE, DX_DONE), "w").close()
            write_mark(mark)
            shutil.rmtree(old, ignore_errors=True)
        say("late done: base now has DirectX (%.0f s)" % (time.time() - t0))
    backfill(proton_dir)


def backfill(proton_dir):
    """
    DirectX into every game prefix that does not have it yet, once the base has it; a game that
    is running is left for a later run
    기본 prefix에 DirectX가 들어간 뒤, 아직 없는 모든 게임 prefix에 넣음. 실행 중인 게임은 다음 실행으로 미룸
    """
    mark = read_mark()
    if mark is None or not os.path.isfile(os.path.join(BASE, DX_DONE)):
        return
    steps = [s for s in redist_steps() if is_late(s) and s["id"] in mark["steps"]]
    if not steps:
        return
    try:
        games = sorted(os.path.join(COMPATDATA, n) for n in os.listdir(COMPATDATA))
    except OSError:
        games = []
    games = [g for g in games if os.path.isfile(os.path.join(g, "version"))
             and not os.path.isfile(os.path.join(g, DX_DONE))]
    for i, compat in enumerate(games, 1):
        if not room("backfill " + compat):
            return
        if prefix_busy(compat):
            say("backfill later (게임 실행 중): " + compat)
            continue
        lock = prefix_lock(compat, False)
        if lock is None:
            continue
        with lock:
            say("backfill start (%d/%d): %s" % (i, len(games), compat))
            t0 = time.time()
            ok = True
            for s in steps:
                for exe, args in s["processes"]:
                    path = unix_exe(s["installdir"], exe)
                    if path is None or proton_run(proton_dir, compat, [path] + args.split()) != 0:
                        ok = False
                        break
            wait_server(proton_dir, compat)
            if ok:
                open(os.path.join(compat, DX_DONE), "w").close()
            say("backfill %s: %s (%.0f s)" % ("done" if ok else "failed", compat, time.time() - t0))


def prepare(proton_dir):
    if not proton_dir or not os.path.isfile(os.path.join(proton_dir, "proton")):
        say("prepare: Proton not installed yet")
        return 0
    say("prepare start: Proton " + proton_version(proton_dir))
    mark = stepwise_up_to_date(proton_dir)
    if mark is None:
        return 0
    clear_pending_if_done(mark)
    say("prepare done: %d redist step(s) %s" % (len(mark["steps"]), ", ".join(mark["steps"]) or "-"))
    # Before Steam starts the app asks for the base only; DirectX (about 3.5 minutes) follows in
    # the session, step by step / Steam 시작 전에는 앱이 기본 prefix만 요청함. DirectX(약 3분 30초)는
    # 세션 안에서 단계별로 이어서 함
    if os.environ.get("EZS_BASE_NO_LATE") == "1":
        if dx_wanted():
            say("late later: DirectX after Steam has started")
        return 0
    if dx_wanted() and not deferred:
        late(proton_dir)
    return 0


def directx(proton_dir):
    """[DirectX 설치]: remember the choice, make the base current, install DirectX, add it to games
    [DirectX 설치]: 선택을 기억하고 기본 prefix를 맞춘 뒤 DirectX를 설치하고 게임에 더함"""
    if not proton_dir or not os.path.isfile(os.path.join(proton_dir, "proton")):
        say("dx: Proton not installed yet (sign in to Steam first)")
        return 0
    os.makedirs(os.path.dirname(WANTED), exist_ok=True)
    open(WANTED, "w").close()
    mark = stepwise_up_to_date(proton_dir)
    if mark is None:
        return 0
    clear_pending_if_done(mark)
    if not [s for s in redist_steps() if is_late(s)]:
        say("dx: no DirectX installer yet (Steamworks Shared comes with the first game download)")
        return 0
    late(proton_dir)
    say("dx: finished")
    return 0


def ensure(compat, proton_dir):
    # DirectX being added to this prefix in the background: wait for it
    # 이 prefix에 백그라운드로 DirectX를 넣는 중이면 기다림
    lock = prefix_lock(compat, False)
    if lock is None:
        say("waiting: DirectX being added to this game")
        lock = prefix_lock(compat, True)
    lock.close()
    if os.path.isfile(os.path.join(compat, "version")):
        return 0
    if os.environ.get("EZS_BASE_PREFIX", "1") == "0":
        return 0
    with locked("waiting: game start waits for the base prefix"):
        if os.path.isfile(os.path.join(compat, "version")):
            return 0
        mark = bring_up_to_date(proton_dir)
        if mark is None:
            return 0
        say("copy start: %d redist step(s) %s" % (len(mark["steps"]), ", ".join(mark["steps"]) or "-"))
        copy_into(compat)
        # DirectX neither installed nor recorded in the base (a base from an older version):
        # record it in the game now / 기본 prefix에 DirectX 설치도 기록도 없음(이전 버전의 기본
        # prefix): 지금 게임에 기록함
        todo = [s for s in late_todo(mark) if s["id"] not in mark.get("marked", [])]
        if todo:
            write_hasrun(proton_dir, compat, [(s["hasrun"], s["name"]) for s in todo if s["hasrun"]])
            wait_server(proton_dir, compat)
            say("copy: DirectX recorded (%d step(s))" % len(todo))
    return 0


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if not ((mode == "ensure" and len(sys.argv) == 4) or (mode in ("prepare", "directx") and len(sys.argv) in (2, 3))):
        print("usage: ezs-baseprefix.py prepare|directx [<Proton dir>] | ensure <compat data dir> <Proton dir>",
              file=sys.stderr)
        return 2
    global GATE
    # The app's own runs wait for room; a game start is not held up
    # 앱 자체 실행만 여유를 기다림. 게임 시작은 막지 않음
    GATE = mode in ("prepare", "directx")
    if GATE:
        read_test_switch()
        import threading
        threading.Thread(target=sample_peaks, daemon=True).start()
    try:
        if mode == "prepare":
            return prepare(sys.argv[2] if len(sys.argv) == 3 else find_proton())
        if mode == "directx":
            return directx(sys.argv[2] if len(sys.argv) == 3 else find_proton())
        return ensure(sys.argv[2], sys.argv[3])
    except Exception as e:  # never block the game: Proton makes the prefix itself then
        say("error, Proton makes the prefix itself: %r" % (e,))
        return 0
    finally:
        if GATE:
            say("procs: run peak %d" % max(peak_run, own_procs()))


if __name__ == "__main__":
    sys.exit(main())
