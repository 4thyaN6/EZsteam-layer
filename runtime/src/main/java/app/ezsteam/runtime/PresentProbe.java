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
 * Runs as a thread of the app (no extra process). Nothing leaves the device.
 * 게임 프레임 경로 확인의 앱 쪽(native/ezspresent, libezs_present.so). rootfs 안 UNIX 소켓(guest 경로
 * /tmp/ezs-present.sock)에서 기다리고, steam-run.sh가 Steam 클라이언트 전에 시작하는
 * /opt/ezsteam/bin/ezs-present-probe에 답함: probe는 GPU 버퍼·펜스의 Vulkan 내보내기/가져오기를,
 * 이쪽은 화면 밖 EGL 컨텍스트로 EGL 쪽을 시험함. 결과는 Steam 로그의 "INFO present :" 줄로 남음.
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
                // Full lines are printed by the probe into the Steam log; one summary line here
                // 전체 줄은 probe가 Steam 로그에 찍음. 여기서는 요약 한 줄
                log.log("PRESENT 확인 응답 완료 (" + r.split("\n").length + "줄)");
            }
        }, "ezs-present");
        t.setDaemon(true);
        t.start();
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
