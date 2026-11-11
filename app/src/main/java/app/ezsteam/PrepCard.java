package app.ezsteam;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Progress card for the base prefix steps before Steam starts, laid out like EZlator's download
 * card (download_progress_dialog.xml in the EZlator app): ring spinner, title with the percentage
 * on the right, a status line, the bar, then the step count (no second bar: the percentage already
 * counts steps). Built here in code with the same dark colours; nothing is taken from EZlator.
 * Steam 시작 전 기본 prefix 단계용 진행 카드. EZlator 앱의 다운로드 카드(download_progress_dialog.xml)와
 * 같은 배치: 원형 스피너, 오른쪽에 퍼센트가 붙은 제목, 상태 줄, 막대, 그다음 단계 수(두 번째 막대 없음:
 * 퍼센트가 이미 단계를 셈). 같은 어두운 색으로 여기서 코드로 만듦. EZlator에서 가져온 것은 없음
 */
final class PrepCard extends FrameLayout {
    // EZlator dark neon colours (colors_neon.xml neon_d_*) / EZlator 어두운 네온 색(colors_neon.xml neon_d_*)
    private static final int SURFACE = 0xFF101019;
    private static final int SURFACE_ALT = 0xFF1C1C2A;
    private static final int ACCENT = 0xFF00E5FF;
    private static final int GLOW = 0x5900E5FF;
    private static final int TEXT = 0xFFE9EFFF;
    private static final int TEXT_DIM = 0xFF8D97B5;

    private final TextView title, percent, detail, count;
    private final ProgressBar bar;
    private final LinearLayout overallRow;
    private volatile boolean active;

    PrepCard(Context ctx) {
        super(ctx);
        float dp = ctx.getResources().getDisplayMetrics().density;
        setClickable(true); // nothing reaches the black screen below / 아래 검은 화면으로 터치가 가지 않음
        setBackgroundColor(0x99000000);
        setVisibility(GONE);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(22, dp), px(20, dp), px(22, dp), px(18, dp));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SURFACE);
        bg.setCornerRadius(20 * dp);
        bg.setStroke(px(1, dp), GLOW);
        card.setBackground(bg);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ProgressBar ring = new ProgressBar(ctx);
        ring.setIndeterminate(true);
        ring.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        LinearLayout.LayoutParams ringLp = new LinearLayout.LayoutParams(px(36, dp), px(36, dp));
        ringLp.rightMargin = px(14, dp);
        row.addView(ring, ringLp);

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        title = text(ctx, 15, TEXT, true);
        texts.addView(title);
        detail = text(ctx, 12, TEXT_DIM, false);
        detail.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
        detailLp.topMargin = px(2, dp);
        texts.addView(detail, detailLp);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        percent = text(ctx, 15, ACCENT, true);
        LinearLayout.LayoutParams pctLp = new LinearLayout.LayoutParams(-2, -2);
        pctLp.leftMargin = px(12, dp);
        row.addView(percent, pctLp);
        card.addView(row);

        bar = linear(ctx);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, px(8, dp));
        barLp.topMargin = px(14, dp);
        card.addView(bar, barLp);

        overallRow = new LinearLayout(ctx);
        overallRow.setOrientation(LinearLayout.VERTICAL);
        count = text(ctx, 12, TEXT_DIM, false);
        overallRow.addView(count);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = px(12, dp);
        card.addView(overallRow, rowLp);

        // At most 460 dp wide, with 24 dp margins on narrow screens (same as the EZlator card)
        // 최대 460dp 폭, 좁은 화면에서는 24dp 여백(EZlator 카드와 같음)
        int max = px(460, dp);
        int screen = ctx.getResources().getDisplayMetrics().widthPixels - px(48, dp);
        addView(card, new LayoutParams(Math.min(max, screen), -2, Gravity.CENTER));
    }

    private static int px(float v, float dp) {
        return Math.round(v * dp);
    }

    private static TextView text(Context ctx, int sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Rounded determinate bar like neon_progress_horizontal.xml / neon_progress_horizontal.xml 같은 둥근 막대 */
    private static ProgressBar linear(Context ctx) {
        ProgressBar p = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        p.setMax(100);
        GradientDrawable track = new GradientDrawable();
        track.setColor(SURFACE_ALT);
        track.setCornerRadius(999);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(ACCENT);
        fill.setCornerRadius(999);
        LayerDrawable d = new LayerDrawable(new android.graphics.drawable.Drawable[]{
                track, new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)});
        d.setId(0, android.R.id.background);
        d.setId(1, android.R.id.progress);
        p.setProgressDrawable(d);
        return p;
    }

    boolean active() {
        return active;
    }

    /** Any thread / 아무 스레드 */
    void show(String heading) {
        active = true;
        post(() -> {
            title.setText(heading);
            percent.setText("");
            detail.setText("");
            bar.setIndeterminate(true);
            overallRow.setVisibility(GONE);
            setVisibility(VISIBLE);
        });
    }

    /**
     * Any thread. pct -1 = not known yet (moving bar); stepsDone -1 hides the step row.
     * 아무 스레드. pct -1 = 아직 모름(움직이는 막대). stepsDone -1이면 단계 줄을 숨김
     */
    void update(int pct, String status, String steps, int stepsDone, int stepsAll) {
        if (!active) return;
        post(() -> {
            bar.setIndeterminate(pct < 0);
            if (pct >= 0) bar.setProgress(pct);
            percent.setText(pct >= 0 ? pct + "%" : "");
            detail.setText(status);
            if (stepsDone >= 0 && stepsAll > 1) {
                count.setText(steps);
                overallRow.setVisibility(VISIBLE);
            } else {
                overallRow.setVisibility(GONE);
            }
        });
    }

    /** Any thread / 아무 스레드 */
    void hide() {
        active = false;
        post(() -> setVisibility(GONE));
    }
}
