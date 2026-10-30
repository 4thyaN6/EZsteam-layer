#!/usr/bin/env python3
# Library scan in one process, in place of shell pipes that started many short programs.
# Why: before each Steam start, steam-run.sh and steam-deps.sh ran "find | xargs -n1 dirname | sort |
# paste" (one dirname per library file) and, per ELF file, "od | tr" and the ldd shell script: up to
# 6 extra processes at once and hundreds in a few seconds (process list 2026-10-30 20:17:18), against
# the Android limit of 32 child processes for all apps together. Same results, one python3 process
# plus at most one loader run at a time.
# 라이브러리 검사를 프로세스 하나로 함. 짧은 프로그램을 많이 띄우던 셸 파이프를 대신함.
# 이유: Steam 시작 전마다 steam-run.sh와 steam-deps.sh가 "find | xargs -n1 dirname | sort | paste"(라이브러리
# 파일마다 dirname 하나)와 ELF 파일마다 "od | tr"·ldd 셸 스크립트를 돌렸음: 한 번에 최대 6개, 몇 초 동안
# 수백 개(프로세스 목록 2026-10-30 20:17:18). 안드로이드 한도는 모든 앱 합계 자식 32개. 결과는 같고,
# python3 하나 + 한 번에 로더 실행 하나뿐임
#
#   ezs-libscan.py libdirs DEPTH DIR...            folders holding *.so* files, joined with ":"
#                                                  *.so* 파일이 있는 폴더들을 ":"로 이음
#   ezs-libscan.py missing LIBDIRS FILE...         sonames the files need but are not found, one per line
#                                                  파일들이 필요로 하나 찾지 못한 soname, 한 줄에 하나
#   ezs-libscan.py scan DEPTH DIR...               "missing" over the ELF files steam-deps.sh checks
#                                                  steam-deps.sh가 검사하는 ELF 파일 전체에 대한 "missing"
import fnmatch
import os
import subprocess
import sys

# glibc's dynamic loader: "--list" prints what ldd prints (ldd is a shell script around it)
# glibc 동적 로더: "--list"는 ldd와 같은 내용을 출력함(ldd는 이것을 감싼 셸 스크립트)
LOADER = "/lib/ld-linux-aarch64.so.1"


def walk(dirs, depth):
    """Files down to DEPTH levels like find -maxdepth (a file directly in DIR is level 1)
    find -maxdepth처럼 DEPTH 단계까지의 파일(DIR 바로 아래 파일이 1단계)"""
    for top in dirs:
        top = top.rstrip("/") or "/"
        base = top.count("/")
        for root, subdirs, files in os.walk(top):
            level = root.rstrip("/").count("/") - base + 1
            if level >= depth:
                subdirs[:] = []
            if level > depth:
                continue
            for f in files:
                p = os.path.join(root, f)
                if os.path.isfile(p) and not os.path.islink(p):
                    yield p


def libdirs(depth, dirs):
    seen = []
    for p in walk(dirs, depth):
        if fnmatch.fnmatch(os.path.basename(p), "*.so*"):
            d = os.path.dirname(p)
            if d not in seen:
                seen.append(d)
    # sort -u order, no empty entry (an empty entry would mean the current folder)
    # sort -u 순서, 빈 항목 없음(빈 항목은 현재 폴더를 뜻함)
    return ":".join(sorted(seen))


def is_elf(p):
    try:
        with open(p, "rb") as f:
            return f.read(4) == b"\x7fELF"
    except OSError:
        return False


def missing(dirs_path, files):
    env = dict(os.environ)
    if dirs_path:
        env["LD_LIBRARY_PATH"] = dirs_path
    out = set()
    for f in files:
        try:
            r = subprocess.run([LOADER, "--list", f], env=env, stdout=subprocess.PIPE,
                               stderr=subprocess.DEVNULL, timeout=30)
        except (OSError, subprocess.TimeoutExpired):
            continue
        for line in r.stdout.decode("utf-8", "replace").splitlines():
            if "not found" in line:
                out.add(line.split()[0])
    return sorted(out)


def scan(depth, dirs):
    # Same names as steam-deps.sh checked before / steam-deps.sh가 전에 검사하던 이름과 같음
    pats = ("*.so*", "steam", "steamwebhelper", "*query", "steamsysinfo")
    files = [p for p in walk(dirs, depth)
             if any(fnmatch.fnmatch(os.path.basename(p), x) for x in pats) and is_elf(p)]
    return missing(libdirs(depth, dirs), files)


def main(argv):
    if len(argv) < 3:
        print(__doc__ or "usage: ezs-libscan.py libdirs|missing|scan ...", file=sys.stderr)
        return 2
    cmd = argv[1]
    if cmd == "libdirs":
        print(libdirs(int(argv[2]), argv[3:]))
    elif cmd == "missing":
        for s in missing(argv[2], argv[3:]):
            print(s)
    elif cmd == "scan":
        for s in scan(int(argv[2]), argv[3:]):
            print(s)
    else:
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
