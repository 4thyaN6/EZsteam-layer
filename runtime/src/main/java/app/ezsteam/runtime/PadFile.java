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

    /**
     * One input source's controller state. Two sources feed the same virtual pad: a physical
     * controller and the on-screen touch pad; write() merges them (buttons: either pressed;
     * sticks: the one pushed further; triggers: the larger).
     * 입력원 하나의 컨트롤러 상태. 같은 가상 패드에 두 입력원이 들어감: 실제 컨트롤러와 화면 터치 패드.
     * write()가 합침(버튼: 어느 쪽이든 누르면 누름, 스틱: 더 많이 민 쪽, 트리거: 큰 값)
     */
    public static final class State {
        final float[] axes = new float[4];
        float leftTrigger, rightTrigger;
        final boolean[] buttons = new boolean[BUTTONS];

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

        /** Everything released, sticks centred / 모두 뗌, 스틱 가운데 */
        public void clear() {
            java.util.Arrays.fill(axes, 0f);
            leftTrigger = rightTrigger = 0f;
            java.util.Arrays.fill(buttons, false);
        }
    }

    /** Physical controller (methods below) and touch pad sources / 실제 컨트롤러(아래 메서드)와 터치 패드 입력원 */
    private final State physical = new State();
    public final State touch = new State();

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

    // Physical controller state / 실제 컨트롤러 상태
    public void setAxis(int i, float v) {
        physical.setAxis(i, v);
    }

    public void setTriggers(float left, float right) {
        physical.setTriggers(left, right);
    }

    public void setButton(int i, boolean down) {
        physical.setButton(i, down);
    }

    public boolean button(int i) {
        return physical.button(i);
    }

    /**
     * Called with each button that goes down, from either source (SDL button order); the left
     * stick pushed past 60 % also counts as the D-pad. Drives HOME and the app's own menu.
     * 어느 입력원이든 버튼이 눌리는 순간 그 버튼으로 부름(SDL 버튼 순서). 왼쪽 스틱을 60% 넘게 밀어도
     * D-pad로 셈. HOME과 앱 자체 메뉴가 씀
     */
    public interface ButtonListener {
        void pressed(int button);
    }

    private volatile ButtonListener buttonListener;
    private final boolean[] was = new boolean[BUTTONS];

    public void setButtonListener(ButtonListener l) {
        buttonListener = l;
    }

    /** Byte 33 of the file: 1 = the Steam client reads a released pad (app menu open)
     *  파일 33번째 바이트: 1 = Steam 클라이언트는 뗀 패드로 읽음(앱 메뉴가 열림) */
    public static final int STEAM_MUTE_OFFSET = 33;

    public synchronized void setSteamMuted(boolean on) {
        buf.put(STEAM_MUTE_OFFSET, (byte) (on ? 1 : 0));
    }

    /** Write the whole state to the shared file / 전체 상태를 공유 파일에 씀 */
    public synchronized void write() {
        ButtonListener bl = buttonListener;
        for (int i = 0; i < BUTTONS; i++) {
            boolean down = physical.buttons[i] || touch.buttons[i];
            // Left stick as D-pad for menus / 메뉴용으로 왼쪽 스틱을 D-pad로
            float lx = Math.abs(physical.axes[0]) > Math.abs(touch.axes[0]) ? physical.axes[0] : touch.axes[0];
            float ly = Math.abs(physical.axes[1]) > Math.abs(touch.axes[1]) ? physical.axes[1] : touch.axes[1];
            if (i == DPAD_UP) down |= ly < -0.6f;
            if (i == DPAD_DOWN) down |= ly > 0.6f;
            if (i == DPAD_LEFT) down |= lx < -0.6f;
            if (i == DPAD_RIGHT) down |= lx > 0.6f;
            if (down && !was[i] && bl != null) bl.pressed(i);
            was[i] = down;
        }
        buf.position(0);
        // Each stick from the source that pushes it further / 스틱마다 더 많이 민 입력원 값
        for (int s = 0; s < 4; s += 2) {
            State src = mag(physical, s) >= mag(touch, s) ? physical : touch;
            buf.putShort((short) Math.round(src.axes[s] * 32767));
            buf.putShort((short) Math.round(src.axes[s + 1] * 32767));
        }
        // Triggers: -32767 released .. 32767 fully pressed / 트리거: -32767 뗌 .. 32767 끝까지 누름
        float lt = Math.max(physical.leftTrigger, touch.leftTrigger);
        float rt = Math.max(physical.rightTrigger, touch.rightTrigger);
        buf.putShort((short) Math.round(lt * 65534 - 32767));
        buf.putShort((short) Math.round(rt * 65534 - 32767));
        for (int i = 0; i < BUTTONS; i++) buf.put((byte) (physical.buttons[i] || touch.buttons[i] ? 1 : 0));
        buf.put((byte) 0);
    }

    /** Byte 32 of the file: 1 = games read a released pad (the Steam client still reads it all)
     *  파일 32번째 바이트: 1 = 게임은 뗀 패드로 읽음(Steam 클라이언트는 그대로 다 읽음) */
    public static final int MUTE_OFFSET = 32;

    /**
     * Keep games from seeing the pad while a Steam menu or dialog is over them (HOME opened the
     * Steam menu, and the game also took the presses, 2026-10-24).
     * Steam 메뉴나 대화상자가 게임 위에 있는 동안 게임이 패드를 보지 않게 함(HOME으로 Steam 메뉴를 열었는데
     * 게임도 입력을 받았음, 2026-10-24)
     */
    public synchronized void setGameMuted(boolean on) {
        if (buf.get(MUTE_OFFSET) == (byte) (on ? 1 : 0)) return;
        buf.put(MUTE_OFFSET, (byte) (on ? 1 : 0));
    }

    /** Squared push of the stick whose x axis is index s / x축 번호가 s인 스틱을 민 정도의 제곱 */
    private static float mag(State st, int s) {
        return st.axes[s] * st.axes[s] + st.axes[s + 1] * st.axes[s + 1];
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }
}
