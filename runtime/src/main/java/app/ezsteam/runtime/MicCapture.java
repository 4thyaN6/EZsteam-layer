package app.ezsteam.runtime;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.Closeable;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The phone's microphone for programs in the rootfs, shared by every reader: PulseServer record
 * streams (Steam web helper, e.g. voice chat) and ALSA capture streams of games (pcm_ezs.c through
 * AudioServer). One Android AudioRecord (48 kHz mono 16-bit) runs only while at least one reader is
 * open, so the microphone indicator shows only while a program really records. The RECORD_AUDIO
 * permission is asked the first time a program opens a record stream (not at app start); until it
 * is granted, or when it is refused, readers get silence and the program keeps running. Each reader
 * has its own 200 ms ring; when a reader falls behind, its oldest samples are dropped, so the delay
 * stays short. Threads of the app, no extra process. Nothing leaves the device.
 * Voice source: VOICE_COMMUNICATION, so the platform's echo cancelling (when the device has it)
 * keeps the game sound from the speaker out of voice chat (inference: depends on the device).
 * rootfs 안 프로그램용 휴대폰 마이크. 읽는 쪽 모두가 함께 씀: PulseServer 녹음 스트림(Steam 웹 도우미, 예: 음성 채팅)과
 * 게임의 ALSA 녹음 스트림(pcm_ezs.c → AudioServer). 안드로이드 AudioRecord(48kHz 모노 16비트) 하나를 읽는 쪽이
 * 하나라도 열려 있을 때만 돌림. 그래서 마이크 표시는 프로그램이 실제로 녹음할 때만 뜸. RECORD_AUDIO 권한은 프로그램이
 * 처음 녹음 스트림을 열 때 물음(앱 시작 때 아님). 허용 전이나 거부되면 무음을 주고 프로그램은 계속 돎. 읽는 쪽마다 200ms
 * 링을 가짐. 읽는 쪽이 늦으면 가장 오래된 샘플을 버려 지연이 짧게 유지됨. 앱의 스레드라 추가 프로세스 없음. 기기 밖으로
 * 나가는 데이터 없음.
 * 음성 소스: VOICE_COMMUNICATION. 기기에 에코 제거가 있으면 스피커의 게임 소리가 음성 채팅에 섞이지 않게 함
 * (추론: 기기마다 다름)
 */
public final class MicCapture implements Closeable {

    /** Asks the user for RECORD_AUDIO (called once per session, on any thread)
     *  사용자에게 RECORD_AUDIO를 물음(세션에 한 번, 아무 스레드에서나 불림) */
    public interface Asker {
        void ask();
    }

    /** Capture format: 48 kHz mono 16-bit / 녹음 형식: 48kHz 모노 16비트 */
    public static final int RATE = 48000;
    /** One read: 10 ms / 한 번 읽기: 10ms */
    private static final int READ = RATE / 100;
    /** Per-reader ring: 200 ms / 읽는 쪽별 링: 200ms */
    private static final int RING = RATE / 5;

    private final Context ctx;
    private final Asker asker;
    private final AudioServer.Log log;
    private final CopyOnWriteArrayList<Tap> taps = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();
    private volatile boolean closed;
    private boolean asked;
    private Thread worker;

    public MicCapture(Context ctx, Asker asker, AudioServer.Log log) {
        this.ctx = ctx.getApplicationContext();
        this.asker = asker;
        this.log = log;
    }

    /** One reader's samples (mono, 48 kHz) / 읽는 쪽 하나의 샘플(모노, 48kHz) */
    public final class Tap implements Closeable {
        private final short[] ring = new short[RING];
        private int head, count;
        private long dropped;

        private synchronized void push(short[] s, int n) {
            for (int i = 0; i < n; i++) {
                ring[(head + count) % RING] = s[i];
                if (count < RING) {
                    count++;
                } else {
                    // Full: the oldest sample goes / 가득 참: 가장 오래된 샘플을 버림
                    head = (head + 1) % RING;
                    dropped++;
                }
            }
            notifyAll();
        }

        /** Take up to n samples without waiting; returns how many were there
         *  기다리지 않고 최대 n개를 가져옴. 있던 개수를 돌려줌 */
        public synchronized int poll(short[] dst, int n) {
            int k = Math.min(n, count);
            for (int i = 0; i < k; i++) dst[i] = ring[(head + i) % RING];
            head = (head + k) % RING;
            count -= k;
            return k;
        }

        /** Take n samples, waiting up to waitMs for them; the missing rest is silence
         *  n개를 가져오되 최대 waitMs 기다림. 모자란 나머지는 무음 */
        public synchronized int take(short[] dst, int n, long waitMs) throws InterruptedException {
            long end = System.nanoTime() + waitMs * 1_000_000L;
            while (count < n && !closed) {
                long left = (end - System.nanoTime()) / 1_000_000L;
                if (left <= 0) break;
                wait(left);
            }
            int k = poll(dst, n);
            for (int i = k; i < n; i++) dst[i] = 0;
            return k;
        }

        /** Drop what is queued (record stream resumed after a pause) / 쌓인 것을 버림(멈췄던 녹음 스트림을 다시 시작할 때) */
        public synchronized void clear() {
            head = count = 0;
        }

        @Override
        public void close() {
            if (taps.remove(this)) {
                log.log(String.format(Locale.ROOT, "AUDIO 마이크 읽기 닫음 (남은 %d개%s)", taps.size(),
                        dropped > 0 ? String.format(Locale.ROOT, ", 밀려 버림 %.0fms", dropped * 1000.0 / RATE) : ""));
            }
            synchronized (this) {
                notifyAll();
            }
        }
    }

    /** True when RECORD_AUDIO is granted / RECORD_AUDIO가 허용됐으면 참 */
    public boolean permitted() {
        return ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** A new reader; starts the microphone (asking for permission first if needed)
     *  새 읽는 쪽. 마이크를 시작함(필요하면 먼저 권한을 물음) */
    public Tap open(String who) {
        Tap t = new Tap();
        taps.add(t);
        boolean ok = permitted();
        log.log("AUDIO 마이크 읽기 열림: " + who + " (동시 " + taps.size() + "개, 권한 " + (ok ? "있음" : "없음 → 무음") + ")");
        synchronized (lock) {
            if (!ok && !asked) {
                asked = true;
                try {
                    asker.ask();
                } catch (RuntimeException e) {
                    log.log("AUDIO 마이크 권한 묻기 실패: " + e);
                }
            }
            if (worker == null || !worker.isAlive()) {
                worker = new Thread(this::run, "ezs-mic");
                worker.setDaemon(true);
                worker.start();
            }
            lock.notifyAll();
        }
        return t;
    }

    /**
     * Capture loop: records while readers exist and the permission is there; otherwise waits
     * (checks the permission again every 0.5 s, so a grant takes effect without reopening).
     * 녹음 반복: 읽는 쪽이 있고 권한이 있으면 녹음함. 아니면 기다림(0.5초마다 권한을 다시 봄. 허용되면 다시 열지 않아도 됨)
     */
    private void run() {
        short[] buf = new short[READ];
        AudioRecord rec = null;
        long frames = 0;
        try {
            while (!closed) {
                if (taps.isEmpty() || !permitted()) {
                    if (rec != null) {
                        rec = stop(rec, frames);
                        frames = 0;
                    }
                    synchronized (lock) {
                        lock.wait(500);
                    }
                    continue;
                }
                if (rec == null) {
                    rec = startRecord();
                    if (rec == null) {
                        // Could not open: try again in a while, readers keep getting silence
                        // 열지 못함: 잠시 뒤 다시 시도함. 읽는 쪽은 계속 무음을 받음
                        synchronized (lock) {
                            lock.wait(2000);
                        }
                        continue;
                    }
                }
                int n = rec.read(buf, 0, buf.length);
                if (n < 0) {
                    log.log("AUDIO 마이크 읽기 오류 " + n + ", 다시 엶");
                    rec = stop(rec, frames);
                    frames = 0;
                    continue;
                }
                frames += n;
                for (Tap t : taps) t.push(buf, n);
            }
        } catch (InterruptedException ignored) {
        } finally {
            if (rec != null) stop(rec, frames);
        }
    }

    /** AudioRecord at 48 kHz mono; null when the device refuses / 48kHz 모노 AudioRecord. 기기가 거부하면 null */
    private AudioRecord startRecord() {
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) {
                log.log("AUDIO 마이크 형식 지원 안 함 (" + min + ")");
                return null;
            }
            AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE / 10 * 2));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                log.log("AUDIO 마이크 열기 실패 (초기화 안 됨)");
                return null;
            }
            r.startRecording();
            log.log(String.format(Locale.ROOT, "AUDIO 마이크 시작 %dHz 1ch (버퍼 %.0fms)", RATE, min * 1000.0 / (RATE * 2)));
            return r;
        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
            log.log("AUDIO 마이크 열기 실패: " + e);
            return null;
        }
    }

    private AudioRecord stop(AudioRecord r, long frames) {
        try {
            r.stop();
        } catch (IllegalStateException ignored) {
        }
        r.release();
        log.log(String.format(Locale.ROOT, "AUDIO 마이크 멈춤 (%.1f초 녹음)", frames / (double) RATE));
        return null;
    }

    @Override
    public void close() {
        closed = true;
        for (Tap t : taps) t.close();
        synchronized (lock) {
            lock.notifyAll();
        }
    }
}
