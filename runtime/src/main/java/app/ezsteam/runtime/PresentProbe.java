package app.ezsteam.runtime;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

/**
 * App side of the game frame path check (native/ezspresent, libezs_present.so). Listens on a
 * UNIX socket inside the rootfs (guest path /tmp/ezs-present.sock) and answers
 * /opt/ezsteam/bin/ezs-present-probe, which steam-run.sh starts before the Steam client: the
 * probe tests Vulkan export/import of GPU buffers and fences, this side tests the EGL half with
 * its own off-screen EGL context. Results go into the Steam log as "INFO present :" lines.
 * The same socket takes the game frame path: a game's present layer (VK_LAYER_EZS_present)
 * connects, gets AHardwareBuffers to draw into, and sends each finished frame; FramebufferView
 * shows it through frame() / frameDone() (the compositor, zero copy).
 * Runs as a thread of the app (no extra process). Nothing leaves the device.
 * 게임 프레임 경로 확인의 앱 쪽(native/ezspresent, libezs_present.so). rootfs 안 UNIX 소켓(guest 경로
 * /tmp/ezs-present.sock)에서 기다리고, steam-run.sh가 Steam 클라이언트 전에 시작하는
 * /opt/ezsteam/bin/ezs-present-probe에 답함: probe는 GPU 버퍼·펜스의 Vulkan 내보내기/가져오기를,
 * 이쪽은 화면 밖 EGL 컨텍스트로 EGL 쪽을 시험함. 결과는 Steam 로그의 "INFO present :" 줄로 남음.
 * 같은 소켓으로 게임 프레임 경로도 받음: 게임의 present 레이어(VK_LAYER_EZS_present)가 연결해 그릴
 * AHardwareBuffer를 받고 끝난 프레임마다 보냄. FramebufferView가 frame() / frameDone()으로 보여 줌
 * (컴포지터, 0복사).
 * 앱 스레드로 동작(추가 프로세스 없음). 기기 밖으로 나가는 데이터 없음
 */
public final class PresentProbe implements Closeable {

    /** Log sink / 로그 출력 */
    public interface Log {
        void log(String line);
    }

    /** Guest path, same as EZP_GUEST_SOCKET in ezs_present_proto.h / guest 경로, ezs_present_proto.h와 같음 */
    public static final String GUEST_SOCKET = "/tmp/ezs-present.sock";

    private static final boolean LOADED;

    static {
        boolean ok;
        try {
            System.loadLibrary("ezs_present");
            ok = true;
        } catch (UnsatisfiedLinkError e) {
            ok = false;
        }
        LOADED = ok;
    }

    private static native int nativeListen(String path);

    private static native String nativeServe(int fd);

    private static native void nativeClose(int fd);

    private static native int nativeFrame(int[] out);

    private static native void nativeFrameDone();

    private static native void nativeGlReset();

    private static native void nativeSetRect(int id, int x, int y, int w, int h);

    private static native int[] nativeConnections();

    /** Window position source (the host's X connection) / 창 위치 출처(호스트의 X 연결) */
    public interface WindowRects {
        /** Ask the window's place in X screen pixels; answer may come later on any thread
         *  창 위치를 X 화면 화소로 물음. 답은 나중에 아무 스레드에서나 올 수 있음 */
        void query(int window, RectCallback cb);
    }

    public interface RectCallback {
        void rect(int x, int y, int w, int h);
    }

    private volatile WindowRects rects;

    /** Set where window positions come from (null = treat game frames as full screen)
     *  창 위치를 어디서 얻을지 정함(null = 게임 프레임을 전체 화면으로 봄) */
    public void setWindowRects(WindowRects r) {
        rects = r;
    }

    /**
     * Game frame for this display refresh (GL thread of the view). out: texture, width, height,
     * window x, y, w, h (w 0 = unknown), connection id, 1 if new since the last call. False when no
     * game is presenting through the layer (the X screen is shown as before).
     * 이번 화면 갱신의 게임 프레임(뷰의 GL 스레드). out: 텍스처, 폭, 높이, 창 x, y, w, h(w 0 = 모름),
     * 연결 id, 지난 호출 뒤 새 것이면 1. 레이어로 present하는 게임이 없으면 false(지금처럼 X 화면을 보임)
     */
    public static boolean frame(int[] out) {
        return LOADED && nativeFrame(out) != 0;
    }

    /** After drawing: the previous game frame goes back with a GL fence (GL thread)
     *  그린 뒤: 이전 게임 프레임을 GL 펜스와 함께 돌려줌(GL 스레드) */
    public static void frameDone() {
        if (LOADED) nativeFrameDone();
    }

    /** New GL context: forget textures of the old one (GL thread) / 새 GL 컨텍스트: 이전 텍스처를 잊음(GL 스레드) */
    public static void glReset() {
        if (LOADED) nativeGlReset();
    }

    private final int fd;
    private final File socketFile;
    private volatile boolean closed;

    private PresentProbe(int fd, File socketFile) {
        this.fd = fd;
        this.socketFile = socketFile;
    }

    /** Whether the native library is in this APK / 이 APK에 네이티브 라이브러리가 있는지 */
    public static boolean available() {
        return LOADED;
    }

    /**
     * Bind rootfs + GUEST_SOCKET and answer probe connections on a daemon thread.
     * rootfs + GUEST_SOCKET에 소켓을 만들고 데몬 스레드에서 probe 연결에 답함
     */
    public static PresentProbe start(File rootfs, Log log) throws IOException {
        if (!LOADED) throw new IOException("libezs_present.so 없음");
        File f = new File(rootfs, GUEST_SOCKET.substring(1));
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        int fd = nativeListen(f.getAbsolutePath());
        if (fd < 0) throw new IOException("listen 실패 errno " + (-fd));
        PresentProbe p = new PresentProbe(fd, f);
        Thread t = new Thread(() -> {
            while (!p.closed) {
                String r = nativeServe(fd);
                if (r == null) {
                    if (!p.closed) log.log("PRESENT 연결 받기 끝");
                    return;
                }
                if (r.startsWith("S ")) {
                    // Present layer of a game: "S id w h buffers vkformat window pid stride"
                    // 게임의 present 레이어: "S id w h 버퍼 vk형식 창 pid 줄간격"
                    String[] v = r.split(" ");
                    log.log("PRESENT 게임 화면 직접 전달 연결 " + v[2] + "x" + v[3] + ", 버퍼 " + v[4]
                            + "개, 형식 " + v[5] + ", 창 0x" + Long.toHexString(Long.parseLong(v[6]))
                            + ", pid " + v[7]);
                } else if (r.startsWith("E ")) {
                    log.log("PRESENT 게임 화면 직접 전달 실패: " + r.substring(2));
                } else {
                    // Full lines are printed by the probe into the Steam log; one summary line here
                    // 전체 줄은 probe가 Steam 로그에 찍음. 여기서는 요약 한 줄
                    log.log("PRESENT 확인 응답 완료 (" + r.split("\n").length + "줄)");
                }
            }
        }, "ezs-present");
        t.setDaemon(true);
        t.start();
        // Once a second: window places for live connections, and a line when a connection starts
        // or stops showing frames / 1초마다: 살아 있는 연결의 창 위치, 연결이 프레임을 보이기 시작하거나
        // 멈출 때 한 줄
        Thread w = new Thread(() -> {
            java.util.Map<Integer, int[]> seen = new java.util.HashMap<>();
            while (!p.closed) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                int[] c = nativeConnections();
                java.util.Set<Integer> live = new java.util.HashSet<>();
                for (int i = 0; i + 3 < c.length; i += 4) {
                    final int id = c[i], win = c[i + 1], presents = c[i + 2], shown = c[i + 3];
                    live.add(id);
                    int[] old = seen.get(id);
                    if (old == null || (old[1] == 0 && shown > 0)) {
                        if (shown > 0) log.log("PRESENT 게임 프레임 표시 중 (연결 " + id + ")");
                    }
                    seen.put(id, new int[]{presents, shown});
                    WindowRects wr = p.rects;
                    if (wr != null && win != 0) wr.query(win, (x, y, ww, hh) -> nativeSetRect(id, x, y, ww, hh));
                }
                for (java.util.Iterator<java.util.Map.Entry<Integer, int[]>> it = seen.entrySet().iterator(); it.hasNext(); ) {
                    java.util.Map.Entry<Integer, int[]> e = it.next();
                    if (!live.contains(e.getKey())) {
                        log.log("PRESENT 게임 화면 연결 끝 (연결 " + e.getKey() + ", present " + e.getValue()[0]
                                + ", 표시 " + e.getValue()[1] + ")");
                        it.remove();
                    }
                }
            }
        }, "ezs-present-watch");
        w.setDaemon(true);
        w.start();
        return p;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        nativeClose(fd);
        //noinspection ResultOfMethodCallIgnored
        socketFile.delete();
    }
}
