#!/usr/bin/env python3
# Keeps the x86_64 files of Valve's ARM64 Steam client out of its arm64 folder.
# Why: the ARM64 client also installs a "steamrt64/" tree of x86_64 files (its packages
# *_steamrt_linuxarm64). steam-install.sh used to make steamrt64 a link to steamrtarm64, so those
# files were unpacked over the arm64 files of the same name. Client 1794424036 (2026-11-12) wrote
# x86_64 libGLESv2.so, libEGL.so and libvk_swiftshader.so into steamrtarm64; the web helper could not
# load ANGLE and the Steam screen never came up. The manifest lists 107 such names, steam,
# steamclient.so, steamui.so and libcef.so among them. The ARM64 client itself only uses steamrtarm64.
# Valve ARM64 Steam 클라이언트의 x86_64 파일이 arm64 폴더에 들어가지 않게 함.
# 이유: ARM64 클라이언트는 x86_64 파일로 된 "steamrt64/" 트리도 설치함(패키지 *_steamrt_linuxarm64).
# steam-install.sh가 steamrt64를 steamrtarm64로의 링크로 만들어서, 그 파일이 같은 이름의 arm64 파일 위에
# 풀렸음. 클라이언트 1794424036(2026-11-12)이 x86_64 libGLESv2.so, libEGL.so, libvk_swiftshader.so를
# steamrtarm64에 썼고, 웹 도우미가 ANGLE을 불러오지 못해 Steam 화면이 뜨지 않았음. 매니페스트에 이런 이름이
# 107개 있고 steam, steamclient.so, steamui.so, libcef.so도 들어 있음. ARM64 클라이언트 자신은 steamrtarm64만 씀
#
#   ezs-steamrt.py fix STEAM_DIR     steamrt64 link -> real folder; files the manifest lists only under
#                                    steamrt64 move there out of steamrtarm64 (rename, no copy)
#                                    steamrt64 링크를 실제 폴더로 바꿈. 매니페스트에 steamrt64에만 있는 파일을
#                                    steamrtarm64에서 그리로 옮김(이름 바꾸기, 복사 없음)
#   ezs-steamrt.py check STEAM_DIR   one line "COUNT VERSION NAME..." when ELF files the manifest lists under
#                                    steamrtarm64 are not aarch64; nothing when all are
#                                    매니페스트의 steamrtarm64 ELF 파일 중 aarch64가 아닌 것이 있으면
#                                    "개수 버전 이름..." 한 줄, 모두 맞으면 출력 없음
import glob
import os
import re
import sys

ARM = "steamrtarm64"
X86 = "steamrt64"
EM_AARCH64 = 0xB7


def manifest(steam_dir):
    """Newest installed-files list of the client, or None.
    Each line: "path,size;mtime;crc" (size -1 = folder)
    클라이언트의 가장 최근 설치 파일 목록, 없으면 None. 줄마다 "경로,크기;시각;crc"(크기 -1 = 폴더)"""
    found = glob.glob(os.path.join(steam_dir, "package", "steam_client*linuxarm64.installed"))
    return max(found, key=os.path.getmtime) if found else None


def entries(path):
    """{tree: {relative path: size}} for the two client folders
    두 클라이언트 폴더의 {트리: {상대 경로: 크기}}"""
    out = {ARM: {}, X86: {}}
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            name, _, rest = line.rstrip("\n").partition(",")
            top, _, rel = name.partition("/")
            if top not in out or not rel:
                continue
            try:
                size = int(rest.split(";", 1)[0])
            except ValueError:
                continue
            out[top][rel.rstrip("/")] = size
    return out


def fix(steam_dir):
    x86 = os.path.join(steam_dir, X86)
    arm = os.path.join(steam_dir, ARM)
    # A move left half done (stopped between removing the link and renaming) is finished here
    # 링크를 지우고 이름을 바꾸기 전에 멈춘 이전 작업은 여기서 마무리함
    new = x86 + ".ezs-new"
    if not os.path.islink(x86):
        if not os.path.lexists(x86):
            if os.path.isdir(new):
                os.rename(new, x86)
            else:
                os.makedirs(x86, exist_ok=True)
            print("INFO steamrt64 : 실제 폴더 만듦")
        return
    moved = 0
    m = manifest(steam_dir)
    if m and os.path.isdir(arm):
        e = entries(m)
        only_x86 = {p: s for p, s in e[X86].items() if p not in e[ARM]}
        for rel, size in sorted(only_x86.items()):
            if size == -1:
                continue
            src = os.path.join(arm, rel)
            if not os.path.lexists(src) or (os.path.isdir(src) and not os.path.islink(src)):
                continue
            dst = os.path.join(new, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            os.replace(src, dst)
            moved += 1
        # Folders of the x86_64 tree left empty in steamrtarm64, deepest first
        # steamrtarm64에 비어 남은 x86_64 트리 폴더, 깊은 것부터
        for rel in sorted((p for p, s in only_x86.items() if s == -1), key=lambda p: -p.count("/")):
            try:
                os.rmdir(os.path.join(arm, rel))
            except OSError:
                pass
    os.makedirs(new, exist_ok=True)
    os.unlink(x86)
    os.rename(new, x86)
    print(f"INFO steamrt64 : 링크(→ {ARM})를 실제 폴더로 바꿈, x86_64 전용 파일 {moved}개를 {ARM}에서 옮김"
          + ("" if m else " (설치 파일 목록 없음)"))


def machine(path):
    """ELF e_machine, or None for a file that is not ELF
    ELF e_machine 값, ELF가 아니면 None"""
    try:
        with open(path, "rb") as f:
            h = f.read(20)
    except OSError:
        return None
    if len(h) < 20 or h[:4] != b"\x7fELF":
        return None
    return int.from_bytes(h[18:20], "little" if h[5] == 1 else "big")


def check(steam_dir):
    m = manifest(steam_dir)
    if not m:
        return
    arm = os.path.join(steam_dir, ARM)
    bad = []
    for rel, size in sorted(entries(m)[ARM].items()):
        if size <= 0:
            continue
        mach = machine(os.path.join(arm, rel))
        if mach is not None and mach != EM_AARCH64:
            bad.append(rel)
    if not bad:
        return
    version = "?"
    try:
        with open(m[: -len(".installed")] + ".manifest", encoding="utf-8", errors="replace") as f:
            v = re.search(r'"version"\s+"([^"]+)"', f.read())
            version = v.group(1) if v else "?"
    except OSError:
        pass
    print(len(bad), version, " ".join(bad[:8]) + (" ..." if len(bad) > 8 else ""))


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in ("fix", "check"):
        print("usage: ezs-steamrt.py fix|check STEAM_DIR", file=sys.stderr)
        return 2
    (fix if sys.argv[1] == "fix" else check)(sys.argv[2])
    return 0


if __name__ == "__main__":
    sys.exit(main())
