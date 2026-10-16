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
    private volatile java.util.Map<Integer, Float> cpuPct = new java.util.HashMap<>();
    /** Game frame hand-over mode shown in the top bar (Steam mode) / 위쪽 막대에 보일 게임 화면 전달 방식(Steam 모드) */
    private volatile String wsiName = "";
    /** Session start, for the elapsed time in the top bar / 세션 시작 시각, 위쪽 막대 경과 시간용 */
    private final long startMs = SystemClock.elapsedRealtime();
    /** Turn on PRoot's crash report (PROOT_CRASH_LOG) / PRoot 크래시 보고(PROOT_CRASH_LOG) 켜기 */
    private static final boolean CRASH_LOG = false;
    private static final String[] SCALE_NAMES = {"선형", "바이큐빅", "바이큐빅+선명"};
    private Button scaleButton;

    // Touch state / 터치 상태
    private boolean leftDown, twoFinger, scrolled;
    private long twoFingerStart;
    private float lastAvgY, scrollAcc;
    private int[] lastPos;
    // Physical mouse state: buttons held (Android BUTTON_* bits), wheel remainder, lock button
    // 실제 마우스 상태: 누른 버튼(안드로이드 BUTTON_* 비트), 휠 나머지, 잠금 버튼
    private int mouseButtons;
    private float wheelV, wheelH, relRestX, relRestY;
    private Button mouseLockButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // Volume keys change the media volume, which the game sound uses (AudioServer, USAGE_GAME)
        // 볼륨 키가 게임 소리가 쓰는 미디어 볼륨을 바꾸게 함(AudioServer, USAGE_GAME)
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
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
        mouseLockButton = addButton(bar, "마우스 잠금", v -> toggleMouseLock());
        addButton(bar, "프로세스", v -> showProcesses());
        addButton(bar, "Proton", v -> showProtonLog());
        addButton(bar, "로그", v -> logScroll.setVisibility(logScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        addButton(bar, "로그 저장", v -> saveLogs());
        addButton(bar, "닫기", v -> confirmClose());
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
        view.setOnTouchListener((v, e) -> onTouch(e));
        // Physical mouse: hover moves, wheel and button changes (not only while a button is held),
        // and relative movement while locked (pointer capture)
        // 실제 마우스: 이동(버튼을 누르지 않아도), 휠, 버튼 변화, 잠금(포인터 캡처) 중 상대 이동
        view.setOnGenericMotionListener((v, e) -> e.isFromSource(android.view.InputDevice.SOURCE_MOUSE) && onMouse(e, false));
        view.setOnCapturedPointerListener((v, e) -> onMouse(e, true));
        // Android's own arrow is hidden over the screen: the X cursor drawn in the frame is the one that counts
        // 화면 위에서는 안드로이드 화살표를 숨김: 화면에 그려지는 X 커서가 실제 위치임
        view.setPointerIcon(android.view.PointerIcon.getSystemIcon(this, android.view.PointerIcon.TYPE_NULL));
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
                // Fewer Steam web helper processes (option on the main screen, on by default)
                // Steam 웹 도우미 프로세스 줄이기(메인 화면 옵션, 기본 켬)
                if (MainActivity.cefSlim(this)) c.env.put("EZS_CEF_FLAGS", MainActivity.CEF_SLIM_FLAGS);
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
            }
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
                x.focusUnderPointer();
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
        // Volume and mute keys stay with Android (they were sent to X and the volume did not change,
        // user report); the game sound plays through Android's media volume
        // 볼륨·음소거 키는 안드로이드가 처리함(X로 보내져 볼륨이 바뀌지 않았음, 사용자 보고).
        // 게임 소리는 안드로이드 미디어 볼륨으로 재생됨
        int kc = e.getKeyCode();
        if (kc == KeyEvent.KEYCODE_VOLUME_UP || kc == KeyEvent.KEYCODE_VOLUME_DOWN
                || kc == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(e);
        }
        // Back: from a physical device (keyboard Esc fallback, gamepad B, mouse) it is Esc in X;
        // only the on-screen back asks to close. Closing ends Steam and the game, and a physical
        // keyboard closed the screen again and again (user report).
        // 뒤로 가기: 실제 장치(키보드 Esc 대체, 게임패드 B, 마우스)에서 오면 X의 Esc로 보냄.
        // 화면의 뒤로 가기만 닫기를 물어봄. 닫으면 Steam과 게임이 끝나는데, 실제 키보드에서 화면이
        // 계속 닫혔음(사용자 보고)
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            // On-screen back (navigation bar, gesture) comes from the virtual device id -1
            // 화면의 뒤로 가기(내비게이션 바, 제스처)는 가상 장치 id -1에서 옴
            android.view.InputDevice dev = e.getDevice();
            boolean physical = e.getDeviceId() > 0 && (dev == null || !dev.isVirtual());
            // A mouse's back key is the right/back button, already sent as a button / 마우스의 뒤로 가기는 오른쪽·뒤로 버튼이며 이미 버튼으로 보냄
            if (physical && dev != null && (dev.getSources() & android.view.InputDevice.SOURCE_MOUSE) == android.view.InputDevice.SOURCE_MOUSE) return true;
            if (physical) {
                XTestClient x = xtest;
                if (x != null && e.getRepeatCount() == 0 && e.getAction() != KeyEvent.ACTION_MULTIPLE) {
                    x.key(XKeys.ESCAPE, e.getAction() == KeyEvent.ACTION_DOWN);
                    if (e.getAction() == KeyEvent.ACTION_DOWN) lastKey = "BACK→Esc";
                }
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_UP) confirmClose();
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
                .setTitle("Steam 종료")
                .setMessage("화면을 닫으면 Steam과 실행 중인 게임이 모두 종료됩니다.")
                .setPositiveButton("종료", (d, w) -> finish())
                .setNegativeButton("취소", null)
                .show();
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
            if ((now & changed) != 0) x.focusUnderPointer();
            for (int[] m : MOUSE_MAP) {
                if ((changed & m[0]) != 0) x.button(m[1], (now & m[0]) != 0);
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
        view.postDelayed(() -> mouseLockButton.setText(view.hasPointerCapture() ? "마우스 잠금 해제" : "마우스 잠금"), 200);
        log("마우스 잠금 " + (view.hasPointerCapture() ? "해제 요청" : "요청 (해제: 이 버튼을 터치)"));
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
            while (!finishing) {
                for (String line : steam.poll()) {
                    if (STEAM_KEY.matcher(line).find()) log("STEAM " + line);
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
                // Every 2 s look for a newer Proton log / 2초마다 더 새 Proton 로그를 찾음
                if (tick++ % 4 == 0) {
                    File[] fs = logDir.listFiles((d, name) -> name.startsWith("steam-") && name.endsWith(".log"));
                    File newest = null;
                    if (fs != null) for (File f : fs) if (newest == null || f.lastModified() > newest.lastModified()) newest = f;
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
            while (!finishing) {
                java.util.List<app.ezsteam.runtime.ChildProcessCounter.Proc> ps =
                        app.ezsteam.runtime.ChildProcessCounter.descendants(android.os.Process.myPid());
                procNow = ps.size();
                updateCpu(ps, lastTicks, lastNs);
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

    /**
     * CPU use of each child process since the previous pass (tick difference / elapsed time;
     * 100 = one core fully busy), and the top-bar summary: all children, the game, Xvfb, PRoot.
     * First pass only records the ticks. Processes gone since then are dropped.
     * 이전 회차 이후 자식 프로세스별 CPU 사용률(틱 차이 / 경과 시간, 100 = 코어 하나를 다 씀)과
     * 위쪽 막대 요약: 자식 전체, 게임, Xvfb, PRoot. 첫 회차는 틱만 기록함. 그사이 끝난 프로세스는 뺌
     */
    private void updateCpu(java.util.List<app.ezsteam.runtime.ChildProcessCounter.Proc> ps,
                           java.util.Map<Integer, Long> lastTicks, long lastNs) {
        long now = System.nanoTime();
        float sec = lastNs == 0 ? 0f : (now - lastNs) / 1e9f;
        java.util.Map<Integer, Float> pct = new java.util.HashMap<>();
        java.util.Map<Integer, Long> ticks = new java.util.HashMap<>();
        float all = 0, xvfb = 0, proot = 0;
        app.ezsteam.runtime.ChildProcessCounter.Proc game = gameOf(ps);
        for (app.ezsteam.runtime.ChildProcessCounter.Proc pr : ps) {
            ticks.put(pr.pid, pr.cpuTicks);
            Long before = lastTicks.get(pr.pid);
            if (sec <= 0 || before == null) continue;
            // Ticks are 1/100 s, so ticks / s = percent of one core / 틱은 1/100초이므로 틱 / 초 = 코어 하나 기준 %
            float p = Math.max(0, pr.cpuTicks - before) / sec;
            pct.put(pr.pid, p);
            all += p;
            String comm = exeName(pr);
            if (comm.equals("Xvfb")) xvfb += p;
            if (comm.contains("libproot")) proot += p;
        }
        lastTicks.clear();
        lastTicks.putAll(ticks);
        cpuPct = pct;
        if (sec <= 0) return;
        Float g = game == null ? null : pct.get(game.pid);
        cpuText = String.format(Locale.ROOT, "CPU 전체 %.0f%%  게임 %s  Xvfb %.0f%%  PRoot %.0f%%",
                all, g == null ? "-" : String.format(Locale.ROOT, "%.0f%%", g), xvfb, proot);
    }

    /** Process name before " | " (kernel comm) / " | " 앞의 프로세스 이름(커널 comm) */
    private static String exeName(app.ezsteam.runtime.ChildProcessCounter.Proc pr) {
        int i = pr.name.indexOf(" | ");
        return i >= 0 ? pr.name.substring(0, i) : pr.name;
    }

    /** The game: largest .exe that is not a Wine helper, or null / 게임: Wine 보조가 아닌 가장 큰 .exe, 없으면 null */
    private static app.ezsteam.runtime.ChildProcessCounter.Proc gameOf(
            java.util.List<app.ezsteam.runtime.ChildProcessCounter.Proc> ps) {
        app.ezsteam.runtime.ChildProcessCounter.Proc game = null;
        for (app.ezsteam.runtime.ChildProcessCounter.Proc pr : ps) {
            String exe = exeName(pr).toLowerCase(Locale.ROOT);
            if (exe.endsWith(".exe") && !WINE_HELPERS.contains(exe) && (game == null || pr.rssKb > game.rssKb)) {
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
            showTextWindow("Proton 로그", "Proton 로그 없음 (게임을 EZ Proton으로 실행하면 생김)");
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
                .setTitle("Proton 로그 선택 (최신 순)")
                .setItems(items, (dlg, which) -> showProtonLogFile(fs[which]))
                .setPositiveButton("닫기", null)
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
        showTextWindow("Proton 로그", text);
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
    private void saveLogs() {
        String name = "ezsteam-logs-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
                .format(new java.util.Date()) + ".zip";
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
        if (requestCode != REQ_SAVE_LOGS) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            log("로그 저장 취소");
            return;
        }
        android.net.Uri uri = data.getData();
        // Snapshots taken on the UI thread; writing happens in the background
        // UI 스레드에서 현재 내용을 떠 두고, 쓰기는 백그라운드에서 함
        String appLog = String.join("\n", logLines) + "\n";
        new Thread(() -> writeLogZip(uri, appLog, processText()), "ezs-log-save").start();
    }

    /**
     * Zip: app log, process list with game threads, every file in ~/ezs-logs (steam.log, Proton logs)
     * and /tmp/ezs-lsof.log. Files larger than LOG_SAVE_MAX keep only their end.
     * zip 내용: 앱 로그, 게임 스레드를 포함한 프로세스 목록, ~/ezs-logs의 모든 파일(steam.log,
     * Proton 로그), /tmp/ezs-lsof.log. LOG_SAVE_MAX보다 큰 파일은 끝부분만 넣음
     */
    private void writeLogZip(android.net.Uri uri, String appLog, String procs) {
        File rootfs = RootfsInstaller.rootfsDir(this);
        java.util.List<File> files = new java.util.ArrayList<>();
        File[] logs = new File(rootfs, "home/steam/ezs-logs").listFiles(File::isFile);
        if (logs != null) files.addAll(Arrays.asList(logs));
        File lsof = new File(rootfs, "tmp/ezs-lsof.log");
        if (lsof.isFile()) files.add(lsof);
        int n = 0;
        try (java.io.OutputStream os = getContentResolver().openOutputStream(uri);
             java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(os)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("app-log.txt"));
            zip.write(appLog.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("processes.txt"));
            zip.write(procs.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            byte[] buf = new byte[1 << 16];
            for (File f : files) {
                zip.putNextEntry(new java.util.zip.ZipEntry("logs/" + f.getName()));
                try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(f, "r")) {
                    long len = in.length();
                    if (len > LOG_SAVE_MAX) {
                        in.seek(len - LOG_SAVE_MAX);
                        zip.write(("[앞부분 " + (len - LOG_SAVE_MAX) + "바이트 생략]\n").getBytes(StandardCharsets.UTF_8));
                    }
                    int r;
                    while ((r = in.read(buf)) > 0) zip.write(buf, 0, r);
                } catch (IOException e) {
                    zip.write(("[읽기 실패: " + e + "]\n").getBytes(StandardCharsets.UTF_8));
                }
                zip.closeEntry();
                n++;
            }
            log("로그 저장 완료: 로그 파일 " + n + "개 + 앱 로그 + 프로세스 목록 (계정 이름이 들어 있을 수 있으니 공유 전 확인)");
        } catch (IOException | RuntimeException e) {
            log("로그 저장 실패: " + e);
        }
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
                .setPositiveButton("닫기", null)
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
        showTextWindow("자식 프로세스", processText());
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
        view.setFramebuffer(null);
        app.ezsteam.runtime.AudioServer a = audio;
        if (a != null) a.close();
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
