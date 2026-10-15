package app.ezsteam.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists processes started from this app by reading /proc (only our own uid is visible).
 * The Android child-process limit (32 across all apps) counts these.
 * /proc를 읽어 이 앱에서 시작된 프로세스를 나열함(자기 uid만 보임).
 * 안드로이드 자식 프로세스 제한(모든 앱 합계 32개)이 세는 대상임
 */
public final class ChildProcessCounter {

    /** One process entry / 프로세스 한 항목 */
    public static final class Proc {
        public final int pid;
        public final int ppid;
        public final String name;
        public final long rssKb;
        /** Kernel state letter (R running, S sleeping, D disk wait, T stopped/traced, Z zombie)
         *  커널 상태 글자(R 실행, S 대기, D 디스크 대기, T 정지·추적, Z 좀비) */
        public char state = '?';
        /** CPU time used so far, in clock ticks (normally 1/100 s) / 지금까지 쓴 CPU 시간(보통 1/100초 단위) */
        public long cpuTicks;

        Proc(int pid, int ppid, String name, long rssKb) {
            this.pid = pid;
            this.ppid = ppid;
            this.name = name;
            this.rssKb = rssKb;
        }
    }

    /**
     * Threads of one process: id, name, state, CPU seconds and the kernel function it waits in
     * (wchan, may be unreadable on Android). Tells a busy loop from a thread that is blocked.
     * 한 프로세스의 스레드: id, 이름, 상태, CPU 초, 대기 중인 커널 함수(wchan, 안드로이드에서는
     * 못 읽을 수 있음). 계속 도는 스레드와 막혀 있는 스레드를 구분하기 위함
     */
    public static String threads(int pid) {
        StringBuilder sb = new StringBuilder();
        File[] ts = new File("/proc/" + pid + "/task").listFiles();
        if (ts == null) return "(스레드 목록을 읽지 못함)\n";
        java.util.Arrays.sort(ts, (a, b) -> a.getName().compareTo(b.getName()));
        for (File t : ts) {
            String stat = readSmall(new File(t, "stat"));
            if (stat == null) continue;
            int l = stat.indexOf('('), r = stat.lastIndexOf(')');
            if (l < 0 || r < l) continue;
            String name = stat.substring(l + 1, r);
            String[] f = stat.substring(r + 2).trim().split(" ");
            long cpu = 0;
            try {
                cpu = Long.parseLong(f[11]) + Long.parseLong(f[12]);
            } catch (RuntimeException ignored) {
                // leave 0 / 0으로 둠
            }
            String wchan = readSmall(new File(t, "wchan"));
            sb.append(String.format(java.util.Locale.ROOT, "%7s %c %7.1fs  %-16s %s%n", t.getName(),
                    f.length > 0 && !f[0].isEmpty() ? f[0].charAt(0) : '?', cpu / 100.0, name,
                    wchan == null || wchan.isEmpty() || "0".equals(wchan) ? "" : wchan.trim()));
        }
        return sb.toString();
    }

    private ChildProcessCounter() {
    }

    /**
     * All descendants of rootPid (normally the app's own pid), parents before children.
     * rootPid(보통 앱 자신의 pid)의 모든 자손, 부모가 먼저 오도록 정렬함
     */
    public static List<Proc> descendants(int rootPid) {
        Map<Integer, Proc> all = new HashMap<>();
        File[] dirs = new File("/proc").listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                int pid;
                try {
                    pid = Integer.parseInt(d.getName());
                } catch (NumberFormatException e) {
                    continue;
                }
                Proc p = read(pid);
                if (p != null) all.put(pid, p);
            }
        }
        // Walk the tree from rootPid / rootPid부터 트리를 따라감
        List<Proc> out = new ArrayList<>();
        List<Integer> queue = new ArrayList<>();
        queue.add(rootPid);
        for (int qi = 0; qi < queue.size(); qi++) {
            int parent = queue.get(qi);
            for (Proc p : all.values()) {
                if (p.ppid == parent && p.pid != rootPid) {
                    out.add(p);
                    queue.add(p.pid);
                }
            }
        }
        return out;
    }

    /** Parse /proc/<pid>/stat and RSS from statm; null if unreadable / stat·statm 읽기, 못 읽으면 null */
    private static Proc read(int pid) {
        String stat = readSmall(new File("/proc/" + pid + "/stat"));
        if (stat == null) return null;
        // Format: pid (comm) state ppid ... ; comm may contain spaces or ')'
        // 형식: pid (이름) 상태 ppid ... ; 이름에 공백이나 ')'가 있을 수 있음
        int l = stat.indexOf('('), r = stat.lastIndexOf(')');
        if (l < 0 || r < l) return null;
        String name = stat.substring(l + 1, r);
        String[] f = stat.substring(r + 2).trim().split(" ");
        if (f.length < 2) return null;
        int ppid;
        try {
            ppid = Integer.parseInt(f[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        // Wine and the glibc loader rename processes; cmdline argv[0] is more telling
        // Wine과 glibc 로더는 이름을 바꾸므로 cmdline의 argv[0]이 더 알아보기 쉬움
        String cmd = readSmall(new File("/proc/" + pid + "/cmdline"));
        if (cmd != null && !cmd.isEmpty()) {
            int z = cmd.indexOf('\0');
            String a0 = z >= 0 ? cmd.substring(0, z) : cmd;
            if (!a0.isEmpty()) name = name + " | " + a0;
        }
        long rss = 0;
        String statm = readSmall(new File("/proc/" + pid + "/statm"));
        if (statm != null) {
            String[] m = statm.trim().split(" ");
            if (m.length > 1) {
                try {
                    rss = Long.parseLong(m[1]) * 4; // pages of 4 KiB / 4KiB 페이지
                } catch (NumberFormatException ignored) {
                    // leave 0 / 0으로 둠
                }
            }
        }
        Proc p = new Proc(pid, ppid, name, rss);
        if (!f[0].isEmpty()) p.state = f[0].charAt(0);
        try {
            // utime and stime: fields 14 and 15 of stat, here 11 and 12 after the state
            // utime, stime: stat의 14·15번째 항목, 여기서는 상태 뒤 11·12번째
            p.cpuTicks = Long.parseLong(f[11]) + Long.parseLong(f[12]);
        } catch (RuntimeException ignored) {
            // leave 0 / 0으로 둠
        }
        return p;
    }

    private static String readSmall(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[4096];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return null;
        }
    }
}
