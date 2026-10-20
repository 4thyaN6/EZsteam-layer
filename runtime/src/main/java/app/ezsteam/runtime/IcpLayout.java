package app.ezsteam.runtime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * On-screen control layout read from an input controls profile file (.icp, the JSON format that
 * Winlator and EZlator export: {"name", "elements": [{type, shape, bindings[4], scale, x, y,
 * toggleSwitch, text}]}). Only the file format is shared; the reader and the input mapping below
 * are written for this layer. x and y are the element centre as a fraction of the screen width and
 * height; sizes are counted in "grid" units of 1/100 of the screen width.
 * 입력 컨트롤 프로필 파일(.icp, Winlator·EZlator가 내보내는 JSON 형식)에서 읽은 화면 조작 배치.
 * 파일 형식만 같고, 아래 읽기와 입력 연결은 이 레이어용으로 새로 작성함. x, y는 화면 너비·높이에 대한
 * 요소 중심 비율이고, 크기는 화면 너비의 1/100인 "칸" 단위로 셈
 */
public final class IcpLayout {

    /** Element kinds in the file / 파일의 요소 종류 */
    public static final String BUTTON = "BUTTON", D_PAD = "D_PAD", STICK = "STICK",
            TRACKPAD = "TRACKPAD", RANGE_BUTTON = "RANGE_BUTTON";

    /** One element as written in the file / 파일에 적힌 요소 하나 */
    public static final class Element {
        public final String type;
        public final String shape;
        /** Up to 4 binding names; for a d-pad or stick: up, right, down, left
         *  바인딩 이름 최대 4개. 십자키·스틱은 위, 오른쪽, 아래, 왼쪽 순 */
        public final String[] bindings;
        public final float scale, x, y;
        public final boolean toggle;
        public final String text;

        Element(String type, String shape, String[] bindings, float scale, float x, float y,
                boolean toggle, String text) {
            this.type = type;
            this.shape = shape;
            this.bindings = bindings;
            this.scale = scale;
            this.x = x;
            this.y = y;
            this.toggle = toggle;
            this.text = text;
        }

        /**
         * Half width and half height in grid units (1/100 of the screen width), before the scale;
         * same proportions as the editor that writes these files so a layout keeps its look
         * 칸 단위(화면 너비의 1/100) 반너비·반높이, 배율 적용 전. 이 파일을 만드는 편집기와 같은 비율이라
         * 배치 모양이 그대로 유지됨
         */
        public float[] halfSize() {
            switch (type) {
                case D_PAD:
                    return new float[]{7, 7};
                case STICK:
                case TRACKPAD:
                    return new float[]{6, 6};
                case RANGE_BUTTON:
                    return new float[]{bindings.length * 2, 2};
                default:
                    if ("RECT".equals(shape) || "ROUND_RECT".equals(shape)) return new float[]{4, 2};
                    if ("SQUARE".equals(shape)) return new float[]{2.5f, 2.5f};
                    return new float[]{3, 3};
            }
        }
    }

    public final String name;
    public final List<Element> elements;

    private IcpLayout(String name, List<Element> elements) {
        this.name = name;
        this.elements = Collections.unmodifiableList(elements);
    }

    /** Parse .icp text; unknown fields are ignored / .icp 글을 읽음. 모르는 항목은 무시함 */
    public static IcpLayout parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        JSONArray arr = o.getJSONArray("elements");
        List<Element> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.getJSONObject(i);
            JSONArray b = e.optJSONArray("bindings");
            String[] bindings = new String[b != null ? Math.max(1, b.length()) : 1];
            for (int k = 0; k < bindings.length; k++) {
                bindings[k] = b != null && k < b.length() ? b.optString(k, "NONE") : "NONE";
            }
            list.add(new Element(e.optString("type", BUTTON), e.optString("shape", "CIRCLE"), bindings,
                    (float) e.optDouble("scale", 1.0), (float) e.optDouble("x", 0.5),
                    (float) e.optDouble("y", 0.5), e.optBoolean("toggleSwitch", false), e.optString("text", "")));
        }
        return new IcpLayout(o.optString("name", ""), list);
    }

    // ---- binding names -> inputs / 바인딩 이름 -> 입력

    /** What a binding drives / 바인딩이 움직이는 것 */
    public static final int NONE = 0, PAD_BUTTON = 1, PAD_TRIGGER = 2, PAD_AXIS = 3, KEY = 4,
            MOUSE_BUTTON = 5, MOUSE_SCROLL = 6, MOUSE_MOVE = 7;

    /**
     * Resolved binding: kind plus a value
     * - PAD_BUTTON: PadFile button index / PadFile 버튼 번호
     * - PAD_TRIGGER: 0 left, 1 right / 0 왼쪽, 1 오른쪽
     * - PAD_AXIS: axis 0..3 (left x, left y, right x, right y) and sign / 축 0..3(왼 x, 왼 y, 오른 x, 오른 y)과 부호
     * - KEY: X keycode / X 키코드
     * - MOUSE_BUTTON: X button 1 left, 2 middle, 3 right / X 버튼 1 왼쪽, 2 가운데, 3 오른쪽
     * - MOUSE_SCROLL: X button 4 up, 5 down / X 버튼 4 위, 5 아래
     * - MOUSE_MOVE: direction 0 up, 1 right, 2 down, 3 left / 방향 0 위, 1 오른쪽, 2 아래, 3 왼쪽
     * 해석한 바인딩: 종류와 값
     */
    public static final class Target {
        public final int kind, value, sign;
        public final String label;

        Target(int kind, int value, int sign, String label) {
            this.kind = kind;
            this.value = value;
            this.sign = sign;
            this.label = label;
        }
    }

    private static final Target NOTHING = new Target(NONE, 0, 0, "");
    private static final Map<String, Target> TARGETS = new HashMap<>();

    private static void put(String name, int kind, int value, int sign, String label) {
        TARGETS.put(name, new Target(kind, value, sign, label));
    }

    static {
        // Game pad buttons (Xbox names on the labels) / 게임패드 버튼(라벨은 Xbox 이름)
        put("GAMEPAD_BUTTON_A", PAD_BUTTON, PadFile.A, 0, "A");
        put("GAMEPAD_BUTTON_B", PAD_BUTTON, PadFile.B, 0, "B");
        put("GAMEPAD_BUTTON_X", PAD_BUTTON, PadFile.X, 0, "X");
        put("GAMEPAD_BUTTON_Y", PAD_BUTTON, PadFile.Y, 0, "Y");
        put("GAMEPAD_BUTTON_L1", PAD_BUTTON, PadFile.LEFT_SHOULDER, 0, "LB");
        put("GAMEPAD_BUTTON_R1", PAD_BUTTON, PadFile.RIGHT_SHOULDER, 0, "RB");
        put("GAMEPAD_BUTTON_SELECT", PAD_BUTTON, PadFile.BACK, 0, "BACK");
        put("GAMEPAD_BUTTON_START", PAD_BUTTON, PadFile.START, 0, "START");
        put("GAMEPAD_BUTTON_L3", PAD_BUTTON, PadFile.LEFT_STICK, 0, "L3");
        put("GAMEPAD_BUTTON_R3", PAD_BUTTON, PadFile.RIGHT_STICK, 0, "R3");
        put("GAMEPAD_DPAD_UP", PAD_BUTTON, PadFile.DPAD_UP, 0, "↑");
        put("GAMEPAD_DPAD_RIGHT", PAD_BUTTON, PadFile.DPAD_RIGHT, 0, "→");
        put("GAMEPAD_DPAD_DOWN", PAD_BUTTON, PadFile.DPAD_DOWN, 0, "↓");
        put("GAMEPAD_DPAD_LEFT", PAD_BUTTON, PadFile.DPAD_LEFT, 0, "←");
        put("GAMEPAD_BUTTON_L2", PAD_TRIGGER, 0, 0, "LT");
        put("GAMEPAD_BUTTON_R2", PAD_TRIGGER, 1, 0, "RT");
        // Stick directions: axis and sign (y grows downwards) / 스틱 방향: 축과 부호(y는 아래로 커짐)
        put("GAMEPAD_LEFT_THUMB_UP", PAD_AXIS, 1, -1, "LS↑");
        put("GAMEPAD_LEFT_THUMB_RIGHT", PAD_AXIS, 0, 1, "LS→");
        put("GAMEPAD_LEFT_THUMB_DOWN", PAD_AXIS, 1, 1, "LS↓");
        put("GAMEPAD_LEFT_THUMB_LEFT", PAD_AXIS, 0, -1, "LS←");
        put("GAMEPAD_RIGHT_THUMB_UP", PAD_AXIS, 3, -1, "RS↑");
        put("GAMEPAD_RIGHT_THUMB_RIGHT", PAD_AXIS, 2, 1, "RS→");
        put("GAMEPAD_RIGHT_THUMB_DOWN", PAD_AXIS, 3, 1, "RS↓");
        put("GAMEPAD_RIGHT_THUMB_LEFT", PAD_AXIS, 2, -1, "RS←");
        // Mouse / 마우스
        put("MOUSE_LEFT_BUTTON", MOUSE_BUTTON, 1, 0, "LMB");
        put("MOUSE_MIDDLE_BUTTON", MOUSE_BUTTON, 2, 0, "MMB");
        put("MOUSE_RIGHT_BUTTON", MOUSE_BUTTON, 3, 0, "RMB");
        put("MOUSE_SCROLL_UP", MOUSE_SCROLL, 4, 0, "휠↑");
        put("MOUSE_SCROLL_DOWN", MOUSE_SCROLL, 5, 0, "휠↓");
        put("MOUSE_MOVE_UP", MOUSE_MOVE, 0, 0, "M↑");
        put("MOUSE_MOVE_RIGHT", MOUSE_MOVE, 1, 0, "M→");
        put("MOUSE_MOVE_DOWN", MOUSE_MOVE, 2, 0, "M↓");
        put("MOUSE_MOVE_LEFT", MOUSE_MOVE, 3, 0, "M←");
        // Keyboard: X keycodes of the default evdev keymap (Linux key code + 8)
        // 키보드: 기본 evdev 키 배치의 X 키코드(리눅스 키 코드 + 8)
        String row1 = "QWERTYUIOP", row2 = "ASDFGHJKL", row3 = "ZXCVBNM";
        for (int i = 0; i < row1.length(); i++) key("KEY_" + row1.charAt(i), 24 + i, String.valueOf(row1.charAt(i)));
        for (int i = 0; i < row2.length(); i++) key("KEY_" + row2.charAt(i), 38 + i, String.valueOf(row2.charAt(i)));
        for (int i = 0; i < row3.length(); i++) key("KEY_" + row3.charAt(i), 52 + i, String.valueOf(row3.charAt(i)));
        for (int i = 1; i <= 9; i++) key("KEY_" + i, 9 + i, String.valueOf(i));
        key("KEY_0", 19, "0");
        for (int i = 1; i <= 10; i++) key("KEY_F" + i, 66 + i, "F" + i);
        key("KEY_F11", 95, "F11");
        key("KEY_F12", 96, "F12");
        int[] kp = {90, 87, 88, 89, 83, 84, 85, 79, 80, 81};
        for (int i = 0; i <= 9; i++) key("KEY_KP_" + i, kp[i], "NUM" + i);
        Object[][] other = {
                {"KEY_ESC", 9, "ESC"}, {"KEY_MINUS", 20, "-"}, {"KEY_BKSP", 22, "BKSP"}, {"KEY_TAB", 23, "TAB"},
                {"KEY_BRACKET_LEFT", 34, "["}, {"KEY_BRACKET_RIGHT", 35, "]"}, {"KEY_ENTER", 36, "ENTER"},
                {"KEY_CTRL_L", 37, "CTRL"}, {"KEY_CTRL", 37, "CTRL"}, {"KEY_SEMICOLON", 47, ";"},
                {"KEY_APOSTROPHE", 48, "'"}, {"KEY_GRAVE", 49, "`"}, {"KEY_SHIFT_L", 50, "SHIFT"},
                {"KEY_SHIFT", 50, "SHIFT"}, {"KEY_BACKSLASH", 51, "\\"}, {"KEY_COMMA", 59, ","},
                {"KEY_PERIOD", 60, "."}, {"KEY_SLASH", 61, "/"}, {"KEY_SHIFT_R", 62, "SHIFT"},
                {"KEY_ALT_L", 64, "ALT"}, {"KEY_ALT", 64, "ALT"}, {"KEY_SPACE", 65, "SPACE"},
                {"KEY_CAPS_LOCK", 66, "CAPS"}, {"KEY_NUM_LOCK", 77, "NUM"}, {"KEY_KP_ADD", 86, "+"},
                {"KEY_CTRL_R", 105, "CTRL"}, {"KEY_PRTSCN", 107, "PRT"}, {"KEY_ALT_R", 108, "ALT"},
                {"KEY_HOME", 110, "HOME"}, {"KEY_UP", 111, "↑"}, {"KEY_PG_UP", 112, "PGUP"},
                {"KEY_LEFT", 113, "←"}, {"KEY_RIGHT", 114, "→"}, {"KEY_END", 115, "END"},
                {"KEY_DOWN", 116, "↓"}, {"KEY_PG_DOWN", 117, "PGDN"}, {"KEY_INSERT", 118, "INS"},
                {"KEY_DEL", 119, "DEL"},
        };
        for (Object[] k : other) key((String) k[0], (Integer) k[1], (String) k[2]);
    }

    private static void key(String name, int code, String label) {
        put(name, KEY, code, 0, label);
    }

    /** Input for a binding name; unknown names drive nothing / 바인딩 이름의 입력. 모르는 이름은 아무것도 안 함 */
    public static Target target(String name) {
        Target t = name != null ? TARGETS.get(name) : null;
        return t != null ? t : NOTHING;
    }
}
