#!/usr/bin/env python3
# Convert X11 window dumps (xwd, ZPixmap, 24/32 bits per pixel) to one PNG, side by side, with the
# standard library only. Prints the share of non-black pixels in the cube area (0,0 500x500) and
# in the reference clock area (760,160 400x400) of each dump.
#   xwd2png.py <out.png> <in1.xwd> [in2.xwd ...]
# X11 창 덤프(xwd, ZPixmap, 픽셀당 24/32비트)를 표준 라이브러리만으로 PNG 하나에 나란히 붙임.
# 각 덤프의 큐브 영역(0,0 500x500)과 기준 시계 영역(760,160 400x400)의 검지 않은 픽셀 비율을 출력함
import os
import struct
import sys
import zlib


def read_xwd(src):
    """Return (width, height, rows of RGB bytes) / (폭, 높이, RGB 바이트 줄 목록)을 돌려줌"""
    d = open(src, "rb").read()
    # Header: 25 big-endian CARD32 fields, then the window name, then the color map
    # 헤더: 빅엔디언 CARD32 25개, 이어서 창 이름, 컬러맵
    h = struct.unpack(">25I", d[:100])
    header_size, version, fmt, w, hgt = h[0], h[1], h[2], h[4], h[5]
    byte_order, bpp, bpl = h[7], h[11], h[12]
    red_mask, green_mask, blue_mask, ncolors = h[14], h[15], h[16], h[19]
    if version != 7 or fmt != 2 or bpp not in (24, 32):
        raise SystemExit(f"unsupported xwd: version={version} format={fmt} bpp={bpp}")
    data = d[header_size + ncolors * 12:]

    def shift(mask):
        s = 0
        while mask and not (mask >> s) & 1:
            s += 1
        return s

    rs, gs, bs = shift(red_mask), shift(green_mask), shift(blue_mask)
    step = bpp // 8
    endian = "little" if byte_order == 0 else "big"
    rows = []
    for y in range(hgt):
        line = data[y * bpl:y * bpl + w * step]
        out = bytearray()
        for x in range(0, len(line), step):
            p = int.from_bytes(line[x:x + step], endian)
            out += bytes(((p & red_mask) >> rs, (p & green_mask) >> gs, (p & blue_mask) >> bs))
        rows.append(bytes(out))
    return w, hgt, rows


def nonblack(rows, x0, y0, w, h):
    """Percent of pixels that are not black in a region / 영역 안 검지 않은 픽셀 비율(%)"""
    n = total = 0
    for y in range(y0, min(y0 + h, len(rows))):
        r = rows[y]
        for x in range(x0, min(x0 + w, len(r) // 3)):
            total += 1
            if r[3 * x] or r[3 * x + 1] or r[3 * x + 2]:
                n += 1
    return 100.0 * n / total if total else 0.0


def write_png(dst, w, h, rows):
    def chunk(kind, body):
        c = kind + body
        return struct.pack(">I", len(body)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    raw = b"".join(b"\x00" + r for r in rows)  # filter type 0 per row / 줄마다 필터 0
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b"")
    open(dst, "wb").write(png)


def main():
    dst, srcs = sys.argv[1], [s for s in sys.argv[2:] if os.path.isfile(s) and os.path.getsize(s) > 0]
    if not srcs:
        print("FAIL shot : xwd 캡처 없음")
        return 1
    images = []
    for s in srcs:
        w, h, rows = read_xwd(s)
        name = os.path.basename(s)
        print(f"INFO pixels : {name} cube {nonblack(rows, 0, 0, 500, 500):.1f}% / clock {nonblack(rows, 760, 160, 400, 400):.1f}%")
        images.append((w, h, rows))
    # Side by side (same height) / 나란히(같은 높이)
    h = max(i[1] for i in images)
    w = sum(i[0] for i in images)
    rows = []
    for y in range(h):
        rows.append(b"".join(i[2][y] if y < i[1] else bytes(3 * i[0]) for i in images))
    write_png(dst, w, h, rows)
    return 0


if __name__ == "__main__":
    sys.exit(main())
