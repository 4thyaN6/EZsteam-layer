#!/bin/sh
# Download / storage speed check inside the rootfs (PRoot), to tell whether slow Steam downloads come
# from the network or from file writes through PRoot. Uses the Steam client bootstrap zip on Valve's
# update server (about 100 MB, the same file [Steam 설치] uses) as the test download.
#   1. network only: download to /dev/null
#   2. network + file: download to a file in the Steam folder's file system
#   3. one large file write (256 MB), 4. many small files (2000 x 64 KB, like downloaded chunks)
# rootfs(PRoot) 안 다운로드·저장 속도 확인. Steam 다운로드가 느린 원인이 네트워크인지 PRoot를
# 거친 파일 쓰기인지 가리기 위함. 시험 파일은 Valve 업데이트 서버의 Steam 부트스트랩 zip
# (약 100 MB, [Steam 설치]가 받는 파일과 같음).
#   1. 네트워크만: /dev/null로 받기   2. 네트워크 + 파일: Steam 폴더와 같은 파일 시스템에 받기
#   3. 큰 파일 하나 쓰기(256 MB)   4. 작은 파일 여러 개(64 KB x 2000, 받은 조각과 비슷)
set -u
BASE=https://client-update.steamstatic.com
DIR=$HOME/.local/share/Steam/ezs-speedtest
mkdir -p "$DIR"

# Test file name from the manifest / 매니페스트에서 시험 파일 이름 읽기
ZIP=$(curl -fsSL "$BASE/steam_client_linuxarm64" | grep -oE 'bins_linuxarm64_linuxarm64\.zip\.[0-9a-f]+' | head -n 1)
[ -n "$ZIP" ] || { echo "FAIL net : 매니페스트를 받지 못함 (네트워크 확인)"; exit 0; }

# MB/s from bytes and seconds / 바이트와 초로 MB/s 계산
rate() { python3 -c "import sys; b,t=float(sys.argv[1]),float(sys.argv[2]); print(f'{b/1048576:.0f} MB, {t:.1f}s, {b/1048576/max(t,1e-3):.1f} MB/s')" "$1" "$2"; }

# 1. Network only / 네트워크만
r=$(curl -fsS -o /dev/null -w '%{size_download} %{time_total}' "$BASE/$ZIP") \
    && echo "OK   net : $(rate $r) (/dev/null)" || echo "FAIL net : 받기 실패"

# 2. Network + file / 네트워크 + 파일
r=$(curl -fsS -o "$DIR/dl.bin" -w '%{size_download} %{time_total}' "$BASE/$ZIP") \
    && echo "OK   net+file : $(rate $r)" || echo "FAIL net+file : 받기 실패"
rm -f "$DIR/dl.bin"

# 3 + 4. File writes (Python: same calls Steam makes, open/write/close per file)
# 3 + 4. 파일 쓰기(Python: Steam과 같은 호출, 파일마다 open/write/close)
python3 - "$DIR" <<'EOF'
import os, sys, time
d = sys.argv[1]
block = os.urandom(1 << 20)
t = time.time()
with open(os.path.join(d, "big.bin"), "wb") as f:
    for _ in range(256):
        f.write(block)
    f.flush(); os.fsync(f.fileno())
dt = time.time() - t
print(f"OK   disk-big : 256 MB, {dt:.1f}s, {256/dt:.1f} MB/s")
os.remove(os.path.join(d, "big.bin"))
small = block[:65536]
os.makedirs(os.path.join(d, "s"), exist_ok=True)
t = time.time()
for i in range(2000):
    with open(os.path.join(d, "s", f"{i}.chunk"), "wb") as f:
        f.write(small)
dt = time.time() - t
print(f"OK   disk-small : 2000 x 64 KB, {dt:.1f}s, {2000/dt:.0f} files/s, {125/dt:.1f} MB/s")
t = time.time()
for i in range(2000):
    os.remove(os.path.join(d, "s", f"{i}.chunk"))
print(f"INFO delete : 2000 files {time.time()-t:.1f}s")
EOF
rm -rf "$DIR"
echo "DONE"
