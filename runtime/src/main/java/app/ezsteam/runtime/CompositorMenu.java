package app.ezsteam.runtime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The app's own menu over the game, opened with HOME (the compositor's menu, in the place a
 * gamescope-style compositor has its own). Laid out as a side panel: it slides in from the right
 * edge over the dimmed game, with a vertical tab strip at the right edge and the rows of the chosen
 * tab beside it. Own design and drawing (Android views and canvas icons, no images or code from
 * other projects). Drawn above the game view, so it needs no X window and nothing from the game.
 * Pad: up/down move, LB/RB change tab anywhere, A choose, left/right change a value; on the tab
 * strip left or A goes into the rows, from the rows right (on a row without a value) or B goes back
 * to the strip, B on the strip closes. Touch works on tabs and rows. The host gives every text shown
 * (this module has no string resources, so the host's app translates them).
 * HOME으로 여는 게임 위의 앱 자체 메뉴(gamescope 같은 컴포지터가 자기 메뉴를 두는 자리의 컴포지터 메뉴).
 * 옆 판 형태: 어두워진 게임 위로 오른쪽 가장자리에서 밀려 나오며, 오른쪽 끝에 세로 탭 줄, 그 옆에 고른 탭의 줄을 둠.
 * 자체 설계·그리기(안드로이드 뷰와 캔버스 아이콘, 다른 프로젝트의 이미지나 코드 없음). 게임 뷰 위에 그리므로 X 창도
 * 게임 쪽 도움도 필요 없음. 패드: 위아래 이동, LB/RB 어디서나 탭 바꾸기, A 고르기, 좌우 값 바꾸기. 탭 줄에서
 * 왼쪽이나 A는 줄 목록으로 들어가고, 줄 목록에서 오른쪽(값 없는 줄)이나 B는 탭 줄로 돌아가며, 탭 줄에서 B는 닫음.
 * 터치는 탭과 줄 모두 됨. 보이는 모든 글은 호스트가 줌(이 모듈은 문자열 리소스가 없어 호스트 앱이 번역함)
 */
public final class CompositorMenu extends FrameLayout {

    /** One row: label, current value (null = action row), what left / right / A do
     *  줄 하나: 이름, 지금 값(null = 동작 줄), 왼쪽 / 오른쪽 / A가 하는 일 */
    public static final class Item {
        final String label;
        final Supplier<String> value;
        final Runnable left, right, select;

        public Item(String label, Supplier<String> value, Runnable left, Runnable right, Runnable select) {
            this.label = label;
            this.value = value;
            this.left = left;
            this.right = right;
            this.select = select;
        }

        /** A row whose left / right change something / 좌우로 무언가를 바꾸는 줄 */
        boolean hasValue() {
            return left != null || right != null;
        }
    }

    // Tab icons drawn by this class / 이 클래스가 그리는 탭 아이콘
    public static final int ICON_QUICK = 0, ICON_DISPLAY = 1, ICON_INPUT = 2, ICON_DIAG = 3;

    /** One tab of the strip: icon, label, rows / 탭 줄의 탭 하나: 아이콘, 이름, 줄 */
    public static final class Tab {
        final int icon;
        final String label;
        final List<Item> items;

        public Tab(int icon, String label, List<Item> items) {
            this.icon = icon;
            this.label = label;
            this.items = new ArrayList<>(items);
        }
    }

    /** Called when the menu opens or closes / 메뉴가 열리거나 닫힐 때 부름 */
    public interface Listener {
        void shown(boolean shown);
    }

    // SDL button numbers (PadFile order) / SDL 버튼 번호(PadFile 순서)
    private static final int A = 0, B = 1, LB = 9, RB = 10, DPAD_UP = 11, DPAD_DOWN = 12, DPAD_LEFT = 13, DPAD_RIGHT = 14;

    // Colours of the panel / 판 색
    private static final int C_PANEL = 0xF5161A20, C_STRIP = 0xFF0E1115, C_ROW_FOCUS = 0xFF2C3746,
            C_ROW_FOCUS_DIM = 0xFF222A35, C_ACCENT = 0xFF1A9FFF, C_TEXT = 0xFFF2F4F7, C_TEXT_DIM = 0xFF8C98A6;

    private final LinearLayout panel, strip, list;
    private final ScrollView scroll;
    private final TextView header, hintView;
    private final List<View> tabViews = new ArrayList<>();
    private final List<IconView> tabIcons = new ArrayList<>();
    private final List<TextView> tabLabels = new ArrayList<>();
    private final List<LinearLayout> rows = new ArrayList<>();
    private List<Tab> tabs = new ArrayList<>();
    private int[] sel = new int[0];
    private int tab;
    private boolean onStrip;
    private volatile boolean open;
    private Listener listener;

    public CompositorMenu(Context ctx) {
        super(ctx);
        // Dim the game behind; touches outside the panel close the menu
        // 뒤의 게임을 어둡게. 판 밖을 터치하면 메뉴를 닫음
        setBackgroundColor(0x8C000000);
        setVisibility(GONE);
        setClickable(true);
        setOnClickListener(v -> hide());

        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setBackgroundColor(C_PANEL);
        panel.setClickable(true);
        // Rows column: tab name, rows, control hint at the bottom
        // 줄 칸: 탭 이름, 줄, 맨 아래 조작 안내
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(14), dp(12));
        header = new TextView(ctx);
        header.setTextColor(C_TEXT);
        header.setTextSize(20);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(dp(6), 0, dp(6), dp(12));
        content.addView(header);
        scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new FrameLayout.LayoutParams(-1, -2));
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        hintView = new TextView(ctx);
        hintView.setTextColor(C_TEXT_DIM);
        hintView.setTextSize(12);
        hintView.setPadding(dp(6), dp(10), dp(6), 0);
        content.addView(hintView);
        panel.addView(content, new LinearLayout.LayoutParams(0, -1, 1f));
        // Vertical tab strip at the right edge / 오른쪽 끝의 세로 탭 줄
        strip = new LinearLayout(ctx);
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setBackgroundColor(C_STRIP);
        strip.setPadding(0, dp(14), 0, dp(14));
        panel.addView(strip, new LinearLayout.LayoutParams(dp(84), -1));

        addView(panel, new FrameLayout.LayoutParams(panelWidth(), -1, Gravity.END));
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    /** About 480 dp, at most 70 % of the screen width / 약 480dp, 화면 너비의 70% 이하 */
    private int panelWidth() {
        int screen = getResources().getDisplayMetrics().widthPixels;
        return Math.min(dp(480), Math.round(screen * 0.7f));
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Control hint shown at the bottom of the panel (from the host) / 판 맨 아래의 조작 안내(호스트가 줌) */
    public void setHint(String hint) {
        hintView.setText(hint);
    }

    /** Replace the tabs (UI thread); the chosen tab and rows are kept when they still exist
     *  탭을 바꿈(UI 스레드). 고른 탭과 줄은 아직 있으면 유지함 */
    public void setTabs(List<Tab> t) {
        int[] old = sel;
        tabs = new ArrayList<>(t);
        sel = new int[tabs.size()];
        for (int i = 0; i < sel.length && i < old.length; i++) sel[i] = old[i];
        tab = Math.min(tab, Math.max(0, tabs.size() - 1));
        buildStrip();
        renderRows();
    }

    /** Tab strip views: icon over a short name / 탭 줄 뷰: 아이콘 아래 짧은 이름 */
    private void buildStrip() {
        strip.removeAllViews();
        tabViews.clear();
        tabIcons.clear();
        tabLabels.clear();
        for (int i = 0; i < tabs.size(); i++) {
            final int k = i;
            LinearLayout v = new LinearLayout(getContext());
            v.setOrientation(LinearLayout.VERTICAL);
            v.setGravity(Gravity.CENTER_HORIZONTAL);
            v.setPadding(dp(4), dp(10), dp(4), dp(10));
            IconView icon = new IconView(getContext(), tabs.get(i).icon);
            v.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
            TextView name = new TextView(getContext());
            name.setText(tabs.get(i).label);
            name.setTextSize(11);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(0, dp(4), 0, 0);
            v.addView(name, new LinearLayout.LayoutParams(-1, -2));
            // Tap: show that tab, rows keep the focus / 탭: 그 탭을 보임, 포커스는 줄 목록
            v.setOnClickListener(x -> {
                tab = k;
                onStrip = false;
                renderRows();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(4);
            strip.addView(v, lp);
            tabViews.add(v);
            tabIcons.add(icon);
            tabLabels.add(name);
        }
    }

    /** Rows of the chosen tab / 고른 탭의 줄 */
    private void renderRows() {
        list.removeAllViews();
        rows.clear();
        if (tabs.isEmpty()) return;
        Tab t = tabs.get(tab);
        header.setText(t.label);
        for (int i = 0; i < t.items.size(); i++) {
            final int k = i;
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(11), dp(12), dp(11));
            row.setMinimumHeight(dp(48));
            TextView label = new TextView(getContext());
            label.setTextSize(16);
            label.setTextColor(C_TEXT);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView value = new TextView(getContext());
            value.setTextSize(15);
            value.setGravity(Gravity.END);
            value.setSingleLine(true);
            value.setPadding(dp(10), 0, 0, 0);
            row.addView(value, new LinearLayout.LayoutParams(-2, -2));
            // Tap: choose the row; on a value row a tap moves to the next value
            // 탭: 그 줄을 고름. 값 줄은 탭하면 다음 값으로
            row.setOnClickListener(v -> {
                sel[tab] = k;
                onStrip = false;
                activate(true);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(2);
            list.addView(row, lp);
            rows.add(row);
        }
        sel[tab] = Math.min(sel[tab], Math.max(0, rows.size() - 1));
        scroll.scrollTo(0, 0);
        refresh();
    }

    private Item current() {
        if (tabs.isEmpty()) return null;
        List<Item> it = tabs.get(tab).items;
        int s = sel[tab];
        return s >= 0 && s < it.size() ? it.get(s) : null;
    }

    private static GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    /** Redraw labels, values, the highlight and the tab strip / 이름·값·강조·탭 줄을 다시 그림 */
    public void refresh() {
        if (tabs.isEmpty()) return;
        // Tab strip: chosen tab bright with an accent bar, the focused one also gets a box
        // 탭 줄: 고른 탭은 밝게·강조 막대, 포커스가 있으면 상자도
        for (int i = 0; i < tabViews.size(); i++) {
            boolean chosen = i == tab;
            View v = tabViews.get(i);
            if (chosen) {
                GradientDrawable g = round(onStrip ? C_ROW_FOCUS : 0xFF1B222B, dp(8));
                g.setStroke(onStrip ? dp(2) : 0, C_ACCENT);
                v.setBackground(g);
            } else {
                v.setBackground(null);
            }
            tabIcons.get(i).setColor(chosen ? C_ACCENT : C_TEXT_DIM);
            tabLabels.get(i).setTextColor(chosen ? C_TEXT : C_TEXT_DIM);
        }
        List<Item> it = tabs.get(tab).items;
        for (int i = 0; i < rows.size() && i < it.size(); i++) {
            Item m = it.get(i);
            LinearLayout row = rows.get(i);
            boolean focus = i == sel[tab];
            ((TextView) row.getChildAt(0)).setText(m.label);
            TextView value = (TextView) row.getChildAt(1);
            String v = m.value != null ? m.value.get() : null;
            if (v == null || v.isEmpty()) {
                value.setText(m.value == null && focus && !onStrip ? "›" : (v == null ? "" : v));
                value.setTextColor(C_TEXT_DIM);
            } else {
                // Arrows only on the focused row that can change / 바꿀 수 있는 포커스 줄에만 화살표
                value.setText(focus && !onStrip && m.hasValue() ? "◀  " + v + "  ▶" : v);
                value.setTextColor(focus ? C_ACCENT : C_TEXT_DIM);
            }
            if (focus) {
                row.setBackground(round(onStrip ? C_ROW_FOCUS_DIM : C_ROW_FOCUS, dp(8)));
            } else {
                row.setBackground(null);
            }
        }
        // Keep the focused row in view / 포커스 줄이 보이게 함
        int s = sel[tab];
        if (s >= 0 && s < rows.size()) {
            View r = rows.get(s);
            scroll.post(() -> {
                int top = r.getTop(), bottom = r.getBottom();
                int y = scroll.getScrollY(), h = scroll.getHeight();
                if (top < y) scroll.smoothScrollTo(0, top);
                else if (bottom > y + h) scroll.smoothScrollTo(0, bottom - h);
            });
        }
    }

    /** A or tap on the focused row / 포커스 줄에서 A 또는 탭 */
    private void activate(boolean tap) {
        Item m = current();
        if (m == null) return;
        if (tap && m.value != null && m.right != null) m.right.run();
        else if (m.select != null) m.select.run();
        else if (m.right != null) m.right.run();
        if (open) refresh();
    }

    private void stepTab(int d) {
        if (tabs.isEmpty()) return;
        tab = (tab + d + tabs.size()) % tabs.size();
        renderRows();
    }

    public boolean isOpen() {
        return open;
    }

    /** Open on the last tab with the rows focused, sliding in from the right
     *  마지막 탭, 줄 목록 포커스로 엶. 오른쪽에서 밀려 나옴 */
    public void show() {
        if (open) return;
        open = true;
        onStrip = false;
        if (!tabs.isEmpty()) sel[tab] = 0;
        panel.getLayoutParams().width = panelWidth();
        panel.requestLayout();
        renderRows();
        setVisibility(VISIBLE);
        bringToFront();
        panel.animate().cancel();
        panel.setTranslationX(panelWidth());
        panel.animate().translationX(0).setDuration(140).start();
        setAlpha(0f);
        animate().cancel();
        animate().alpha(1f).setDuration(140).start();
        if (listener != null) listener.shown(true);
    }

    public void hide() {
        if (!open) return;
        open = false;
        panel.animate().cancel();
        animate().cancel();
        panel.animate().translationX(panel.getWidth()).setDuration(110).start();
        animate().alpha(0f).setDuration(110).withEndAction(() -> {
            if (!open) setVisibility(GONE);
        }).start();
        if (listener != null) listener.shown(false);
    }

    /** B / screen back: rows → tab strip → close / B·화면 뒤로 가기: 줄 목록 → 탭 줄 → 닫기 */
    private void back() {
        if (!onStrip) {
            onStrip = true;
            refresh();
        } else {
            hide();
        }
    }

    /** Back on the screen (Android back) while open / 열린 동안 화면 뒤로 가기 */
    public void onBack() {
        if (open) post(this::back);
    }

    /**
     * A pad button went down while the menu is open (any thread); true when the menu used it.
     * 메뉴가 열린 동안 패드 버튼이 눌림(아무 스레드). 메뉴가 썼으면 true
     */
    public boolean onButton(int b) {
        if (!open) return false;
        post(() -> {
            if (!open || tabs.isEmpty()) return;
            int n = rows.size();
            Item m = current();
            switch (b) {
                case LB:
                    stepTab(-1);
                    return;
                case RB:
                    stepTab(1);
                    return;
                case B:
                    back();
                    return;
                default:
                    break;
            }
            if (onStrip) {
                // Up/down choose the tab (its rows show at once), left or A go into the rows
                // 위아래로 탭 고름(그 줄이 바로 보임), 왼쪽이나 A는 줄 목록으로
                switch (b) {
                    case DPAD_UP:
                        stepTab(-1);
                        return;
                    case DPAD_DOWN:
                        stepTab(1);
                        return;
                    case DPAD_LEFT:
                    case A:
                        onStrip = false;
                        break;
                    default:
                        return;
                }
                refresh();
                return;
            }
            switch (b) {
                case DPAD_UP:
                    if (n > 0) sel[tab] = (sel[tab] + n - 1) % n;
                    break;
                case DPAD_DOWN:
                    if (n > 0) sel[tab] = (sel[tab] + 1) % n;
                    break;
                case DPAD_LEFT:
                    if (m != null && m.left != null) m.left.run();
                    break;
                case DPAD_RIGHT:
                    if (m != null && m.right != null) m.right.run();
                    else onStrip = true;
                    break;
                case A:
                    activate(false);
                    return;
                default:
                    return;
            }
            refresh();
        });
        return true;
    }

    @Override
    public void setVisibility(int v) {
        super.setVisibility(v);
        if (v == View.VISIBLE) bringToFront();
    }

    /**
     * Small line icons drawn on a canvas (own shapes, no image files): lightning bolt (quick),
     * monitor (display), pad (input), pulse line (diagnosis).
     * 캔버스로 그리는 작은 선 아이콘(자체 모양, 이미지 파일 없음): 번개(빠른 메뉴), 모니터(화면), 패드(입력), 맥박선(진단)
     */
    private static final class IconView extends View {
        private final int type;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();

        IconView(Context c, int type) {
            super(c);
            this.type = type;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(Color.WHITE);
        }

        void setColor(int c) {
            if (p.getColor() != c) {
                p.setColor(c);
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), u = Math.min(w, h) / 24f;
            float ox = (w - 24 * u) / 2f, oy = (h - 24 * u) / 2f;
            p.setStrokeWidth(2f * u);
            path.reset();
            switch (type) {
                case ICON_QUICK:
                    // Lightning bolt / 번개
                    p.setStyle(Paint.Style.FILL_AND_STROKE);
                    path.moveTo(ox + 13 * u, oy + 2 * u);
                    path.lineTo(ox + 5 * u, oy + 14 * u);
                    path.lineTo(ox + 11 * u, oy + 14 * u);
                    path.lineTo(ox + 10 * u, oy + 22 * u);
                    path.lineTo(ox + 19 * u, oy + 10 * u);
                    path.lineTo(ox + 13 * u, oy + 10 * u);
                    path.close();
                    c.drawPath(path, p);
                    p.setStyle(Paint.Style.STROKE);
                    break;
                case ICON_DISPLAY:
                    // Monitor with stand / 받침 있는 모니터
                    r.set(ox + 2 * u, oy + 4 * u, ox + 22 * u, oy + 17 * u);
                    c.drawRoundRect(r, 2 * u, 2 * u, p);
                    c.drawLine(ox + 12 * u, oy + 17 * u, ox + 12 * u, oy + 21 * u, p);
                    c.drawLine(ox + 7 * u, oy + 21 * u, ox + 17 * u, oy + 21 * u, p);
                    break;
                case ICON_INPUT:
                    // Pad outline, cross on the left, two buttons on the right
                    // 패드 윤곽, 왼쪽 십자, 오른쪽 버튼 둘
                    r.set(ox + 1 * u, oy + 6 * u, ox + 23 * u, oy + 19 * u);
                    c.drawRoundRect(r, 6 * u, 6 * u, p);
                    c.drawLine(ox + 5 * u, oy + 12.5f * u, ox + 10 * u, oy + 12.5f * u, p);
                    c.drawLine(ox + 7.5f * u, oy + 10 * u, ox + 7.5f * u, oy + 15 * u, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(ox + 16 * u, oy + 11 * u, 1.4f * u, p);
                    c.drawCircle(ox + 19 * u, oy + 14 * u, 1.4f * u, p);
                    p.setStyle(Paint.Style.STROKE);
                    break;
                default:
                    // Pulse line in a box / 상자 안 맥박선
                    r.set(ox + 2 * u, oy + 3 * u, ox + 22 * u, oy + 21 * u);
                    c.drawRoundRect(r, 3 * u, 3 * u, p);
                    path.moveTo(ox + 5 * u, oy + 13 * u);
                    path.lineTo(ox + 9 * u, oy + 13 * u);
                    path.lineTo(ox + 11 * u, oy + 8 * u);
                    path.lineTo(ox + 14 * u, oy + 17 * u);
                    path.lineTo(ox + 16 * u, oy + 12 * u);
                    path.lineTo(ox + 19 * u, oy + 12 * u);
                    c.drawPath(path, p);
                    break;
            }
        }
    }
}
