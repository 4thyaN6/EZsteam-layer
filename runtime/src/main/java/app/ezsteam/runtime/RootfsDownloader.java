package app.ezsteam.runtime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Downloads the rootfs from this project's GitHub Release, the same way EZlator fetches its
 * imagefs: only when the user presses the button, nothing is sent besides the HTTP request.
 * Files: rootfs.version, rootfs.tar.xz.sha256, rootfs.tar.xz (published by CI on tagged builds).
 * EZlator가 imagefs를 받는 방식처럼 이 프로젝트 GitHub Release에서 rootfs를 받음.
 * 사용자가 버튼을 누를 때만 받고, HTTP 요청 외에는 아무것도 보내지 않음.
 * 파일: rootfs.version, rootfs.tar.xz.sha256, rootfs.tar.xz(태그 빌드 때 CI가 올림)
 */
public final class RootfsDownloader {

    /** Release asset names / Release 자산 이름 */
    public static final String VERSION = "rootfs.version";
    public static final String ARCHIVE = "rootfs.tar.xz";
    public static final String CHECKSUM = "rootfs.tar.xz.sha256";

    /** Progress callback (bytes received, total or -1) / 진행 콜백(받은 바이트, 전체 또는 -1) */
    public interface Progress {
        void onProgress(long done, long total);
    }

    private RootfsDownloader() {
    }

    /** Read the version string published next to the archive / 압축 파일 옆에 올린 버전 문자열을 읽음 */
    public static String remoteVersion(String baseUrl) throws IOException {
        return new String(fetchSmall(join(baseUrl, VERSION)), StandardCharsets.UTF_8).trim();
    }

    /**
     * Download the archive into dest and verify its SHA-256 against the published checksum.
     * The partial file is deleted on any failure.
     * 압축 파일을 dest에 받고 올라온 체크섬과 SHA-256을 비교함. 실패하면 받던 파일을 지움
     */
    public static void download(String baseUrl, File dest, Progress progress) throws IOException {
        String expected = new String(fetchSmall(join(baseUrl, CHECKSUM)), StandardCharsets.US_ASCII)
                .trim().split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
        if (expected.length() != 64) throw new IOException("bad checksum file");

        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }

        HttpURLConnection c = open(join(baseUrl, ARCHIVE));
        long total = c.getContentLengthLong();
        boolean ok = false;
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 16];
            long done = 0, lastReport = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                done += n;
                // Report every 8 MiB / 8MiB마다 진행 알림
                if (progress != null && done - lastReport >= (8L << 20)) {
                    lastReport = done;
                    progress.onProgress(done, total);
                }
            }
            if (total > 0 && done != total) throw new IOException("incomplete download " + done + "/" + total);
            String got = hex(md.digest());
            if (!got.equals(expected)) throw new IOException("sha256 mismatch");
            ok = true;
        } finally {
            c.disconnect();
            if (!ok) dest.delete();
        }
    }

    /** Join base URL and asset name with exactly one '/' / 기본 주소와 자산 이름을 '/' 하나로 이음 */
    private static String join(String base, String name) {
        return base.endsWith("/") ? base + name : base + "/" + name;
    }

    /** Open a GET connection; GitHub release links redirect to another https host
     *  GET 연결을 엶. GitHub Release 주소는 다른 https 호스트로 넘어감 */
    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "ezsteam-layer");
        int code = c.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            c.disconnect();
            throw new IOException("HTTP " + code + " " + url);
        }
        return c;
    }

    /** Fetch a small text asset (max 64 KiB) / 작은 텍스트 자산을 받음(최대 64KiB) */
    private static byte[] fetchSmall(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) {
                bo.write(b, 0, n);
                if (bo.size() > 65536) throw new IOException("asset too large: " + url);
            }
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format(java.util.Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}
