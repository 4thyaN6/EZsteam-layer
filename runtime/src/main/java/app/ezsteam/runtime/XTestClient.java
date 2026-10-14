package app.ezsteam.runtime;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal X11 client that injects pointer and keyboard input into an X server through the XTEST
 * extension (FakeInput), over the server's Unix socket. Needs no Xlib; speaks the X11 wire
 * protocol directly (little endian). The server must allow the connection without a cookie
 * (Xvfb "-ac"). Sends happen on one background thread, in call order.
 * XTEST 확장(FakeInput)으로 X 서버에 포인터·키보드 입력을 넣는 최소 X11 클라이언트.
 * 서버 Unix 소켓으로 연결함. Xlib 없이 X11 통신 규약을 직접 씀(리틀 엔디언).
 * 서버가 인증 없이 연결을 허용해야 함(Xvfb "-ac"). 전송은 백그라운드 스레드 하나에서 호출 순서대로 함
 */
public final class XTestClient implements Closeable {

    /** Log callback (X errors, connection end) / 로그 콜백(X 오류, 연결 끝) */
    public interface Log {
        void line(String s);
    }

    // Core event types used by FakeInput / FakeInput이 쓰는 코어 이벤트 종류
    private static final int KEY_PRESS = 2, KEY_RELEASE = 3, BUTTON_PRESS = 4, BUTTON_RELEASE = 5, MOTION = 6;

    public final int screenWidth, screenHeight;
    public final String vendor;
    public final int xtestMajor, xtestMinor;

    private final LocalSocket socket;
    private final OutputStream out;
    private final int opcode;
    private final ExecutorService tx = Executors.newSingleThreadExecutor(r -> new Thread(r, "ezs-xtest"));
    private volatile boolean closed;

    private XTestClient(LocalSocket s, OutputStream out, int opcode, int w, int h, String vendor, int vmaj, int vmin) {
        this.socket = s;
        this.out = out;
        this.opcode = opcode;
        this.screenWidth = w;
        this.screenHeight = h;
        this.vendor = vendor;
        this.xtestMajor = vmaj;
        this.xtestMinor = vmin;
    }

    /**
     * Connect to the X server socket file (real Android path), check XTEST, start the reply reader.
     * Blocking: call from a background thread.
     * X 서버 소켓 파일(실제 안드로이드 경로)에 연결하고 XTEST를 확인한 뒤 응답 읽기 스레드를 시작함.
     * 블로킹 동작이므로 백그라운드 스레드에서 부를 것
     */
    public static XTestClient connect(File socketFile, Log log) throws IOException {
        LocalSocket s = new LocalSocket();
        try {
            s.connect(new LocalSocketAddress(socketFile.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
            OutputStream out = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());

            // 1. Connection setup: little endian, protocol 11.0, no authorization
            // 1. 연결 설정: 리틀 엔디언, 프로토콜 11.0, 인증 없음
            ByteBuffer req = le(12);
            req.put((byte) 'l').put((byte) 0).putShort((short) 11).putShort((short) 0)
                    .putShort((short) 0).putShort((short) 0).putShort((short) 0);
            out.write(req.array());
            out.flush();

            byte[] head = new byte[8];
            in.readFully(head);
            ByteBuffer hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            int status = head[0];
            int extra = (hb.getShort(6) & 0xffff) * 4;
            byte[] body = new byte[extra];
            in.readFully(body);
            if (status != 1) {
                String reason = status == 0 ? new String(body, 0, Math.min(head[1] & 0xff, body.length), StandardCharsets.ISO_8859_1) : "authenticate";
                throw new IOException("X connection refused: " + reason);
            }
            ByteBuffer b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
            int vendorLen = b.getShort(16) & 0xffff;
            int formats = b.get(21) & 0xff;
            String vendor = new String(body, 32, vendorLen, StandardCharsets.ISO_8859_1);
            // First screen: after vendor (padded to 4) and pixmap formats (8 bytes each)
            // 첫 화면 정보: vendor(4바이트 맞춤)와 픽스맵 형식(각 8바이트) 다음
            int scr = 32 + pad4(vendorLen) + formats * 8;
            int w = b.getShort(scr + 20) & 0xffff;
            int h = b.getShort(scr + 22) & 0xffff;

            // 2. QueryExtension "XTEST" (sequence 1) / 2. QueryExtension "XTEST"(순번 1)
            byte[] name = "XTEST".getBytes(StandardCharsets.ISO_8859_1);
            ByteBuffer q = le(8 + pad4(name.length));
            q.put((byte) 98).put((byte) 0).putShort((short) ((8 + pad4(name.length)) / 4))
                    .putShort((short) name.length).putShort((short) 0).put(name);
            out.write(q.array());
            out.flush();
            byte[] r = readReply(in);
            if (r[8] == 0) throw new IOException("X server has no XTEST extension");
            int op = r[9] & 0xff;

            // 3. XTEST GetVersion 2.2 (sequence 2), for the log / 3. XTEST GetVersion 2.2(순번 2), 기록용
            ByteBuffer gv = le(8);
            gv.put((byte) op).put((byte) 0).putShort((short) 2).put((byte) 2).put((byte) 0).putShort((short) 2);
            out.write(gv.array());
            out.flush();
            byte[] v = readReply(in);
            int vmaj = v[1] & 0xff;
            int vmin = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN).getShort(8) & 0xffff;

            XTestClient c = new XTestClient(s, out, op, w, h, vendor, vmaj, vmin);
            c.startReader(in, log);
            return c;
        } catch (IOException | RuntimeException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    /** Move the pointer to an absolute screen position / 포인터를 화면 절대 위치로 옮김 */
    public void motion(int x, int y) {
        fake(MOTION, 0, x, y);
    }

    /** Move the pointer by an offset (relative mode) / 포인터를 상대 이동함(상대 모드) */
    public void motionRelative(int dx, int dy) {
        fake(MOTION, 1, dx, dy);
    }

    /** Press or release a button: 1 left, 2 middle, 3 right, 4/5 wheel up/down
     *  버튼 누름·뗌: 1 왼쪽, 2 가운데, 3 오른쪽, 4/5 휠 위/아래 */
    public void button(int button, boolean down) {
        fake(down ? BUTTON_PRESS : BUTTON_RELEASE, button, 0, 0);
    }

    /** Press or release an X keycode (Linux evdev code + 8 with the default keymap)
     *  X 키코드 누름·뗌(기본 키 배치에서는 리눅스 evdev 코드 + 8) */
    public void key(int keycode, boolean down) {
        fake(down ? KEY_PRESS : KEY_RELEASE, keycode, 0, 0);
    }

    /**
     * XTEST FakeInput request (36 bytes): event type, detail, time = CurrentTime, root = None,
     * position. Queued to the send thread.
     * XTEST FakeInput 요청(36바이트): 이벤트 종류, detail, 시각 = CurrentTime, root = None,
     * 위치. 전송 스레드에 넣음
     */
    private void fake(int type, int detail, int x, int y) {
        if (closed) return;
        ByteBuffer r = le(36);
        r.put((byte) opcode).put((byte) 2).putShort((short) 9);
        r.put((byte) type).put((byte) detail).putShort((short) 0);
        r.putInt(0).putInt(0).putInt(0).putInt(0);
        r.putShort((short) x).putShort((short) y);
        // remaining 8 bytes stay zero (deviceid 0 = core device) / 나머지 8바이트는 0(deviceid 0 = 코어 장치)
        byte[] data = r.array();
        tx.execute(() -> {
            try {
                out.write(data);
                out.flush();
            } catch (IOException e) {
                closed = true;
            }
        });
    }

    /**
     * Read and drop everything the server sends; report X errors (FakeInput has no reply).
     * 서버가 보내는 것을 읽어 버리고 X 오류만 알림(FakeInput은 응답이 없음)
     */
    private void startReader(DataInputStream in, Log log) {
        Thread t = new Thread(() -> {
            byte[] p = new byte[32];
            try {
                while (!closed) {
                    in.readFully(p);
                    if (p[0] == 0) {
                        if (log != null) log.line("X error code=" + (p[1] & 0xff) + " major=" + (p[10] & 0xff)
                                + " minor=" + (ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).getShort(8) & 0xffff));
                    } else if (p[0] == 1) {
                        skip(in, ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).getInt(4) * 4);
                    }
                }
            } catch (IOException e) {
                if (!closed && log != null) log.line("X 입력 연결 끊김: " + e.getMessage());
            }
            closed = true;
        }, "ezs-xtest-rx");
        t.setDaemon(true);
        t.start();
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        tx.shutdownNow();
        socket.close();
    }

    /** Read one 32-byte reply plus its extra data; X errors become exceptions
     *  32바이트 응답과 추가 데이터를 읽음. X 오류는 예외로 바꿈 */
    private static byte[] readReply(DataInputStream in) throws IOException {
        byte[] r = new byte[32];
        in.readFully(r);
        if (r[0] == 0) throw new IOException("X error code=" + (r[1] & 0xff));
        skip(in, ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN).getInt(4) * 4);
        return r;
    }

    /** Read and drop n bytes exactly / 정확히 n바이트를 읽어 버림 */
    private static void skip(DataInputStream in, int n) throws IOException {
        byte[] buf = new byte[256];
        while (n > 0) {
            int k = Math.min(n, buf.length);
            in.readFully(buf, 0, k);
            n -= k;
        }
    }

    private static ByteBuffer le(int n) {
        return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static int pad4(int n) {
        return (n + 3) & ~3;
    }
}
