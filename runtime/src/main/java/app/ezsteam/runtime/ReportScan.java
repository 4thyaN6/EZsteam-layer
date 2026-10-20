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
}
