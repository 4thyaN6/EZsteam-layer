package app.ezsteam.runtime;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;

import java.io.File;
import java.io.IOException;

/**
 * Shared-storage places used together with EZlator (same layout as EZlator's core/AppDirs and
 * steam/SteamGameInstaller, EZlator 9dfc3a6), for test builds; final places are decided when the
 * layer is merged into EZlator. Internal storage stays separate (files/ezs, files/ezs-data do not
 * clash with EZlator's imagefs, contents, steam ...).
 * EZlator와 함께 쓰는 공용 저장소 위치(EZlator core/AppDirs, steam/SteamGameInstaller와 같은 배치,
 * EZlator 9dfc3a6). 시험 빌드용이며 최종 위치는 레이어를 EZlator에 합칠 때 정함. 내부 저장소는 따로 둠
 * (files/ezs, files/ezs-data는 EZlator의 imagefs, contents, steam 등과 겹치지 않음).
 *
 * - Download/.EZlator/Reports : saved log bundles (ezsteam_report_*.zip, newest 5 kept; EZlator's
 *                               own report_*.zip are never touched)
 *                               저장한 로그 묶음(ezsteam_report_*.zip, 최근 5개 유지. EZlator 자체
 *                               report_*.zip은 건드리지 않음)
 * - /storage/emulated/0/EZlator/SteamLibrary : EZlator's Steam library (release build path), seen by
 *                               the Linux Steam client as an extra library folder
 *                               EZlator Steam 라이브러리(릴리스 빌드 경로). 리눅스 Steam 클라이언트에는
 *                               추가 라이브러리 폴더로 보임
 *
 * Needs the legacy storage permission (targetSdk 28 + WRITE_EXTERNAL_STORAGE), like EZlator.
 * EZlator처럼 기존 방식 저장소 권한이 필요함(targetSdk 28 + WRITE_EXTERNAL_STORAGE).
 */
public final class SharedStorage {

    /** Folder name in Download, same as EZlator's AppDirs.DOWNLOAD_DIR / 다운로드 안 폴더 이름, EZlator와 같음 */
    public static final String DOWNLOAD_DIR = ".EZlator";
    /** Where the shared Steam library appears inside the rootfs / rootfs 안에서 공유 Steam 라이브러리가 보이는 곳 */
    public static final String GUEST_LIBRARY = "/mnt/ezlator-library";
    /** Prefix of this layer's report files / 이 레이어 제보 파일 이름 앞부분 */
    public static final String REPORT_PREFIX = "ezsteam_report_";

    private SharedStorage() {
    }

    /** Write permission for shared storage / 공용 저장소 쓰기 권한 */
    public static boolean granted(Context ctx) {
        return ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Download/.EZlator, created with .nomedia when missing / 없으면 .nomedia와 함께 만듦 */
    public static File root() {
        @SuppressWarnings("deprecation")
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(downloads, DOWNLOAD_DIR);
        if (dir.isDirectory() || dir.mkdirs()) {
            File noMedia = new File(dir, ".nomedia");
            try {
                if (!noMedia.exists()) noMedia.createNewFile();
            } catch (IOException ignored) {
                // gallery hiding only / 갤러리 숨김용일 뿐임
            }
        }
        return dir;
    }

    /** Download/.EZlator/Reports, created when missing / 없으면 만듦 */
    public static File reports() throws IOException {
        File d = new File(root(), "Reports");
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("cannot create " + d);
        return d;
    }

    /**
     * Remove this layer's oldest reports so that, with the one about to be written, at most keep
     * remain. Only files named ezsteam_report_*.zip.
     * 곧 쓸 하나를 포함해 최대 keep개만 남도록 이 레이어의 오래된 제보를 지움. ezsteam_report_*.zip만 대상
     */
    public static void trimReports(File dir, int keep) {
        File[] fs = dir.listFiles((d, n) -> n.startsWith(REPORT_PREFIX) && n.endsWith(".zip"));
        if (fs == null || fs.length < keep) return;
        java.util.Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = keep - 1; i < fs.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            fs[i].delete();
        }
    }

    /**
     * EZlator's Steam library folder (release path /storage/emulated/0/EZlator/SteamLibrary), with
     * its steamapps folder created when missing; null without permission or when it cannot be made.
     * EZlator Steam 라이브러리 폴더(릴리스 경로 /storage/emulated/0/EZlator/SteamLibrary). 없으면
     * steamapps 폴더를 만듦. 권한이 없거나 만들 수 없으면 null
     */
    public static File steamLibrary(Context ctx) {
        if (!granted(ctx)) return null;
        @SuppressWarnings("deprecation")
        File lib = new File(new File(Environment.getExternalStorageDirectory(), "EZlator"), "SteamLibrary");
        File apps = new File(lib, "steamapps");
        if (!apps.isDirectory() && !apps.mkdirs()) return null;
        return lib;
    }
}
