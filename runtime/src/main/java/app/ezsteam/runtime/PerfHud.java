package app.ezsteam.runtime;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Performance HUD card (detail mode of the app's performance display). Only shows what the host
 * hands in through {@link #update(Snapshot)}: it measures nothing itself, starts no thread and no
 * process, and adds no step to any frame path. Values the host cannot measure arrive as "N/A".
 * Layout follows the agreed design (2026-10-30): title + state badge, a 2x2 block (processes,
 * memory, CPU, frame rate), "Graphics" key/value rows, a process group table, a cost line.
 * 성능 HUD 카드(앱 성능 표시의 자세히 모드). 호스트가 {@link #update(Snapshot)}로 넘긴 값만 보여 줌: 스스로 재는 것이
 * 없고 스레드·프로세스를 만들지 않으며 프레임 경로에 단계를 더하지 않음. 호스트가 잴 수 없는 값은 "N/A"로 옴.
 * 배치는 정한 디자인을 따름(2026-10-30): 제목 + 상태 배지, 2x2 칸(프로세스, 메모리, CPU, 프레임), "Graphics" 이름/값 줄,
 * 프로세스 묶음 표, 수집 비용 줄
 */
public final class PerfHud extends LinearLayout {

    /** Everything one refresh shows, as display text / 한 번 갱신에 보이는 모든 값(표시용 글) */
    public static final class Snapshot {
        public String badge = "RUNNING";
        public String procValue = "N/A", procSub = "";
        public String memValue = "N/A", memSub = "";
        public String cpuValue = "N/A", cpuSub = "";
        public String fpsValue = "N/A", fpsSub = "";
        public String renderer = "N/A", display = "N/A", buffer = "N/A", copies = "N/A";
        /** Group rows: name, now, max / 묶음 줄: 이름, 현재, 최고 */
        public String[][] groups = new String[0][];
        public String footer = "";
    }

    private static final int C_BG = 0xE6101114, C_LINE = 0xFF2A2D33, C_DIM = 0xFF9AA0A8, C_TEXT = 0xFFF2F3F5;
    private static final int C_OK = 0xFF35C46A, C_WARN = 0xFFE0A030;

    private final TextView badge;
    private final TextView[] value = new TextView[4], sub = new TextView[4];
    private final TextView renderer, display, buffer, copies, footer;
    private final LinearLayout groupBox;

    public PerfHud(Context c) {
        super(c);
        setOrientation(VERTICAL);
        int p = dp(12);
        setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_BG);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), C_LINE);
        setBackground(bg);

        // Title row with the state badge / 상태 배지가 있는 제목 줄
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(c, "EZsteam-layer · Performance HUD", 13, C_TEXT, true);
        head.addView(title, new LayoutParams(0, -2, 1f));
        badge = text(c, "RUNNING", 10, C_OK, true);
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(0x3335C46A);
        bb.setCornerRadius(dp(8));
        badge.setBackground(bb);
        badge.setPadding(dp(6), dp(2), dp(6), dp(2));
        head.addView(badge);
        addView(head);

        // 2x2 block: label, big value, small line / 2x2 칸: 이름, 큰 값, 작은 줄
        String[] labels = {"Process count", "Memory usage", "CPU usage", "Frame rate"};
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(c);
            row.setPadding(0, dp(r == 0 ? 10 : 6), 0, 0);
            for (int k = 0; k < 2; k++) {
                int i = r * 2 + k;
                LinearLayout cell = new LinearLayout(c);
                cell.setOrientation(VERTICAL);
                cell.addView(text(c, labels[i], 10, C_DIM, false));
                value[i] = text(c, "N/A", 18, C_TEXT, true);
                cell.addView(value[i]);
                sub[i] = text(c, "", 9, C_DIM, false);
                cell.addView(sub[i]);
                row.addView(cell, new LayoutParams(0, -2, 1f));
            }
            addView(row);
        }

        addView(rule(c));
        addView(text(c, "Graphics", 11, C_TEXT, true));
        renderer = kv(c, "Renderer");
        display = kv(c, "Display");
        buffer = kv(c, "Buffer");
        copies = kv(c, "GPU copy / CPU copy");

        addView(rule(c));
        addView(text(c, "Process monitor", 11, C_TEXT, true));
        LinearLayout th = row3(c, "항목", "현재", "최고", C_DIM);
        addView(th);
        groupBox = new LinearLayout(c);
        groupBox.setOrientation(VERTICAL);
        addView(groupBox);

        footer = text(c, "", 9, C_DIM, false);
        footer.setPadding(0, dp(6), 0, 0);
        addView(footer);
    }

    /** Show one snapshot (UI thread) / 스냅숏 하나를 보여 줌(UI 스레드) */
    public void update(Snapshot s) {
        badge.setText(s.badge);
        String[] v = {s.procValue, s.memValue, s.cpuValue, s.fpsValue};
        String[] u = {s.procSub, s.memSub, s.cpuSub, s.fpsSub};
        for (int i = 0; i < 4; i++) {
            value[i].setText(v[i]);
            sub[i].setText(u[i]);
        }
        renderer.setText(s.renderer);
        display.setText(s.display);
        buffer.setText(s.buffer);
        copies.setText(s.copies);
        // Rows are rebuilt only when their number changes / 줄 수가 바뀔 때만 줄을 다시 만듦
        if (groupBox.getChildCount() != s.groups.length) {
            groupBox.removeAllViews();
            for (int i = 0; i < s.groups.length; i++) groupBox.addView(row3(getContext(), "", "", "", C_TEXT));
        }
        for (int i = 0; i < s.groups.length; i++) {
            LinearLayout r = (LinearLayout) groupBox.getChildAt(i);
            for (int k = 0; k < 3; k++) ((TextView) r.getChildAt(k)).setText(s.groups[i][k]);
        }
        footer.setText(s.footer);
    }

    /** Process count colour: over the target in amber / 프로세스 수 색: 목표를 넘으면 주황 */
    public void setProcessWarning(boolean over) {
        value[0].setTextColor(over ? C_WARN : C_TEXT);
    }

    private TextView kv(Context c, String name) {
        LinearLayout row = new LinearLayout(c);
        row.setPadding(0, dp(3), 0, 0);
        row.addView(text(c, name, 10, C_DIM, false), new LayoutParams(0, -2, 1f));
        TextView v = text(c, "N/A", 10, C_TEXT, true);
        v.setGravity(Gravity.END);
        row.addView(v);
        addView(row);
        return v;
    }

    private LinearLayout row3(Context c, String a, String b, String d, int color) {
        LinearLayout row = new LinearLayout(c);
        row.setPadding(0, dp(3), 0, 0);
        row.addView(text(c, a, 10, color, false), new LayoutParams(0, -2, 2f));
        TextView tb = text(c, b, 10, color, false);
        tb.setGravity(Gravity.END);
        row.addView(tb, new LayoutParams(0, -2, 1f));
        TextView td = text(c, d, 10, color, false);
        td.setGravity(Gravity.END);
        row.addView(td, new LayoutParams(0, -2, 1f));
        return row;
    }

    private android.view.View rule(Context c) {
        android.view.View v = new android.view.View(c);
        v.setBackgroundColor(C_LINE);
        LayoutParams lp = new LayoutParams(-1, dp(1));
        lp.setMargins(0, dp(8), 0, dp(6));
        v.setLayoutParams(lp);
        return v;
    }

    private TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setIncludeFontPadding(false);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
