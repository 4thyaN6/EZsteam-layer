package app.ezsteam.runtime;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
 * gamescope-style compositor has its own). Drawn as Android views above the game view, so it
 * needs no X window and nothing from the game. Driven by the pad (D-pad / left stick up-down
 * to move, left-right to change, A to choose, B to go back or close) or by touch. Rows can open
 * a submenu; a long list scrolls with the selection. The host fills the rows and gives every
 * text shown (this module has no string resources, so the host's app translates them).
 * HOME으로 여는 게임 위의 앱 자체 메뉴(gamescope 같은 컴포지터가 자기 메뉴를 두는 자리의 컴포지터 메뉴).
 * 게임 뷰 위의 안드로이드 뷰로 그리므로 X 창도 게임 쪽 도움도 필요 없음. 패드(D-pad / 왼쪽 스틱 위아래로
 * 이동, 좌우로 값 바꾸기, A 고르기, B 뒤로 또는 닫기)나 터치로 조작함. 줄은 하위 메뉴를 열 수 있고, 긴 목록은
 * 선택을 따라 스크롤함. 줄과 보이는 모든 글은 호스트가 줌(이 모듈은 문자열 리소스가 없어 호스트 앱이 번역함)
 */
public final class CompositorMenu extends FrameLayout {

    /** One row: label, current value (null = action row), what left / right / A do, or a submenu
     *  줄 하나: 이름, 지금 값(null = 동작 줄), 왼쪽 / 오른쪽 / A가 하는 일, 또는 하위 메뉴 */
    public static final class Item {
        final String label;
        final Supplier<String> value;
        final Runnable left, right, select;
        final List<Item> sub;

        public Item(String label, Supplier<String> value, Runnable left, Runnable right, Runnable select) {
            this.label = label;
            this.value = value;
            this.left = left;
            this.right = right;
            this.select = select;
            this.sub = null;
        }

        /** A row that opens a submenu / 하위 메뉴를 여는 줄 */
        public Item(String label, List<Item> sub) {
            this.label = label;
            this.value = null;
            this.left = this.right = this.select = null;
            this.sub = sub;
        }
    }

    /** Called when the menu opens or closes / 메뉴가 열리거나 닫힐 때 부름 */
    public interface Listener {
        void shown(boolean shown);
    }

    // SDL button numbers (PadFile order) / SDL 버튼 번호(PadFile 순서)
    private static final int A = 0, B = 1, DPAD_UP = 11, DPAD_DOWN = 12, DPAD_LEFT = 13, DPAD_RIGHT = 14;

    /** One level of the menu: its rows, title and selection / 메뉴의 한 단계: 줄, 제목, 선택 */
    private static final class Level {
        final List<Item> items;
        final String title;
        int sel;

        Level(List<Item> items, String title) {
            this.items = items;
            this.title = title;
        }
    }

    private final ScrollView scroll;
    private final LinearLayout list;
    private final TextView titleView;
    private final List<TextView> rows = new ArrayList<>();
    private final ArrayList<Level> stack = new ArrayList<>();
    private List<Item> rootItems = new ArrayList<>();
    private String rootTitle = "", hint = "", backLabel = "◀";
    private volatile boolean open;
    private Listener listener;

    public CompositorMenu(Context ctx) {
        super(ctx);
        // Dim the game behind; touches outside the panel close the menu
        // 뒤의 게임을 어둡게. 판 밖을 터치하면 메뉴를 닫음
        setBackgroundColor(0x99000000);
        setVisibility(GONE);
        setClickable(true);
        setOnClickListener(v -> hide());
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE1B2026);
        bg.setCornerRadius(dp(14));
        panel.setBackground(bg);
        panel.setClickable(true);
        titleView = new TextView(ctx);
        titleView.setTextColor(0xFFB0BEC5);
        titleView.setTextSize(13);
        titleView.setPadding(dp(6), 0, dp(6), dp(8));
        panel.addView(titleView);
        // Rows scroll when the list is taller than the screen allows / 목록이 화면보다 길면 줄이 스크롤됨
        scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(true);
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new FrameLayout.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(460), -2, Gravity.CENTER);
        lp.topMargin = lp.bottomMargin = dp(24);
        addView(panel, lp);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /**
     * Texts from the host: top title, the control hint after it, the label of a submenu's back row
     * 호스트가 주는 글: 맨 위 제목, 그 뒤 조작 안내, 하위 메뉴의 뒤로 줄 이름
     */
    public void setTexts(String title, String controlHint, String back) {
        rootTitle = title;
        hint = controlHint;
        backLabel = back;
        if (!stack.isEmpty()) render();
    }

    /** Replace the rows (UI thread); a submenu that is open goes back to the top
     *  줄을 바꿈(UI 스레드). 열린 하위 메뉴는 맨 위로 돌아감 */
    public void setItems(List<Item> it) {
        rootItems = new ArrayList<>(it);
        int sel = stack.isEmpty() ? 0 : stack.get(0).sel;
        stack.clear();
        Level top = new Level(rootItems, rootTitle);
        top.sel = Math.min(sel, Math.max(0, rootItems.size() - 1));
        stack.add(top);
        render();
    }

    private Level level() {
        return stack.get(stack.size() - 1);
    }

    /** Rows of the current level; a submenu starts with its back row / 지금 단계의 줄. 하위 메뉴는 뒤로 줄로 시작함 */
    private void render() {
        if (stack.isEmpty()) return;
        Level lv = level();
        list.removeAllViews();
        rows.clear();
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < stack.size(); i++) t.append(i == 0 ? rootTitle : "  ›  " + stack.get(i).title);
        if (!hint.isEmpty()) t.append("   (").append(hint).append(')');
        titleView.setText(t);
        int n = lv.items.size() + (stack.size() > 1 ? 1 : 0);
        for (int i = 0; i < n; i++) {
            final int k = i;
            TextView row = new TextView(getContext());
            row.setTextSize(17);
            row.setPadding(dp(12), dp(9), dp(12), dp(9));
            row.setTextColor(Color.WHITE);
            // Tap: choose the row; on a value row a tap moves to the next value
            // 탭: 그 줄을 고름. 값 줄은 탭하면 다음 값으로
            row.setOnClickListener(v -> {
                level().sel = k;
                activate(true);
            });
            rows.add(row);
            list.addView(row);
        }
        lv.sel = Math.min(lv.sel, Math.max(0, n - 1));
        refresh();
    }

    /** Item behind a row number of the current level, null for the back row / 지금 단계 줄 번호의 항목, 뒤로 줄이면 null */
    private Item itemAt(int row) {
        Level lv = level();
        int off = stack.size() > 1 ? 1 : 0;
        return row < off ? null : lv.items.get(row - off);
    }

    /** Redraw labels, values and the highlight / 이름·값·강조를 다시 그림 */
    public void refresh() {
        if (stack.isEmpty()) return;
        Level lv = level();
        for (int i = 0; i < rows.size(); i++) {
            Item m = itemAt(i);
            String text;
            if (m == null) {
                text = backLabel;
            } else if (m.sub != null) {
                text = m.label + "   ▸";
            } else {
                String v = m.value != null ? m.value.get() : null;
                text = v == null ? m.label : m.label + "   ◀ " + v + " ▶";
            }
            rows.get(i).setText(text);
            rows.get(i).setBackgroundColor(i == lv.sel ? 0xFF2E6BD6 : Color.TRANSPARENT);
        }
        // Keep the selected row in view / 고른 줄이 보이게 함
        if (lv.sel >= 0 && lv.sel < rows.size()) {
            TextView r = rows.get(lv.sel);
            scroll.post(() -> {
                int top = r.getTop(), bottom = r.getBottom();
                int y = scroll.getScrollY(), h = scroll.getHeight();
                if (top < y) scroll.smoothScrollTo(0, top);
                else if (bottom > y + h) scroll.smoothScrollTo(0, bottom - h);
            });
        }
    }

    /** A or tap on the selected row / 고른 줄에서 A 또는 탭 */
    private void activate(boolean tap) {
        Item m = itemAt(level().sel);
        if (m == null) {
            back();
            return;
        }
        if (m.sub != null) {
            Level lv = new Level(m.sub, m.label);
            stack.add(lv);
            render();
            return;
        }
        if (tap && m.value != null && m.right != null) m.right.run();
        else if (m.select != null) m.select.run();
        else if (m.right != null) m.right.run();
        if (open) refresh();
    }

    /** B: one level up, or close at the top / B: 한 단계 위로, 맨 위면 닫음 */
    private void back() {
        if (stack.size() > 1) {
            stack.remove(stack.size() - 1);
            render();
        } else {
            hide();
        }
    }

    public boolean isOpen() {
        return open;
    }

    /** Open at the top level / 맨 위 단계로 엶 */
    public void show() {
        if (open) return;
        open = true;
        while (stack.size() > 1) stack.remove(stack.size() - 1);
        if (stack.isEmpty()) stack.add(new Level(rootItems, rootTitle));
        level().sel = 0;
        render();
        setVisibility(VISIBLE);
        bringToFront();
        if (listener != null) listener.shown(true);
    }

    public void hide() {
        if (!open) return;
        open = false;
        setVisibility(GONE);
        if (listener != null) listener.shown(false);
    }

    /** Back on the screen (Android back) while open: one level up or close / 열린 동안 화면 뒤로 가기: 한 단계 위로 또는 닫기 */
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
            if (stack.isEmpty()) return;
            Level lv = level();
            int n = rows.size();
            if (n == 0) return;
            Item m = itemAt(lv.sel);
            switch (b) {
                case DPAD_UP:
                    lv.sel = (lv.sel + n - 1) % n;
                    break;
                case DPAD_DOWN:
                    lv.sel = (lv.sel + 1) % n;
                    break;
                case DPAD_LEFT:
                    if (m != null && m.left != null) m.left.run();
                    break;
                case DPAD_RIGHT:
                    if (m != null && m.right != null) m.right.run();
                    else if (m != null && m.sub != null) {
                        activate(false);
                        return;
                    }
                    break;
                case A:
                    activate(false);
                    return;
                case B:
                    back();
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
}
