package app.ezsteam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import app.ezsteam.runtime.IcpLayout;
import app.ezsteam.runtime.PadFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * On-screen touch controls laid out from an input controls profile (.icp, IcpLayout): buttons,
 * d-pads and sticks at the positions and sizes the file gives, so a layout made in EZlator looks
 * and works the same here. Game pad bindings feed the virtual Xbox 360 pad (PadFile touch source),
 * keyboard and mouse bindings go to the X server through Input.
 * 입력 컨트롤 프로필(.icp, IcpLayout)대로 배치하는 화면 터치 조작: 파일이 정한 위치·크기의 버튼, 십자키,
 * 스틱. 그래서 EZlator에서 만든 배치가 여기서도 같은 모양·동작이 됨. 게임패드 바인딩은 가상 Xbox 360
 * 패드(PadFile 터치 입력원)로, 키보드·마우스 바인딩은 Input을 거쳐 X 서버로 감
 *
 * - Every finger stays with the element it first touched / 손가락마다 처음 닿은 요소를 유지함
 * - A touch that starts away from every element passes through to the screen below
 *   요소 밖에서 시작한 터치는 아래 화면으로 넘어감
 * - Size: one scale on top of each element's own scale; opacity: how strongly they are drawn
 *   크기: 요소마다의 배율 위에 곱하는 배율 하나, 진하기: 얼마나 진하게 그릴지
 * - Spacing: spreads each side's controls from that side's centre
 *   간격: 각 쪽 조작부를 그쪽 중심에서 벌림
 */
public final class TouchPadView extends View {

    /** Keyboard and mouse output (X server) / 키보드·마우스 출력(X 서버) */
    public interface Input {
        void key(int xKeycode, boolean down);

        void mouseButton(int xButton, boolean down);

        void mouseMove(int dx, int dy);
    }

    /** Stick dead zone (fraction of the radius) / 스틱 무감 범위(반지름 비율) */
    private static final float STICK_DEAD = 0.15f;
    /** Push needed before a d-pad or stick direction counts as pressed / 십자키·스틱 방향이 눌림이 되는 기울기 */
    private static final float DIRECTION_ON = 0.3f;
    /** Pointer speed for mouse-move bindings, px per frame at full push / 마우스 이동 바인딩 속도, 끝까지 밀었을 때 프레임당 px */
    private static final float MOUSE_SPEED = 14f;

    /** One element on screen / 화면의 요소 하나 */
    private static final class Item {
        final IcpLayout.Element e;
        final IcpLayout.Target[] targets;
        final String label;
        final RectF box = new RectF();
        float cx, cy, unit;
        boolean toggledOn;
        /** Finger holding it and its push (-1..1, sticks and d-pads) / 잡은 손가락과 기울기(-1..1, 스틱·십자키) */
        int pointer = -1;
        float dx, dy;

        Item(IcpLayout.Element e) {
            this.e = e;
            targets = new IcpLayout.Target[e.bindings.length];
            String first = "";
            for (int i = 0; i < targets.length; i++) {
                targets[i] = IcpLayout.target(e.bindings[i]);
                if (first.isEmpty()) first = targets[i].label;
            }
            label = e.text != null && !e.text.isEmpty() ? e.text : first;
        }

        boolean held() {
            return pointer >= 0 || toggledOn;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private PadFile pad;
    private Input input;
    private float scale = 1f;
    private float opacity = 0.45f;
    /** Spacing: distance of each control from the centre of its side, 0.8..1.6 (1 = as in the file)
     *  간격: 각 조작부가 자기 쪽(왼쪽·오른쪽) 중심에서 떨어진 거리, 0.8..1.6(1 = 파일 그대로) */
    private float gap = 1f;
    /** Keys and mouse buttons currently held by the pad / 패드가 지금 누르고 있는 키·마우스 버튼 */
    private final Set<Integer> keysDown = new HashSet<>(), buttonsDown = new HashSet<>();
    private final Map<Item, Boolean> scrollSent = new HashMap<>();
    private float moveX, moveY;
    private boolean moving;

    public TouchPadView(Context ctx) {
        super(ctx);
        line.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
    }

    /** Pad to drive (null: draw only) / 움직일 패드(null이면 그리기만) */
    public void setPad(PadFile pad) {
        this.pad = pad;
    }

    /** Keyboard and mouse output / 키보드·마우스 출력 */
    public void setInput(Input input) {
        this.input = input;
    }

    /** Layout to show; replaces the current one / 보여 줄 배치. 지금 배치를 바꿈 */
    public void setLayout(IcpLayout layout) {
        releaseAll();
        items.clear();
        for (IcpLayout.Element e : layout.elements) {
            // Range buttons (scrolling key strips) are not supported yet / 범위 버튼(스크롤 키 줄)은 아직 지원 안 함
            if (IcpLayout.RANGE_BUTTON.equals(e.type)) continue;
            items.add(new Item(e));
        }
        place();
        invalidate();
    }

    /** Size 0.5..1.8 and opacity 0.15..0.9 / 크기 0.5..1.8, 진하기 0.15..0.9 */
    public void setLook(float scale, float opacity) {
        this.scale = Math.max(0.5f, Math.min(1.8f, scale));
        this.opacity = Math.max(0.15f, Math.min(0.9f, opacity));
        place();
        invalidate();
    }

    /**
     * Spacing 0.8..1.6: spreads the controls of each side (left / right half) away from that side's
     * centre, so bigger controls do not run into each other (user report 2026-10-22: with a larger
     * size the controls had to be farther apart and the size alone could not do it)
     * 간격 0.8..1.6: 각 쪽(왼쪽·오른쪽 절반)의 조작부를 그쪽 중심에서 벌림. 크기를 키운 조작부끼리 겹치지
     * 않게 함(사용자 제보 2026-10-22: 크기를 키우면 조작부가 더 벌어져야 했는데 크기만으로는 안 됐음)
     */
    public void setGap(float gap) {
        this.gap = Math.max(0.8f, Math.min(1.6f, gap));
        place();
        invalidate();
    }

    /** Release everything (hidden, focus lost) / 모두 뗌(숨김, 포커스 잃음) */
    public void releaseAll() {
        for (Item it : items) {
            it.pointer = -1;
            it.toggledOn = false;
            it.dx = it.dy = 0;
        }
        apply();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        place();
    }

    /** Screen box of every element: centre from x/y, size in 1/100 of the width
     *  모든 요소의 화면 상자: 중심은 x/y, 크기는 너비의 1/100 단위 */
    private void place() {
        int w = getWidth(), h = getHeight();
        float grid = w / 100f;
        // Centre of each side (average position of its controls) for the spacing
        // 간격용 각 쪽의 중심(그쪽 조작부의 평균 위치)
        float[] sx = new float[2], sy = new float[2];
        int[] sn = new int[2];
        for (Item it : items) {
            int side = it.e.x < 0.5f ? 0 : 1;
            sx[side] += it.e.x;
            sy[side] += it.e.y;
            sn[side]++;
        }
        for (int i = 0; i < 2; i++) {
            if (sn[i] > 0) {
                sx[i] /= sn[i];
                sy[i] /= sn[i];
            }
        }
        for (Item it : items) {
            float[] half = it.e.halfSize();
            it.unit = grid * it.e.scale * scale;
            int side = it.e.x < 0.5f ? 0 : 1;
            float hw = half[0] * it.unit, hh = half[1] * it.unit;
            float x = (sx[side] + (it.e.x - sx[side]) * gap) * w;
            float y = (sy[side] + (it.e.y - sy[side]) * gap) * h;
            // Keep the whole control on screen / 조작부 전체가 화면 안에 있게 함
            it.cx = Math.max(Math.min(hw, w / 2f), Math.min(w - Math.min(hw, w / 2f), x));
            it.cy = Math.max(Math.min(hh, h / 2f), Math.min(h - Math.min(hh, h / 2f), y));
            it.box.set(it.cx - hw, it.cy - hh, it.cx + hw, it.cy + hh);
        }
    }

    // ---- touch / 터치

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        int idx = ev.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                Item it = hit(ev.getX(idx), ev.getY(idx));
                // First finger away from every element: Android hands the gesture to the view below
                // 첫 손가락이 요소 밖: 안드로이드가 이 동작을 아래 뷰에 넘김
                if (action == MotionEvent.ACTION_DOWN && it == null) {
                    releaseAll();
                    return false;
                }
                if (it != null && it.pointer < 0) {
                    if (it.e.toggle) it.toggledOn = !it.toggledOn;
                    it.pointer = ev.getPointerId(idx);
                    track(it, ev.getX(idx), ev.getY(idx));
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < ev.getPointerCount(); i++) {
                    int id = ev.getPointerId(i);
                    for (Item it : items) if (it.pointer == id) track(it, ev.getX(i), ev.getY(i));
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int id = ev.getPointerId(idx);
                for (Item it : items) {
                    if (it.pointer != id) continue;
                    it.pointer = -1;
                    it.dx = it.dy = 0;
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                for (Item it : items) {
                    it.pointer = -1;
                    it.dx = it.dy = 0;
                }
                break;
            default:
                break;
        }
        apply();
        return true;
    }

    /** Push of a stick, d-pad or trackpad from the finger position / 손가락 위치로 스틱·십자키·트랙패드 기울기 */
    private void track(Item it, float x, float y) {
        if (IcpLayout.BUTTON.equals(it.e.type)) return;
        float r = it.box.width() / 2f;
        float dx = (x - it.cx) / r, dy = (y - it.cy) / r;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > 1f) {
            dx /= len;
            dy /= len;
        }
        it.dx = dx;
        it.dy = dy;
    }

    /** Topmost element whose box holds the point / 점이 상자 안에 드는 맨 위 요소 */
    private Item hit(float x, float y) {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).box.contains(x, y)) return items.get(i);
        }
        return null;
    }

    /**
     * Rebuild every output from all elements: pad state (written once), keys and mouse buttons
     * (only changes are sent), mouse movement (a repeating step while held)
     * 모든 요소로 출력을 다시 만듦: 패드 상태(한 번에 씀), 키·마우스 버튼(바뀐 것만 보냄), 마우스 이동(누르는
     * 동안 반복)
     */
    private void apply() {
        PadFile p = pad;
        PadFile.State st = p != null ? p.touch : null;
        if (st != null) st.clear();
        float[] axes = new float[4];
        float lt = 0f, rt = 0f;
        Set<Integer> keys = new HashSet<>(), buttons = new HashSet<>();
        float mx = 0f, my = 0f;
        for (Item it : items) {
            if (!it.held()) {
                scrollSent.remove(it);
                continue;
            }
            boolean directional = !IcpLayout.BUTTON.equals(it.e.type);
            float sx = it.dx, sy = it.dy;
            if (IcpLayout.STICK.equals(it.e.type) && sx * sx + sy * sy < STICK_DEAD * STICK_DEAD) sx = sy = 0;
            for (int i = 0; i < it.targets.length; i++) {
                IcpLayout.Target t = it.targets[i];
                // Strength of this binding: direction i of a stick/d-pad (0 up, 1 right, 2 down,
                // 3 left), or 1 for a held button
                // 이 바인딩의 세기: 스틱·십자키의 i번 방향(0 위, 1 오른쪽, 2 아래, 3 왼쪽), 버튼은 누르면 1
                float s = 1f;
                if (directional) {
                    s = i == 0 ? -sy : i == 1 ? sx : i == 2 ? sy : -sx;
                    if (s < 0) s = 0;
                    // D-pads are digital / 십자키는 켜고 끄기만 함
                    if (IcpLayout.D_PAD.equals(it.e.type)) s = s > DIRECTION_ON ? 1f : 0f;
                }
                if (s <= 0f) continue;
                boolean on = s > DIRECTION_ON || !directional;
                switch (t.kind) {
                    case IcpLayout.PAD_BUTTON:
                        if (st != null && on) st.setButton(t.value, true);
                        break;
                    case IcpLayout.PAD_TRIGGER:
                        if (t.value == 0) lt = Math.max(lt, s);
                        else rt = Math.max(rt, s);
                        break;
                    case IcpLayout.PAD_AXIS:
                        axes[t.value] += t.sign * s;
                        break;
                    case IcpLayout.KEY:
                        if (on) keys.add(t.value);
                        break;
                    case IcpLayout.MOUSE_BUTTON:
                        if (on) buttons.add(t.value);
                        break;
                    case IcpLayout.MOUSE_SCROLL:
                        // One wheel click per press / 누를 때마다 휠 한 칸
                        if (on && scrollSent.get(it) == null && input != null) {
                            input.mouseButton(t.value, true);
                            input.mouseButton(t.value, false);
                            scrollSent.put(it, Boolean.TRUE);
                        }
                        break;
                    case IcpLayout.MOUSE_MOVE:
                        if (t.value == 0) my -= s;
                        else if (t.value == 1) mx += s;
                        else if (t.value == 2) my += s;
                        else mx -= s;
                        break;
                    default:
                        break;
                }
            }
            // Trackpads move the mouse by their push / 트랙패드는 기울기만큼 마우스를 움직임
            if (IcpLayout.TRACKPAD.equals(it.e.type)) {
                mx += sx;
                my += sy;
            }
        }
        if (st != null) {
            for (int a = 0; a < 4; a++) st.setAxis(a, Math.max(-1f, Math.min(1f, axes[a])));
            st.setTriggers(Math.min(1f, lt), Math.min(1f, rt));
            p.write();
        }
        Input in = input;
        if (in != null) {
            for (Integer k : keysDown) if (!keys.contains(k)) in.key(k, false);
            for (Integer k : keys) if (!keysDown.contains(k)) in.key(k, true);
            for (Integer b : buttonsDown) if (!buttons.contains(b)) in.mouseButton(b, false);
            for (Integer b : buttons) if (!buttonsDown.contains(b)) in.mouseButton(b, true);
        }
        keysDown.clear();
        keysDown.addAll(keys);
        buttonsDown.clear();
        buttonsDown.addAll(buttons);
        moveX = mx;
        moveY = my;
        if ((mx != 0 || my != 0) && !moving) {
            moving = true;
            ui.post(mover);
        }
        invalidate();
    }

    /** Repeating mouse step while a mouse-move binding is held / 마우스 이동 바인딩을 누르는 동안 반복하는 이동 */
    private final Runnable mover = new Runnable() {
        @Override
        public void run() {
            Input in = input;
            if (moveX == 0 && moveY == 0) {
                moving = false;
                return;
            }
            if (in != null) in.mouseMove(Math.round(moveX * MOUSE_SPEED), Math.round(moveY * MOUSE_SPEED));
            ui.postDelayed(this, 16);
        }
    };

    // ---- drawing / 그리기

    @Override
    protected void onDraw(Canvas canvas) {
        int a = Math.round(opacity * 255);
        for (Item it : items) {
            boolean pressed = it.held();
            int base = pressed ? Math.min(255, a + 70) : a;
            float stroke = Math.max(1f, it.unit * 0.25f);
            line.setStrokeWidth(stroke);
            fill.setColor(Color.argb(pressed ? base / 2 : base / 5, 255, 255, 255));
            line.setColor(Color.argb(base, 255, 255, 255));
            text.setColor(Color.argb(Math.min(255, base + 40), 255, 255, 255));
            RectF b = it.box;
            switch (it.e.type) {
                case IcpLayout.STICK: {
                    float r = b.width() / 2f;
                    canvas.drawCircle(it.cx, it.cy, r, fill);
                    canvas.drawCircle(it.cx, it.cy, r, line);
                    // Knob follows the push / 손잡이가 기울기를 따라감
                    float knob = it.unit * 3.5f;
                    float kx = it.cx + it.dx * (r - knob * 0.5f), ky = it.cy + it.dy * (r - knob * 0.5f);
                    canvas.drawCircle(kx, ky, knob, fill);
                    canvas.drawCircle(kx, ky, knob, line);
                    break;
                }
                case IcpLayout.D_PAD:
                    drawDpad(canvas, it);
                    break;
                case IcpLayout.TRACKPAD: {
                    float r = b.height() * 0.15f;
                    canvas.drawRoundRect(b, r, r, fill);
                    canvas.drawRoundRect(b, r, r, line);
                    break;
                }
                default: {
                    if ("RECT".equals(it.e.shape)) {
                        canvas.drawRect(b, fill);
                        canvas.drawRect(b, line);
                    } else if ("ROUND_RECT".equals(it.e.shape)) {
                        float r = b.height() / 2f;
                        canvas.drawRoundRect(b, r, r, fill);
                        canvas.drawRoundRect(b, r, r, line);
                    } else if ("SQUARE".equals(it.e.shape)) {
                        float r = it.unit * 0.75f;
                        canvas.drawRoundRect(b, r, r, fill);
                        canvas.drawRoundRect(b, r, r, line);
                    } else {
                        canvas.drawCircle(it.cx, it.cy, b.width() / 2f, fill);
                        canvas.drawCircle(it.cx, it.cy, b.width() / 2f, line);
                    }
                    if (!it.label.isEmpty()) {
                        float size = it.unit * 2f;
                        text.setTextSize(size);
                        float wText = text.measureText(it.label);
                        float room = b.width() - stroke * 4;
                        if (wText > room && wText > 0) text.setTextSize(size * room / wText);
                        canvas.drawText(it.label, it.cx, it.cy - (text.ascent() + text.descent()) / 2, text);
                    }
                    break;
                }
            }
        }
    }

    /** Cross with the pressed arms filled / 눌린 방향을 채운 십자 */
    /** Outline of the cross and the arrows, reused every frame / 십자 외곽선과 화살표, 매 프레임 재사용 */
    private final android.graphics.Path cross = new android.graphics.Path(), arrow = new android.graphics.Path();

    /**
     * D-pad drawn as one cross like a real pad (user request 2026-10-22: the four separate arms with
     * text did not look like a D-pad): each arm lights up while pushed, an arrow points outwards.
     * 실제 패드처럼 하나의 십자로 그림(사용자 요청 2026-10-22: 텍스트가 있는 따로 떨어진 네 팔은 십자키처럼
     * 보이지 않았음). 누른 방향의 팔이 밝아지고, 화살표가 바깥을 가리킴
     */
    private void drawDpad(Canvas canvas, Item it) {
        float reach = it.box.width() / 2f;   // centre to arm end / 중심에서 팔 끝까지
        float arm = reach * 0.36f;           // half width of an arm / 팔 반폭
        float cx = it.cx, cy = it.cy;
        int base = Math.round(opacity * 255);
        // Arms in target order: up, right, down, left / 바인딩 순서대로의 팔: 위, 오른쪽, 아래, 왼쪽
        float[][] arms = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int i = 0; i < 4; i++) {
            float ax = arms[i][0], ay = arms[i][1];
            float s = i == 0 ? -it.dy : i == 1 ? it.dx : i == 2 ? it.dy : -it.dx;
            boolean on = it.pointer >= 0 && s > DIRECTION_ON;
            if (ax == 0) tmp.set(cx - arm, ay < 0 ? cy - reach : cy + arm, cx + arm, ay < 0 ? cy - arm : cy + reach);
            else tmp.set(ax < 0 ? cx - reach : cx + arm, cy - arm, ax < 0 ? cx - arm : cx + reach, cy + arm);
            fill.setColor(Color.argb(on ? Math.min(255, base + 70) / 2 : base / 5, 255, 255, 255));
            canvas.drawRect(tmp, fill);
            // Arrow from 50 % to 85 % of the arm, pointing outwards / 팔의 50~85 % 위치에 바깥을 향한 화살표
            float tip = reach * 0.85f, back = reach * 0.5f, half = arm * 0.55f;
            arrow.reset();
            arrow.moveTo(cx + ax * tip, cy + ay * tip);
            arrow.lineTo(cx + ax * back - ay * half, cy + ay * back + ax * half);
            arrow.lineTo(cx + ax * back + ay * half, cy + ay * back - ax * half);
            arrow.close();
            canvas.drawPath(arrow, text);
        }
        // Centre square / 가운데 사각형
        fill.setColor(Color.argb(base / 5, 255, 255, 255));
        tmp.set(cx - arm, cy - arm, cx + arm, cy + arm);
        canvas.drawRect(tmp, fill);
        // One outline around the whole cross (12 corners) / 십자 전체를 두르는 외곽선 하나(꼭짓점 12개)
        cross.reset();
        cross.moveTo(cx - arm, cy - reach);
        cross.lineTo(cx + arm, cy - reach);
        cross.lineTo(cx + arm, cy - arm);
        cross.lineTo(cx + reach, cy - arm);
        cross.lineTo(cx + reach, cy + arm);
        cross.lineTo(cx + arm, cy + arm);
        cross.lineTo(cx + arm, cy + reach);
        cross.lineTo(cx - arm, cy + reach);
        cross.lineTo(cx - arm, cy + arm);
        cross.lineTo(cx - reach, cy + arm);
        cross.lineTo(cx - reach, cy - arm);
        cross.lineTo(cx - arm, cy - arm);
        cross.close();
        canvas.drawPath(cross, line);
    }
}
