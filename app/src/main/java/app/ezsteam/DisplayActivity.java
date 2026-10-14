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
                    "%dx%d  화면 %.0ffps  내용변경 %.0f/s  업로드 %.1fms  키 %s  ", fw, fh, fps, changed, ms, lastKey)));
        });
        // Soft keyboard text arrives here as key events / 소프트 키보드 텍스트는 키 이벤트로 바뀌어 여기로 옴
        view.setKeyListener(this::sendKey);
        view.setOnTouchListener((v, e) -> onTouch(e));
        view.requestFocus();

        log("app build=" + BuildConfig.GIT_SHA + ", 요청 해상도 " + w + "x" + h);
        new Thread(() -> start(w, h), "ezs-display").start();
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

    private void log(String s) {
        runOnUiThread(() -> {
            logView.append(s + "\n");
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
            // Fake root: the X server needs setuid()/setgid() before xkbcomp (see drawtest.sh)
            // 가짜 root: X 서버는 xkbcomp 전에 setuid()/setgid()가 필요함(drawtest.sh 참고)
            c.rootId = true;
            c.display = ":" + DISPLAY;
            Process p = GlibcLauncher.startScript(c, "/opt/ezsteam/display.sh",
                    Arrays.asList(Integer.toString(w), Integer.toString(h)), null, null);
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
            log("exit=" + p.waitFor());
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
        runOnUiThread(() -> logScroll.setVisibility(View.GONE));
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
