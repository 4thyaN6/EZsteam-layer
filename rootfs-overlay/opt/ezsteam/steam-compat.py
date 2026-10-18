#!/usr/bin/env python3
# Make EZ Proton the compatibility tool of every Windows game, before the client starts.
# 클라이언트 시작 전에 모든 Windows 게임의 호환성 도구를 EZ Proton으로 맞춤.
#
# Why: games of the shared EZlator library had no tool chosen, so the client used its default
# (Valve's Proton through its container) and every start stopped at once with
# "pressure-vessel-wrap: E: opendir(/proc/self/root): No such file or directory" (steam.log,
# 2026-10-18). The container cannot run under PRoot; EZ Proton starts the same Valve Proton
# without it (compat/ezproton).
# 이유: 공유 EZlator 라이브러리 게임에는 도구가 정해져 있지 않아 클라이언트가 기본값(컨테이너를 거치는
# Valve Proton)을 썼고, 실행할 때마다 "pressure-vessel-wrap: E: opendir(/proc/self/root): No such file
# or directory"로 바로 멈췄음(steam.log, 2026-10-18). 이 컨테이너는 PRoot에서 돌지 못함. EZ Proton은
# 같은 Valve Proton을 컨테이너 없이 시작함(compat/ezproton).
#
#   python3 steam-compat.py <Steam dir>
#
# <Steam>/config/config.vdf, InstallConfigStore/Software/Valve/Steam/CompatToolMapping:
#   "0" (the default for all titles) and every per-game entry naming another tool -> "ezproton".
#   Entries already on ezproton are left alone. Nothing happens before the client's first run (no
#   config.vdf yet).
#   "0"(모든 게임의 기본값)과 다른 도구를 가리키는 게임별 항목을 "ezproton"으로 바꿈. 이미 ezproton인
#   항목은 그대로 둠. 클라이언트 첫 실행 전에는(config.vdf가 없음) 아무것도 하지 않음.
import importlib.util
import os
import sys

TOOL = "ezproton"


def vdf():
    """parse/dump shared with steam-library.py / steam-library.py와 같은 parse/dump"""
    here = os.path.dirname(os.path.abspath(__file__))
    spec = importlib.util.spec_from_file_location("ezs_vdf", os.path.join(here, "steam-library.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def child(d, name):
    """Sub-dict by key, any letter case, made when missing / 대소문자 무시로 하위 dict, 없으면 만듦"""
    for k, v in d.items():
        if k.lower() == name.lower() and isinstance(v, dict):
            return v
    d[name] = {}
    return d[name]


def libraries(steam, v):
    """
    Diagnosis: each library the client lists, with how many appmanifests it really holds (a game
    in the rootfs library showed as not installed, user report 2026-10-18)
    진단: 클라이언트가 등록한 라이브러리마다 실제 appmanifest 수(rootfs 라이브러리의 게임이 설치 안 됨으로
    보였다는 사용자 보고, 2026-10-18)
    """
    path = os.path.join(steam, "config", "libraryfolders.vdf")
    if not os.path.isfile(path):
        return
    with open(path, encoding="utf-8", errors="replace") as f:
        folders = v.parse(f.read()).get("libraryfolders", {})
    for key, lib in folders.items():
        if not isinstance(lib, dict):
            continue
        apps_dir = os.path.join(lib.get("path", ""), "steamapps")
        try:
            have = sorted(n[12:-4] for n in os.listdir(apps_dir) if n.startswith("appmanifest_") and n.endswith(".acf"))
        except OSError as e:
            print("WARN library : [%s] %s 읽기 실패 (%s)" % (key, lib.get("path"), e.strerror))
            continue
        listed = sorted(lib.get("apps", {}).keys()) if isinstance(lib.get("apps"), dict) else []
        print("INFO library : [%s] %s appmanifest %d개 %s, 목록 %d개" % (
            key, lib.get("path"), len(have), " ".join(have), len(listed)))


def main():
    steam = sys.argv[1]
    libraries(steam, vdf())
    path = os.path.join(steam, "config", "config.vdf")
    if not os.path.isfile(path):
        print("INFO compat : config.vdf 없음 (첫 실행 전), 다음 시작부터 EZ Proton 기본값")
        return
    v = vdf()
    with open(path, encoding="utf-8", errors="replace") as f:
        data = v.parse(f.read())
    node = data
    for name in ("InstallConfigStore", "Software", "Valve", "Steam", "CompatToolMapping"):
        node = child(node, name)
    changed = []
    if "0" not in node:
        node["0"] = {"name": TOOL, "config": "", "priority": "75"}
        changed.append("기본값(없음)")
    for appid, entry in node.items():
        if not isinstance(entry, dict):
            continue
        old = entry.get("name", "")
        if old and old != TOOL:
            entry["name"] = TOOL
            changed.append("%s(%s)" % ("기본값" if appid == "0" else appid, old))
    if not changed:
        print("OK   compat : 호환성 도구 모두 EZ Proton")
        return
    tmp = path + ".ezs-tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(v.dump(data))
    os.replace(tmp, path)
    print("OK   compat : EZ Proton으로 바꿈: " + ", ".join(changed))


if __name__ == "__main__":
    main()
