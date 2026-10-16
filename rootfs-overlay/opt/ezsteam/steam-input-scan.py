#!/usr/bin/env python3
# Static look at how the Steam client binaries reach input devices (diagnosis only, changes nothing):
# for the client and steamclient.so, the libraries they need (ELF DT_NEEDED) and how many times
# input-related words appear in them (hidraw, uinput, udev, SDL, Steam Deck controller ids).
# Steam 클라이언트 바이너리가 입력 장치에 닿는 방식을 정적으로 봄(진단 전용, 아무것도 바꾸지 않음):
# 클라이언트와 steamclient.so마다 필요한 라이브러리(ELF DT_NEEDED)와 입력 관련 단어(hidraw, uinput,
# udev, SDL, Steam Deck 컨트롤러 id)가 몇 번 나오는지.
#
#   python3 steam-input-scan.py <Steam dir> <output file>
#
# Only counts and the few matching words are written, not file contents.
# 횟수와 맞은 단어 몇 개만 쓰고 파일 내용은 쓰지 않음.
import os
import re
import struct
import sys

WORDS = [
    b"/dev/hidraw", b"/dev/uinput", b"/dev/input/event", b"/dev/input/js", b"/sys/class/hidraw",
    b"udev_enumerate_scan_devices", b"udev_monitor_new_from_netlink", b"libudev.so",
    b"SDL_Init", b"SDL_JoystickOpen", b"SDL_HINT_JOYSTICK", b"SDL_JOYSTICK_DISABLE_UDEV",
    b"libSDL2", b"libSDL3", b"hid_enumerate", b"HIDIOCGFEATURE", b"UI_DEV_CREATE",
    b"Steam Deck", b"28de", b"0x1205", b"gamepadui", b"steamdeck",
]


def needed(path):
    """DT_NEEDED names of a 64-bit little-endian ELF file / 64비트 리틀 엔디언 ELF의 DT_NEEDED 이름"""
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        return ["(not a 64-bit LE ELF)"]
    phoff, = struct.unpack_from("<Q", data, 0x20)
    phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
    loads, dyn = [], None
    for i in range(phnum):
        p_type, _, p_offset, p_vaddr, _, p_filesz, _, _ = struct.unpack_from(
            "<IIQQQQQQ", data, phoff + i * phentsize)
        if p_type == 1:
            loads.append((p_vaddr, p_offset, p_filesz))
        elif p_type == 2:
            dyn = (p_offset, p_filesz)
    if not dyn:
        return ["(static)"]

    def off(vaddr):
        for va, fo, sz in loads:
            if va <= vaddr < va + sz:
                return fo + vaddr - va
        return None

    strtab, names = None, []
    for i in range(dyn[1] // 16):
        tag, val = struct.unpack_from("<qQ", data, dyn[0] + i * 16)
        if tag == 0:
            break
        if tag == 5:
            strtab = off(val)
        elif tag == 1:
            names.append(val)
    out = []
    for n in names:
        if strtab is None:
            break
        s = strtab + n
        out.append(data[s:data.index(b"\0", s)].decode("utf-8", "replace"))
    return out


def scan(path, out):
    out.write("== %s (%d bytes)\n" % (path, os.path.getsize(path)))
    try:
        out.write("NEEDED: %s\n" % ", ".join(needed(path)))
    except Exception as e:  # noqa: BLE001 - diagnosis keeps going / 진단은 계속함
        out.write("NEEDED: error %s\n" % e)
    with open(path, "rb") as f:
        data = f.read()
    for w in WORDS:
        n = data.count(w)
        if n:
            out.write("  %-32s %d\n" % (w.decode(), n))
    # Device paths written in the binary, at most 20 / 바이너리에 적힌 장치 경로, 최대 20개
    paths = sorted(set(re.findall(rb"/(?:dev|sys|run/udev)/[A-Za-z0-9_./%-]{2,60}", data)))[:20]
    for p in paths:
        out.write("  path %s\n" % p.decode("ascii", "replace"))


def main():
    steam, dest = sys.argv[1], sys.argv[2]
    files = [os.path.join(steam, "steamrtarm64", "steam")]
    for root, _dirs, names in os.walk(steam):
        depth = root[len(steam):].count(os.sep)
        if depth > 2:
            continue
        for n in names:
            if n in ("steamclient.so", "libSDL3.so.0", "libSDL2-2.0.so.0", "libudev.so.1"):
                files.append(os.path.join(root, n))
    with open(dest, "w") as out:
        for p in files:
            if os.path.isfile(p):
                scan(p, out)


if __name__ == "__main__":
    main()
