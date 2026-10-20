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
#   Entries already on ezproton are left alone. Before the client's first sign-in there is no
#   config.vdf: then one holding only this mapping is written, because the first game started in
#   that session otherwise got the client's default (Proton Experimental), which made it download
#   Proton Experimental and the Steam Linux Runtime 4.0 container (content_log 2026-10-20)
#   "0"(모든 게임의 기본값)과 다른 도구를 가리키는 게임별 항목을 "ezproton"으로 바꿈. 이미 ezproton인
#   항목은 그대로 둠. 클라이언트 첫 로그인 전에는 config.vdf가 없음: 그때는 이 연결만 든 파일을 씀. 그렇지
#   않으면 그 세션에서 처음 시작한 게임이 클라이언트 기본값(Proton Experimental)을 받아 Proton Experimental과
#   Steam Linux Runtime 4.0 컨테이너를 받았음(content_log 2026-10-20)
import importlib.util
import os
import re
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
        # Game folders without an install record: the client sees them as not installed and
        # downloads again unless the install goes to this same library (user report 2026-10-18)
        # 설치 기록이 없는 게임 폴더: 클라이언트는 설치 안 됨으로 보며, 같은 라이브러리로 설치하지 않으면
        # 다시 받음(사용자 보고 2026-10-18)
        dirs = set()
        for n in have:
            try:
                with open(os.path.join(apps_dir, "appmanifest_%s.acf" % n), encoding="utf-8", errors="replace") as f:
                    d = v.parse(f.read()).get("AppState", {}).get("installdir")
                if d:
                    dirs.add(d)
            except OSError:
                pass
        try:
            orphans = sorted(d for d in os.listdir(os.path.join(apps_dir, "common"))
                             if d not in dirs and os.path.isdir(os.path.join(apps_dir, "common", d)))
        except OSError:
            orphans = []
        if orphans:
            print("INFO library : [%s] 설치 기록 없는 게임 폴더 %d개: %s" % (key, len(orphans), ", ".join(orphans)))


# IPv4 and IPv6 addresses in log text (masked before printing) / 로그 글의 IPv4·IPv6 주소(출력 전 가림)
IPV4 = re.compile(r"(?<![0-9.])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![0-9.])")
IPV6 = re.compile(r"\[?(?:[0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{0,4}\]?")


def network(steam):
    """
    Diagnosis of the download server choice, before the client starts (slow downloads: the
    client got a European cell or only outside CDNs, content_log.txt 2026-10-18):
    - download region / cell values in config/config.vdf (names containing "cell" or "region")
    - how the last session reached Steam's servers: IPv4 vs IPv6 address count in
      logs/connection_log.txt, and its lines naming a cell (every address masked as <ip>)
    - which address family getaddrinfo() gives first in the rootfs now (gai.conf effect)
    다운로드 서버 선택 진단, 클라이언트 시작 전(느린 다운로드: 클라이언트가 유럽 지역이나 외부 CDN만
    받았음, content_log.txt 2026-10-18):
    - config/config.vdf의 다운로드 지역·셀 값(이름에 "cell"이나 "region"이 든 것)
    - 지난 세션이 Steam 서버에 닿은 방식: logs/connection_log.txt의 IPv4·IPv6 주소 수와 셀이 적힌 줄
      (모든 주소는 <ip>로 가림)
    - 지금 rootfs에서 getaddrinfo()가 먼저 내놓는 주소 종류(gai.conf 효과)
    """
    path = os.path.join(steam, "config", "config.vdf")
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            found = re.findall(r'"([^"]*(?:[Cc]ell|[Rr]egion)[^"]*)"\s+"([^"]*)"', f.read())
        print("INFO network : config.vdf 지역 값 " + (", ".join("%s=%s" % kv for kv in found) if found else "없음(자동)"))
    except OSError:
        pass
    path = os.path.join(steam, "logs", "connection_log.txt")
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            lines = f.readlines()[-400:]
        v4 = sum(len(IPV4.findall(l)) for l in lines)
        v6 = sum(len([m for m in IPV6.findall(l) if m.count(":") >= 3]) for l in lines)
        print("INFO network : 지난 접속 기록 주소 IPv4 %d개, IPv6 %d개 (connection_log 끝 400줄)" % (v4, v6))
        cells = [l.strip() for l in lines if re.search(r"cell", l, re.I)][-3:]
        for l in cells:
            l = IPV4.sub("<ip>", l)
            l = re.sub(r"\[?(?:[0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{0,4}\]?", "<ip>", l)
            print("INFO network : " + l[:200])
    except OSError:
        pass
    try:
        import socket
        fam = [a[0] for a in socket.getaddrinfo("cache1-seo1.steamcontent.com", 443, type=socket.SOCK_STREAM)]
        if fam:
            print("INFO network : 이름 찾기 첫 주소 %s (IPv4 %d개, IPv6 %d개)" % (
                "IPv4" if fam[0] == socket.AF_INET else "IPv6",
                fam.count(socket.AF_INET), fam.count(socket.AF_INET6)))
    except OSError as e:
        print("INFO network : 이름 찾기 실패 (%s)" % e)


def main():
    steam = sys.argv[1]
    libraries(steam, vdf())
    network(steam)
    path = os.path.join(steam, "config", "config.vdf")
    v = vdf()
    data = {}
    if os.path.isfile(path):
        with open(path, encoding="utf-8", errors="replace") as f:
            data = v.parse(f.read())
    elif os.path.isdir(steam):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        print("INFO compat : config.vdf 없음 (첫 로그인 전) → EZ Proton 기본값만 든 파일을 만듦")
    else:
        return
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
