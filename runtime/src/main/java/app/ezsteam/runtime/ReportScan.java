package app.ezsteam.runtime;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Two report sections made from files only (no processes): where the app's storage goes, and
 * which installed games name a DLL of Microsoft's DirectX June 2010 runtime (the one DXSETUP
 * installs), to decide whether that runtime is needed in the prefixes.
 * 파일만으로 만드는 제보 항목 두 개(프로세스 없음): 앱 저장 공간이 어디에 쓰이는지, 그리고 설치된
 * 게임 중 Microsoft DirectX 2010년 6월 런타임(DXSETUP이 설치하는 것)의 DLL 이름을 가진 게임.
 * prefix에 그 런타임이 필요한지 정하기 위함
 */
public final class ReportScan {

    private ReportScan() {
    }

    // ---- storage / 저장 공간 ----

    /** Bytes of regular files under a path, links not followed / 경로 아래 일반 파일 바이트 합, 링크는 따라가지 않음 */
    public static long size(File dir) {
        final long[] total = {0};
        if (!Files.exists(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) return 0;
        try {
            Files.walkFileTree(dir.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                    if (a.isRegularFile()) total[0] += a.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // partial total / 일부 합계
        }
        return total[0];
    }

    private static String gb(long bytes) {
        return String.format(Locale.ROOT, "%8.2f GB", bytes / 1e9);
    }

    /** Sizes of the children of one folder, largest first / 한 폴더의 하위 항목 크기, 큰 순 */
    private static void children(StringBuilder out, String title, File dir, int indent, int limit) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        List<long[]> rows = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (File k : kids) {
            rows.add(new long[]{size(k), names.size()});
            names.add(k.getName() + (k.isDirectory() ? "/" : ""));
        }
        rows.sort((a, b) -> Long.compare(b[0], a[0]));
        long total = 0;
        for (long[] r : rows) total += r[0];
        String pad = "  ".repeat(indent);
        out.append(pad).append(gb(total)).append("  ").append(title).append('\n');
        int shown = 0;
        for (long[] r : rows) {
            if (shown++ >= limit || r[0] < 1_000_000) break;
            out.append(pad).append("  ").append(gb(r[0])).append("  ").append(names.get((int) r[1])).append('\n');
        }
    }

    /**
     * Storage breakdown: the app's files and cache, the rootfs, the Steam client folder, the
     * games and prefixes, and the base prefix (sizes of regular files; shared hard links are
     * counted once per name, so totals can be a little high)
     * 저장 공간 나눔: 앱 파일·캐시, rootfs, Steam 클라이언트 폴더, 게임과 prefix, 기본 prefix(일반 파일 크기.
     * 공유 하드 링크는 이름마다 세므로 합계가 조금 클 수 있음)
     */
    public static String storageUsage(File appFiles, File appCache, File rootfs) {
        StringBuilder out = new StringBuilder();
        out.append("# 앱 저장 공간 (일반 파일 크기 합, 링크는 따라가지 않음)\n");
        out.append(gb(size(appCache))).append("  앱 캐시\n");
        children(out, "앱 파일 (" + appFiles.getName() + "/)", appFiles, 0, 20);
        out.append('\n');
        children(out, "rootfs", rootfs, 0, 20);
        File home = new File(rootfs, "home/steam");
        File steam = new File(home, ".local/share/Steam");
        out.append('\n');
        children(out, "Steam 클라이언트 폴더", steam, 0, 25);
        out.append('\n');
        children(out, "steamapps/common (게임, Proton)", new File(steam, "steamapps/common"), 0, 30);
        out.append('\n');
        children(out, "steamapps/compatdata (게임 prefix)", new File(steam, "steamapps/compatdata"), 0, 30);
        out.append('\n');
        children(out, "steamapps/shadercache", new File(steam, "steamapps/shadercache"), 0, 10);
        out.append('\n');
        children(out, "package (클라이언트 갱신 파일)", new File(steam, "package"), 0, 10);
        out.append('\n');
        children(out, "ezsteam (기본 prefix 등)", new File(home, ".local/share/ezsteam"), 0, 10);
        out.append('\n');
        children(out, "rootfs /usr", new File(rootfs, "usr"), 0, 10);
        out.append('\n');
        children(out, "rootfs /var", new File(rootfs, "var"), 0, 10);
        return out.toString();
    }

    // ---- DirectX June 2010 runtime names / DirectX 2010년 6월 런타임 이름 ----

    /**
     * DLLs only that runtime provides (Windows 8/10 already have d3dcompiler_47, xinput1_4,
     * xinput9_1_0, xaudio2_8/2_9, so those are not counted)
     * 그 런타임만 주는 DLL(Windows 8/10에 이미 있는 d3dcompiler_47, xinput1_4, xinput9_1_0,
     * xaudio2_8/2_9는 세지 않음)
     */
    private static final Pattern DX2010 = Pattern.compile(
            "(d3dx9_\\d\\d|d3dx10(_\\d\\d)?|d3dx11_4[23]|d3dcompiler_(3[3-9]|4[0-3])|xinput1_3|xaudio2_[0-7]"
                    + "|x3daudio1_[0-7]|xactengine[23]_\\d|xapofx1_[0-5])(\\.dll)?(?![0-9a-z_])",
            Pattern.CASE_INSENSITIVE);
    /** Folders that are not games / 게임이 아닌 폴더 */
    private static final Set<String> NOT_GAMES = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    static {
        NOT_GAMES.addAll(Arrays.asList("Steamworks Shared", "SteamLinuxRuntime", "SteamLinuxRuntime_soldier",
                "SteamLinuxRuntime_sniper", "_CommonRedist"));
    }

    /**
     * Per game folder of each library: the DLL names of that runtime found inside its .exe/.dll
     * files (import tables and LoadLibrary strings, ASCII or UTF-16), and copies of such DLLs the
     * game carries itself (then it does not need the runtime for them)
     * 라이브러리별 게임 폴더마다: .exe/.dll 파일 안에서 찾은 그 런타임의 DLL 이름(가져오기 표와 LoadLibrary
     * 문자열, ASCII 또는 UTF-16), 게임이 직접 가진 같은 DLL 복사본(그러면 그것에는 런타임이 필요 없음)
     */
    public static String dxUsage(List<File> libraries) {
        StringBuilder out = new StringBuilder();
        out.append("# DirectX 2010년 6월 런타임(DXSETUP) DLL을 쓰는 게임\n");
        out.append("# 이름 = 게임 실행 파일 안에 그 DLL 이름이 있음, 동봉 = 게임 폴더에 같은 DLL이 직접 들어 있음\n");
        int games = 0, users = 0;
        for (File lib : libraries) {
            File[] dirs = new File(lib, "common").listFiles(File::isDirectory);
            if (dirs == null) continue;
            Arrays.sort(dirs);
            out.append("\n## ").append(lib).append('\n');
            for (File g : dirs) {
                if (NOT_GAMES.contains(g.getName()) || new File(g, "proton").isFile()) continue;
                games++;
                Set<String> named = new TreeSet<>(), bundled = new TreeSet<>();
                int[] scanned = {0};
                scanGame(g, named, bundled, scanned);
                Set<String> needed = new TreeSet<>(named);
                needed.removeAll(bundled);
                if (!named.isEmpty()) users++;
                out.append(named.isEmpty() ? "없음  " : needed.isEmpty() ? "동봉  " : "필요  ")
                        .append(g.getName()).append("  (실행 파일 ").append(scanned[0]).append("개 검사)");
                if (!named.isEmpty()) out.append("\n      이름: ").append(String.join(", ", named));
                if (!bundled.isEmpty()) out.append("\n      동봉: ").append(String.join(", ", bundled));
                out.append('\n');
            }
        }
        out.append("\n게임 ").append(games).append("개 중 이름이 있는 게임 ").append(users).append("개\n");
        out.append("(필요 = 런타임이 없으면 Proton 자체 구현으로 대신함, 동봉 = 게임이 직접 가진 DLL을 씀)\n");
        return out.toString();
    }

    private static void scanGame(File game, Set<String> named, Set<String> bundled, int[] scanned) {
        try {
            Files.walkFileTree(game.toPath(), java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 8,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                            String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
                            if (!a.isRegularFile() || !(n.endsWith(".exe") || n.endsWith(".dll"))) {
                                return FileVisitResult.CONTINUE;
                            }
                            if (DX2010.matcher(n).matches()) {
                                bundled.add(n);
                                return FileVisitResult.CONTINUE;
                            }
                            // Very large files are mostly data; the names sit near the start of the
                            // import table but LoadLibrary strings can be anywhere, so up to 400 MB
                            // 아주 큰 파일은 대부분 데이터. 이름은 가져오기 표에 있지만 LoadLibrary 문자열은
                            // 어디에나 있을 수 있어 400 MB까지 봄
                            if (a.size() > 400L << 20) return FileVisitResult.CONTINUE;
                            scanned[0]++;
                            findNames(file.toFile(), named);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException ignored) {
            // partial result / 일부 결과
        }
    }

    /** DLL names in a file, ASCII and UTF-16LE, read in overlapping blocks / 파일 안의 DLL 이름, ASCII와 UTF-16LE, 겹치는 블록으로 읽음 */
    static void findNames(File f, Set<String> named) {
        byte[] buf = new byte[1 << 20];
        int keep = 64;
        int have = 0;
        try (InputStream in = new java.io.FileInputStream(f)) {
            int n;
            while ((n = in.read(buf, have, buf.length - have)) > 0) {
                int len = have + n;
                match(buf, len, named);
                // Keep the tail so a name split between blocks is still found
                // 블록 경계에 걸친 이름도 찾도록 끝부분을 남김
                int tail = Math.min(keep, len);
                System.arraycopy(buf, len - tail, buf, 0, tail);
                have = tail;
            }
        } catch (IOException ignored) {
            // unreadable file / 읽을 수 없는 파일
        }
    }

    private static void match(byte[] buf, int len, Set<String> named) {
        // ASCII view (bytes outside printable ASCII become '.') / ASCII 보기(인쇄 가능한 ASCII 밖은 '.')
        char[] a = new char[len];
        // UTF-16LE view: every second byte must be 0 / UTF-16LE 보기: 두 번째 바이트마다 0이어야 함
        char[] w = new char[len / 2];
        for (int i = 0; i < len; i++) {
            int b = buf[i] & 0xff;
            a[i] = b >= 0x20 && b < 0x7f ? (char) b : '.';
        }
        for (int i = 0; i + 1 < len; i += 2) {
            int b = buf[i] & 0xff;
            w[i / 2] = buf[i + 1] == 0 && b >= 0x20 && b < 0x7f ? (char) b : '.';
        }
        for (char[] view : new char[][]{a, w}) {
            Matcher m = DX2010.matcher(new String(view));
            // LoadLibrary strings may leave out ".dll" / LoadLibrary 문자열은 ".dll"을 뺄 수 있음
            while (m.find()) named.add(m.group(1).toLowerCase(Locale.ROOT) + ".dll");
        }
    }

    // ---- which DLLs are whose, and what games loaded / 어느 DLL이 누구 것이고 게임이 무엇을 불러왔는지 ----

    /** DLLs checked: DirectX 2010 parts, Windows 10 ones, Visual C++, Direct3D
     *  확인하는 DLL: DirectX 2010 부품, Windows 10 기본 것, Visual C++, Direct3D */
    private static final String[] CHECK = {"d3dx9_43.dll", "xinput1_3.dll", "xaudio2_7.dll", "d3dcompiler_43.dll",
            "xinput1_4.dll", "xaudio2_9.dll", "msvcp140.dll", "vcruntime140.dll", "d3d11.dll", "dxgi.dll"};

    /**
     * CPU of a Windows DLL file from its PE header, and whether it is Wine's own (builtin or
     * placeholder text in the DOS stub area): checks that Proton's parts are built for ARM and
     * that DXSETUP/Visual C++ files are Microsoft's x86 code (run through FEX)
     * PE 헤더로 본 Windows DLL 파일의 CPU와 Wine 자체 파일인지(DOS 스텁 자리의 builtin/placeholder 글).
     * Proton 부품이 ARM용인지, DXSETUP/Visual C++ 파일이 Microsoft x86 코드(FEX로 실행)인지 확인함
     */
    static String peInfo(File f) {
        byte[] b = new byte[4096];
        int n;
        try (InputStream in = new java.io.FileInputStream(f)) {
            n = in.read(b);
        } catch (IOException e) {
            return "읽기 실패";
        }
        if (n < 0x40 || b[0] != 'M' || b[1] != 'Z') return "PE 아님 (" + f.length() + " B)";
        String head = new String(b, 0, Math.min(n, 512), java.nio.charset.StandardCharsets.ISO_8859_1);
        String who = head.contains("Wine builtin DLL") ? "Wine builtin" : head.contains("Wine placeholder DLL") ? "Wine 자리표시" : "Wine 아님";
        int pe = (b[0x3c] & 0xff) | (b[0x3d] & 0xff) << 8 | (b[0x3e] & 0xff) << 16;
        if (pe <= 0 || pe + 6 > n) return who;
        int m = (b[pe + 4] & 0xff) | (b[pe + 5] & 0xff) << 8;
        String cpu;
        switch (m) {
            case 0x14c: cpu = "x86"; break;
            case 0x8664: cpu = "x86-64"; break;
            case 0xaa64: cpu = "ARM64"; break;
            case 0xa641: cpu = "ARM64EC"; break;
            case 0xa64e: cpu = "ARM64X"; break;
            default: cpu = String.format(Locale.ROOT, "0x%x", m);
        }
        return cpu + ", " + who + ", " + f.length() + " B";
    }

    private static File find(File dir, String name) {
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) if (f.getName().equalsIgnoreCase(name)) return f;
        return null;
    }

    private static void dllTable(StringBuilder out, String title, File dir) {
        if (!dir.isDirectory()) return;
        out.append("\n## ").append(title).append("  (").append(dir).append(")\n");
        for (String d : CHECK) {
            File f = find(dir, d);
            out.append("  ").append(String.format(Locale.ROOT, "%-20s", d)).append(f == null ? "없음" : peInfo(f)).append('\n');
        }
    }

    private static final Pattern LOADDLL = Pattern.compile(
            "trace:loaddll:\\S+ Loaded L\"([^\"]+)\" at \\S+: (native|builtin)", Pattern.CASE_INSENSITIVE);

    /**
     * DLL check report: Proton's DLL folders, the base prefix and up to 4 game prefixes (CPU and
     * owner of each checked DLL), then per Proton log which of them were loaded native or builtin
     * DLL 확인 제보: Proton DLL 폴더, 기본 prefix, 게임 prefix 최대 4개(확인 DLL마다 CPU와 출처), 이어서
     * Proton 로그마다 그중 무엇을 native/builtin으로 불러왔는지
     */
    public static String dllCheck(File steam, File baseprefix, File logDir) {
        StringBuilder out = new StringBuilder("# DLL 확인 (Proton 부품의 CPU, Microsoft 파일 여부, 게임이 실제로 불러온 것)\n");
        File[] commons = new File(steam, "steamapps/common").listFiles(File::isDirectory);
        for (File c : commons == null ? new File[0] : commons) {
            if (!new File(c, "proton").isFile()) continue;
            File wine = new File(c, "files/lib/wine");
            File[] arch = wine.listFiles(File::isDirectory);
            out.append("\n# ").append(c.getName()).append(": files/lib/wine 아래 ");
            if (arch != null) {
                Arrays.sort(arch);
                for (File a : arch) out.append(a.getName()).append(' ');
            }
            out.append('\n');
            for (File a : arch == null ? new File[0] : arch) {
                if (a.getName().endsWith("-windows")) dllTable(out, c.getName() + " " + a.getName(), a);
            }
        }
        dllTable(out, "기본 prefix system32", new File(baseprefix, "pfx/drive_c/windows/system32"));
        dllTable(out, "기본 prefix syswow64", new File(baseprefix, "pfx/drive_c/windows/syswow64"));
        File[] games = new File(steam, "steamapps/compatdata").listFiles(File::isDirectory);
        if (games != null) {
            Arrays.sort(games, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = 0; i < Math.min(4, games.length); i++) {
                String tag = games[i].getName() + (new File(games[i], "ezs-directx").isFile() ? " (DirectX 설치됨)" : "");
                dllTable(out, "게임 " + tag + " system32", new File(games[i], "pfx/drive_c/windows/system32"));
                dllTable(out, "게임 " + tag + " syswow64", new File(games[i], "pfx/drive_c/windows/syswow64"));
            }
        }
        out.append("\n# Proton 로그에서 불러온 것 (native = prefix 안 파일, builtin = Proton 자체)\n");
        File[] logs = logDir.listFiles((d, nm) -> nm.endsWith(".log") && nm.contains("steam-"));
        if (logs != null) {
            Arrays.sort(logs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            Set<String> want = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            want.addAll(Arrays.asList(CHECK));
            for (File lf : logs) {
                Set<String> seen = new TreeSet<>();
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(lf))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (!line.contains("loaddll")) continue;
                        Matcher m = LOADDLL.matcher(line);
                        if (!m.find()) continue;
                        String path = m.group(1).replace("\\\\", "\\");
                        String nm = path.substring(path.lastIndexOf('\\') + 1);
                        if (want.contains(nm) || DX2010.matcher(nm.toLowerCase(Locale.ROOT)).matches()) {
                            seen.add(nm.toLowerCase(Locale.ROOT) + " " + m.group(2).toLowerCase(Locale.ROOT)
                                    + (path.toLowerCase(Locale.ROOT).contains("\\windows\\") ? "" : " (게임 폴더)"));
                        }
                    }
                } catch (IOException e) {
                    continue;
                }
                out.append(lf.getName()).append(": ").append(seen.isEmpty() ? "해당 DLL 없음" : String.join(", ", seen)).append('\n');
            }
        }
        return out.toString();
    }
}
