package app.ezsteam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import app.ezsteam.runtime.PadFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * On-screen touch game pad (Xbox layout) drawn over the game. It feeds the same virtual Xbox 360 pad
 * as a physical controller (PadFile touch source -> native/ezspad -> Wine). Own design, no code from
 * other projects.
 * 게임 위에 그리는 화면 터치 게임패드(Xbox 배치). 실제 컨트롤러와 같은 가상 Xbox 360 패드로 들어감
 * (PadFile 터치 입력원 -> native/ezspad -> Wine). 자체 설계, 다른 프로젝트 코드 없음.
 *
 * - Size: one scale (hand size) for every control and its distance from the screen edge
 *   크기: 모든 조작부의 크기와 화면 가장자리에서의 거리를 함께 바꾸는 배율 하나(손 크기)
 * - Opacity: how strongly the controls are drawn / 투명도: 조작부를 얼마나 진하게 그릴지
 * - Multi-touch: every finger stays with the control it first touched (the face buttons and the
 *   d-pad follow a finger that slides); the pad state is rebuilt from all fingers on each event
 *   멀티 터치: 손가락마다 처음 닿은 조작부를 유지함(ABXY와 십자키는 밀어도 따라감). 이벤트마다 모든
 *   손가락으로 패드 상태를 다시 만듦
 * - Stick double tap (tap, then touch again within 300 ms) holds L3/R3 while the finger stays
 *   스틱 두 번 탭(탭 후 300 ms 안에 다시 닿음)하면 손가락을 떼기 전까지 L3/R3을 누름
 * - While shown, every touch belongs to the pad (touches between controls do nothing); use a
 *   physical mouse or hide the pad for the pointer
 *   보이는 동안 모든 터치는 패드 몫임(조작부 사이 터치는 무시). 포인터는 실제 마우스를 쓰거나 패드를 숨김
 */
public final class TouchPadView extends View {

    /** Kinds of controls / 조작부 종류 */
    private static final int STICK = 0, DPAD = 1, FACE = 2, BUTTON = 3, TRIGGER = 4;

    /** One control: kind, centre and size in dp before scaling, and which pad input it drives
     *  조작부 하나: 종류, 배율 적용 전 dp 중심·크기, 움직이는 패드 입력 */
    private static final class Control {
        final int kind;
        final String label;
        /** Anchor: centre measured from the left (x >= 0) or right (x < 0) edge, and from the
         *  bottom (y >= 0) or top (y < 0) edge, in dp
         *  기준점: 왼쪽(x >= 0) 또는 오른쪽(x < 0) 가장자리, 아래(y >= 0) 또는 위(y < 0) 가장자리로부터
         *  잰 중심, dp 단위 */
        final float ax, ay, rDp;
        /** Pad input: button index, stick number (0 left, 1 right) or trigger (0 left, 1 right)
         *  패드 입력: 버튼 번호, 스틱 번호(0 왼쪽, 1 오른쪽), 트리거(0 왼쪽, 1 오른쪽) */
        final int target;
        float cx, cy, r;  // on screen, px / 화면 위치, px
        boolean drawnPressed;

        Control(int kind, String label, float ax, float ay, float rDp, int target) {
            this.kind = kind;
            this.label = label;
            this.ax = ax;
            this.ay = ay;
            this.rDp = rDp;
            this.target = target;
        }
    }

    /** What one finger is doing / 손가락 하나가 하는 일 */
    private static final class Finger {
        Control control;
        float x, y;
        boolean stickClick;  // L3/R3 held by a double tap / 두 번 탭으로 누른 L3/R3
    }

    private final List<Control> controls = new ArrayList<>();
    private final Map<Integer, Finger> fingers = new HashMap<>();
    /** Last time each stick was released, for the double tap / 두 번 탭용 스틱별 마지막 뗀 시각 */
    private final long[] stickUpAt = {0, 0};
    private final long[] stickDownAt = {0, 0};
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;

    private PadFile pad;
    private float scale = 1f;
    private float opacity = 0.45f;

    public TouchPadView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        // Triggers/shoulders start below the app's button row (about 48 dp) and the corner [≡]
        // 트리거·숄더는 앱 버튼 줄(약 48 dp)과 모서리 [≡] 아래에서 시작함
        // Left side / 왼쪽
        controls.add(new Control(STICK, "", 120, 130, 62, 0));
        controls.add(new Control(DPAD, "", 250, 70, 50, 0));
        controls.add(new Control(TRIGGER, "LT", 70, -100, 34, 0));
        controls.add(new Control(BUTTON, "LB", 70, -175, 34, PadFile.LEFT_SHOULDER));
        // Right side / 오른쪽
        controls.add(new Control(FACE, "A", -120, 70, 30, PadFile.A));
        controls.add(new Control(FACE, "B", -60, 130, 30, PadFile.B));
        controls.add(new Control(FACE, "X", -180, 130, 30, PadFile.X));
        controls.add(new Control(FACE, "Y", -120, 190, 30, PadFile.Y));
        controls.add(new Control(STICK, "", -300, 90, 55, 1));
        controls.add(new Control(TRIGGER, "RT", -70, -100, 34, 1));
        controls.add(new Control(BUTTON, "RB", -70, -175, 34, PadFile.RIGHT_SHOULDER));
        // Middle row (placed from the centre in onLayout) / 가운데 줄(onLayout에서 가운데 기준 배치)
        controls.add(new Control(BUTTON, "BACK", 0, 40, 26, PadFile.BACK));
        controls.add(new Control(BUTTON, "≡", 0, 40, 26, PadFile.START));
        controls.add(new Control(BUTTON, "⌂", 0, 40, 22, PadFile.GUIDE));
    }

    /** Pad to drive (null: draw only) / 움직일 패드(null이면 그리기만) */
    public void setPad(PadFile pad) {
        this.pad = pad;
    }

    /** Hand size 0.5..1.8 and opacity 0.15..0.9 / 손 크기 0.5..1.8, 투명도 0.15..0.9 */
    public void setLook(float scale, float opacity) {
        this.scale = Math.max(0.5f, Math.min(1.8f, scale));
        this.opacity = Math.max(0.15f, Math.min(0.9f, opacity));
        place();
        invalidate();
    }

    /** Release everything (hidden, focus lost) / 모두 뗌(숨김, 포커스 잃음) */
    public void releaseAll() {
        fingers.clear();
        apply();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        place();
    }

    /** Screen position of every control from its anchor, scale and the view size
     *  기준점, 배율, 뷰 크기로 모든 조작부의 화면 위치를 정함 */
    private void place() {
        float k = density * scale;
        int w = getWidth(), h = getHeight();
        int middle = 0;
        for (Control c : controls) {
            c.r = c.rDp * k;
            if (c.ax == 0 && c.kind == BUTTON) {
                // BACK, START, GUIDE: a row around the centre / BACK, START, GUIDE: 가운데 주변 한 줄
                float dx = (middle == 0 ? -80 : middle == 1 ? 80 : 0) * k;
                c.cx = w / 2f + dx;
                c.cy = h - c.ay * k;
                middle++;
                continue;
            }
            c.cx = c.ax >= 0 ? c.ax * k : w + c.ax * k;
            c.cy = c.ay >= 0 ? h - c.ay * k : -c.ay * k;
        }
    }

    // ---- touch / 터치

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                Finger f = new Finger();
                f.x = e.getX(idx);
                f.y = e.getY(idx);
                f.control = hit(f.x, f.y);
                if (f.control != null && f.control.kind == STICK) {
                    int s = f.control.target;
                    long now = SystemClock.uptimeMillis();
                    // Double tap: the last touch of this stick was a short tap ending < 300 ms ago
                    // 두 번 탭: 이 스틱의 마지막 터치가 300 ms 안에 끝난 짧은 탭
                    f.stickClick = now - stickUpAt[s] < 300 && stickUpAt[s] - stickDownAt[s] < 250;
                    stickDownAt[s] = now;
                }
                fingers.put(e.getPointerId(idx), f);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    Finger f = fingers.get(e.getPointerId(i));
                    if (f == null) continue;
                    f.x = e.getX(i);
                    f.y = e.getY(i);
                    // Face buttons: the finger may slide to another face button
                    // ABXY: 손가락을 밀어 다른 버튼으로 옮길 수 있음
                    if (f.control != null && f.control.kind == FACE) {
                        Control c = hit(f.x, f.y);
                        if (c != null && c.kind == FACE) f.control = c;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                Finger f = fingers.remove(e.getPointerId(idx));
                if (f != null && f.control != null && f.control.kind == STICK) {
                    stickUpAt[f.control.target] = SystemClock.uptimeMillis();
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                fingers.clear();
                break;
            default:
                break;
        }
        apply();
        return true;
    }

    /** Control under a point; sticks and the d-pad catch a wider ring
     *  점 아래의 조작부. 스틱과 십자키는 더 넓은 범위를 받음 */
    private Control hit(float x, float y) {
        Control best = null;
        float bestD = Float.MAX_VALUE;
        for (Control c : controls) {
            float reach = c.r * (c.kind == STICK || c.kind == DPAD ? 1.6f : 1.25f);
            float dx = x - c.cx, dy = y - c.cy;
            float d = dx * dx + dy * dy;
            if (d <= reach * reach && d < bestD) {
                best = c;
                bestD = d;
            }
        }
        return best;
    }

    /** Rebuild the touch source from all fingers and write the pad file
     *  모든 손가락으로 터치 입력원을 다시 만들고 패드 파일에 씀 */
    private void apply() {
        for (Control c : controls) c.drawnPressed = false;
        PadFile p = pad;
        PadFile.State st = p != null ? p.touch : null;
        if (st != null) st.clear();
        float lt = 0f, rt = 0f;
        for (Finger f : fingers.values()) {
            Control c = f.control;
            if (c == null) continue;
            c.drawnPressed = true;
            if (st == null) continue;
            float dx = (f.x - c.cx) / c.r, dy = (f.y - c.cy) / c.r;
            switch (c.kind) {
                case STICK: {
                    float len = (float) Math.sqrt(dx * dx + dy * dy);
                    if (len > 1f) {
                        dx /= len;
                        dy /= len;
                    }
                    st.setAxis(c.target * 2, dx);
                    st.setAxis(c.target * 2 + 1, dy);
                    if (f.stickClick) st.setButton(c.target == 0 ? PadFile.LEFT_STICK : PadFile.RIGHT_STICK, true);
                    break;
                }
                case DPAD:
                    // 8 directions; the centre third is neutral / 8방향, 가운데 1/3은 중립
                    if (dx * dx + dy * dy > 0.11f) {
                        double a = Math.toDegrees(Math.atan2(dy, dx));
                        if (a > -67.5 && a < 67.5) st.setButton(PadFile.DPAD_RIGHT, true);
                        if (a > 22.5 && a < 157.5) st.setButton(PadFile.DPAD_DOWN, true);
                        if (a > 112.5 || a < -112.5) st.setButton(PadFile.DPAD_LEFT, true);
                        if (a > -157.5 && a < -22.5) st.setButton(PadFile.DPAD_UP, true);
                    }
                    break;
                case TRIGGER:
                    if (c.target == 0) lt = 1f;
                    else rt = 1f;
                    break;
                default:
                    st.setButton(c.target, true);
                    break;
            }
        }
        if (st != null) {
            st.setTriggers(lt, rt);
            p.write();
        }
        invalidate();
    }

    // ---- drawing / 그리기

    @Override
    protected void onDraw(Canvas canvas) {
        int a = Math.round(opacity * 255);
        line.setStrokeWidth(2f * density);
        for (Control c : controls) {
            int base = c.drawnPressed ? Math.min(255, a + 70) : a;
            fill.setColor(Color.argb(c.drawnPressed ? base / 2 : base / 4, 255, 255, 255));
            line.setColor(Color.argb(base, 255, 255, 255));
            text.setColor(Color.argb(Math.min(255, base + 40), 255, 255, 255));
            text.setTextSize(c.r * (c.label.length() > 2 ? 0.45f : 0.8f));
            switch (c.kind) {
                case STICK: {
                    canvas.drawCircle(c.cx, c.cy, c.r, fill);
                    canvas.drawCircle(c.cx, c.cy, c.r, line);
                    // Knob follows the finger / 손잡이가 손가락을 따라감
                    float kx = c.cx, ky = c.cy;
                    for (Finger f : fingers.values()) {
                        if (f.control != c) continue;
                        float dx = f.x - c.cx, dy = f.y - c.cy;
                        float len = (float) Math.sqrt(dx * dx + dy * dy);
                        float max = c.r * 0.6f;
                        if (len > max) {
                            dx = dx / len * max;
                            dy = dy / len * max;
                        }
                        kx += dx;
                        ky += dy;
                    }
                    canvas.drawCircle(kx, ky, c.r * 0.42f, fill);
                    canvas.drawCircle(kx, ky, c.r * 0.42f, line);
                    break;
                }
                case DPAD: {
                    float t = c.r * 0.36f;
                    rect.set(c.cx - c.r, c.cy - t, c.cx + c.r, c.cy + t);
                    canvas.drawRoundRect(rect, t * 0.4f, t * 0.4f, fill);
                    canvas.drawRoundRect(rect, t * 0.4f, t * 0.4f, line);
                    rect.set(c.cx - t, c.cy - c.r, c.cx + t, c.cy + c.r);
                    canvas.drawRoundRect(rect, t * 0.4f, t * 0.4f, fill);
                    canvas.drawRoundRect(rect, t * 0.4f, t * 0.4f, line);
                    break;
                }
                case BUTTON:
                case TRIGGER: {
                    rect.set(c.cx - c.r * 1.3f, c.cy - c.r * 0.7f, c.cx + c.r * 1.3f, c.cy + c.r * 0.7f);
                    canvas.drawRoundRect(rect, c.r * 0.35f, c.r * 0.35f, fill);
                    canvas.drawRoundRect(rect, c.r * 0.35f, c.r * 0.35f, line);
                    canvas.drawText(c.label, c.cx, c.cy - (text.ascent() + text.descent()) / 2, text);
                    break;
                }
                default: {
                    canvas.drawCircle(c.cx, c.cy, c.r, fill);
                    canvas.drawCircle(c.cx, c.cy, c.r, line);
                    canvas.drawText(c.label, c.cx, c.cy - (text.ascent() + text.descent()) / 2, text);
                    break;
                }
            }
        }
    }
}
