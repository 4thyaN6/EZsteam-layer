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
    private String mode = "test";
    /** X display number used by display.sh / display.sh가 쓰는 X 디스플레이 번호 */
    private static final int DISPLAY = 98;

    private FramebufferView view;
    private TextView statsView, logView;
    private ScrollView logScroll;
    private volatile Process proc;
    private volatile XTestClient xtest;
    private volatile XvfbFramebuffer fb;
    private volatile boolean finishing;

    /** Last key sent to X, shown in the top bar / X로 보낸 마지막 키, 위쪽 막대에 표시 */
    private volatile String lastKey = "-";
    private volatile boolean shaderLogged;
    /** Child processes now / most seen, free memory (monitor thread) / 현재·최대 자식 프로세스 수, 여유 메모리(감시 스레드) */
    private volatile int procNow, procMax;
    private volatile long memFreeMb;
    private volatile boolean procListLogged;
    /** Android kills an app's extra processes beyond 32 (all apps together); list ours when close
     *  안드로이드는 앱 추가 프로세스가 32개(모든 앱 합계)를 넘으면 종료시킴. 가까워지면 목록을 남김 */
    private static final int PROC_WARN = 28;
    /** Turn on PRoot's crash report (PROOT_CRASH_LOG) / PRoot 크래시 보고(PROOT_CRASH_LOG) 켜기 */
    private static final boolean CRASH_LOG = false;
    private static final String[] SCALE_NAMES = {"선형", "바이큐빅", "바이큐빅+선명"};
    private Button scaleButton;

    // Touch state / 터치 상태
    private boolean leftDown, twoFinger, scrolled;
    private long twoFingerStart;
    private float lastAvgY, scrollAcc;
    private int[] lastPos;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        int w = getIntent().getIntExtra(EXTRA_W, 1280);
        int h = getIntent().getIntExtra(EXTRA_H, 720);
        String m = getIntent().getStringExtra(EXTRA_MODE);
        if ("steam".equals(m)) mode = "steam";

        // Screen view full size, a small bar (stats + buttons) on top, log overlay below it
        // 화면 뷰는 전체 크기, 위에 작은 막대(통계 + 버튼), 그 아래 로그 겹침 표시
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new FramebufferView(this);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0x99000000);
        statsView = new TextView(this);
        statsView.setTextColor(Color.WHITE);
        statsView.setTypeface(Typeface.MONOSPACE);
        statsView.setTextSize(12);
        statsView.setText(w + "x" + h + " 시작 중");
        bar.addView(statsView);
        scaleButton = addButton(bar, "확대: " + SCALE_NAMES[FramebufferView.SCALE_SHARP], v -> cycleScale());
        addButton(bar, "키보드", v -> toggleKeyboard());
        addButton(bar, "로그", v -> logScroll.setVisibility(logScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        addButton(bar, "닫기", v -> finish());
        root.addView(bar, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextColor(Color.WHITE);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        logView.setBackgroundColor(0xB0000000);
        logScroll.addView(logView);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.height = (int) (220 * getResources().getDisplayMetrics().density);
        root.addView(logScroll, lp);
        setContentView(root);
        hideSystemBars();

        view.setStatsListener((fps, ms, changed, fw, fh) -> {
            String err = view.shaderError;
            if (err != null && !shaderLogged) {
                shaderLogged = true;
                log("확대 셰이더 오류(선형으로 대체): " + err);
            }
            runOnUiThread(() -> statsView.setText(String.format(Locale.ROOT,
                    "%dx%d  화면 %.0ffps  내용변경 %.0f/s  업로드 %.1fms  키 %s  자식 %d(최대 %d)  여유 %.1fGB  ",
                    fw, fh, fps, changed, ms, lastKey, procNow, procMax, memFreeMb / 1024f)));
        });
        // Soft keyboard text arrives here as key events / 소프트 키보드 텍스트는 키 이벤트로 바뀌어 여기로 옴
        view.setKeyListener(this::sendKey);
        view.setOnTouchListener((v, e) -> onTouch(e));
        view.requestFocus();

        log("app build=" + BuildConfig.GIT_SHA + ", 요청 해상도 " + w + "x" + h);
        new Thread(() -> start(w, h), "ezs-display").start();
        startMonitor();
    }

    private Button addButton(LinearLayout bar, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        // Buttons must not take key focus from the screen view / 버튼이 화면 뷰의 키 포커스를 가져가지 않게 함
        b.setFocusable(false);
        bar.addView(b);
        return b;
    }

    /** Next scaling mode: linear → bicubic → bicubic + sharpen / 다음 확대 방식: 선형 → 바이큐빅 → 바이큐빅 + 선명 */
    private void cycleScale() {
        int m = (view.getScaleMode() + 1) % SCALE_NAMES.length;
        view.setScaleMode(m);
        scaleButton.setText("확대: " + SCALE_NAMES[m]);
    }

    /** Immersive full screen (system bars hidden) / 몰입형 전체 화면(시스템 막대 숨김) */
    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    /** Append a log line, keeping the last LOG_LINES / 로그 줄 추가, 마지막 LOG_LINES줄만 유지 */
    private void log(String s) {
        runOnUiThread(() -> {
            logLines.addLast(s);
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
            } else {
                c.rootId = true;
            }
            c.display = ":" + DISPLAY;
            log("모드 " + mode);
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
                }
            }
            log("exit=" + p.waitFor() + " (자식 프로세스 최대 " + procMax + "개, 여유 메모리 "
                    + memFreeMb + " MB)");
        } catch (Throwable e) {
            log("ERROR " + e);
        }
        if (fb == null) runOnUiThread(() -> logScroll.setVisibility(View.VISIBLE));
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
            log("XTEST " + xtest.xtestMajor + "." + xtest.xtestMinor + " 연결, 서버 " + xtest.vendor
                    + " " + xtest.screenWidth + "x" + xtest.screenHeight);
        } catch (IOException e) {
            log("X 입력 연결 실패: " + e);
        }
        if ("steam".equals(mode)) startSteamLogTail(c.rootfs);
        // Steam mode keeps the log open (first start can take minutes) / steam 모드는 로그를 열어 둠(첫 시작이 몇 분 걸릴 수 있음)
        if (!"steam".equals(mode)) runOnUiThread(() -> logScroll.setVisibility(View.GONE));
    }

    // ---- touch: absolute pointer, like a touch screen / 터치: 터치스크린처럼 절대 위치 포인터

    private boolean onTouch(MotionEvent e) {
        XTestClient x = xtest;
        if (x == null) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int[] p = view.toScreen(e.getX(), e.getY());
                if (p == null) return true;
                x.motion(p[0], p[1]);
                x.button(1, true);
                leftDown = true;
                twoFinger = false;
                lastPos = p;
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
                // Short two-finger tap without scrolling = right click / 스크롤 없는 짧은 두 손가락 탭 = 오른쪽 클릭
                if (twoFinger && !scrolled && SystemClock.uptimeMillis() - twoFingerStart < 300) {
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

    // ---- keys / 키

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Back closes the screen; everything else goes to X / 뒤로 가기는 화면 닫기, 나머지는 X로 보냄
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e);
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
        if (down) lastKey = KeyEvent.keyCodeToString(e.getKeyCode()).replace("KEYCODE_", "") + "→" + code;
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

    @Override
    protected void onPause() {
        super.onPause();
        view.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.onResume();
    }

    /** Lines of the Steam log shown in the app log (case-insensitive)
     *  앱 로그에 보여 줄 Steam 로그 줄(대소문자 무시) */
    private static final java.util.regex.Pattern STEAM_KEY = java.util.regex.Pattern.compile(
            "error|fail|fatal|denied|missing|not found|update|download|restart|webhelper|login|exit|lsof|assert"
                    + "|websocket|ezproton|proton|compat|launch|wrapper|fex|reaper|appid",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Proton log lines shown in the app log / 앱 로그에 보여 줄 Proton 로그 줄 */
    private static final java.util.regex.Pattern PROTON_KEY = java.util.regex.Pattern.compile(
            "err:|unhandled|exception|wine: |fex|failed|abort", java.util.regex.Pattern.CASE_INSENSITIVE);

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
            while (!finishing) {
                for (String line : steam.poll()) {
                    if (STEAM_KEY.matcher(line).find()) log("STEAM " + line);
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
                // Every 2 s look for a newer Proton log / 2초마다 더 새 Proton 로그를 찾음
                if (tick++ % 4 == 0) {
                    File[] fs = logDir.listFiles((d, name) -> name.startsWith("steam-") && name.endsWith(".log"));
                    File newest = null;
                    if (fs != null) for (File f : fs) if (newest == null || f.lastModified() > newest.lastModified()) newest = f;
                    if (newest != null && !newest.equals(proton.file)) {
                        proton.file = newest;
                        proton.pos = 0;
                        proton.partial.setLength(0);
                        log("PROTON 로그: " + newest.getName());
                    }
                }
                for (String line : proton.poll()) {
                    if (PROTON_KEY.matcher(line).find()) log("PROTON " + (line.length() > 220 ? line.substring(0, 220) : line));
                }
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
            while (!finishing) {
                java.util.List<app.ezsteam.runtime.ChildProcessCounter.Proc> ps =
                        app.ezsteam.runtime.ChildProcessCounter.descendants(android.os.Process.myPid());
                procNow = ps.size();
                if (procNow > procMax) procMax = procNow;
                am.getMemoryInfo(mi);
                memFreeMb = mi.availMem >> 20;
                if (procNow >= PROC_WARN && !procListLogged) {
                    procListLogged = true;
                    StringBuilder sb = new StringBuilder("PROC 자식 프로세스 " + procNow + "개 (한도 32):");
                    for (app.ezsteam.runtime.ChildProcessCounter.Proc pr : ps) {
                        sb.append("\nPROC   ").append(pr.pid).append(' ').append(pr.name).append(' ')
                                .append(pr.rssKb / 1024).append("MB");
                    }
                    log(sb.toString());
                }
                SystemClock.sleep(2000);
            }
        }, "ezs-monitor");
        t.setDaemon(true);
        t.start();
    }

    @Override
    protected void onDestroy() {
        finishing = true;
        view.setFramebuffer(null);
        XTestClient x = xtest;
        if (x != null) {
            try {
                x.close();
            } catch (IOException ignored) {
            }
        }
        new Thread(this::stopProcess, "ezs-display-stop").start();
        super.onDestroy();
    }

    /**
     * Close display.sh's stdin (it then stops Xvfb and the test windows), force-stop after 3 s.
     * The screen file stays mapped until fb is closed here.
     * display.sh의 stdin을 닫음(그러면 Xvfb와 시험 창을 끔). 3초 뒤에도 남으면 강제 종료함.
     * 화면 파일 매핑은 여기서 fb를 닫을 때까지 유지됨
     */
    private void stopProcess() {
        Process p = proc;
        if (p != null) {
            try {
                p.getOutputStream().close();
            } catch (IOException ignored) {
            }
            for (int i = 0; i < 30 && p.isAlive(); i++) SystemClock.sleep(100);
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
