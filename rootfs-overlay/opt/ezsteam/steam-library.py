#!/usr/bin/env python3
# Register a shared folder (EZlator's Steam library, bound by the app) as an extra Steam library
# folder of the Linux client, before the client starts. Test builds only; nothing is moved or
# deleted, only the library list gets one more entry.
# 공유 폴더(앱이 연결한 EZlator Steam 라이브러리)를 리눅스 클라이언트의 추가 Steam 라이브러리 폴더로
# 클라이언트 시작 전에 등록함. 시험 빌드 전용. 아무것도 옮기거나 지우지 않고 라이브러리 목록에 항목
# 하나만 더함.
#
#   python3 steam-library.py <Steam dir> <library path>
#
# - <library>/libraryfolder.vdf : the folder's own marker (contentid, label), made when missing
#   폴더 자체 표시 파일(contentid, label). 없으면 만듦
# - <Steam>/config/libraryfolders.vdf and <Steam>/steamapps/libraryfolders.vdf (whichever exist):
#   one entry with "path" = <library> added when no entry has that path; its "apps" list is filled
#   from the appmanifest_*.acf files already in <library>/steamapps
#   (둘 중 있는 것) "path"가 <library>인 항목이 없으면 하나 추가. "apps" 목록은 <library>/steamapps에
#   이미 있는 appmanifest_*.acf로 채움
# The client rewrites these files itself; before its first run they do not exist and nothing is
# added (registered from the next start).
# 클라이언트가 이 파일들을 스스로 다시 씀. 첫 실행 전에는 파일이 없어 추가하지 않음(다음 시작부터 등록).
import os
import random
import re
import sys

TOKEN = re.compile(r'"((?:[^"\\]|\\.)*)"|([{}])|//[^\n]*|\s+')


def parse(text):
    """VDF text -> nested dicts (keys keep their order) / VDF 글 -> 중첩 dict(키 순서 유지)"""
    stack = [{}]
    key = None
    for m in TOKEN.finditer(text):
        s, brace = m.group(1), m.group(2)
        if brace == "{":
            d = {}
            stack[-1][key] = d
            stack.append(d)
            key = None
        elif brace == "}":
            stack.pop()
        elif s is not None:
            # Kept as written (escapes not decoded), so dump() writes every value back byte for
            # byte (config.vdf holds values with backslash escapes)
            # 쓴 그대로 둠(이스케이프를 풀지 않음). 그래서 dump()가 모든 값을 바이트 그대로 다시 씀
            # (config.vdf에는 백슬래시 이스케이프가 든 값이 있음)
            if key is None:
                key = s
            else:
                stack[-1][key] = s
                key = None
    return stack[0]


def dump(d, depth=0):
    """Nested dicts -> VDF text with tabs, like the client writes / 클라이언트처럼 탭으로 쓴 VDF 글"""
    out = []
    pad = "\t" * depth
    for k, v in d.items():
        if isinstance(v, dict):
            out.append('%s"%s"\n%s{\n%s%s}\n' % (pad, k, pad, dump(v, depth + 1), pad))
        else:
            out.append('%s"%s"\t\t"%s"\n' % (pad, k, v))
    return "".join(out)


SIZE_LINE = re.compile(r'("SizeOnDisk"\s+")0(")')


def tree_size(path):
    """Bytes of all regular files under path (symbolic links not followed) / path 아래 일반 파일 바이트 합"""
    total = 0
    for root, _dirs, files in os.walk(path):
        for n in files:
            try:
                st = os.lstat(os.path.join(root, n))
            except OSError:
                continue
            if not os.path.islink(os.path.join(root, n)):
                total += st.st_size
    return total


def fill_sizes(lib):
    """
    Write the real size into appmanifests that say SizeOnDisk "0" (EZlator writes 0: its
    SteamGameInstaller.writeAppManifest; the client then showed every game as 0 B, user report).
    Only that one value is changed, once per game (later starts find a non-zero size).
    SizeOnDisk가 "0"인 appmanifest에 실제 크기를 씀(EZlator는 0을 씀: SteamGameInstaller.
    writeAppManifest. 그래서 클라이언트가 모든 게임을 0 B로 보여 줬음, 사용자 보고). 그 값 하나만 바꾸며
    게임마다 한 번만 함(다음 시작부터는 0이 아님)
    """
    apps_dir = os.path.join(lib, "steamapps")
    for name in sorted(os.listdir(apps_dir)) if os.path.isdir(apps_dir) else []:
        if not re.fullmatch(r"appmanifest_\d+\.acf", name):
            continue
        path = os.path.join(apps_dir, name)
        try:
            with open(path, encoding="utf-8", errors="replace") as f:
                text = f.read()
        except OSError:
            continue
        if not SIZE_LINE.search(text):
            continue
        state = parse(text).get("AppState", {})
        game = os.path.join(apps_dir, "common", state.get("installdir", ""))
        if not state.get("installdir") or not os.path.isdir(game):
            continue
        size = tree_size(game)
        if size <= 0:
            continue
        tmp = path + ".ezs-tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(SIZE_LINE.sub(lambda m: m.group(1) + str(size) + m.group(2), text, count=1))
        os.replace(tmp, path)
        print("OK   library : %s 크기 %.2f GB 기록 (SizeOnDisk 0)" % (state.get("name", name), size / 1e9))


def installed_apps(lib):
    """appid -> SizeOnDisk from the library's appmanifest files / 라이브러리 appmanifest의 appid -> 크기"""
    apps = {}
    apps_dir = os.path.join(lib, "steamapps")
    for name in sorted(os.listdir(apps_dir)) if os.path.isdir(apps_dir) else []:
        m = re.fullmatch(r"appmanifest_(\d+)\.acf", name)
        if not m:
            continue
        try:
            with open(os.path.join(apps_dir, name), encoding="utf-8", errors="replace") as f:
                state = parse(f.read()).get("AppState", {})
            apps[m.group(1)] = state.get("SizeOnDisk", "0")
        except OSError:
            continue
    return apps


def marker(lib):
    """contentid from <library>/libraryfolder.vdf, made when missing / 없으면 만들고 contentid를 돌려줌"""
    path = os.path.join(lib, "libraryfolder.vdf")
    if os.path.isfile(path):
        with open(path, encoding="utf-8", errors="replace") as f:
            cid = parse(f.read()).get("libraryfolder", {}).get("contentid")
        if cid:
            return cid
    cid = str(random.randint(10 ** 18, 9 * 10 ** 18))
    with open(path, "w", encoding="utf-8") as f:
        f.write(dump({"libraryfolder": {"contentid": cid, "label": "EZlator"}}))
    return cid


def main():
    steam, lib = sys.argv[1], sys.argv[2].rstrip("/")
    cid = marker(lib)
    fill_sizes(lib)
    apps = installed_apps(lib)
    lists = [os.path.join(steam, "config", "libraryfolders.vdf"),
             os.path.join(steam, "steamapps", "libraryfolders.vdf")]
    found = False
    for path in lists:
        if not os.path.isfile(path):
            continue
        found = True
        with open(path, encoding="utf-8", errors="replace") as f:
            data = parse(f.read())
        folders = data.setdefault("libraryfolders", {})
        mine = [v for v in folders.values() if isinstance(v, dict) and v.get("path", "").rstrip("/") == lib]
        if mine:
            # Already listed: refresh its game sizes / 이미 있음: 게임 크기만 새로 씀
            if mine[0].get("apps") != apps:
                mine[0]["apps"] = apps
                tmp = path + ".ezs-tmp"
                with open(tmp, "w", encoding="utf-8") as f:
                    f.write(dump(data))
                os.replace(tmp, path)
            print("OK   library : %s 이미 등록, 게임 %d개 (%s)" % (lib, len(apps), os.path.relpath(path, steam)))
            continue
        nums = [int(k) for k in folders if k.isdigit()]
        folders[str(max(nums) + 1 if nums else 0)] = {
            "path": lib, "label": "EZlator", "contentid": cid, "totalsize": "0",
            "update_clean_bytes_tally": "0", "time_last_update_verified": "0", "apps": apps,
        }
        tmp = path + ".ezs-tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(dump(data))
        os.replace(tmp, path)
        print("OK   library : %s 등록, 설치된 게임 %d개 (%s)" % (lib, len(apps), os.path.relpath(path, steam)))
    if not found:
        print("INFO library : 라이브러리 목록 파일 없음 (첫 실행 전), 다음 시작부터 %s 등록" % lib)


if __name__ == "__main__":
    main()
