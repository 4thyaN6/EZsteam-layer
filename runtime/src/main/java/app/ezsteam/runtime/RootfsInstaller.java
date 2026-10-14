package app.ezsteam.runtime;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Installs the glibc rootfs into files/ezs, either from a development asset
 * (assets/rootfs.tar.xz or assets/rootfs.tar) or from a file downloaded by RootfsDownloader.
 * glibc rootfs를 files/ezs에 설치함. 개발용 asset(assets/rootfs.tar.xz 또는 rootfs.tar)이나
 * RootfsDownloader가 받은 파일에서 풂
 *
 * The short folder name keeps AF_UNIX socket paths (107 bytes max) short.
 * 유닉스 소켓 경로 제한(107바이트) 때문에 폴더 이름을 짧게 둠
 */
public final class RootfsInstaller {

    /** Optional development assets (normally the rootfs is downloaded, as EZlator does with imagefs)
     *  선택 개발용 asset(보통은 EZlator imagefs처럼 내려받음) */
    public static final String ASSET_TAR_XZ = "rootfs.tar.xz";
    public static final String ASSET_TAR = "rootfs.tar";
    public static final String ASSET_VERSION = "rootfs.version";

    /** Opens a fresh stream of the uncompressed tar / 압축 풀린 tar 스트림을 새로 엶 */
    public interface TarSource {
        InputStream open() throws IOException;
    }

    /** Marker file inside rootfs holding the installed version / 설치된 버전을 적는 표시 파일 */
    private static final String MARKER = ".ezs-version";

    private RootfsInstaller() {
    }

    /**
     * Rootfs directory: files/ezs of the host app. Kept apart from any other runtime the host
     * has (e.g. EZlator's files/imagefs) and short, because AF_UNIX paths are limited.
     * rootfs 폴더: 호스트 앱의 files/ezs. 호스트의 다른 런타임(EZlator files/imagefs 등)과
     * 분리하고, 유닉스 소켓 경로 제한 때문에 짧게 둠
     */
    public static File rootfsDir(Context ctx) {
        return new File(ctx.getFilesDir(), "ezs");
    }

    /** Runtime data outside the rootfs (logs, kept home during updates): files/ezs-data
     *  rootfs 밖 런타임 데이터(로그, 갱신 중 보관하는 홈): files/ezs-data */
    public static File dataDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "ezs-data");
        d.mkdirs();
        return d;
    }

    /** Version bundled in the APK ("" if missing) / APK에 든 버전(없으면 "") */
    public static String bundledVersion(Context ctx) {
        try (InputStream in = ctx.getAssets().open(ASSET_VERSION)) {
            return new String(readAll(in), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /** Version currently installed ("" if none) / 지금 설치된 버전(없으면 "") */
    public static String installedVersion(Context ctx) {
        File m = new File(rootfsDir(ctx), MARKER);
        try (InputStream in = new java.io.FileInputStream(m)) {
            return new String(readAll(in), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /** True when the bundled rootfs differs from the installed one / APK 쪽 rootfs가 설치본과 다르면 참 */
    public static boolean needsInstall(Context ctx) {
        String b = bundledVersion(ctx);
        return !b.isEmpty() && !b.equals(installedVersion(ctx));
    }

    /** Tar source from the APK assets (.tar.xz preferred), or null when none is bundled
     *  APK asset의 tar 원본(.tar.xz 우선). 들어 있지 않으면 null */
    public static TarSource assetSource(Context ctx) {
        if (hasAsset(ctx, ASSET_TAR_XZ)) {
            return () -> new org.tukaani.xz.XZInputStream(
                    new BufferedInputStream(ctx.getAssets().open(ASSET_TAR_XZ), 1 << 16));
        }
        if (hasAsset(ctx, ASSET_TAR)) {
            return () -> new BufferedInputStream(ctx.getAssets().open(ASSET_TAR), 1 << 16);
        }
        return null;
    }

    /** Tar source from a downloaded .tar.xz file / 내려받은 .tar.xz 파일의 tar 원본 */
    public static TarSource fileSource(File tarXz) {
        return () -> new org.tukaani.xz.XZInputStream(
                new BufferedInputStream(new java.io.FileInputStream(tarXz), 1 << 16));
    }

    private static boolean hasAsset(Context ctx, String name) {
        try (InputStream ignored = ctx.getAssets().open(name)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Wipe and extract from the given source, then record version. The user's home
     * (home/steam) is kept across updates.
     * 주어진 원본에서 지우고 새로 푼 뒤 버전을 기록함. 사용자 홈(home/steam)은 갱신해도 유지함
     */
    public static void install(Context ctx, TarSource source, String version,
                               TarExtractor.Progress progress) throws IOException {
        File root = rootfsDir(ctx);
        File keepHome = new File(dataDir(ctx), "home-keep");
        File home = new File(root, "home/steam");

        // Move the home folder aside, then remove the old tree
        // 홈 폴더를 옮겨 두고 이전 트리를 지움
        deleteTree(keepHome);
        if (home.isDirectory() && !home.renameTo(keepHome)) {
            throw new IOException("cannot keep home folder");
        }
        deleteTree(root);
        if (!root.mkdirs() && !root.isDirectory()) throw new IOException("cannot create " + root);

        try (InputStream in = source.open()) {
            TarExtractor.extract(in, root, progress);
        }

        // Restore or create home, and the folders the layer expects
        // 홈을 되돌리거나 만들고, 레이어가 쓰는 폴더를 만듦
        new File(root, "home").mkdirs();
        if (keepHome.isDirectory()) {
            deleteTree(home);
            if (!keepHome.renameTo(home)) throw new IOException("cannot restore home folder");
        } else {
            home.mkdirs();
        }
        for (String d : new String[]{"tmp", "dev/shm", "run/user/ezg"}) new File(root, d).mkdirs();

        try (FileOutputStream os = new FileOutputStream(new File(root, MARKER))) {
            os.write(version.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Asset folder holding the rootfs helper scripts / rootfs 보조 스크립트가 든 asset 폴더 */
    public static final String SCRIPTS_ASSET = "ezsteam";

    /**
     * Copy the helper scripts bundled in the APK (assets/ezsteam/**) over <rootfs>/opt/ezsteam,
     * so the APK's copy wins over the one in an older rootfs. Files are small; all are made
     * executable (0755). Does nothing when the rootfs is not installed.
     * APK에 든 보조 스크립트(assets/ezsteam/**)를 <rootfs>/opt/ezsteam에 덮어 복사함.
     * 이전 rootfs에 든 것보다 APK 쪽이 우선함. 파일이 작아 매번 복사하며 모두 실행 가능(0755)으로 둠.
     * rootfs가 설치되지 않았으면 아무것도 하지 않음
     */
    public static int syncScripts(Context ctx) throws IOException {
        File root = rootfsDir(ctx);
        if (installedVersion(ctx).isEmpty()) return 0;
        return copyAssetTree(ctx, SCRIPTS_ASSET, new File(root, "opt/" + SCRIPTS_ASSET));
    }

    /** Recursive asset copy; returns the number of files written / asset 재귀 복사, 쓴 파일 수를 돌려줌 */
    private static int copyAssetTree(Context ctx, String path, File dest) throws IOException {
        String[] names = ctx.getAssets().list(path);
        if (names == null || names.length == 0) {
            // A file (list() is empty for files) / 파일임(파일은 list()가 비어 있음)
            File parent = dest.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("cannot create " + parent);
            }
            File tmp = new File(dest.getPath() + ".new");
            try (InputStream in = ctx.getAssets().open(path);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            try {
                android.system.Os.chmod(tmp.getPath(), 0755);
            } catch (android.system.ErrnoException e) {
                throw new IOException(e);
            }
            // Replace atomically (a running script keeps its old inode) / 원자적으로 교체(실행 중인 스크립트는 이전 파일 유지)
            if (!tmp.renameTo(dest)) throw new IOException("cannot replace " + dest);
            return 1;
        }
        int count = 0;
        for (String n : names) count += copyAssetTree(ctx, path + "/" + n, new File(dest, n));
        return count;
    }

    /**
     * Delete a tree without following symlinks (lstat-based).
     * 심볼릭 링크를 따라가지 않고 트리를 지움(lstat 기준)
     */
    public static void deleteTree(File f) {
        try {
            android.system.StructStat st = android.system.Os.lstat(f.getPath());
            if (android.system.OsConstants.S_ISDIR(st.st_mode)) {
                // Make sure we may list/delete inside read-only folders
                // 읽기 전용 폴더 안도 지울 수 있게 권한을 엶
                try {
                    android.system.Os.chmod(f.getPath(), 0700);
                } catch (android.system.ErrnoException ignored) {
                    // keep going / 계속 진행
                }
                String[] names = f.list();
                if (names != null) for (String n : names) deleteTree(new File(f, n));
            }
            android.system.Os.remove(f.getPath());
        } catch (android.system.ErrnoException ignored) {
            // Missing already / 이미 없음
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toByteArray();
    }
}
