package app.ezsteam.runtime;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Game controller state shared with the rootfs: a 64-byte file the preload library
 * native/ezspad/ezs_pad.c reads in Wine's winebus process and turns into an SDL virtual Xbox 360
 * controller. Layout (little endian): int16 lx, ly, rx, ry, lt, rt; uint8 btn[15] in SDL
 * controller order; uint8 hat (unused). Memory-mapped, so a write is visible at once.
 * rootfs와 공유하는 게임 컨트롤러 상태: Wine winebus 프로세스에서 preload 라이브러리
 * native/ezspad/ezs_pad.c가 읽어 SDL 가상 Xbox 360 컨트롤러로 바꾸는 64바이트 파일.
 * 배치(리틀 엔디언): int16 lx, ly, rx, ry, lt, rt, uint8 btn[15](SDL 컨트롤러 순서), uint8 hat(안 씀).
 * 메모리 매핑이라 쓰면 바로 보임
 */
public final class PadFile implements Closeable {

    /** Guest path, also the default of EZS_PAD_FILE / guest 경로, EZS_PAD_FILE 기본값과 같음 */
    public static final String GUEST_PATH = "/tmp/ezs-pad0.mem";

    // SDL controller button order / SDL 컨트롤러 버튼 순서
    public static final int A = 0, B = 1, X = 2, Y = 3, BACK = 4, GUIDE = 5, START = 6,
            LEFT_STICK = 7, RIGHT_STICK = 8, LEFT_SHOULDER = 9, RIGHT_SHOULDER = 10,
            DPAD_UP = 11, DPAD_DOWN = 12, DPAD_LEFT = 13, DPAD_RIGHT = 14;
    public static final int BUTTONS = 15;

    private final RandomAccessFile raf;
    private final MappedByteBuffer buf;

    // Current state / 현재 상태
    private final float[] axes = new float[4];
    private float leftTrigger, rightTrigger;
    private final boolean[] buttons = new boolean[BUTTONS];

    private PadFile(RandomAccessFile raf, MappedByteBuffer buf) {
        this.raf = raf;
        this.buf = buf;
    }

    /** Create (or reuse) rootfs + GUEST_PATH, reset to "nothing pressed"
     *  rootfs + GUEST_PATH를 만들거나 다시 쓰고 "아무것도 안 누름"으로 초기화함 */
    public static PadFile open(File rootfs) throws IOException {
        File f = new File(rootfs, GUEST_PATH.substring(1));
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        RandomAccessFile raf = new RandomAccessFile(f, "rw");
        raf.setLength(64);
        MappedByteBuffer buf = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, 64);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        PadFile p = new PadFile(raf, buf);
        p.write();
        return p;
    }

    /** Stick axis 0 lx, 1 ly, 2 rx, 3 ry in -1..1 (down/right positive)
     *  스틱 축 0 lx, 1 ly, 2 rx, 3 ry, -1..1(아래·오른쪽이 +) */
    public void setAxis(int i, float v) {
        axes[i] = Math.max(-1f, Math.min(1f, v));
    }

    /** Triggers in 0..1 / 트리거 0..1 */
    public void setTriggers(float left, float right) {
        leftTrigger = Math.max(0f, Math.min(1f, left));
        rightTrigger = Math.max(0f, Math.min(1f, right));
    }

    public void setButton(int i, boolean down) {
        buttons[i] = down;
    }

    public boolean button(int i) {
        return buttons[i];
    }

    /** Write the whole state to the shared file / 전체 상태를 공유 파일에 씀 */
    public synchronized void write() {
        buf.position(0);
        for (float a : axes) buf.putShort((short) Math.round(a * 32767));
        // Triggers: -32767 released .. 32767 fully pressed / 트리거: -32767 뗌 .. 32767 끝까지 누름
        buf.putShort((short) Math.round(leftTrigger * 65534 - 32767));
        buf.putShort((short) Math.round(rightTrigger * 65534 - 32767));
        for (boolean b : buttons) buf.put((byte) (b ? 1 : 0));
        buf.put((byte) 0);
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }
}
