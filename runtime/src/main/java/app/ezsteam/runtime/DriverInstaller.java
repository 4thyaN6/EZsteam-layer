package app.ezsteam.runtime;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Installs glibc Vulkan driver packages (first: Mesa Turnip for Adreno via KGSL) and wires them
 * into the guest. Packages come from this project's GitHub Release like the rootfs, only when
 * the user asks. They live outside the rootfs (files/ezs-data/drivers/<name>) so a rootfs update
 * keeps them, and are bound into the guest at /opt/ezdrv/<name>.
 * glibc Vulkan 드라이버 묶음(먼저 KGSL용 Mesa Turnip)을 설치하고 guest에 연결함.
 * rootfs처럼 이 프로젝트 GitHub Release에서 사용자가 요청할 때만 받음.
 * rootfs 밖(files/ezs-data/drivers/<이름>)에 두어 rootfs를 갱신해도 남고,
 * guest에는 /opt/ezdrv/<이름>으로 바인드됨
 *
 * Package layout (made by tools/build-turnip-glibc.sh):
 * 묶음 구조(tools/build-turnip-glibc.sh가 만듦):
 *   icd.json                      Vulkan ICD manifest, library_path relative to this file
 *                                 Vulkan ICD 매니페스트, library_path는 이 파일 기준 상대 경로
 *   lib/libvulkan_*.so            driver / 드라이버
 *   env                           optional KEY=VALUE lines applied when the driver is active
 *                                 드라이버 사용 시 넣을 KEY=VALUE 줄(선택)
 *   LICENSE, source.txt           license notice and exact source / 라이선스 고지, 사용 소스
 */
public final class DriverInstaller {

    /** Guest folder where all driver packages appear / 드라이버 묶음이 보이는 guest 폴더 */
    public static final String GUEST_DIR = "/opt/ezdrv";

    /** Release asset names for a package / 묶음의 Release 자산 이름 */
    public static String archiveName(String name) {
        return name + ".tar.xz";
    }

    public static String checksumName(String name) {
        return name + ".tar.xz.sha256";
    }

    public static String versionName(String name) {
        return name + ".version";
    }

    private static final String MARKER = ".version";

    private DriverInstaller() {
    }

    /** Real folder holding all packages: files/ezs-data/drivers / 모든 묶음이 있는 실제 폴더 */
    public static File driversDir(Context ctx) {
        return new File(RootfsInstaller.dataDir(ctx), "drivers");
    }

    /** Real folder of one package / 묶음 하나의 실제 폴더 */
    public static File packageDir(Context ctx, String name) {
        return new File(driversDir(ctx), name);
    }

    /** Installed version ("" if none) / 설치된 버전(없으면 "") */
    public static String installedVersion(Context ctx, String name) {
        File m = new File(packageDir(ctx, name), MARKER);
        try (InputStream in = new FileInputStream(m)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[256];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Download (sha256 checked) and install a package from the Release, replacing an older one.
     * Returns the installed version. Nothing happens when the same version is already there.
     * Release에서 묶음을 받아(sha256 확인) 설치하고 이전 것을 바꿈. 설치된 버전을 돌려줌.
     * 같은 버전이 이미 있으면 아무것도 하지 않음
     */
    public static String installFromRelease(Context ctx, String baseUrl, String name,
                                            RootfsDownloader.Progress progress) throws IOException {
        String version = RootfsDownloader.remoteText(baseUrl, versionName(name));
        if (version.equals(installedVersion(ctx, name))) return version;

        File archive = new File(ctx.getCacheDir(), archiveName(name));
        try {
            RootfsDownloader.download(baseUrl, archiveName(name), checksumName(name), archive, progress);
            install(ctx, name, archive, version);
        } finally {
            // The archive is not kept / 압축 파일은 보관하지 않음
            archive.delete();
        }
        return version;
    }

    /** Wipe and extract a package from a local .tar.xz, then record its version
     *  로컬 .tar.xz에서 묶음을 지우고 새로 푼 뒤 버전을 기록함 */
    public static void install(Context ctx, String name, File tarXz, String version) throws IOException {
        File dir = packageDir(ctx, name);
        RootfsInstaller.deleteTree(dir);
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create " + dir);
        try (InputStream in = new org.tukaani.xz.XZInputStream(
                new BufferedInputStream(new FileInputStream(tarXz), 1 << 16))) {
            TarExtractor.extract(in, dir, null);
        }
        if (!new File(dir, "icd.json").isFile()) {
            RootfsInstaller.deleteTree(dir);
            throw new IOException("package has no icd.json");
        }
        try (FileOutputStream os = new FileOutputStream(new File(dir, MARKER))) {
            os.write(version.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Make an installed package active for programs started with this config: bind the drivers
     * folder into the guest, point the Vulkan loader at the package's manifest and add the
     * package's own env lines. Returns false when the package is not installed.
     * 설치된 묶음을 이 설정으로 실행하는 프로그램에 적용함: 드라이버 폴더를 guest에 바인드하고,
     * Vulkan 로더가 묶음의 매니페스트를 쓰게 하고, 묶음의 env 줄을 더함. 미설치면 false
     */
    public static boolean apply(Context ctx, GlibcLauncher.Config c, String name) throws IOException {
        if (installedVersion(ctx, name).isEmpty()) return false;
        c.binds.add(new String[]{driversDir(ctx).getAbsolutePath(), GUEST_DIR});

        String icd = GUEST_DIR + "/" + name + "/icd.json";
        // VK_DRIVER_FILES replaces the default driver search; VK_ICD_FILENAMES is the older name
        // VK_DRIVER_FILES는 기본 드라이버 탐색을 대체함. VK_ICD_FILENAMES는 예전 이름
        c.env.put("VK_DRIVER_FILES", icd);
        c.env.put("VK_ICD_FILENAMES", icd);

        File envFile = new File(packageDir(ctx, name), "env");
        if (envFile.isFile()) {
            for (String line : java.nio.file.Files.readAllLines(envFile.toPath(), StandardCharsets.UTF_8)) {
                String t = line.trim();
                int eq = t.indexOf('=');
                if (t.isEmpty() || t.startsWith("#") || eq <= 0) continue;
                c.env.put(t.substring(0, eq), t.substring(eq + 1));
            }
        }
        return true;
    }
}
