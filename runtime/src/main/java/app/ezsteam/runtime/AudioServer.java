package app.ezsteam.runtime;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Plays the sound of programs in the rootfs: listens on a UNIX socket inside the rootfs
 * (guest path /tmp/ezs-audio.sock) for the ALSA plugin native/ezsaudio/pcm_ezs.c. One Android
 * output (AudioTrack, 48 kHz stereo) stays open for the whole session and every connection is
 * mixed into it, the way a sound server keeps its device open. Before, each connection got its own
 * AudioTrack that was made when the stream opened and released when it closed: Big Picture's
 * entry sound played, but its short navigation sounds were never heard (device logs 2026-10-26,
 * one stream per entry, none after). Building an AudioTrack takes time and its first frames ramp
 * in, which can swallow a sound of a tenth of a second (inference); an output that is already
 * running plays them at once. Runs as threads of the app, so no extra process is started (Android
 * allows 32 extra processes for all apps together). Nothing leaves the device.
 * rootfs 안 프로그램의 소리를 재생함: rootfs 안 UNIX 소켓(guest 경로 /tmp/ezs-audio.sock)에서 ALSA 플러그인
 * native/ezsaudio/pcm_ezs.c의 연결을 받음. 안드로이드 출력(AudioTrack, 48kHz 스테레오) 하나를 세션 내내 열어 두고
 * 모든 연결을 거기에 섞음(사운드 서버가 장치를 열어 두는 방식). 전에는 연결마다 스트림이 열릴 때 AudioTrack을 만들고
 * 닫힐 때 해제했음: 빅픽처 진입음은 났지만 짧은 이동 효과음은 들리지 않았음(기기 로그 2026-10-26, 진입마다 스트림
 * 1개, 그 뒤 없음). AudioTrack을 만드는 데 시간이 걸리고 첫 프레임은 서서히 커져 0.1초짜리 소리를 삼킬 수 있음
 * (추론). 이미 돌고 있는 출력은 바로 재생함. 앱의 스레드로 동작하므로 추가 프로세스가 없음(안드로이드는 모든 앱 합계
 * 추가 프로세스 32개까지 허용). 기기 밖으로 나가는 데이터 없음
 *
 * Stream per connection: 16-byte header ("EZSA", version 1, rate, channels; little-endian uint32),
 * then interleaved signed 16-bit little-endian samples until the plugin closes the connection.
 * 48 kHz is mixed (mono is spread to both sides); any other rate gets its own AudioTrack as before.
 * Capture (the plugin's record streams): header "EZSR" (version 1, 48000, 2), then the app sends
 * the microphone (MicCapture) as 48 kHz stereo 16-bit until the plugin closes; without a
 * MicCapture the connection is closed and the plugin gives silence as before.
 * 연결당 스트림: 16바이트 머리("EZSA", 버전 1, rate, channels, 리틀엔디언 uint32), 그 뒤로 플러그인이 연결을 닫을 때까지
 * 인터리브된 16비트 부호 있는 리틀엔디언 샘플. 48kHz는 섞음(모노는 양쪽으로), 다른 주파수는 전처럼 따로 AudioTrack을 씀.
 * 녹음(플러그인의 녹음 스트림): 머리 "EZSR"(버전 1, 48000, 2) 뒤 플러그인이 닫을 때까지 앱이 마이크(MicCapture)를
 * 48kHz 스테레오 16비트로 보냄. MicCapture가 없으면 연결을 닫고 플러그인은 전처럼 무음을 줌
 */
public final class AudioServer implements Closeable {

    /** Log sink / 로그 출력 */
    public interface Log {
        void log(String line);
    }

    /** Guest path of the socket, also written into ~/.asoundrc by steam-run.sh
     *  소켓의 guest 경로. steam-run.sh가 ~/.asoundrc에 같은 값을 씀 */
    public static final String GUEST_SOCKET = "/tmp/ezs-audio.sock";

    /** Mixed output format / 섞는 출력 형식 */
    private static final int RATE = 48000, CH = 2;
    /** Mixer step: 5 ms / 믹서 한 번: 5ms */
    private static final int CHUNK = RATE / 200;
    /** Per-stream buffer: 250 ms; the reader waits when it is full (the plugin's socket then fills)
     *  스트림별 버퍼: 250ms. 가득 차면 읽는 쪽이 기다림(그러면 플러그인 소켓이 참) */
    private static final int RING = RATE / 4;
    /** A stream joins the mix once it has 20 ms (or has waited that long), against clicks at its start
     *  스트림은 20ms가 모이면(또는 그만큼 기다리면) 섞기에 들어감. 시작 때 딸깍 소리 방지 */
    private static final int PREBUF = RATE / 50;

    private final File socketFile;
    private final LocalServerSocket server;
    /**
     * The bound socket whose descriptor the server listens on. Kept referenced: when it is
     * garbage-collected its finalizer closes that descriptor and accept() fails with EBADF
     * (measured: the first game stream played, the next accept failed).
     * 서버가 듣는 디스크립터를 가진 바인드된 소켓. 참조를 유지해야 함: 가비지 수집되면 종료자가
     * 그 디스크립터를 닫아 accept()가 EBADF로 실패함(실측: 첫 게임 스트림 뒤 다음 accept 실패)
     */
    private final LocalSocket bound;
    private final Log log;
    private volatile boolean closed;
    /** Connections now playing / 지금 재생 중인 연결 수 */
    private final java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
    /** A read counts as sound from this peak (about -40 dBFS) / 이 최대값부터 소리로 셈(약 -40 dBFS) */
    private static final int PEAK_LOUD = 330;

    /** Streams being mixed / 섞는 중인 스트림 */
    private final CopyOnWriteArrayList<Source> sources = new CopyOnWriteArrayList<>();
    private AudioTrack out;
    /** Microphone for the plugin's record streams, null = they stay silent
     *  플러그인 녹음 스트림용 마이크, null이면 무음 그대로 */
    private volatile MicCapture mic;

    private AudioServer(File socketFile, LocalSocket bound, LocalServerSocket server, Log log) {
        this.socketFile = socketFile;
        this.bound = bound;
        this.server = server;
        this.log = log;
    }

    /**
     * Bind the socket at rootfs + GUEST_SOCKET (an old socket file is replaced), open the shared
     * output and start accepting.
     * rootfs + GUEST_SOCKET 위치에 소켓을 만들고(이전 소켓 파일은 바꿈) 공용 출력을 연 뒤 연결을 받기 시작함
     */
    public static AudioServer start(File rootfs, Log log) throws IOException {
        File f = new File(rootfs, GUEST_SOCKET.substring(1));
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        if (f.exists() && !f.delete()) throw new IOException("cannot replace " + f);
        LocalSocket s = new LocalSocket();
        s.bind(new LocalSocketAddress(f.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
        LocalServerSocket server = new LocalServerSocket(s.getFileDescriptor());
        AudioServer a = new AudioServer(f, s, server, log);
        a.openOutput();
        Thread m = new Thread(a::mixLoop, "ezs-audio-mix");
        m.setDaemon(true);
        m.setPriority(Thread.MAX_PRIORITY);
        m.start();
        Thread t = new Thread(a::acceptLoop, "ezs-audio-accept");
        t.setDaemon(true);
        t.start();
        return a;
    }

    /** Microphone for record streams opened from now on / 이제부터 열리는 녹음 스트림에 쓸 마이크 */
    public void setMic(MicCapture m) {
        mic = m;
    }

    public int activeStreams() {
        return active.get();
    }

    /** A new stream in the mix (PulseServer playback) / 섞기에 들어가는 새 스트림(PulseServer 재생) */
    Source attach() {
        Source s = new Source();
        sources.add(s);
        return s;
    }

    /** Output buffer in frames, part of the latency PulseServer reports / 출력 버퍼(프레임), PulseServer가 알리는 지연의 일부 */
    int outputFrames() {
        AudioTrack t = out;
        return t == null ? 0 : t.getBufferSizeInFrames();
    }

    private static AudioTrack newTrack(int rate, int channels, int sizeBytes) {
        int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .build())
                .setBufferSizeInBytes(sizeBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build();
    }

    /** The shared output: about 40 ms of buffer, playing (silence) from the start
     *  공용 출력: 버퍼 약 40ms, 처음부터 재생(무음) */
    private void openOutput() {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        out = newTrack(RATE, CH, Math.max(min, RATE * 4 / 25));
        out.setBufferSizeInFrames(Math.max(CHUNK * 4, RATE / 25));
        out.play();
        log.log(String.format(Locale.ROOT, "AUDIO 출력 열림 %dHz %dch (섞기, 버퍼 %.0f ms, 세션 내내 유지)",
                RATE, CH, out.getBufferSizeInFrames() * 1000f / RATE));
    }

    /** One stream's samples between its reader and the mixer (stereo frames, 48 kHz). Also used
     *  by PulseServer for its playback streams
     *  읽는 쪽과 믹서 사이의 한 스트림 샘플(스테레오 프레임, 48kHz). PulseServer의 재생 스트림도 씀 */
    static final class Source {
        final short[] ring = new short[RING * CH];
        int head, tail, count;
        boolean started, ended;
        /** Corked (PulseAudio): kept, not played / 멈춤(PulseAudio): 남겨 두고 재생하지 않음 */
        boolean paused;
        long firstAt;
        /** Frames the mixer has taken in total / 믹서가 지금까지 가져간 프레임 수 */
        long played;

        /** Capacity in frames / 담을 수 있는 프레임 수 */
        static int capacity() {
            return RING;
        }

        /** Put up to frames without waiting; returns how many fit (the rest is dropped by the caller)
         *  기다리지 않고 최대 frames를 넣음. 들어간 수를 돌려줌(나머지는 부르는 쪽이 버림) */
        synchronized int offer(short[] s, int frames) {
            int k = Math.min(frames, RING - count);
            for (int i = 0; i < k; i++) {
                ring[tail * 2] = s[i * 2];
                ring[tail * 2 + 1] = s[i * 2 + 1];
                tail = (tail + 1) % RING;
            }
            count += k;
            if (k > 0 && firstAt == 0) firstAt = System.nanoTime();
            return k;
        }

        /** Frames waiting to be played / 재생을 기다리는 프레임 수 */
        synchronized int queued() {
            return count;
        }

        /** Drop everything queued (flush); the next data prebuffers again
         *  쌓인 것을 모두 버림(flush). 다음 데이터는 다시 미리 모음 */
        synchronized void clear() {
            head = tail = count = 0;
            started = false;
            firstAt = 0;
            notifyAll();
        }

        synchronized void setPaused(boolean p) {
            paused = p;
        }

        synchronized boolean isStarted() {
            return started;
        }

        synchronized long playedFrames() {
            return played;
        }

        synchronized void put(short[] s, int frames) throws InterruptedException {
            int i = 0;
            while (i < frames) {
                while (count == RING && !ended) wait(50);
                if (ended) return;
                ring[tail * 2] = s[i * 2];
                ring[tail * 2 + 1] = s[i * 2 + 1];
                tail = (tail + 1) % RING;
                count++;
                i++;
            }
            if (firstAt == 0) firstAt = System.nanoTime();
        }

        /** Add up to n frames into acc; false when the stream is finished and empty
         *  acc에 최대 n프레임을 더함. 스트림이 끝나고 비었으면 false */
        synchronized boolean mixInto(int[] acc, int n) {
            if (paused) return !ended;
            if (!started) {
                boolean waited = firstAt != 0 && System.nanoTime() - firstAt > 20_000_000L;
                if (count >= PREBUF || waited || (ended && count > 0)) started = true;
                else return !(ended && count == 0);
            }
            int k = Math.min(n, count);
            for (int i = 0; i < k; i++) {
                acc[i * 2] += ring[head * 2];
                acc[i * 2 + 1] += ring[head * 2 + 1];
                head = (head + 1) % RING;
            }
            count -= k;
            played += k;
            if (k > 0) notifyAll();
            return !(ended && count == 0);
        }

        synchronized void end() {
            ended = true;
            notifyAll();
        }
    }

    /** Mixer: every 5 ms the sum of all streams (silence when none), clipped; AudioTrack.write paces it
     *  믹서: 5ms마다 모든 스트림의 합(없으면 무음), 넘치면 자름. AudioTrack.write가 박자를 맞춤 */
    private void mixLoop() {
        int[] acc = new int[CHUNK * CH];
        short[] buf = new short[CHUNK * CH];
        while (!closed) {
            java.util.Arrays.fill(acc, 0);
            for (Source s : sources) {
                if (!s.mixInto(acc, CHUNK)) sources.remove(s);
            }
            for (int i = 0; i < acc.length; i++) {
                int v = acc[i];
                buf[i] = (short) (v > 32767 ? 32767 : Math.max(v, -32768));
            }
            int off = 0;
            while (off < buf.length && !closed) {
                int w = out.write(buf, off, buf.length - off);
                if (w < 0) {
                    log.log("AUDIO 출력 쓰기 오류 " + w + ", 출력 다시 엶");
                    try {
                        out.release();
                    } catch (RuntimeException ignored) {
                    }
                    try {
                        openOutput();
                    } catch (RuntimeException e) {
                        log.log("AUDIO 출력 다시 열기 실패: " + e);
                        return;
                    }
                    break;
                }
                off += w;
            }
        }
        try {
            out.stop();
        } catch (IllegalStateException ignored) {
        }
        out.release();
    }

    private void acceptLoop() {
        while (!closed) {
            LocalSocket c;
            try {
                c = server.accept();
            } catch (IOException e) {
                if (!closed) log.log("AUDIO 연결 받기 실패: " + e);
                return;
            }
            if (closed) {
                closeQuietly(c);
                return;
            }
            Thread t = new Thread(() -> play(c), "ezs-audio-stream");
            t.setDaemon(true);
            t.start();
        }
    }

    /** One connection: read the header, then feed the mixer (48 kHz) or a track of its own
     *  연결 하나: 머리를 읽고 믹서(48kHz) 또는 자체 트랙에 넣음 */
    private void play(LocalSocket c) {
        long bytes = 0;
        // Sound bursts in this stream (a silent run, then samples above PEAK_LOUD): tells whether
        // later UI sounds still arrive in an open stream or never reach the app (Big Picture played
        // its start sound and then nothing, user report 2026-10-30)
        // 이 스트림의 소리 구간(조용하다가 PEAK_LOUD를 넘는 샘플): 열린 스트림에 나중 UI 소리가 계속 오는지,
        // 아예 앱에 오지 않는지 가림(빅픽처가 시작음만 내고 그 뒤 소리 없음, 사용자 제보 2026-10-30)
        int bursts = 0, peakMax = 0;
        long firstBurst = -1, lastBurst = -1;
        boolean loud = false;
        int rate = RATE, channels = CH;
        Source src = null;
        AudioTrack own = null;
        try (InputStream in = c.getInputStream()) {
            byte[] h = new byte[16];
            new DataInputStream(in).readFully(h);
            ByteBuffer hb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            if (h[0] == 'E' && h[1] == 'Z' && h[2] == 'S' && h[3] == 'R' && hb.getInt(4) == 1) {
                record(c);
                return;
            }
            if (h[0] != 'E' || h[1] != 'Z' || h[2] != 'S' || h[3] != 'A' || hb.getInt(4) != 1) {
                log.log("AUDIO 알 수 없는 연결(머리 불일치), 닫음");
                return;
            }
            rate = hb.getInt(8);
            channels = hb.getInt(12);
            if (rate < 8000 || rate > 192000 || channels < 1 || channels > 2) {
                log.log("AUDIO 지원하지 않는 형식 " + rate + "Hz " + channels + "ch, 닫음");
                return;
            }
            int frameBytes = 2 * channels;
            if (rate == RATE) {
                src = new Source();
                sources.add(src);
            } else {
                // Other rates: a track of their own as before (no resampler here)
                // 다른 주파수: 전처럼 자체 트랙(여기에는 리샘플러 없음)
                int min = AudioTrack.getMinBufferSize(rate,
                        channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT);
                own = newTrack(rate, channels, Math.max(min, rate * frameBytes / 25));
                own.setBufferSizeInFrames(rate / 20);
                own.play();
            }
            int now = active.incrementAndGet();
            log.log(String.format(Locale.ROOT, "AUDIO 재생 시작 %dHz %dch (%s, 동시 %d개)",
                    rate, channels, src != null ? "섞기" : "자체 출력", now));
            byte[] b = new byte[8192];
            short[] st = new short[(b.length / 2) * 2];
            int have = 0, n;
            while (!closed && (n = in.read(b, have, b.length - have)) > 0) {
                have += n;
                bytes += n;
                int whole = have - have % frameBytes;
                int frames = whole / frameBytes;
                // Peak of this read (16-bit little-endian samples) / 이번 읽기의 최대값(16비트 리틀 엔디언 샘플)
                int peak = 0;
                for (int i = 0; i + 1 < whole; i += 2) {
                    int v = Math.abs((short) ((b[i] & 0xff) | (b[i + 1] << 8)));
                    if (v > peak) peak = v;
                }
                peakMax = Math.max(peakMax, peak);
                if (peak >= PEAK_LOUD && !loud) {
                    bursts++;
                    lastBurst = (bytes - n) / frameBytes;
                    if (firstBurst < 0) firstBurst = lastBurst;
                }
                loud = peak >= PEAK_LOUD;
                if (src != null) {
                    // To stereo shorts (mono spread to both sides) / 스테레오 short로(모노는 양쪽으로)
                    ByteBuffer bb = ByteBuffer.wrap(b, 0, whole).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < frames; i++) {
                        short l = bb.getShort();
                        short r = channels == 2 ? bb.getShort() : l;
                        st[i * 2] = l;
                        st[i * 2 + 1] = r;
                    }
                    src.put(st, frames);
                } else {
                    int off = 0;
                    while (off < whole) {
                        int w = own.write(b, off, whole - off);
                        if (w <= 0) throw new IOException("AudioTrack.write " + w);
                        off += w;
                    }
                }
                System.arraycopy(b, whole, b, 0, have - whole);
                have -= whole;
            }
        } catch (IOException | RuntimeException e) {
            if (!closed) log.log("AUDIO 연결 오류: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (src != null || own != null) {
                active.decrementAndGet();
                log.log(String.format(Locale.ROOT, "AUDIO 재생 끝 (%.1f초 분량, 소리 구간 %d번%s, 최대 크기 %d)",
                        bytes / (double) (rate * 2 * channels), bursts,
                        bursts > 0 ? String.format(Locale.ROOT, ": 처음 %.1f초 마지막 %.1f초", firstBurst / (double) rate,
                                lastBurst / (double) rate) : "", peakMax));
            }
            // The mixer drops the stream once it has played the rest / 믹서가 남은 것을 재생한 뒤 스트림을 뺌
            if (src != null) src.end();
            if (own != null) {
                try {
                    own.stop();
                } catch (IllegalStateException ignored) {
                }
                own.release();
            }
            closeQuietly(c);
        }
    }

    /**
     * Record connection: microphone samples (48 kHz mono) as stereo 16-bit, written in 10 ms pieces
     * as they arrive. The write blocks while the plugin is not reading (stream stopped); MicCapture
     * then drops the oldest samples of this reader, so a restart does not hear old sound for long.
     * 녹음 연결: 마이크 샘플(48kHz 모노)을 스테레오 16비트로, 오는 대로 10ms 조각으로 씀. 플러그인이 읽지 않으면(스트림
     * 멈춤) 쓰기가 기다리고, 그동안 MicCapture가 이 읽는 쪽의 가장 오래된 샘플을 버림. 다시 시작할 때 오래된 소리가 길게
     * 들리지 않음
     */
    private void record(LocalSocket c) throws IOException {
        MicCapture m = mic;
        if (m == null) {
            log.log("AUDIO 녹음 연결: 마이크 없음, 닫음 (플러그인은 무음)");
            return;
        }
        MicCapture.Tap tap = m.open("ALSA");
        long frames = 0;
        try (java.io.OutputStream os = c.getOutputStream()) {
            short[] in = new short[RATE / 100];
            byte[] b = new byte[in.length * 4];
            while (!closed) {
                int n = tap.take(in, in.length, 50);
                if (n == 0) continue;
                for (int i = 0; i < n; i++) {
                    int o = i * 4;
                    b[o] = b[o + 2] = (byte) in[i];
                    b[o + 1] = b[o + 3] = (byte) (in[i] >> 8);
                }
                os.write(b, 0, n * 4);
                frames += n;
            }
        } catch (IOException e) {
            // The plugin closed the stream: normal end / 플러그인이 스트림을 닫음: 정상 끝
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            tap.close();
            log.log(String.format(Locale.ROOT, "AUDIO 녹음 연결 끝 (%.1f초 보냄)", frames / (double) RATE));
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (Source s : sources) s.end();
        // Wake accept() by connecting once; the loop then sees closed and ends
        // 한 번 연결해 accept()를 깨움. 반복문은 closed를 보고 끝남
        LocalSocket wake = new LocalSocket();
        try {
            wake.connect(new LocalSocketAddress(socketFile.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
        } catch (IOException ignored) {
        }
        closeQuietly(wake);
        try {
            server.close();
        } catch (IOException ignored) {
        }
        closeQuietly(bound);
        socketFile.delete();
    }

    private static void closeQuietly(LocalSocket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
