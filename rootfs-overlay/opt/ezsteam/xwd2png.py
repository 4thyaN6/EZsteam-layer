#!/usr/bin/env python3
# Convert an X11 window dump (xwd, ZPixmap, 24/32 bits per pixel) to PNG with the standard library.
# X11 창 덤프(xwd, ZPixmap, 픽셀당 24/32비트)를 표준 라이브러리만으로 PNG로 바꿈
import struct
import sys
import zlib


def convert(src, dst):
    d = open(src, "rb").read()
    # Header: 25 big-endian CARD32 fields, then the window name, then the color map
    # 헤더: 빅엔디언 CARD32 25개, 이어서 창 이름, 컬러맵
    h = struct.unpack(">25I", d[:100])
    header_size, version, fmt, depth, w, hgt = h[0], h[1], h[2], h[3], h[4], h[5]
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
    rows = bytearray()
    for y in range(hgt):
        line = data[y * bpl:y * bpl + w * step]
        rows.append(0)  # PNG filter type 0 / PNG 필터 0
        for x in range(0, len(line), step):
            p = int.from_bytes(line[x:x + step], endian)
            rows += bytes(((p & red_mask) >> rs, (p & green_mask) >> gs, (p & blue_mask) >> bs))

    def chunk(kind, body):
        c = kind + body
        return struct.pack(">I", len(body)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, hgt, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(rows), 6)) + chunk(b"IEND", b"")
    open(dst, "wb").write(png)


if __name__ == "__main__":
    convert(sys.argv[1], sys.argv[2])
