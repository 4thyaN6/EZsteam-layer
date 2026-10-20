package app.ezsteam;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.ezsteam.runtime.ChildProcessCounter;
import app.ezsteam.runtime.DriverInstaller;
import app.ezsteam.runtime.GlibcLauncher;
import app.ezsteam.runtime.RootfsDownloader;
import app.ezsteam.runtime.RootfsInstaller;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * Test launcher screen: install rootfs, run the in-app environment check,
 * count child processes, prepare Steam. One background task at a time.
 * 시험용 실행기 화면: rootfs 설치, 앱 안 환경 시험, 자식 프로세스 수, Steam 준비.
 * 한 번에 작업 하나만 백그라운드로 돌림
 */
public class MainActivity extends Activity {

    private TextView logView;
    /** Screenshot of the drawing test (hidden until there is one) / 그리기 시험 화면 캡처(생기기 전엔 숨김) */
    private android.widget.ImageView shotView;
    private ScrollView scroll;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Layout built in code: buttons on top, log below
        // 코드로 화면 구성: 위에 버튼, 아래에 로그
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        // Start panel: what a player needs (Steam start, screen size, start-on-launch). The test and
        // setup buttons below live in a hidden admin section (user request: start Steam right away,
        // hide the admin mode)
        // 시작 패널: 사용자에게 필요한 것(Steam 시작, 화면 크기, 앱 시작 시 바로 실행). 아래 시험·설치
        // 버튼은 숨긴 관리자 영역에 둠(사용자 요청: Steam을 바로 시작, 관리자 모드는 숨김)
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        LinearLayout start = new LinearLayout(this);
        start.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(start);
        // Progress panel under the start row while a task runs (step, moving bar, elapsed time and
        // data received, refreshed every second) instead of only log lines
        // 작업이 도는 동안 시작 줄 아래 진행 패널(단계, 움직이는 막대, 경과 시간과 받은 양을 1초마다 갱신).
        // 로그 줄만 보이던 것을 대신함
        float dp = getResources().getDisplayMetrics().density;
        taskPanel = new LinearLayout(this);
        taskPanel.setOrientation(LinearLayout.VERTICAL);
        taskPanel.setPadding(0, (int) (8 * dp), 0, (int) (8 * dp));
        taskPanel.setVisibility(View.GONE);
        taskTitle = new TextView(this);
        taskTitle.setTextSize(16);
        taskPanel.addView(taskTitle);
        taskBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        taskBar.setIndeterminate(true);
        taskPanel.addView(taskBar, new LinearLayout.LayoutParams(-1, -2));
        taskDetail = new TextView(this);
        taskDetail.setTextSize(13);
        taskPanel.addView(taskDetail);
        root.addView(taskPanel);
        // One start button: installs whatever is missing first (with the progress panel), then
        // opens Steam; when everything is there it opens Steam at once (user request, 2026-10-20)
        // 시작 버튼 하나: 빠진 것이 있으면 먼저 설치하고(진행 패널 표시) Steam을 엶. 다 있으면 바로 엶
        // (사용자 요청, 2026-10-20)
        Button go = addButton(start, "Steam 시작", v -> startSteam());
        go.setTextSize(18);
        Button size = addButton(start, sizeLabel(), null);
        size.setOnClickListener(v -> {
            boolean big = !prefs.getBoolean(KEY_BIG_SCREEN, false);
            prefs.edit().putBoolean(KEY_BIG_SCREEN, big).apply();
            size.setText(sizeLabel());
        });
        Button auto = addButton(start, autoLabel(prefs.getBoolean(KEY_AUTOSTART, true)), null);
        auto.setOnClickListener(v -> {
            boolean on = !prefs.getBoolean(KEY_AUTOSTART, true);
            prefs.edit().putBoolean(KEY_AUTOSTART, on).apply();
            auto.setText(autoLabel(on));
        });
        // Shared EZlator Steam library on/off (startup got slower with it, user report; for
        // comparing and as a way back)
        // 공유 EZlator Steam 라이브러리 켬/끔(켠 뒤 시작이 느려졌다는 사용자 보고. 비교용이자 되돌리는 방법)
        Button shared = addButton(start, sharedLabel(prefs.getBoolean(KEY_SHARED_LIB, true)), null);
        shared.setOnClickListener(v -> {
            boolean on = !prefs.getBoolean(KEY_SHARED_LIB, true);
            prefs.edit().putBoolean(KEY_SHARED_LIB, on).apply();
            shared.setText(sharedLabel(on));
            log("공유 Steam 라이브러리 " + (on ? "켬" : "끔") + " (다음 Steam 시작부터)");
        });
        adminBox = new LinearLayout(this);
        adminBox.setOrientation(LinearLayout.VERTICAL);
        Button admin = addButton(start, "관리자 모드", null);
        admin.setOnClickListener(v -> setAdminShown(adminBox.getVisibility() != View.VISIBLE));
        root.addView(adminBox);
        // Install everything without starting Steam (kept for tests): rootfs, driver, tools, Steam,
        // skipping what is already there
        // Steam을 시작하지 않고 모두 설치(시험용으로 남김): rootfs, 드라이버, 도구, Steam. 이미 있는 것은 건너뜀
        LinearLayout row0 = new LinearLayout(this);
        row0.setOrientation(LinearLayout.HORIZONTAL);
        adminBox.addView(row0);
        addButton(row0, "전체 준비만", v -> runTask("setup", this::doSetupAll));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        adminBox.addView(row);
        addButton(row, "rootfs 설치", v -> runTask("install", this::doInstall));
        addButton(row, "환경 시험", v -> runTask("smoke", this::doSmoke));
        addButton(row, "자식 프로세스", v -> runTask("procs", this::doProcsLive));
        addButton(row, "Steam 준비", v -> runTask("prepare", this::doPrepare));
        addButton(row, "PRoot 상태", v -> runTask("proot", this::doProotInfo));

        // Second row: GPU checks / 두 번째 줄: GPU 확인
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        adminBox.addView(row2);
        addButton(row2, "드라이버 설치", v -> runTask("driver", this::doDriverInstall));
        addButton(row2, "GPU 확인", v -> runTask("gpu", this::doGpuCheck));
        addButton(row2, "시험 도구 설치", v -> runTask("tools", this::doToolsInstall));
        addButton(row2, "그리기 시험", v -> runTask("draw", this::doDrawTest));

        // Third row: live display test per X screen size (16:10 like the device, then the device's own size)
        // 세 번째 줄: X 화면 크기별 실시간 화면 시험(기기와 같은 16:10, 마지막은 기기 자체 해상도)
        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        adminBox.addView(row3);
        addButton(row3, "화면 1280x800", v -> openDisplay(1280, 800));
        addButton(row3, "화면 1920x1200", v -> openDisplay(1920, 1200));
        addButton(row3, "화면 2560x1600", v -> openDisplay(2560, 1600));
        int[] nat = nativeSize();
        addButton(row3, "화면 " + nat[0] + "x" + nat[1], v -> openDisplay(nat[0], nat[1]));

        // Fourth row: Valve Linux ARM64 Steam client (downloaded by the device from Valve)
        // 네 번째 줄: Valve Linux ARM64 Steam 클라이언트(기기가 Valve에서 직접 받음)
        LinearLayout row4 = new LinearLayout(this);
        row4.setOrientation(LinearLayout.HORIZONTAL);
        adminBox.addView(row4);
        addButton(row4, "Steam 설치", v -> runTask("steam-install", this::doSteamInstall));
        addButton(row4, "Steam 실행 1280x800", v -> openSteam(1280, 800));
        addButton(row4, "Steam 실행 1920x1200", v -> openSteam(1920, 1200));
        addButton(row4, "속도 시험", v -> runTask("speed", this::doSpeedTest));
        Button cef = addButton(row4, cefLabel(cefSlim(this)), null);
        cef.setOnClickListener(v -> {
            boolean on = !cefSlim(this);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_CEF_SLIM, on).apply();
            cef.setText(cefLabel(on));
            log("CEF 절감 " + (on ? "켬: " + CEF_SLIM_FLAGS : "끔") + " (다음 Steam 실행부터)");
        });
        // Test: Steam UI drawn with the GPU (off → Chromium's choice → ANGLE on Vulkan)
        // 시험: Steam 화면을 GPU로 그림(끔 → Chromium 선택 → Vulkan 위 ANGLE)
        Button gpu = addButton(row4, "Steam 화면 GPU: " + STEAM_GPU_MODES[steamGpu(this)][0], null);
        gpu.setOnClickListener(v -> {
            int m = (steamGpu(this) + 1) % STEAM_GPU_MODES.length;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_STEAM_GPU, m).apply();
            gpu.setText("Steam 화면 GPU: " + STEAM_GPU_MODES[m][0]);
            log("Steam 화면 GPU(시험) " + STEAM_GPU_MODES[m][0]
                    + (m == 0 ? "" : ": " + STEAM_GPU_MODES[m][1]) + " (다음 Steam 실행부터)");
        });
        // Diagnosis: the client's file I/O summary with its busiest written paths, once a minute
        // 진단: 클라이언트 파일 입출력 요약과 쓰기가 많은 경로를 1분마다 기록
        Button io = addButton(row4, "IO 통계: " + (ioStat(this) ? "켬" : "끔"), null);
        io.setOnClickListener(v -> {
            boolean on = !ioStat(this);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_IOSTAT, on).apply();
            io.setText("IO 통계: " + (on ? "켬" : "끔"));
            log("IO 통계 " + (on ? "켬(앱 로그 ezs-io:, PROOT_STAT 줄, 1분마다)" : "끔") + " (다음 Steam 실행부터)");
        });

        // Fifth row: how games hand finished frames to the X server, and the DXVK overlay
        // (both read when Steam starts, so they apply from the next [Steam 실행])
        // 다섯 번째 줄: 게임이 완성된 화면을 X 서버에 넘기는 방식, DXVK 표시줄
        // (둘 다 Steam 시작 때 읽으므로 다음 [Steam 실행]부터 적용됨)
        LinearLayout row5 = new LinearLayout(this);
        row5.setOrientation(LinearLayout.HORIZONTAL);
        adminBox.addView(row5);
        Button wsi = addButton(row5, wsiLabel(wsiMode(this)), null);
        wsi.setOnClickListener(v -> {
            int next = (wsiIndex(wsiMode(this)) + 1) % WSI_MODES.length;
            String m = WSI_MODES[next][0];
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_WSI, m).apply();
            wsi.setText(wsiLabel(m));
            log("게임 화면 " + WSI_MODES[next][1] + ": MESA_VK_WSI_DEBUG=" + WSI_MODES[next][2]
                    + " MESA_VK_WSI_PRESENT_MODE=" + WSI_MODES[next][3]
                    + " DXVK_CONFIG=" + (WSI_MODES[next][4].isEmpty() ? "(없음)" : WSI_MODES[next][4])
                    + " (다음 Steam 실행부터)");
        });
        Button hud = addButton(row5, hudLabel(dxvkHud(this)), null);
        hud.setOnClickListener(v -> {
            boolean on = !dxvkHud(this);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_DXVK_HUD, on).apply();
            hud.setText(hudLabel(on));
            log("DXVK HUD " + (on ? "켬: DXVK_HUD=" + DXVK_HUD_VALUE : "끔") + " (다음 Steam 실행부터)");
        });

        // Screenshot area for the drawing test / 그리기 시험 화면 캡처 영역
        shotView = new android.widget.ImageView(this);
        shotView.setAdjustViewBounds(true);
        shotView.setVisibility(View.GONE);
        adminBox.addView(shotView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (240 * getResources().getDisplayMetrics().density)));

        scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(12);
        logView.setTextIsSelectable(true);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        // Which build is running (compare with the commit on GitHub) / 실행 중인 빌드(GitHub 커밋과 비교)
        log("app build=" + BuildConfig.GIT_SHA + ", 설치 시각=" + installTime());
        log("installed=" + RootfsInstaller.installedVersion(this)
                + " bundled=" + RootfsInstaller.bundledVersion(this));
        if (RootfsInstaller.installedVersion(this).isEmpty()) {
            log("rootfs 미설치: [rootfs 설치]를 누르십시오 (APK에 없으면 GitHub Release에서 받음)");
        } else if (RootfsInstaller.needsInstall(this)) {
            log("APK 안 rootfs가 설치본과 다름: [rootfs 설치]로 갱신");
        }
        String missing = notReady();
        setAdminShown(prefs.getBoolean(KEY_ADMIN, false));
        if (!missing.isEmpty()) {
            log("설치 필요: " + missing + " → [Steam 시작]을 누르면 설치한 뒤 시작함 (인터넷 사용)");
        } else if (savedInstanceState == null && prefs.getBoolean(KEY_AUTOSTART, true)) {
            // Only on a fresh launch: coming back from the Steam screen must not start it again
            // 새로 실행했을 때만: Steam 화면에서 돌아왔을 때 다시 시작하면 안 됨
            autoStartPending = true;
        }
        // Shared storage (Download/.EZlator reports, EZlator Steam library), asked like EZlator does;
        // the automatic start waits for the answer
        // 공용 저장소(Download/.EZlator 제보, EZlator Steam 라이브러리) 권한을 EZlator처럼 물어봄.
        // 자동 시작은 답을 받은 뒤에 함
        if (!app.ezsteam.runtime.SharedStorage.granted(this)) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        } else {
            maybeAutoStart();
        }
    }

    private static final int REQ_STORAGE = 1;
    /** Automatic Steam start waiting for the permission answer / 권한 답을 기다리는 자동 Steam 시작 */
    private boolean autoStartPending;

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_STORAGE) return;
        log(app.ezsteam.runtime.SharedStorage.granted(this)
                ? "저장소 권한 있음: 제보는 Download/.EZlator/Reports, EZlator Steam 라이브러리 공유"
                : "저장소 권한 없음: 제보는 파일 선택 창으로 저장, Steam 라이브러리 공유 안 함");
        maybeAutoStart();
    }

    /**
     * Start button: Steam at once when everything is installed, otherwise the installation first
     * (same steps as [전체 준비만]) and Steam right after it succeeds
     * 시작 버튼: 다 설치돼 있으면 바로 Steam, 아니면 먼저 설치([전체 준비만]과 같은 단계)하고 성공하면 바로 Steam
     */
    private void startSteam() {
        if (busy) {
            log("다른 작업 진행 중");
            return;
        }
        String missing = notReady();
        if (missing.isEmpty()) {
            openSteam(steamSize()[0], steamSize()[1]);
            return;
        }
        log("설치 필요: " + missing + " → 설치 후 Steam 시작");
        runTask("setup", this::doSetupAll, () -> {
            if (notReady().isEmpty()) openSteam(steamSize()[0], steamSize()[1]);
            else log("설치가 끝나지 않아 Steam을 열지 않음 (위 로그 확인 후 [Steam 시작]을 다시 누르십시오)");
        });
    }

    private void maybeAutoStart() {
        if (!autoStartPending) return;
        autoStartPending = false;
        log("앱 시작 시 바로 Steam ([바로 실행]으로 끔)");
        openSteam(steamSize()[0], steamSize()[1]);
    }

    // ---- start panel / 시작 패널

    static final String KEY_SHARED_LIB = "steam.sharedlib";
    static final String KEY_AUTOSTART = "autostart", KEY_BIG_SCREEN = "steam.big", KEY_ADMIN = "admin.shown";
    /** Holds every setup and test row / 모든 설치·시험 줄을 담음 */
    private LinearLayout adminBox;

    private void setAdminShown(boolean shown) {
        adminBox.setVisibility(shown ? View.VISIBLE : View.GONE);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ADMIN, shown).apply();
    }

    /** Steam screen size: 1280x800 (default) or 1920x1200 / Steam 화면 크기: 1280x800(기본) 또는 1920x1200 */
    private int[] steamSize() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_BIG_SCREEN, false)
                ? new int[]{1920, 1200} : new int[]{1280, 800};
    }

    private String sizeLabel() {
        int[] s = steamSize();
        return "화면 " + s[0] + "x" + s[1];
    }

    private static String sharedLabel(boolean on) {
        return "EZlator 라이브러리: " + (on ? "켬" : "끔");
    }

    /** Shared EZlator Steam library wanted (DisplayActivity) / 공유 EZlator Steam 라이브러리 사용 여부 */
    static boolean sharedLibrary(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SHARED_LIB, true);
    }

    private static String autoLabel(boolean on) {
        return "바로 실행: " + (on ? "켬" : "끔");
    }

    /** X server and tools from [시험 도구 설치] are in the rootfs / [시험 도구 설치]의 X 서버·도구가 rootfs에 있음 */
    private boolean toolsInstalled() {
        return new File(RootfsInstaller.rootfsDir(this), "usr/bin/Xvfb").exists();
    }

    private boolean steamInstalled() {
        return new File(RootfsInstaller.rootfsDir(this), "home/steam/.local/share/Steam/steamrtarm64/steam").isFile();
    }

    /**
     * One-button setup for a new device: rootfs -> GPU driver -> tools (Xvfb) -> Steam client, each
     * only when missing, stopping at the first step that does not finish.
     * 새 기기용 한 번에 준비: rootfs -> GPU 드라이버 -> 도구(Xvfb) -> Steam 클라이언트. 빠진 것만 하고,
     * 끝나지 않은 단계에서 멈춤
     */
    private void doSetupAll() throws Exception {
        if (RootfsInstaller.installedVersion(this).isEmpty() || RootfsInstaller.needsInstall(this)) {
            log("[1/4] rootfs 설치");
            doInstall();
            if (RootfsInstaller.installedVersion(this).isEmpty()) {
                log("FAIL 준비 : rootfs 설치가 끝나지 않음 (위 로그 확인)");
                return;
            }
        } else {
            log("[1/4] rootfs 있음");
        }
        if (DriverInstaller.installedVersion(this, TURNIP).isEmpty()) {
            log("[2/4] 드라이버 설치");
            doDriverInstall();
            if (DriverInstaller.installedVersion(this, TURNIP).isEmpty()) {
                log("FAIL 준비 : 드라이버 설치가 끝나지 않음 (위 로그 확인)");
                return;
            }
        } else {
            log("[2/4] 드라이버 있음");
        }
        if (!toolsInstalled()) {
            log("[3/4] 시험 도구 설치 (X 서버 등)");
            doToolsInstall();
            if (!toolsInstalled()) {
                log("FAIL 준비 : 시험 도구 설치가 끝나지 않음 (위 로그 확인)");
                return;
            }
        } else {
            log("[3/4] 시험 도구 있음");
        }
        if (!steamInstalled()) {
            log("[4/4] Steam 설치");
            doSteamInstall();
            if (!steamInstalled()) {
                log("FAIL 준비 : Steam 설치가 끝나지 않음 (위 로그 확인)");
                return;
            }
        } else {
            log("[4/4] Steam 있음");
        }
        log("준비 끝 (첫 실행은 Steam이 스스로 갱신해 몇 분 걸릴 수 있음)");
    }

    /**
     * What is missing before Steam can start, empty when ready: rootfs, GPU driver, Steam client.
     * Steam 시작 전에 빠진 것, 준비되면 빈 문자열: rootfs, GPU 드라이버, Steam 클라이언트
     */
    private String notReady() {
        if (RootfsInstaller.installedVersion(this).isEmpty()) return "rootfs 미설치";
        if (RootfsInstaller.needsInstall(this)) return "rootfs 갱신 필요";
        if (DriverInstaller.installedVersion(this, "turnip").isEmpty()) return "드라이버 미설치";
        if (!toolsInstalled()) return "시험 도구(Xvfb) 미설치";
        if (!new File(RootfsInstaller.rootfsDir(this), "home/steam/.local/share/Steam/steamrtarm64/steam").isFile()) {
            return "Steam 미설치";
        }
        return "";
    }

    /** When this APK was installed or updated (tells whether "install -r" took effect)
     *  이 APK를 설치·갱신한 시각("install -r"이 적용됐는지 확인용) */
    private String installTime() {
        try {
            long t = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
            return new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date(t));
        } catch (Exception e) {
            return "?";
        }
    }

    /** Device screen size in landscape (long side first) / 가로 방향 기준 기기 화면 크기(긴 변 먼저) */
    @SuppressWarnings("deprecation")
    private int[] nativeSize() {
        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(m);
        return new int[]{Math.max(m.widthPixels, m.heightPixels), Math.min(m.widthPixels, m.heightPixels)};
    }

    /**
     * Open the live display test with the given X screen size (not while another task runs).
     * 주어진 X 화면 크기로 실시간 화면 시험을 엶(다른 작업 중에는 열지 않음)
     */
    private void openDisplay(int w, int h) {
        if (busy) {
            log("다른 작업 진행 중");
            return;
        }
        if (RootfsInstaller.installedVersion(this).isEmpty()) {
            log("먼저 rootfs 설치");
            return;
        }
        log("== display " + w + "x" + h);
        startActivity(new android.content.Intent(this, DisplayActivity.class)
                .putExtra(DisplayActivity.EXTRA_W, w).putExtra(DisplayActivity.EXTRA_H, h));
    }

    /** Open the display in Steam mode / 화면을 Steam 모드로 엶 */
    private void openSteam(int w, int h) {
        if (busy) {
            log("다른 작업 진행 중");
            return;
        }
        // Everything checked before the screen opens (a tester got a black screen with only
        // "FAIL tools : Xvfb 없음")
        // 화면을 열기 전에 모두 확인함(테스터가 "FAIL tools : Xvfb 없음"만 뜬 검은 화면을 받았음)
        String missing = notReady();
        if (!missing.isEmpty()) {
            log("Steam 시작 불가: " + missing + " → [Steam 시작]으로 설치하십시오");
            return;
        }
        log("== steam " + w + "x" + h);
        startActivity(new android.content.Intent(this, DisplayActivity.class)
                .putExtra(DisplayActivity.EXTRA_W, w).putExtra(DisplayActivity.EXTRA_H, h)
                .putExtra(DisplayActivity.EXTRA_MODE, "steam"));
    }

    /**
     * Install the client's libraries (apt) and download the ARM64 Steam bootstrap from Valve's
     * update server. Runs as PRoot fake root, only on the button press.
     * 클라이언트용 라이브러리(apt) 설치 + Valve 업데이트 서버에서 ARM64 Steam 부트스트랩 받기.
     * PRoot 가짜 root로 실행하며 버튼을 누를 때만 동작함
     */
    private void doSteamInstall() throws Exception {
        if (RootfsInstaller.installedVersion(this).isEmpty()) {
            log("먼저 rootfs 설치");
            return;
        }
        // Libraries + bootstrap + first self update need room / 라이브러리 + 부트스트랩 + 첫 자체 갱신에 공간 필요
        long free = getFilesDir().getUsableSpace();
        if (free < (3L << 30)) {
            log("공간 부족: 남은 공간 " + (free >> 20) + " MB, 3072 MB 이상 필요");
            return;
        }
        String dns = RootfsInstaller.writeResolvConf(this);
        log("DNS: " + (dns.isEmpty() ? "알 수 없음 (네트워크 연결 확인)" : dns));
        GlibcLauncher.Config c = config();
        c.rootId = true;
        Process p = GlibcLauncher.startScript(c, "/opt/ezsteam/steam-install.sh", null, null, null);
        pipe(p);
        log("exit=" + p.waitFor());
    }

    /**
     * Network / file write speed inside the rootfs (speedtest.sh), to find why Steam downloads are slow.
     * Downloads about 200 MB from Valve's update server, only on the button press.
     * rootfs 안 네트워크·파일 쓰기 속도(speedtest.sh), Steam 다운로드가 느린 원인 확인용.
     * Valve 업데이트 서버에서 약 200 MB를 받으며 버튼을 누를 때만 동작함
     */
    private void doSpeedTest() throws Exception {
        if (RootfsInstaller.installedVersion(this).isEmpty()) {
            log("먼저 rootfs 설치");
            return;
        }
        RootfsInstaller.writeResolvConf(this);
        GlibcLauncher.Config c = config();
        // Same ids as the Steam client / Steam 클라이언트와 같은 id
        c.changeId = "1000:1000";
        Process p = GlibcLauncher.startScript(c, "/opt/ezsteam/speedtest.sh", null, null, null);
        pipe(p);
        log("exit=" + p.waitFor());
    }

    /** Settings file and keys / 설정 파일과 키 */
    static final String PREFS = "ezsteam";
    static final String KEY_CEF_SLIM = "cef_slim";
    /**
     * Chromium flags for the Steam web helper that merge helper processes into fewer ones
     * (Android allows 32 extra processes for all apps together): no zygote (needs no sandbox, which
     * the client already runs without), GPU in the browser process, audio service in process, one
     * renderer process. The network service stays a separate process: with it in process the client
     * no longer accepts its UI WebSocket (0x3008, measured). Steam's own flags come after these
     * and win on conflicts. --disable-site-isolation-trials lets pages share one renderer.
     * Measured: 4 helpers (browser, renderer, network, storage); Steam passes its own
     * --disable-features internally, so feature switches from here are dropped. Why: a first game start reached 31 child
     * processes with 7 web helpers and Android ended the session (exit 137, 2026-10-18).
     * Steam 웹 도우미 보조 프로세스를 줄이는 Chromium 플래그(안드로이드는 모든 앱 합계 추가 프로세스
     * 32개까지 허용): zygote 없음(샌드박스 없이 실행해야 하며 클라이언트는 이미 그렇게 실행 중),
     * GPU를 브라우저 프로세스 안에서, 오디오 서비스도 안에서, 렌더러 1개. 네트워크 서비스는 별도
     * 프로세스로 둠: 안에서 돌리면 클라이언트가 UI WebSocket을 받아들이지 않음(0x3008, 실측).
     * Steam 자체 플래그가 뒤에 오므로 겹치면 Steam 값이 우선함. 네트워크 서비스까지 안에서 돌렸을
     * 때 실측: 도우미 9개 → 4개. --disable-site-isolation-trials는 페이지들이 렌더러 하나를 같이 쓰게 함.
     * 실측: 도우미 4개(browser, renderer, network, storage). Steam이 자체 --disable-features를 내부에서
     * 넘기므로 여기서 주는 기능 스위치는 빠짐. 이유: 게임 첫 실행 때 웹 도우미 7개로 자식 프로세스가
     * 31개가 되어 안드로이드가 세션을 끝냈음(exit 137, 2026-10-18)
     */
    static final String CEF_SLIM_FLAGS = "--no-sandbox --no-zygote --in-process-gpu --renderer-process-limit=1"
            + " --disable-site-isolation-trials";
    /**
     * Steam UI GPU test modes {label, extra Chromium flags}. Off: Steam starts with
     * -cef-disable-gpu (software drawing, as before). The other modes drop that option
     * (steam-run.sh, EZS_STEAM_GPU=1) and add flags to the web helper. The GPU stays inside the
     * browser process (--in-process-gpu above), so the helper count does not grow. Not yet measured
     * on the device: steamwebhelper-output.log in a report shows whether the GPU started.
     * Steam 화면 GPU 시험 방식 {이름, 추가 Chromium 플래그}. 끔: Steam을 -cef-disable-gpu로 시작함(기존처럼
     * 소프트웨어 그리기). 나머지는 그 옵션을 빼고(steam-run.sh, EZS_STEAM_GPU=1) 웹 도우미에 플래그를
     * 더함. GPU는 브라우저 프로세스 안에 있으므로(위 --in-process-gpu) 도우미 수는 늘지 않음. 기기에서
     * 아직 측정 안 함: 제보의 steamwebhelper-output.log로 GPU가 시작됐는지 확인함
     */
    static final String KEY_STEAM_GPU = "steam_gpu";
    static final String[][] STEAM_GPU_MODES = {
            {"끔", ""},
            {"Chromium 선택", "--ignore-gpu-blocklist"},
            {"ANGLE Vulkan", "--ignore-gpu-blocklist --use-gl=angle --use-angle=vulkan"},
    };

    static int steamGpu(android.content.Context ctx) {
        int m = ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_STEAM_GPU, 0);
        return m >= 0 && m < STEAM_GPU_MODES.length ? m : 0;
    }

    /** Client file I/O statistics (native ezs_iostat, EZS_IOSTAT=1) and PRoot syscall statistics
     *  (PROOT_SYSSTAT=60), off by default
     *  클라이언트 파일 입출력 통계(네이티브 ezs_iostat, EZS_IOSTAT=1)와 PRoot 시스템 호출 통계
     *  (PROOT_SYSSTAT=60), 기본 끔 */
    static final String KEY_IOSTAT = "iostat";

    static boolean ioStat(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_IOSTAT, false);
    }

    /** CEF process saving on (default) or off / CEF 프로세스 절감 켬(기본) 또는 끔 */
    static boolean cefSlim(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_CEF_SLIM, true);
    }

    private static String cefLabel(boolean on) {
        return "CEF 절감: " + (on ? "켬" : "끔");
    }

    static final String KEY_WSI = "wsi_mode";
    static final String KEY_DXVK_HUD = "dxvk_hud";
    /**
     * Ways Turnip's X11 software output hands a finished game frame to Xvfb:
     * {key, label, MESA_VK_WSI_DEBUG, MESA_VK_WSI_PRESENT_MODE, DXVK_CONFIG}.
     * Source facts (Mesa 26.2.4 wsi_common_x11.c, DXVK presenter):
     *   - With MIT-SHM the frame goes through the X Present extension and the present id a game
     *     waits on advances only when Xvfb's Present COMPLETE event is handled.
     *   - Without MIT-SHM (noshm) the frame is copied with PutImage and the present id never
     *     advances, so a present wait would never return.
     *   - DXVK waits for every present id (vkWaitForPresentKHR, no timeout) only in FIFO mode,
     *     i.e. with vsync; dxgi.syncInterval=0 makes it pick IMMEDIATE/MAILBOX and not wait.
     *   - Measured: in FIFO with MIT-SHM a game stopped right after creating its swapchain, with
     *     the game's main thread, dxvk-frame and both WSI threads waiting.
     * Modes: putimage = no Present events and no present wait at all (default);
     * shm-novsync = MIT-SHM but no present wait; shm-mailbox / shm-fifo = earlier settings.
     * The no-vsync modes cap the frame rate at 60 with DXVK's own limiter.
     * Turnip X11 소프트웨어 출력이 완성된 게임 화면을 Xvfb에 넘기는 방식:
     * {키, 이름, MESA_VK_WSI_DEBUG, MESA_VK_WSI_PRESENT_MODE, DXVK_CONFIG}.
     * 소스 근거(Mesa 26.2.4 wsi_common_x11.c, DXVK presenter):
     *   - MIT-SHM이 있으면 X Present 확장으로 넘기며, 게임이 기다리는 present id는 Xvfb의
     *     Present COMPLETE 이벤트를 처리해야만 올라감
     *   - MIT-SHM 없이(noshm) PutImage로 복사하면 present id가 전혀 올라가지 않으므로
     *     present 대기는 끝나지 않음
     *   - DXVK는 FIFO(수직 동기) 방식일 때만 present id마다 기다림(vkWaitForPresentKHR, 제한 시간 없음).
     *     dxgi.syncInterval=0이면 IMMEDIATE/MAILBOX를 골라 기다리지 않음
     *   - 실측: MIT-SHM + FIFO에서 게임이 스왑체인을 만든 직후 멈췄고, 게임 메인 스레드·dxvk-frame·
     *     WSI 스레드 둘이 모두 대기 중이었음
     * 방식: putimage = Present 이벤트도 present 대기도 없음(기본), shm-novsync = MIT-SHM이지만
     * present 대기 없음, shm-mailbox / shm-fifo = 이전 설정.
     * 수직 동기를 끄는 방식은 DXVK 자체 제한기로 초당 60장까지만 그림
     */
    static final String DXVK_NOVSYNC = "dxgi.syncInterval=0;d3d9.presentInterval=0;dxgi.maxFrameRate=60;d3d9.maxFrameRate=60";
    static final String[][] WSI_MODES = {
            {"putimage", "PutImage", "sw,noshm", "immediate", DXVK_NOVSYNC},
            {"shm-novsync", "SHM 동기 끔", "sw", "immediate", DXVK_NOVSYNC},
            {"shm-mailbox", "SHM mailbox", "sw", "mailbox", ""},
            {"shm-fifo", "SHM fifo", "sw", "fifo", ""},
    };
    /** DXVK overlay: frame rate, frame time graph, GPU load
     *  DXVK 표시줄: 초당 화면 수, 화면 시간 그래프, GPU 사용률 */
    static final String DXVK_HUD_VALUE = "fps,frametimes,gpuload";

    /** Selected frame hand-over mode key / 선택된 화면 전달 방식 키 */
    static String wsiMode(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_WSI, WSI_MODES[0][0]);
    }

    /** Row of WSI_MODES for a key, first row when unknown / 키에 해당하는 WSI_MODES 줄, 모르면 첫 줄 */
    static int wsiIndex(String key) {
        for (int i = 0; i < WSI_MODES.length; i++) if (WSI_MODES[i][0].equals(key)) return i;
        return 0;
    }

    private static String wsiLabel(String key) {
        return "게임 화면: " + WSI_MODES[wsiIndex(key)][1];
    }

    /** DXVK overlay on or off (default off) / DXVK 표시줄 켬 또는 끔(기본 끔) */
    static boolean dxvkHud(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DXVK_HUD, false);
    }

    private static String hudLabel(boolean on) {
        return "DXVK HUD: " + (on ? "켬" : "끔");
    }

    private Button addButton(LinearLayout row, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        row.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    /** Body of a background task / 백그라운드 작업 본체 */
    private interface Task {
        void run() throws Exception;
    }

    /** Progress panel views / 진행 패널 뷰 */
    private LinearLayout taskPanel;
    private TextView taskTitle, taskDetail;
    private android.widget.ProgressBar taskBar;
    /** Current step shown on the panel ("[1/4] rootfs 설치" etc.) / 패널에 보이는 현재 단계("[1/4] rootfs 설치" 등) */
    private volatile String taskStep;
    private static final java.util.regex.Pattern STEP = java.util.regex.Pattern.compile("^\\[(\\d)/(\\d)\\] ");

    /** Run one task on a worker thread, logging errors / 작업 하나를 작업 스레드에서 돌리고 오류를 기록함 */
    private void runTask(String name, Task t) {
        runTask(name, t, null);
    }

    /** Same, then "after" on the UI thread once the task has ended / 같음. 작업이 끝나면 UI 스레드에서 "after" 실행 */
    private void runTask(String name, Task t, Runnable after) {
        if (busy) {
            log("다른 작업 진행 중");
            return;
        }
        busy = true;
        taskStep = TASK_NAMES.containsKey(name) ? TASK_NAMES.get(name) : name;
        log("== " + name);
        new Thread(() -> {
            Thread beat = startHeartbeat(name);
            Thread tick = startPanel();
            try {
                t.run();
            } catch (Throwable e) {
                log("ERROR " + e);
            } finally {
                busy = false;
                beat.interrupt();
                tick.interrupt();
                runOnUiThread(() -> {
                    taskPanel.setVisibility(View.GONE);
                    if (after != null) after.run();
                });
            }
        }, "ezs-" + name).start();
    }

    /** Panel titles for the task names / 작업 이름별 패널 제목 */
    private static final java.util.Map<String, String> TASK_NAMES = new java.util.HashMap<>();
    static {
        TASK_NAMES.put("setup", "설치");
        TASK_NAMES.put("install", "rootfs 설치");
        TASK_NAMES.put("driver", "드라이버 설치");
        TASK_NAMES.put("tools", "시험 도구 설치");
        TASK_NAMES.put("steam-install", "Steam 설치");
        TASK_NAMES.put("prepare", "Steam 준비");
    }

    /**
     * Shows the progress panel and refreshes it every second: step, elapsed time, data received.
     * 진행 패널을 띄우고 1초마다 갱신함: 단계, 경과 시간, 받은 양
     */
    private Thread startPanel() {
        Thread th = new Thread(() -> {
            long t0 = android.os.SystemClock.elapsedRealtime();
            int uid = android.os.Process.myUid();
            long rx0 = android.net.TrafficStats.getUidRxBytes(uid);
            try {
                while (true) {
                    long sec = (android.os.SystemClock.elapsedRealtime() - t0) / 1000;
                    long rx = android.net.TrafficStats.getUidRxBytes(uid);
                    String step = taskStep;
                    String detail = String.format(java.util.Locale.ROOT, "경과 %d:%02d", sec / 60, sec % 60)
                            + (rx < 0 ? "" : String.format(java.util.Locale.ROOT, "  ·  받은 양 %.1f MB", (rx - rx0) / 1e6))
                            + "\n앱을 닫지 마십시오. 자세한 내용은 아래 로그에 있음";
                    // Overall share for "[n/m]" steps (bar keeps moving inside a step)
                    // "[n/m]" 단계의 전체 비율(단계 안에서는 막대가 계속 움직임)
                    java.util.regex.Matcher m = STEP.matcher(step);
                    int pct = m.find() ? (Integer.parseInt(m.group(1)) - 1) * 100 / Integer.parseInt(m.group(2)) : -1;
                    runOnUiThread(() -> {
                        taskTitle.setText(step);
                        taskDetail.setText(pct >= 0 ? "전체 " + pct + "% 완료  ·  " + detail : detail);
                        taskPanel.setVisibility(View.VISIBLE);
                    });
                    Thread.sleep(1000);
                }
            } catch (InterruptedException ignored) {
                // task finished / 작업 끝남
            }
        }, "ezs-panel");
        th.setDaemon(true);
        th.start();
        return th;
    }

    /**
     * Every 30 s while a task runs: one line with elapsed time, data received by this app (the
     * rootfs programs included) and the busiest child process over the interval, so a long step
     * (downloads, apt, Steam's first update) can be told from a stuck one.
     * 작업이 도는 동안 30초마다 한 줄: 경과 시간, 이 앱이 받은 데이터(rootfs 프로그램 포함), 그 사이 가장
     * 바쁜 자식 프로세스. 오래 걸리는 단계(받기, apt, Steam 첫 갱신)와 멈춘 단계를 가리기 위함
     * Why: during a clean install the screen showed only CPU and PRoot numbers moving, with no way
     * to tell progress from a hang (user, 2026-10-18).
     * 이유: 새 설치 중 화면에 CPU와 PRoot 숫자만 오르내려 진행 중인지 멈췄는지 알 수 없었음(사용자,
     * 2026-10-18)
     */
    private Thread startHeartbeat(String name) {
        Thread th = new Thread(() -> {
            long t0 = android.os.SystemClock.elapsedRealtime();
            int uid = android.os.Process.myUid();
            long rx0 = android.net.TrafficStats.getUidRxBytes(uid), rxLast = rx0;
            java.util.Map<Integer, Long> lastTicks = new java.util.HashMap<>();
            try {
                while (true) {
                    Thread.sleep(30000);
                    long rx = android.net.TrafficStats.getUidRxBytes(uid);
                    // Busiest child over the last 30 s (ticks are 1/100 s) / 지난 30초 동안 가장 바쁜 자식(틱은 1/100초)
                    String top = "-";
                    long best = 0;
                    java.util.Map<Integer, Long> ticks = new java.util.HashMap<>();
                    java.util.List<app.ezsteam.runtime.ChildProcessCounter.Proc> ps =
                            app.ezsteam.runtime.ChildProcessCounter.descendants(android.os.Process.myPid());
                    for (app.ezsteam.runtime.ChildProcessCounter.Proc pr : ps) {
                        ticks.put(pr.pid, pr.cpuTicks);
                        Long before = lastTicks.get(pr.pid);
                        long d = pr.cpuTicks - (before == null ? 0 : before);
                        String comm = pr.name.contains(" | ") ? pr.name.substring(0, pr.name.indexOf(" | ")) : pr.name;
                        if (d > best && !comm.contains("libproot")) {
                            best = d;
                            top = comm;
                        }
                    }
                    lastTicks = ticks;
                    long sec = (android.os.SystemClock.elapsedRealtime() - t0) / 1000;
                    log(String.format(java.util.Locale.ROOT, "… %s 진행 중 %d분 %02d초: 받은 양 %s, 최근 30초 %s, 자식 %d개, 가장 바쁜 프로세스 %s",
                            name, sec / 60, sec % 60,
                            rx < 0 ? "-" : String.format(java.util.Locale.ROOT, "%.1f MB", (rx - rx0) / 1e6),
                            rx < 0 ? "-" : String.format(java.util.Locale.ROOT, "%.1f Mbps", (rx - rxLast) * 8 / 30e6),
                            ps.size(), best > 0 ? top + String.format(java.util.Locale.ROOT, " (%.0f%%)", best / 30.0) : "없음 (CPU 사용 없음)"));
                    rxLast = rx;
                }
            } catch (InterruptedException ignored) {
                // task finished / 작업 끝남
            }
        }, "ezs-heartbeat");
        th.setDaemon(true);
        th.start();
        return th;
    }

    /** Thread-safe log append / 스레드 안전 로그 추가 */
    private void log(String line) {
        // "[n/m] ..." step lines become the panel title / "[n/m] ..." 단계 줄은 패널 제목이 됨
        if (STEP.matcher(line).find()) taskStep = line;
        runOnUiThread(() -> {
            logView.append(line + "\n");
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    /** Launcher config for this app (PRoot from nativeLibraryDir) / 이 앱의 실행기 설정(nativeLibraryDir의 PRoot 사용) */
    private GlibcLauncher.Config config() {
        // Bring the rootfs scripts up to this APK's version / rootfs 스크립트를 이 APK 버전으로 맞춤
        try {
            int n = RootfsInstaller.syncScripts(this);
            if (n > 0) log("스크립트 동기화 " + n + "개 (APK → rootfs /opt/ezsteam)");
        } catch (java.io.IOException e) {
            log("스크립트 복사 실패: " + e);
        }
        GlibcLauncher.Config c = new GlibcLauncher.Config();
        c.rootfs = RootfsInstaller.rootfsDir(this);
        c.nativeLibDir = new File(getApplicationInfo().nativeLibraryDir);
        c.dataDir = RootfsInstaller.dataDir(this);
        return c;
    }

    /**
     * Install rootfs: APK asset when present (development), otherwise download from the
     * project's GitHub Release like EZlator's imagefs. Runs only when the button is pressed.
     * rootfs 설치: APK asset이 있으면 그것(개발용), 없으면 EZlator imagefs처럼 이 프로젝트
     * GitHub Release에서 받음. 버튼을 누를 때만 동작함
     */
    private void doInstall() throws Exception {
        // Rough space check: archive + extracted tree / 대략적 공간 확인: 압축 파일 + 푼 트리
        long free = getFilesDir().getUsableSpace();
        if (free < (1L << 30)) {
            log("공간 부족: 남은 공간 " + (free >> 20) + " MB, 1024 MB 이상 필요");
            return;
        }

        RootfsInstaller.TarSource src = RootfsInstaller.assetSource(this);
        String installed = RootfsInstaller.installedVersion(this);
        String version;
        File archive = null;

        if (src != null) {
            version = RootfsInstaller.bundledVersion(this);
            if (version.isEmpty()) version = "asset";
            log("APK 안 rootfs 사용, version=" + version);
        } else {
            String base = BuildConfig.ROOTFS_BASE_URL;
            if (base.isEmpty()) {
                log("rootfs 주소 없음: gradle.properties의 ezsteam.rootfsBaseUrl을 설정하십시오");
                return;
            }
            version = RootfsDownloader.remoteVersion(base);
            log("Release version=" + version + ", 설치본=" + (installed.isEmpty() ? "없음" : installed));
            if (version.equals(installed)) {
                log("이미 같은 버전이 설치되어 있음");
                return;
            }
            archive = new File(getCacheDir(), RootfsDownloader.ARCHIVE);
            RootfsDownloader.download(base, archive, (done, total) ->
                    log("  받음 " + (done >> 20) + (total > 0 ? " / " + (total >> 20) : "") + " MB"));
            log("sha256 확인 완료");
            src = RootfsInstaller.fileSource(archive);
        }

        long t0 = System.currentTimeMillis();
        final long[] lastMb = {0};
        try {
            RootfsInstaller.install(this, src, version, (bytes, name) -> {
                long mb = bytes >> 20;
                if (mb >= lastMb[0] + 50) {
                    lastMb[0] = mb;
                    log("  풀기 " + mb + " MB");
                }
            });
        } finally {
            // The archive is not kept (re-download on the next update)
            // 압축 파일은 보관하지 않음(다음 갱신 때 다시 받음)
            if (archive != null) archive.delete();
        }
        log("설치 완료 " + (System.currentTimeMillis() - t0) / 1000 + "s, version="
                + RootfsInstaller.installedVersion(this));
    }

    private void doSmoke() throws Exception {
        if (RootfsInstaller.installedVersion(this).isEmpty()) {
            log("먼저 rootfs 설치");
            return;
        }
        GlibcLauncher.Config c = config();
        if (!GlibcLauncher.available(c)) {
            log("PRoot 없음: " + c.nativeLibDir + " (APK에 libproot.so가 들어갔는지 확인)");
            return;
        }
        Process p = GlibcLauncher.startScript(c, "/opt/ezsteam/smoke.sh",
                Collections.emptyList(), null, null);
        pipe(p);
        log("exit=" + p.waitFor());
    }

    /**
     * Run /opt/ezsteam/gpucheck.sh (KGSL device open + ioctl, Vulkan if present) and, while it
     * runs, list the app's child processes (PRoot tracer + guest programs).
     * /opt/ezsteam/gpucheck.sh 실행(KGSL 장치 열기·ioctl, 있으면 Vulkan). 실행 중에
     * 앱 자식 프로세스 목록(PRoot 추적 프로세스 + guest 프로그램)도 기록함
     */
    private void doGpuCheck() throws Exception {
        if (RootfsInstaller.installedVersion(this).isEmpty()) {
            log("먼저 rootfs 설치");
            return;
        }
        GlibcLauncher.Config c = config();
        if (!new File(c.rootfs, "opt/ezsteam/gpucheck.sh").isFile()) {
            log("gpucheck.sh 없음: 새 rootfs로 [rootfs 설치]를 다시 하십시오");
            return;
        }
        // Use the Turnip package when installed (vulkaninfo then sees it) / Turnip 묶음이 있으면 사용
        log(DriverInstaller.apply(this, c, TURNIP)
                ? "드라이버: " + TURNIP + " " + DriverInstaller.installedVersion(this, TURNIP)
                : "드라이버: 없음 (KGSL 장치 확인만 함)");
        Process p = GlibcLauncher.startScript(c, "/opt/ezsteam/gpucheck.sh", null, null, null);
        pipe(p);
        log("exit=" + p.waitFor());
    }

    /**
     * Install the optional test tools (Xvfb, xwd) with apt inside the rootfs. Runs as PRoot fake
     * root and uses the device's DNS servers. Only on a button press; downloads from Debian mirrors.
     * 선택 시험 도구(Xvfb, xwd)를 rootfs 안에서 apt로 설치함. PRoot 가짜 root로 실행하고
     * 기기 DNS 서버를 씀. 버튼을 누를 때만 동작하며 Debian 미러에서 받음
     */
    private void doToolsInstall() throws Exception {
        if (RootfsInstaller.installedVersion(this).isEmpty()) {
            log("먼저 rootfs 설치");
            return;
        }
        String dns = RootfsInstaller.writeResolvConf(this);
        log("DNS: " + (dns.isEmpty() ? "알 수 없음 (네트워크 연결 확인)" : dns));
        GlibcLauncher.Config c = config();
        c.rootId = true;
        Process p = GlibcLauncher.startScript(c, "/opt/ezsteam/tools-install.sh", null, null, null);
        pipe(p);
        log("exit=" + p.waitFor());
    }

    /**
     * Drawing test: Xvfb + vkcube through Turnip, frames per second, then show the screenshot.
     * 그리기 시험: Xvfb + Turnip으로 그리는 vkcube, 초당 프레임, 이어서 화면 캡처 표시
     */
    private void doDrawTest() throws Exception {
        if (RootfsInstaller.installedVersion(this).isEmpty()) {
            log("먼저 rootfs 설치");
            return;
        }
        GlibcLauncher.Config c = config();
        if (!DriverInstaller.apply(this, c, TURNIP)) {
            log("먼저 [드라이버 설치]");
            return;
        }
        // Fake root for this test: the X server runs xkbcomp after setgid()/setuid() to its own ids,
        // which Android's app seccomp blocks (PRoot answers ENOSYS, the child exits). PRoot's
        // fake root answers them. Steam later runs without it, against the app's own X server.
        // 이 시험은 가짜 root로 실행: X 서버는 자기 id로 setgid()/setuid()를 부른 뒤 xkbcomp를 실행하는데
        // 안드로이드 앱 seccomp이 막아(PRoot는 ENOSYS) 자식이 종료됨. PRoot 가짜 root는 이를 처리함.
        // Steam은 나중에 가짜 root 없이 앱 X 서버로 실행함
        c.rootId = true;
        File png = new File(c.rootfs, "home/steam/ezs-logs/drawtest.png");
        png.delete();
        Process p = GlibcLauncher.startScript(c, "/opt/ezsteam/drawtest.sh", null, null, null);
        pipe(p);
        log("exit=" + p.waitFor());
        if (png.isFile()) {
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(png.getPath());
            runOnUiThread(() -> {
                shotView.setImageBitmap(bmp);
                shotView.setVisibility(View.VISIBLE);
            });
        }
    }

    /** Driver package name in the Release / Release의 드라이버 묶음 이름 */
    private static final String TURNIP = "turnip";

    /** Download and install the Turnip package from the Release (button only)
     *  Release에서 Turnip 묶음을 받아 설치함(버튼을 누를 때만) */
    private void doDriverInstall() throws Exception {
        String base = BuildConfig.ROOTFS_BASE_URL;
        if (base.isEmpty()) {
            log("Release 주소 없음: gradle.properties의 ezsteam.rootfsBaseUrl을 설정하십시오");
            return;
        }
        String before = DriverInstaller.installedVersion(this, TURNIP);
        String v = DriverInstaller.installFromRelease(this, base, TURNIP, (done, total) ->
                log("  받음 " + (done >> 20) + (total > 0 ? " / " + (total >> 20) : "") + " MB"));
        log(v.equals(before) ? "이미 같은 버전: " + v : "설치 완료: " + TURNIP + " " + v);
    }

    /**
     * Count child processes while a guest program is surely alive (guest "sleep 3"), and report
     * how many /proc entries the app can see, to tell "no children" from "cannot see them".
     * guest 프로그램("sleep 3")이 확실히 살아 있는 동안 자식 프로세스를 셈. 앱이 볼 수 있는
     * /proc 항목 수도 기록해 "자식이 없음"과 "보이지 않음"을 구분함
     */
    private void doProcsLive() throws Exception {
        if (RootfsInstaller.installedVersion(this).isEmpty()) {
            doProcs();
            return;
        }
        Process p = GlibcLauncher.start(config(), "/bin/sleep", java.util.Arrays.asList("3"), null, null);
        Thread.sleep(1000);
        log("guest sleep 실행 중=" + p.isAlive() + ", 앱 pid=" + android.os.Process.myPid());
        String[] all = new File("/proc").list();
        int visible = 0;
        if (all != null) for (String n : all) if (n.matches("\\d+")) visible++;
        log("/proc에서 보이는 프로세스 " + visible + "개");
        doProcs();
        p.waitFor();
    }

    private void doProcs() {
        List<ChildProcessCounter.Proc> ps = ChildProcessCounter.descendants(android.os.Process.myPid());
        log("자식 프로세스 " + ps.size() + "개 (한도: 모든 앱 합계 32)");
        for (ChildProcessCounter.Proc p : ps) {
            log(String.format(java.util.Locale.ROOT, "  %6d <- %6d %7d KB  %s", p.pid, p.ppid, p.rssKb, p.name));
        }
    }

    private void doPrepare() throws Exception {
        log(GlibcLauncher.prepareSteam(config()));
    }

    /** Show PRoot files and a verbose start of /bin/true (PRoot's own messages)
     *  PRoot 파일 상태와 /bin/true 실행 시 PRoot 자체 메시지를 보여 줌 */
    private void doProotInfo() throws Exception {
        GlibcLauncher.Config c = config();
        for (String n : new String[]{GlibcLauncher.PROOT, GlibcLauncher.PROOT_LOADER}) {
            File f = new File(c.nativeLibDir, n);
            log(n + ": " + (f.isFile() ? f.length() + " B" + (f.canExecute() ? ", exec" : ", no exec") : "없음"));
        }
        if (!GlibcLauncher.available(c) || RootfsInstaller.installedVersion(this).isEmpty()) return;
        c.verbose = 1;
        Process p = GlibcLauncher.start(c, "/bin/true", null, null, null);
        pipe(p);
        log("exit=" + p.waitFor());
    }

    /** Copy a process's output into the log until it ends / 프로세스 출력이 끝날 때까지 로그로 옮김 */
    private void pipe(Process p) throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) log(line);
        }
    }
}
