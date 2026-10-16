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

/**
 * Plays the sound of programs in the rootfs: listens on a UNIX socket inside the rootfs
 * (guest path /tmp/ezs-audio.sock) for the ALSA plugin native/ezsaudio/pcm_ezs.c and gives each
 * connection its own Android AudioTrack. Runs as threads of the app, so no extra process is
 * started (Android allows 32 extra processes for all apps together). Nothing leaves the device.
 * rootfs 안 프로그램의 소리를 재생함: rootfs 안 UNIX 소켓(guest 경로 /tmp/ezs-audio.sock)에서
 * ALSA 플러그인 native/ezsaudio/pcm_ezs.c의 연결을 받고, 연결마다 안드로이드 AudioTrack을 하나씩 씀.
 * 앱의 스레드로 동작하므로 추가 프로세스가 없음(안드로이드는 모든 앱 합계 추가 프로세스 32개까지 허용).
 * 기기 밖으로 나가는 데이터 없음
 *
 * Stream per connection: 16-byte header ("EZSA", version 1, rate, channels; little-endian uint32),
 * then interleaved signed 16-bit little-endian samples until the plugin closes the connection.
 * 연결당 스트림: 16바이트 머리("EZSA", 버전 1, rate, channels, 리틀엔디언 uint32), 그 뒤로 플러그인이
 * 연결을 닫을 때까지 인터리브된 16비트 부호 있는 리틀엔디언 샘플
 */
public final class AudioServer implements Closeable {

    /** Log sink / 로그 출력 */
    public interface Log {
        void log(String line);
    }

    /** Guest path of the socket, also written into ~/.asoundrc by steam-run.sh
     *  소켓의 guest 경로. steam-run.sh가 ~/.asoundrc에 같은 값을 씀 */
    public static final String GUEST_SOCKET = "/tmp/ezs-audio.sock";

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

    private AudioServer(File socketFile, LocalSocket bound, LocalServerSocket server, Log log) {
        this.socketFile = socketFile;
        this.bound = bound;
        this.server = server;
        this.log = log;
    }

    /**
     * Bind the socket at rootfs + GUEST_SOCKET (an old socket file is replaced) and start accepting.
     * rootfs + GUEST_SOCKET 위치에 소켓을 만들고(이전 소켓 파일은 바꿈) 연결을 받기 시작함
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
        Thread t = new Thread(a::acceptLoop, "ezs-audio-accept");
        t.setDaemon(true);
        t.start();
        return a;
    }

    /** Connections now playing / 지금 재생 중인 연결 수 */
    public int activeStreams() {
        return active.get();
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

    /** One connection: header, then samples into an AudioTrack / 연결 하나: 머리, 그 뒤 샘플을 AudioTrack으로 */
    private void play(LocalSocket c) {
        AudioTrack track = null;
        long bytes = 0;
        int frameBytes = 4, rate = 48000;
        try (InputStream in = c.getInputStream()) {
            byte[] h = new byte[16];
            new DataInputStream(in).readFully(h);
            ByteBuffer hb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            if (h[0] != 'E' || h[1] != 'Z' || h[2] != 'S' || h[3] != 'A' || hb.getInt(4) != 1) {
                log.log("AUDIO 알 수 없는 연결(머리 불일치), 닫음");
                return;
            }
            rate = hb.getInt(8);
            int channels = hb.getInt(12);
            if (rate < 8000 || rate > 192000 || channels < 1 || channels > 2) {
                log.log("AUDIO 지원하지 않는 형식 " + rate + "Hz " + channels + "ch, 닫음");
                return;
            }
            frameBytes = 2 * channels;
            int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
            // Capacity: the device minimum (measured 120 ms here), at least 40 ms
            // 용량: 기기 최소값(여기서 실측 120 ms), 최소 40 ms
            int size = Math.max(min, rate * frameBytes / 25);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(mask)
                            .build())
                    .setBufferSizeInBytes(size)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
            // Latency: use only 50 ms of the capacity (AudioTrack may round it up); measured 240 ms
            // with the capacity doubled and fully used
            // 지연: 용량 중 50 ms만 씀(AudioTrack이 올려 잡을 수 있음). 용량을 두 배로 다 쓸 때 실측 240 ms
            track.setBufferSizeInFrames(rate / 20);
            float ms = track.getBufferSizeInFrames() * 1000f / rate;
            track.play();
            int now = active.incrementAndGet();
            log.log(String.format(Locale.ROOT, "AUDIO 재생 시작 %dHz %dch (버퍼 %.0f ms, 동시 %d개)",
                    rate, channels, ms, now));
            // Whole frames only go to the track; a split frame waits for its rest
            // 트랙에는 온전한 프레임만 넘김. 잘린 프레임은 나머지를 기다림
            byte[] buf = new byte[8192];
            int have = 0;
            int n;
            while (!closed && (n = in.read(buf, have, buf.length - have)) > 0) {
                have += n;
                bytes += n;
                int whole = have - have % frameBytes;
                int off = 0;
                while (off < whole) {
                    int w = track.write(buf, off, whole - off);
                    if (w <= 0) throw new IOException("AudioTrack.write " + w);
                    off += w;
                }
                System.arraycopy(buf, whole, buf, 0, have - whole);
                have -= whole;
            }
        } catch (IOException | RuntimeException e) {
            if (!closed) log.log("AUDIO 연결 오류: " + e);
        } finally {
            if (track != null) {
                active.decrementAndGet();
                try {
                    track.stop();
                } catch (IllegalStateException ignored) {
                    // already stopped / 이미 멈춤
                }
                track.release();
                log.log(String.format(Locale.ROOT, "AUDIO 재생 끝 (%.1f초 분량)", bytes / (double) (rate * frameBytes)));
            }
            closeQuietly(c);
        }
    }

    /**
     * Stop accepting and remove the socket file. LocalServerSocket.accept() does not return on
     * close(), so a short connection wakes it first.
     * 연결 받기를 멈추고 소켓 파일을 지움. LocalServerSocket.accept()는 close()로 풀리지 않으므로
     * 짧은 연결로 먼저 깨움
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        LocalSocket wake = new LocalSocket();
        try {
            wake.connect(new LocalSocketAddress(socketFile.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
        } catch (IOException ignored) {
            // accept thread may be gone already / accept 스레드가 이미 끝났을 수 있음
        }
        closeQuietly(wake);
        try {
            server.close();
        } catch (IOException ignored) {
            // nothing to do / 할 일 없음
        }
        closeQuietly(bound);
        //noinspection ResultOfMethodCallIgnored
        socketFile.delete();
    }

    private static void closeQuietly(LocalSocket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // nothing to do / 할 일 없음
        }
    }
}
