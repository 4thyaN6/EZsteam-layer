package app.ezsteam.runtime;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal PulseAudio native-protocol server, own code (MIT), written from the protocol facts
 * (pstream frames, tagstructs, command layouts) with PipeWire's MIT pulse server and the client
 * side as references for facts only; no PulseAudio code. Listens on a UNIX socket inside the rootfs
 * (guest path /tmp/ezs-pulse/native) and plays every playback stream through the AudioServer mixer,
 * so it adds no process (threads of the app).
 * Why: Steam's Chromium (CEF 126) under ALSA counts only real sound cards; with none in the rootfs
 * its WebAudio got a fake output and the Big Picture UI sounds were never heard (2026-11-01). With a
 * PulseAudio server it takes the server's sink instead. Only the web helper is told where this
 * server is (PULSE_SERVER in ezs-webhelper-env.sh); games keep the ALSA path.
 * Scope: one sink "ezs_out" (48 kHz stereo) and its monitor, playback streams in S16LE / FLOAT32LE /
 * S32LE, any rate (linear resampling to 48 kHz), mono or stereo (more channels: first two kept).
 * Record streams get the phone's microphone through MicCapture (source "ezs_in"; RECORD_AUDIO is
 * asked when the first record stream opens), silence until it is granted or without a MicCapture.
 * No shared memory: the AUTH reply never offers it,
 * so audio comes inline over the socket.
 * PulseAudio 네이티브 프로토콜 최소 서버. 자체 코드(MIT). 프로토콜 사실(pstream 프레임, tagstruct, 명령 형식)로
 * 작성했고 PipeWire의 MIT pulse 서버와 클라이언트 쪽은 사실 확인용으로만 봄. PulseAudio 코드 없음.
 * rootfs 안 UNIX 소켓(guest 경로 /tmp/ezs-pulse/native)에서 듣고 재생 스트림은 모두 AudioServer 믹서로 재생함.
 * 앱의 스레드라 프로세스가 늘지 않음.
 * 이유: Steam의 Chromium(CEF 126)은 ALSA에서 실제 사운드카드만 셈. rootfs에는 카드가 없어 WebAudio가 가짜 출력을
 * 받았고 빅픽처 UI 효과음이 들리지 않았음(2026-11-01). PulseAudio 서버가 있으면 그 서버의 출력 장치를 씀. 이 서버
 * 위치는 웹 도우미에만 알림(ezs-webhelper-env.sh의 PULSE_SERVER). 게임은 ALSA 경로 그대로임.
 * 범위: 출력 장치 "ezs_out"(48kHz 스테레오) 하나와 그 모니터. 재생 스트림은 S16LE / FLOAT32LE / S32LE, 주파수는
 * 무엇이든(48kHz로 선형 변환), 모노·스테레오(채널이 더 많으면 앞 두 개). 녹음 스트림은 MicCapture로 휴대폰 마이크를
 * 받음(소스 "ezs_in", 첫 녹음 스트림이 열릴 때 RECORD_AUDIO를 물음). 허용 전이나 MicCapture가 없으면 무음. 공유 메모리 없음: AUTH 답에서 알리지 않으므로 소리는
 * 소켓으로 직접 옴
 */
public final class PulseServer implements Closeable {

    /** Guest path of the socket; ezs-webhelper-env.sh points PULSE_SERVER at it
     *  소켓의 guest 경로. ezs-webhelper-env.sh가 PULSE_SERVER를 여기로 맞춤 */
    public static final String GUEST_SOCKET = "/tmp/ezs-pulse/native";

    /** Protocol version this server speaks / 이 서버가 쓰는 프로토콜 버전 */
    private static final int VERSION = 35;
    private static final int CONTROL = 0xFFFFFFFF;
    private static final int INVALID = 0xFFFFFFFF;

    // Commands (numbers of the public protocol) / 명령(공개 프로토콜 번호)
    private static final int CMD_ERROR = 0, CMD_REPLY = 2;
    private static final int CMD_CREATE_PLAYBACK = 3, CMD_DELETE_PLAYBACK = 4;
    private static final int CMD_CREATE_RECORD = 5, CMD_DELETE_RECORD = 6;
    private static final int CMD_AUTH = 8, CMD_SET_CLIENT_NAME = 9;
    private static final int CMD_DRAIN = 12, CMD_PLAYBACK_LATENCY = 14;
    private static final int CMD_SERVER_INFO = 20;
    private static final int CMD_SINK_INFO = 21, CMD_SINK_LIST = 22;
    private static final int CMD_SOURCE_INFO = 23, CMD_SOURCE_LIST = 24;
    private static final int CMD_SUBSCRIBE = 35;
    private static final int CMD_SET_SINK_VOLUME = 36, CMD_SET_INPUT_VOLUME = 37, CMD_SET_SOURCE_VOLUME = 38;
    private static final int CMD_SET_SINK_MUTE = 39, CMD_SET_SOURCE_MUTE = 40;
    private static final int CMD_CORK_PLAYBACK = 41, CMD_FLUSH_PLAYBACK = 42, CMD_TRIGGER = 43;
    private static final int CMD_RECORD_LATENCY = 57, CMD_CORK_RECORD = 58, CMD_FLUSH_RECORD = 59;
    private static final int CMD_PREBUF = 60, CMD_REQUEST = 61;
    private static final int CMD_SET_PLAYBACK_ATTR = 72, CMD_SET_RECORD_ATTR = 73;
    private static final int CMD_UPDATE_PLAYBACK_RATE = 74;
    private static final int CMD_UPDATE_PLAYBACK_PROPLIST = 81, CMD_UPDATE_CLIENT_PROPLIST = 82;
    private static final int CMD_UPDATE_RECORD_PROPLIST = 80;
    private static final int CMD_SET_INPUT_MUTE = 76;
    private static final int CMD_STARTED = 86;

    // Error codes / 오류 코드
    private static final int ERR_ACCESS = 1, ERR_INVALID = 3, ERR_NOENTITY = 5, ERR_PROTOCOL = 7;
    private static final int ERR_NOTSUPPORTED = 19;

    // Sample formats / 샘플 형식
    private static final int FMT_S16LE = 3, FMT_FLOAT32LE = 5, FMT_S32LE = 7;

    private static final String SINK = "ezs_out", MONITOR = "ezs_out.monitor";
    /** Microphone source, offered when a MicCapture is given / 마이크 소스, MicCapture가 있을 때 알림 */
    private static final String MIC = "ezs_in";
    private static final int RATE = 48000;

    private final File socketFile;
    /** Kept referenced so its descriptor is not closed by the finalizer (see AudioServer)
     *  종료자가 디스크립터를 닫지 않게 참조를 유지함(AudioServer 참고) */
    private final LocalSocket bound;
    private final LocalServerSocket server;
    private final AudioServer mixer;
    /** Microphone for record streams, null = silence / 녹음 스트림용 마이크, null이면 무음 */
    private final MicCapture mic;
    private final AudioServer.Log log;
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicInteger clientIds = new java.util.concurrent.atomic.AtomicInteger();
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();

    private PulseServer(File socketFile, LocalSocket bound, LocalServerSocket server, AudioServer mixer,
                        MicCapture mic, AudioServer.Log log) {
        this.socketFile = socketFile;
        this.bound = bound;
        this.server = server;
        this.mixer = mixer;
        this.mic = mic;
        this.log = log;
    }

    /**
     * Bind the socket at rootfs + GUEST_SOCKET (an old one is replaced) and start accepting.
     * rootfs + GUEST_SOCKET 위치에 소켓을 만들고(이전 것은 바꿈) 연결을 받기 시작함
     */
    public static PulseServer start(File rootfs, AudioServer mixer, AudioServer.Log log) throws IOException {
        return start(rootfs, mixer, null, log);
    }

    /** Same, with the microphone for record streams (null = silence)
     *  같음. 녹음 스트림용 마이크를 함께 줌(null이면 무음) */
    public static PulseServer start(File rootfs, AudioServer mixer, MicCapture mic, AudioServer.Log log) throws IOException {
        File f = new File(rootfs, GUEST_SOCKET.substring(1));
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        if (f.exists() && !f.delete()) throw new IOException("cannot replace " + f);
        LocalSocket s = new LocalSocket();
        s.bind(new LocalSocketAddress(f.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
        LocalServerSocket server = new LocalServerSocket(s.getFileDescriptor());
        PulseServer p = new PulseServer(f, s, server, mixer, mic, log);
        Thread t = new Thread(p::acceptLoop, "ezs-pulse-accept");
        t.setDaemon(true);
        t.start();
        return p;
    }

    private void acceptLoop() {
        while (!closed) {
            LocalSocket c;
            try {
                c = server.accept();
            } catch (IOException e) {
                if (!closed) log.log("AUDIO pulse 연결 받기 실패: " + e);
                return;
            }
            if (closed) {
                quiet(c);
                return;
            }
            Client cl = new Client(c, clientIds.incrementAndGet());
            clients.add(cl);
            Thread t = new Thread(cl::run, "ezs-pulse-client");
            t.setDaemon(true);
            t.start();
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (Client c : clients) c.shutdown();
        LocalSocket wake = new LocalSocket();
        try {
            wake.connect(new LocalSocketAddress(socketFile.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
        } catch (IOException ignored) {
        }
        quiet(wake);
        try {
            server.close();
        } catch (IOException ignored) {
        }
        quiet(bound);
        socketFile.delete();
    }

    private static void quiet(LocalSocket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ tagstruct
    // tagstruct

    /** Sample spec / 샘플 사양 */
    private static final class Spec {
        int format, channels, rate;

        Spec(int format, int channels, int rate) {
            this.format = format;
            this.channels = channels;
            this.rate = rate;
        }

        int sampleBytes() {
            return format == FMT_S16LE ? 2 : 4;
        }

        int frameBytes() {
            return sampleBytes() * channels;
        }

        String name() {
            return (format == FMT_S16LE ? "s16le" : format == FMT_FLOAT32LE ? "float32le" : format == FMT_S32LE ? "s32le"
                    : "format" + format) + " " + rate + "Hz " + channels + "ch";
        }
    }

    /** Timeval value / timeval 값 */
    private static final class Tv {
        final long sec, usec;

        Tv(long sec, long usec) {
            this.sec = sec;
            this.usec = usec;
        }
    }

    /** Reads typed values in order; every value starts with its tag byte (big-endian numbers)
     *  값을 차례로 읽음. 모든 값은 태그 바이트로 시작함(숫자는 빅엔디언) */
    private static final class Reader {
        private final byte[] b;
        private int p;

        Reader(byte[] b) {
            this.b = b;
        }

        boolean more() {
            return p < b.length;
        }

        private int u8() throws IOException {
            if (p >= b.length) throw new IOException("short tagstruct");
            return b[p++] & 0xff;
        }

        private long u32raw() throws IOException {
            if (p + 4 > b.length) throw new IOException("short tagstruct");
            long v = ((b[p] & 0xffL) << 24) | ((b[p + 1] & 0xffL) << 16) | ((b[p + 2] & 0xffL) << 8) | (b[p + 3] & 0xffL);
            p += 4;
            return v;
        }

        /** Next value of any kind: Long (L, U, R, r, V), Boolean, String (or null), Spec, int[] (channel
         *  map, cvolume), Map (proplist), Tv, byte[] (arbitrary), Integer (u8), Object[] (format info)
         *  어떤 종류든 다음 값 하나 */
        Object next() throws IOException {
            int tag = u8();
            switch (tag) {
                case 'L':
                case 'V':
                    return u32raw();
                case 'U':
                case 'R':
                case 'r':
                    return (u32raw() << 32) | u32raw();
                case 'B':
                    return u8();
                case '1':
                    return Boolean.TRUE;
                case '0':
                    return Boolean.FALSE;
                case 'N':
                    return null;
                case 't': {
                    int e = p;
                    while (e < b.length && b[e] != 0) e++;
                    if (e >= b.length) throw new IOException("unterminated string");
                    String s = new String(b, p, e - p, StandardCharsets.UTF_8);
                    p = e + 1;
                    return s;
                }
                case 'a': {
                    int fmt = u8(), ch = u8();
                    return new Spec(fmt, ch, (int) u32raw());
                }
                case 'm':
                case 'v': {
                    int n = u8();
                    int[] a = new int[n];
                    for (int i = 0; i < n; i++) a[i] = tag == 'm' ? u8() : (int) u32raw();
                    return a;
                }
                case 'T':
                    return new Tv(u32raw(), u32raw());
                case 'x': {
                    int n = (int) u32raw();
                    if (n < 0 || p + n > b.length) throw new IOException("short arbitrary");
                    byte[] a = java.util.Arrays.copyOfRange(b, p, p + n);
                    p += n;
                    return a;
                }
                case 'P': {
                    Map<String, byte[]> m = new java.util.HashMap<>();
                    while (true) {
                        Object k = next();
                        if (k == null) break;
                        next();
                        Object v = next();
                        m.put((String) k, (byte[]) v);
                    }
                    return m;
                }
                case 'f': {
                    Object enc = next();
                    Object props = next();
                    return new Object[]{enc, props};
                }
                default:
                    throw new IOException("unknown tag " + tag);
            }
        }

        /** All remaining values / 남은 값 모두 */
        List<Object> rest() throws IOException {
            List<Object> l = new ArrayList<>();
            while (more()) l.add(next());
            return l;
        }
    }

    /** Builds a tagstruct / tagstruct를 만듦 */
    private static final class Writer {
        final ByteArrayOutputStream o = new ByteArrayOutputStream(256);

        private void raw32(long v) {
            o.write((int) (v >>> 24));
            o.write((int) (v >>> 16));
            o.write((int) (v >>> 8));
            o.write((int) v);
        }

        Writer u32(long v) {
            o.write('L');
            raw32(v);
            return this;
        }

        Writer u8(int v) {
            o.write('B');
            o.write(v);
            return this;
        }

        Writer usec(long v) {
            o.write('U');
            raw32(v >>> 32);
            raw32(v);
            return this;
        }

        Writer s64(long v) {
            o.write('r');
            raw32(v >>> 32);
            raw32(v);
            return this;
        }

        Writer u64(long v) {
            o.write('R');
            raw32(v >>> 32);
            raw32(v);
            return this;
        }

        Writer bool(boolean v) {
            o.write(v ? '1' : '0');
            return this;
        }

        Writer str(String s) {
            if (s == null) {
                o.write('N');
            } else {
                o.write('t');
                byte[] b = s.getBytes(StandardCharsets.UTF_8);
                o.write(b, 0, b.length);
                o.write(0);
            }
            return this;
        }

        Writer spec(Spec s) {
            o.write('a');
            o.write(s.format);
            o.write(s.channels);
            raw32(s.rate);
            return this;
        }

        Writer map(int[] m) {
            o.write('m');
            o.write(m.length);
            for (int v : m) o.write(v);
            return this;
        }

        Writer cvolume(int channels) {
            o.write('v');
            o.write(channels);
            for (int i = 0; i < channels; i++) raw32(0x10000);
            return this;
        }

        Writer volume(long v) {
            o.write('V');
            raw32(v);
            return this;
        }

        Writer tv(long sec, long usec) {
            o.write('T');
            raw32(sec);
            raw32(usec);
            return this;
        }

        /** Proplist with the given key/value pairs (values with their NUL) / 주어진 키·값 쌍의 proplist(값은 NUL 포함) */
        Writer props(String... kv) {
            o.write('P');
            for (int i = 0; i + 1 < kv.length; i += 2) {
                str(kv[i]);
                byte[] v = (kv[i + 1] + "\0").getBytes(StandardCharsets.UTF_8);
                u32(v.length);
                o.write('x');
                raw32(v.length);
                o.write(v, 0, v.length);
            }
            o.write('N');
            return this;
        }

        /** PCM format info / PCM 형식 정보 */
        Writer pcmFormat() {
            o.write('f');
            u8(1);
            props();
            return this;
        }

        byte[] bytes() {
            return o.toByteArray();
        }
    }

    // ------------------------------------------------------------------ one client
    // 클라이언트 하나

    /** Playback or record stream of a client / 클라이언트의 재생 또는 녹음 스트림 */
    private static final class Stream {
        final boolean playback;
        final int channel;
        final Spec spec;
        final int[] map;
        long maxlength, tlength, prebuf, minreq, fragsize;
        boolean corked;
        AudioServer.Source src;
        /** Bytes asked for with REQUEST and not yet received / REQUEST로 요청했고 아직 받지 않은 바이트 */
        long requested;
        /** Bytes received (write index) / 받은 바이트(쓰기 위치) */
        long written;
        /** Converter state: position between the last two input frames, last frame
         *  변환 상태: 마지막 두 입력 프레임 사이 위치, 마지막 프레임 */
        double pos;
        short lastL, lastR;
        boolean haveLast;
        boolean startedSent;
        /** Frames the mixer had taken at the last flush (read index base) / 마지막 flush 때 믹서가 가져간 프레임(읽기 위치 기준) */
        long dropped;
        // Record: bytes sent and the time it started / 녹음: 보낸 바이트와 시작 시각
        long recSent, recStart;
        /** Record: microphone reader (null = silence), fraction of a 48 kHz sample carried over
         *  녹음: 마이크 읽는 쪽(null이면 무음), 다음으로 넘기는 48kHz 샘플의 소수 부분 */
        MicCapture.Tap tap;
        double recFrac;
        long droppedBytes;

        Stream(boolean playback, int channel, Spec spec, int[] map) {
            this.playback = playback;
            this.channel = channel;
            this.spec = spec;
            this.map = map;
        }

        /** Input bytes still waiting in the mixer, converted back to the client's format
         *  믹서에서 기다리는 입력 바이트(클라이언트 형식으로 환산) */
        long queuedBytes() {
            if (src == null) return 0;
            return (long) src.queued() * spec.rate / RATE * spec.frameBytes();
        }
    }

    private final class Client {
        final LocalSocket sock;
        final int id;
        final Map<Integer, Stream> streams = new ConcurrentHashMap<>();
        OutputStream out;
        int version = 8;
        boolean authed;
        int nextChannel;
        String name = "?";
        final Set<Integer> unknown = new HashSet<>();
        volatile boolean done;

        Client(LocalSocket sock, int id) {
            this.sock = sock;
            this.id = id;
        }

        void shutdown() {
            done = true;
            quiet(sock);
        }

        void run() {
            Thread pacer = null;
            try {
                out = sock.getOutputStream();
                DataInputStream in = new DataInputStream(sock.getInputStream());
                pacer = new Thread(this::pace, "ezs-pulse-pace");
                pacer.setDaemon(true);
                pacer.start();
                byte[] d = new byte[20];
                while (!done && !closed) {
                    in.readFully(d);
                    int len = be32(d, 0), ch = be32(d, 4), flags = be32(d, 16);
                    if (len < 0 || len > 16 << 20) throw new IOException("frame too large " + len);
                    byte[] body = new byte[len];
                    in.readFully(body);
                    if (ch == CONTROL) {
                        command(body);
                    } else {
                        // Shared memory blocks are never offered, so they are a protocol error
                        // 공유 메모리 블록은 알리지 않으므로 오면 프로토콜 오류임
                        if ((flags & 0xFF000000) != 0) throw new IOException("shm block");
                        memblock(ch, body);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // A client that went away (end of file, broken pipe while answering) is normal
                // 떠난 클라이언트(파일 끝, 답하다가 끊긴 연결)는 정상임
                String m = String.valueOf(e.getMessage());
                boolean gone = e instanceof java.io.EOFException || m.contains("Broken pipe") || m.contains("reset");
                if (!done && !closed && !gone) {
                    log.log("AUDIO pulse 클라이언트 " + id + " 오류: " + e);
                }
            } finally {
                done = true;
                for (Stream s : streams.values()) endStream(s, "연결 끝");
                streams.clear();
                clients.remove(this);
                quiet(sock);
                if (pacer != null) pacer.interrupt();
            }
        }

        // ---- sending / 보내기

        /** One frame: control packet or record data / 프레임 하나: 제어 패킷 또는 녹음 데이터 */
        private void frame(int channel, byte[] payload) throws IOException {
            byte[] d = new byte[20];
            put32(d, 0, payload.length);
            put32(d, 4, channel);
            synchronized (this) {
                out.write(d);
                out.write(payload);
                out.flush();
            }
        }

        private void reply(int tag, Writer w) throws IOException {
            Writer h = new Writer().u32(CMD_REPLY).u32(tag);
            byte[] a = h.bytes(), b = w == null ? new byte[0] : w.bytes();
            byte[] all = new byte[a.length + b.length];
            System.arraycopy(a, 0, all, 0, a.length);
            System.arraycopy(b, 0, all, a.length, b.length);
            frame(CONTROL, all);
        }

        private void error(int tag, int code) throws IOException {
            frame(CONTROL, new Writer().u32(CMD_ERROR).u32(tag).u32(code).bytes());
        }

        private void event(int cmd, int channel, long arg, boolean withArg) throws IOException {
            Writer w = new Writer().u32(cmd).u32(INVALID).u32(channel);
            if (withArg) w.u32(arg);
            frame(CONTROL, w.bytes());
        }

        // ---- commands / 명령

        @SuppressWarnings("unchecked")
        private void command(byte[] body) throws IOException {
            Reader r = new Reader(body);
            int cmd = (int) (long) (Long) r.next();
            int tag = (int) (long) (Long) r.next();
            if (!authed && cmd != CMD_AUTH) {
                error(tag, ERR_ACCESS);
                return;
            }
            switch (cmd) {
                case CMD_AUTH: {
                    long v = (Long) r.next();
                    // Client version without its shared memory flags; the reply offers none
                    // 공유 메모리 플래그를 뺀 클라이언트 버전. 답에서는 아무것도 알리지 않음
                    int cv = (int) (v & 0xFFFF);
                    if (cv < 8) {
                        error(tag, ERR_PROTOCOL);
                        return;
                    }
                    version = Math.min(cv, VERSION);
                    authed = true;
                    reply(tag, new Writer().u32(VERSION));
                    break;
                }
                case CMD_SET_CLIENT_NAME: {
                    if (version >= 13) {
                        Object p = r.next();
                        if (p instanceof Map) {
                            byte[] n = ((Map<String, byte[]>) p).get("application.name");
                            if (n != null) name = new String(n, StandardCharsets.UTF_8).replace("\0", "");
                        }
                        reply(tag, new Writer().u32(id));
                    } else {
                        Object n = r.next();
                        if (n instanceof String) name = (String) n;
                        reply(tag, null);
                    }
                    log.log("AUDIO pulse 클라이언트 " + id + " 연결: " + name + " (프로토콜 " + version + ")");
                    break;
                }
                case CMD_SERVER_INFO: {
                    Writer w = new Writer().str("pulseaudio").str("16.1").str("steam").str("ezsteam")
                            .spec(new Spec(FMT_S16LE, 2, RATE)).str(SINK).str(source()).u32(1);
                    if (version >= 15) w.map(new int[]{1, 2});
                    reply(tag, w);
                    break;
                }
                case CMD_SINK_INFO:
                case CMD_SOURCE_INFO: {
                    long idx = (Long) r.next();
                    String n = (String) r.next();
                    boolean sink = cmd == CMD_SINK_INFO;
                    String own = sink ? SINK : source();
                    boolean match = (idx == 0 && n == null) || own.equals(n) || (!sink && MONITOR.equals(n))
                            || (sink ? "@DEFAULT_SINK@" : "@DEFAULT_SOURCE@").equals(n)
                            || (idx == INVALID_L && n == null);
                    if (!match) {
                        error(tag, ERR_NOENTITY);
                        return;
                    }
                    Writer w = new Writer();
                    device(w, sink);
                    reply(tag, w);
                    break;
                }
                case CMD_SINK_LIST:
                case CMD_SOURCE_LIST: {
                    Writer w = new Writer();
                    device(w, cmd == CMD_SINK_LIST);
                    reply(tag, w);
                    break;
                }
                case CMD_CREATE_PLAYBACK:
                    createPlayback(tag, r.rest());
                    break;
                case CMD_CREATE_RECORD:
                    createRecord(tag, r.rest());
                    break;
                case CMD_DELETE_PLAYBACK:
                case CMD_DELETE_RECORD: {
                    Stream s = streams.remove((int) (long) (Long) r.next());
                    if (s == null) {
                        error(tag, ERR_NOENTITY);
                        return;
                    }
                    endStream(s, "닫음");
                    reply(tag, null);
                    break;
                }
                case CMD_CORK_PLAYBACK:
                case CMD_CORK_RECORD: {
                    Stream s = streams.get((int) (long) (Long) r.next());
                    boolean cork = (Boolean) r.next();
                    if (s == null) {
                        error(tag, ERR_NOENTITY);
                        return;
                    }
                    synchronized (s) {
                        s.corked = cork;
                        if (s.src != null) s.src.setPaused(cork);
                        if (!cork && !s.playback) {
                            s.recStart = System.nanoTime();
                            s.recSent = 0;
                            // Resumed: start from fresh microphone samples / 다시 시작: 새 마이크 샘플부터
                            if (s.tap != null) s.tap.clear();
                        }
                        if (cork) s.startedSent = false;
                    }
                    reply(tag, null);
                    // Uncorked: ask for data right away / 멈춤 해제: 바로 데이터를 요청함
                    if (!cork && s.playback) request(s);
                    break;
                }
                case CMD_FLUSH_PLAYBACK:
                case CMD_FLUSH_RECORD: {
                    Stream s = streams.get((int) (long) (Long) r.next());
                    if (s == null) {
                        error(tag, ERR_NOENTITY);
                        return;
                    }
                    synchronized (s) {
                        if (s.src != null) {
                            // Bytes dropped count as read, so the read index stays honest
                            // 버린 바이트는 읽은 것으로 셈(읽기 위치가 맞게 남음)
                            s.dropped += s.src.queued();
                            s.src.clear();
                        }
                        s.haveLast = false;
                        s.pos = 0;
                    }
                    reply(tag, null);
                    if (s.playback) request(s);
                    break;
                }
                case CMD_TRIGGER:
                case CMD_PREBUF: {
                    Stream s = streams.get((int) (long) (Long) r.next());
                    if (s == null) {
                        error(tag, ERR_NOENTITY);
                        return;
                    }
                    reply(tag, null);
                    break;
                }
                case CMD_DRAIN: {
                    final Stream s = streams.get((int) (long) (Long) r.next());
                    if (s == null) {
                        error(tag, ERR_NOENTITY);
                        return;
                    }
                    // Answered once the queue has played out (or after 2 s) / 쌓인 것이 다 재생되면(또는 2초 뒤) 답함
                    Thread t = new Thread(() -> {
                        long end = System.nanoTime() + 2_000_000_000L;
                        while (!done && s.src != null && s.src.queued() > 0 && !s.corked && System.nanoTime() < end) {
                            try {
                                Thread.sleep(5);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                        try {
                            reply(tag, null);
                        } catch (IOException ignored) {
                        }
                    }, "ezs-pulse-drain");
                    t.setDaemon(true);
                    t.start();
                    break;
                }
                case CMD_PLAYBACK_LATENCY:
                case CMD_RECORD_LATENCY: {
                    Stream s = streams.get((int) (long) (Long) r.next());
                    Tv tv = (Tv) r.next();
                    if (s == null) {
                        error(tag, ERR_NOENTITY);
                        return;
                    }
                    latency(tag, s, tv, cmd == CMD_PLAYBACK_LATENCY);
                    break;
                }
                case CMD_SET_PLAYBACK_ATTR:
                case CMD_SET_RECORD_ATTR: {
                    Stream s = streams.get((int) (long) (Long) r.next());
                    if (s == null) {
                        error(tag, ERR_NOENTITY);
                        return;
                    }
                    long a = (Long) r.next(), b = (Long) r.next();
                    Writer w = new Writer();
                    if (s.playback) {
                        long c = (Long) r.next(), d = (Long) r.next();
                        synchronized (s) {
                            fixPlayback(s, a, b, c, d);
                        }
                        w.u32(s.maxlength).u32(s.tlength).u32(s.prebuf).u32(s.minreq);
                    } else {
                        synchronized (s) {
                            fixRecord(s, a, b);
                        }
                        w.u32(s.maxlength).u32(s.fragsize);
                    }
                    if (version >= 13) w.usec(latencyUsec(s));
                    reply(tag, w);
                    if (s.playback) request(s);
                    break;
                }
                case CMD_UPDATE_PLAYBACK_RATE: {
                    Stream s = streams.get((int) (long) (Long) r.next());
                    long rate = (Long) r.next();
                    if (s == null) {
                        error(tag, ERR_NOENTITY);
                        return;
                    }
                    if (rate < 1000 || rate > 384000) {
                        error(tag, ERR_INVALID);
                        return;
                    }
                    synchronized (s) {
                        s.spec.rate = (int) rate;
                    }
                    reply(tag, null);
                    break;
                }
                case CMD_SUBSCRIBE:
                case CMD_SET_SINK_VOLUME:
                case CMD_SET_INPUT_VOLUME:
                case CMD_SET_SOURCE_VOLUME:
                case CMD_SET_SINK_MUTE:
                case CMD_SET_SOURCE_MUTE:
                case CMD_SET_INPUT_MUTE:
                case CMD_UPDATE_PLAYBACK_PROPLIST:
                case CMD_UPDATE_RECORD_PROPLIST:
                case CMD_UPDATE_CLIENT_PROPLIST:
                    // Accepted and ignored: one fixed device, volume stays at 100 %
                    // 받고 무시함: 장치는 하나로 고정, 음량은 100% 유지
                    reply(tag, null);
                    break;
                default:
                    // Every other command gets an answer, so a waiting client never hangs
                    // 그 밖의 명령도 답함. 기다리는 클라이언트가 멈추지 않게 함
                    if (unknown.add(cmd)) log.log("AUDIO pulse 클라이언트 " + id + " 모르는 명령 " + cmd + " → 지원 안 함");
                    error(tag, ERR_NOTSUPPORTED);
            }
        }

        /** Sink entry, or the source entry (microphone, or the sink's monitor without one)
         *  출력 장치 항목 또는 소스 항목(마이크, 없으면 출력 장치의 모니터) */
        private void device(Writer w, boolean sink) {
            boolean micSrc = !sink && mic != null;
            String desc = sink ? "EZsteam output" : micSrc ? "EZsteam microphone" : "Monitor of EZsteam output";
            w.u32(0).str(sink ? SINK : source()).str(desc)
                    .spec(new Spec(FMT_S16LE, 2, RATE)).map(new int[]{1, 2})
                    .u32(INVALID_L).cvolume(2).bool(false);
            // Sink: its monitor source; monitor source: the sink; microphone: monitors nothing
            // 출력 장치: 그 모니터 소스, 모니터 소스: 출력 장치, 마이크: 모니터하는 장치 없음
            if (micSrc) w.u32(INVALID_L).str(null);
            else w.u32(0).str(sink ? MONITOR : SINK);
            w.usec(0).str("ezsteam")
                    // Sink: LATENCY | DECIBEL_VOLUME | DYNAMIC_LATENCY; source: LATENCY | DECIBEL_VOLUME | DYNAMIC_LATENCY
                    // 출력: LATENCY | DECIBEL_VOLUME | DYNAMIC_LATENCY, 소스: LATENCY | DECIBEL_VOLUME | DYNAMIC_LATENCY
                    .u32(sink ? 0x0002 | 0x0020 | 0x0080 : 0x0002 | 0x0020 | 0x0040);
            if (version >= 13) {
                w.props("device.description", desc, "device.class", sink || micSrc ? "sound" : "monitor");
                w.usec(0);
            }
            if (version >= 15) {
                // Base volume 100 %, state IDLE, 65537 steps, no card / 기본 음량 100%, 상태 IDLE, 65537단계, 카드 없음
                w.volume(0x10000).u32(1).u32(65537).u32(INVALID_L);
            }
            if (version >= 16) {
                w.u32(0).str(null);
            }
            if (version >= 21) {
                w.u8(1).pcmFormat();
            }
        }

        // ---- streams / 스트림

        private Spec readSpec(Object o) throws IOException {
            if (!(o instanceof Spec)) throw new IOException("no sample spec");
            return (Spec) o;
        }

        private void createPlayback(int tag, List<Object> v) throws IOException {
            Spec spec = readSpec(v.get(0));
            int[] map = (int[]) v.get(1);
            if (spec.format != FMT_S16LE && spec.format != FMT_FLOAT32LE && spec.format != FMT_S32LE
                    || spec.channels < 1 || spec.channels > 32 || spec.rate < 1000 || spec.rate > 384000) {
                log.log("AUDIO pulse 클라이언트 " + id + " 지원하지 않는 재생 형식 " + spec.name());
                error(tag, ERR_NOTSUPPORTED);
                return;
            }
            Stream s = new Stream(true, nextChannel++, spec, map);
            s.corked = (Boolean) v.get(5);
            fixPlayback(s, (Long) v.get(4), (Long) v.get(6), (Long) v.get(7), (Long) v.get(8));
            s.src = mixer.attach();
            s.src.setPaused(s.corked);
            streams.put(s.channel, s);
            long missing = s.tlength;
            s.requested = missing;
            Writer w = new Writer().u32(s.channel).u32(s.channel).u32(missing);
            if (version >= 9) w.u32(s.maxlength).u32(s.tlength).u32(s.prebuf).u32(s.minreq);
            if (version >= 12) w.spec(spec).map(map).u32(0).str(SINK).bool(false);
            if (version >= 13) w.usec(latencyUsec(s));
            if (version >= 21) w.pcmFormat();
            reply(tag, w);
            log.log(String.format(Locale.ROOT, "AUDIO pulse 재생 열림 %s, 목표 %.0fms, 요청 단위 %.0fms%s",
                    spec.name(), ms(s, s.tlength), ms(s, s.minreq), s.corked ? ", 멈춤 상태로 시작" : ""));
        }

        private void createRecord(int tag, List<Object> v) throws IOException {
            Spec spec = readSpec(v.get(0));
            int[] map = (int[]) v.get(1);
            if (spec.format != FMT_S16LE && spec.format != FMT_FLOAT32LE && spec.format != FMT_S32LE
                    || spec.channels < 1 || spec.channels > 32 || spec.rate < 1000 || spec.rate > 384000) {
                error(tag, ERR_NOTSUPPORTED);
                return;
            }
            Stream s = new Stream(false, nextChannel++, spec, map);
            s.corked = (Boolean) v.get(5);
            fixRecord(s, (Long) v.get(4), (Long) v.get(6));
            s.recStart = System.nanoTime();
            if (mic != null) s.tap = mic.open("pulse " + name);
            streams.put(s.channel, s);
            Writer w = new Writer().u32(s.channel).u32(s.channel);
            if (version >= 9) w.u32(s.maxlength).u32(s.fragsize);
            if (version >= 12) w.spec(spec).map(map).u32(0).str(source()).bool(false);
            if (version >= 13) w.usec(0);
            if (version >= 22) w.pcmFormat();
            reply(tag, w);
            log.log("AUDIO pulse 녹음 열림 " + spec.name() + (mic != null ? " (마이크)" : " (마이크 없음, 무음)"));
        }

        /** Final buffer values for a playback stream ("-1" = server's choice)
         *  재생 스트림의 최종 버퍼 값("-1" = 서버가 정함) */
        private void fixPlayback(Stream s, long maxlength, long tlength, long prebuf, long minreq) {
            long fb = s.spec.frameBytes();
            // Most the mixer can hold for this stream, in the client's bytes (the mixer ring is 48 kHz)
            // 이 스트림에 대해 믹서가 담을 수 있는 최대(클라이언트 바이트, 믹서 링은 48kHz)
            long cap = (long) AudioServer.Source.capacity() * s.spec.rate / RATE * fb * 4 / 5;
            cap -= cap % fb;
            long def = (long) s.spec.rate / 10 * fb;
            s.maxlength = maxlength == INVALID_L || maxlength > cap * 4 ? cap * 4 : Math.max(maxlength - maxlength % fb, fb);
            long t = tlength == INVALID_L || tlength == 0 ? def : tlength;
            t = Math.min(Math.max(t - t % fb, fb * 2), cap);
            long m = minreq == INVALID_L || minreq == 0 ? t / 4 : minreq;
            m = Math.max(m - m % fb, fb);
            if (t < 2 * m) t = Math.min(2 * m, cap);
            if (m * 2 > t) m = Math.max((t / 2) - (t / 2) % fb, fb);
            long p = prebuf == INVALID_L ? t + fb - m : prebuf;
            p = Math.min(Math.max(p - p % fb, 0), t);
            s.tlength = t;
            s.minreq = m;
            s.prebuf = p;
        }

        /** Final buffer values for a record stream / 녹음 스트림의 최종 버퍼 값 */
        private void fixRecord(Stream s, long maxlength, long fragsize) {
            long fb = s.spec.frameBytes();
            long f = fragsize == INVALID_L || fragsize == 0 ? (long) s.spec.rate / 50 * fb : fragsize;
            f = Math.max(f - f % fb, fb);
            s.fragsize = f;
            s.maxlength = maxlength == INVALID_L || maxlength < f * 4 ? f * 4 : maxlength - maxlength % fb;
        }

        private double ms(Stream s, long bytes) {
            return bytes * 1000.0 / ((double) s.spec.rate * s.spec.frameBytes());
        }

        /** Queued sound plus the Android output buffer, in microseconds / 쌓인 소리 + 안드로이드 출력 버퍼(마이크로초) */
        private long latencyUsec(Stream s) {
            if (!s.playback) return 0;
            long frames = (s.src == null ? 0 : s.src.queued()) + mixer.outputFrames();
            return frames * 1_000_000L / RATE;
        }

        private void latency(int tag, Stream s, Tv tv, boolean playback) throws IOException {
            long nowUs = System.currentTimeMillis() * 1000L;
            long write, read;
            boolean playing;
            synchronized (s) {
                if (playback) {
                    write = s.written;
                    read = Math.max(0, write - s.queuedBytes());
                    playing = !s.corked && s.src != null && s.src.isStarted();
                } else {
                    write = s.recSent;
                    read = s.recSent;
                    playing = !s.corked;
                }
            }
            Writer w = new Writer();
            if (playback) {
                w.usec(latencyUsec(s)).usec(0);
            } else {
                w.usec(0).usec(0);
            }
            w.bool(playing).tv(tv.sec, tv.usec).tv(nowUs / 1_000_000L, nowUs % 1_000_000L).s64(write).s64(read);
            if (playback && version >= 13) w.u64(0).u64(read);
            reply(tag, w);
        }

        /** Ask for what the stream is missing, when it is at least one request unit
         *  스트림에 모자란 것이 요청 단위 이상이면 요청함 */
        private void request(Stream s) throws IOException {
            long n;
            synchronized (s) {
                if (s.corked || s.src == null) return;
                long missing = s.tlength - s.queuedBytes() - s.requested;
                if (missing < s.minreq) return;
                missing -= missing % s.spec.frameBytes();
                s.requested += missing;
                n = missing;
            }
            event(CMD_REQUEST, s.channel, n, true);
        }

        /** Audio from the client: convert to 48 kHz stereo 16-bit and hand to the mixer
         *  클라이언트의 소리: 48kHz 스테레오 16비트로 바꿔 믹서에 넘김 */
        private void memblock(int channel, byte[] data) {
            Stream s = streams.get(channel);
            if (s == null || !s.playback) return;
            synchronized (s) {
                s.written += data.length;
                s.requested = Math.max(0, s.requested - data.length);
                short[] st = convert(s, data);
                int frames = st.length / 2;
                int put = s.src.offer(st, frames);
                if (put < frames) s.droppedBytes += (long) (frames - put) * s.spec.frameBytes() * s.spec.rate / RATE;
            }
        }

        /** Client samples to 48 kHz stereo shorts (linear between neighbouring frames)
         *  클라이언트 샘플을 48kHz 스테레오 short로(이웃 프레임 사이 선형) */
        private short[] convert(Stream s, byte[] d) {
            int sb = s.spec.sampleBytes(), ch = s.spec.channels, fb = sb * ch;
            int n = d.length / fb;
            short[] l = new short[n], r = new short[n];
            for (int i = 0; i < n; i++) {
                int o = i * fb;
                l[i] = sample(s.spec.format, d, o);
                r[i] = ch > 1 ? sample(s.spec.format, d, o + sb) : l[i];
            }
            if (s.spec.rate == RATE) {
                short[] outBuf = new short[n * 2];
                for (int i = 0; i < n; i++) {
                    outBuf[i * 2] = l[i];
                    outBuf[i * 2 + 1] = r[i];
                }
                return outBuf;
            }
            double step = s.spec.rate / (double) RATE;
            short[] outBuf = new short[(int) Math.ceil((n + 1) / step) * 2 + 4];
            int k = 0;
            // Position counts from the last frame of the previous block (index -1)
            // 위치는 이전 덩어리의 마지막 프레임(번호 -1)부터 셈
            double pos = s.haveLast ? s.pos : 0;
            while (true) {
                int i0 = (int) Math.floor(pos) - (s.haveLast ? 1 : 0);
                int i1 = i0 + 1;
                if (i1 >= n) break;
                double f = pos - Math.floor(pos);
                short a0 = i0 < 0 ? s.lastL : l[i0], b0 = i0 < 0 ? s.lastR : r[i0];
                if (k + 2 > outBuf.length) break;
                outBuf[k++] = (short) Math.round(a0 + (l[i1] - a0) * f);
                outBuf[k++] = (short) Math.round(b0 + (r[i1] - b0) * f);
                pos += step;
            }
            if (n > 0) {
                // Keep the position relative to this block's last frame / 이 덩어리의 마지막 프레임 기준으로 위치를 남김
                s.pos = pos - (n - (s.haveLast ? 0 : 1));
                s.lastL = l[n - 1];
                s.lastR = r[n - 1];
                s.haveLast = true;
            }
            return java.util.Arrays.copyOf(outBuf, k);
        }

        /** Pacer: asks playback streams for data as the mixer plays them, sends STARTED, and
         *  sends record streams their data in real time (every 5 ms)
         *  박자 맞춤: 믹서가 재생하는 만큼 재생 스트림에 데이터를 요청하고 STARTED를 보내며, 녹음 스트림에는
         *  실시간으로 데이터를 보냄(5ms마다) */
        private void pace() {
            try {
                while (!done && !closed) {
                    Thread.sleep(5);
                    for (Stream s : streams.values()) {
                        if (s.playback) {
                            request(s);
                            boolean start = false;
                            synchronized (s) {
                                if (!s.corked && !s.startedSent && s.src != null && s.src.isStarted()) {
                                    s.startedSent = true;
                                    start = version >= 13;
                                }
                            }
                            if (start) event(CMD_STARTED, s.channel, 0, false);
                        } else {
                            record(s);
                        }
                    }
                }
            } catch (InterruptedException ignored) {
            } catch (IOException e) {
                String m = String.valueOf(e.getMessage());
                if (!done && !closed && !m.contains("Broken pipe") && !m.contains("reset")) {
                    log.log("AUDIO pulse 클라이언트 " + id + " 보내기 실패: " + e);
                }
                shutdown();
            }
        }

        /** Data for a record stream, as much as real time allows, in fragsize pieces: microphone
         *  samples (48 kHz mono) converted to the stream's rate, channels and format; silence where
         *  none arrived yet or without a microphone
         *  녹음 스트림용 데이터. 실제 시간만큼 fragsize 조각으로 보냄: 마이크 샘플(48kHz 모노)을 스트림의 주파수·채널·
         *  형식으로 바꿈. 아직 오지 않은 부분이나 마이크가 없으면 무음 */
        private void record(Stream s) throws IOException {
            byte[] piece = null;
            synchronized (s) {
                if (s.corked) return;
                long due = (System.nanoTime() - s.recStart) / 1000L * s.spec.rate / 1_000_000L * s.spec.frameBytes();
                if (due - s.recSent >= s.fragsize) {
                    piece = new byte[(int) s.fragsize];
                    s.recSent += s.fragsize;
                    if (s.tap != null) fill(s, piece);
                }
            }
            if (piece != null) frame(s.channel, piece);
        }

        /** Microphone samples into one record piece (linear resampling, same value on every channel)
         *  마이크 샘플을 녹음 조각 하나에 채움(선형 변환, 모든 채널에 같은 값) */
        private void fill(Stream s, byte[] piece) {
            int fb = s.spec.frameBytes(), sb = s.spec.sampleBytes();
            int frames = piece.length / fb;
            // 48 kHz samples this piece covers, the fraction carried to the next piece
            // 이 조각이 담는 48kHz 샘플 수, 소수 부분은 다음 조각으로 넘김
            double want = frames * (double) MicCapture.RATE / s.spec.rate + s.recFrac;
            int m = (int) want;
            s.recFrac = want - m;
            if (m <= 0) return;
            short[] in = new short[m];
            s.tap.poll(in, m);
            for (int i = 0; i < frames; i++) {
                double x = (i + 0.5) * m / frames - 0.5;
                int a = Math.max(0, Math.min(m - 1, (int) Math.floor(x)));
                int b = Math.min(m - 1, a + 1);
                double t = Math.max(0, Math.min(1, x - a));
                int v = (int) Math.round(in[a] + (in[b] - in[a]) * t);
                for (int c = 0; c < s.spec.channels; c++) putSample(s.spec.format, piece, i * fb + c * sb, v);
            }
        }

        private void endStream(Stream s, String why) {
            if (s.src != null) s.src.end();
            if (s.tap != null) s.tap.close();
            if (s.playback) {
                log.log(String.format(Locale.ROOT, "AUDIO pulse 재생 %s (%.1f초 분량%s)", why,
                        s.written / ((double) s.spec.rate * s.spec.frameBytes()),
                        s.droppedBytes > 0 ? String.format(Locale.ROOT, ", 넘쳐 버림 %.0fms", ms(s, s.droppedBytes)) : ""));
            }
        }
    }

    private static final long INVALID_L = 0xFFFFFFFFL;

    /** Default source name: the microphone when there is one, otherwise the sink's monitor
     *  기본 소스 이름: 마이크가 있으면 마이크, 없으면 출력 장치의 모니터 */
    private String source() {
        return mic != null ? MIC : MONITOR;
    }

    /** One 16-bit value written in the client's format / 16비트 값 하나를 클라이언트 형식으로 씀 */
    private static void putSample(int format, byte[] d, int o, int v) {
        if (format == FMT_S16LE) {
            d[o] = (byte) v;
            d[o + 1] = (byte) (v >> 8);
            return;
        }
        int bits = format == FMT_S32LE ? v << 16 : Float.floatToIntBits(v / 32768f);
        d[o] = (byte) bits;
        d[o + 1] = (byte) (bits >> 8);
        d[o + 2] = (byte) (bits >> 16);
        d[o + 3] = (byte) (bits >> 24);
    }

    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    private static void put32(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }

    /** One sample of the client's format as a 16-bit value / 클라이언트 형식의 샘플 하나를 16비트 값으로 */
    private static short sample(int format, byte[] d, int o) {
        if (format == FMT_S16LE) return (short) ((d[o] & 0xff) | (d[o + 1] << 8));
        int bits = (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8) | ((d[o + 2] & 0xff) << 16) | (d[o + 3] << 24);
        if (format == FMT_S32LE) return (short) (bits >> 16);
        float f = Float.intBitsToFloat(bits);
        if (!(f == f)) return 0;
        int v = Math.round(f * 32767f);
        return (short) Math.max(-32768, Math.min(32767, v));
    }
}
