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
    Steamworks Shared (appid 228980) fully downloaded in this library: its install record says
    StateFlags 4 (installed, nothing pending). Installers are not run from a download in progress.
    No record (folder copied by hand) counts as ready.
    이 라이브러리에 Steamworks Shared(appid 228980)가 다 받아졌는지: 설치 기록의 StateFlags가 4(설치됨,
    남은 작업 없음). 받는 중인 파일로 설치 프로그램을 돌리지 않음. 기록이 없으면(직접 복사한 폴더) 준비된 것으로 봄
    """
    acf = os.path.join(lib, "appmanifest_228980.acf")
    try:
        with open(acf, encoding="utf-8", errors="replace") as f:
            m = re.search(r'"StateFlags"\s+"(\d+)"', f.read())
    except OSError:
        return True
    return m is None or m.group(1) == "4"


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
    write_mark({"proton": version, "steps": [], "failed": [], "built": time.strftime("%Y-%m-%d %H:%M:%S")})
    say("prefix ready")
    return True


def add_redists(proton_dir, mark):
    """Run the redistributable steps the base does not have yet / 기본 prefix에 아직 없는 재배포 단계를 실행함"""
    steps = redist_steps()
    todo = [s for s in steps if s["id"] not in mark["steps"] and s["id"] not in mark.get("failed", [])]
    if not steps:
        say("redist: Steamworks Shared 없음 (재배포 없이 사용)")
    for s in todo:
        t0 = time.time()
        say("redist start: " + s["id"])
        ok = True
        for exe, args in s["processes"]:
            path = unix_exe(s["installdir"], exe)
            if path is None:
                say("redist skip: file not found " + exe)
                ok = False
                break
            rc = proton_run(proton_dir, BASE, [path] + args.split())
            # Same rule as Steam: exit code 0 = done / Steam과 같은 규칙: exit 코드 0 = 완료
            if rc != 0:
                say("redist failed: %s rc %d" % (exe, rc))
                ok = False
                break
        if ok and (not s["hasrun"] or write_hasrun(proton_dir, BASE, [(s["hasrun"], s["name"])])):
            mark["steps"].append(s["id"])
            say("redist done: %s (%.0f s, %s \\ %s = 1)" % (s["id"], time.time() - t0, s["hasrun"] or "-", s["name"]))
        else:
            # Failed steps are left to Steam's own install in each game, as before
            # 실패한 단계는 지금처럼 게임마다 Steam이 설치하게 둠
            mark.setdefault("failed", []).append(s["id"])
        wait_server(proton_dir, BASE)
        write_mark(mark)


def copy_into(compat):
    """Copy the base into the game's compat data folder / 기본 prefix를 게임 compat data 폴더로 복사함"""
    t0 = time.time()
    # Unfinished prefix (no version file): nothing of the game can be in it yet
    # 완성되지 않은 prefix(version 파일 없음): 게임 것이 아직 있을 수 없음
    for name in os.listdir(compat):
        p = os.path.join(compat, name)
        if os.path.isdir(p) and not os.path.islink(p):
            shutil.rmtree(p, ignore_errors=True)
        else:
            try:
                os.unlink(p)
            except OSError:
                pass
    for name in os.listdir(BASE):
        if name == MARK or name.endswith(".lock"):
            continue
        src, dst = os.path.join(BASE, name), os.path.join(compat, name)
        if os.path.isdir(src) and not os.path.islink(src):
            shutil.copytree(src, dst, symlinks=True)
        else:
            shutil.copy2(src, dst, follow_symlinks=False)
    # Paths of the base written in Proton's own files point to the game folder instead
    # Proton 자체 파일에 적힌 기본 prefix 경로를 게임 폴더로 바꿈
    for name in ("tracked_files", "config_info", "pfx/system.reg", "pfx/user.reg", "pfx/userdef.reg"):
        p = os.path.join(compat, name)
        try:
            with open(p, encoding="utf-8", errors="surrogateescape") as f:
                text = f.read()
        except OSError:
            continue
        new = text.replace(BASE, compat)
        # New machine id per prefix, as Proton makes for each new prefix
        # Proton이 새 prefix마다 만드는 것처럼 prefix마다 새 기기 id
        if name == "pfx/system.reg":
            new = re.sub(r'("MachineGuid"=")[0-9a-fA-F-]{36}(")', lambda m: m.group(1) + str(uuid.uuid4()) + m.group(2), new)
        if new != text:
            with open(p, "w", encoding="utf-8", errors="surrogateescape") as f:
                f.write(new)
    # Recorded redistributable keys now in the game prefix (check that Steam can see them)
    # 게임 prefix에 들어간 재배포 기록 키 수(Steam이 볼 수 있는지 확인용)
    keys = 0
    try:
        with open(os.path.join(compat, "pfx", "system.reg"), encoding="utf-8", errors="replace") as f:
            keys = sum(1 for line in f if line.startswith("[") and "commonredist" in line.lower())
    except OSError:
        pass
    say("copy done: %s (%.1f s, CommonRedist keys %d)" % (compat, time.time() - t0, keys))


def bring_up_to_date(proton_dir):
    """Build or rebuild the base for this Proton and add new redistributables; caller holds the lock.
    Returns the mark, or None when the base could not be made.
    이 Proton용 기본 prefix를 만들거나 다시 만들고 새 재배포를 더함. 호출한 쪽이 잠금을 잡고 있음.
    표식을 돌려줌. 기본 prefix를 못 만들면 None"""
    version = proton_version(proton_dir)
    mark = read_mark()
    if mark is None or mark.get("proton") != version or not os.path.isfile(os.path.join(BASE, "version")):
        if mark is not None:
            say("rebuild: Proton %s -> %s" % (mark.get("proton"), version))
        if not build(proton_dir, version):
            return None
        mark = read_mark()
    add_redists(proton_dir, mark)
    return mark


def locked():
    """Exclusive lock on the base (one builder at a time; a game start waits for a running prepare)
    기본 prefix의 단독 잠금(한 번에 하나만. 게임 시작은 돌고 있는 prepare를 기다림)"""
    os.makedirs(os.path.dirname(BASE), exist_ok=True)
    lock = open(BASE + ".lock", "w")
    fcntl.flock(lock, fcntl.LOCK_EX)
    return lock


def prepare(proton_dir):
    if not proton_dir or not os.path.isfile(os.path.join(proton_dir, "proton")):
        say("prepare: Proton not installed yet")
        return 0
    with locked():
        say("prepare start: Proton " + proton_version(proton_dir))
        mark = bring_up_to_date(proton_dir)
        if mark is not None:
            say("prepare done: %d redist step(s) %s" % (len(mark["steps"]), ", ".join(mark["steps"]) or "-"))
    return 0


def ensure(compat, proton_dir):
    if os.path.isfile(os.path.join(compat, "version")):
        return 0
    if os.environ.get("EZS_BASE_PREFIX", "1") == "0":
        return 0
    with locked():
        if os.path.isfile(os.path.join(compat, "version")):
            return 0
        mark = bring_up_to_date(proton_dir)
        if mark is None:
            return 0
        say("copy start: %d redist step(s) %s" % (len(mark["steps"]), ", ".join(mark["steps"]) or "-"))
        copy_into(compat)
    return 0


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if not ((mode == "ensure" and len(sys.argv) == 4) or (mode == "prepare" and len(sys.argv) in (2, 3))):
        print("usage: ezs-baseprefix.py prepare [<Proton dir>] | ensure <compat data dir> <Proton dir>",
              file=sys.stderr)
        return 2
    try:
        if mode == "prepare":
            return prepare(sys.argv[2] if len(sys.argv) == 3 else find_proton())
        return ensure(sys.argv[2], sys.argv[3])
    except Exception as e:  # never block the game: Proton makes the prefix itself then
        say("error, Proton makes the prefix itself: %r" % (e,))
        return 0


if __name__ == "__main__":
    sys.exit(main())
