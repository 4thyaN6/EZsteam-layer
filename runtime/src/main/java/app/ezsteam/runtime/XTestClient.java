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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

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
    /** Root window of screen 0, atoms WM_PROTOCOLS and WM_TAKE_FOCUS / 화면 0의 루트 창, 아톰 WM_PROTOCOLS·WM_TAKE_FOCUS */
    private final int root, atomProtocols, atomTakeFocus;
    /** Sequence number of the last request sent (send thread only) / 마지막으로 보낸 요청 순번(전송 스레드 전용) */
    private int seq;
    /** Reply handlers by 16-bit sequence number / 16비트 순번별 응답 처리기 */
    private final Map<Integer, Consumer<byte[]>> pending = new ConcurrentHashMap<>();
    /** Top-level window that last got the input focus from here / 여기서 마지막으로 입력 포커스를 준 최상위 창 */
    private volatile int focused;
    /** Log sink given to connect() / connect()에 받은 로그 출력 */
    private volatile Log log;
    private final ExecutorService tx = Executors.newSingleThreadExecutor(r -> new Thread(r, "ezs-xtest"));
    private volatile boolean closed;

    private XTestClient(LocalSocket s, OutputStream out, int opcode, int w, int h, String vendor, int vmaj, int vmin,
                        int root, int atomProtocols, int atomTakeFocus, int seq) {
        this.socket = s;
        this.out = out;
        this.opcode = opcode;
        this.root = root;
        this.atomProtocols = atomProtocols;
        this.atomTakeFocus = atomTakeFocus;
        this.seq = seq;
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
            int rootWin = b.getInt(scr);
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

            // 4. InternAtom WM_PROTOCOLS, WM_TAKE_FOCUS (sequences 3, 4), for giving windows focus
            // 4. InternAtom WM_PROTOCOLS, WM_TAKE_FOCUS(순번 3, 4), 창에 포커스를 줄 때 씀
            int aProt = internAtom(out, in, "WM_PROTOCOLS");
            int aTake = internAtom(out, in, "WM_TAKE_FOCUS");

            // 5. Watch top-level windows appearing (SubstructureNotify on the root, sequence 5), so a
            //    newly shown window gets the focus like under a window manager. Measured: a game
            //    window shown while the focus was still on the Steam window stayed black on its
            //    movie and took no keys until it was clicked.
            // 5. 최상위 창이 나타나는 것을 지켜봄(루트에 SubstructureNotify, 순번 5). 창 관리자처럼 새로
            //    보인 창에 포커스를 주기 위함. 실측: 포커스가 Steam 창에 남은 채 뜬 게임 창이 동영상에서
            //    검은 화면으로 멈추고, 클릭하기 전까지 키를 받지 않았음
            ByteBuffer cw = le(16);
            cw.put((byte) 2).put((byte) 0).putShort((short) 4).putInt(rootWin).putInt(0x800).putInt(0x80000);
            out.write(cw.array());
            out.flush();

            XTestClient c = new XTestClient(s, out, op, w, h, vendor, vmaj, vmin, rootWin, aProt, aTake, 5);
            c.log = log;
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
     * Give the keyboard focus to the top-level window under the pointer, the way a window manager
     * does: SetInputFocus plus a WM_TAKE_FOCUS client message. There is no window manager here, and
     * Wine makes a window the active (foreground) one only on WM_TAKE_FOCUS; without it keys went
     * to no window and DirectInput keyboards of games stayed silent (measured: key events reached
     * X, the game did not react). Sent again only when the window under the pointer changes.
     * 포인터 아래 최상위 창에 창 관리자처럼 키보드 포커스를 줌: SetInputFocus와 WM_TAKE_FOCUS 클라이언트
     * 메시지. 여기에는 창 관리자가 없고, Wine은 WM_TAKE_FOCUS를 받아야 창을 활성(전경) 창으로 만듦.
     * 없으면 키가 어느 창에도 가지 않아 게임의 DirectInput 키보드가 조용했음(실측: 키 이벤트는 X에
     * 도착, 게임은 반응 없음). 포인터 아래 창이 바뀔 때만 다시 보냄
     */
    public void focusUnderPointer() {
        if (closed) return;
        // QueryPointer(root): reply byte 12..15 = child of root under the pointer
        // QueryPointer(root): 응답 12~15바이트 = 포인터 아래의 루트 자식 창
        ByteBuffer q = le(8);
        q.put((byte) 38).put((byte) 0).putShort((short) 2).putInt(root);
        send(q.array(), reply -> {
            int child = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN).getInt(12);
            if (child != 0 && child != focused) {
                focused = child;
                giveFocus(child);
            }
        });
    }

    /** Forget the last focused window, so the next focusUnderPointer() sends again
     *  마지막 포커스 창을 잊음. 다음 focusUnderPointer()가 다시 보냄 */
    public void resetFocus() {
        focused = 0;
    }

    /** SetInputFocus(window, RevertToParent) + ClientMessage WM_PROTOCOLS/WM_TAKE_FOCUS to it
     *  SetInputFocus(창, RevertToParent) + 그 창에 ClientMessage WM_PROTOCOLS/WM_TAKE_FOCUS */
    private void giveFocus(int window) {
        giveFocus(window, "");
    }

    private void giveFocus(int window, String why) {
        Log l = log;
        if (l != null) l.line(String.format(java.util.Locale.ROOT, "X 포커스 → 창 0x%x%s", window, why));
        ByteBuffer f = le(12);
        f.put((byte) 42).put((byte) 2).putShort((short) 3).putInt(window).putInt(0);
        send(f.array(), null);
        if (atomProtocols == 0 || atomTakeFocus == 0) return;
        // SendEvent: propagate no, event mask 0 (goes to the window's creator), 32-byte ClientMessage
        // SendEvent: 전파 없음, 이벤트 마스크 0(창을 만든 클라이언트에게 감), 32바이트 ClientMessage
        ByteBuffer e = le(44);
        e.put((byte) 25).put((byte) 0).putShort((short) 11).putInt(window).putInt(0);
        e.put((byte) 33).put((byte) 32).putShort((short) 0).putInt(window).putInt(atomProtocols);
        e.putInt(atomTakeFocus).putInt(0).putInt(0).putInt(0).putInt(0);
        send(e.array(), null);
    }

    /**
     * Queue one request on the send thread; it takes the next sequence number, and a reply handler
     * (requests with replies only) runs on the reader thread.
     * 요청 하나를 전송 스레드에 넣음. 다음 순번을 받으며, 응답 처리기(응답이 있는 요청만)는 읽기 스레드에서 돎
     */
    private void send(byte[] data, Consumer<byte[]> onReply) {
        if (closed) return;
        tx.execute(() -> {
            try {
                seq++;
                if (onReply != null) pending.put(seq & 0xffff, onReply);
                out.write(data);
                out.flush();
            } catch (IOException e) {
                closed = true;
            }
        });
    }

    /** InternAtom during setup (synchronous); 0 when it fails / 설정 중 InternAtom(동기). 실패하면 0 */
    private static int internAtom(OutputStream out, DataInputStream in, String name) throws IOException {
        byte[] n = name.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer r = le(8 + pad4(n.length));
        r.put((byte) 16).put((byte) 0).putShort((short) ((8 + pad4(n.length)) / 4))
                .putShort((short) n.length).putShort((short) 0).put(n);
        out.write(r.array());
        out.flush();
        byte[] a = readReply(in);
        return ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN).getInt(8);
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
        send(r.array(), null);
    }

    /**
     * Read everything the server sends: replies go to their handler, X errors are reported,
     * events are dropped (none are selected; generic events carry extra data).
     * 서버가 보내는 것을 모두 읽음: 응답은 처리기로, X 오류는 알림, 이벤트는 버림(선택한 이벤트 없음,
     * generic 이벤트는 추가 데이터가 있음)
     */
    private void startReader(DataInputStream in, Log log) {
        Thread t = new Thread(() -> {
            byte[] p = new byte[32];
            try {
                while (!closed) {
                    in.readFully(p);
                    ByteBuffer pb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
                    int sq = pb.getShort(2) & 0xffff;
                    if (p[0] == 0) {
                        pending.remove(sq);
                        // BadWindow (3) on focus requests is normal when a window just closed
                        // 창이 막 닫혔을 때 포커스 요청의 BadWindow(3)는 정상
                        int major = p[10] & 0xff;
                        boolean benign = (p[1] & 0xff) == 3 && (major == 42 || major == 25);
                        if (log != null && !benign) log.line("X error code=" + (p[1] & 0xff) + " major=" + major
                                + " minor=" + (pb.getShort(8) & 0xffff));
                    } else if (p[0] == 1) {
                        skip(in, pb.getInt(4) * 4);
                        Consumer<byte[]> h = pending.remove(sq);
                        if (h != null) h.accept(p);
                    } else if ((p[0] & 0x7f) == 35) {
                        skip(in, pb.getInt(4) * 4);
                    } else if ((p[0] & 0x7f) == 19) {
                        // MapNotify on the root: bytes 8..11 the window, byte 12 override-redirect
                        // (menus, tooltips: no focus) / 루트의 MapNotify: 8~11바이트 창, 12바이트
                        // override-redirect(메뉴·툴팁: 포커스 안 줌)
                        int win = pb.getInt(8);
                        if (p[12] == 0 && win != 0) {
                            focused = win;
                            giveFocus(win, " (새 창)");
                        }
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
