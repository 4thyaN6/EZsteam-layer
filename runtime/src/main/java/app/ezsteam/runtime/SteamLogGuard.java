package app.ezsteam.runtime;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * Keeps the Steam web helper logs (Steam/logs/cef_log*.txt, steamwebhelper*.log) from filling the
 * device. When a file takes more than LIMIT on disk, it is cut back to its first KEEP_HEAD bytes
 * (the head holds the start-up lines, where the cause of a repeating error was found); the writer
 * goes on writing after that. Why: after the Steam client update of 2026-11-12 the web helper
 * repeated a GL context error without end, the logs grew about 1.5 GB a minute and the device
 * storage reached 99 % (about 73 GB of these logs).
 *
 * Writers: Chromium opens its --log-file (cef_log.txt) for appending, so it continues right after
 * the kept head. Steam's wrapper opens steamwebhelper.log with "&>" (not appending): its next
 * write lands at its old offset and the file gets a hole, which takes no disk space. That is why
 * the size checked is the space used on disk, not the length.
 *
 * Steam 웹 도우미 로그(Steam/logs/cef_log*.txt, steamwebhelper*.log)가 기기를 채우지 않게 함. 파일이 디스크에서
 * LIMIT보다 많이 차지하면 앞의 KEEP_HEAD 바이트만 남기고 잘라냄(앞부분에 시작 줄이 있고, 반복 오류의 원인도 거기서 찾았음).
 * 쓰는 쪽은 그 뒤로 계속 씀. 이유: 2026-11-12 Steam 클라이언트 갱신 뒤 웹 도우미가 GL 컨텍스트 오류를 끝없이 반복했고,
 * 로그가 분당 약 1.5 GB씩 늘어 기기 저장공간이 99%가 됐음(이 로그 약 73 GB).
 *
 * 쓰는 쪽: Chromium은 --log-file(cef_log.txt)을 이어 쓰기로 열므로 남긴 앞부분 바로 뒤에 이어 씀. Steam 감쌈 스크립트는
 * steamwebhelper.log를 "&>"(이어 쓰기 아님)로 열어서 다음 쓰기가 예전 위치에 들어가고 파일에 구멍이 생김. 구멍은 디스크를
 * 차지하지 않음. 그래서 길이가 아니라 디스크 사용량으로 비교함
 */
public final class SteamLogGuard {

    /** Space on disk above which a file is cut / 이보다 디스크를 많이 쓰면 자름 */
    public static final long LIMIT = 32L << 20;
    /** Bytes kept from the start of a cut file / 자른 파일의 앞에서 남기는 바이트 */
    public static final long KEEP_HEAD = 1L << 20;

    /** One cut file / 자른 파일 하나 */
    public static final class Cut {
        public final String name;
        /** Space on disk before the cut / 자르기 전 디스크 사용량 */
        public final long diskBefore;

        Cut(String name, long diskBefore) {
            this.name = name;
            this.diskBefore = diskBefore;
        }
    }

    private SteamLogGuard() {
    }

    /** Files this guard looks after / 이 장치가 맡는 파일 */
    public static boolean watched(String name) {
        return (name.startsWith("cef_log") && name.endsWith(".txt"))
                || (name.startsWith("steamwebhelper") && name.endsWith(".log"));
    }

    /**
     * Cut every watched file in logsDir that uses more than limit on disk back to keepHead bytes.
     * Files that cannot be read or cut are skipped (checked again on the next call).
     * logsDir 안에서 디스크를 limit보다 많이 쓰는 맡은 파일을 keepHead 바이트로 자름. 읽거나 자를 수 없는 파일은
     * 건너뜀(다음 호출에 다시 봄)
     */
    public static List<Cut> trim(File logsDir, long limit, long keepHead, ToLongFunction<File> diskBytes) {
        List<Cut> cuts = new ArrayList<>();
        File[] fs = logsDir.listFiles((d, n) -> watched(n));
        if (fs == null) return cuts;
        Arrays.sort(fs);
        for (File f : fs) {
            if (!f.isFile()) continue;
            long used = diskBytes.applyAsLong(f);
            if (used <= limit) continue;
            try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
                if (raf.length() > keepHead) raf.setLength(keepHead);
                cuts.add(new Cut(f.getName(), used));
            } catch (IOException | SecurityException ignored) {
            }
        }
        return cuts;
    }

    /**
     * Space a file takes on disk (512-byte blocks), or its length where that cannot be read
     * (stat failed, or JVM tests where android.jar only has stubs that throw)
     * 파일이 디스크에서 차지하는 크기(512바이트 블록). 읽을 수 없으면(stat 실패, 또는 android.jar가 예외만 던지는 JVM 시험) 길이
     */
    public static long diskBytes(File f) {
        try {
            return android.system.Os.stat(f.getPath()).st_blocks * 512L;
        } catch (Exception e) {
            return f.length();
        }
    }
}
