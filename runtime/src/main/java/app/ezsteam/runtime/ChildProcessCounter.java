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

        Proc(int pid, int ppid, String name, long rssKb) {
            this.pid = pid;
            this.ppid = ppid;
            this.name = name;
            this.rssKb = rssKb;
        }
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
        return new Proc(pid, ppid, name, rss);
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
