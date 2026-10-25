package app.ezsteam.runtime;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The app's own menu over the game, opened with HOME (the compositor's menu, in the place a
 * gamescope-style compositor has its own). Drawn as Android views above the game view, so it
 * needs no X window and nothing from the game. Driven by the pad (D-pad / left stick up-down
 * to move, left-right to change, A to choose, B to close) or by touch. The host fills the rows.
 * HOME으로 여는 게임 위의 앱 자체 메뉴(gamescope 같은 컴포지터가 자기 메뉴를 두는 자리의 컴포지터 메뉴).
 * 게임 뷰 위의 안드로이드 뷰로 그리므로 X 창도 게임 쪽 도움도 필요 없음. 패드(D-pad / 왼쪽 스틱 위아래로
 * 이동, 좌우로 값 바꾸기, A 고르기, B 닫기)나 터치로 조작함. 줄은 호스트가 채움
 */
public final class CompositorMenu extends FrameLayout {

    /** One row: label, current value (null = action row), and what left / right / A do
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
    }

    /** Called when the menu opens or closes / 메뉴가 열리거나 닫힐 때 부름 */
    public interface Listener {
        void shown(boolean shown);
    }

    // SDL button numbers (PadFile order) / SDL 버튼 번호(PadFile 순서)
    private static final int A = 0, B = 1, DPAD_UP = 11, DPAD_DOWN = 12, DPAD_LEFT = 13, DPAD_RIGHT = 14;

    private final LinearLayout list;
    private final List<Item> items = new ArrayList<>();
    private final List<TextView> rows = new ArrayList<>();
    private int sel;
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
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        list.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE1B2026);
        bg.setCornerRadius(dp(14));
        list.setBackground(bg);
        list.setClickable(true);
        addView(list, new FrameLayout.LayoutParams(dp(420), -2, Gravity.CENTER));
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Replace the rows (UI thread) / 줄을 바꿈(UI 스레드) */
    public void setItems(List<Item> it) {
        items.clear();
        items.addAll(it);
        list.removeAllViews();
        rows.clear();
        TextView title = new TextView(getContext());
        title.setText("EZsteam 메뉴   (A 선택 · ◀▶ 바꾸기 · B 닫기)");
        title.setTextColor(0xFFB0BEC5);
        title.setTextSize(13);
        title.setPadding(dp(6), 0, dp(6), dp(8));
        list.addView(title);
        for (int i = 0; i < items.size(); i++) {
            final int k = i;
            TextView t = new TextView(getContext());
            t.setTextSize(17);
            t.setPadding(dp(12), dp(9), dp(12), dp(9));
            t.setTextColor(Color.WHITE);
            // Tap: choose the row; on a value row a tap moves to the next value
            // 탭: 그 줄을 고름. 값 줄은 탭하면 다음 값으로
            t.setOnClickListener(v -> {
                sel = k;
                Item m = items.get(k);
                if (m.value != null && m.right != null) m.right.run();
                else if (m.select != null) m.select.run();
                refresh();
            });
            rows.add(t);
            list.addView(t);
        }
        sel = Math.min(sel, Math.max(0, items.size() - 1));
        refresh();
    }

    /** Redraw labels, values and the highlight / 이름·값·강조를 다시 그림 */
    public void refresh() {
        for (int i = 0; i < rows.size(); i++) {
            Item m = items.get(i);
            String v = m.value != null ? m.value.get() : null;
            rows.get(i).setText(v == null ? m.label : m.label + "   ◀ " + v + " ▶");
            rows.get(i).setBackgroundColor(i == sel ? 0xFF2E6BD6 : Color.TRANSPARENT);
        }
    }

    public boolean isOpen() {
        return open;
    }

    public void show() {
        if (open) return;
        open = true;
        sel = 0;
        refresh();
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

    /**
     * A pad button went down while the menu is open (any thread); true when the menu used it.
     * 메뉴가 열린 동안 패드 버튼이 눌림(아무 스레드). 메뉴가 썼으면 true
     */
    public boolean onButton(int b) {
        if (!open) return false;
        post(() -> {
            if (items.isEmpty()) return;
            Item m = items.get(sel);
            switch (b) {
                case DPAD_UP:
                    sel = (sel + items.size() - 1) % items.size();
                    break;
                case DPAD_DOWN:
                    sel = (sel + 1) % items.size();
                    break;
                case DPAD_LEFT:
                    if (m.left != null) m.left.run();
                    break;
                case DPAD_RIGHT:
                    if (m.right != null) m.right.run();
                    break;
                case A:
                    if (m.select != null) m.select.run();
                    else if (m.right != null) m.right.run();
                    break;
                case B:
                    hide();
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
