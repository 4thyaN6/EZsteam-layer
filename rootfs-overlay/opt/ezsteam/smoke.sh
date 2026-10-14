#!/bin/sh
# In-app environment check, started by the app's "환경 시험" button through GlibcLauncher.
# 앱의 "환경 시험" 버튼이 GlibcLauncher로 실행하는 앱 안 환경 확인임
#
# Each line is "OK <item> : detail" or "FAIL <item> : detail".
# 각 줄은 "OK 항목 : 내용" 또는 "FAIL 항목 : 내용" 형식임

ok()   { echo "OK   $1 : $2"; }
fail() { echo "FAIL $1 : $2"; }

# 1. Architecture of the rootfs programs / rootfs 프로그램 아키텍처
m=$(uname -m 2>&1); [ "$m" = "aarch64" ] && ok arch "$m" || fail arch "$m"

# 2. /etc is mapped into rootfs (Android has no os-release) / /etc 변환
v=$(. /etc/os-release 2>/dev/null && echo "$PRETTY_NAME")
[ -n "$v" ] && ok os-release "$v" || fail os-release "not readable"

# 3. /proc/self/exe of a child is the real program, not ld.so / 자식의 /proc/self/exe 보정
e=$(readlink /proc/self/exe 2>&1)
case "$e" in *ld-linux*|"") fail self-exe "$e" ;; *) ok self-exe "$e" ;; esac

# 4. Nested shell exec chain / 셸 안 셸 실행 연쇄
n=$(sh -c 'echo nested' 2>&1); [ "$n" = "nested" ] && ok nested-exec "$n" || fail nested-exec "$n"

# 5. /tmp write / /tmp 쓰기
echo ok > /tmp/.ezs-smoke 2>/dev/null && [ "$(cat /tmp/.ezs-smoke)" = "ok" ] && ok tmp-write "/tmp" || fail tmp-write "/tmp"
rm -f /tmp/.ezs-smoke

# 6. glibc version / glibc 버전
g=$(ldd --version 2>&1 | head -n 1); [ -n "$g" ] && ok glibc "$g" || fail glibc "ldd missing"

# 7. python3 for Valve's proton script / Valve proton 스크립트용 python3
p=$(python3 -c 'import sys; print(sys.version.split()[0])' 2>&1) && ok python3 "$p" || fail python3 "$p"

# 8. "#!/usr/bin/env python3" style script through the layer / env 경유 스크립트 실행
printf '#!/usr/bin/env python3\nprint("env-shebang")\n' > /tmp/.ezs-py && chmod +x /tmp/.ezs-py
s=$(/tmp/.ezs-py 2>&1); [ "$s" = "env-shebang" ] && ok env-shebang "$s" || fail env-shebang "$s"
rm -f /tmp/.ezs-py

# 9. Thread creation: glibc pthread_create uses syscalls that Android's app seccomp filter may
#    block (rseq, set_robust_list, clone3). A SIGSYS kill shows up as exit code 159 (128+31).
#    스레드 생성: glibc pthread_create가 안드로이드 앱 seccomp이 막을 수 있는 호출
#    (rseq, set_robust_list, clone3)을 씀. SIGSYS로 죽으면 종료 코드 159(128+31)로 보임
t=$(python3 -c 'import threading; r=[]; th=threading.Thread(target=lambda: r.append(1)); th.start(); th.join(); print("thread-ok" if r else "no-run")' 2>&1)
rc=$?
if [ "$t" = "thread-ok" ]; then ok pthread "$t"; elif [ $rc -eq 159 ]; then fail pthread "SIGSYS (seccomp)"; else fail pthread "rc=$rc $t"; fi

# 10. Syscalls glibc may call directly; report each one's result (SIGSYS = blocked)
#     glibc가 직접 부를 수 있는 호출별 결과 표시(SIGSYS = 차단됨)
for sc in faccessat2 stat close_range; do
    case $sc in
        faccessat2)  code='import os; os.access("/tmp", os.R_OK, follow_symlinks=False); print("ok")' ;;
        stat)        code='import os; os.stat("/tmp"); print("ok")' ;;
        close_range) code='import os; os.closerange(100, 200); print("ok")' ;;
    esac
    r=$(python3 -c "$code" 2>&1); rc=$?
    if [ "$r" = "ok" ]; then ok "sys-$sc" "$r"; elif [ $rc -eq 159 ]; then fail "sys-$sc" "SIGSYS (seccomp)"; else fail "sys-$sc" "rc=$rc $r"; fi
done

# 11. Process count seen from inside (this shell's siblings are not counted here)
# 안에서 본 프로세스 수(앱 화면의 "자식 프로세스" 버튼이 정확한 값)
echo "INFO pid=$$ ppid=$PPID"
echo "DONE"
