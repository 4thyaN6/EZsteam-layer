#!/bin/sh
# Rewrite absolute symlinks inside a rootfs into relative ones (run on a Linux/WSL PC).
# rootfs 안의 절대 경로 심볼릭 링크를 상대 경로로 바꿈(리눅스·WSL PC에서 실행)
#
# Why: without PRoot the kernel resolves "/etc/alternatives/x" against the real Android
# root, not the rootfs. Relative links resolve correctly wherever the rootfs lives.
# 이유: PRoot가 없으면 커널이 "/etc/alternatives/x" 같은 절대 대상을 실제 안드로이드
# 루트 기준으로 풂. 상대 링크는 rootfs 위치와 상관없이 맞게 풀림
#
#   sh tools/fix-abs-symlinks.sh ./rootfs
set -eu

ROOT="$(cd "$1" && pwd)"
COUNT=0

# -print0 / read -d '' is not POSIX sh, so use find -exec with a small inline script
# POSIX sh에는 -print0 처리가 없어 find -exec 안에서 한 개씩 처리함
find "$ROOT" -type l -exec sh -c '
    root="$1"; link="$2"
    target="$(readlink "$link")"
    case "$target" in
        /*)
            # New target: path from the link directory to <root><target>
            # 새 대상: 링크가 있는 폴더에서 <root><target>까지의 상대 경로
            rel="$(realpath -m -s --relative-to="$(dirname "$link")" "$root$target")"
            ln -sfn "$rel" "$link"
            echo "$link: $target -> $rel"
            ;;
    esac
' _ "$ROOT" {} \; | tee /dev/stderr | wc -l | {
    read n
    echo "rewritten: $n"
}
