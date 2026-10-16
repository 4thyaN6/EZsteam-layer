package app.ezsteam.runtime;

import android.util.SparseIntArray;
import android.view.KeyEvent;

/**
 * Android key event to X keycode for an X server with the default XKB keymap (rules "evdev"),
 * where X keycode = Linux evdev key code + 8.
 * Physical keyboards report the Linux code as the scan code, so it is used directly; soft
 * keyboards report no scan code, so common keys are looked up in a table.
 * 기본 XKB 키 배치(rules "evdev")를 쓰는 X 서버용 안드로이드 키 이벤트 → X 키코드 변환.
 * 이 배치에서 X 키코드 = 리눅스 evdev 키 코드 + 8 임.
 * 물리 키보드는 스캔 코드로 리눅스 코드를 주므로 그대로 쓰고, 소프트 키보드는 스캔 코드가
 * 없으므로 자주 쓰는 키를 표에서 찾음
 */
public final class XKeys {

    /** X keycode of Left Shift (evdev 42 + 8) / 왼쪽 Shift의 X 키코드(evdev 42 + 8) */
    public static final int SHIFT_L = 50;
    /** X keycode of Escape (scan code 1 + 8) / Escape의 X 키코드(스캔 코드 1 + 8) */
    public static final int ESCAPE = 9;

    private static final SparseIntArray MAP = new SparseIntArray();

    static {
        // Letters: Android KEYCODE_A..Z to evdev codes (QWERTY rows)
        // 글자: 안드로이드 KEYCODE_A..Z → evdev 코드(QWERTY 배열)
        int[] letters = {30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44};
        for (int i = 0; i < 26; i++) MAP.put(KeyEvent.KEYCODE_A + i, letters[i]);
        // Digits: KEYCODE_0..9 / 숫자: KEYCODE_0..9
        MAP.put(KeyEvent.KEYCODE_0, 11);
        for (int i = 1; i <= 9; i++) MAP.put(KeyEvent.KEYCODE_0 + i, 1 + i);
        // Number pad digits / 숫자 패드
        int[] pad = {82, 79, 80, 81, 75, 76, 77, 71, 72, 73};
        for (int i = 0; i <= 9; i++) MAP.put(KeyEvent.KEYCODE_NUMPAD_0 + i, pad[i]);

        int[][] other = {
                {KeyEvent.KEYCODE_ESCAPE, 1}, {KeyEvent.KEYCODE_MINUS, 12}, {KeyEvent.KEYCODE_EQUALS, 13},
                {KeyEvent.KEYCODE_DEL, 14}, {KeyEvent.KEYCODE_TAB, 15}, {KeyEvent.KEYCODE_LEFT_BRACKET, 26},
                {KeyEvent.KEYCODE_RIGHT_BRACKET, 27}, {KeyEvent.KEYCODE_ENTER, 28}, {KeyEvent.KEYCODE_CTRL_LEFT, 29},
                {KeyEvent.KEYCODE_SEMICOLON, 39}, {KeyEvent.KEYCODE_APOSTROPHE, 40}, {KeyEvent.KEYCODE_GRAVE, 41},
                {KeyEvent.KEYCODE_SHIFT_LEFT, 42}, {KeyEvent.KEYCODE_BACKSLASH, 43}, {KeyEvent.KEYCODE_COMMA, 51},
                {KeyEvent.KEYCODE_PERIOD, 52}, {KeyEvent.KEYCODE_SLASH, 53}, {KeyEvent.KEYCODE_SHIFT_RIGHT, 54},
                {KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 55}, {KeyEvent.KEYCODE_ALT_LEFT, 56}, {KeyEvent.KEYCODE_SPACE, 57},
                {KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 74}, {KeyEvent.KEYCODE_NUMPAD_ADD, 78},
                {KeyEvent.KEYCODE_NUMPAD_DOT, 83}, {KeyEvent.KEYCODE_NUMPAD_ENTER, 96},
                {KeyEvent.KEYCODE_CTRL_RIGHT, 97}, {KeyEvent.KEYCODE_NUMPAD_DIVIDE, 98},
                {KeyEvent.KEYCODE_ALT_RIGHT, 100}, {KeyEvent.KEYCODE_MOVE_HOME, 102}, {KeyEvent.KEYCODE_DPAD_UP, 103},
                {KeyEvent.KEYCODE_PAGE_UP, 104}, {KeyEvent.KEYCODE_DPAD_LEFT, 105}, {KeyEvent.KEYCODE_DPAD_RIGHT, 106},
                {KeyEvent.KEYCODE_MOVE_END, 107}, {KeyEvent.KEYCODE_DPAD_DOWN, 108}, {KeyEvent.KEYCODE_PAGE_DOWN, 109},
                {KeyEvent.KEYCODE_INSERT, 110}, {KeyEvent.KEYCODE_FORWARD_DEL, 111},
                {KeyEvent.KEYCODE_F1, 59}, {KeyEvent.KEYCODE_F2, 60}, {KeyEvent.KEYCODE_F3, 61}, {KeyEvent.KEYCODE_F4, 62},
                {KeyEvent.KEYCODE_F5, 63}, {KeyEvent.KEYCODE_F6, 64}, {KeyEvent.KEYCODE_F7, 65}, {KeyEvent.KEYCODE_F8, 66},
                {KeyEvent.KEYCODE_F9, 67}, {KeyEvent.KEYCODE_F10, 68}, {KeyEvent.KEYCODE_F11, 87}, {KeyEvent.KEYCODE_F12, 88},
        };
        for (int[] o : other) MAP.put(o[0], o[1]);
    }

    private XKeys() {
    }

    /**
     * X keycode for the event, or 0 when unknown.
     * 이벤트의 X 키코드. 모르면 0
     */
    public static int keycode(KeyEvent e) {
        int scan = e.getScanCode();
        if (scan > 0 && scan < 248) return scan + 8;
        int evdev = MAP.get(e.getKeyCode(), 0);
        return evdev == 0 ? 0 : evdev + 8;
    }

    /** True when the event came from a soft keyboard (no scan code) / 소프트 키보드 이벤트면 참(스캔 코드 없음) */
    public static boolean fromSoftKeyboard(KeyEvent e) {
        return e.getScanCode() <= 0;
    }
}
