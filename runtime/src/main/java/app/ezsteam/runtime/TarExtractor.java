package app.ezsteam.runtime;

import android.system.ErrnoException;
import android.system.Os;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal tar extractor (ustar + GNU long names + pax path) that keeps symlinks,
 * hard links and permission bits. No external libraries.
 * 심볼릭 링크·하드 링크·권한 비트를 유지하는 최소 tar 풀기(ustar + GNU 긴 이름 + pax 경로).
 * 외부 라이브러리 없음
 */
public final class TarExtractor {

    /** Progress callback (bytes of file data written) / 진행 콜백(쓴 파일 데이터 바이트) */
    public interface Progress {
        void onProgress(long bytes, String name);
    }

    private static final int BLOCK = 512;

    private TarExtractor() {
    }

    /**
     * Extract a tar stream into dest. Directory permissions are applied at the end so
     * read-only directories do not block writing their children.
     * tar 스트림을 dest에 풂. 읽기 전용 폴더가 하위 쓰기를 막지 않도록 폴더 권한은 마지막에 적용함
     */
    public static void extract(InputStream in, File dest, Progress progress) throws IOException {
        String destCanon = dest.getCanonicalPath();
        byte[] hdr = new byte[BLOCK];
        String longName = null, longLink = null, paxPath = null, paxLink = null;
        List<File> dirs = new ArrayList<>();
        List<Integer> dirModes = new ArrayList<>();
        long written = 0;

        while (true) {
            if (!readBlock(in, hdr)) break;
            if (isZero(hdr)) break; // end of archive / 아카이브 끝

            String name = str(hdr, 0, 100);
            int mode = (int) octal(hdr, 100, 8);
            long size = number(hdr, 124, 12);
            char type = (char) hdr[156];
            String link = str(hdr, 157, 100);
            if ("ustar".equals(str(hdr, 257, 5))) {
                String prefix = str(hdr, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
            }

            // Metadata entries that describe the next entry
            // 다음 항목을 설명하는 메타데이터 항목
            if (type == 'L') { longName = cString(readData(in, size)); continue; }
            if (type == 'K') { longLink = cString(readData(in, size)); continue; }
            if (type == 'x') {
                String[] pl = parsePax(readData(in, size));
                if (pl[0] != null) paxPath = pl[0];
                if (pl[1] != null) paxLink = pl[1];
                continue;
            }
            if (type == 'g') { skip(in, size); continue; }

            if (paxPath != null) name = paxPath; else if (longName != null) name = longName;
            if (paxLink != null) link = paxLink; else if (longLink != null) link = longLink;
            longName = longLink = paxPath = paxLink = null;

            File out = safeFile(dest, destCanon, name);
            if (out == null) { skip(in, size); continue; } // archive root or unsafe path / 루트 또는 위험 경로

            switch (type) {
                case '5': { // directory / 폴더
                    out.mkdirs();
                    chmod(out, 0700 | (mode & 07777));
                    dirs.add(out);
                    dirModes.add(mode & 07777);
                    skip(in, size);
                    break;
                }
                case '2': { // symlink, target kept verbatim / 심볼릭 링크, 대상 그대로 유지
                    out.getParentFile().mkdirs();
                    deleteQuiet(out);
                    try {
                        Os.symlink(link, out.getPath());
                    } catch (ErrnoException e) {
                        throw new IOException("symlink " + name + " -> " + link + ": " + e.getMessage());
                    }
                    skip(in, size);
                    break;
                }
                case '1': { // hard link to an earlier entry / 앞 항목에 대한 하드 링크
                    File target = safeFile(dest, destCanon, link);
                    out.getParentFile().mkdirs();
                    deleteQuiet(out);
                    if (target != null) {
                        try {
                            Os.link(target.getPath(), out.getPath());
                        } catch (ErrnoException e) {
                            // Some app storage setups refuse hard links: copy instead
                            // 하드 링크를 막는 저장소도 있어 복사로 대신함
                            copyFile(target, out);
                            chmod(out, mode & 07777);
                        }
                    }
                    skip(in, size);
                    break;
                }
                case '0':
                case '\0':
                case '7': { // regular file / 일반 파일
                    out.getParentFile().mkdirs();
                    deleteQuiet(out);
                    try (OutputStream os = new FileOutputStream(out)) {
                        copyN(in, os, size);
                    }
                    skipPadding(in, size);
                    chmod(out, mode & 07777);
                    written += size;
                    if (progress != null) progress.onProgress(written, name);
                    break;
                }
                default: // devices, fifos: not usable in app storage / 장치·FIFO는 앱 저장소에서 못 씀
                    skip(in, size);
            }
        }

        // Apply final directory modes deepest-first; owner rwx is kept so the app can
        // update or delete the tree later (e.g. 0555 becomes 0755)
        // 깊은 폴더부터 최종 권한 적용. 앱이 나중에 갱신·삭제할 수 있도록 소유자 rwx는 유지함
        // (예: 0555는 0755가 됨)
        for (int i = dirs.size() - 1; i >= 0; i--) chmod(dirs.get(i), 0700 | dirModes.get(i));
    }

    /** Resolve an archive path under dest; null for the root itself or anything escaping dest
     *  dest 아래 경로로 바꿈. 루트 자체이거나 dest 밖으로 나가면 null */
    private static File safeFile(File dest, String destCanon, String name) throws IOException {
        String n = name;
        while (n.startsWith("./")) n = n.substring(2);
        while (n.startsWith("/")) n = n.substring(1);
        if (n.endsWith("/")) n = n.substring(0, n.length() - 1);
        if (n.isEmpty() || n.equals(".")) return null;
        for (String part : n.split("/")) if (part.equals("..")) return null;
        File f = new File(dest, n);
        // Parent must stay inside dest (the entry itself may be a symlink)
        // 부모 폴더가 dest 안이어야 함(항목 자체는 링크일 수 있음)
        String parent = f.getParentFile().getCanonicalPath();
        if (!parent.equals(destCanon) && !parent.startsWith(destCanon + "/")) return null;
        return f;
    }

    private static void chmod(File f, int mode) {
        try {
            Os.chmod(f.getPath(), mode);
        } catch (ErrnoException ignored) {
            // Best effort / 가능한 만큼만 적용
        }
    }

    private static void deleteQuiet(File f) {
        try {
            Os.remove(f.getPath());
        } catch (ErrnoException ignored) {
            // Did not exist / 없던 파일
        }
    }

    private static void copyFile(File from, File to) throws IOException {
        try (InputStream is = new java.io.FileInputStream(from); OutputStream os = new FileOutputStream(to)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = is.read(b)) > 0) os.write(b, 0, n);
        }
    }

    /** Read one block; false at clean EOF / 블록 하나 읽기, 깔끔한 끝이면 false */
    private static boolean readBlock(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) {
                if (off == 0) return false;
                throw new EOFException("truncated tar header");
            }
            off += n;
        }
        return true;
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String str(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c == 0 || c == ' ') {
                if (v != 0) break;
                continue;
            }
            if (c < '0' || c > '7') break;
            v = v * 8 + (c - '0');
        }
        return v;
    }

    /** Size field: octal, or GNU base-256 when the high bit is set / 크기: 8진수 또는 GNU 256진수 */
    private static long number(byte[] b, int off, int len) {
        if ((b[off] & 0x80) != 0) {
            long v = b[off] & 0x7f;
            for (int i = off + 1; i < off + len; i++) v = (v << 8) | (b[i] & 0xff);
            return v;
        }
        return octal(b, off, len);
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        if (size > 1 << 20) throw new IOException("tar metadata entry too large");
        byte[] d = new byte[(int) size];
        int off = 0;
        while (off < d.length) {
            int n = in.read(d, off, d.length - off);
            if (n < 0) throw new EOFException("truncated tar data");
            off += n;
        }
        skipPadding(in, size);
        return d;
    }

    private static String cString(byte[] d) {
        int end = 0;
        while (end < d.length && d[end] != 0) end++;
        return new String(d, 0, end, StandardCharsets.UTF_8);
    }

    /** pax records "LEN key=value\n"; returns {path, linkpath} / pax 기록에서 path, linkpath만 꺼냄 */
    private static String[] parsePax(byte[] d) {
        String[] r = new String[2];
        int i = 0;
        while (i < d.length) {
            int sp = i;
            while (sp < d.length && d[sp] != ' ') sp++;
            if (sp >= d.length) break;
            int len;
            try {
                len = Integer.parseInt(new String(d, i, sp - i, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                break;
            }
            if (len <= 0 || i + len > d.length) break;
            String rec = new String(d, sp + 1, i + len - sp - 2, StandardCharsets.UTF_8);
            int eq = rec.indexOf('=');
            if (eq > 0) {
                String k = rec.substring(0, eq), v = rec.substring(eq + 1);
                if (k.equals("path")) r[0] = v;
                else if (k.equals("linkpath")) r[1] = v;
            }
            i += len;
        }
        return r;
    }

    private static void copyN(InputStream in, OutputStream out, long n) throws IOException {
        byte[] b = new byte[65536];
        while (n > 0) {
            int r = in.read(b, 0, (int) Math.min(b.length, n));
            if (r < 0) throw new EOFException("truncated tar file data");
            out.write(b, 0, r);
            n -= r;
        }
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long pad = (BLOCK - (size % BLOCK)) % BLOCK;
        skipExact(in, pad);
    }

    private static void skip(InputStream in, long size) throws IOException {
        skipExact(in, size);
        skipPadding(in, size);
    }

    private static void skipExact(InputStream in, long n) throws IOException {
        byte[] b = new byte[8192];
        while (n > 0) {
            int r = in.read(b, 0, (int) Math.min(b.length, n));
            if (r < 0) throw new EOFException("truncated tar");
            n -= r;
        }
    }
}
