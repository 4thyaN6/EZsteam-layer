package app.ezsteam;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.ezsteam.runtime.DriverInstaller;
import app.ezsteam.runtime.FramebufferView;
import app.ezsteam.runtime.GlibcLauncher;
import app.ezsteam.runtime.RootfsInstaller;
import app.ezsteam.runtime.XKeys;
import app.ezsteam.runtime.XTestClient;
import app.ezsteam.runtime.XvfbFramebuffer;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Live display test: starts display.sh (Xvfb with its screen in a file + test windows) in the
 * rootfs, shows that screen with FramebufferView, and sends touch / key input back through XTEST.
 * Touch: one finger = left button (tap = click, drag = drag), two-finger tap = right click,
 * two-finger vertical drag = wheel. Resolution comes from the intent (EXTRA_W / EXTRA_H).
 * 실시간 화면 시험: rootfs에서 display.sh(화면을 파일로 두는 Xvfb + 시험 창)를 띄우고,
 * 그 화면을 FramebufferView로 보여 주며, 터치·키 입력을 XTEST로 돌려보냄.
 * 터치: 한 손가락 = 왼쪽 버튼(탭 = 클릭, 끌기 = 드래그), 두 손가락 탭 = 오른쪽 클릭,
 * 두 손가락 세로 끌기 = 휠. 해상도는 인텐트(EXTRA_W / EXTRA_H)로 받음
 */
public class DisplayActivity extends Activity {

    public static final String EXTRA_W = "w";
    public static final String EXTRA_H = "h";
    /** "test" (test windows) or "steam" (Steam client) / "test"(시험 창) 또는 "steam"(Steam 클라이언트) */
    public static final String EXTRA_MODE = "mode";
    /** Lines kept in the log overlay (Steam prints many) / 로그 겹침 창에 남기는 줄 수(Steam은 많이 출력함) */
    private static final int LOG_LINES = 300;
    private final java.util.ArrayDeque<String> logLines = new java.util.ArrayDeque<>();
    /**
     * Longer copy of the app log for [로그 저장] only (the on-screen log keeps LOG_LINES). Measured:
     * 300 lines covered only the last minutes (controller presses filled it), so the start of a run
     * was missing from saved logs.
     * [로그 저장] 전용의 더 긴 앱 로그 사본(화면 로그는 LOG_LINES 유지). 실측: 300줄은 마지막 몇 분만
     * 담겨(컨트롤러 입력이 채움) 저장한 로그에 실행 시작 부분이 없었음
     */
    private static final int SAVE_LINES = 5000;
    private final java.util.ArrayDeque<String> saveLines = new java.util.ArrayDeque<>();
    private String mode = "test";
    /** X display number used by display.sh / display.sh가 쓰는 X 디스플레이 번호 */
    private static final int DISPLAY = 98;

    private FramebufferView view;
    private TextView statsView, logView;
    /** First-run preparation banner (installers running) / 첫 실행 준비 안내 띠(설치 프로그램 실행 중) */
    private TextView prepView;
    /** Banner box: small spinner + text / 안내 띠 상자: 작은 스피너 + 글 */
    private LinearLayout prepBox;
    /** Centre progress card while Steam starts or updates (instead of a black screen)
     *  Steam이 시작·갱신하는 동안 가운데 진행 카드(검은 화면 대신) */
    private LinearLayout setupPanel;
    private TextView setupTitle, setupDetail;
    private android.widget.ProgressBar setupBar;
    /** A Steam window has appeared since the last (re)start / 마지막 (재)시작 뒤 Steam 창이 나타났음 */
    private volatile boolean steamWindowSeen;
    /** Buttons, log and status strip / 버튼·로그·상태 띠 */
    private LinearLayout overlay;
    /** [≡] corner button shown while the overlay is hidden / 겹침 표시를 숨긴 동안 보이는 [≡] 모서리 버튼 */
    private Button showButton;
    /** Status strip wanted ([정보]) / 상태 띠 표시 여부([정보]) */
    private boolean statsShown = true;
    private ScrollView logScroll;
    private volatile Process proc;
    private volatile XTestClient xtest;
    private volatile XvfbFramebuffer fb;
    private volatile boolean finishing;

    /** Last key sent to X, shown in the top bar / X로 보낸 마지막 키, 위쪽 막대에 표시 */
    private volatile String lastKey = "-";
    private volatile String shaderLogged;
    /** Child processes now / most seen, free memory (monitor thread) / 현재·최대 자식 프로세스 수, 여유 메모리(감시 스레드) */
    private volatile int procNow, procMax;

    // ---- Performance HUD (detail mode) / 성능 HUD(자세히 모드) ----
    /** Performance display: 0 off, 1 strip, 2 HUD card / 성능 표시: 0 끔, 1 띠, 2 HUD 카드 */
    private volatile int hudMode;
    private app.ezsteam.runtime.PerfHud hud;
    private static final String PREF_HUD = "view.hud";
    /** Process target of this layer (user 2026-10-30: about 25, conservative while EZlator's own
     *  share is not known) / 이 레이어의 프로세스 목표(사용자 2026-10-30: 25 정도, EZlator 몫을 모르니 보수적으로) */
    private static final int PROC_TARGET = 25;
    /** Vulkan device and X server as the session reported them / 세션이 알린 Vulkan 장치와 X 서버 */
    private volatile String hudRenderer = "N/A", hudXserver = "N/A";
    /** All children's CPU and the busiest one (updateCpu) / 자식 전체 CPU와 가장 바쁜 것(updateCpu) */
    private volatile float cpuAll = -1f, cpuUi, cpuProot, cpuX;
    private volatile String cpuTop = "";
    private long pssKb = -1, pssAtMs, pssCostNs;
    private final java.util.Map<String, Integer> hudGroupMax = new java.util.HashMap<>();
    private volatile long memFreeMb;
    private volatile boolean procListLogged;
    /** Process lists for the [프로세스] window: now, and at the highest count so far
     *  [프로세스] 창용 프로세스 목록: 현재, 지금까지 가장 많았던 때 */
    private volatile String procListNow = "", procListAtMax = "";
    /** Android kills an app's extra processes beyond 32 (all apps together); list ours when close
     *  안드로이드는 앱 추가 프로세스가 32개(모든 앱 합계)를 넘으면 종료시킴. 가까워지면 목록을 남김 */
    private static final int PROC_WARN = 28;
    /** CPU use over the last monitor interval, 100 = one core fully busy (monitor thread)
     *  마지막 감시 주기 동안의 CPU 사용률, 100 = 코어 하나를 다 씀(감시 스레드) */
    private volatile String cpuText = "";
    /** Sound output of the rootfs programs (Steam mode) / rootfs 프로그램의 소리 출력(Steam 모드) */
    private volatile app.ezsteam.runtime.AudioServer audio;
    /** Game frame path check, app side (answers ezs-present-probe) / 게임 프레임 경로 확인의 앱 쪽(ezs-present-probe에 답함) */
    private volatile app.ezsteam.runtime.PresentProbe present;
    /** Game pad input held back: windows cover the game (direct path) / Steam menu raised by HOME
     *  게임 패드 입력을 멈춤: 창이 게임을 덮음(직접 전달) / HOME으로 Steam 메뉴를 올림 */
    private volatile boolean overlayMuted, steamMenuUp;
    /** Game top-level window that was in front when HOME raised Steam (to bring it back)
     *  HOME으로 Steam을 올렸을 때 앞에 있던 게임 최상위 창(되돌릴 때 씀) */
    private volatile int gameTopBeforeMenu;
    /** Time of the last HOME press / 마지막 HOME 누른 시각 */
    private volatile long homeAt;

    private void applyGameMute() {
        app.ezsteam.runtime.PadFile pf = pad;
        if (pf == null) return;
        pf.setGameMuted(overlayMuted || steamMenuUp || menuOpen);
        // The Steam client too while the app menu is open (Big Picture would move behind it)
        // 앱 메뉴가 열린 동안에는 Steam 클라이언트도(빅픽처가 뒤에서 움직임)
        pf.setSteamMuted(menuOpen);
    }

    /** The app's own menu over the game (HOME) / 게임 위의 앱 자체 메뉴(HOME) */
    private app.ezsteam.runtime.CompositorMenu menu;
    private volatile boolean menuOpen;
    /** Second press needed to end the game from the menu / 메뉴에서 게임을 끝내려면 한 번 더 눌러야 함 */
    private boolean quitArmed;

    private static final String PREF_FPS = "game.fps";
    private static final String PREF_MATCH_RATE = "view.matchRate";
    /** Frame limits offered (0 = none) / 고를 수 있는 프레임 제한(0 = 없음) */
    private static final int[] FPS_CHOICES = {30, 40, 45, 60, 90, 120, 0};
    /** FSR output choices (names in R.array.fsr_out) / FSR 출력 선택(이름은 R.array.fsr_out) */
    private static final int FSR_OUT_COUNT = 3;

    private String[] fsrOutNames() {
        return getResources().getStringArray(R.array.fsr_out);
    }

    private int fpsPref() {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getInt(PREF_FPS, 60);
    }

    private boolean matchRatePref() {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getBoolean(PREF_MATCH_RATE, false);
    }

    /**
     * Apply the frame limit: the present layer paces running games at once (direct path); the file
     * ~/.local/share/ezsteam/fps is read by EZ Proton at the next game start (DXVK / vkd3d limit when
     * the direct path is off); the panel refresh follows when "match" is on.
     * 프레임 제한 적용: present 레이어가 실행 중인 게임을 바로 맞춤(직접 전달). ~/.local/share/ezsteam/fps
     * 파일은 다음 게임 시작 때 EZ Proton이 읽음(직접 전달이 꺼져 있으면 DXVK / vkd3d 제한). "맞춤"이 켜져 있으면
     * 패널 주사율도 따라감
     */
    private void applyFps(int fps, boolean logIt) {
        app.ezsteam.runtime.PresentProbe.setFps(fps);
        File f = new File(RootfsInstaller.rootfsDir(this), "home/steam/.local/share/ezsteam/fps");
        try {
            File d = f.getParentFile();
            if (d != null && !d.isDirectory() && !d.mkdirs()) throw new IOException("폴더 없음");
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
                o.write((fps + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
        } catch (IOException e) {
            log("프레임 제한 파일 쓰기 실패: " + e.getMessage());
        }
        view.setDisplayRate(matchRatePref() && fps > 0 ? fps : 0);
        if (logIt) log("프레임 제한: " + (fps == 0 ? "없음" : fps + "fps") + (matchRatePref() && fps > 0 ? ", 화면 주사율 맞춤" : ""));
    }

    /** String resource, with format arguments when given / 문자열 리소스. 인자가 있으면 형식 적용 */
    private String s(int id, Object... a) {
        return a.length == 0 ? getString(id) : getString(id, a);
    }

    private String onOff(boolean on) {
        return s(on ? R.string.v_on : R.string.v_off);
    }

    private static final String PREF_STATS = "view.stats";

    /**
     * Tabs of the app menu (side panel with a vertical tab strip, user request 2026-10-26). The
     * top-bar buttons of earlier versions all live here (no bar over the game): frequent actions
     * in the first tab, then display settings, input, diagnosis.
     * 앱 메뉴의 탭(세로 탭 줄이 있는 옆 판, 사용자 요청 2026-10-26). 이전 버전의 위쪽 막대 버튼은 모두 여기에 있음
     * (게임 위 막대 없음): 첫 탭은 자주 쓰는 동작, 그다음 화면 설정·입력·진단
     */
    private void buildMenu() {
        menu.setHint(s(R.string.menu_hint));
        java.util.List<app.ezsteam.runtime.CompositorMenu.Item> display = new java.util.ArrayList<>();
        display.add(new app.ezsteam.runtime.CompositorMenu.Item(s(R.string.menu_scale), () -> scaleNames()[view.getScaleMode()],
                () -> setScale((view.getScaleMode() + SCALE_COUNT - 1) % SCALE_COUNT),
                () -> setScale((view.getScaleMode() + 1) % SCALE_COUNT), null));
        display.add(new app.ezsteam.runtime.CompositorMenu.Item(s(R.string.menu_fsr_out), () -> fsrOutNames()[fsrTargetPref()],
                () -> setFsrOut((fsrTargetPref() + FSR_OUT_COUNT - 1) % FSR_OUT_COUNT),
                () -> setFsrOut((fsrTargetPref() + 1) % FSR_OUT_COUNT), null));
        display.add(new app.ezsteam.runtime.CompositorMenu.Item(s(R.string.menu_fsr_sharp), () -> fsrSharpNames()[fsrSharpIndex()],
                () -> setFsrSharp((fsrSharpIndex() + FSR_SHARP.length - 1) % FSR_SHARP.length),
                () -> setFsrSharp((fsrSharpIndex() + 1) % FSR_SHARP.length), null));
        display.add(new app.ezsteam.runtime.CompositorMenu.Item(s(R.string.menu_fps), () -> fpsName(fpsPref()),
                () -> stepFps(-1), () -> stepFps(1), null));
        display.add(new app.ezsteam.runtime.CompositorMenu.Item(s(R.string.menu_match_rate), () -> onOff(matchRatePref()),
                this::toggleMatchRate, this::toggleMatchRate, null));
        // LSFG: the choice file EZ Proton reads at the next game start (the layer is set up per game)
        // LSFG: 다음 게임 시작 때 EZ Proton이 읽는 선택 파일(레이어는 게임마다 설정됨)
        display.add(new app.ezsteam.runtime.CompositorMenu.Item(s(R.string.menu_lsfg), this::lsfgName,
                () -> stepLsfg(-1), () -> stepLsfg(1), null));
        display.add(new app.ezsteam.runtime.CompositorMenu.Item(s(R.string.menu_stats), this::hudModeName,
                () -> stepHudMode(-1), () -> stepHudMode(1), null));

        java.util.List<app.ezsteam.runtime.CompositorMenu.Item> input = new java.util.ArrayList<>();
        input.add(new app.ezsteam.runtime.CompositorMenu.Item(s(R.string.menu_touch_pad),
                () -> s(touchPad.getVisibility() == View.VISIBLE ? R.string.v_shown : R.string.v_hidden),
                () -> setTouchPadShown(touchPad.getVisibility() != View.VISIBLE),
                () -> setTouchPadShown(touchPad.getVisibility() != View.VISIBLE), null));
        input.add(new app.ezsteam.runtime.CompositorMenu.Item(s(R.string.menu_pad_settings), null, null, null, () -> {
            menu.hide();
            showPadSettings();
        }));
        input.add(new app.ezsteam.runtime.CompositorMenu.Item(s(R.string.menu_keyboard), null, null, null, () -> {
            menu.hide();
            toggleKeyboard();
        }));
        input.add(new app.ezsteam.runtime.CompositorMenu.Item(s(R.string.menu_mouse_lock), () -> onOff(view.hasPointerCapture()),
                null, null, () -> {
            menu.hide();
            toggleMouseLock();
        }));

        java.util.List<app.ezsteam.runtime.CompositorMenu.Item> diag = new java.util.ArrayList<>();
        diag.add(new app.ezsteam.runtime.CompositorMenu.Item(s(R.string.menu_processes), null, null, null, () -> {
            menu.hide();
            showProcesses();
        }));
        diag.add(new app.ezsteam.runtime.CompositorMenu.Item(s(R.string.menu_proton_log), null, null, null, () -> {
            menu.hide();
            showProtonLog();
        }));
        diag.add(new app.ezsteam.runtime.CompositorMenu.Item(s(R.string.menu_app_log), () -> onOff(logScroll.getVisibility() == View.VISIBLE),
                this::toggleLogView, this::toggleLogView, null));
        diag.add(new app.ezsteam.runtime.CompositorMenu.Item(s(R.string.menu_save_logs_pick), null, null, null, () -> {
            menu.hide();
            pickLogFile("ezsteam-logs-" + new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)
                    .format(new java.util.Date()) + ".zip");
        }));

        java.util.List<app.ezsteam.runtime.CompositorMenu.Item> it = new java.util.ArrayList<>();
        it.add(new app.ezsteam.runtime.CompositorMenu.Item(s(R.string.menu_resume), null, null, null, () -> menu.hide()));
        it.add(new app.ezsteam.runtime.CompositorMenu.Item(s(R.string.menu_steam), null, null, null, () -> {
            menu.hide();
            raiseSteamOverGame();
        }));
        it.add(new app.ezsteam.runtime.CompositorMenu.Item(s(R.string.menu_steam_guide), null, null, null, () -> {
            menu.hide();
            sendSteamGuide();
        }));
        it.add(new app.ezsteam.runtime.CompositorMenu.Item(s(R.string.menu_save_logs), null, null, null, () -> {
            menu.hide();
            saveLogs();
        }));
        it.add(new app.ezsteam.runtime.CompositorMenu.Item(s(R.string.menu_quit_game),
                () -> quitArmed ? s(R.string.menu_quit_again) : "", null, null, this::quitFromMenu));
        it.add(new app.ezsteam.runtime.CompositorMenu.Item(s(R.string.menu_close), null, null, null, () -> {
            menu.hide();
            confirmClose();
        }));
        java.util.List<app.ezsteam.runtime.CompositorMenu.Tab> tabs = new java.util.ArrayList<>();
        tabs.add(new app.ezsteam.runtime.CompositorMenu.Tab(app.ezsteam.runtime.CompositorMenu.ICON_QUICK, s(R.string.menu_tab_quick), it));
        tabs.add(new app.ezsteam.runtime.CompositorMenu.Tab(app.ezsteam.runtime.CompositorMenu.ICON_DISPLAY, s(R.string.menu_display), display));
        tabs.add(new app.ezsteam.runtime.CompositorMenu.Tab(app.ezsteam.runtime.CompositorMenu.ICON_INPUT, s(R.string.menu_input), input));
        tabs.add(new app.ezsteam.runtime.CompositorMenu.Tab(app.ezsteam.runtime.CompositorMenu.ICON_DIAG, s(R.string.menu_diag), diag));
        menu.setTabs(tabs);
    }

    /** Open the menu from touch or the screen's back (no game needed) / 터치나 화면 뒤로 가기로 메뉴를 엶(게임 없어도 됨) */
    private void openMenu() {
        app.ezsteam.runtime.CompositorMenu m = menu;
        if (m == null) return;
        // Two ways firing for one gesture (edge swipe and the system back) must not open and
        // close it again / 한 동작에 두 경로가 함께 와도(가장자리 밀기와 시스템 뒤로 가기) 열었다 닫지 않게 함
        long now = SystemClock.uptimeMillis();
        if (now - menuOpenedAt < 400) return;
        if (m.isOpen()) {
            m.onBack();
            return;
        }
        XTestClient x = xtest;
        int f = x != null ? x.focusedWindow() : 0;
        if (f != 0 && x != null && !x.isSteamWindow(f)) gameTopBeforeMenu = f;
        quitArmed = false;
        menuOpenedAt = now;
        m.show();
    }

    private long menuOpenedAt;

    private String fpsName(int fps) {
        return fps == 0 ? s(R.string.v_none) : s(R.string.v_fps, fps);
    }

    private String lsfgName() {
        int n = MainActivity.lsfgMode(this);
        if (n == 0) return s(R.string.v_off);
        // Without Lossless Scaling installed that is shown instead of a multiplier
        // Lossless Scaling이 설치되지 않았으면 배수 대신 그것을 표시함
        return MainActivity.losslessInstalled(this) ? s(R.string.v_times, n) : s(R.string.v_no_lossless);
    }

    /** LSFG off → 2x → 3x, for the next game start / LSFG 끔 → 2배 → 3배, 다음 게임 시작부터 */
    private void stepLsfg(int dir) {
        int[] modes = {0, 2, 3};
        int cur = MainActivity.lsfgMode(this), k = 0;
        for (int i = 0; i < modes.length; i++) if (modes[i] == cur) k = i;
        int n = modes[(k + dir + modes.length) % modes.length];
        String err = MainActivity.setLsfgMode(this, n);
        log("LSFG: " + (n == 0 ? "끔" : n + "배") + " (메뉴, 다음 게임 시작부터)" + (err != null ? " 설정 실패: " + err : ""));
        notice(s(R.string.notice_next_game));
    }

    /** Performance display off / strip / HUD card, kept for next time / 성능 표시 끔·띠·HUD 카드, 다음에도 유지 */
    private void stepHudMode(int dir) {
        hudMode = (hudMode + dir + 3) % 3;
        statsShown = hudMode == 1;
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().putInt(PREF_HUD, hudMode)
                .putBoolean(PREF_STATS, statsShown).apply();
        statsView.setVisibility(statsShown ? View.VISIBLE : View.GONE);
        hud.setVisibility(hudMode == 2 ? View.VISIBLE : View.GONE);
        log("성능 표시: " + hudModeName() + " (메뉴)");
    }

    private String hudModeName() {
        return s(hudMode == 0 ? R.string.v_off : hudMode == 1 ? R.string.v_hud_strip : R.string.v_hud_card);
    }

    /** App log over the screen on/off / 화면 위 앱 로그 켬/끔 */
    private void toggleLogView() {
        logScroll.setVisibility(logScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private int fsrTargetPref() {
        int t = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getInt(PREF_FSR_TARGET, 0);
        return t < 0 || t >= FSR_OUT_COUNT ? 0 : t;
    }

    private void setScale(int m) {
        view.setScaleMode(m);
        saveScale(m);
        log("확대 방식: " + scaleNames()[m] + " (메뉴)");
    }

    private void setFsrOut(int t) {
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().putInt(PREF_FSR_TARGET, t).apply();
        applyFsrTarget(t);
        log("FSR 출력: " + fsrOutNames()[t] + " (메뉴)");
    }

    /** Saved FSR sharpness as an index into FSR_SHARP / 저장된 FSR 선명도의 FSR_SHARP 번호 */
    private int fsrSharpIndex() {
        float v = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getFloat(PREF_FSR_SHARP, 0.2f);
        for (int i = 0; i < FSR_SHARP.length; i++) if (Math.abs(FSR_SHARP[i] - v) < 1e-3f) return i;
        return 1;
    }

    /** FSR 1 sharpness from the menu (RCAS stops: 0 strongest, each stop halves it), kept for next time
     *  메뉴에서 FSR 1 선명도(RCAS 단: 0이 가장 강하고 1단마다 절반), 다음에도 유지 */
    private void setFsrSharp(int i) {
        float v = FSR_SHARP[i];
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().putFloat(PREF_FSR_SHARP, v).apply();
        view.setFsrSharpness(v);
        log("FSR 선명도: " + fsrSharpNames()[i] + " (메뉴)");
    }

    private void stepFps(int dir) {
        int cur = fpsPref(), k = 0;
        for (int i = 0; i < FPS_CHOICES.length; i++) if (FPS_CHOICES[i] == cur) k = i;
        k = (k + dir + FPS_CHOICES.length) % FPS_CHOICES.length;
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().putInt(PREF_FPS, FPS_CHOICES[k]).apply();
        applyFps(FPS_CHOICES[k], true);
    }

    private void toggleMatchRate() {
        boolean on = !matchRatePref();
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().putBoolean(PREF_MATCH_RATE, on).apply();
        applyFps(fpsPref(), true);
    }

    /** End the game from the menu: first A arms, second A kills the game process
     *  메뉴에서 게임 끝내기: 첫 A는 준비, 두 번째 A가 게임 프로세스를 끝냄 */
    private void quitFromMenu() {
        if (!quitArmed) {
            quitArmed = true;
            return;
        }
        quitArmed = false;
        java.util.List<app.ezsteam.runtime.ChildProcessCounter.Proc> ps = lastProcs;
        app.ezsteam.runtime.ChildProcessCounter.Proc g = ps == null ? null : gameOf(ps);
        menu.hide();
        if (g == null) {
            log("메뉴: 끝낼 게임 없음");
            return;
        }
        log("메뉴: 게임 " + winExeName(g) + "(" + g.pid + ") 종료");
        android.os.Process.killProcess(g.pid);
    }

    /** Raise the Steam client's window over the game (Big Picture), game pad input held back
     *  Steam 클라이언트 창(빅픽처)을 게임 위로 올림. 게임 패드 입력은 멈춤 */
    private void raiseSteamOverGame() {
        XTestClient x = xtest;
        if (x == null || x.isClosed()) return;
        int f = x.focusedWindow();
        if (f != 0 && !x.isSteamWindow(f)) gameTopBeforeMenu = f;
        homeAt = SystemClock.elapsedRealtime();
        int sw = x.steamWindow();
        if (sw == 0) {
            log("메뉴: Steam 창을 찾지 못함");
            return;
        }
        log(String.format(Locale.ROOT, "메뉴: Steam 창 0x%x을 게임 위로 올림 (HOME으로 게임에 돌아감)", sw));
        x.activate(sw);
    }

    /** Main-thread handler for pad and gesture callbacks / 패드·제스처 콜백용 메인 스레드 핸들러 */
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    /** HOME held, and held long enough to go to Steam (main thread) / HOME 누름, Steam으로 넘길 만큼 오래 누름(메인 스레드) */
    private boolean homeHeld, homeLong;
    /** Hold time after which HOME is Steam's guide button / 이 시간 넘게 누르면 HOME이 Steam 가이드 버튼이 됨 */
    private static final long HOME_LONG_MS = 500;

    /** HOME held past HOME_LONG_MS: Steam now sees the guide button, until it is released (Steam's
     *  own chords such as guide + X keep working while it is held)
     *  HOME을 HOME_LONG_MS 넘게 누름: 뗄 때까지 Steam이 가이드 버튼을 받음(누른 동안 가이드 + X 같은 Steam 자체
     *  조합도 그대로 됨) */
    private final Runnable homeLongCheck = () -> {
        // Qualified: the fields are declared further down / 한정 이름: 필드가 아래쪽에 선언됨
        app.ezsteam.runtime.CompositorMenu m = this.menu;
        app.ezsteam.runtime.PadFile p = this.pad;
        if (!this.homeHeld || p == null || (m != null && m.isOpen())) return;
        homeLong = true;
        p.setGuidePass(true);
        log("HOME 길게: Steam 가이드 버튼으로 넘김");
    };

    private void homeDown() {
        homeHeld = true;
        homeLong = false;
        ui.removeCallbacks(homeLongCheck);
        ui.postDelayed(homeLongCheck, HOME_LONG_MS);
    }

    private void homeUp() {
        homeHeld = false;
        ui.removeCallbacks(homeLongCheck);
        if (homeLong) {
            homeLong = false;
            app.ezsteam.runtime.PadFile p = pad;
            if (p != null) p.setGuidePass(false);
            return;
        }
        onHome();
    }

    /** Menu row: one guide press for Steam (after the menu closed, so Steam reads the pad again)
     *  메뉴 줄: Steam에 가이드 한 번 누름(메뉴가 닫혀 Steam이 다시 패드를 읽은 뒤) */
    private void sendSteamGuide() {
        ui.postDelayed(() -> {
            app.ezsteam.runtime.PadFile p = pad;
            if (p == null) return;
            p.setGuidePulse(true);
            log("메뉴: Steam 가이드 버튼 보냄");
            ui.postDelayed(() -> p.setGuidePulse(false), 150);
        }, 250);
    }

    /**
     * HOME (GUIDE), short press: the app's own menu, game or not (user decision 2026-10-26: Steam
     * opening its library or Big Picture on HOME was not wanted; Steam's guide is a long press or a
     * menu row now). HOME again closes it. After "Steam 메뉴" raised the Steam window, HOME brings
     * the game back. Game and Steam client get no pad input while the menu is open.
     * HOME(GUIDE) 짧게: 게임 유무와 상관없이 앱 자체 메뉴(사용자 결정 2026-10-26: HOME에 Steam이 라이브러리나
     * 빅픽처를 여는 것은 원하지 않음. Steam 가이드는 이제 길게 누르기나 메뉴 줄). HOME을 다시 누르면 닫음. "Steam 메뉴"로
     * Steam 창을 올린 뒤에는 HOME이 게임을 되돌림. 메뉴가 열린 동안 게임과 Steam 클라이언트에는 패드 입력을 주지 않음
     */
    private void onHome() {
        app.ezsteam.runtime.CompositorMenu m = menu;
        if (m == null) return;
        if (m.isOpen()) {
            runOnUiThread(m::hide);
            return;
        }
        XTestClient x = xtest;
        if (steamMenuUp) {
            int g = gameTopBeforeMenu;
            steamMenuUp = false;
            applyGameMute();
            if (g != 0 && x != null) {
                log(String.format(Locale.ROOT, "HOME: 게임 창 0x%x로 돌아감", g));
                x.activate(g);
            }
            return;
        }
        java.util.List<app.ezsteam.runtime.ChildProcessCounter.Proc> ps = lastProcs;
        boolean game = ps != null && gameOf(ps) != null;
        int f = x != null ? x.focusedWindow() : 0;
        log(String.format(Locale.ROOT, "HOME: 게임 %s, 앞 창 0x%x%s", game ? "실행 중" : "없음", f,
                x != null && x.isSteamWindow(f) ? " (Steam)" : ""));
        if (f != 0 && x != null && !x.isSteamWindow(f)) gameTopBeforeMenu = f;
        runOnUiThread(() -> {
            quitArmed = false;
            m.show();
        });
    }

    /** A window was raised and focused (by Steam's request or by us) / 창을 올리고 포커스를 줌(Steam 요청 또는 우리) */
    private void onActivated(int win) {
        XTestClient x = xtest;
        if (x == null) return;
        boolean steam = x.isSteamWindow(win);
        if (steam && gameTopBeforeMenu != 0 && SystemClock.elapsedRealtime() - homeAt < 5000) {
            steamMenuUp = true;
        } else if (!steam) {
            steamMenuUp = false;
        }
        applyGameMute();
    }
    /** Sum and count of per-second new-frame rates since the last PERF line / 지난 PERF 줄 뒤 초당 새 프레임 수의 합과 개수 */
    private volatile float changedSum;
    private volatile int changedCount;
    /** Game controller state for the rootfs (Steam mode) / rootfs용 게임 컨트롤러 상태(Steam 모드) */
    private volatile app.ezsteam.runtime.PadFile pad;
    /** On-screen touch pad over the game ([패드]) / 게임 위 화면 터치 패드([패드]) */
    private TouchPadView touchPad;
    /** Controller that last sent input, logged once / 마지막으로 입력한 컨트롤러, 한 번만 기록 */
    private int padDeviceLogged = -1;
    private volatile java.util.Map<Integer, Float> cpuPct = new java.util.HashMap<>();
    /** Game frame hand-over mode shown in the top bar (Steam mode) / 위쪽 막대에 보일 게임 화면 전달 방식(Steam 모드) */
    private volatile String wsiName = "";
    /** Session start, for the elapsed time in the top bar / 세션 시작 시각, 위쪽 막대 경과 시간용 */
    private final long startMs = SystemClock.elapsedRealtime();
    /** Turn on PRoot's crash report (PROOT_CRASH_LOG) / PRoot 크래시 보고(PROOT_CRASH_LOG) 켜기 */
    private static final boolean CRASH_LOG = false;
    // Index = FramebufferView.SCALE_* (names in R.array.scale_modes) / 순서 = FramebufferView.SCALE_*(이름은 R.array.scale_modes)
    private static final int SCALE_COUNT = 4;

    private String[] scaleNames() {
        return getResources().getStringArray(R.array.scale_modes);
    }

    // Touch state / 터치 상태
    private boolean leftDown, twoFinger, scrolled;
    private long twoFingerStart;
    private float lastAvgY, scrollAcc;
    private int[] lastPos;
    // Physical mouse state: buttons held (Android BUTTON_* bits), wheel remainder, lock button
    // 실제 마우스 상태: 누른 버튼(안드로이드 BUTTON_* 비트), 휠 나머지, 잠금 버튼
    private int mouseButtons;
    private float wheelV, wheelH, relRestX, relRestY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        openSessionLog();
        // Foreground priority for the whole session, also while the user is in another app
        // 사용자가 다른 앱에 있는 동안에도 세션 전체를 포그라운드 우선순위로 유지함
        SessionService.start(this);
        // Volume keys change the media volume, which the game sound uses (AudioServer, USAGE_GAME)
        // 볼륨 키가 게임 소리가 쓰는 미디어 볼륨을 바꾸게 함(AudioServer, USAGE_GAME)
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        int w = getIntent().getIntExtra(EXTRA_W, 1280);
        int h = getIntent().getIntExtra(EXTRA_H, 720);
        String m = getIntent().getStringExtra(EXTRA_MODE);
        if ("steam".equals(m)) mode = "steam";
        // GPU path check (display.sh runs gpu-check.sh; otherwise like the display test)
        // GPU 경로 점검(display.sh가 gpu-check.sh를 돌림. 그 밖에는 화면 시험과 같음)
        if ("gpucheck".equals(m)) mode = "gpucheck";
        registerPcReceiver();

        // Layout (user request: buttons, log and numbers overlapped and moved; the numbers covered
        // the DXVK HUD in the top-left corner):
        //   top    : button row only, full width
        //   middle : log, under the buttons, hidden once the screen is running ([로그] shows it)
        //   bottom : status numbers, one small strip ([정보] hides it)
        //   [숨김] hides everything but a small [≡] corner button for playing
        // Touches outside the visible parts reach the screen view below.
        // 배치(사용자 요청: 버튼·로그·숫자가 겹치고 움직였으며, 숫자가 왼쪽 위 DXVK HUD를 가렸음):
        //   위    : 버튼 줄만, 화면 전체 폭
        //   가운데 : 로그, 버튼 아래. 화면이 돌기 시작하면 숨김([로그]로 표시)
        //   아래  : 상태 숫자, 작은 띠 하나([정보]로 숨김)
        //   [숨김]은 작은 [≡] 모서리 버튼만 남기고 모두 숨김(플레이용)
        // 보이는 부분 밖의 터치는 아래 화면 뷰로 감
        FrameLayout root = new FrameLayout(this);
        watchKeyboard(root);
        root.setBackgroundColor(Color.BLACK);
        view = new FramebufferView(this);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        // Touch pad between the screen and the button row: while shown it takes every touch, the
        // buttons above stay usable
        // 화면과 버튼 줄 사이의 터치 패드: 보이는 동안 모든 터치를 받고, 위의 버튼은 그대로 쓸 수 있음
        touchPad = new TouchPadView(this);
        touchPad.setVisibility(View.GONE);
        android.content.SharedPreferences pp = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        touchPad.setLook(pp.getFloat(PREF_PAD_SCALE, 1f), pp.getFloat(PREF_PAD_OPACITY, 0.45f));
        touchPad.setGap(pp.getFloat(PREF_PAD_GAP, 1f));
        touchPad.setSwapLeft(pp.getBoolean(PREF_PAD_SWAP, false));
        // After the log view exists (the layout load writes a log line) / 로그 뷰가 생긴 뒤(배치 읽기가 로그를 남김)
        root.post(this::loadPadLayout);
        // Keyboard and mouse bindings of the layout go to the X server
        // 배치의 키보드·마우스 바인딩은 X 서버로 감
        touchPad.setInput(new TouchPadView.Input() {
            @Override
            public void key(int xKeycode, boolean down) {
                XTestClient x = xtest;
                if (x != null) x.key(xKeycode, down);
            }

            @Override
            public void mouseButton(int xButton, boolean down) {
                XTestClient x = xtest;
                if (x != null) x.button(xButton, down);
            }

            @Override
            public void mouseMove(int dx, int dy) {
                XTestClient x = xtest;
                if (x != null) x.motionRelative(dx, dy);
            }
        });
        root.addView(touchPad, new FrameLayout.LayoutParams(-1, -1));
        // App menu over everything (HOME) / 모든 것 위의 앱 메뉴(HOME)
        menu = new app.ezsteam.runtime.CompositorMenu(this);
        menu.setListener(shown -> {
            menuOpen = shown;
            applyGameMute();
            log("메뉴 " + (shown ? "열림 (게임·Steam 패드 입력 멈춤)" : "닫힘"));
        });
        root.post(() -> {
            root.addView(menu, new FrameLayout.LayoutParams(-1, -1));
            buildMenu();
        });

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        // No button bar over the game (user decision 2026-10-26): everything is in the HOME menu,
        // opened with the pad's HOME, the corner [≡] button or the screen's back gesture.
        // Scale mode: the last one chosen, FSR 1 when none was chosen yet (user request 2026-10-24)
        // 게임 위 버튼 막대 없음(사용자 결정 2026-10-26): 모두 HOME 메뉴에 있음. 패드 HOME, 모서리 [≡] 버튼,
        // 화면 뒤로 가기로 엶. 확대 방식: 마지막으로 고른 것, 아직 고른 적 없으면 FSR 1(사용자 요청 2026-10-24)
        int savedScale = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getInt(PREF_SCALE, FramebufferView.SCALE_FSR);
        if (savedScale < 0 || savedScale >= SCALE_COUNT) savedScale = FramebufferView.SCALE_FSR;
        view.setScaleMode(savedScale);
        view.setFsrSharpness(getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getFloat(PREF_FSR_SHARP, 0.2f));
        applyFsrTarget(getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getInt(PREF_FSR_TARGET, 0));
        statsShown = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getBoolean(PREF_STATS, true);
        // Three-way display mode; the old on/off value maps to strip/off / 세 단계 표시 방식. 예전 켬/끔 값은 띠/끔으로
        hudMode = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getInt(PREF_HUD, statsShown ? 1 : 0);
        statsShown = hudMode == 1;

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextColor(Color.WHITE);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        logView.setBackgroundColor(0xB0000000);
        logScroll.addView(logView);
        // Ends above the status strip so the two never overlap / 상태 띠 위에서 끝나 둘이 겹치지 않음
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.bottomMargin = (int) (32 * getResources().getDisplayMetrics().density);
        overlay.addView(logScroll, lp);

        statsView = new TextView(this);
        statsView.setTextColor(Color.WHITE);
        statsView.setTypeface(Typeface.MONOSPACE);
        statsView.setTextSize(11);
        statsView.setBackgroundColor(0x99000000);
        statsView.setText(w + "x" + h + " " + s(R.string.stats_starting));
        statsView.setVisibility(statsShown ? View.VISIBLE : View.GONE);
        root.addView(statsView, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
        // HUD card, top left; it only shows values the monitor thread hands in (no own work)
        // HUD 카드, 왼쪽 위. 모니터 스레드가 넘긴 값만 보여 줌(스스로 하는 일 없음)
        hud = new app.ezsteam.runtime.PerfHud(this);
        float dens = getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams((int) (340 * dens), -2, Gravity.TOP | Gravity.START);
        hlp.leftMargin = hlp.topMargin = (int) (8 * dens);
        hud.setVisibility(hudMode == 2 ? View.VISIBLE : View.GONE);
        root.addView(hud, hlp);

        // First-run preparation banner, top centre, shown only while an installer runs; it takes
        // no touches (they reach the screen below)
        // 첫 실행 준비 안내 띠, 위쪽 가운데. 설치 프로그램이 도는 동안만 보임. 터치는 받지 않음(아래 화면으로 감)
        prepView = new TextView(this);
        prepView.setTextColor(Color.WHITE);
        prepView.setTextSize(14);
        prepView.setGravity(Gravity.CENTER);
        int prepPad = (int) (10 * getResources().getDisplayMetrics().density);
        prepView.setPadding(prepPad * 2, prepPad, prepPad * 2, prepPad);
        prepView.setClickable(false);
        prepView.setFocusable(false);
        // Spinner beside the text, so a long step visibly keeps going / 글 옆 스피너: 긴 단계도 진행 중임이 보이게 함
        prepBox = new LinearLayout(this);
        prepBox.setOrientation(LinearLayout.HORIZONTAL);
        prepBox.setGravity(Gravity.CENTER_VERTICAL);
        prepBox.setBackgroundColor(0xCC000000);
        prepBox.setPadding(prepPad, 0, 0, 0);
        prepBox.setClickable(false);
        prepBox.setFocusable(false);
        prepBox.setVisibility(View.GONE);
        prepBox.addView(new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleSmall));
        prepBox.addView(prepView);
        FrameLayout.LayoutParams prepLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        prepLp.topMargin = (int) (56 * getResources().getDisplayMetrics().density);
        root.addView(prepBox, prepLp);

        // Centre progress card for Steam's start and self update (the X screen stays black until
        // Steam opens its first window); hidden when that window appears
        // Steam 시작과 자체 갱신용 가운데 진행 카드(Steam이 첫 창을 열 때까지 X 화면은 검은색임). 그 창이
        // 나타나면 숨김
        float dp = getResources().getDisplayMetrics().density;
        setupPanel = new LinearLayout(this);
        setupPanel.setOrientation(LinearLayout.VERTICAL);
        setupPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        setupPanel.setBackgroundColor(0xE0181A1F);
        int sp = (int) (20 * dp);
        setupPanel.setPadding(sp, sp, sp, sp);
        setupPanel.setClickable(false);
        setupPanel.setFocusable(false);
        setupPanel.setVisibility(View.GONE);
        setupTitle = new TextView(this);
        setupTitle.setTextColor(Color.WHITE);
        setupTitle.setTextSize(18);
        setupTitle.setGravity(Gravity.CENTER);
        setupPanel.addView(setupTitle);
        setupBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        setupBar.setMax(100);
        setupBar.setIndeterminate(true);
        LinearLayout.LayoutParams setupBarLp = new LinearLayout.LayoutParams((int) (320 * dp), -2);
        setupBarLp.topMargin = (int) (12 * dp);
        setupBarLp.bottomMargin = (int) (8 * dp);
        setupPanel.addView(setupBar, setupBarLp);
        setupDetail = new TextView(this);
        setupDetail.setTextColor(0xFFB8BCC6);
        setupDetail.setTextSize(13);
        setupDetail.setGravity(Gravity.CENTER);
        setupPanel.addView(setupDetail);
        root.addView(setupPanel, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        buildLaunchCover(root);

        // Corner button that opens the HOME menu by touch (no bar any more); small and faint so it
        // covers little of the Steam window / 터치로 HOME 메뉴를 여는 모서리 버튼(이제 막대 없음). Steam 창을
        // 적게 가리도록 작고 흐림
        showButton = new Button(this);
        showButton.setText("≡");
        showButton.setAllCaps(false);
        showButton.setFocusable(false);
        showButton.setAlpha(0.35f);
        showButton.setTextSize(13);
        showButton.setMinWidth(0);
        showButton.setMinimumWidth(0);
        showButton.setMinHeight(0);
        showButton.setMinimumHeight(0);
        int cornerPad = (int) (8 * dp);
        showButton.setPadding(cornerPad * 2, cornerPad / 2, cornerPad * 2, cornerPad / 2);
        showButton.setContentDescription(s(R.string.menu_title));
        showButton.setOnClickListener(v -> openMenu());
        // Hidden for now (user request 2026-10-27: test the touch gestures instead); kept for the
        // dedicated compositor later / 지금은 숨김(사용자 요청 2026-10-27: 대신 터치 제스처를 시험). 나중 전용
        // 컴포지터 때를 위해 남겨 둠
        showButton.setVisibility(View.GONE);
        root.addView(showButton, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
        setContentView(root);
        hideSystemBars();

        view.setStatsListener((fps, ms, changed, fw, fh) -> {
            // Per-minute average of new frames for the PERF line (game frames with the direct path,
            // changed X screens otherwise) / PERF 줄용 분당 새 프레임 평균(직접 전달이면 게임 프레임, 아니면
            // 바뀐 X 화면)
            changedSum += changed;
            changedCount++;
            String err = view.shaderError;
            // Logged again when the text changes (FSR can fail later, at its first use)
            // 문구가 바뀌면 다시 기록함(FSR은 처음 쓸 때 늦게 실패할 수 있음)
            if (err != null && !err.equals(shaderLogged)) {
                shaderLogged = err;
                log("확대 셰이더 오류(기본: 선형, FSR: 바이큐빅+선명으로 대체): " + err);
            }
            // Second line: elapsed time, frame hand-over mode and CPU use, to compare runs of equal length
            // 둘째 줄: 경과 시간, 화면 전달 방식, CPU 사용률. 같은 길이의 실행끼리 비교하기 위함
            long sec = (SystemClock.elapsedRealtime() - startMs) / 1000;
            runOnUiThread(() -> statsView.setText(String.format(Locale.ROOT,
                    "%dx%d  화면 %.0ffps  내용변경 %.0f/s  업로드 %.1fms  키 %s  자식 %d(최대 %d)  여유 %.1fGB\n"
                            + "경과 %d:%02d  %s%s%s",
                    fw, fh, fps, changed, ms, lastKey, procNow, procMax, memFreeMb / 1024f,
                    sec / 60, sec % 60, wsiName.isEmpty() ? "" : "게임 화면 " + wsiName + "  ", cpuText,
                    audio == null ? "" : "  소리 " + audio.activeStreams())));
        });
        // Soft keyboard text arrives here as key events / 소프트 키보드 텍스트는 키 이벤트로 바뀌어 여기로 옴
        view.setKeyListener(this::sendKey);
        // Characters with no key (Korean and others) go in through a spare X keycode
        // 키가 없는 글자(한글 등)는 X 예비 키코드로 들어감
        view.setCharListener(cp -> {
            XTestClient x = xtest;
            if (x != null) x.typeChar(cp);
            lastKey = new String(Character.toChars(cp));
        });
        view.setOnTouchListener((v, e) -> onTouch(e));
        // Physical mouse: hover moves, wheel and button changes (not only while a button is held),
        // and relative movement while locked (pointer capture)
        // 실제 마우스: 이동(버튼을 누르지 않아도), 휠, 버튼 변화, 잠금(포인터 캡처) 중 상대 이동
        view.setOnGenericMotionListener((v, e) -> {
            return e.isFromSource(android.view.InputDevice.SOURCE_MOUSE) && onMouse(e, false);
        });
        view.setOnCapturedPointerListener((v, e) -> onMouse(e, true));
        // Android's own arrow is hidden over the screen: the X cursor drawn in the frame is the one that counts
        // 화면 위에서는 안드로이드 화살표를 숨김: 화면에 그려지는 X 커서가 실제 위치임
        view.setPointerIcon(android.view.PointerIcon.getSystemIcon(this, android.view.PointerIcon.TYPE_NULL));
        view.requestFocus();

        log("app build=" + BuildConfig.GIT_SHA + ", 요청 해상도 " + w + "x" + h);
        new Thread(() -> start(w, h), "ezs-display").start();
        startMonitor();
    }

    /** Saved scale mode (FramebufferView.SCALE_*) / 저장된 확대 방식(FramebufferView.SCALE_*) */
    private static final String PREF_SCALE = "view.scale";

    private void saveScale(int m) {
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().putInt(PREF_SCALE, m).apply();
    }

    private static final String PREF_FSR_SHARP = "fsr.sharp";
    private static final float[] FSR_SHARP = {0f, 0.2f, 0.5f, 1f, 2f, -1f};
    /** FSR sharpness names (R.array.fsr_sharp, same order as FSR_SHARP) / FSR 선명도 이름(R.array.fsr_sharp, FSR_SHARP와 같은 순서) */
    private String[] fsrSharpNames() {
        return getResources().getStringArray(R.array.fsr_sharp);
    }

    /** FSR output: 0 = up to the display, 1 = up to 1920x1200 then linear, 2 = up to 1920x1080 then linear
     *  FSR 출력: 0 = 화면 크기까지, 1 = 1920x1200까지 뒤 선형, 2 = 1920x1080까지 뒤 선형 */
    private static final String PREF_FSR_TARGET = "fsr.target";

    private void applyFsrTarget(int i) {
        if (i == 1) view.setFsrTarget(1920, 1200);
        else if (i == 2) view.setFsrTarget(1920, 1080);
        else view.setFsrTarget(0, 0);
    }

    /** Immersive full screen (system bars hidden) / 몰입형 전체 화면(시스템 막대 숨김) */
    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    /** Append a log line, keeping the last LOG_LINES / 로그 줄 추가, 마지막 LOG_LINES줄만 유지 */
    /**
     * The app log of this session as a file too (~/ezs-logs/app-session.log in the rootfs); the
     * previous session's file is kept as app-session-prev.log. Both go into the report with the
     * other ~/ezs-logs files. Why: a report saved after reopening the app had only the new
     * session; what happened when a game was started before was lost (2026-10-18).
     * 이 세션의 앱 로그를 파일로도 남김(rootfs의 ~/ezs-logs/app-session.log). 이전 세션 파일은
     * app-session-prev.log로 보관함. 둘 다 다른 ~/ezs-logs 파일과 함께 제보에 들어감. 이유: 앱을 다시 연
     * 뒤 저장한 제보에는 새 세션만 있어, 그 전에 게임을 시작했을 때의 일이 사라졌음(2026-10-18)
     */
    private java.io.Writer sessionLog;
    private long sessionLogBytes;
    /** Cap of the session file / 세션 파일 상한 */
    private static final long SESSION_LOG_MAX = 16L << 20;

    private void openSessionLog() {
        try {
            File dir = new File(RootfsInstaller.rootfsDir(this), "home/steam/ezs-logs");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File cur = new File(dir, "app-session.log");
            File prev = new File(dir, "app-session-prev.log");
            if (cur.isFile()) {
                if (prev.exists() && !prev.delete()) return;
                if (!cur.renameTo(prev)) return;
            }
            sessionLog = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(cur), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            sessionLog = null;
        }
    }

    private void log(String s) {
        // HUD facts from the session's own lines (cheap prefix checks) / 세션 자체 줄에서 HUD 정보(가벼운 앞부분 비교)
        // "장치 <name>, API <ver>" only; the next line "장치 확장 ..." (extension list) took its place (2026-10-30)
        // "장치 <이름>, API <버전>"만. 다음 줄 "장치 확장 ..."(확장 목록)이 그 자리를 차지했음(2026-10-30)
        if (s.startsWith("INFO present : 장치 ") && s.contains(", API ")) hudRenderer = s.substring("INFO present : 장치 ".length());
        else if (s.startsWith("OK   xvfb : ")) hudXserver = s.contains("서버 xwayland") ? "Xwayland" : "Xvfb";
        // First Steam window after a (re)start: the centre progress card is no longer needed
        // (re)시작 뒤 첫 Steam 창: 가운데 진행 카드가 더는 필요 없음
        if (!steamWindowSeen && s.startsWith("X 포커스") && s.contains("(새 창)")) {
            steamWindowSeen = true;
            hideSetup();
        }
        launchWatch(s);
        runOnUiThread(() -> {
            logLines.addLast(s);
            // Time stamp only in the saved copy / 시각은 저장용 사본에만 붙임
            String stamped = new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT)
                    .format(new java.util.Date()) + " " + s;
            saveLines.addLast(stamped);
            java.io.Writer w = sessionLog;
            if (w != null && sessionLogBytes < SESSION_LOG_MAX) {
                try {
                    w.write(stamped);
                    w.write('\n');
                    w.flush();
                    sessionLogBytes += stamped.length() + 1;
                } catch (IOException e) {
                    sessionLog = null;
                }
            }
            while (saveLines.size() > SAVE_LINES) saveLines.removeFirst();
            if (logLines.size() > LOG_LINES) {
                while (logLines.size() > LOG_LINES) logLines.removeFirst();
                logView.setText(String.join("\n", logLines) + "\n");
            } else {
                logView.append(s + "\n");
            }
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    /**
     * Start display.sh through PRoot, wait for READY, then open the screen file and the XTEST input.
     * PRoot로 display.sh를 띄우고 READY를 기다린 뒤 화면 파일과 XTEST 입력을 엶
     */
    private void start(int w, int h) {
        try {
            if (RootfsInstaller.installedVersion(this).isEmpty()) {
                log("먼저 rootfs 설치");
                return;
            }
            int n = RootfsInstaller.syncScripts(this);
            if (n > 0) log("스크립트 동기화 " + n + "개");
            killLeftovers();
            GlibcLauncher.Config c = new GlibcLauncher.Config();
            c.rootfs = RootfsInstaller.rootfsDir(this);
            c.nativeLibDir = new File(getApplicationInfo().nativeLibraryDir);
            c.dataDir = RootfsInstaller.dataDir(this);
            if (!DriverInstaller.apply(this, c, "turnip")) {
                log("먼저 [드라이버 설치]");
                return;
            }
            // Fake ids: the X server needs setuid()/setgid() before xkbcomp (see drawtest.sh).
            // Test mode: fake root. Steam mode: fake user steam 1000:1000 (Steam refuses root).
            // 가짜 id: X 서버는 xkbcomp 전에 setuid()/setgid()가 필요함(drawtest.sh 참고).
            // test 모드: 가짜 root. steam 모드: 가짜 사용자 steam 1000:1000(Steam은 root 거부)
            if ("steam".equals(mode)) {
                c.changeId = "1000:1000";
                // PRoot crash report (tools/patches) stays off: FEX handles many SIGBUS/SIGSEGV
                // signals itself, and reporting each one floods the log and slows the game.
                // Set CRASH_LOG to true only when a crash location is needed.
                // PRoot 크래시 보고(tools/patches)는 꺼 둠: FEX는 SIGBUS/SIGSEGV를 많이 스스로 처리하므로
                // 하나하나 보고하면 로그가 넘치고 게임이 느려짐. 크래시 위치가 필요할 때만 CRASH_LOG를 true로
                if (CRASH_LOG) c.env.put("PROOT_CRASH_LOG", "1");
                // Fewer Steam web helper processes (option on the main screen, on by default)
                // Steam 웹 도우미 프로세스 줄이기(메인 화면 옵션, 기본 켬)
                String cefFlags = MainActivity.cefSlim(this) ? MainActivity.CEF_SLIM_FLAGS : "";
                // Steam UI GPU (admin option, ANGLE Vulkan by default) / Steam 화면 GPU(관리자 옵션, 기본 ANGLE Vulkan)
                int gpuMode = MainActivity.steamGpu(this);
                if (gpuMode > 0) {
                    c.env.put("EZS_STEAM_GPU", "1");
                    cefFlags = (cefFlags + " " + MainActivity.STEAM_GPU_MODES[gpuMode][1]).trim();
                    log("Steam 화면 GPU(시험) " + MainActivity.STEAM_GPU_MODES[gpuMode][0] + ": " + cefFlags);
                }
                if (!cefFlags.isEmpty()) c.env.put("EZS_CEF_FLAGS", cefFlags);
                // Big Picture at start (steam-run.sh adds -gamepadui) / 시작할 때 빅픽처(steam-run.sh가 -gamepadui를 붙임)
                if (MainActivity.bigPicture(this)) c.env.put("EZS_STEAM_BIGPICTURE", "1");
                // Test switch: fewer HID discovery rounds in the Steam client (steam-run.sh)
                // 시험 스위치: Steam 클라이언트의 HID 찾기 줄이기(steam-run.sh)
                if (MainActivity.steamHidLow(this)) c.env.put("EZS_STEAM_HID_LOW", "1");
                // State of the switch in the log, so a report shows whether it was on
                // 스위치 상태를 로그에 남겨 제보에서 켜졌는지 보이게 함
                log("시험 스위치: Steam HID 찾기 " + (MainActivity.steamHidLow(this) ? "줄임" : "기본(끔)"));
                // Phone language for the Steam client language (steam-language.py)
                // Steam 클라이언트 언어용 휴대폰 언어(steam-language.py)
                c.env.put("EZS_LANG", Locale.getDefault().toLanguageTag());
                // Client file I/O statistics (admin option) / 클라이언트 파일 입출력 통계(관리자 옵션)
                // plus PRoot's own count of the system calls it stops for (PROOT_STAT lines)
                // PRoot가 멈춰 처리하는 시스템 호출 집계도 함께 켬(PROOT_STAT 줄)
                if (MainActivity.ioStat(this)) {
                    c.env.put("EZS_IOSTAT", "1");
                    c.env.put("PROOT_SYSSTAT", "60");
                    log("IO 통계 켬: 1분마다 ezs-io:, PROOT_STAT 줄");
                }
                // Address order back to glibc's default (an earlier build's IPv4-first gai.conf)
                // 주소 순서를 glibc 기본값으로 되돌림(이전 빌드가 쓴 IPv4 우선 gai.conf)
                try {
                    if (RootfsInstaller.removeOwnGaiConf(this)) log("네트워크: 이전 빌드의 IPv4 우선 설정(gai.conf) 지움 → 기본값");
                } catch (IOException e) {
                    log("네트워크: gai.conf 지우기 실패 " + e);
                }
                // A VPN on the device decides where Steam's servers are picked (sign-in and
                // download servers followed the VPN to Europe, about 3 Mbps, 2026-10-18)
                // 기기의 VPN이 Steam 서버 위치를 정함(로그인·다운로드 서버가 VPN을 따라 유럽으로 잡혀 약
                // 3 Mbps였음, 2026-10-18)
                if (vpnActive()) log("네트워크: VPN 켜짐 → Steam 로그인·다운로드 서버가 VPN 위치로 잡힐 수 있음");
                // EZlator's Steam library as an extra library of the Linux Steam client, so a game
                // already downloaded there is not downloaded again (steam-run.sh registers it)
                // EZlator Steam 라이브러리를 리눅스 Steam 클라이언트의 추가 라이브러리로 연결해, 그곳에
                // 이미 받은 게임을 다시 받지 않게 함(steam-run.sh가 등록함)
                File sharedLib = MainActivity.sharedLibrary(this) ? app.ezsteam.runtime.SharedStorage.steamLibrary(this) : null;
                if (sharedLib != null) {
                    c.binds.add(new String[]{sharedLib.getAbsolutePath(), app.ezsteam.runtime.SharedStorage.GUEST_LIBRARY});
                    c.env.put("EZS_SHARED_LIBRARY", app.ezsteam.runtime.SharedStorage.GUEST_LIBRARY);
                    log("공유 Steam 라이브러리: " + sharedLib + " -> " + app.ezsteam.runtime.SharedStorage.GUEST_LIBRARY);
                } else {
                    log("공유 Steam 라이브러리 없음 (" + (MainActivity.sharedLibrary(this) ? "저장소 권한 없음" : "시작 화면에서 끔") + ")");
                }
                // Game frame hand-over (main screen option, see MainActivity.WSI_MODES); replaces the
                // driver package's MESA_VK_WSI_DEBUG=sw. Steam passes these on to Proton and the game.
                // 게임 화면 전달 방식(메인 화면 옵션, MainActivity.WSI_MODES 참고). 드라이버 묶음의
                // MESA_VK_WSI_DEBUG=sw를 대신함. Steam이 Proton과 게임에 그대로 넘김
                String[] wsi = MainActivity.WSI_MODES[MainActivity.wsiIndex(MainActivity.wsiMode(this))];
                c.env.put("MESA_VK_WSI_DEBUG", wsi[2]);
                c.env.put("MESA_VK_WSI_PRESENT_MODE", wsi[3]);
                if (!wsi[4].isEmpty()) c.env.put("DXVK_CONFIG", wsi[4]);
                // DXVK overlay drawn into the game frame: shows whether frames are produced at all
                // 게임 화면 안에 그리는 DXVK 표시줄: 화면이 만들어지고 있는지 바로 보임
                if (MainActivity.dxvkHud(this)) c.env.put("DXVK_HUD", MainActivity.DXVK_HUD_VALUE);
                log("게임 화면 " + wsi[1]);
                wsiName = wsi[1];
            } else {
                c.rootId = true;
            }
            c.display = ":" + DISPLAY;
            log("모드 " + mode);
            applyDisplayPathSwitches(c);
            // Sound of the programs in the rootfs (ALSA plugin -> socket -> AudioTrack), started
            // before them so the first sound already finds the socket
            // rootfs 프로그램의 소리(ALSA 플러그인 -> 소켓 -> AudioTrack). 첫 소리가 소켓을 찾도록 먼저 시작함
            if ("steam".equals(mode)) {
                try {
                    audio = app.ezsteam.runtime.AudioServer.start(c.rootfs, this::log);
                    log("AUDIO 대기 " + app.ezsteam.runtime.AudioServer.GUEST_SOCKET);
                } catch (IOException | RuntimeException e) {
                    log("AUDIO 시작 실패 (소리 없이 계속): " + e);
                }
                // Zero-copy frame path check: listens before steam-run.sh starts the probe
                // 0복사 프레임 경로 확인: steam-run.sh가 probe를 시작하기 전에 기다림
                try {
                    present = app.ezsteam.runtime.PresentProbe.start(c.rootfs, this::log);
                    // Window places for game frames come from our X connection
                    // 게임 프레임의 창 위치는 우리 X 연결에서 얻음
                    present.setWindowRects(new app.ezsteam.runtime.PresentProbe.WindowRects() {
                        @Override
                        public void query(int win, app.ezsteam.runtime.PresentProbe.RectCallback cb) {
                            XTestClient x = xtest;
                            if (x != null && !x.isClosed()) x.windowRect(win, cb);
                        }

                        @Override
                        public void topLevel(int win, java.util.function.IntConsumer cb) {
                            XTestClient x = xtest;
                            if (x != null && !x.isClosed()) x.topLevelOf(win, cb);
                        }

                        @Override
                        public void above(int top, java.util.function.Consumer<int[]> cb) {
                            XTestClient x = xtest;
                            if (x != null && !x.isClosed()) x.windowsAbove(top, cb);
                        }
                    });
                    // Windows over the game (Steam menu from HOME, dialogs) are drawn over the game
                    // frame; while they cover a quarter of the screen or more the game gets no pad
                    // input, like Steam's own overlay (the Steam client still gets it)
                    // 게임 위의 창(HOME으로 연 Steam 메뉴, 대화상자)은 게임 프레임 위에 그림. 그 창들이 화면의
                    // 4분의 1 이상을 덮는 동안 게임에는 패드 입력을 주지 않음(Steam 자체 오버레이처럼, Steam
                    // 클라이언트는 계속 받음)
                    present.setOverlayListener(rects -> {
                        view.setOverlays(rects);
                        long area = 0;
                        for (int i = 0; i + 3 < rects.length; i += 4) area += (long) rects[i + 2] * rects[i + 3];
                        XTestClient x = xtest;
                        long screen = x != null ? (long) x.screenWidth * x.screenHeight : 1280L * 800L;
                        overlayMuted = area * 4 >= screen;
                        applyGameMute();
                    });
                    log("PRESENT 대기 " + app.ezsteam.runtime.PresentProbe.GUEST_SOCKET);
                    // Frame limit (file for EZ Proton, live for the layer) and the compression test
                    // switch, before any game starts / 프레임 제한(EZ Proton용 파일, 레이어에는 바로)과 압축 시험
                    // 스위치. 게임이 시작하기 전에 둠
                    applyFps(fpsPref(), true);
                    app.ezsteam.runtime.PresentProbe.setCompress(MainActivity.ahbCompress(this));
                    if (MainActivity.ahbCompress(this)) log("PRESENT AHB 압축(시험) 켬: 시작 확인 C 결과가 압축이면 다음 게임부터");
                } catch (IOException | RuntimeException e) {
                    log("PRESENT 시작 실패 (확인 없이 계속): " + e);
                }
                // Game controller: the app writes the Android controller state here, the rootfs
                // library native/ezspad turns it into a virtual Xbox 360 pad inside Wine
                // 게임 컨트롤러: 앱이 안드로이드 컨트롤러 상태를 여기에 쓰고, rootfs 라이브러리
                // native/ezspad가 Wine 안의 가상 Xbox 360 패드로 바꿈
                try {
                    pad = app.ezsteam.runtime.PadFile.open(c.rootfs);
                    // HOME (GUIDE) from the touch pad or a real controller / 터치 패드나 실제 컨트롤러의 HOME(GUIDE)
                    pad.setButtonListener(new app.ezsteam.runtime.PadFile.ButtonListener() {
                        @Override
                        public void pressed(int b) {
                            // HOME: decided on release (short = app menu) or after a hold (long =
                            // Steam's guide). Posted, never run inside the pad write
                            // HOME: 뗄 때(짧게 = 앱 메뉴) 또는 누르고 있을 때(길게 = Steam 가이드) 정함. 패드 쓰기
                            // 안에서 돌리지 않고 넘김
                            if (b == app.ezsteam.runtime.PadFile.GUIDE) {
                                ui.post(DisplayActivity.this::homeDown);
                                return;
                            }
                            app.ezsteam.runtime.CompositorMenu mm = menu;
                            if (mm != null) mm.onButton(b);
                        }

                        @Override
                        public void released(int b) {
                            if (b == app.ezsteam.runtime.PadFile.GUIDE) ui.post(DisplayActivity.this::homeUp);
                        }
                    });
                    final app.ezsteam.runtime.PadFile opened = pad;
                    runOnUiThread(() -> touchPad.setPad(opened));
                    log("PAD 공유 파일 " + app.ezsteam.runtime.PadFile.GUEST_PATH);
                } catch (IOException | RuntimeException e) {
                    log("PAD 공유 파일 실패 (컨트롤러 없이 계속): " + e);
                }
            }
            sessionConfig = c;
            watchNetwork(c.rootfs);
            Process p = GlibcLauncher.startScript(c, "/opt/ezsteam/display.sh",
                    Arrays.asList(Integer.toString(w), Integer.toString(h), mode), null, null);
            proc = p;
            if (finishing) {
                stopProcess();
                return;
            }

            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log(line);
                    if (line.startsWith("READY ")) onReady(c, line);
                    if (line.startsWith("NEED deps")) installDeps(c);
                    onSessionLine(line);
                }
            }
            log("exit=" + p.waitFor() + " (자식 프로세스 최대 " + procMax + "개, 여유 메모리 "
                    + memFreeMb + " MB)");
        } catch (Throwable e) {
            log("ERROR " + e);
        }
        if (fb == null) runOnUiThread(() -> logScroll.setVisibility(View.VISIBLE));
    }

    /**
     * Install the libraries steam-run.sh asked for ("NEED deps"): steam-deps.sh install in a
     * second PRoot as fake root (apt needs root; the Steam session runs as user steam), then
     * remove the request file so steam-run.sh goes on. Output goes to the app log as "DEPS".
     * steam-run.sh가 요청한 라이브러리 설치("NEED deps"): 두 번째 PRoot에서 가짜 root로 steam-deps.sh
     * install을 돌림(apt는 root가 필요하고 Steam 세션은 사용자 steam으로 돎). 그다음 요청 파일을 지워
     * steam-run.sh가 계속하게 함. 출력은 앱 로그에 "DEPS"로 남김
     */
    private void installDeps(GlibcLauncher.Config session) {
        new Thread(() -> {
            File req = new File(session.rootfs, "tmp/ezs-deps-request");
            try {
                GlibcLauncher.Config c = new GlibcLauncher.Config();
                c.rootfs = session.rootfs;
                c.nativeLibDir = session.nativeLibDir;
                c.dataDir = session.dataDir;
                // Fake root, as [Steam 설치] uses: without it apt/dpkg ran as the app's own id and
                // every install failed (2026-10-18)
                // [Steam 설치]와 같은 가짜 root: 없으면 apt/dpkg가 앱 자체 id로 돌아 설치가 모두 실패했음
                // (2026-10-18)
                c.rootId = true;
                log("DEPS 필요한 라이브러리 설치 시작 (apt, 네트워크 사용)");
                showSetup(s(R.string.setup_deps), -1, s(R.string.setup_deps_detail));
                Process p = GlibcLauncher.startScript(c, "/opt/ezsteam/steam-deps.sh",
                        Arrays.asList("install"), null, null);
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null) log("DEPS " + l);
                }
                log("DEPS 끝 exit=" + p.waitFor());
                // Back to the Steam start card / Steam 시작 카드로 돌아감
                if (!steamWindowSeen) showSetup(s(R.string.setup_steam_start), -1, "");
            } catch (IOException | InterruptedException | RuntimeException e) {
                log("DEPS 실패: " + e);
            } finally {
                if (req.exists() && !req.delete()) log("DEPS 요청 파일을 지우지 못함: " + req);
            }
        }, "ezs-deps").start();
    }

    /** "READY w h /tmp/ezs-fb/Xvfb_screen0": map the screen and connect input
     *  "READY w h /tmp/ezs-fb/Xvfb_screen0": 화면을 매핑하고 입력을 연결함 */
    private void onReady(GlibcLauncher.Config c, String line) {
        String[] f = line.trim().split("\\s+");
        // Guest path to real path: the guest "/" is the rootfs folder / guest 경로 → 실제 경로: guest "/"는 rootfs 폴더
        File screen = new File(c.rootfs, f[3].substring(1));
        try {
            fb = XvfbFramebuffer.open(screen);
            view.setFramebuffer(fb);
            log("화면 파일 " + fb.width + "x" + fb.height + " " + fb.bitsPerPixel + "bpp, 줄 " + fb.bytesPerLine + " B");
        } catch (IOException e) {
            log("화면 파일 열기 실패: " + e);
        }
        try {
            File sock = new File(c.rootfs, "tmp/.X11-unix/X" + DISPLAY);
            xtest = XTestClient.connect(sock, this::log);
            // Raises done as a window manager (Steam menu on HOME) / 창 관리자처럼 한 올리기(HOME의 Steam 메뉴)
            xtest.setActivationListener(this::onActivated);
            xtest.setBigPictureListener(this::onBigPicture);
            log("XTEST " + xtest.xtestMajor + "." + xtest.xtestMinor + " 연결, 서버 " + xtest.vendor
                    + " " + xtest.screenWidth + "x" + xtest.screenHeight);
        } catch (IOException e) {
            log("X 입력 연결 실패: " + e);
        }
        if ("steam".equals(mode)) {
            showSetup(s(R.string.setup_steam_start), -1, s(R.string.setup_steam_first));
            startSteamLogTail(c.rootfs);
        }
        // The log is hidden once the screen runs, in both modes ([로그] shows it, [로그 저장] keeps it)
        // 화면이 돌기 시작하면 두 모드 모두 로그를 숨김([로그]로 표시, [로그 저장]으로 보관)
        runOnUiThread(() -> logScroll.setVisibility(View.GONE));
    }

    // ---- touch: absolute pointer, like a touch screen / 터치: 터치스크린처럼 절대 위치 포인터

    private boolean onTouch(MotionEvent e) {
        // Mouse presses also come here as touches: handle them as a mouse (button per Android button)
        // 마우스 누름도 터치로 여기에 옴: 마우스로 처리함(안드로이드 버튼별로 X 버튼)
        if (e.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) return onMouse(e, false);
        XTestClient x = xtest;
        if (x == null) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int[] p = view.toScreen(e.getX(), e.getY());
                if (p == null) return true;
                x.motion(p[0], p[1]);
                // Focus the window under the finger before the click (no window manager here)
                // 클릭 전에 손가락 아래 창에 포커스를 줌(여기에는 창 관리자가 없음)
                x.focusUnderPointer(true);
                x.button(1, true);
                leftDown = true;
                twoFinger = false;
                lastPos = p;
                // Input diagnosis: where each tap lands in X / 입력 진단: 탭이 X의 어디에 닿았는지
                log("TOUCH " + p[0] + "," + p[1]);
                // Remembered so the soft keyboard does not cover the field just tapped
                // 소프트 키보드가 방금 누른 입력란을 가리지 않도록 기억함
                lastTapRawY = e.getRawY();
                if (imeHeight > 0) keepTapAboveKeyboard();
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                // Second finger: cancel the left button, start right-click / wheel detection
                // 두 번째 손가락: 왼쪽 버튼을 취소하고 오른쪽 클릭·휠 판정을 시작함
                if (e.getPointerCount() == 2) {
                    if (leftDown) {
                        x.button(1, false);
                        leftDown = false;
                    }
                    twoFinger = true;
                    scrolled = false;
                    scrollAcc = 0;
                    twoFingerStart = SystemClock.uptimeMillis();
                    lastAvgY = (e.getY(0) + e.getY(1)) / 2;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (twoFinger && e.getPointerCount() >= 2) {
                    // One wheel step per 40 dp of vertical finger travel / 손가락 세로 이동 40dp마다 휠 한 칸
                    float avg = (e.getY(0) + e.getY(1)) / 2;
                    scrollAcc += avg - lastAvgY;
                    lastAvgY = avg;
                    float step = 40 * getResources().getDisplayMetrics().density;
                    while (Math.abs(scrollAcc) >= step) {
                        int b = scrollAcc > 0 ? 4 : 5;
                        x.button(b, true);
                        x.button(b, false);
                        scrollAcc -= Math.signum(scrollAcc) * step;
                        scrolled = true;
                    }
                } else if (!twoFinger) {
                    int[] p = view.toScreen(e.getX(), e.getY());
                    if (p != null && (lastPos == null || p[0] != lastPos[0] || p[1] != lastPos[1])) {
                        x.motion(p[0], p[1]);
                        lastPos = p;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (leftDown) {
                    x.button(1, false);
                    leftDown = false;
                }
                // Short two-finger tap without scrolling = right click; not on a cancel (a menu
                // gesture took the touches) / 스크롤 없는 짧은 두 손가락 탭 = 오른쪽 클릭. 취소(메뉴 제스처가 터치를
                // 가져감)에는 하지 않음
                if (e.getActionMasked() == MotionEvent.ACTION_UP
                        && twoFinger && !scrolled && SystemClock.uptimeMillis() - twoFingerStart < 300) {
                    x.button(3, true);
                    x.button(3, false);
                }
                twoFinger = false;
                break;
            default:
                break;
        }
        return true;
    }

    // ---- menu gestures: four-finger tap, swipe down from the top / 메뉴 제스처: 네 손가락 탭, 위에서 아래로 밀기

    /** Four-finger tap state: first finger time, all-four time, start points, moved too far
     *  네 손가락 탭 상태: 첫 손가락 시각, 네 손가락이 된 시각, 시작점, 너무 움직임 */
    private long tapStart, tapFour;
    private final android.util.SparseArray<float[]> tapPoints = new android.util.SparseArray<>();
    private boolean tapMoved, tapCancelled;
    /** Top swipe: 0 none, 1 waiting (down held back), 2 swiping / 위쪽 밀기: 0 없음, 1 대기(누름 보류), 2 미는 중 */
    private int edge;
    private MotionEvent edgeDown;
    private float edgeX, edgeY;
    private long edgeAt;

    private float dpx(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /**
     * Before any view: two ways to open the menu without HOME (user request 2026-10-26).
     * - Four fingers down within 300 ms and all up within 700 ms of the first, none moving more
     *   than 48 dp. Without the touch pad, the third finger cancels the mouse input (no meaning
     *   there); with the touch pad the game keeps its touches (four-finger play stays possible).
     * - A swipe down that starts in the top 12 % of the screen and goes 72 dp, mostly downwards
     *   (user request 2026-10-27: the earlier swipe in from the right edge also pulled out the
     *   system bars). Start it a little below the very edge: a swipe from the edge itself is the
     *   system's (it shows the status and navigation bars, which an app cannot stop). The first
     *   touch is held back until it is clear: a tap or another movement there is passed on as is;
     *   touches on touch pad controls are left alone.
     * 모든 뷰보다 먼저: HOME 없이 메뉴를 여는 두 방법(사용자 요청 2026-10-26).
     * - 첫 손가락부터 300ms 안에 네 손가락이 닿고 700ms 안에 모두 떼며, 48dp 넘게 움직인 손가락이 없음. 터치 패드가
     *   없으면 세 번째 손가락에서 마우스 입력을 취소함(그때는 쓸모 없음). 터치 패드가 있으면 게임이 터치를 그대로 받음
     *   (네 손가락 조작도 됨)
     * - 화면 위쪽 12% 안에서 시작해 72dp, 주로 아래로 민 경우(사용자 요청 2026-10-27: 이전의 오른쪽 끝 밀기는 시스템
     *   막대도 함께 끌어냈음). 가장자리 바로 아래에서 시작해야 함: 가장자리 자체에서 미는 것은 시스템 몫(상태·탐색 막대를
     *   보이며 앱이 막을 수 없음). 판단될 때까지 첫 터치를 보류하고, 거기서의 탭이나 다른 움직임은 그대로 넘김. 터치 패드
     *   조작부 위의 터치는 건드리지 않음
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        // Input to screen time (PERF line) / 입력→화면 시간(PERF 줄)
        int am = e.getActionMasked();
        if ((am == MotionEvent.ACTION_DOWN || am == MotionEvent.ACTION_POINTER_DOWN) && view != null) view.markInput();
        app.ezsteam.runtime.CompositorMenu m = menu;
        if (m == null || m.isOpen() || e.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
            edge = 0;
            return super.dispatchTouchEvent(e);
        }
        if (trackFourFingerTap(e)) return true;
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN && e.getY() <= getWindow().getDecorView().getHeight() * 0.12f
                && !touchPad.hasControlAt(e.getX(), e.getY())) {
            edge = 1;
            edgeX = e.getX();
            edgeY = e.getY();
            edgeAt = SystemClock.uptimeMillis();
            if (edgeDown != null) edgeDown.recycle();
            edgeDown = MotionEvent.obtain(e);
            return true;
        }
        if (edge == 1) {
            float dy = e.getY() - edgeY, dx = Math.abs(e.getX() - edgeX);
            if (a == MotionEvent.ACTION_MOVE && dy > dpx(16) && dy > 2 * dx) {
                edge = 2;
                return true;
            }
            boolean still = a == MotionEvent.ACTION_MOVE && Math.abs(dy) < dpx(16) && dx < dpx(16)
                    && SystemClock.uptimeMillis() - edgeAt < 200;
            if (still) return true;
            // Not a swipe: hand the held-back touch over, then this event / 밀기가 아님: 보류한 터치를 넘긴 뒤 이 이벤트
            edge = 0;
            if (edgeDown != null) {
                super.dispatchTouchEvent(edgeDown);
                edgeDown.recycle();
                edgeDown = null;
            }
            return a == MotionEvent.ACTION_CANCEL || super.dispatchTouchEvent(e);
        }
        if (edge == 2) {
            if (a == MotionEvent.ACTION_UP) {
                edge = 0;
                if (e.getY() - edgeY > dpx(72)) {
                    log("메뉴: 위에서 아래로 밀기");
                    openMenu();
                }
            } else if (a == MotionEvent.ACTION_CANCEL) {
                edge = 0;
            }
            return true;
        }
        return super.dispatchTouchEvent(e);
    }

    /** Four-finger tap bookkeeping; true when the event must not reach the views
     *  네 손가락 탭 기록. 이벤트를 뷰에 넘기면 안 되면 true */
    private boolean trackFourFingerTap(MotionEvent e) {
        int a = e.getActionMasked();
        long now = SystemClock.uptimeMillis();
        if (a == MotionEvent.ACTION_DOWN) {
            tapStart = now;
            tapFour = 0;
            tapMoved = false;
            tapCancelled = false;
            tapPoints.clear();
        }
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
            int i = e.getActionIndex();
            tapPoints.put(e.getPointerId(i), new float[]{e.getX(i), e.getY(i)});
            int n = e.getPointerCount();
            if (n >= 4 && tapFour == 0 && now - tapStart <= 300) tapFour = now;
            // No touch pad: from the third finger on the touches are the menu's / 터치 패드 없음: 세 번째 손가락부터 터치는 메뉴 몫
            if (n == 3 && !tapCancelled && touchPad.getVisibility() != View.VISIBLE) {
                MotionEvent c = MotionEvent.obtain(e);
                c.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(c);
                c.recycle();
                tapCancelled = true;
            }
        } else if (a == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < e.getPointerCount(); i++) {
                float[] p0 = tapPoints.get(e.getPointerId(i));
                if (p0 != null && Math.hypot(e.getX(i) - p0[0], e.getY(i) - p0[1]) > dpx(48)) tapMoved = true;
            }
        } else if (a == MotionEvent.ACTION_UP) {
            boolean cancelled = tapCancelled;
            tapCancelled = false;
            if (tapFour != 0 && !tapMoved && now - tapStart <= 700) {
                tapFour = 0;
                // Views that kept their touches (touch pad shown) still get the last finger up
                // 터치를 계속 받던 뷰(터치 패드 보임)에도 마지막 손가락 뗌을 넘김
                if (!cancelled) super.dispatchTouchEvent(e);
                log("메뉴: 네 손가락 탭");
                openMenu();
                return true;
            }
            tapFour = 0;
            return cancelled;
        } else if (a == MotionEvent.ACTION_CANCEL) {
            boolean cancelled = tapCancelled;
            tapCancelled = false;
            tapFour = 0;
            return cancelled;
        }
        return tapCancelled;
    }

    // ---- keys / 키

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Key and controller button presses for the input to screen time (PERF line)
        // 입력→화면 시간용 키·컨트롤러 버튼 누름(PERF 줄)
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && view != null) view.markInput();
        // Volume and mute keys stay with Android (they were sent to X and the volume did not change,
        // user report); the game sound plays through Android's media volume
        // 볼륨·음소거 키는 안드로이드가 처리함(X로 보내져 볼륨이 바뀌지 않았음, 사용자 보고).
        // 게임 소리는 안드로이드 미디어 볼륨으로 재생됨
        int kc = e.getKeyCode();
        if (kc == KeyEvent.KEYCODE_VOLUME_UP || kc == KeyEvent.KEYCODE_VOLUME_DOWN
                || kc == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(e);
        }
        // Controller buttons go to the virtual pad, not to X as keys (they showed as "?96" before:
        // no X keycode; only the d-pad worked because Android also reports it as arrow keys)
        // 컨트롤러 버튼은 X 키가 아니라 가상 패드로 보냄(전에는 X 키코드가 없어 "?96"처럼 보였고,
        // 안드로이드가 화살표 키로도 알려 주는 십자키만 동작했음)
        if (pad != null && isPadKey(e)) return onPadKey(e);
        // A controller button Android has no key code for (KEYCODE_UNKNOWN, shown as "?0"): some
        // pads send their HOME this way, told apart by the Linux scan code (BTN_MODE 316,
        // KEY_HOMEPAGE 172). Others are logged with their scan code (2026-10-25).
        // 안드로이드에 키 코드가 없는 컨트롤러 버튼(KEYCODE_UNKNOWN, "?0"으로 보였음): 일부 패드는 HOME을 이렇게
        // 보내며 리눅스 스캔 코드로 구분함(BTN_MODE 316, KEY_HOMEPAGE 172). 나머지는 스캔 코드와 함께 기록함(2026-10-25)
        if (pad != null && kc == KeyEvent.KEYCODE_UNKNOWN && fromPadDevice(e)) return onUnknownPadKey(e);
        // Back: from a physical device (keyboard Esc fallback, mouse) it is Esc in X (a controller's
        // back is handled above as its B button);
        // only the on-screen back asks to close. Closing ends Steam and the game, and a physical
        // keyboard closed the screen again and again (user report).
        // 뒤로 가기: 실제 장치(키보드 Esc 대체, 마우스)에서 오면 X의 Esc로 보냄(컨트롤러의 뒤로 가기는
        // 위에서 B 버튼으로 처리함).
        // 화면의 뒤로 가기만 닫기를 물어봄. 닫으면 Steam과 게임이 끝나는데, 실제 키보드에서 화면이
        // 계속 닫혔음(사용자 보고)
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            // On-screen back (navigation bar, gesture) comes from the virtual device id -1
            // 화면의 뒤로 가기(내비게이션 바, 제스처)는 가상 장치 id -1에서 옴
            android.view.InputDevice dev = e.getDevice();
            boolean physical = e.getDeviceId() > 0 && (dev == null || !dev.isVirtual());
            // A mouse's back key is the right/back button, already sent as a button. Decided by the
            // event's own source: a keyboard with a touchpad reports mouse among its device sources,
            // and checking the device swallowed its Esc (measured: Esc never reached the game)
            // 마우스의 뒤로 가기는 오른쪽·뒤로 버튼이며 이미 버튼으로 보냄. 이벤트 자체의 출처로 판단함:
            // 터치패드가 달린 키보드는 장치 출처에 마우스가 함께 있어, 장치로 판단하면 Esc가 사라졌음
            // (실측: Esc가 게임에 가지 않음)
            if (physical && e.isFromSource(android.view.InputDevice.SOURCE_MOUSE)
                    && !e.isFromSource(android.view.InputDevice.SOURCE_KEYBOARD)) return true;
            if (physical) {
                XTestClient x = xtest;
                if (x != null && e.getRepeatCount() == 0 && e.getAction() != KeyEvent.ACTION_MULTIPLE) {
                    x.key(XKeys.ESCAPE, e.getAction() == KeyEvent.ACTION_DOWN);
                    if (e.getAction() == KeyEvent.ACTION_DOWN) {
                        lastKey = "BACK→Esc";
                        log("KEY BACK → X " + XKeys.ESCAPE + " (Esc)");
                    }
                }
                return true;
            }
            // On-screen back opens the HOME menu (or goes back in it); closing the session is a
            // menu row now (no bar any more) / 화면 뒤로 가기는 HOME 메뉴를 엶(열려 있으면 뒤로). 세션 닫기는 이제
            // 메뉴 줄(막대 없음)
            if (e.getAction() == KeyEvent.ACTION_UP) runOnUiThread(this::openMenu);
            return true;
        }
        // Text delivered as one event (some keyboards): split into key events
        // 한 이벤트로 온 텍스트(일부 키보드): 키 이벤트로 나눔
        if (e.getAction() == KeyEvent.ACTION_MULTIPLE && e.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN) {
            view.sendChars(e.getCharacters());
            return true;
        }
        return sendKey(e) || super.dispatchKeyEvent(e);
    }

    /**
     * Send one Android key event to X; false when it has no X keycode (or input is not connected).
     * 안드로이드 키 이벤트 하나를 X로 보냄. X 키코드가 없거나 입력이 연결되지 않았으면 false
     */
    private boolean sendKey(KeyEvent e) {
        XTestClient x = xtest;
        int code = XKeys.keycode(e);
        if (code == 0) lastKey = "?" + e.getKeyCode();
        if (x == null || code == 0) return false;
        if (e.getAction() == KeyEvent.ACTION_MULTIPLE) return true;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        // Keys go to the focused window: focus the one under the pointer first (cheap when unchanged)
        // 키는 포커스 창으로 감: 먼저 포인터 아래 창에 포커스를 줌(바뀌지 않았으면 가벼움)
        if (down && e.getRepeatCount() == 0) x.focusUnderPointer();
        if (down) lastKey = KeyEvent.keyCodeToString(e.getKeyCode()).replace("KEYCODE_", "") + "→" + code;
        // Input diagnosis: control keys only go to the app log (no letters or digits, so typed text
        // such as a password never lands in a saved log)
        // 입력 진단: 조작 키만 앱 로그에 남김(문자·숫자는 남기지 않아 입력한 글, 예컨대 비밀번호가
        // 저장된 로그에 들어가지 않음)
        if (down && e.getRepeatCount() == 0 && isControlKey(e.getKeyCode())) {
            log("KEY " + KeyEvent.keyCodeToString(e.getKeyCode()).replace("KEYCODE_", "") + " → X " + code);
        }
        // Soft keyboards send capitals as one event with the Shift meta flag: wrap it in Shift
        // 소프트 키보드는 대문자를 Shift 메타 플래그가 붙은 이벤트 하나로 보내므로 Shift로 감쌈
        boolean wrapShift = XKeys.fromSoftKeyboard(e) && e.isShiftPressed() && code != XKeys.SHIFT_L;
        if (down) {
            if (wrapShift) x.key(XKeys.SHIFT_L, true);
            x.key(code, true);
        } else {
            x.key(code, false);
            if (wrapShift) x.key(XKeys.SHIFT_L, false);
        }
        return true;
    }

    /**
     * Close the screen; in Steam mode ask first, because closing ends Steam and any running game.
     * 화면 닫기. Steam 모드에서는 먼저 물어봄: 닫으면 Steam과 실행 중인 게임이 모두 끝남
     */
    private void confirmClose() {
        if (!"steam".equals(mode)) {
            finish();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(s(R.string.dlg_close_title))
                .setMessage(s(R.string.dlg_close_msg))
                .setPositiveButton(s(R.string.btn_end), (d, w) -> finish())
                .setNegativeButton(s(R.string.btn_cancel), null)
                .show();
    }

    // ---- on-screen touch pad / 화면 터치 패드

    private static final String PREF_PAD_SCALE = "pad.scale", PREF_PAD_OPACITY = "pad.opacity";
    /** Touch pad spacing 0.8..1.6 / 터치 패드 간격 0.8..1.6 */
    private static final String PREF_PAD_GAP = "pad.gap";
    /** Left D-pad and left stick swapped / 왼쪽 십자키·스틱 자리 바꿈 */
    private static final String PREF_PAD_SWAP = "pad.swapLeft";
    private static final int REQ_PAD_LAYOUT = 7302;

    /** Imported layout (.icp) kept inside the app / 앱 안에 보관한 불러온 배치(.icp) */
    private File padLayoutFile() {
        return new File(getFilesDir(), "ezs-data/pads/current.icp");
    }

    /**
     * Touch pad layout: the imported .icp when there is one, otherwise the bundled default layout
     * 터치 패드 배치: 불러온 .icp가 있으면 그것, 없으면 앱에 든 기본 배치
     */
    private void loadPadLayout() {
        File f = padLayoutFile();
        String json = f.isFile() ? readText(f) : "";
        try {
            if (json.isEmpty()) {
                try (java.io.InputStream in = getAssets().open("pads/default.icp")) {
                    json = new String(readAll(in), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            app.ezsteam.runtime.IcpLayout layout = app.ezsteam.runtime.IcpLayout.parse(json);
            touchPad.setLayout(layout);
            log("PAD 배치 " + (f.isFile() ? "불러온 파일" : "기본") + ": " + layout.name + " (요소 " + layout.elements.size() + "개)");
        } catch (IOException | org.json.JSONException e) {
            log("PAD 배치를 읽지 못함: " + e);
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** Pick a .icp file (exported from EZlator/Winlator) / .icp 파일 고르기(EZlator·Winlator에서 내보낸 것) */
    private void pickPadLayout() {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_PAD_LAYOUT);
        } catch (RuntimeException e) {
            log("PAD 파일 선택 창을 열지 못함: " + e);
        }
    }

    /** Check and keep the picked layout, then show it / 고른 배치를 확인·보관한 뒤 보여 줌 */
    private void importPadLayout(android.net.Uri uri) {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("열 수 없음");
            byte[] data = readAll(in);
            if (data.length > 1 << 20) throw new IOException("파일이 너무 큼");
            // Must parse before it replaces the current layout / 지금 배치를 바꾸기 전에 읽히는지 확인
            app.ezsteam.runtime.IcpLayout.parse(new String(data, java.nio.charset.StandardCharsets.UTF_8));
            File f = padLayoutFile();
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("폴더를 만들지 못함");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                out.write(data);
            }
            loadPadLayout();
            notice(s(R.string.notice_pad_loaded));
        } catch (IOException | org.json.JSONException | RuntimeException e) {
            log("PAD 배치 불러오기 실패: " + e);
            notice(s(R.string.notice_pad_bad, e.getMessage()));
        }
    }

    /** Show or hide the touch pad; hiding releases every touch button
     *  터치 패드를 보이거나 숨김. 숨기면 터치로 누른 버튼을 모두 뗌 */
    private void setTouchPadShown(boolean shown) {
        if (shown && pad == null) {
            log("PAD 터치 패드: Steam 모드에서만 동작 (가상 패드 없음)");
        }
        if (!shown) touchPad.releaseAll();
        touchPad.setVisibility(shown ? View.VISIBLE : View.GONE);
        if (menu != null) menu.refresh();
    }

    /**
     * Size, spacing and opacity of the touch pad, saved for next time. Size scales every control
     * (50..180 %); spacing spreads each side's controls apart (80..160 %).
     * 터치 패드 크기·간격·진하기, 다음에도 유지함. 크기는 모든 조작부를 키움(50..180 %), 간격은 각 쪽
     * 조작부 사이를 벌림(80..160 %)
     */
    private void showPadSettings() {
        android.content.SharedPreferences pp = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        float[] cur = {pp.getFloat(PREF_PAD_SCALE, 1f), pp.getFloat(PREF_PAD_OPACITY, 0.45f),
                pp.getFloat(PREF_PAD_GAP, 1f)};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad16 = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad16, pad16, pad16, 0);
        TextView sizeText = new TextView(this);
        android.widget.SeekBar size = new android.widget.SeekBar(this);
        size.setMax(130);  // 50..180 %
        size.setProgress(Math.round(cur[0] * 100) - 50);
        TextView alphaText = new TextView(this);
        android.widget.SeekBar alpha = new android.widget.SeekBar(this);
        alpha.setMax(75);  // 15..90 %
        alpha.setProgress(Math.round(cur[1] * 100) - 15);
        TextView gapText = new TextView(this);
        android.widget.SeekBar gapBar = new android.widget.SeekBar(this);
        gapBar.setMax(80);  // 80..160 %
        gapBar.setProgress(Math.round(cur[2] * 100) - 80);
        Runnable update = () -> {
            cur[0] = (size.getProgress() + 50) / 100f;
            cur[1] = (alpha.getProgress() + 15) / 100f;
            cur[2] = (gapBar.getProgress() + 80) / 100f;
            sizeText.setText(s(R.string.pad_size, size.getProgress() + 50));
            gapText.setText(s(R.string.pad_gap, gapBar.getProgress() + 80));
            alphaText.setText(s(R.string.pad_alpha, alpha.getProgress() + 15));
            touchPad.setLook(cur[0], cur[1]);
            touchPad.setGap(cur[2]);
        };
        android.widget.SeekBar.OnSeekBarChangeListener l = new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int v, boolean fromUser) {
                update.run();
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar sb) {
            }
        };
        size.setOnSeekBarChangeListener(l);
        gapBar.setOnSeekBarChangeListener(l);
        alpha.setOnSeekBarChangeListener(l);
        box.addView(sizeText);
        box.addView(size);
        box.addView(gapText);
        box.addView(gapBar);
        box.addView(alphaText);
        box.addView(alpha);
        // Left D-pad and left stick change places (tried at once, kept with Save)
        // 왼쪽 십자키·스틱 자리 바꾸기(바로 보이고, 저장으로 유지)
        boolean[] swap = {pp.getBoolean(PREF_PAD_SWAP, false)};
        android.widget.CheckBox swapBox = new android.widget.CheckBox(this);
        swapBox.setText(s(R.string.pad_swap));
        swapBox.setChecked(swap[0]);
        swapBox.setOnCheckedChangeListener((b, on) -> {
            swap[0] = on;
            touchPad.setSwapLeft(on);
        });
        box.addView(swapBox);
        update.run();
        // Show the pad while adjusting so the change is visible / 조절하는 동안 패드를 보여 변화가 보이게 함
        boolean wasShown = touchPad.getVisibility() == View.VISIBLE;
        if (!wasShown) setTouchPadShown(true);
        new android.app.AlertDialog.Builder(this)
                .setTitle(s(R.string.menu_touch_pad))
                .setView(box)
                .setPositiveButton(s(R.string.btn_save), (d, w) -> pp.edit()
                        .putFloat(PREF_PAD_SCALE, cur[0]).putFloat(PREF_PAD_OPACITY, cur[1])
                        .putFloat(PREF_PAD_GAP, cur[2]).putBoolean(PREF_PAD_SWAP, swap[0]).apply())
                .setNeutralButton(s(R.string.pad_layout_file), (d, w) -> {
                    // Keep the size and opacity being tried, then choose the layout
                    // 시험 중인 크기·진하기를 저장한 뒤 배치를 고름
                    pp.edit().putFloat(PREF_PAD_SCALE, cur[0]).putFloat(PREF_PAD_OPACITY, cur[1])
                            .putFloat(PREF_PAD_GAP, cur[2]).putBoolean(PREF_PAD_SWAP, swap[0]).apply();
                    showPadLayoutMenu();
                })
                .setNegativeButton(s(R.string.btn_cancel), (d, w) -> restorePadLook(pp))
                .setOnCancelListener(d -> restorePadLook(pp))
                .show();
    }

    /** Saved size, opacity and spacing back on the pad (cancel) / 저장된 크기·진하기·간격으로 되돌림(취소) */
    private void restorePadLook(android.content.SharedPreferences pp) {
        touchPad.setLook(pp.getFloat(PREF_PAD_SCALE, 1f), pp.getFloat(PREF_PAD_OPACITY, 0.45f));
        touchPad.setGap(pp.getFloat(PREF_PAD_GAP, 1f));
        touchPad.setSwapLeft(pp.getBoolean(PREF_PAD_SWAP, false));
    }

    /** Layout choices: import a .icp, back to the default, or default size and opacity
     *  배치 선택: .icp 불러오기, 기본 배치로, 크기·진하기 기본값 */
    private void showPadLayoutMenu() {
        String[] items = getResources().getStringArray(R.array.pad_layout_items);
        new android.app.AlertDialog.Builder(this)
                .setTitle(s(R.string.pad_layout_title))
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        pickPadLayout();
                    } else if (which == 1) {
                        File f = padLayoutFile();
                        if (f.isFile() && !f.delete()) log("PAD 불러온 배치를 지우지 못함: " + f);
                        loadPadLayout();
                    } else {
                        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                                .remove(PREF_PAD_SCALE).remove(PREF_PAD_OPACITY).remove(PREF_PAD_GAP)
                                .remove(PREF_PAD_SWAP).apply();
                        touchPad.setLook(1f, 0.45f);
                        touchPad.setGap(1f);
                        touchPad.setSwapLeft(false);
                    }
                })
                .show();
    }

    // ---- game controller / 게임 컨트롤러

    /** Android key code -> SDL controller button / 안드로이드 키 코드 -> SDL 컨트롤러 버튼 */
    private static int padButton(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return app.ezsteam.runtime.PadFile.A;
            case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_BACK: return app.ezsteam.runtime.PadFile.B;
            case KeyEvent.KEYCODE_BUTTON_X: return app.ezsteam.runtime.PadFile.X;
            case KeyEvent.KEYCODE_BUTTON_Y: return app.ezsteam.runtime.PadFile.Y;
            case KeyEvent.KEYCODE_BUTTON_L1: return app.ezsteam.runtime.PadFile.LEFT_SHOULDER;
            case KeyEvent.KEYCODE_BUTTON_R1: return app.ezsteam.runtime.PadFile.RIGHT_SHOULDER;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return app.ezsteam.runtime.PadFile.BACK;
            case KeyEvent.KEYCODE_BUTTON_START: return app.ezsteam.runtime.PadFile.START;
            case KeyEvent.KEYCODE_BUTTON_MODE: return app.ezsteam.runtime.PadFile.GUIDE;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return app.ezsteam.runtime.PadFile.LEFT_STICK;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return app.ezsteam.runtime.PadFile.RIGHT_STICK;
            case KeyEvent.KEYCODE_DPAD_UP: return app.ezsteam.runtime.PadFile.DPAD_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return app.ezsteam.runtime.PadFile.DPAD_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return app.ezsteam.runtime.PadFile.DPAD_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return app.ezsteam.runtime.PadFile.DPAD_RIGHT;
            default: return -1;
        }
    }

    /**
     * A key event from a controller (buttons, its d-pad, its back key). The d-pad and back keys are
     * also keyboard keys, so they count only when the sending device is a controller: the event
     * source says gamepad/joystick, or the device has gamepad/joystick sources and no letter keys.
     * Measured on the device: controller d-pad keys still went to X as arrow keys (KEY DPAD_DOWN
     * -> X 116) when only the event source was checked.
     * 컨트롤러에서 온 키 이벤트(버튼, 컨트롤러 십자키, 컨트롤러의 뒤로 가기). 십자키와 뒤로 가기는
     * 키보드 키이기도 해서 보낸 장치가 컨트롤러일 때만 셈: 이벤트 출처가 게임패드·조이스틱이거나,
     * 장치 출처에 게임패드·조이스틱이 있고 글자 키가 없음. 기기 실측: 이벤트 출처만 볼 때 컨트롤러
     * 십자키가 여전히 X 화살표 키로 감(KEY DPAD_DOWN -> X 116)
     */
    /** The event comes from a controller (gamepad / joystick source) / 이벤트가 컨트롤러에서 옴 */
    private static boolean fromPadDevice(KeyEvent e) {
        if (e.isFromSource(android.view.InputDevice.SOURCE_GAMEPAD)
                || e.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)) return true;
        android.view.InputDevice dev = e.getDevice();
        if (dev == null || dev.isVirtual()) return false;
        int src = dev.getSources();
        return (src & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD
                || (src & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK;
    }

    /** Linux scan codes of a HOME / guide button / HOME·가이드 버튼의 리눅스 스캔 코드 */
    private static final int SCAN_BTN_MODE = 316, SCAN_KEY_HOMEPAGE = 172;

    private boolean onUnknownPadKey(KeyEvent e) {
        app.ezsteam.runtime.PadFile p = pad;
        if (p == null || e.getAction() == KeyEvent.ACTION_MULTIPLE) return true;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int sc = e.getScanCode();
        if (sc == SCAN_BTN_MODE || sc == SCAN_KEY_HOMEPAGE) {
            if (p.button(app.ezsteam.runtime.PadFile.GUIDE) != down) {
                p.setButton(app.ezsteam.runtime.PadFile.GUIDE, down);
                p.write();
            }
            if (down && e.getRepeatCount() == 0) {
                lastKey = "PAD HOME(scan " + sc + ")";
                log("PAD HOME (키 코드 없음, 스캔 코드 " + sc + ")");
            }
            return true;
        }
        if (down && e.getRepeatCount() == 0) {
            lastKey = "PAD?scan" + sc;
            log("PAD 알 수 없는 버튼: 키 코드 0, 스캔 코드 " + sc + " (장치 "
                    + (e.getDevice() != null ? e.getDevice().getName() : "?") + ")");
        }
        return true;
    }

    private static boolean isPadKey(KeyEvent e) {
        int k = e.getKeyCode();
        if (KeyEvent.isGamepadButton(k)) return true;
        if (padButton(k) < 0) return false;
        if (e.isFromSource(android.view.InputDevice.SOURCE_GAMEPAD)
                || e.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)) return true;
        android.view.InputDevice dev = e.getDevice();
        if (dev == null || dev.isVirtual()) return false;
        int src = dev.getSources();
        boolean padDevice = (src & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD
                || (src & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK;
        return padDevice && dev.getKeyboardType() != android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    }

    /**
     * Controller sticks/triggers at the window level, before view focus decides the receiver. When no
     * view took them, Android turned the stick and hat into d-pad key repeats (ViewRootImpl
     * SyntheticJoystickHandler), seen as long runs of DPAD_DOWN in the log.
     * 컨트롤러 스틱·트리거를 창 단계에서 받음(뷰 포커스가 받는 곳을 정하기 전). 어느 뷰도 받지 않으면
     * 안드로이드가 스틱·햇을 십자키 반복 입력으로 바꿈(ViewRootImpl SyntheticJoystickHandler). 로그의
     * 긴 DPAD_DOWN 연속이 그것으로 보임
     */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (pad != null && e.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)) return onPadMotion(e);
        return super.dispatchGenericMotionEvent(e);
    }

    /** Controller button press/release -> virtual pad / 컨트롤러 버튼 누름·뗌 -> 가상 패드 */
    private boolean onPadKey(KeyEvent e) {
        app.ezsteam.runtime.PadFile p = pad;
        if (p == null || e.getAction() == KeyEvent.ACTION_MULTIPLE) return true;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int k = e.getKeyCode();
        logPadDevice(e.getDevice());
        if (k == KeyEvent.KEYCODE_BUTTON_L2 || k == KeyEvent.KEYCODE_BUTTON_R2) {
            // Digital triggers (pads without trigger axes) / 디지털 트리거(트리거 축이 없는 패드)
            digitalLeft = k == KeyEvent.KEYCODE_BUTTON_L2 ? down : digitalLeft;
            digitalRight = k == KeyEvent.KEYCODE_BUTTON_R2 ? down : digitalRight;
            p.setTriggers(Math.max(axisLeft, digitalLeft ? 1f : 0f), Math.max(axisRight, digitalRight ? 1f : 0f));
        } else {
            int b = padButton(k);
            if (b < 0) {
                lastKey = "PAD?" + k;
                return true;
            }
            if (p.button(b) == down) return true;
            p.setButton(b, down);
        }
        p.write();
        if (down && e.getRepeatCount() == 0) {
            lastKey = "PAD " + KeyEvent.keyCodeToString(k).replace("KEYCODE_", "");
            log("PAD " + KeyEvent.keyCodeToString(k).replace("KEYCODE_", ""));
        }
        return true;
    }

    // Trigger values from axes and from digital buttons / 축과 디지털 버튼에서 온 트리거 값
    private float axisLeft, axisRight;
    private boolean digitalLeft, digitalRight;

    /**
     * Controller sticks, triggers and hat (d-pad on some pads) -> virtual pad. Right stick: Z/RZ on
     * most Android pads, RX/RY on some; picked by which axes the device reports.
     * 컨트롤러 스틱·트리거·햇(일부 패드의 십자키) -> 가상 패드. 오른쪽 스틱은 대부분의 안드로이드 패드에서
     * Z/RZ, 일부는 RX/RY라 장치가 알려 주는 축으로 고름
     */
    private boolean onPadMotion(MotionEvent e) {
        app.ezsteam.runtime.PadFile p = pad;
        if (p == null) return false;
        android.view.InputDevice dev = e.getDevice();
        logPadDevice(dev);
        boolean zrz = dev == null || dev.getMotionRange(MotionEvent.AXIS_Z, e.getSource()) != null;
        p.setAxis(0, padAxis(e, dev, MotionEvent.AXIS_X));
        p.setAxis(1, padAxis(e, dev, MotionEvent.AXIS_Y));
        p.setAxis(2, padAxis(e, dev, zrz ? MotionEvent.AXIS_Z : MotionEvent.AXIS_RX));
        p.setAxis(3, padAxis(e, dev, zrz ? MotionEvent.AXIS_RZ : MotionEvent.AXIS_RY));
        axisLeft = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE));
        axisRight = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS));
        p.setTriggers(Math.max(axisLeft, digitalLeft ? 1f : 0f), Math.max(axisRight, digitalRight ? 1f : 0f));
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (dev != null && dev.getMotionRange(MotionEvent.AXIS_HAT_X, e.getSource()) != null) {
            p.setButton(app.ezsteam.runtime.PadFile.DPAD_LEFT, hx < -0.5f);
            p.setButton(app.ezsteam.runtime.PadFile.DPAD_RIGHT, hx > 0.5f);
            p.setButton(app.ezsteam.runtime.PadFile.DPAD_UP, hy < -0.5f);
            p.setButton(app.ezsteam.runtime.PadFile.DPAD_DOWN, hy > 0.5f);
        }
        p.write();
        return true;
    }

    /** Axis value with the device's flat (dead) zone removed / 장치의 무감 영역을 뺀 축 값 */
    private static float padAxis(MotionEvent e, android.view.InputDevice dev, int axis) {
        float v = e.getAxisValue(axis);
        android.view.InputDevice.MotionRange r = dev == null ? null : dev.getMotionRange(axis, e.getSource());
        float flat = r == null ? 0.1f : Math.max(r.getFlat(), 0.05f);
        return Math.abs(v) <= flat ? 0f : v;
    }

    /** Log a controller the first time it sends input / 컨트롤러가 처음 입력할 때 한 번 기록함 */
    private void logPadDevice(android.view.InputDevice dev) {
        if (dev == null || dev.getId() == padDeviceLogged) return;
        padDeviceLogged = dev.getId();
        log("PAD 컨트롤러: " + dev.getName() + String.format(Locale.ROOT, " (%04x:%04x)",
                dev.getVendorId(), dev.getProductId()));
    }

    // ---- physical mouse / 실제 마우스

    /** Android button bit -> X button number / 안드로이드 버튼 비트 -> X 버튼 번호 */
    private static final int[][] MOUSE_MAP = {
            {MotionEvent.BUTTON_PRIMARY, 1}, {MotionEvent.BUTTON_TERTIARY, 2}, {MotionEvent.BUTTON_SECONDARY, 3},
            {MotionEvent.BUTTON_BACK, 8}, {MotionEvent.BUTTON_FORWARD, 9},
    };

    /**
     * One mouse event. Unlocked: the X pointer follows the Android position (absolute), so both
     * cursors stay together (before, only touches moved the X pointer and a mouse moved Android's
     * own arrow alone; user report). Locked (pointer capture): relative movement for games that turn
     * the camera with the mouse. Buttons are sent when they change, the wheel as buttons 4-7.
     * 마우스 이벤트 하나. 잠금 해제: X 포인터가 안드로이드 위치를 따라감(절대 위치)이라 두 커서가
     * 함께 움직임(전에는 터치만 X 포인터를 옮겨 마우스는 안드로이드 화살표만 따로 움직였음, 사용자 보고).
     * 잠금(포인터 캡처): 마우스로 시점을 돌리는 게임용 상대 이동. 버튼은 바뀔 때 보내고, 휠은 버튼 4~7로 보냄
     */
    private boolean onMouse(MotionEvent e, boolean captured) {
        XTestClient x = xtest;
        if (x == null) return true;
        if (captured) {
            // Relative axes carry the movement since the last event (fractions kept)
            // 상대 축은 지난 이벤트 이후 이동량임(소수점 나머지 유지)
            relRestX += e.getAxisValue(MotionEvent.AXIS_RELATIVE_X);
            relRestY += e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
            int dx = (int) relRestX, dy = (int) relRestY;
            relRestX -= dx;
            relRestY -= dy;
            if (dx != 0 || dy != 0) x.motionRelative(dx, dy);
        } else {
            int[] p = view.toScreen(e.getX(), e.getY());
            if (p != null && (lastPos == null || p[0] != lastPos[0] || p[1] != lastPos[1])) {
                x.motion(p[0], p[1]);
                lastPos = p;
            }
        }
        int now = e.getButtonState();
        int changed = now ^ mouseButtons;
        if (changed != 0) {
            // Focus the window under the pointer before a press (no window manager here)
            // 누르기 전에 포인터 아래 창에 포커스를 줌(여기에는 창 관리자가 없음)
            if ((now & changed) != 0) x.focusUnderPointer(true);
            for (int[] m : MOUSE_MAP) {
                if ((changed & m[0]) != 0) {
                    boolean press = (now & m[0]) != 0;
                    x.button(m[1], press);
                    // Input diagnosis: presses go to the app log / 입력 진단: 누름을 앱 로그에 남김
                    if (press) log("MOUSE 버튼 " + m[1] + (captured ? " (잠금)" : ""));
                }
            }
            mouseButtons = now;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            wheelV += e.getAxisValue(MotionEvent.AXIS_VSCROLL);
            wheelH += e.getAxisValue(MotionEvent.AXIS_HSCROLL);
            while (Math.abs(wheelV) >= 1) {
                int b = wheelV > 0 ? 4 : 5;
                x.button(b, true);
                x.button(b, false);
                wheelV -= Math.signum(wheelV);
            }
            while (Math.abs(wheelH) >= 1) {
                int b = wheelH > 0 ? 7 : 6;
                x.button(b, true);
                x.button(b, false);
                wheelH -= Math.signum(wheelH);
            }
        }
        return true;
    }

    /**
     * [마우스 잠금]: capture the mouse for relative movement (Android hides its arrow and sends raw
     * motion); press the button again with a touch, or leave the app, to release.
     * [마우스 잠금]: 상대 이동용으로 마우스를 붙잡음(안드로이드가 화살표를 숨기고 원래 움직임을 보냄).
     * 버튼을 터치로 다시 누르거나 앱을 벗어나면 풀림
     */
    private void toggleMouseLock() {
        if (view.hasPointerCapture()) {
            view.releasePointerCapture();
        } else {
            view.requestFocus();
            view.requestPointerCapture();
        }
        // The capture state changes asynchronously; show it shortly after
        // 캡처 상태는 비동기로 바뀌므로 잠시 뒤 표시함
        boolean was = view.hasPointerCapture();
        view.postDelayed(() -> notice(s(view.hasPointerCapture() ? R.string.notice_mouse_locked : R.string.notice_mouse_free)), 200);
        log("마우스 잠금 " + (was ? "해제 요청" : "요청 (해제: 메뉴 > 입력 > 마우스 잠금)"));
    }

    /** Keys worth logging for input diagnosis (no text keys) / 입력 진단용으로 남길 키(글자 키 제외) */
    private static boolean isControlKey(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_SPACE: case KeyEvent.KEYCODE_TAB: case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            default:
                return k >= KeyEvent.KEYCODE_F1 && k <= KeyEvent.KEYCODE_F12;
        }
    }

    /** Screen y of the last tap and the soft keyboard height (0 = hidden), px
     *  마지막 탭의 화면 y와 소프트 키보드 높이(0 = 숨김), px */
    private float lastTapRawY = -1;
    private int imeHeight;

    /**
     * Follows the soft keyboard: while it is shown the game screen moves up just enough that the
     * last tapped spot (usually the text field) stays above it, and back down when it hides
     * (the keyboard covered the Steam sign-in fields, user report 2026-10-20). Android 11+ reports
     * the keyboard height in the window insets; older versions use the visible window frame.
     * 소프트 키보드를 따라감: 키보드가 보이는 동안 마지막으로 누른 곳(보통 입력란)이 키보드 위에 오도록
     * 게임 화면을 필요한 만큼만 올리고, 숨으면 되돌림(키보드가 Steam 로그인 입력란을 가렸음, 사용자 보고
     * 2026-10-20). 안드로이드 11 이상은 창 인셋으로 키보드 높이를 주고, 이전 버전은 보이는 창 영역을 씀
     */
    private void watchKeyboard(View root) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                int ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                onKeyboardHeight(ime);
                return insets;
            });
        } else {
            root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                android.graphics.Rect r = new android.graphics.Rect();
                root.getWindowVisibleDisplayFrame(r);
                int hidden = root.getRootView().getHeight() - r.bottom;
                onKeyboardHeight(hidden > root.getRootView().getHeight() / 6 ? hidden : 0);
            });
        }
    }

    private void onKeyboardHeight(int h) {
        if (h == imeHeight) return;
        imeHeight = h;
        if (h == 0) view.setTranslationY(0f);
        else keepTapAboveKeyboard();
    }

    /** Move the game screen up so the last tap is above the keyboard / 마지막 탭이 키보드 위에 오도록 게임 화면을 올림 */
    private void keepTapAboveKeyboard() {
        if (lastTapRawY < 0) return;
        float margin = 56 * getResources().getDisplayMetrics().density;
        int screenH = view.getRootView().getHeight();
        // Tap position on the unshifted screen / 올리기 전 화면에서의 탭 위치
        float tapY = lastTapRawY - view.getTranslationY();
        float shift = tapY + margin - (screenH - imeHeight);
        view.setTranslationY(-Math.max(0f, Math.min(shift, imeHeight)));
    }

    private void toggleKeyboard() {
        view.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    /** This screen is in front (between onResume and onPause) / 이 화면이 앞에 있음(onResume~onPause) */
    private volatile boolean inFront = true;

    @Override
    protected void onPause() {
        super.onPause();
        // Logged so a slowdown can be matched with leaving the app / 느려진 때와 앱을 떠난 때를 맞춰 보려고 기록함
        inFront = false;
        log("APP 화면 떠남 (다른 앱, 홈 또는 화면 꺼짐)");
        view.onPause();
        // Fingers lifted while away are never reported; release the touch pad buttons
        // 화면을 떠난 동안 뗀 손가락은 알려지지 않으므로 터치 패드 버튼을 모두 뗌
        if (touchPad != null) touchPad.releaseAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!inFront) log("APP 화면 돌아옴");
        inFront = true;
        view.onResume();
    }

    /**
     * Processes of this app left from a session that was killed (orphans: not under this app
     * process any more), stopped before a new session. After the Steam session was killed (137)
     * its X server stayed alive and the next start failed with "server already running"
     * (2026-10-21). Only processes of this app's own user id; work started from this app process
     * (e.g. [DirectX 설치]) is a descendant and is left alone.
     * 죽은 세션에서 남은 이 앱의 프로세스(고아: 더는 이 앱 프로세스 아래에 없음)를 새 세션 전에 멈춤. Steam
     * 세션이 137로 죽은 뒤 X 서버가 살아남아 다음 시작이 "server already running"으로 실패했음(2026-10-21).
     * 이 앱 사용자 id의 프로세스만 대상. 이 앱 프로세스에서 시작한 작업([DirectX 설치] 등)은 자손이라 건드리지 않음
     */
    private void killLeftovers() {
        int me = android.os.Process.myPid();
        int uid = android.os.Process.myUid();
        java.util.Map<Integer, Integer> parent = new java.util.HashMap<>();
        java.util.Map<Integer, String> name = new java.util.HashMap<>();
        File[] procs = new File("/proc").listFiles(f -> f.getName().matches("\\d+"));
        for (File pd : procs == null ? new File[0] : procs) {
            String st = readText(new File(pd, "status"));
            java.util.regex.Matcher u = java.util.regex.Pattern.compile("(?m)^Uid:\\s+(\\d+)").matcher(st);
            java.util.regex.Matcher pp = java.util.regex.Pattern.compile("(?m)^PPid:\\s+(\\d+)").matcher(st);
            java.util.regex.Matcher nm = java.util.regex.Pattern.compile("(?m)^Name:\\s+(.*)$").matcher(st);
            if (!u.find() || Integer.parseInt(u.group(1)) != uid || !pp.find()) continue;
            int pid = Integer.parseInt(pd.getName());
            parent.put(pid, Integer.parseInt(pp.group(1)));
            name.put(pid, nm.find() ? nm.group(1).trim() : "?");
        }
        java.util.List<String> killed = new java.util.ArrayList<>();
        for (int pid : parent.keySet()) {
            if (pid == me) continue;
            // Under this app process? / 이 앱 프로세스 아래인가?
            int p = pid, guard = 0;
            boolean mine = false;
            while (parent.containsKey(p) && guard++ < 64) {
                p = parent.get(p);
                if (p == me) {
                    mine = true;
                    break;
                }
            }
            if (mine) continue;
            android.os.Process.killProcess(pid);
            killed.add(name.get(pid) + "(" + pid + ")");
        }
        if (!killed.isEmpty()) log("이전 세션에서 남은 프로세스 " + killed.size() + "개 멈춤: " + String.join(", ", killed));
    }

    /** Lines of the Steam log shown in the app log (case-insensitive)
     *  앱 로그에 보여 줄 Steam 로그 줄(대소문자 무시) */
    private static final java.util.regex.Pattern STEAM_KEY = java.util.regex.Pattern.compile(
            "error|fail|fatal|denied|missing|not found|update|download|restart|webhelper|login|exit|lsof|assert|ezs-pad|ezs-io"
                    + "|websocket|ezproton|proton|compat|launch|wrapper|fex|reaper|appid",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Proton log lines shown in the app log / 앱 로그에 보여 줄 Proton 로그 줄 */
    private static final java.util.regex.Pattern PROTON_KEY = java.util.regex.Pattern.compile(
            "err:|unhandled|wine: |failed|abort|dxvk|vkd3d|vulkan|d3d1|steamapi|presenter|present mode|config env",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    /** FEX's per-site misalignment exception lines are counted, not shown one by one
     *  FEX의 위치별 정렬 예외 줄은 하나씩 보여 주지 않고 개수만 셈 */
    private static final String MISALIGN = "Exception: Code: 80000002";
    /** Current Proton log file, for the [Proton] window / [Proton] 창용 현재 Proton 로그 파일 */
    private volatile File protonLogFile;

    /**
     * Reads the lines appended to a growing file since the last call; starts over when the file shrinks.
     * 지난 호출 이후 늘어난 파일의 새 줄을 읽음. 파일이 줄어들면 처음부터 읽음
     */
    private static final class LogFollower {
        File file;
        long pos;
        final StringBuilder partial = new StringBuilder();
        final byte[] buf = new byte[16384];

        java.util.List<String> poll() {
            java.util.List<String> out = new java.util.ArrayList<>();
            if (file == null) return out;
            try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(file, "r")) {
                if (f.length() < pos) {
                    pos = 0;
                    partial.setLength(0);
                }
                f.seek(pos);
                int n;
                while ((n = f.read(buf)) > 0) {
                    pos += n;
                    partial.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    int nl;
                    while ((nl = partial.indexOf("\n")) >= 0) {
                        out.add(partial.substring(0, nl));
                        partial.delete(0, nl + 1);
                    }
                }
            } catch (IOException ignored) {
                // Not created yet / 아직 없음
            }
            return out;
        }
    }

    /**
     * Follow ~/ezs-logs/steam.log and the newest Proton log (~/ezs-logs/steam-<appid>.log, written
     * because ezproton sets PROTON_LOG) from the app, with no tail/grep processes in the rootfs
     * (they would count against Android's process limit), and show their key lines. When the UI
     * WebSocket check fails, also show the last lsof calls.
     * 앱에서 ~/ezs-logs/steam.log와 가장 최근 Proton 로그(~/ezs-logs/steam-<appid>.log, ezproton이
     * PROTON_LOG를 켜서 생김)를 따라 읽고 주요 줄을 보여 줌. rootfs 안 tail/grep 프로세스를 없애
     * 안드로이드 프로세스 한도를 아낌. UI WebSocket 확인이 실패하면 마지막 lsof 호출도 보여 줌
     */
    private void startSteamLogTail(File rootfs) {
        File logDir = new File(rootfs, "home/steam/ezs-logs");
        File lsofLog = new File(rootfs, "tmp/ezs-lsof.log");
        Thread t = new Thread(() -> {
            LogFollower steam = new LogFollower();
            steam.file = new File(logDir, "steam.log");
            LogFollower proton = new LogFollower();
            boolean wsLogged = false;
            int tick = 0;
            // The first read returns the whole old steam.log: its lines are shown but not taken as
            // things happening now (an old "Upgrading prefix" line showed the banner, 2026-10-19)
            // 첫 읽기는 예전 steam.log 전체를 돌려줌: 줄은 보여 주지만 지금 일어나는 일로 보지 않음
            // (예전 "Upgrading prefix" 줄 때문에 안내 띠가 떴음, 2026-10-19)
            boolean replay = true;
            while (!finishing) {
                for (String line : steam.poll()) {
                    // Steam's self update writes several progress lines a second: shown on the progress
                    // card, logged only when the stage changes (818 "Installing update" lines in one
                    // fresh install, 2026-10-19)
                    // Steam 자체 갱신은 초당 여러 줄의 진행 줄을 씀: 진행 카드에 보이고, 로그에는 단계가 바뀔 때만
                    // 남김(새 설치 한 번에 "Installing update" 줄 818개, 2026-10-19)
                    String stage = updateStage(line, replay);
                    if (stage != null) {
                        if (!stage.equals(lastUpdateStage)) {
                            lastUpdateStage = stage;
                            log("STEAM " + line);
                        }
                        continue;
                    }
                    if (STEAM_KEY.matcher(line).find()) log("STEAM " + line);
                    if (!replay) {
                        // Proton creates a new prefix: the first preparation step, before any installer
                        // Proton이 새 prefix를 만듦: 설치 프로그램보다 앞선 첫 준비 단계
                        if (line.contains("Upgrading prefix from None")) prefixHintMs = SystemClock.elapsedRealtime();
                        // Base prefix steps (ezs-baseprefix.py) / 기본 prefix 단계(ezs-baseprefix.py)
                        if (line.contains("ezproton: base ")) baseStep(line);
                    }
                    // A game started with Valve's Proton directly goes through the Steam Linux Runtime
                    // container (pressure-vessel), which cannot start under PRoot (/proc/self/root
                    // missing, measured); the game then ends right away. EZ Proton skips the container.
                    // Valve Proton을 직접 고른 게임은 Steam Linux Runtime 컨테이너(pressure-vessel)를 거치는데,
                    // PRoot 안에서는 시작하지 못함(/proc/self/root 없음, 실측). 그러면 게임이 바로 끝남.
                    // EZ Proton은 컨테이너를 거치지 않음
                    if (line.contains("pressure-vessel-wrap") && line.contains(" E: ")) {
                        log("STEAM " + line);
                        log("HINT 이 게임의 호환 도구가 EZ Proton이 아님 → 게임 속성 > 호환성에서 "
                                + "\"EZ Proton (Valve ARM64, direct)\" 선택 (또는 Steam 설정 > 호환성의 기본 도구)");
                    }
                    if (!wsLogged && line.contains("Failed to connect to websocket")) {
                        wsLogged = true;
                        log("DIAG ---- lsof 호출 (마지막 10)");
                        try {
                            java.util.List<String> ls = java.nio.file.Files.readAllLines(lsofLog.toPath());
                            for (String l : ls.subList(Math.max(0, ls.size() - 10), ls.size())) log("DIAG " + l);
                        } catch (IOException ignored) {
                        }
                    }
                }
                replay = false;
                // Every 2 s look for a newer Proton log / 2초마다 더 새 Proton 로그를 찾음
                if (tick++ % 4 == 0) {
                    File[] fs = logDir.listFiles((d, name) -> name.startsWith("steam-") && name.endsWith(".log"));
                    File newest = null;
                    if (fs != null) for (File f : fs) if (newest == null || f.lastModified() > newest.lastModified()) newest = f;
                    // Only a log written in the last 15 s: when a game starts, its old log is moved away
                    // first, and the newest left was another game's from long before (its stale lines
                    // came into the report, 2026-10-25)
                    // 최근 15초 안에 쓰인 로그만: 게임이 시작할 때 이전 로그가 먼저 옮겨져, 남은 것 중 가장 새 것이
                    // 한참 전 다른 게임 로그였음(그 오래된 줄이 제보에 섞임, 2026-10-25)
                    if (newest != null && System.currentTimeMillis() - newest.lastModified() > 15000) newest = null;
                    if (newest != null && !newest.equals(proton.file)) {
                        proton.file = newest;
                        protonLogFile = newest;
                        proton.pos = 0;
                        proton.partial.setLength(0);
                        log("PROTON 로그: " + newest.getName());
                    }
                }
                int misalign = 0;
                for (String line : proton.poll()) {
                    if (line.contains(MISALIGN)) {
                        misalign++;
                    } else if (PROTON_KEY.matcher(line).find()) {
                        log("PROTON " + (line.length() > 220 ? line.substring(0, 220) : line));
                    }
                }
                if (misalign > 0) log("PROTON (FEX 정렬 예외 " + misalign + "건)");
                SystemClock.sleep(500);
            }
        }, "ezs-steamlog");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Every 2 s: count this app's child processes (PRoot and everything under it) and free memory.
     * Exit 137 of the whole session points at Android ending our processes (process limit or low
     * memory); these numbers tell which.
     * 2초마다 이 앱의 자식 프로세스(PRoot와 그 아래 전부) 수와 여유 메모리를 셈.
     * 세션 전체가 137로 끝나면 안드로이드가 프로세스를 종료시킨 것(프로세스 한도 또는 메모리 부족)이며,
     * 이 숫자로 어느 쪽인지 가림
     */
    private void startMonitor() {
        Thread t = new Thread(() -> {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            // CPU ticks per pid at the previous pass / 이전 회차의 pid별 CPU 틱
            java.util.Map<Integer, Long> lastTicks = new java.util.HashMap<>();
            long lastNs = 0;
            int pass = 0;
            long lastRx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid());
            long lastRxNs = System.nanoTime();
            while (!finishing) {
                long scan0 = System.nanoTime();
                java.util.List<app.ezsteam.runtime.ChildProcessCounter.Proc> ps =
                        app.ezsteam.runtime.ChildProcessCounter.descendants(android.os.Process.myPid());
                long scanNs = System.nanoTime() - scan0;
                procNow = ps.size();
                updateCpu(ps, lastTicks, lastNs);
                logCefHelpers(ps);
                updatePrep(ps);
                checkBasePrefix(ps);
                lastNs = System.nanoTime();
                java.util.Map<Integer, Float> pct = cpuPct;
                StringBuilder list = new StringBuilder();
                for (app.ezsteam.runtime.ChildProcessCounter.Proc pr : ps) {
                    Float c = pct.get(pr.pid);
                    list.append(String.format(Locale.ROOT, "%6d %c %5dMB %7.1fs %4.0f%%  %s%n",
                            pr.pid, pr.state, pr.rssKb / 1024, pr.cpuTicks / 100.0, c == null ? 0f : c, pr.name));
                }
                procListNow = list.toString();
                if (procNow > procMax) {
                    procMax = procNow;
                    procListAtMax = new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new java.util.Date())
                            + " 기준\n" + procListNow;
                }
                am.getMemoryInfo(mi);
                memFreeMb = mi.availMem >> 20;
                // HUD card only while it is shown (PSS and grouping cost nothing when hidden)
                // HUD 카드는 보일 때만(숨기면 PSS·묶음 비용 없음)
                if (hudMode == 2) updateHud(ps, scanNs);
                measureTick();
                if (procNow >= PROC_WARN && !procListLogged) {
                    procListLogged = true;
                    StringBuilder sb = new StringBuilder("PROC 자식 프로세스 " + procNow + "개 (한도 32):");
                    for (app.ezsteam.runtime.ChildProcessCounter.Proc pr : ps) {
                        sb.append("\nPROC   ").append(pr.pid).append(' ').append(pr.name).append(' ')
                                .append(pr.rssKb / 1024).append("MB");
                    }
                    log(sb.toString());
                }
                // Once a minute: CPU, network receive rate of this app (Steam downloads included),
                // front/back, CPU clock, battery temperature, so a slowdown can be told apart
                // (background, heat, or the network)
                // 1분마다: CPU, 이 앱의 네트워크 수신 속도(Steam 다운로드 포함), 앞/뒤, CPU 클럭, 배터리
                // 온도. 느려진 원인(백그라운드, 발열, 네트워크)을 가리기 위함
                if (++pass % 30 == 0) {
                    long rx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid());
                    long ns = System.nanoTime();
                    float mbps = rx < 0 || lastRx < 0 ? -1f : (rx - lastRx) * 8f / ((ns - lastRxNs) / 1e3f);
                    lastRx = rx;
                    lastRxNs = ns;
                    // New frames per second (average of the minute) and which output was shown:
                    // direct = game frames from the present layer (with how much of the X screen the
                    // window covers), X11 = the X screen / 초당 새 프레임(그 분의 평균)과 보인 출력:
                    // 직접 = present 레이어 게임 프레임(창이 X 화면을 덮는 비율 포함), X11 = X 화면
                    float avgChanged = changedCount > 0 ? changedSum / changedCount : 0f;
                    changedSum = 0f;
                    changedCount = 0;
                    String out = view.showingGame
                            ? String.format(Locale.ROOT, "직접(창 %.0f%%)", view.gameCover * 100f) : "X11";
                    log(String.format(Locale.ROOT, "PERF %s  새 프레임 %.0f/s  출력 %s  수신 %s  화면 %s  CPU 최고 클럭 %s  배터리 %s  여유 메모리 %.1fGB  입력→화면 %s",
                            cpuText, avgChanged, out, mbps < 0 ? "-" : String.format(Locale.ROOT, "%.1f Mbps", mbps),
                            inFront ? "앞" : "뒤", maxCpuClock(), batteryTemp(), memFreeMb / 1024f, view.takeInputLatency()));
                }
                SystemClock.sleep(2000);
            }
        }, "ezs-monitor");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Build one HUD snapshot from what the monitor pass already has, plus PSS every 30 s, and hand it
     * to the card. Measures nothing in the frame path; values not available are "N/A". The cost line
     * shows this pass's own time (process list, PSS, grouping), so the HUD's overhead is visible.
     * 모니터 회차가 이미 가진 값과 30초마다의 PSS로 HUD 스냅숏을 만들어 카드에 넘김. 프레임 경로에서 재는 것은 없고,
     * 없는 값은 "N/A". 비용 줄은 이 회차 자체 시간(프로세스 목록, PSS, 묶음)을 보여 줘 HUD 오버헤드가 보이게 함
     */
    private void updateHud(java.util.List<app.ezsteam.runtime.ChildProcessCounter.Proc> ps, long scanNs) {
        long t0 = System.nanoTime();
        app.ezsteam.runtime.PerfHud.Snapshot s = new app.ezsteam.runtime.PerfHud.Snapshot();
        app.ezsteam.runtime.ChildProcessCounter.Proc game = gameOf(ps);
        int inProot = 0;
        long rssKb = 0;
        // Groups in a fixed order / 고정 순서의 묶음
        String[] names = {"Steam", "CEF", "Proton/Wine", "Game", "Display", "기타"};
        int[] now = new int[names.length];
        for (app.ezsteam.runtime.ChildProcessCounter.Proc pr : ps) {
            String comm = exeName(pr);
            if (!comm.contains("libproot")) inProot++;
            rssKb += pr.rssKb;
            now[hudGroup(comm, game != null && pr.pid == game.pid)]++;
        }
        s.procValue = String.valueOf(procNow);
        s.procSub = String.format(Locale.ROOT, "Peak %d · 목표 %d · 한도 32 · PRoot 안 %d", procMax, PROC_TARGET, inProot);

        // PSS every 30 s from /proc/<pid>/smaps_rollup (same uid, readable); N/A when none could be read
        // PSS는 30초마다 /proc/<pid>/smaps_rollup에서(같은 uid라 읽힘). 하나도 못 읽으면 N/A
        long ms = SystemClock.elapsedRealtime();
        if (pssAtMs == 0 || ms - pssAtMs >= 30_000) {
            long p0 = System.nanoTime();
            pssKb = readPssKb(ps);
            // smaps_rollup was not readable on the device (PSS N/A, 2026-10-30): ask the system,
            // which reports processes of the caller's own uid / 기기에서 smaps_rollup을 못 읽었음(PSS N/A,
            // 2026-10-30): 호출한 앱과 같은 uid의 프로세스를 알려 주는 시스템에 물음
            if (pssKb < 0) pssKb = systemPssKb(ps);
            pssCostNs = System.nanoTime() - p0;
            pssAtMs = ms;
        }
        s.memValue = String.format(Locale.ROOT, "%.2f GB", rssKb / 1048576f);
        s.memSub = "RSS 합 · PSS " + (pssKb < 0 ? "N/A" : String.format(Locale.ROOT, "%.2f GB", pssKb / 1048576f))
                + String.format(Locale.ROOT, " · 기기 여유 %.1f GB", memFreeMb / 1024f);

        float all = cpuAll;
        s.cpuValue = all < 0 ? "N/A" : String.format(Locale.ROOT, "%.0f%%", all);
        s.cpuSub = "100% = 코어 1개" + (cpuTop.isEmpty() ? "" : " · 최고 " + cpuTop);

        float[] fs = view.frameStats(10_000);
        boolean gameShown = view.showingGame;
        s.fpsValue = fs == null ? "N/A" : String.format(Locale.ROOT, "%.0f FPS", fs[0]);
        s.fpsSub = fs == null ? "10초 동안 새 프레임 없음"
                : String.format(Locale.ROOT, "평균 %.1fms · P95 %.1fms (%s, 10초)", fs[1], fs[2], gameShown ? "게임" : "X 화면");

        s.renderer = hudRenderer;
        s.display = hudXserver + (gameShown ? " + 게임 직접 전달" : "");
        s.buffer = gameShown ? "AHardwareBuffer " + (MainActivity.ahbCompress(this) ? "(압축 시험 켬)" : "(linear)")
                : "X 화면 파일 (mmap)";
        // Copies per frame from the code of the active path, not measured (marked so)
        // 프레임당 복사 횟수는 지금 경로의 코드 기준, 측정값 아님(그렇게 표시)
        boolean alone = gameShown && view.gameCover >= 0.95f;
        String xCopies = "Xwayland".equals(hudXserver) ? "2 / 2" : "1 / 1";
        s.copies = (alone ? "1 / 0" : gameShown ? "게임 1 / 0, X " + xCopies : xCopies) + " (코드 기준)";

        String[][] rows = new String[names.length][];
        synchronized (hudGroupMax) {
            for (int i = 0; i < names.length; i++) {
                Integer m = hudGroupMax.get(names[i]);
                int max = Math.max(m == null ? 0 : m, now[i]);
                hudGroupMax.put(names[i], max);
                rows[i] = new String[]{names[i], String.valueOf(now[i]), String.valueOf(max)};
            }
        }
        s.groups = rows;
        s.badge = gameShown ? "GAME" : "RUNNING";
        long sec = (SystemClock.elapsedRealtime() - startMs) / 1000;
        long buildNs = System.nanoTime() - t0;
        s.footer = String.format(Locale.ROOT, "실행 %d:%02d:%02d · 수집 비용: 목록 %.1fms + 정리 %.1fms / 2초, PSS %.0fms / 30초",
                sec / 3600, sec / 60 % 60, sec % 60, scanNs / 1e6, buildNs / 1e6, pssCostNs / 1e6);
        boolean over = procNow > PROC_TARGET;
        runOnUiThread(() -> {
            hud.update(s);
            hud.setProcessWarning(over);
        });
    }

    /** HUD group of a process (index into the fixed name list) / 프로세스의 HUD 묶음(고정 이름 목록 번호) */
    private static int hudGroup(String comm, boolean isGame) {
        if (isGame) return 3;
        if (comm.equals("steam")) return 0;
        if (comm.startsWith("steamwebhelper")) return 1;
        if (comm.startsWith("wine") || comm.endsWith(".exe") || comm.equals("reaper") || comm.startsWith("pw-audio")
                || comm.startsWith("UnityCrash")) return 2;
        if (comm.equals("Xwayland") || comm.equals("Xvfb") || comm.equals("ezs-wl")) return 4;
        return 5;
    }

    /** PSS through ActivityManager (same-uid processes), -1 when it gives nothing; the reason is logged once
     *  ActivityManager로 얻은 PSS(같은 uid 프로세스). 아무것도 없으면 -1. 이유는 한 번 기록함 */
    private long systemPssKb(java.util.List<app.ezsteam.runtime.ChildProcessCounter.Proc> ps) {
        try {
            int[] pids = new int[ps.size()];
            for (int i = 0; i < pids.length; i++) pids[i] = ps.get(i).pid;
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            android.os.Debug.MemoryInfo[] mi = am.getProcessMemoryInfo(pids);
            long sum = 0;
            for (android.os.Debug.MemoryInfo m : mi) if (m != null) sum += m.getTotalPss();
            if (sum > 0) return sum;
            if (!pssWhyLogged) log("HUD: PSS 없음 (smaps_rollup 읽기 불가, 시스템 답 0)");
        } catch (RuntimeException e) {
            if (!pssWhyLogged) log("HUD: PSS 없음 (smaps_rollup 읽기 불가, 시스템 " + e + ")");
        }
        pssWhyLogged = true;
        return -1;
    }

    private boolean pssWhyLogged;

    /** Sum of PSS over the processes in kB, -1 when none was readable / 프로세스들의 PSS 합(kB). 하나도 못 읽으면 -1 */
    private static long readPssKb(java.util.List<app.ezsteam.runtime.ChildProcessCounter.Proc> ps) {
        long sum = 0;
        int read = 0;
        for (app.ezsteam.runtime.ChildProcessCounter.Proc pr : ps) {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/" + pr.pid + "/smaps_rollup"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("Pss:")) {
                        sum += Long.parseLong(line.replaceAll("[^0-9]", ""));
                        read++;
                        break;
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // gone or not readable / 끝났거나 읽을 수 없음
            }
        }
        return read == 0 ? -1 : sum;
    }

    /**
     * CPU use of each child process since the previous pass (tick difference / elapsed time;
     * 100 = one core fully busy), and the top-bar summary: all children, the game, X server, PRoot.
     * First pass only records the ticks. Processes gone since then are dropped.
     * 이전 회차 이후 자식 프로세스별 CPU 사용률(틱 차이 / 경과 시간, 100 = 코어 하나를 다 씀)과
     * 위쪽 막대 요약: 자식 전체, 게임, X 서버, PRoot. 첫 회차는 틱만 기록함. 그사이 끝난 프로세스는 뺌
     */
    private void updateCpu(java.util.List<app.ezsteam.runtime.ChildProcessCounter.Proc> ps,
                           java.util.Map<Integer, Long> lastTicks, long lastNs) {
        long now = System.nanoTime();
        float sec = lastNs == 0 ? 0f : (now - lastNs) / 1e9f;
        java.util.Map<Integer, Float> pct = new java.util.HashMap<>();
        java.util.Map<Integer, Long> ticks = new java.util.HashMap<>();
        float all = 0, xvfb = 0, proot = 0, ui = 0, top = -1f;
        String topName = "";
        app.ezsteam.runtime.ChildProcessCounter.Proc game = gameOf(ps);
        lastProcs = ps;
        for (app.ezsteam.runtime.ChildProcessCounter.Proc pr : ps) {
            ticks.put(pr.pid, pr.cpuTicks);
            Long before = lastTicks.get(pr.pid);
            if (sec <= 0 || before == null) continue;
            // Ticks are 1/100 s, so ticks / s = percent of one core / 틱은 1/100초이므로 틱 / 초 = 코어 하나 기준 %
            float p = Math.max(0, pr.cpuTicks - before) / sec;
            pct.put(pr.pid, p);
            all += p;
            String comm = exeName(pr);
            if (p > top) {
                top = p;
                topName = comm;
            }
            // X server column: Xvfb, or Xwayland plus its compositor ezs-wl (the column counted only
            // Xvfb and showed 0% in Xwayland sessions, 2026-10-29)
            // X 서버 칸: Xvfb, 또는 Xwayland와 그 컴포지터 ezs-wl(Xvfb만 세어 Xwayland 세션에서 0%로 보였음, 2026-10-29)
            if (comm.equals("Xvfb") || comm.equals("Xwayland") || comm.equals("ezs-wl")) xvfb += p;
            if (comm.contains("libproot")) proot += p;
            // Steam screen (web helper processes), to compare GPU and software drawing
            // Steam 화면(웹 도우미 프로세스). GPU 그리기와 소프트웨어 그리기 비교용
            if (comm.startsWith("steamwebhelper")) ui += p;
        }
        lastTicks.clear();
        lastTicks.putAll(ticks);
        cpuPct = pct;
        if (sec <= 0) return;
        cpuAll = all;
        cpuUi = ui;
        cpuProot = proot;
        cpuX = xvfb;
        cpuTop = top < 0 ? "" : String.format(Locale.ROOT, "%s %.0f%%", topName, top);
        Float g = game == null ? null : pct.get(game.pid);
        cpuText = String.format(Locale.ROOT, "CPU 전체 %.0f%%  게임 %s  Steam 화면 %.0f%%  X 서버 %.0f%%  PRoot %.0f%%",
                all, g == null ? "-" : String.format(Locale.ROOT, "%.0f%%", g), ui, xvfb, proot);
    }

    /**
     * Highest current clock over all CPU cores, "-" when the files are not readable
     * 모든 CPU 코어 중 가장 높은 현재 클럭. 파일을 읽을 수 없으면 "-"
     */
    private static String maxCpuClock() {
        long best = -1;
        for (int i = 0; i < 16; i++) {
            File f = new File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            if (!f.exists()) continue;
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
                best = Math.max(best, Long.parseLong(r.readLine().trim()));
            } catch (IOException | RuntimeException ignored) {
                // not readable on this device / 이 기기에서는 읽을 수 없음
            }
        }
        return best <= 0 ? "-" : String.format(Locale.ROOT, "%.2fGHz", best / 1e6);
    }

    /** Battery temperature from the sticky battery broadcast / 고정 배터리 방송의 배터리 온도 */
    private String batteryTemp() {
        android.content.Intent b = registerReceiver(null,
                new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        int t = b == null ? Integer.MIN_VALUE : b.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        return t == Integer.MIN_VALUE ? "-" : String.format(Locale.ROOT, "%.1f°C", t / 10f);
    }

    /** Free space of the filesystem holding a folder, in GB / 폴더가 있는 파일 시스템의 여유 공간(GB) */
    private static String freeGb(File dir) {
        try {
            android.os.StatFs st = new android.os.StatFs(dir.getAbsolutePath());
            return String.format(Locale.ROOT, "%.1fGB", st.getAvailableBytes() / 1e9);
        } catch (RuntimeException e) {
            return "-";
        }
    }

    private android.net.ConnectivityManager.NetworkCallback netCallback;

    /**
     * Keep rootfs /tmp/ezs-net in step with the phone's network: "1 wifi", "1 ethernet",
     * "1 cellular" or "0". The NetworkManager stand-in in the rootfs (ezs-netstate.py) reports it
     * to the Steam client. Only the kind and up/down, nothing about the network itself.
     * rootfs /tmp/ezs-net을 휴대폰 네트워크에 맞춰 둠: "1 wifi", "1 ethernet", "1 cellular" 또는 "0". rootfs 안
     * NetworkManager 대역(ezs-netstate.py)이 Steam 클라이언트에 알림. 종류와 연결 여부만, 네트워크 자체 정보는 없음
     */
    private void watchNetwork(File rootfs) {
        File f = new File(rootfs, "tmp/ezs-net");
        android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null || netCallback != null) return;
        android.net.ConnectivityManager.NetworkCallback cb = new android.net.ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(android.net.Network n, android.net.NetworkCapabilities nc) {
                String kind = nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ? "wifi"
                        : nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ? "cellular" : "ethernet";
                write(nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ? "1 " + kind : "0");
            }

            @Override
            public void onLost(android.net.Network n) {
                write("0");
            }

            private void write(String v) {
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
                    o.write((v + "\n").getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // rootfs /tmp not there yet: the stand-in counts the network as up
                    // rootfs /tmp가 아직 없음: 대역은 연결됨으로 봄
                }
            }
        };
        try {
            cm.registerDefaultNetworkCallback(cb);
            netCallback = cb;
        } catch (RuntimeException e) {
            log("네트워크 상태 감시 실패: " + e);
        }
    }

    /** The device's active network is a VPN / 기기의 현재 네트워크가 VPN임 */
    private boolean vpnActive() {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network n = cm == null ? null : cm.getActiveNetwork();
            android.net.NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            return nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** IPv4 / IPv6 addresses in a log line / 로그 줄의 IPv4 / IPv6 주소 */
    private static final java.util.regex.Pattern IPV4_ADDR = java.util.regex.Pattern.compile(
            "(?<![0-9.])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9.])");
    private static final java.util.regex.Pattern IPV6_ADDR = java.util.regex.Pattern.compile(
            "(?<![0-9A-Za-z:])(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}(?![0-9A-Za-z:])");

    /** Line with every address replaced by its kind (<v4>, <v6>) / 모든 주소를 종류(<v4>, <v6>)로 바꾼 줄 */
    static String maskAddresses(String line) {
        String out = IPV4_ADDR.matcher(line).replaceAll("<v4>");
        java.util.regex.Matcher m = IPV6_ADDR.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            // At least 3 colons, so times like 05:35:09 stay / 콜론 3개 이상만. 05:35:09 같은 시각은 남김
            String g = m.group();
            m.appendReplacement(sb, g.chars().filter(c -> c == ':').count() >= 3 && !g.matches("[0-9:]+")
                    ? "<v6>" : java.util.regex.Matcher.quoteReplacement(g));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Last logged web helper summary / 마지막으로 기록한 웹 도우미 요약 */
    private String cefLogged = "";
    private boolean cefFeaturesLogged;

    /**
     * When the set of Steam web helper processes changes: one line with their count and kinds
     * (Chromium --type and --utility-sub-type from /proc/<pid>/cmdline), and once the feature
     * switches of the browser process. Shows which helper kinds are left to merge (32 process
     * limit, a first game start ended at 31 with 7 helpers, 2026-10-18).
     * Steam 웹 도우미 프로세스 구성이 바뀔 때: 개수와 종류 한 줄(/proc/<pid>/cmdline의 Chromium --type,
     * --utility-sub-type), 그리고 브라우저 프로세스의 기능 스위치 한 번. 합칠 수 있는 도우미 종류를 보기
     * 위함(프로세스 한도 32, 게임 첫 실행이 도우미 7개로 31개에서 끝났음, 2026-10-18)
     */
    private void logCefHelpers(java.util.List<app.ezsteam.runtime.ChildProcessCounter.Proc> ps) {
        java.util.TreeMap<String, Integer> kinds = new java.util.TreeMap<>();
        int n = 0;
        for (app.ezsteam.runtime.ChildProcessCounter.Proc pr : ps) {
            if (!exeName(pr).startsWith("steamwebhelper")) continue;
            String[] args;
            try {
                byte[] raw = java.nio.file.Files.readAllBytes(new File("/proc/" + pr.pid + "/cmdline").toPath());
                args = new String(raw, StandardCharsets.UTF_8).split("\0");
            } catch (IOException | RuntimeException e) {
                continue;
            }
            String type = "browser", sub = null;
            for (String a : args) {
                if (a.startsWith("--type=")) type = a.substring(7);
                else if (a.startsWith("--utility-sub-type=")) sub = a.substring(19);
            }
            if (type.equals("browser") && !cefFeaturesLogged) {
                StringBuilder f = new StringBuilder();
                for (String a : args) {
                    if (a.startsWith("--enable-features=") || a.startsWith("--disable-features=")
                            || a.equals("--single-process") || a.startsWith("--renderer-process-limit")) {
                        f.append(' ').append(a);
                    }
                }
                cefFeaturesLogged = true;
                log("CEF 브라우저 스위치:" + (f.length() == 0 ? " (기능 스위치 없음)" : f.toString()));
            }
            if (type.equals("browser") || type.equals("gpu-process")) logCefGpuLibs(pr.pid);
            String k = sub == null ? type : type + "(" + sub + ")";
            kinds.merge(k, 1, Integer::sum);
            n++;
        }
        if (n == 0) return;
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : kinds.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey());
            if (e.getValue() > 1) sb.append('×').append(e.getValue());
        }
        String line = "CEF 도우미 " + n + "개: " + sb;
        if (!line.equals(cefLogged)) {
            cefLogged = line;
            log(line);
        }
    }

    /** Last GPU library line and when it was checked / 마지막 GPU 라이브러리 줄과 확인 시각 */
    private String cefGpuLogged;
    private long cefGpuCheckMs;

    /**
     * Which graphics libraries the web helper process that draws has loaded (its memory map), at
     * most every 30 s, logged when the set changes. Shows whether the Steam UI GPU test really
     * uses the GPU: ANGLE (libEGL/libGLESv2) with the Vulkan loader and Turnip
     * (libvulkan_freedreno) means GPU; SwiftShader or none means software drawing.
     * 그리기를 맡은 웹 도우미 프로세스가 불러온 그래픽 라이브러리(메모리 맵)를 최대 30초마다 보고, 구성이
     * 바뀌면 기록함. Steam 화면 GPU 시험이 실제로 GPU를 쓰는지 보여 줌: ANGLE(libEGL/libGLESv2)과
     * Vulkan 로더, Turnip(libvulkan_freedreno)이 있으면 GPU, SwiftShader거나 없으면 소프트웨어 그리기
     */
    private void logCefGpuLibs(int pid) {
        long now = SystemClock.elapsedRealtime();
        if (now - cefGpuCheckMs < 30_000) return;
        cefGpuCheckMs = now;
        java.util.TreeSet<String> libs = new java.util.TreeSet<>();
        try (BufferedReader r = new BufferedReader(new java.io.FileReader("/proc/" + pid + "/maps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                int i = line.lastIndexOf('/');
                if (i < 0) continue;
                String name = line.substring(i + 1);
                String low = name.toLowerCase(Locale.ROOT);
                if (low.startsWith("libegl") || low.startsWith("libglesv2") || low.startsWith("libvulkan")
                        || low.contains("swiftshader") || low.contains("freedreno") || low.startsWith("libgl.")
                        || low.startsWith("libglx") || low.startsWith("libgallium") || low.startsWith("libgbm")) {
                    // Zink GL test: mark the copies from the mesax bundle / Zink GL 시험: mesax 묶음의 것은 표시함
                    libs.add(line.contains("/opt/ezsteam/mesax/") ? name + "(mesax)" : name);
                }
            }
        } catch (IOException | RuntimeException e) {
            return;
        }
        String line = "CEF 그래픽 라이브러리(pid " + pid + "): " + (libs.isEmpty() ? "없음" : String.join(", ", libs));
        // pid is left out of the comparison so a restart with the same set is not logged again
        // 같은 구성으로 다시 시작한 경우 다시 기록하지 않도록 pid는 비교에서 뺌
        String key = String.join(",", libs);
        if (!key.equals(cefGpuLogged)) {
            cefGpuLogged = key;
            log(line);
        }
    }

    /** Process name before " | " (kernel comm) / " | " 앞의 프로세스 이름(커널 comm) */
    private static String exeName(app.ezsteam.runtime.ChildProcessCounter.Proc pr) {
        int i = pr.name.indexOf(" | ");
        return i >= 0 ? pr.name.substring(0, i) : pr.name;
    }

    // ---- progress card / 진행 카드 ----

    private String lastUpdateStage;
    private static final java.util.regex.Pattern UPDATE_DL =
            java.util.regex.Pattern.compile("Downloading update \\((\\d+) of (\\d+) KB\\)");

    /**
     * Steam self-update progress line → card (unless a Steam window is already up); returns the
     * stage name for such lines (used to log each stage once), null for other lines.
     * Steam 자체 갱신 진행 줄 → 카드(Steam 창이 이미 떠 있으면 제외). 그런 줄이면 단계 이름을, 아니면 null을 돌려줌
     * (단계마다 한 번만 기록하는 데 씀)
     */
    private String updateStage(String line, boolean replay) {
        java.util.regex.Matcher m = UPDATE_DL.matcher(line);
        if (m.find()) {
            if (!replay && !steamWindowSeen) {
                long got = Long.parseLong(m.group(1)), all = Long.parseLong(m.group(2));
                int pct = all > 0 ? (int) (got * 100 / all) : -1;
                showSetup(s(R.string.setup_update_dl), pct, String.format(Locale.ROOT, "%.0f / %.0f MB", got / 1024f, all / 1024f));
            }
            return "download";
        }
        if (line.contains("Installing update") || line.contains("Extracting package")) {
            if (!replay && !steamWindowSeen) showSetup(s(R.string.setup_update_install), -1, s(R.string.setup_update_install_detail));
            return "install";
        }
        if (line.contains("Verifying installation")) {
            if (!replay && !steamWindowSeen) showSetup(s(R.string.setup_verify), -1, "");
            return "verify";
        }
        if (line.contains("Set status message:") || line.matches("^\\[(----|\\s*\\d+%)\\].*")) return "status";
        return null;
    }

    /** Zink GL test switch was on at this start / 이번 시작에 Zink GL 시험 스위치가 켜져 있었음 */
    private volatile boolean zinkOn;

    /**
     * GPU display path test switches (MainActivity [시험], files in ~/.local/share/ezsteam), each off
     * by default = the current path:
     *   zink-gl   EZS_ZINK=1: steam-run.sh / EZ Proton put Zink (OpenGL on Vulkan, /opt/ezsteam/mesax)
     *             in front of GL for the Steam client and games
     *   gpu-node  /dev/kgsl-3d0 also bound to /dev/dri/renderD128, EZS_GPU_NODE=1 (libdrm shim,
     *             Turnip DRM numbers, X11 DRI3 path)
     *   xwayland  EZS_XSERVER=xwayland: display.sh starts ezs-wl + Xwayland instead of Xvfb
     * GPU 화면 경로 시험 스위치(MainActivity [시험], ~/.local/share/ezsteam의 파일). 각각 기본 끔 = 지금 경로:
     *   zink-gl   EZS_ZINK=1: steam-run.sh / EZ Proton이 Steam 클라이언트와 게임의 GL 앞에 Zink(Vulkan 위 OpenGL,
     *             /opt/ezsteam/mesax)를 둠
     *   gpu-node  /dev/kgsl-3d0을 /dev/dri/renderD128에도 바인드, EZS_GPU_NODE=1(libdrm 심, Turnip DRM 번호, X11 DRI3 경로)
     *   xwayland  EZS_XSERVER=xwayland: display.sh가 Xvfb 대신 ezs-wl + Xwayland를 띄움
     */
    private void applyDisplayPathSwitches(GlibcLauncher.Config c) {
        boolean zink = MainActivity.testSwitch(this, MainActivity.SW_ZINK);
        boolean node = MainActivity.testSwitch(this, MainActivity.SW_GPU_NODE);
        boolean xwl = MainActivity.testSwitch(this, MainActivity.SW_XWAYLAND);
        zinkOn = zink;
        if (zink) c.env.put("EZS_ZINK", "1");
        String nodeState = "끔";
        if (node) {
            File kgsl = new File("/dev/kgsl-3d0");
            if (kgsl.exists()) {
                c.binds.add(new String[]{kgsl.getPath(), "/dev/dri/renderD128"});
                c.env.put("EZS_GPU_NODE", "1");
                nodeState = "켬 (/dev/kgsl-3d0 → /dev/dri/renderD128)";
            } else {
                nodeState = "켬이나 /dev/kgsl-3d0 없음 → 끔";
            }
        }
        if (xwl) c.env.put("EZS_XSERVER", "xwayland");
        log("시험 스위치: Zink GL " + (zink ? "켬" : "끔") + ", GPU 장치 " + nodeState
                + ", 화면 방식 " + (xwl ? "Xwayland" : "Xvfb"));
    }

    /** This Steam start drew its UI with the GPU / was started in Big Picture (from the start line)
     *  이번 Steam 시작이 화면을 GPU로 그림 / 빅픽처로 시작함(시작 줄에서 읽음) */
    private volatile boolean steamUiGpu, steamStartBp, bpRestartAsked;

    /**
     * Steam's Big Picture window came up. Big Picture drawn with the Steam UI GPU stays black with
     * only sound (Y700 Gen5 2026-10-22, another device 2026-10-26: "failed to create drawable" in the
     * web helper output when Big Picture was entered from a normal window started with the GPU).
     * steam-run.sh turns the GPU off only for a Big Picture start, so when Big Picture is entered
     * inside a GPU session the app marks the next start as Big Picture (tmp/ezs-bigpicture-next,
     * read by steam-run.sh) and ends the client with SIGTERM; steam-run.sh then starts it again
     * with -gamepadui and without the GPU. No watcher process in the guest (the process limit).
     * Steam 빅픽처 창이 뜸. Steam 화면 GPU로 그린 빅픽처는 소리만 나고 검은 화면임(Y700 Gen5 2026-10-22, 다른 기기
     * 2026-10-26: GPU로 시작한 일반 창에서 빅픽처로 들어가자 웹 도우미 출력에 "failed to create drawable"). steam-run.sh는
     * 빅픽처로 시작할 때만 GPU를 끄므로, GPU 세션 안에서 빅픽처로 들어가면 앱이 다음 시작을 빅픽처로 표시하고
     * (tmp/ezs-bigpicture-next, steam-run.sh가 읽음) SIGTERM으로 클라이언트를 끝냄. 그러면 steam-run.sh가 -gamepadui,
     * GPU 없이 다시 시작함. 게스트에 감시 프로세스는 두지 않음(프로세스 한도)
     */
    private void onBigPicture(int win, String sign) {
        if (!steamUiGpu || steamStartBp || bpRestartAsked) return;
        // Zink GL test: Big Picture stays on the GPU so the GL window can be checked (no restart)
        // Zink GL 시험: GL 창을 확인할 수 있게 빅픽처를 GPU로 둠(다시 시작하지 않음)
        if (zinkOn) {
            bpRestartAsked = true;
            log(String.format(Locale.ROOT, "빅픽처 창 0x%x(%s): Zink GL 켬 → GPU로 그대로 둠 (다시 시작 안 함)", win, sign));
            return;
        }
        GlibcLauncher.Config c = sessionConfig;
        if (c == null) return;
        // A running game would end with the client: leave it, tell the user
        // 실행 중인 게임은 클라이언트와 함께 끝나므로 두고 사용자에게 알림
        java.util.List<app.ezsteam.runtime.ChildProcessCounter.Proc> ps = lastProcs;
        if (ps != null && gameOf(ps) != null) {
            log(String.format(Locale.ROOT, "빅픽처 창 0x%x(%s): 게임 실행 중이라 다시 시작하지 않음", win, sign));
            notice(s(R.string.notice_bp_game));
            return;
        }
        bpRestartAsked = true;
        new Thread(() -> {
            File mark = new File(c.rootfs, "tmp/ezs-bigpicture-next");
            try {
                if (!mark.createNewFile() && !mark.isFile()) throw new IOException("만들 수 없음");
            } catch (IOException e) {
                log("빅픽처 다시 시작 표시 실패: " + e.getMessage());
                return;
            }
            log(String.format(Locale.ROOT, "빅픽처 창 0x%x(%s): Steam 화면 GPU로는 검은 화면 → Steam을 빅픽처로 다시 시작", win, sign));
            // The client's main process (and its update helper): kernel name "steam". Proc.name is
            // "comm | argv[0]", so the comm part is compared (the plain compare matched nothing,
            // device log 2026-10-26 22:34: "Steam 프로세스 0개")
            // 클라이언트 주 프로세스(와 갱신 도우미): 커널 이름 "steam". Proc.name은 "comm | argv[0]"이라 comm
            // 부분을 비교함(그냥 비교하면 하나도 안 맞았음, 기기 로그 2026-10-26 22:34: "Steam 프로세스 0개")
            int n = 0;
            for (app.ezsteam.runtime.ChildProcessCounter.Proc pr
                    : app.ezsteam.runtime.ChildProcessCounter.descendants(android.os.Process.myPid())) {
                String comm = pr.name;
                int bar = comm.indexOf(" | ");
                if (bar >= 0) comm = comm.substring(0, bar);
                if ("steam".equals(comm)) {
                    android.os.Process.sendSignal(pr.pid, 15);
                    n++;
                }
            }
            log("빅픽처 다시 시작: Steam 프로세스 " + n + "개에 SIGTERM");
            if (n == 0) {
                // Nothing ended: take the mark back so a later exit does not restart Steam, and let
                // the next Big Picture window try again / 끝낸 것이 없음: 표시를 되돌려 나중 종료가 Steam을 다시
                // 시작하지 않게 하고, 다음 빅픽처 창에서 다시 시도하게 함
                if (!mark.delete()) log("빅픽처 다시 시작 표시를 지우지 못함");
                bpRestartAsked = false;
                return;
            }
            notice(s(R.string.notice_bp_restart));
            // The restart card at once, so the black Big Picture window is not what is seen
            // (onSessionLine shows it again at the next start and the new window closes it)
            // 다시 시작 카드를 바로 보임. 검은 빅픽처 창이 보이지 않게 함(onSessionLine이 다음 시작 때 다시
            // 보이고 새 창이 닫음)
            showSetup(s(R.string.setup_restart), -1, s(R.string.setup_restart_detail));
        }, "ezs-bp-restart").start();
    }

    /** Session script lines that change the card (restart after an update) / 카드를 바꾸는 세션 스크립트 줄(갱신 뒤 재시작) */
    private void onSessionLine(String line) {
        if (line.startsWith("INFO steam : 시작 ")) {
            // "... 화면 GPU=1 빅픽처=0": what this start is / 이번 시작이 어떤 것인지
            steamUiGpu = line.contains("화면 GPU=1");
            steamStartBp = line.contains("빅픽처=1");
            bpRestartAsked = false;
        }
        if (line.startsWith("INFO steam : 종료 rc=42")) {
            steamWindowSeen = false;
            showSetup(s(R.string.setup_apply), -1, s(R.string.setup_apply_detail));
        } else if (line.startsWith("INFO steam : 시작 ") && !line.startsWith("INFO steam : 시작 1 ")) {
            steamWindowSeen = false;
            showSetup(s(R.string.setup_restart), -1, s(R.string.setup_restart_detail));
        }
    }

    /** Show the centre card; percent -1 = spinning bar / 가운데 카드 표시. percent -1 = 계속 도는 막대 */
    private void showSetup(String title, int percent, String detail) {
        runOnUiThread(() -> {
            setupTitle.setText(title);
            setupBar.setIndeterminate(percent < 0);
            if (percent >= 0) setupBar.setProgress(percent);
            setupDetail.setText(percent >= 0 ? percent + "%  ·  " + detail : detail);
            setupPanel.setVisibility(View.VISIBLE);
        });
    }

    private void hideSetup() {
        runOnUiThread(() -> setupPanel.setVisibility(View.GONE));
    }

    /**
     * Proton download progress from its install record (appmanifest_4628740.acf: BytesDownloaded /
     * BytesToDownload while StateFlags is not 4), or -1 when not downloading.
     * 설치 기록으로 본 Proton 다운로드 진행(appmanifest_4628740.acf: StateFlags가 4가 아닌 동안
     * BytesDownloaded / BytesToDownload). 받는 중이 아니면 -1
     */
    private int protonDownloadPercent() {
        GlibcLauncher.Config session = sessionConfig;
        if (session == null) return -1;
        String acf = readText(new File(session.rootfs, "home/steam/.local/share/Steam/steamapps/appmanifest_4628740.acf"));
        if (acf.isEmpty()) return -1;
        java.util.regex.Matcher st = java.util.regex.Pattern.compile("\"StateFlags\"\\s+\"(\\d+)\"").matcher(acf);
        if (!st.find() || st.group(1).equals("4")) return -1;
        java.util.regex.Matcher a = java.util.regex.Pattern.compile("\"BytesToDownload\"\\s+\"(\\d+)\"").matcher(acf);
        java.util.regex.Matcher b = java.util.regex.Pattern.compile("\"BytesDownloaded\"\\s+\"(\\d+)\"").matcher(acf);
        if (!a.find() || !b.find()) return 0;
        long all = Long.parseLong(a.group(1)), got = Long.parseLong(b.group(1));
        return all > 0 ? (int) Math.min(100, got * 100 / all) : 0;
    }

    /** Banner shown for the Proton download / Proton 다운로드용으로 띄운 안내 띠 */
    private boolean protonBanner;

    // ---- game launch cover / 게임 실행 가림막 ----

    /**
     * Full-screen cover from the moment Steam starts a game until the game's first window shows.
     * Between the two Proton runs of a start (install script, then the game) and after the last
     * preparation step, the Steam screen came back for a few seconds and looked frozen (DMC5: 6 s,
     * 2026-10-23 log; user report). A tap closes it; it also closes by itself after 3 minutes.
     * Steam가 게임을 시작한 순간부터 게임의 첫 창이 뜰 때까지 화면 전체를 가림. 한 번의 시작에 있는 두 번의
     * Proton 실행(설치 스크립트, 그다음 게임) 사이와 마지막 준비 단계 뒤에 Steam 화면이 몇 초 돌아와 멈춘
     * 것처럼 보였음(DMC5: 6초, 2026-10-23 로그, 사용자 제보). 누르면 닫히고, 3분이 지나면 저절로 닫힘
     */
    private FrameLayout launchCover;
    private TextView launchText;
    /** When the cover was shown (elapsedRealtime), 0 = not shown / 가림막을 띄운 시각, 0이면 안 띄움 */
    private volatile long launchStartMs;
    /** Steam has been started in this session (lines before it are the old log read again)
     *  이번 세션에 Steam이 시작됨(그 전 줄은 다시 읽은 예전 로그) */
    private volatile boolean steamStarted;
    /** The game itself has been started (not only its install script); installer windows before
     *  that do not close the cover / 게임 자체가 시작됨(설치 스크립트만이 아님). 그 전의 설치 프로그램 창은
     *  가림막을 닫지 않음 */
    private volatile boolean launchGameRun;
    private static final long LAUNCH_COVER_MAX_MS = 180_000;

    private void buildLaunchCover(FrameLayout root) {
        float dp = getResources().getDisplayMetrics().density;
        launchCover = new FrameLayout(this);
        launchCover.setBackgroundColor(0xF0000000);
        launchCover.setVisibility(View.GONE);
        launchCover.setClickable(true);
        // Taps are taken and do nothing (people tap while waiting); a swipe slides it away so the
        // screen below shows, in case the game opens no window of its own (user request 2026-10-23)
        // 터치는 받기만 하고 아무것도 안 함(기다리면서 화면을 누름). 밀면 옆으로 밀려나며 아래 화면이 보임.
        // 게임이 자기 창을 열지 않는 경우 대비(사용자 요청 2026-10-23)
        float[] down = new float[2];
        launchCover.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    v.setTranslationX(e.getRawX() - down[0]);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float dx = e.getRawX() - down[0];
                    if (Math.abs(dx) > v.getWidth() * 0.3f) {
                        v.animate().translationX(Math.signum(dx) * v.getWidth()).setDuration(150)
                                .withEndAction(() -> endLaunchCover("밀어서 닫음")).start();
                    } else {
                        v.animate().translationX(0).setDuration(150).start();
                    }
                    break;
            }
            return true;
        });
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.addView(new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleLarge));
        launchText = new TextView(this);
        launchText.setTextColor(Color.WHITE);
        launchText.setTextSize(16);
        launchText.setGravity(Gravity.CENTER);
        launchText.setPadding(0, (int) (16 * dp), 0, (int) (8 * dp));
        col.addView(launchText);
        TextView hint = new TextView(this);
        hint.setTextColor(0x99FFFFFF);
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setText(s(R.string.launch_swipe));
        col.addView(hint);
        launchCover.addView(col, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(launchCover, new FrameLayout.LayoutParams(-1, -1));
    }

    /**
     * Watches log lines (any thread): Steam start, a Proton run of a game (install script or the
     * game itself) opens the cover, the first Wine window (depth 24; Steam's own windows are depth
     * 32 in every log so far) of at least 320 px closes it.
     * 로그 줄 감시(아무 스레드): Steam 시작, 게임의 Proton 실행(설치 스크립트나 게임 자체)이 가림막을 열고,
     * 폭 320px 이상인 첫 Wine 창(깊이 24. 지금까지 모든 로그에서 Steam 자체 창은 깊이 32)이 닫음
     */
    private void launchWatch(String s) {
        if (s.startsWith("INFO steam : 시작 ")) {
            steamStarted = true;
        } else if (steamStarted && s.startsWith("STEAM ezproton: /")
                && (s.contains(" waitforexitandrun ") || s.contains("iscriptevaluator.exe"))) {
            if (s.contains(" waitforexitandrun ")) launchGameRun = true;
            if (launchStartMs == 0) {
                launchStartMs = SystemClock.elapsedRealtime();
                runOnUiThread(() -> {
                    launchCover.setTranslationX(0);
                    launchCover.setVisibility(View.VISIBLE);
                    tickLaunchCover();
                });
                checkPreviousGame();
            }
        } else if (launchStartMs != 0 && launchGameRun && s.startsWith("X 새 창 0x") && s.endsWith("깊이 24")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(": (\\d+)x(\\d+) ").matcher(s);
            if (m.find() && Integer.parseInt(m.group(1)) >= 320) endLaunchCover("게임 창");
        }
    }

    /** Elapsed time and the current preparation step, every second while shown
     *  보이는 동안 1초마다 경과 시간과 지금 준비 단계 */
    private void tickLaunchCover() {
        long start = launchStartMs;
        if (start == 0 || launchCover.getVisibility() != View.VISIBLE) return;
        long t = SystemClock.elapsedRealtime() - start;
        if (t > LAUNCH_COVER_MAX_MS) {
            endLaunchCover("3분 지남");
            return;
        }
        String kind = prepKind;
        launchText.setText((kind != null ? s(R.string.launch_preparing_kind, kind) : s(R.string.launch_preparing)) + " · " + mmss(t));
        // The cover already says it; the small banner would repeat it / 가림막이 이미 알리므로 작은 안내 띠는 숨김
        prepBox.setVisibility(View.GONE);
        launchCover.postDelayed(this::tickLaunchCover, 1000);
    }

    private void endLaunchCover(String why) {
        long start = launchStartMs;
        if (start == 0) return;
        launchStartMs = 0;
        launchGameRun = false;
        log("게임 실행 가림막 닫힘: " + why + " (" + mmss(SystemClock.elapsedRealtime() - start) + ")");
        runOnUiThread(() -> launchCover.setVisibility(View.GONE));
    }

    /** Child processes of the last monitor pass / 마지막 감시 회차의 자식 프로세스 */
    private volatile java.util.List<app.ezsteam.runtime.ChildProcessCounter.Proc> lastProcs;

    /**
     * Another game is starting while one still runs: two games went over Android's limit of 32
     * child processes for all apps and the whole session was killed (exit 137, 36 processes;
     * the first game was still running in Steam's list although it looked closed, 2026-10-23).
     * Default: tell; option on the main screen: end the old game first.
     * 게임 하나가 아직 실행 중인데 다른 게임이 시작됨: 두 게임이 안드로이드의 모든 앱 합계 자식 프로세스 한도
     * 32개를 넘어 세션 전체가 종료됐음(exit 137, 36개. 앞 게임은 꺼진 듯 보였지만 Steam 목록에서는 실행 중,
     * 2026-10-23). 기본: 알림. 메인 화면 옵션: 앞 게임을 먼저 끝냄
     */
    private void checkPreviousGame() {
        java.util.List<app.ezsteam.runtime.ChildProcessCounter.Proc> ps = lastProcs;
        app.ezsteam.runtime.ChildProcessCounter.Proc old = ps == null ? null : gameOf(ps);
        if (old == null) return;
        String name = winExeName(old);
        if (MainActivity.prevGameEnd(this)) {
            log("앞 게임 " + name + "(" + old.pid + ") 실행 중 → 종료 (설정: 자동 종료)");
            android.os.Process.killProcess(old.pid);
            notice(s(R.string.notice_prev_ended, name));
        } else {
            log("앞 게임 " + name + "(" + old.pid + ") 아직 실행 중 (설정: 알림)");
            notice(s(R.string.notice_prev_running, name));
        }
    }

    // ---- first-run preparation banner / 첫 실행 준비 안내 띠 ----

    /** Set when Proton logs a new prefix (log thread) / Proton이 새 prefix를 기록하면 정함(로그 스레드) */
    private volatile long prefixHintMs;
    private String prepKind;
    private long prepStartMs, prepKindStartMs, prepLastSeenMs;
    private final java.util.Set<Integer> prepPids = new java.util.HashSet<>();

    /**
     * Which preparation step a process belongs to, from its kernel name (lower case, cut to 15
     * characters by the kernel), or null. Higher index = more specific (shown first).
     * 커널 이름(소문자, 커널이 15자로 자름)으로 프로세스가 속한 준비 단계를 정함. 없으면 null.
     * 번호가 클수록 구체적임(먼저 보여 줌)
     */
    // Step names: R.array.prep_names, same order as prepIndexOf / 단계 이름: R.array.prep_names, prepIndexOf와 같은 순서

    private static int prepIndexOf(String comm) {
        String n = comm.toLowerCase(Locale.ROOT);
        if (n.startsWith("ue4prereq") || n.startsWith("ueprereq")) return 9;
        if (n.startsWith("xnafx")) return 8;
        if (n.startsWith("physx")) return 7;
        if (n.startsWith("oalinst")) return 6;
        if (n.startsWith("dotnetfx") || n.startsWith("ndp4") || n.startsWith("dotnet")) return 5;
        if (n.startsWith("vcredist") || n.startsWith("vc_redist")) return 4;
        if (n.startsWith("dxsetup")) return 3;
        if (n.startsWith("msiexec")) return 2;
        if (n.startsWith("wineboot")) return 1;
        if (n.startsWith("iscriptevaluat")) return 0;
        return -1;
    }

    /**
     * Shows what the first start of a game is doing while Steam's install script, Proton's prefix
     * creation or a redistributable installer runs (DirectX alone took about 3 minutes with 101
     * sub-processes, 2026-10-19), with elapsed time and a sub-task count so it does not look
     * frozen; logs start, step changes and end. Monitor thread, every 2 s.
     * 게임 첫 시작 때 Steam 설치 스크립트, Proton prefix 만들기, 재배포 패키지 설치가 도는 동안 무엇을 하는지
     * 경과 시간·하위 작업 수와 함께 보여 줌(DirectX만 하위 프로세스 101개로 약 3분 걸렸음, 2026-10-19).
     * 멈춘 것처럼 보이지 않게 함. 시작·단계 변경·끝을 기록함. 감시 스레드, 2초마다
     */
    private void updatePrep(java.util.List<app.ezsteam.runtime.ChildProcessCounter.Proc> ps) {
        long now = SystemClock.elapsedRealtime();
        int best = -1;
        boolean wine = false;
        for (app.ezsteam.runtime.ChildProcessCounter.Proc pr : ps) {
            best = Math.max(best, prepIndexOf(exeName(pr)));
            wine |= isWineProc(exeName(pr));
        }
        // A new prefix counts as its own step for up to 60 s until an installer shows up, and only
        // while Wine runs: at Steam start the old steam.log is read again from the top, and its
        // earlier "Upgrading prefix" line showed the banner with nothing running (user screenshot,
        // 2026-10-19)
        // 새 prefix는 설치 프로그램이 보이기 전까지 최대 60초 동안 따로 한 단계로 셈. Wine이 돌 때만:
        // Steam 시작 때 예전 steam.log를 처음부터 다시 읽으므로, 그 안의 예전 "Upgrading prefix" 줄 때문에
        // 아무것도 돌지 않는데 안내 띠가 떴음(사용자 화면, 2026-10-19)
        long hint = prefixHintMs;
        if (hint != 0 && now - hint < 60_000 && best < 1 && wine) best = 1;
        // Base prefix step named by ezs-baseprefix.py wins (it also runs installers itself);
        // dropped after 20 minutes without news in case its end line was missed
        // ezs-baseprefix.py가 알린 기본 prefix 단계가 우선(설치 프로그램도 직접 돌림). 끝 줄을
        // 놓친 경우를 위해 20분 동안 소식이 없으면 버림
        String base = baseStage;
        if (base != null && now - baseStageMs > 20 * 60_000) baseStage = base = null;
        if (base != null && best < 2) best = Math.max(best, 1);
        // Proton being downloaded after the first sign-in (step 1 of the game setup)
        // 첫 로그인 뒤 Proton을 받는 중(게임 실행 환경 준비 1단계)
        // Proton download: no banner at Steam start (user request 2026-10-20); Steam's own download
        // page shows it / Proton 받기: Steam 시작 때 안내 띠 없음(사용자 요청 2026-10-20). Steam 다운로드 화면에 보임
        if (best < 0 && base == null && prepKind == null && SHOW_SETUP_BANNERS) {
            int pct = protonDownloadPercent();
            if (pct >= 0) {
                protonBanner = true;
                // Progress and the next step only (user request, 2026-10-20) / 진행도와 다음 단계만(사용자 요청, 2026-10-20)
                String t = s(R.string.prep_step1, pct);
                runOnUiThread(() -> {
                    prepView.setText(t);
                    prepBox.setVisibility(View.VISIBLE);
                });
                return;
            } else if (protonBanner) {
                protonBanner = false;
                runOnUiThread(() -> prepBox.setVisibility(View.GONE));
            }
        }
        // Background DirectX while a game is being played: no banner over the game
        // 게임을 하는 동안의 백그라운드 DirectX: 게임 위에 안내 띠를 띄우지 않음
        boolean background = base != null && (base.equals(bgDirectx()) || base.equals(bgBackfill()));
        if (background && gameOf(ps) != null) {
            runOnUiThread(() -> prepBox.setVisibility(View.GONE));
            return;
        }
        // Preparation ahead of time is shown only while a game start waits for it ("game being
        // prepared"); otherwise it runs quietly (user request 2026-10-20)
        // 미리 하는 준비는 게임 시작이 그것을 기다릴 때만 보여 줌("게임 준비 중"). 그 밖에는 조용히 돎
        // (사용자 요청 2026-10-20)
        if (basePrepRunning && !gameWaiting && !SHOW_SETUP_BANNERS) {
            if (prepKind != null) {
                prepKind = null;
                runOnUiThread(() -> prepBox.setVisibility(View.GONE));
            }
            return;
        }
        if (best >= 0) {
            String kind = base != null ? base : getResources().getStringArray(R.array.prep_names)[best];
            if (prepKind == null) {
                prepStartMs = now;
                prepKindStartMs = now;
                prepPids.clear();
                for (app.ezsteam.runtime.ChildProcessCounter.Proc pr : ps) prepPids.add(pr.pid);
                prepPids.add(-1); // marker: the set holds the processes from before / 표식: 시작 전부터 있던 프로세스
                log("첫 실행 준비: " + kind + " 시작");
            } else if (!kind.equals(prepKind)) {
                log("첫 실행 준비: " + prepKind + " → " + kind + " (" + mmss(now - prepKindStartMs) + ")");
                prepKindStartMs = now;
            }
            prepKind = kind;
            prepLastSeenMs = now;
            // Processes started since the preparation began = sub-tasks / 준비 시작 뒤 생긴 프로세스 = 하위 작업
            // Only Wine processes count (not Steam's own helpers starting meanwhile)
            // Wine 프로세스만 셈(그사이 시작한 Steam 자체 보조 프로그램은 빼고)
            for (app.ezsteam.runtime.ChildProcessCounter.Proc pr : ps) {
                if (prepPids.add(pr.pid) && isWineProc(exeName(pr))) prepNew++;
            }
            // The install script alone also runs at every start (about 10 s): no "first start" wording then
            // 설치 스크립트만 도는 것은 매번 시작할 때도 있음(약 10초): 그때는 "첫 실행" 문구를 쓰지 않음
            if (best >= 1) prepFirstRun = true;
            String text;
            if (basePrepRunning && gameWaiting) {
                text = s(R.string.prep_game_waiting, kind, mmss(now - prepStartMs));
            } else if (basePrepRunning && background) {
                // Games can start now / 이제 게임을 시작할 수 있음
                text = s(R.string.prep_step3, kind, mmss(now - prepKindStartMs));
            } else if (basePrepRunning) {
                // Prepared ahead of time, no game waiting / 미리 준비 중, 기다리는 게임 없음
                text = s(R.string.prep_step2, kind, mmss(now - prepStartMs));
            } else {
                // One wording for every start: the wait comes back at each start, so "first" misled
                // (user report 2026-10-23) / 시작마다 같은 문구: 기다림이 시작할 때마다 있어 "첫"이 헷갈렸음(사용자 제보 2026-10-23)
                text = s(R.string.prep_launch, kind, mmss(now - prepStartMs));
            }
            runOnUiThread(() -> {
                prepView.setText(text);
                // Not under the launch cover, which shows the step itself / 단계를 직접 보여 주는 실행 가림막 아래에서는 띄우지 않음
                if (launchStartMs == 0) prepBox.setVisibility(View.VISIBLE);
            });
        } else if (prepKind != null && now - prepLastSeenMs >= 6_000) {
            log("첫 실행 준비 끝: 총 " + mmss(prepLastSeenMs - prepStartMs) + ", 하위 작업 " + prepNew + "개 (마지막 단계 "
                    + prepKind + ")");
            prepKind = null;
            prepNew = 0;
            prepFirstRun = false;
            prefixHintMs = 0;
            runOnUiThread(() -> prepBox.setVisibility(View.GONE));
        }
    }

    private int prepNew;

    /** A game start is waiting for the base prefix (only then the banner shows its steps)
     *  게임 시작이 기본 prefix를 기다리는 중(그때만 안내 띠에 단계를 보여 줌) */
    private volatile boolean gameWaiting;

    /** Background base steps (games can start meanwhile) / 백그라운드 기본 prefix 단계(그동안 게임 시작 가능) */
    /** Show the setup steps (Proton download, base prefix) even with no game waiting; off
     *  게임이 기다리지 않아도 준비 단계(Proton 받기, 기본 prefix)를 보여 줄지. 끔 */
    private static final boolean SHOW_SETUP_BANNERS = false;
    /** Background base steps, compared by text / 백그라운드 기본 prefix 단계, 글로 비교함 */
    private String bgDirectx() {
        return s(R.string.base_bg_directx);
    }

    private String bgBackfill() {
        return s(R.string.base_bg_backfill);
    }

    /** Current base prefix step for the banner, or null / 안내 띠용 현재 기본 prefix 단계, 없으면 null */
    private volatile String baseStage;
    private volatile long baseStageMs;

    /**
     * Base prefix progress from ezs-baseprefix.py lines: building it (once), installing a common
     * redistributable into it (once), copying it into the game.
     * ezs-baseprefix.py 줄로 본 기본 prefix 진행: 만들기(한 번만), 공용 재배포 설치(한 번만), 게임으로 복사
     */
    private void baseStep(String line) {
        String l = line.toLowerCase(Locale.ROOT);
        String stage = baseStage;
        if (l.contains("base prepare start")) stage = s(R.string.base_prepare_start);
        else if (l.contains("base prepare done") || l.contains("base prepare: proton")) stage = null;
        else if (l.contains("base build start") || l.contains("base rebuild")) stage = s(R.string.base_build);
        else if (l.contains("base redist start")) {
            stage = l.contains("directx") ? bgDirectx()
                    : l.contains("vcredist") ? s(R.string.base_vcredist) : s(R.string.base_redist);
        } else if (l.contains("base late start")) stage = bgDirectx();
        else if (l.contains("base backfill start")) stage = bgBackfill();
        else if (l.contains("base waiting: directx")) {
            stage = s(R.string.base_add_directx);
            gameWaiting = true;
        } else if (l.contains("base waiting: game start")) {
            stage = s(R.string.base_env);
            gameWaiting = true;
        }
        else if (l.contains("base copy start")) {
            stage = s(R.string.base_copy);
            gameWaiting = true;
        }
        else if (l.contains("base copy done") || l.contains("base error") || l.contains("base build failed")
                || l.contains("base late done") || l.contains("base late retry")
                || l.contains("base backfill done") || l.contains("base backfill failed")) stage = null;
        if (l.contains("base copy done") || l.contains("base error") || l.contains("base copy: directx later")) gameWaiting = false;
        baseStage = stage;
        baseStageMs = SystemClock.elapsedRealtime();
    }

    /** Wine side process by kernel name (Windows programs, wineserver, preloader); names are cut
     *  to 15 characters, so ".ex" also counts / 커널 이름으로 본 Wine 쪽 프로세스(윈도우 프로그램,
     *  wineserver, preloader). 이름이 15자로 잘리므로 ".ex"도 셈 */
    private static boolean isWineProc(String comm) {
        String n = comm.toLowerCase(Locale.ROOT);
        return n.endsWith(".exe") || n.endsWith(".ex") || n.startsWith("wine");
    }
    private boolean prepFirstRun;

    private String mmss(long ms) {
        long sec = ms / 1000;
        return s(R.string.fmt_mmss, sec / 60, sec % 60);
    }

    // ---- base prefix prepared ahead of time / 기본 prefix 미리 준비 ----

    /** The Steam session's PRoot settings (for helper runs next to it) / Steam 세션의 PRoot 설정(옆에서 돌리는 보조 실행용) */
    private volatile GlibcLauncher.Config sessionConfig;
    private volatile boolean basePrepRunning;
    private long baseCheckMs;
    /** Proton version already asked about this session / 이번 세션에 이미 물어본 Proton 버전 */
    private volatile String baseAskedFor;

    /**
     * Every 15 s while no game runs: prepares the base prefix (clean prefix + Steam's common
     * redistributables, ezs-baseprefix.py) ahead of the first game start, so that start only copies
     * it. Starts on its own when Proton is there and the base is missing, or when a finished game
     * download brought new redistributables; asks first when Proton itself was updated (the base
     * is made again, several minutes). Reads the rootfs files directly (no process).
     * 게임이 돌지 않는 동안 15초마다: 게임 첫 시작 전에 기본 prefix(깨끗한 prefix + Steam 공용 재배포,
     * ezs-baseprefix.py)를 준비해 첫 시작은 복사만 하게 함. Proton이 있고 기본 prefix가 없을 때, 또는
     * 다 받은 게임 다운로드로 새 재배포가 생겼을 때 스스로 시작함. Proton 자체가 업데이트됐으면 먼저
     * 물어봄(기본 prefix를 다시 만들며 몇 분 걸림). rootfs 파일을 직접 읽음(프로세스 없음)
     */
    private void checkBasePrefix(java.util.List<app.ezsteam.runtime.ChildProcessCounter.Proc> ps) {
        long now = SystemClock.elapsedRealtime();
        if (now - baseCheckMs < 15_000) return;
        baseCheckMs = now;
        GlibcLauncher.Config session = sessionConfig;
        if (basePrepRunning || session == null || gameOf(ps) != null || prepKind != null) return;
        File steam = new File(session.rootfs, "home/steam/.local/share/Steam");
        File proton = findProton(steam);
        if (proton == null) return;
        String version = readText(new File(proton, "version")).trim();
        File base = new File(session.rootfs, "home/steam/.local/share/ezsteam/base-prefix");
        org.json.JSONObject mark = null;
        try {
            String m = readText(new File(base, "ezs-base.json"));
            if (!m.isEmpty()) mark = new org.json.JSONObject(m);
        } catch (org.json.JSONException ignored) {
            // Unreadable mark: treated as missing / 읽을 수 없는 표식: 없는 것으로 봄
        }
        if (mark == null || !new File(base, "version").isFile()) {
            startBasePrepare("처음 준비 (Proton " + version + ")");
            return;
        }
        String built = mark.optString("proton", "");
        if (!built.equals(version)) {
            if (!version.equals(baseAskedFor)) {
                baseAskedFor = version;
                askBaseRebuild(built, version);
            }
            return;
        }
        int fresh = newRedists(session, mark);
        if (fresh > 0) {
            startBasePrepare("새 재배포 " + fresh + "개");
            return;
        }
        // Game prefixes still waiting for DirectX (copied while it was being installed)
        // 아직 DirectX를 기다리는 게임 prefix(설치 중에 복사된 것)
        String pending = readText(new File(session.rootfs, "home/steam/.local/share/ezsteam/dx-pending.txt")).trim();
        if (!pending.isEmpty()) startBasePrepare("DirectX 추가 대기 게임 " + pending.split("\n").length + "개");
    }

    /** Valve ARM64 Proton folder (same rule as compat/ezproton), or null / Valve ARM64 Proton 폴더(compat/ezproton과 같은 규칙) */
    private static File findProton(File steam) {
        File[] dirs = new File(steam, "steamapps/common").listFiles(File::isDirectory);
        if (dirs == null) return null;
        Arrays.sort(dirs);
        File found = null;
        for (File d : dirs) {
            if (readText(new File(d, "toolmanifest.vdf")).contains("4185400") && new File(d, "proton").isFile()) found = d;
        }
        return found;
    }

    /** Whole small text file, or "" / 작은 글 파일 전체, 없으면 "" */
    private static String readText(File f) {
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /**
     * Install scripts of DirectX / Visual C++ in a fully downloaded Steamworks Shared that the base
     * has not run yet (same choice and ids as ezs-baseprefix.py: "<path under _CommonRedist>#step").
     * 다 받은 Steamworks Shared에서 기본 prefix가 아직 실행하지 않은 DirectX / Visual C++ 설치 스크립트 수
     * (ezs-baseprefix.py와 같은 선택과 id: "<_CommonRedist 아래 경로>#단계")
     */
    private int newRedists(GlibcLauncher.Config session, org.json.JSONObject mark) {
        java.util.Set<String> done = new java.util.HashSet<>();
        // "marked" = DirectX recorded without installing (optional install) / "marked" = 설치 없이 기록만 한 DirectX(선택 설치)
        for (String key : new String[]{"steps", "failed", "marked"}) {
            org.json.JSONArray a = mark.optJSONArray(key);
            for (int i = 0; a != null && i < a.length(); i++) {
                String id = a.optString(i);
                int h = id.indexOf('#');
                done.add(h >= 0 ? id.substring(0, h) : id);
            }
        }
        java.util.List<File> libs = new java.util.ArrayList<>();
        libs.add(new File(session.rootfs, "home/steam/.local/share/Steam/steamapps"));
        File shared = MainActivity.sharedLibrary(this) ? app.ezsteam.runtime.SharedStorage.steamLibrary(this) : null;
        if (shared != null) libs.add(new File(shared, "steamapps"));
        for (File lib : libs) {
            File redist = new File(lib, "common/Steamworks Shared/_CommonRedist");
            if (!redist.isDirectory()) continue;
            // Usable files only: installed, no update running (same rule as ezs-baseprefix.py
            // files_usable; "update required" alone is fine), or no record
            // 쓸 수 있는 파일만: 설치됨, 진행 중인 업데이트 없음(ezs-baseprefix.py files_usable과 같은 규칙.
            // "업데이트 필요"만 있는 것은 괜찮음), 또는 기록 없음
            String acf = readText(new File(lib, "appmanifest_228980.acf"));
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"StateFlags\"\\s+\"(\\d+)\"").matcher(acf);
            if (m.find()) {
                long fl = Long.parseLong(m.group(1));
                if ((fl & 4) == 0 || (fl & (32 | 128 | 256 | 512 | 1024 | 2048)) != 0 || fl >= 65536) return 0;
            }
            int n = 0;
            File[] tops = redist.listFiles(File::isDirectory);
            for (File top : tops == null ? new File[0] : tops) {
                String t = top.getName().toLowerCase(Locale.ROOT);
                if (!t.equals("directx") && !t.equals("vcredist")) continue;
                File[] vers = top.listFiles(File::isDirectory);
                for (File v : vers == null ? new File[0] : vers) {
                    File[] fs = v.listFiles(f -> f.getName().equalsIgnoreCase("installscript.vdf"));
                    for (File f : fs == null ? new File[0] : fs) {
                        String rel = top.getName() + "/" + v.getName() + "/" + f.getName();
                        if (!done.contains(rel)) n++;
                    }
                }
            }
            // The first library holding Steamworks Shared is the one ezs-baseprefix.py uses
            // Steamworks Shared가 있는 첫 라이브러리를 ezs-baseprefix.py가 씀
            return n;
        }
        return 0;
    }

    /** Proton was updated: ask before making the base again / Proton이 업데이트됨: 기본 prefix를 다시 만들기 전에 물어봄 */
    private void askBaseRebuild(String from, String to) {
        log("기본 환경: Proton 업데이트 감지 " + from + " → " + to);
        runOnUiThread(() -> new android.app.AlertDialog.Builder(this)
                .setTitle(s(R.string.dlg_proton_update_title))
                .setMessage(s(R.string.dlg_proton_update_msg, from, to))
                .setPositiveButton(s(R.string.btn_build_now), (d, w) -> startBasePrepare("Proton 업데이트"))
                .setNegativeButton(s(R.string.btn_later), (d, w) -> log("기본 환경: 다시 만들기 미룸 (새 게임 첫 실행 때 만들어짐)"))
                .show());
    }

    /** Runs ezs-baseprefix.sh in a second PRoot with the session's settings / 세션 설정으로 두 번째 PRoot에서 ezs-baseprefix.sh 실행 */
    private void startBasePrepare(String why) {
        GlibcLauncher.Config session = sessionConfig;
        if (session == null || basePrepRunning) return;
        basePrepRunning = true;
        new Thread(() -> {
            try {
                GlibcLauncher.Config c = new GlibcLauncher.Config();
                c.rootfs = session.rootfs;
                c.nativeLibDir = session.nativeLibDir;
                c.dataDir = session.dataDir;
                c.guestHome = session.guestHome;
                c.display = session.display;
                c.binds.addAll(session.binds);
                c.link2symlink = session.link2symlink;
                c.sysvipc = session.sysvipc;
                c.changeId = session.changeId;
                c.env.putAll(session.env);
                log("기본 환경 준비 시작: " + why);
                Process p = GlibcLauncher.startScript(c, "/opt/ezsteam/ezs-baseprefix.sh",
                        java.util.Collections.emptyList(), null, null);
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        if (l.contains("ezproton: base ")) {
                            log("BASE " + l);
                            baseStep(l);
                        }
                    }
                }
                log("기본 환경 준비 끝 exit=" + p.waitFor());
            } catch (IOException | InterruptedException | RuntimeException e) {
                log("기본 환경 준비 실패: " + e);
            } finally {
                baseStage = null;
                basePrepRunning = false;
            }
        }, "ezs-base").start();
    }

    /**
     * Windows program file name of a process from its command line (after " | "), else the kernel
     * name. The kernel name is cut to 15 characters, so "DevilMayCry5.exe" and "NieRAutomata.exe"
     * showed as "....ex", were never taken for the game, and the top bar said "게임 -" (logs since
     * 2026-10-21; a 13-character name was found)
     * 명령줄(" | " 뒤)에서 본 프로세스의 윈도우 프로그램 파일 이름, 없으면 커널 이름. 커널 이름은 15자에서
     * 잘려 "DevilMayCry5.exe", "NieRAutomata.exe"가 "....ex"로 보였고 게임으로 잡히지 않아 위쪽 막대가
     * "게임 -"였음(2026-10-21부터의 로그. 13자 이름은 잡혔음)
     */
    private static String winExeName(app.ezsteam.runtime.ChildProcessCounter.Proc pr) {
        int i = pr.name.indexOf(" | ");
        if (i < 0) return exeName(pr);
        String cmd = pr.name.substring(i + 3).trim();
        int sp = cmd.toLowerCase(Locale.ROOT).indexOf(".exe");
        if (sp >= 0) cmd = cmd.substring(0, sp + 4);
        int slash = Math.max(cmd.lastIndexOf('\\'), cmd.lastIndexOf('/'));
        String base = slash >= 0 ? cmd.substring(slash + 1) : cmd;
        return base.toLowerCase(Locale.ROOT).endsWith(".exe") ? base : exeName(pr);
    }

    /** The game: largest .exe that is not a Wine helper, or null / 게임: Wine 보조가 아닌 가장 큰 .exe, 없으면 null */
    private static app.ezsteam.runtime.ChildProcessCounter.Proc gameOf(
            java.util.List<app.ezsteam.runtime.ChildProcessCounter.Proc> ps) {
        app.ezsteam.runtime.ChildProcessCounter.Proc game = null;
        for (app.ezsteam.runtime.ChildProcessCounter.Proc pr : ps) {
            String exe = winExeName(pr).toLowerCase(Locale.ROOT);
            // Installers of the first start are not the game / 첫 시작의 설치 프로그램은 게임이 아님
            if (exe.endsWith(".exe") && !WINE_HELPERS.contains(exe) && prepIndexOf(exe) < 0
                    && (game == null || pr.rssKb > game.rssKb)) {
                game = pr;
            }
        }
        return game;
    }

    /**
     * Window with the last 200 lines of the current Proton log, unfiltered.
     * 현재 Proton 로그의 마지막 200줄을 거르지 않고 보여 주는 창
     */
    private void showProtonLog() {
        // Every Proton log (one per game, steam-<appid>.log), newest first: the log of a game that
        // already exited stays reachable after another game ran
        // 모든 Proton 로그(게임마다 하나, steam-<appid>.log), 최신 순: 이미 끝난 게임의 로그도
        // 다른 게임을 실행한 뒤에 볼 수 있음
        File dir = new File(RootfsInstaller.rootfsDir(this), "home/steam/ezs-logs");
        File[] fs = dir.listFiles((d, n) -> n.startsWith("steam-") && n.endsWith(".log"));
        if (fs == null || fs.length == 0) {
            showTextWindow(s(R.string.menu_proton_log), s(R.string.proton_log_none));
            return;
        }
        Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        if (fs.length == 1) {
            showProtonLogFile(fs[0]);
            return;
        }
        java.text.SimpleDateFormat tf = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT);
        String[] items = new String[fs.length];
        for (int i = 0; i < fs.length; i++) {
            items[i] = String.format(Locale.ROOT, "%s  %s  %.1f MB", fs[i].getName(),
                    tf.format(new java.util.Date(fs[i].lastModified())), fs[i].length() / 1048576f);
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(s(R.string.proton_log_pick))
                .setItems(items, (dlg, which) -> showProtonLogFile(fs[which]))
                .setPositiveButton(s(R.string.btn_close), null)
                .show();
    }

    /** Last 200 lines of one Proton log, unfiltered / Proton 로그 하나의 마지막 200줄, 거르지 않음 */
    private void showProtonLogFile(File f) {
        String text;
        try {
            java.util.List<String> ls = java.nio.file.Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder(f.getName() + " (" + ls.size() + "줄 중 마지막 200줄)\n");
            for (String l : ls.subList(Math.max(0, ls.size() - 200), ls.size())) {
                sb.append(l.length() > 300 ? l.substring(0, 300) : l).append('\n');
            }
            text = sb.toString();
        } catch (IOException | RuntimeException e) {
            text = "읽기 실패: " + e;
        }
        showTextWindow(s(R.string.menu_proton_log), text);
    }

    /** Request code of the "save logs" file picker / "로그 저장" 파일 선택 창 요청 코드 */
    private static final int REQ_SAVE_LOGS = 7301;
    /** Largest part of one log file put into the zip (its end) / zip에 넣는 로그 파일 하나의 최대 크기(끝부분) */
    private static final long LOG_SAVE_MAX = 32L << 20;

    /**
     * [로그 저장]: the user picks where to save one zip (Android file picker, no storage permission
     * needed). Nothing is sent anywhere; the file stays where the user saves it. The logs can hold
     * the Steam account name and paths, so the user checks them before sharing.
     * [로그 저장]: 사용자가 zip 하나를 저장할 위치를 고름(안드로이드 파일 선택 창, 저장소 권한 불필요).
     * 어디로도 보내지 않으며 사용자가 저장한 곳에만 남음. 로그에 Steam 계정 이름과 경로가 들어 있을 수
     * 있으므로 공유 전에 사용자가 확인함
     */
    private volatile boolean saving;

    private void saveLogs() {
        String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new java.util.Date());
        // Testers: straight into Download/.EZlator/Reports, the folder EZlator reports use too
        // (newest 5 of this layer kept). Without the storage permission: the file picker as before
        // 테스터용: EZlator 제보와 같은 Download/.EZlator/Reports에 바로 저장(이 레이어 것 최근 5개 유지).
        // 저장소 권한이 없으면 예전처럼 파일 선택 창
        // One save at a time / 한 번에 하나만 저장
        if (saving) {
            notice(s(R.string.notice_saving));
            return;
        }
        if (app.ezsteam.runtime.SharedStorage.granted(this)) {
            saving = true;
            String appLog = String.join("\n", saveLines) + "\n";
            String procs = processText();
            new Thread(() -> {
                try {
                    File dir = app.ezsteam.runtime.SharedStorage.reports();
                    java.util.List<String> gone = app.ezsteam.runtime.SharedStorage.trimReports(dir, 5);
                    File zip = new File(dir, app.ezsteam.runtime.SharedStorage.REPORT_PREFIX + stamp + ".zip");
                    // Written as ".part" and renamed when complete: a report copied to a computer
                    // while still being written arrived cut off (8 KB, 2026-10-20)
                    // ".part"로 쓰고 다 쓰면 이름을 바꿈: 쓰는 중에 컴퓨터로 복사한 제보가 잘린 채
                    // 도착했음(8 KB, 2026-10-20)
                    // The zip is built in the app's own cache and copied into the shared folder in one
                    // go at the end. Writing the ".part" file there took seconds while it grew, and a
                    // computer browsing the phone over USB (MTP) saw a file changing size and being
                    // renamed: Windows Explorer stopped responding until the cable was pulled (user
                    // report 2026-10-28)
                    // zip은 앱 자체 캐시에서 만들고 끝에 공용 폴더로 한 번에 복사함. 그곳에 ".part" 파일을 쓰면 커지는
                    // 동안 몇 초가 걸렸고, USB(MTP)로 휴대폰을 보던 컴퓨터는 크기가 바뀌고 이름이 바뀌는 파일을 봤음:
                    // Windows 탐색기가 케이블을 뽑을 때까지 응답하지 않았음(사용자 보고 2026-10-28)
                    File part = new File(getCacheDir(), zip.getName() + ".part");
                    // Leftovers of a save that never finished (older than 10 minutes); a save in
                    // progress is not touched (two quick saves deleted each other's file, 2026-10-21)
                    // 끝나지 못한 저장의 남은 파일(10분 넘은 것). 진행 중인 저장은 건드리지 않음(연달아 두 번
                    // 저장하자 서로의 파일을 지웠음, 2026-10-21)
                    long old = System.currentTimeMillis() - 10 * 60_000L;
                    File[] stale = dir.listFiles((d, n) -> n.endsWith(".zip.part"));
                    for (File f : stale == null ? new File[0] : stale) {
                        //noinspection ResultOfMethodCallIgnored
                        if (f.lastModified() < old) f.delete();
                    }
                    notice(s(R.string.notice_saving_scan));
                    try (java.io.OutputStream os = new java.io.FileOutputStream(part)) {
                        writeLogZip(os, appLog, procs);
                    } catch (IOException | RuntimeException e) {
                        // No half-written report left behind / 반쯤 쓴 제보 파일을 남기지 않음
                        //noinspection ResultOfMethodCallIgnored
                        part.delete();
                        throw e;
                    }
                    try (java.io.InputStream in = new java.io.FileInputStream(part);
                         java.io.OutputStream out = new java.io.FileOutputStream(zip)) {
                        byte[] b = new byte[1 << 16];
                        int r;
                        while ((r = in.read(b)) > 0) out.write(b, 0, r);
                    } catch (IOException e) {
                        //noinspection ResultOfMethodCallIgnored
                        zip.delete();
                        throw e;
                    } finally {
                        //noinspection ResultOfMethodCallIgnored
                        part.delete();
                    }
                    log("로그 저장 위치: " + zip.getAbsolutePath());
                    // Make the new file visible over USB (MTP) right away: a computer lists files from
                    // Android's media index, which did not know new reports yet (user report: the
                    // computer and the device showed different report files)
                    // USB(MTP)에서 새 파일이 바로 보이게 함: 컴퓨터는 안드로이드 미디어 목록으로 파일을 보는데,
                    // 그 목록이 새 제보를 아직 몰랐음(사용자 보고: 컴퓨터와 기기에서 보이는 제보 파일이 달랐음)
                    // Removed old reports are scanned too, which takes them out of that list
                    // 지운 옛 제보도 함께 검사해 그 목록에서 빠지게 함
                    gone.add(0, zip.getAbsolutePath());
                    android.media.MediaScannerConnection.scanFile(this, gone.toArray(new String[0]), null,
                            (path, uri) -> log("미디어 목록 갱신: " + new File(path).getName()
                                    + (uri != null ? "" : (new File(path).exists() ? " (등록 안 됨)" : " (지운 파일 제거)"))));
                    notice(s(R.string.notice_saved_at, "Download/.EZlator/Reports/" + zip.getName()));
                } catch (IOException | RuntimeException e) {
                    log("공용 폴더 저장 실패, 파일 선택 창으로 저장: " + e);
                    notice(s(R.string.notice_save_pick));
                    runOnUiThread(() -> pickLogFile("ezsteam-logs-" + stamp + ".zip"));
                } finally {
                    saving = false;
                }
            }, "ezs-log-save").start();
            return;
        }
        pickLogFile("ezsteam-logs-" + stamp + ".zip");
    }

    /** Broadcast the PC helper sends (ezs-report.bat, through adb) / PC 도우미가 보내는 방송(ezs-report.bat, adb 경유) */
    static final String ACTION_PC_REPORT = "app.ezsteam.SAVE_REPORT";
    /** Measurement span from the PC (ezs-compare.bat): extras phase=start|end, label
     *  PC가 보내는 측정 구간(ezs-compare.bat): extra phase=start|end, label */
    static final String ACTION_PC_MEASURE = "app.ezsteam.MEASURE";

    // ---- Measurement span (unattended comparison runs) / 측정 구간(사람 없이 하는 비교 실행) ----
    private final Object measLock = new Object();
    private String measLabel;
    private long measStartMs;
    private int measN, measProcMax;
    private double measAll, measAllMax, measUi, measProot, measX, measFps;
    private final java.util.List<Float> measP95 = new java.util.ArrayList<>();

    /**
     * Start or end a measurement span. While it runs, every 2 s monitor pass adds the CPU split and
     * the shown frame rate; the end prints one "MEASURE" line, so runs of different display modes can
     * be compared over the same span without anyone timing it on the tablet (user 2026-10-30).
     * 측정 구간을 시작하거나 끝냄. 도는 동안 2초 모니터 회차마다 CPU 몫과 보인 프레임 수를 더하고, 끝에서 "MEASURE" 한 줄을
     * 씀. 화면 방식이 다른 실행을 태블릿에서 시간을 재지 않고 같은 구간으로 비교하게 함(사용자 2026-10-30)
     */
    private void measure(String phase, String label) {
        synchronized (measLock) {
            if ("start".equals(phase)) {
                measLabel = label == null ? "?" : label;
                measStartMs = SystemClock.elapsedRealtime();
                measN = measProcMax = 0;
                measAll = measAllMax = measUi = measProot = measX = measFps = 0;
                measP95.clear();
                log("MEASURE 시작: " + measLabel);
                return;
            }
            if (measLabel == null) return;
            String l = measLabel;
            measLabel = null;
            long sec = (SystemClock.elapsedRealtime() - measStartMs) / 1000;
            if (measN == 0) {
                log("MEASURE " + l + ": 표본 없음 (" + sec + "초)");
                return;
            }
            java.util.List<Float> p = new java.util.ArrayList<>(measP95);
            java.util.Collections.sort(p);
            String p95 = p.isEmpty() ? "N/A" : String.format(Locale.ROOT, "중앙 %.1fms 최대 %.1fms",
                    p.get(p.size() / 2), p.get(p.size() - 1));
            log(String.format(Locale.ROOT, "MEASURE %s: %d초 표본 %d, CPU 전체 평균 %.0f%% 최대 %.0f%%, Steam 화면 %.0f%%, "
                            + "PRoot %.0f%%, X 서버 %.0f%%, 새 프레임 %.1f/s, 프레임 시간 P95 %s, 프로세스 최대 %d",
                    l, sec, measN, measAll / measN, measAllMax, measUi / measN, measProot / measN, measX / measN,
                    measFps / measN, p95, measProcMax));
        }
    }

    /** One monitor pass into the running span / 모니터 회차 하나를 진행 중인 구간에 더함 */
    private void measureTick() {
        synchronized (measLock) {
            if (measLabel == null || cpuAll < 0) return;
            measN++;
            measAll += cpuAll;
            measAllMax = Math.max(measAllMax, cpuAll);
            measUi += cpuUi;
            measProot += cpuProot;
            measX += cpuX;
            measProcMax = Math.max(measProcMax, procNow);
            float[] fs = view.frameStats(2_000);
            if (fs != null) {
                measFps += fs[0];
                measP95.add(fs[2]);
            }
        }
    }
    private android.content.BroadcastReceiver pcReceiver;

    /**
     * Save a report when the PC asks (tablet stays untouched on the cable). Only senders holding
     * android.permission.DUMP may send it: the adb shell has it, ordinary apps do not. The file is
     * then pulled with adb, not over MTP (Windows Explorer froze on MTP, 2026-10-28).
     * PC가 요청하면 제보를 저장함(태블릿은 케이블에 꽂아 둔 채 손대지 않음). android.permission.DUMP를 가진 쪽만 보낼 수
     * 있음: adb 셸은 가졌고 일반 앱은 없음. 파일은 MTP가 아니라 adb로 가져감(MTP에서 Windows 탐색기가 멈췄음, 2026-10-28)
     */
    private void registerPcReceiver() {
        pcReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, android.content.Intent i) {
                if (ACTION_PC_MEASURE.equals(i.getAction())) {
                    measure(i.getStringExtra("phase"), i.getStringExtra("label"));
                    return;
                }
                log("PC 요청: 제보 저장");
                saveLogs();
            }
        };
        try {
            android.content.IntentFilter f = new android.content.IntentFilter(ACTION_PC_REPORT);
            f.addAction(ACTION_PC_MEASURE);
            registerReceiver(pcReceiver, f, "android.permission.DUMP", null);
        } catch (RuntimeException e) {
            pcReceiver = null;
            log("PC 요청 받기 실패: " + e);
        }
    }

    /** Save through the system file picker / 시스템 파일 선택 창으로 저장 */
    private void pickLogFile(String name) {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(android.content.Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(i, REQ_SAVE_LOGS);
        } catch (RuntimeException e) {
            log("로그 저장 창을 열 수 없음: " + e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PAD_LAYOUT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) importPadLayout(data.getData());
            return;
        }
        if (requestCode != REQ_SAVE_LOGS) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            log("로그 저장 취소");
            return;
        }
        android.net.Uri uri = data.getData();
        // Snapshots taken on the UI thread; writing happens in the background
        // UI 스레드에서 현재 내용을 떠 두고, 쓰기는 백그라운드에서 함
        String appLog = String.join("\n", saveLines) + "\n";
        String procs = processText();
        // Written to a private file first and copied in one go: the picked file appeared at once and
        // grew while the logs were collected, so a report shared before the end arrived cut off
        // (0.4 MB of a larger report, 2026-10-28)
        // 먼저 앱 전용 파일에 쓰고 한 번에 복사함: 고른 파일이 바로 생겨 로그를 모으는 동안 커졌기 때문에, 끝나기 전에
        // 공유한 제보가 잘린 채 도착했음(더 큰 제보의 0.4 MB, 2026-10-28)
        notice(s(R.string.notice_saving_scan));
        new Thread(() -> {
            File tmp = new File(getCacheDir(), "ezs-report.zip.part");
            try {
                try (java.io.OutputStream os = new java.io.FileOutputStream(tmp)) {
                    writeLogZip(os, appLog, procs);
                }
                try (java.io.InputStream in = new java.io.FileInputStream(tmp);
                     java.io.OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                    if (os == null) throw new IOException("저장 위치를 열 수 없음");
                    byte[] b = new byte[1 << 16];
                    int r;
                    while ((r = in.read(b)) > 0) os.write(b, 0, r);
                }
                log("로그 저장 완료(위치 선택): " + tmp.length() / 1024 + " KB");
                notice(s(R.string.notice_saved));
            } catch (IOException | RuntimeException e) {
                log("로그 저장 실패: " + e);
                notice(s(R.string.notice_save_failed, e.getMessage()));
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }, "ezs-log-save").start();
    }

    /**
     * Zip: app log, process list with game threads, every file in ~/ezs-logs (steam.log, Proton logs)
     * and /tmp/ezs-lsof.log. Files larger than LOG_SAVE_MAX keep only their end.
     * zip 내용: 앱 로그, 게임 스레드를 포함한 프로세스 목록, ~/ezs-logs의 모든 파일(steam.log,
     * Proton 로그), /tmp/ezs-lsof.log. LOG_SAVE_MAX보다 큰 파일은 끝부분만 넣음.
     * Text is scrubbed (Steam account/persona names, SteamID64, tokens, e-mails: LogScrubber);
     * images are copied as they are.
     * 글은 개인 값을 가림(Steam 계정·표시 이름, SteamID64, 토큰, 이메일: LogScrubber). 그림은 그대로 넣음
     */
    private void writeLogZip(java.io.OutputStream os, String appLog, String procs) throws IOException {
        // Screen frames first, before the slow scans below, so they show the moment of the save
        // (only with the admin IO statistics switch: the picture is the screen as it is)
        // 화면 프레임을 먼저 잡음(아래 느린 검사 전에): 저장한 순간을 보여 주게 함
        // (관리자 IO 통계 스위치가 켜졌을 때만: 그림은 화면 그대로임)
        java.util.List<byte[]> shots = MainActivity.ioStat(this) ? screenFrames(8, 1200) : new java.util.ArrayList<byte[]>();
        File rootfs = RootfsInstaller.rootfsDir(this);
        java.util.List<File> files = new java.util.ArrayList<>();
        File[] logs = new File(rootfs, "home/steam/ezs-logs").listFiles(File::isFile);
        if (logs != null) files.addAll(Arrays.asList(logs));
        File lsof = new File(rootfs, "tmp/ezs-lsof.log");
        if (lsof.isFile()) files.add(lsof);
        // The client's own download log (which library, which depots, why a game is downloaded again)
        // 클라이언트 자체 다운로드 로그(어느 라이브러리, 어느 depot, 왜 다시 받는지)
        File content = new File(rootfs, "home/steam/.local/share/Steam/logs/content_log.txt");
        if (content.isFile()) files.add(content);
        // The client's own connection log (when and how it reached Steam's servers: slow sign-in,
        // "no connection" at start); every IP address is replaced by <v4>/<v6> (only the kind stays)
        // 클라이언트 자체 접속 로그(언제 어떻게 Steam 서버에 닿았는지: 느린 로그인, 시작 때 "연결 없음").
        // 모든 IP 주소는 <v4>/<v6>로 바꿈(종류만 남김)
        File conn = new File(rootfs, "home/steam/.local/share/Steam/logs/connection_log.txt");
        if (conn.isFile()) files.add(conn);
        // The client's own launch and compatibility logs: each game start step ("GameAction") and
        // which compatibility tool it picked. A game start that never reached EZ Proton left no
        // trace in the other logs (2026-10-21)
        // 클라이언트 자체 실행·호환성 로그: 게임 시작 단계마다("GameAction")와 고른 호환성 도구. EZ Proton까지
        // 오지 못한 게임 시작은 다른 로그에 흔적이 없었음(2026-10-21)
        for (String n : new String[]{"console_log.txt", "compat_log.txt"}) {
            File f = new File(rootfs, "home/steam/.local/share/Steam/logs/" + n);
            if (f.isFile()) files.add(f);
        }
        // The Steam web helper wrapper as patched by ezsteam-prepare.sh (the feature merge did not
        // reach the browser switches, 2026-10-18); a short Valve script, sent only in a report
        // the user saves
        // ezsteam-prepare.sh가 고친 Steam 웹 도우미 감쌈 스크립트(기능 합치기가 브라우저 스위치에 반영되지
        // 않았음, 2026-10-18). 짧은 Valve 스크립트이며 사용자가 저장하는 제보에만 들어감
        File wh = new File(rootfs, "home/steam/.local/share/Steam/steamrtarm64/steamwebhelper.sh");
        if (wh.isFile()) files.add(wh);
        // The web helper's own output (the wrapper sends it to Steam/logs/steamwebhelper.log): GPU
        // and GL start-up lines for the Steam UI GPU test; IP addresses masked like the connection log
        // 웹 도우미 자체 출력(감쌈 스크립트가 Steam/logs/steamwebhelper.log로 보냄): Steam 화면 GPU 시험용
        // GPU·GL 시작 줄. IP 주소는 접속 로그처럼 가림
        java.util.Map<File, String> alias = new java.util.HashMap<>();
        // The client's own web helper log: popup windows it makes ("SP BPM_uid0" = Big Picture,
        // with its X window id) / 클라이언트 자체 웹 도우미 로그: 만드는 팝업 창("SP BPM_uid0" = 빅픽처, X 창 번호 포함)
        File whTxt = new File(rootfs, "home/steam/.local/share/Steam/logs/webhelper.txt");
        if (whTxt.isFile()) files.add(whTxt);
        // Chromium's own log of the web helper (--log-file): GPU process and GL errors, kept across
        // helper restarts (steamwebhelper.log holds only the last start)
        // 웹 도우미의 Chromium 자체 로그(--log-file): GPU 프로세스와 GL 오류. 도우미가 다시 시작해도 남음
        // (steamwebhelper.log는 마지막 시작분만 있음)
        File cefLog = new File(rootfs, "home/steam/.local/share/Steam/logs/cef_log.txt");
        if (cefLog.isFile()) files.add(cefLog);
        // Output of the previous helper start, kept by ezs-webhelper-env.sh in the Zink GL test
        // 이전 도우미 시작의 출력. Zink GL 시험에서 ezs-webhelper-env.sh가 남김
        File whPrev = new File(rootfs, "home/steam/.local/share/Steam/logs/steamwebhelper-prev.log");
        if (whPrev.isFile()) {
            files.add(whPrev);
            alias.put(whPrev, "steamwebhelper-output-prev.log");
        }
        File whLog = new File(rootfs, "home/steam/.local/share/Steam/logs/steamwebhelper.log");
        if (whLog.isFile()) {
            files.add(whLog);
            alias.put(whLog, "steamwebhelper-output.log");
        }
        // X server output (Xvfb or Xwayland: glamor / GBM / EGL start-up for the GPU display path test)
        // X 서버 출력(Xvfb 또는 Xwayland: GPU 화면 경로 시험용 glamor / GBM / EGL 시작 줄)
        File xLog = new File(rootfs, "tmp/ezs-xvfb-display.log");
        if (xLog.isFile()) {
            files.add(xLog);
            alias.put(xLog, "xserver.log");
        }
        // Crash reports of libezs_crash.so (GPU path check, web helper in the Zink GL test)
        // libezs_crash.so의 크래시 보고(GPU 경로 점검, Zink GL 시험의 웹 도우미)
        File crashLog = new File(rootfs, "tmp/ezs-crash.log");
        if (crashLog.isFile() && crashLog.length() > 0) files.add(crashLog);
        // Unity games' own logs (Player.log in each prefix, newest 3): a game that stays on a black
        // screen leaves its reason there, not in the Proton log (2026-10-18)
        // Unity 게임 자체 로그(prefix마다 있는 Player.log, 최근 3개): 검은 화면에서 멈춘 게임은 이유를
        // Proton 로그가 아니라 여기에 남김(2026-10-18)
        java.util.List<File> unity = new java.util.ArrayList<>();
        File[] pfxs = new File(rootfs, "home/steam/.local/share/Steam/steamapps/compatdata").listFiles(File::isDirectory);
        for (File pfx : pfxs == null ? new File[0] : pfxs) {
            File[] vendors = new File(pfx, "pfx/drive_c/users/steamuser/AppData/LocalLow").listFiles(File::isDirectory);
            for (File v : vendors == null ? new File[0] : vendors) {
                File[] games = v.listFiles(File::isDirectory);
                for (File g : games == null ? new File[0] : games) {
                    File log = new File(g, "Player.log");
                    if (log.isFile()) {
                        unity.add(log);
                        alias.put(log, "unity-" + pfx.getName() + "-Player.log");
                    }
                }
            }
        }
        unity.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        files.addAll(unity.subList(0, Math.min(3, unity.size())));
        int n = 0;
        app.ezsteam.runtime.LogScrubber scrub = app.ezsteam.runtime.LogScrubber.forRootfs(rootfs);
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(os)) {
            // Device and build, so a report sent without a message still says where it came from
            // (model, Android, SoC; nothing personal)
            // 기기와 빌드 정보. 글 없이 보낸 제보도 어디서 왔는지 알 수 있게 함(모델, 안드로이드, SoC. 개인정보 없음)
            zip.putNextEntry(new java.util.zip.ZipEntry("device.txt"));
            zip.write(deviceText().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("app-log.txt"));
            zip.write(scrub.scrub(appLog).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("processes.txt"));
            zip.write(scrub.scrub(procs).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            // Steam install script records in each game prefix (registry keys under Valve\Steam\Apps):
            // shows where Steam records finished redistributables, to compare with the base prefix
            // 게임 prefix마다 Steam 설치 스크립트 기록(Valve\Steam\Apps 아래 레지스트리 키): Steam이 끝난
            // 재배포를 어디에 기록하는지 보여 줌. 기본 prefix와 비교하기 위함
            zip.putNextEntry(new java.util.zip.ZipEntry("prefix-redist-keys.txt"));
            zip.write(scrub.scrub(prefixRedistKeys(rootfs)).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            // Where the app's storage goes (user report: about 6 GB of app data, 2026-10-20)
            // 앱 저장 공간이 어디에 쓰이는지(사용자 보고: 앱 데이터 약 6 GB, 2026-10-20)
            zip.putNextEntry(new java.util.zip.ZipEntry("storage-usage.txt"));
            zip.write(scrub.scrub(app.ezsteam.runtime.ReportScan.storageUsage(getFilesDir(), getCacheDir(), rootfs))
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            // Installed games that name a DLL of the DirectX June 2010 runtime (is DXSETUP needed?)
            // DirectX 2010년 6월 런타임 DLL 이름을 가진 설치된 게임(DXSETUP이 필요한지)
            java.util.List<File> libs = new java.util.ArrayList<>();
            libs.add(new File(rootfs, "home/steam/.local/share/Steam/steamapps"));
            File shared = MainActivity.sharedLibrary(this) ? app.ezsteam.runtime.SharedStorage.steamLibrary(this) : null;
            if (shared != null) libs.add(new File(shared, "steamapps"));
            // Which DLLs are Proton's (and for which CPU) or Microsoft's, and what games loaded
            // 어느 DLL이 Proton 것(어느 CPU용)이고 어느 것이 Microsoft 것인지, 게임이 무엇을 불러왔는지
            zip.putNextEntry(new java.util.zip.ZipEntry("dll-check.txt"));
            zip.write(scrub.scrub(app.ezsteam.runtime.ReportScan.dllCheck(
                    new File(rootfs, "home/steam/.local/share/Steam"),
                    new File(rootfs, "home/steam/.local/share/ezsteam/base-prefix"),
                    new File(rootfs, "home/steam/ezs-logs"))).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("dx-usage.txt"));
            zip.write(scrub.scrub(app.ezsteam.runtime.ReportScan.dxUsage(libs)).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            // Order: session, Steam, web helper and X server logs first, Proton logs (large, one per
            // game) last with the newest first, and only the newest 2 kept copies (old-steam-*). A
            // report cut off while being written (2026-10-28, 0.4 MB of a larger file) ended inside
            // old Proton logs, and the web helper logs after them were missing
            // 순서: 세션, Steam, 웹 도우미, X 서버 로그를 먼저, Proton 로그(크고 게임마다 하나)는 마지막에 최신 순으로,
            // 보관본(old-steam-*)은 최근 2개만. 쓰는 중에 잘린 제보(2026-10-28, 더 큰 파일의 0.4 MB)가 옛 Proton
            // 로그 안에서 끝나 그 뒤의 웹 도우미 로그가 빠졌음
            java.util.List<File> proton = new java.util.ArrayList<>(), kept = new java.util.ArrayList<>();
            for (java.util.Iterator<File> it = files.iterator(); it.hasNext(); ) {
                File f = it.next();
                String nm = f.getName();
                if (nm.startsWith("old-steam-")) {
                    kept.add(f);
                    it.remove();
                } else if (nm.startsWith("steam-") && nm.endsWith(".log")) {
                    proton.add(f);
                    it.remove();
                }
            }
            java.util.Comparator<File> newest = (a, b) -> Long.compare(b.lastModified(), a.lastModified());
            proton.sort(newest);
            kept.sort(newest);
            files.addAll(proton);
            files.addAll(kept.subList(0, Math.min(2, kept.size())));
            byte[] buf = new byte[1 << 16];
            for (File f : files) {
                zip.putNextEntry(new java.util.zip.ZipEntry("logs/" + alias.getOrDefault(f, f.getName())));
                boolean image = f.getName().endsWith(".png");
                boolean maskIp = f.getName().equals("connection_log.txt") || f.equals(whLog) || f.equals(whTxt)
                        || f.getName().equals("cef_log.txt") || f.getName().equals("steamwebhelper-prev.log");
                try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(f, "r")) {
                    long len = in.length();
                    if (len > LOG_SAVE_MAX) {
                        in.seek(len - LOG_SAVE_MAX);
                        zip.write(("[앞부분 " + (len - LOG_SAVE_MAX) + "바이트 생략]\n").getBytes(StandardCharsets.UTF_8));
                    }
                    if (image) {
                        int r;
                        while ((r = in.read(buf)) > 0) zip.write(buf, 0, r);
                    } else {
                        // Line by line through the scrubber / 줄 단위로 가리개를 거침
                        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(
                                java.nio.channels.Channels.newInputStream(in.getChannel()), StandardCharsets.UTF_8));
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (maskIp) line = maskAddresses(line);
                            zip.write(scrub.scrub(line).getBytes(StandardCharsets.UTF_8));
                            zip.write('\n');
                        }
                    }
                } catch (IOException e) {
                    zip.write(("[읽기 실패: " + e + "]\n").getBytes(StandardCharsets.UTF_8));
                }
                zip.closeEntry();
                n++;
            }
            for (int i = 0; i < shots.size(); i++) {
                zip.putNextEntry(new java.util.zip.ZipEntry(String.format(Locale.ROOT, "screen/frame-%d.png", i + 1)));
                zip.write(shots.get(i));
                zip.closeEntry();
            }
            zip.finish();
            log("로그 저장 완료: 로그 파일 " + n + "개 + 앱 로그 + 프로세스 목록 (계정·표시 이름, SteamID, 이메일은 가림)"
                    + (shots.isEmpty() ? "" : " + 화면 " + shots.size() + "장(IO 통계 켬일 때만, 화면 그대로라 이름이 보일 수 있음)"));
        }
        // Failures go to the caller (it reports them and removes or replaces the file); before this
        // a failure was logged inside and the caller still reported the file as saved
        // 실패는 호출한 쪽으로 넘김(호출한 쪽이 알리고 파일을 지우거나 다른 방법으로 저장함). 전에는 안에서
        // 실패를 기록만 하고 호출한 쪽은 저장됐다고 알렸음
    }

    /**
     * Up to max consecutive distinct frames of the X screen file within ms, as PNG. The fourth byte
     * of each pixel is kept as the PNG alpha (unpremultiplied): the padding byte of Xvfb / ezs-wl or
     * whatever the X server copied from a 32-bit window. Why: white bands at the top and bottom of
     * Steam's HOME menu panel on Xwayland (2026-10-29) can be transparent pixels with white colour or
     * parts not drawn in some frames; only the stored values tell which. Frames are copied first and
     * compressed afterwards, so the copies follow each other closely.
     * X 화면 파일에서 ms 안의 서로 다른 연속 프레임을 최대 max장 PNG로 만듦. 픽셀의 넷째 바이트는 PNG 알파로 그대로
     * 둠(곱하지 않은 값): Xvfb·ezs-wl의 채움 바이트, 또는 X 서버가 32비트 창에서 복사한 값. 이유: Xwayland에서 Steam
     * HOME 메뉴 패널 위·아래의 흰 띠(2026-10-29)는 색이 흰 투명 픽셀일 수도, 어떤 프레임에서 그려지지 않은 부분일
     * 수도 있음. 저장된 값만이 어느 쪽인지 알려 줌. 먼저 복사만 하고 압축은 나중에 해서 복사가 촘촘히 이어지게 함
     */
    private java.util.List<byte[]> screenFrames(int max, long ms) {
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        XvfbFramebuffer f = fb;
        if (f == null || f.bitsPerPixel != 32) return out;
        int w = f.width, h = f.height, stride = f.bytesPerLine / 4;
        java.util.List<int[]> frames = new java.util.ArrayList<>();
        try {
            int[] cur = new int[w * h];
            int[] prev = null;
            long end = SystemClock.uptimeMillis() + ms;
            while (frames.size() < max && SystemClock.uptimeMillis() < end) {
                // Little-endian ints: B,G,R,X in memory = 0xXXRRGGBB, the layout of an Android colour
                // 리틀 엔디언 int: 메모리의 B,G,R,X = 0xXXRRGGBB, 안드로이드 색 배치와 같음
                java.nio.IntBuffer ib = f.pixels().asIntBuffer();
                for (int y = 0; y < h; y++) {
                    ib.position(y * stride);
                    ib.get(cur, y * w, w);
                }
                if (prev == null || !Arrays.equals(prev, cur)) {
                    frames.add(cur);
                    prev = cur;
                    cur = new int[w * h];
                }
                SystemClock.sleep(4);
            }
            for (int[] px : frames) {
                android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                // Stored as given (no premultiply), so the fourth byte reaches the PNG unchanged
                // 받은 값 그대로 저장(알파 곱하기 없음): 넷째 바이트가 바뀌지 않고 PNG에 들어감
                bm.setPremultiplied(false);
                bm.setPixels(px, 0, w, 0, 0, w, h);
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bo);
                bm.recycle();
                out.add(bo.toByteArray());
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            log("화면 캡처 실패: " + e);
        }
        return out;
    }

    /**
     * Registry sections under "Valve\\Steam\\Apps" (and their values) of every prefix: game
     * prefixes in compatdata and the base prefix. Small text; nothing personal in these keys.
     * 모든 prefix(compatdata의 게임 prefix와 기본 prefix)의 "Valve\\Steam\\Apps" 아래 레지스트리 구역과
     * 그 값. 작은 글이며 이 키에는 개인 정보가 없음
     */
    private static String prefixRedistKeys(File rootfs) {
        StringBuilder sb = new StringBuilder();
        java.util.List<File> regs = new java.util.ArrayList<>();
        File[] pfxs = new File(rootfs, "home/steam/.local/share/Steam/steamapps/compatdata").listFiles(File::isDirectory);
        for (File d : pfxs == null ? new File[0] : pfxs) regs.add(new File(d, "pfx/system.reg"));
        regs.add(new File(rootfs, "home/steam/.local/share/ezsteam/base-prefix/pfx/system.reg"));
        for (File reg : regs) {
            if (!reg.isFile()) continue;
            sb.append("== ").append(reg.getParentFile().getParentFile().getName())
                    .append(new File(reg.getParentFile().getParentFile(), "version").isFile() ? " (완성)" : " (미완성)")
                    .append('\n');
            try (BufferedReader r = new BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(reg), StandardCharsets.UTF_8))) {
                String line;
                boolean in = false;
                int n = 0;
                while ((line = r.readLine()) != null && n < 400) {
                    if (line.startsWith("[")) in = line.toLowerCase(Locale.ROOT).contains("valve\\\\steam\\\\apps");
                    if (in && !line.isEmpty()) {
                        sb.append(line).append('\n');
                        n++;
                    }
                }
            } catch (IOException e) {
                sb.append("읽기 실패: ").append(e).append('\n');
            }
        }
        return sb.length() == 0 ? "prefix 없음\n" : sb.toString();
    }

    /**
     * Short message on screen: the log view is hidden while Steam runs, so save results were not
     * visible (user report: "log saving does not seem to work")
     * 화면에 짧은 알림: Steam이 도는 동안 로그 화면이 숨겨져 저장 결과가 보이지 않았음(사용자 보고:
     * "로그 저장이 안 되는 것 같음")
     */
    private String deviceText() {
        StringBuilder sb = new StringBuilder();
        sb.append("app build: ").append(BuildConfig.GIT_SHA).append('\n');
        sb.append("device: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                .append(" (").append(android.os.Build.DEVICE).append(")\n");
        sb.append("android: ").append(android.os.Build.VERSION.RELEASE)
                .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            sb.append("soc: ").append(android.os.Build.SOC_MANUFACTURER).append(' ').append(android.os.Build.SOC_MODEL).append('\n');
        }
        sb.append("hardware: ").append(android.os.Build.HARDWARE).append('\n');
        sb.append("rootfs: ").append(RootfsInstaller.installedVersion(this)).append('\n');
        // Free space: app storage (rootfs, Steam library "/") and shared storage (EZlator library)
        // 여유 공간: 앱 저장소(rootfs, Steam 기본 라이브러리 "/")와 공용 저장소(EZlator 라이브러리)
        sb.append("free: app ").append(freeGb(getFilesDir())).append(", shared ")
                .append(freeGb(android.os.Environment.getExternalStorageDirectory())).append('\n');
        sb.append("time: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)
                .format(new java.util.Date())).append('\n');
        return sb.toString();
    }

    private void notice(String text) {
        runOnUiThread(() -> android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show());
    }

    /** Scrollable, selectable text window / 스크롤·선택 가능한 글 창 */
    private void showTextWindow(String title, String text) {
        TextView tv = new TextView(this);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(11);
        tv.setTextIsSelectable(true);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        tv.setText(text);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton(s(R.string.btn_close), null)
                .show();
    }

    /**
     * Window with this app's child processes now and at the highest count (pid, memory, name),
     * so the list does not get lost among log lines.
     * 이 앱의 자식 프로세스를 현재와 가장 많았던 때로 보여 주는 창(pid, 메모리, 이름).
     * 목록이 로그 줄 사이에 묻히지 않게 함
     */
    /** Wine's own helper programs, not the game / Wine 자체 보조 프로그램(게임 아님) */
    private static final java.util.Set<String> WINE_HELPERS = new java.util.HashSet<>(java.util.Arrays.asList(
            "services.exe", "winedevice.exe", "plugplay.exe", "rpcss.exe", "svchost.exe", "tabtip.exe",
            "explorer.exe", "steam.exe", "start.exe", "conhost.exe", "rundll32.exe", "winemenubuilder.exe"));

    /**
     * Process window: the list now (state, memory, CPU seconds) and at the highest count, plus the
     * threads of the game (largest non-helper .exe) to tell loading from a blocked thread.
     * Columns: pid, state (R running, S sleeping, D disk wait, T stopped), memory, CPU time, name.
     * 프로세스 창: 현재 목록(상태, 메모리, CPU 초)과 가장 많았던 때, 그리고 게임(보조 프로그램이 아닌
     * 가장 큰 .exe)의 스레드 목록. 로딩 중인지 막힌 스레드인지 구분하기 위함.
     * 열: pid, 상태(R 실행, S 대기, D 디스크 대기, T 정지), 메모리, CPU 시간, 이름
     */
    private void showProcesses() {
        showTextWindow(s(R.string.menu_processes), processText());
    }

    /** Text of the process window (also saved by [로그 저장]) / 프로세스 창 내용([로그 저장]에도 들어감) */
    private String processText() {
        java.util.List<app.ezsteam.runtime.ChildProcessCounter.Proc> ps =
                app.ezsteam.runtime.ChildProcessCounter.descendants(android.os.Process.myPid());
        // CPU% = use over the last 2 s monitor interval (100 = one core) / CPU% = 마지막 2초 감시 주기 사용률(100 = 코어 하나)
        java.util.Map<Integer, Float> pct = cpuPct;
        StringBuilder sb = new StringBuilder("현재 " + ps.size() + "개 (한도: 모든 앱 합계 32)\n"
                + "   pid 상태  메모리   CPU시간 CPU%  이름\n");
        for (app.ezsteam.runtime.ChildProcessCounter.Proc pr : ps) {
            Float c = pct.get(pr.pid);
            sb.append(String.format(Locale.ROOT, "%6d %c %5dMB %7.1fs %4.0f%%  %s%n",
                    pr.pid, pr.state, pr.rssKb / 1024, pr.cpuTicks / 100.0, c == null ? 0f : c, pr.name));
        }
        app.ezsteam.runtime.ChildProcessCounter.Proc game = gameOf(ps);
        if (game != null) {
            sb.append("\n게임 스레드 (").append(game.pid).append(")\n   tid 상태   CPU시간  이름  대기함수\n")
                    .append(app.ezsteam.runtime.ChildProcessCounter.threads(game.pid));
        }
        sb.append("\n최대 ").append(procMax).append("개, ").append(procListAtMax);
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        finishing = true;
        if (pcReceiver != null) {
            try {
                unregisterReceiver(pcReceiver);
            } catch (RuntimeException ignored) {
                // already gone / 이미 없음
            }
            pcReceiver = null;
        }
        android.net.ConnectivityManager.NetworkCallback nc = netCallback;
        if (nc != null) {
            try {
                ((android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(nc);
            } catch (RuntimeException ignored) {
            }
        }
        view.setFramebuffer(null);
        app.ezsteam.runtime.AudioServer a = audio;
        if (a != null) a.close();
        app.ezsteam.runtime.PresentProbe pp = present;
        if (pp != null) pp.close();
        app.ezsteam.runtime.PadFile pf = pad;
        if (pf != null) {
            try {
                pf.close();
            } catch (IOException ignored) {
                // nothing to do / 할 일 없음
            }
        }
        XTestClient x = xtest;
        if (x != null) {
            try {
                x.close();
            } catch (IOException ignored) {
            }
        }
        new Thread(this::stopProcess, "ezs-display-stop").start();
        SessionService.stop(this);
        java.io.Writer w = sessionLog;
        sessionLog = null;
        if (w != null) {
            try {
                w.close();
            } catch (IOException ignored) {
                // nothing to do / 할 일 없음
            }
        }
        super.onDestroy();
    }

    /**
     * Close display.sh's stdin (it then stops Xvfb and the test windows), force-stop after 3 s.
     * The screen file stays mapped until fb is closed here.
     * display.sh의 stdin을 닫음(그러면 Xvfb와 시험 창을 끔). 3초 뒤에도 남으면 강제 종료함.
     * 화면 파일 매핑은 여기서 fb를 닫을 때까지 유지됨
     */
    /** Send a signal to every child process of this app (same uid) / 이 앱의 모든 자식 프로세스에 시그널을 보냄(같은 uid) */
    private static void signalChildren(int sig) {
        for (app.ezsteam.runtime.ChildProcessCounter.Proc pr
                : app.ezsteam.runtime.ChildProcessCounter.descendants(android.os.Process.myPid())) {
            android.os.Process.sendSignal(pr.pid, sig);
        }
    }

    private void stopProcess() {
        Process p = proc;
        if (p != null) {
            try {
                p.getOutputStream().close();
            } catch (IOException ignored) {
            }
            // Test mode stops by itself after stdin closes; Steam mode (and anything left) is ended
            // here: SIGTERM to every child process, then SIGKILL to those still there after 3 s
            // test 모드는 stdin이 닫히면 스스로 끝남. Steam 모드(와 남은 것)는 여기서 끝냄:
            // 모든 자식 프로세스에 SIGTERM, 3초 뒤에도 남은 것은 SIGKILL
            if (!"steam".equals(mode)) {
                for (int i = 0; i < 30 && p.isAlive(); i++) SystemClock.sleep(100);
            }
            signalChildren(15);
            for (int i = 0; i < 30 && !app.ezsteam.runtime.ChildProcessCounter
                    .descendants(android.os.Process.myPid()).isEmpty(); i++) SystemClock.sleep(100);
            signalChildren(9);
            if (p.isAlive()) p.destroy();
        }
        XvfbFramebuffer f = fb;
        if (f != null) {
            try {
                f.close();
            } catch (IOException ignored) {
            }
        }
    }
}
