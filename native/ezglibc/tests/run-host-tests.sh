#!/bin/sh
# Host self-test for libezglibc.so: builds a tiny fake rootfs and runs tprog the way
# the Android launcher will (loader invoked directly, LD_PRELOAD set).
# libezglibc.so 호스트 자체 시험: 작은 가짜 rootfs를 만들고, 안드로이드 실행기와
# 같은 방식(로더 직접 실행 + LD_PRELOAD)으로 tprog를 실행함
#
# Usage: sh tests/run-host-tests.sh /abs/path/libezglibc.so
set -eu

SO="$1"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
ROOT="$WORK/rootfs"

# Host loader (x86_64 or aarch64) / 호스트 로더(x86_64 또는 aarch64)
if [ -e /lib64/ld-linux-x86-64.so.2 ]; then LDSO=/lib64/ld-linux-x86-64.so.2
else LDSO=/lib/ld-linux-aarch64.so.1; fi

# Fake rootfs: /bin/sh copy, data file, shebang script, test program
# 가짜 rootfs: /bin/sh 복사본, 데이터 파일, #! 스크립트, 시험 프로그램
mkdir -p "$ROOT/bin" "$ROOT/ezgtest" "$ROOT/dev/shm"
cp "$(readlink -f /bin/sh)" "$ROOT/bin/sh"
echo "ezg-data" > "$ROOT/ezgtest/data.txt"
cc -O1 -o "$ROOT/ezgtest/tprog" "$HERE/tprog.c"

# The script checks it was started through the rootfs sh, reads mapped data, and
# execs the glibc test program again (exec chain from a shell)
# 스크립트: rootfs sh로 시작됐는지, 변환 경로 읽기, 셸에서 glibc 프로그램 재실행을 확인함
cat > "$ROOT/ezgtest/script.sh" <<'EOF'
#!/bin/sh
[ "$1" = "arg1" ] || exit 11
read line < /ezgtest/data.txt
[ "$line" = "ezg-data" ] || exit 12
/ezgtest/tprog child from-script
EOF
chmod +x "$ROOT/ezgtest/script.sh"

# Fake Steam Linux Runtime entry point: must never run when EZG_SKIP_SLR=1
# 가짜 SLR 진입점: EZG_SKIP_SLR=1이면 절대 실행되면 안 됨
printf '#!/bin/sh\nexit 55\n' > "$ROOT/ezgtest/_v2-entry-point"
chmod +x "$ROOT/ezgtest/_v2-entry-point"

# Prefixes that do not exist on the host, so a pass proves mapping happened
# 호스트에 없는 앞부분을 써서 통과하면 변환이 실제로 일어났음을 보임
OUT="$WORK/out.txt"
env -i PATH=/usr/bin:/bin \
    EZG_ROOTFS="$ROOT" EZG_LDSO="$LDSO" EZG_REDIRECT="/ezgtest:/bin:/dev/shm" \
    EZG_SELF_EXE="$ROOT/ezgtest/tprog" EZG_LOG="$WORK/ezg.log" EZG_SKIP_SLR=1 \
    LD_PRELOAD="$SO" \
    "$LDSO" --argv0 tprog "$ROOT/ezgtest/tprog" > "$OUT" 2>&1 || true

cat "$OUT"
echo "---- log: $WORK/ezg.log"
FAILS=$(grep -c '^FAIL' "$OUT" || true)
OKS=$(grep -c '^OK' "$OUT" || true)
# Child lines must report the real program path / 자식 줄도 실제 프로그램 경로여야 함
BADCHILD=$(grep '^CHILD' "$OUT" | grep -vc "exe=$ROOT/ezgtest/tprog" || true)
echo "ok=$OKS fail=$FAILS bad_child=$BADCHILD"
[ "$FAILS" = "0" ] && [ "$BADCHILD" = "0" ] && [ "$OKS" -ge 14 ]
