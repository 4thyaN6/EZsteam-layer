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
import java.io.IOException;
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
        // DirectX (optional): bold button under the start row, progress in place, then the
        // "Native" choice with a (?) help (user design, 2026-10-21)
        // DirectX(선택): 시작 줄 아래 굵은 버튼, 그 자리에서 진행 표시, 끝나면 "Native" 선택과 (?) 도움말
        // (사용자 설계, 2026-10-21)
        root.addView(buildDirectXRow());
        // One start button: installs whatever is missing first (with the progress panel), then
        // opens Steam; when everything is there it opens Steam at once (user request, 2026-10-20)
        // 시작 버튼 하나: 빠진 것이 있으면 먼저 설치하고(진행 패널 표시) Steam을 엶. 다 있으면 바로 엶
        // (사용자 요청, 2026-10-20)
        // Top row: start and the admin switch; second row: the player's settings (UI cleanup
        // 2026-10-26: grouped rows, admin sections with titles, test switches folded away)
        // 맨 위 줄: 시작과 관리자 전환. 둘째 줄: 사용자 설정(UI 정리 2026-10-26: 줄 묶기, 제목 있는 관리자 구역,
        // 시험 스위치는 접어 둠)
        Button go = addButton(start, s(R.string.main_start), v -> startSteam());
        go.setTextSize(18);
        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(settings, 1);
        Button size = addButton(settings, sizeLabel(), null);
        size.setOnClickListener(v -> {
            int next = (screenSizeIndex() + 1) % SCREEN_SIZES.length;
            prefs.edit().putInt(KEY_SCREEN_SIZE, next).apply();
            size.setText(sizeLabel());
            log("Steam 화면 크기: " + SCREEN_SIZES[next][0] + "x" + SCREEN_SIZES[next][1]
                    + " (다음 Steam 시작부터)");
        });
        // Big Picture or the normal client window at start / 시작할 때 빅픽처 또는 일반 클라이언트 창
        Button bp = addButton(settings, nv(R.string.main_start_screen,
                bigPicture(this) ? R.string.v_big_picture : R.string.v_normal), null);
        bp.setOnClickListener(v -> {
            boolean on = !bigPicture(this);
            prefs.edit().putBoolean(KEY_BIG_PICTURE, on).apply();
            bp.setText(nv(R.string.main_start_screen, on ? R.string.v_big_picture : R.string.v_normal));
            log("Steam 시작 화면: " + (on ? "빅픽처" : "일반") + " (다음 Steam 시작부터)");
        });
        // LSFG frame generation for games: off / 2x / 3x. Usable only with the user's own Lossless
        // Scaling (Steam app 993090) installed; EZ Proton reads the choice at every game start
        // 게임용 LSFG 프레임 생성: 끔 / 2배 / 3배. 사용자 자신의 Lossless Scaling(Steam 앱 993090)이 설치돼
        // 있어야 쓸 수 있음. EZ Proton이 게임을 시작할 때마다 읽음
        Button lsfg = addButton(settings, lsfgLabel(lsfgMode(this)), null);
        lsfg.setOnClickListener(v -> {
            int n = lsfgMode(this) == 0 ? 2 : lsfgMode(this) == 2 ? 3 : 0;
            String err = setLsfgMode(this, n);
            if (err != null) log("LSFG 설정 실패: " + err);
            lsfg.setText(lsfgLabel(n));
            log("LSFG: " + (n == 0 ? "끔" : n + "배") + " (다음 게임 시작부터, Lossless Scaling 설치 필요)");
        });
        // A game still running when another one starts: tell (default) or end it first
        // 다른 게임을 시작할 때 앞 게임이 아직 실행 중이면: 알림(기본) 또는 먼저 끝냄
        Button prev = addButton(settings, nv(R.string.main_prev_game,
                prevGameEnd(this) ? R.string.v_auto_end : R.string.v_notify), null);
        prev.setOnClickListener(v -> {
            boolean end = !prevGameEnd(this);
            prefs.edit().putBoolean(KEY_PREV_GAME_END, end).apply();
            prev.setText(nv(R.string.main_prev_game, end ? R.string.v_auto_end : R.string.v_notify));
            log("앞 게임이 남아 있을 때: " + (end ? "자동 종료" : "알림만"));
        });
        Button auto = addButton(settings, nv(R.string.main_autostart, onOffRes(prefs.getBoolean(KEY_AUTOSTART, true))), null);
        auto.setOnClickListener(v -> {
            boolean on = !prefs.getBoolean(KEY_AUTOSTART, true);
            prefs.edit().putBoolean(KEY_AUTOSTART, on).apply();
            auto.setText(nv(R.string.main_autostart, onOffRes(on)));
        });
        // Shared EZlator Steam library on/off (startup got slower with it, user report; for
        // comparing and as a way back)
        // 공유 EZlator Steam 라이브러리 켬/끔(켠 뒤 시작이 느려졌다는 사용자 보고. 비교용이자 되돌리는 방법)
        Button shared = addButton(settings, nv(R.string.main_shared_lib, onOffRes(prefs.getBoolean(KEY_SHARED_LIB, true))), null);
        shared.setOnClickListener(v -> {
            boolean on = !prefs.getBoolean(KEY_SHARED_LIB, true);
            prefs.edit().putBoolean(KEY_SHARED_LIB, on).apply();
            shared.setText(nv(R.string.main_shared_lib, onOffRes(on)));
            log("공유 Steam 라이브러리 " + (on ? "켬" : "끔") + " (다음 Steam 시작부터)");
        });
        adminBox = new LinearLayout(this);
        adminBox.setOrientation(LinearLayout.VERTICAL);
        Button admin = addButton(start, s(R.string.main_admin), null);
        admin.setOnClickListener(v -> setAdminShown(adminBox.getVisibility() != View.VISIBLE));
        root.addView(adminBox);

        // ---- Setup / 설치 ----
        // [전체 준비만] installs everything without starting Steam (kept for tests): rootfs, driver,
        // tools, Steam, skipping what is already there
        // [전체 준비만]은 Steam을 시작하지 않고 모두 설치(시험용으로 남김): rootfs, 드라이버, 도구, Steam. 이미 있는 것은 건너뜀
        Flow f = new Flow(section(adminBox, R.string.sec_setup), 5);
        f.add(s(R.string.btn_setup_all), v -> runTask("setup", this::doSetupAll));
        f.add(s(R.string.btn_rootfs), v -> runTask("install", this::doInstall));
        f.add(s(R.string.btn_driver), v -> runTask("driver", this::doDriverInstall));
        f.add(s(R.string.btn_tools), v -> runTask("tools", this::doToolsInstall));
        f.add(s(R.string.btn_steam_install), v -> runTask("steam-install", this::doSteamInstall));

        // ---- Checks / 확인 ----
        f = new Flow(section(adminBox, R.string.sec_checks), 4);
        f.add(s(R.string.btn_smoke), v -> runTask("smoke", this::doSmoke));
        f.add(s(R.string.btn_procs), v -> runTask("procs", this::doProcsLive));
        f.add(s(R.string.btn_prepare), v -> runTask("prepare", this::doPrepare));
        f.add(s(R.string.btn_proot), v -> runTask("proot", this::doProotInfo));
        f.add(s(R.string.btn_gpu), v -> runTask("gpu", this::doGpuCheck));
        f.add(s(R.string.btn_draw), v -> runTask("draw", this::doDrawTest));
        f.add(s(R.string.btn_speed), v -> runTask("speed", this::doSpeedTest));

        // ---- Screen tests: live display per X screen size (16:10 like the device, then the
        // device's own size) and Steam at a fixed size ----
        // ---- 화면 시험: X 화면 크기별 실시간 화면(기기와 같은 16:10, 마지막은 기기 자체 해상도)과 크기를 고정한 Steam ----
        f = new Flow(section(adminBox, R.string.sec_screen), 4);
        f.add(s(R.string.btn_screen, "1280x800"), v -> openDisplay(1280, 800));
        f.add(s(R.string.btn_screen, "1920x1200"), v -> openDisplay(1920, 1200));
        f.add(s(R.string.btn_screen, "2560x1600"), v -> openDisplay(2560, 1600));
        int[] nat = nativeSize();
        f.add(s(R.string.btn_screen, nat[0] + "x" + nat[1]), v -> openDisplay(nat[0], nat[1]));
        f.add(s(R.string.btn_steam_at, "1280x800"), v -> openSteam(1280, 800));
        f.add(s(R.string.btn_steam_at, "1920x1200"), v -> openSteam(1920, 1200));

        // ---- Steam and game settings / Steam·게임 설정 ----
        f = new Flow(section(adminBox, R.string.sec_steam), 4);
        Button cef = f.add(nv(R.string.btn_cef, onOffRes(cefSlim(this))), null);
        cef.setOnClickListener(v -> {
            boolean on = !cefSlim(this);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_CEF_SLIM, on).apply();
            cef.setText(nv(R.string.btn_cef, onOffRes(on)));
            log("CEF 절감 " + (on ? "켬: " + CEF_SLIM_FLAGS : "끔") + " (다음 Steam 실행부터)");
        });
        // Steam UI drawn with the GPU (off → Chromium's choice → ANGLE on Vulkan)
        // Steam 화면을 GPU로 그림(끔 → Chromium 선택 → Vulkan 위 ANGLE)
        Button gpu = f.add(s(R.string.fmt_name_value, s(R.string.btn_steam_gpu),
                getResources().getStringArray(R.array.steam_gpu_modes)[steamGpu(this)]), null);
        gpu.setOnClickListener(v -> {
            int m = (steamGpu(this) + 1) % STEAM_GPU_MODES.length;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_STEAM_GPU, m).apply();
            gpu.setText(s(R.string.fmt_name_value, s(R.string.btn_steam_gpu),
                    getResources().getStringArray(R.array.steam_gpu_modes)[m]));
            log("Steam 화면 GPU(시험) " + STEAM_GPU_MODES[m][0]
                    + (m == 0 ? "" : ": " + STEAM_GPU_MODES[m][1]) + " (다음 Steam 실행부터)");
        });
        // Fewer controller (HID) discovery rounds in the Steam client. Big Picture printed "Using udev
        // for HIDAPI joystick device discovery" 12,000-21,000 times a minute (normal window about
        // 1,700) while PRoot sat near 70 % (normal window 10-20 %), 2026-10-23. On by default since
        // 2026-10-25: with the udev stand-ins (steam-run.sh) Big Picture's HID init stopped repeating
        // (idle PRoot about 64 % -> 6 %) and the pad worked in Big Picture (user check). The Steam
        // client only, games keep the defaults. Off = the client's own defaults.
        // Steam 클라이언트의 컨트롤러(HID) 찾기 횟수 줄이기. 빅픽처는 "Using udev for HIDAPI joystick device
        // discovery"를 분당 12,000~21,000번(일반 창 약 1,700번) 남겼고 그동안 PRoot가 약 70%(일반 창 10~20%)였음
        // (2026-10-23). 2026-10-25부터 기본 켬: udev 대역(steam-run.sh)으로 빅픽처 HID 초기화 반복이 멈췄고
        // (대기 PRoot 약 64% -> 6%) 빅픽처에서 패드가 동작함(사용자 확인). Steam 클라이언트에만 적용하고 게임은
        // 기본값 그대로. 끄면 클라이언트 원래 기본값
        Button hid = f.add(nv(R.string.btn_hid, steamHidLow(this) ? R.string.v_reduced : R.string.v_default), null);
        hid.setOnClickListener(v -> {
            boolean on = !steamHidLow(this);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_STEAM_HID_LOW, on).apply();
            hid.setText(nv(R.string.btn_hid, on ? R.string.v_reduced : R.string.v_default));
            log("Steam HID 찾기: " + (on ? "줄임" : "기본") + " (다음 Steam 시작부터)");
        });
        // Game frames straight to the app compositor (present layer, zero copy). On by default
        // (the device check passed: path 1-b); the file "present-off" turns it off, then the X11
        // software output is used as before. EZ Proton reads it at every game start.
        // 게임 프레임을 앱 컴포지터로 바로 보냄(present 레이어, 0복사). 기본 켬(기기 확인 통과: 1-b 경로).
        // "present-off" 파일이 있으면 끄고 지금까지의 X11 소프트웨어 출력을 씀. EZ Proton이 게임 시작마다 읽음
        File presentOff = new File(dxNativeFile().getParentFile(), "present-off");
        Button direct = f.add(nv(R.string.btn_direct, onOffRes(!presentOff.isFile())), null);
        direct.setOnClickListener(v -> {
            boolean on = presentOff.isFile();
            try {
                if (on) {
                    if (!presentOff.delete()) throw new IOException("지울 수 없음");
                } else {
                    File d = presentOff.getParentFile();
                    if (d != null && !d.isDirectory() && !d.mkdirs()) throw new IOException("폴더 없음");
                    if (!presentOff.createNewFile() && !presentOff.isFile()) throw new IOException("만들 수 없음");
                }
                direct.setText(nv(R.string.btn_direct, onOffRes(on)));
                log("게임 화면 직접 전달: " + (on ? "켬(0복사)" : "끔(기존 X11 출력)") + " (다음 게임 시작부터)");
            } catch (IOException e) {
                log("게임 화면 직접 전달 설정 실패: " + e.getMessage());
            }
        });
        // How games hand finished frames to the X server (X11 path), and the DXVK overlay (both
        // read when Steam starts) / 게임이 완성된 화면을 X 서버에 넘기는 방식(X11 경로)과 DXVK 표시줄(둘 다 Steam
        // 시작 때 읽음)
        Button wsi = f.add(wsiLabel(wsiMode(this)), null);
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
        Button hud = f.add(nv(R.string.btn_dxvk_hud, onOffRes(dxvkHud(this))), null);
        hud.setOnClickListener(v -> {
            boolean on = !dxvkHud(this);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_DXVK_HUD, on).apply();
            hud.setText(nv(R.string.btn_dxvk_hud, onOffRes(on)));
            log("DXVK HUD " + (on ? "켬: DXVK_HUD=" + DXVK_HUD_VALUE : "끔") + " (다음 Steam 실행부터)");
        });
        // Diagnosis: the client's file I/O summary and PRoot's stop counts, once a minute
        // 진단: 클라이언트 파일 입출력 요약과 PRoot 정지 집계를 1분마다 기록
        Button io = f.add(nv(R.string.btn_iostat, onOffRes(ioStat(this))), null);
        io.setOnClickListener(v -> {
            boolean on = !ioStat(this);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_IOSTAT, on).apply();
            io.setText(nv(R.string.btn_iostat, onOffRes(on)));
            log("IO 통계 " + (on ? "켬(앱 로그 ezs-io:, PROOT_STAT 줄, 1분마다)" : "끔") + " (다음 Steam 실행부터)");
        });

        // ---- Test switches, folded (user decision 2026-10-26: kept, out of the way). Each one
        // had no effect or a risk on the test device: FEX TSO off froze a game, big cores only
        // left 2 CPUs on a 2+6 chip, AHB compression is not done by this gralloc, fast sync and
        // hidraw off showed no clear gain. Files / settings EZ Proton or the app read at start.
        // ---- 시험 스위치, 접어 둠(사용자 결정 2026-10-26: 남기되 눈에 띄지 않게). 시험 기기에서 효과가 없거나
        // 위험했음: FEX TSO 끔은 게임이 멈춤, 큰 코어만은 2+6 칩에서 CPU 2개만 남김, AHB 압축은 이 gralloc이 하지
        // 않음, 빠른 동기화·hidraw 끔은 뚜렷한 이득 없음. EZ Proton이나 앱이 시작 때 읽는 파일·설정 ----
        LinearLayout testBox = new LinearLayout(this);
        testBox.setOrientation(LinearLayout.VERTICAL);
        testBox.setVisibility(prefs.getBoolean(KEY_TESTS_SHOWN, false) ? View.VISIBLE : View.GONE);
        Button tests = new Button(this);
        tests.setAllCaps(false);
        tests.setText(s(testBox.getVisibility() == View.VISIBLE ? R.string.sec_tests_open : R.string.sec_tests_closed));
        tests.setOnClickListener(v -> {
            boolean shown = testBox.getVisibility() != View.VISIBLE;
            testBox.setVisibility(shown ? View.VISIBLE : View.GONE);
            prefs.edit().putBoolean(KEY_TESTS_SHOWN, shown).apply();
            tests.setText(s(shown ? R.string.sec_tests_open : R.string.sec_tests_closed));
        });
        adminBox.addView(tests, new LinearLayout.LayoutParams(-2, -2));
        adminBox.addView(testBox);
        f = new Flow(testBox, 5);
        fileSwitch(f, s(R.string.test_fsync), "fsync-shim", R.string.v_on, R.string.v_off);
        fileSwitch(f, s(R.string.test_tso), "fex-tso-off", R.string.v_off, R.string.v_default);
        fileSwitch(f, s(R.string.test_cpu), "cpu-big", R.string.v_big_cores, R.string.v_default);
        fileSwitch(f, s(R.string.test_hidraw), "hidraw-off", R.string.v_off, R.string.v_default);
        // AHB compression test: GPU-only game buffers in the layout the start-up check found
        // (Qualcomm compressed). Off by default; this device's gralloc keeps them linear.
        // AHB 압축 시험: 시작 확인이 찾은 배치(Qualcomm 압축)로 GPU 전용 게임 버퍼. 기본 끔. 이 기기 gralloc은 linear로 둠
        Button ahb = f.add(nv(R.string.test_ahb, onOffRes(ahbCompress(this))), null);
        ahb.setOnClickListener(v -> {
            boolean on = !ahbCompress(this);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_AHB_COMPRESS, on).apply();
            ahb.setText(nv(R.string.test_ahb, onOffRes(on)));
            log("AHB 압축(시험): " + (on ? "켬" : "끔") + " (다음 Steam 시작부터)");
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
        // Buttons scroll on their own above the log: with the admin sections open they were taller
        // than the screen and the last rows were cut off (user report 2026-10-26). Admin open: the
        // two halves share the screen; admin closed: the buttons take what they need
        // 버튼은 로그 위에서 따로 스크롤함: 관리자 구역을 열면 화면보다 길어 마지막 줄이 잘렸음(사용자 제보
        // 2026-10-26). 관리자 열림: 두 부분이 화면을 나눠 씀, 관리자 닫힘: 버튼은 필요한 만큼만 차지함
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(pad, pad, pad, pad);
        topScroll = new ScrollView(this);
        topScroll.addView(root);
        outer.addView(topScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, -2));
        outer.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(outer);

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

    /** Scroll view around the buttons / 버튼을 감싼 스크롤 뷰 */
    private ScrollView topScroll;

    private void setAdminShown(boolean shown) {
        adminBox.setVisibility(shown ? View.VISIBLE : View.GONE);
        if (topScroll != null) {
            topScroll.setLayoutParams(shown
                    ? new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                    : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, -2));
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ADMIN, shown).apply();
    }

    /**
     * X screen sizes for the Steam session; the app scales the picture to the display (FSR 1
     * among the scale modes). 1024x640 and 960x600 were tried and taken out again: on a
     * 3040-pixel-wide panel they are scaled up more than 3 times and text was blurred even with
     * FSR 1 (user report 2026-10-24; FSR 1's strongest mode is 2x). Valve's Proton has no
     * fullscreen scaling hack any more (removed after Proton 8 in favour of gamescope), so a
     * lower size per game would need a Wine virtual desktop plus cropping (not done).
     * Steam 세션의 X 화면 크기. 앱이 화면 크기로 확대함(확대 방식에 FSR 1 있음). 1024x640, 960x600은
     * 시험 뒤 다시 뺌: 가로 3040화소 화면에서 3배 넘게 확대되어 FSR 1로도 글씨가 번졌음(사용자 제보
     * 2026-10-24. FSR 1의 가장 큰 배율 모드가 2배). Valve Proton에는 전체 화면 확대 기능이 더는 없음
     * (Proton 8 이후 gamescope로 대체되며 제거). 게임별로 낮추려면 Wine 가상 데스크톱 + 잘라 보이기가
     * 필요함(안 함)
     */
    static final int[][] SCREEN_SIZES = {{1280, 800}, {1920, 1200}};
    /** Index into SCREEN_SIZES (new key, so the old four-size index is not misread)
     *  SCREEN_SIZES 번호(새 키. 예전 네 가지 목록의 번호를 잘못 읽지 않게 함) */
    static final String KEY_SCREEN_SIZE = "steam.size2";

    /** Chosen size; older settings map to 1920x1200 or 1280x800 / 고른 크기. 예전 설정은 1920x1200 또는 1280x800으로 맞춤 */
    private int screenSizeIndex() {
        android.content.SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean wasBig = p.getInt("steam.size", -1) == 3 || p.getBoolean(KEY_BIG_SCREEN, false);
        int i = p.getInt(KEY_SCREEN_SIZE, wasBig ? 1 : 0);
        return i >= 0 && i < SCREEN_SIZES.length ? i : 0;
    }

    private int[] steamSize() {
        return SCREEN_SIZES[screenSizeIndex()];
    }

    private String sizeLabel() {
        int[] sz = steamSize();
        return s(R.string.btn_screen, sz[0] + "x" + sz[1]);
    }

    /** Shared EZlator Steam library wanted (DisplayActivity) / 공유 EZlator Steam 라이브러리 사용 여부 */
    static boolean sharedLibrary(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SHARED_LIB, true);
    }

    /** Start Steam in Big Picture. Off by default for now: it showed only a black screen on the
     *  device (2026-10-21), cause not found yet / Steam을 빅픽처로 시작. 지금은 기본 끔: 기기에서 검은
     *  화면만 나왔고(2026-10-21) 원인을 아직 찾지 못함 */
    static final String KEY_BIG_PICTURE = "steam.bigpicture";

    /** End a still-running game when another one starts (false = only tell)
     *  다른 게임이 시작될 때 아직 실행 중인 게임을 끝냄(false = 알림만) */
    static final String KEY_PREV_GAME_END = "game.prevEnd";

    static boolean prevGameEnd(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_PREV_GAME_END, false);
    }


    static final String KEY_STEAM_HID_LOW = "steam.hidLow";

    static boolean steamHidLow(android.content.Context ctx) {
        // Default on (see the switch above) / 기본 켬(위 스위치 참고)
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_STEAM_HID_LOW, true);
    }

    static boolean bigPicture(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_BIG_PICTURE, false);
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
        int m = ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_STEAM_GPU, 2);
        return m >= 0 && m < STEAM_GPU_MODES.length ? m : 0;
    }

    /** Client file I/O statistics (native ezs_iostat, EZS_IOSTAT=1) and PRoot syscall statistics
     *  (PROOT_SYSSTAT=60), off by default
     *  클라이언트 파일 입출력 통계(네이티브 ezs_iostat, EZS_IOSTAT=1)와 PRoot 시스템 호출 통계
     *  (PROOT_SYSSTAT=60), 기본 끔 */
    static final String KEY_IOSTAT = "iostat";
    static final String KEY_AHB_COMPRESS = "present.ahbCompress";

    static boolean ahbCompress(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_AHB_COMPRESS, false);
    }

    /**
     * Button for a switch kept as a file in ~/.local/share/ezsteam (EZ Proton reads it at every game
     * start): present = on.
     * ~/.local/share/ezsteam 안 파일로 두는 스위치 버튼(EZ Proton이 게임 시작마다 읽음): 있으면 켬
     */
    private void fileSwitch(Flow row, String name, String file, int onRes, int offRes) {
        File f = new File(dxNativeFile().getParentFile(), file);
        Button b = row.add(s(R.string.fmt_name_value, name, s(f.isFile() ? onRes : offRes)), null);
        b.setOnClickListener(v -> {
            boolean on = !f.isFile();
            try {
                if (on) {
                    File d = f.getParentFile();
                    if (d != null && !d.isDirectory() && !d.mkdirs()) throw new IOException("폴더 없음");
                    if (!f.createNewFile() && !f.isFile()) throw new IOException("만들 수 없음");
                } else if (!f.delete()) {
                    throw new IOException("지울 수 없음");
                }
                b.setText(s(R.string.fmt_name_value, name, s(on ? onRes : offRes)));
                log(name + ": " + s(on ? onRes : offRes) + " (다음 게임 시작부터)");
            } catch (IOException e) {
                log(name + " 설정 실패: " + e.getMessage());
            }
        });
    }

    static boolean ioStat(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_IOSTAT, false);
    }

    /** CEF process saving on (default) or off / CEF 프로세스 절감 켬(기본) 또는 끔 */
    static boolean cefSlim(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_CEF_SLIM, true);
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

    private String wsiLabel(String key) {
        return s(R.string.fmt_name_value, s(R.string.btn_wsi), getResources().getStringArray(R.array.wsi_modes)[wsiIndex(key)]);
    }

    /** DXVK overlay on or off (default off) / DXVK 표시줄 켬 또는 끔(기본 끔) */
    static boolean dxvkHud(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DXVK_HUD, false);
    }


    private Button addButton(LinearLayout row, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        row.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    /** String resource, with format arguments when given / 문자열 리소스. 인자가 있으면 형식 적용 */
    private String s(int id, Object... a) {
        return a.length == 0 ? getString(id) : getString(id, a);
    }

    /** "name: value" from two string resources / 문자열 리소스 두 개로 "이름: 값" */
    private String nv(int name, int value) {
        return s(R.string.fmt_name_value, s(name), s(value));
    }

    private static int onOffRes(boolean on) {
        return on ? R.string.v_on : R.string.v_off;
    }

    static final String KEY_TESTS_SHOWN = "admin.tests";

    /** Titled admin section; returns the box its rows go into / 제목이 있는 관리자 구역. 줄이 들어갈 상자를 돌려줌 */
    private LinearLayout section(LinearLayout parent, int title) {
        TextView t = new TextView(this);
        t.setText(s(title));
        t.setTextSize(13);
        t.setAlpha(0.7f);
        int pad = (int) (6 * getResources().getDisplayMetrics().density);
        t.setPadding(pad, pad * 2, pad, pad / 2);
        parent.addView(t);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        parent.addView(box);
        return box;
    }

    /** Buttons in rows of at most max, a new row when one is full / 한 줄에 최대 max개, 차면 새 줄 */
    private final class Flow {
        private final LinearLayout box;
        private final int max;
        private LinearLayout row;
        private int n;

        Flow(LinearLayout box, int max) {
            this.box = box;
            this.max = max;
        }

        Button add(String text, View.OnClickListener l) {
            if (row == null || n == max) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                box.addView(row);
                n = 0;
            }
            n++;
            return addButton(row, text, l);
        }
    }

    /** Body of a background task / 백그라운드 작업 본체 */
    private interface Task {
        void run() throws Exception;
    }

    // ---- DirectX (optional) / DirectX(선택) ----

    static final String KEY_DX_NATIVE = "dx.native";
    private Button dxButton;
    private android.widget.ProgressBar dxBar;
    private TextView dxStatus;
    private android.widget.CheckBox dxNative;
    private android.widget.LinearLayout dxNativeBox;
    private volatile boolean dxRunning;

    /** DirectX files really installed in the base prefix / 기본 prefix에 DirectX가 실제로 설치됨 */
    private boolean dxInstalled() {
        return new File(RootfsInstaller.rootfsDir(this), "home/steam/.local/share/ezsteam/base-prefix/ezs-directx").isFile();
    }

    /** The file compat/ezproton reads for the Native choice / compat/ezproton이 Native 선택으로 읽는 파일 */
    private File dxNativeFile() {
        return new File(RootfsInstaller.rootfsDir(this), "home/steam/.local/share/ezsteam/dx-native");
    }

    private View buildDirectXRow() {
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (4 * dp), 0, (int) (4 * dp));
        dxButton = new Button(this);
        dxButton.setText(s(R.string.dx_install));
        dxButton.setTypeface(Typeface.DEFAULT_BOLD);
        dxButton.setOnClickListener(v -> installDirectX());
        row.addView(dxButton);
        // Progress next to the button (no separate screen) / 버튼 옆 진행 표시(별도 화면 없음)
        dxBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        dxBar.setIndeterminate(true);
        dxBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams((int) (160 * dp), -2);
        barLp.leftMargin = (int) (8 * dp);
        row.addView(dxBar, barLp);
        dxStatus = new TextView(this);
        dxStatus.setPadding((int) (8 * dp), 0, 0, 0);
        row.addView(dxStatus);
        // After installing: Native choice + (?) / 설치 후: Native 선택 + (?)
        dxNativeBox = new LinearLayout(this);
        dxNativeBox.setOrientation(LinearLayout.HORIZONTAL);
        dxNativeBox.setGravity(android.view.Gravity.CENTER_VERTICAL);
        dxNative = new android.widget.CheckBox(this);
        dxNative.setText(s(R.string.dx_native));
        dxNative.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DX_NATIVE, false));
        dxNative.setOnCheckedChangeListener((b, on) -> setDxNative(on));
        dxNativeBox.addView(dxNative);
        TextView help = new TextView(this);
        help.setText("?");
        help.setTypeface(Typeface.DEFAULT_BOLD);
        help.setGravity(android.view.Gravity.CENTER);
        int hs = (int) (24 * dp);
        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setStroke((int) (1.5f * dp), 0xFF888888);
        help.setBackground(circle);
        String tip = s(R.string.dx_native_help);
        // Hover or long press shows the short help; a tap shows it in a dialog
        // 커서를 대거나 길게 누르면 짧은 도움말, 누르면 대화 상자로 보여 줌
        help.setTooltipText(tip);
        help.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle(s(R.string.dx_native)).setMessage(tip).setPositiveButton(s(R.string.btn_ok), null).show());
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(hs, hs);
        hp.leftMargin = (int) (4 * dp);
        dxNativeBox.addView(help, hp);
        LinearLayout.LayoutParams nb = new LinearLayout.LayoutParams(-2, -2);
        nb.leftMargin = (int) (12 * dp);
        row.addView(dxNativeBox, nb);
        refreshDirectXRow();
        return row;
    }

    private void refreshDirectXRow() {
        boolean done = dxInstalled();
        dxButton.setEnabled(!dxRunning && !done);
        dxButton.setText(s(done ? R.string.dx_installed : R.string.dx_install));
        dxNativeBox.setVisibility(done ? View.VISIBLE : View.GONE);
        if (done) setDxNative(dxNative.isChecked());
    }

    /** Save the choice and write or remove the file ezproton reads / 선택을 저장하고 ezproton이 읽는 파일을 쓰거나 지움 */
    /** LSFG choice file read by EZ Proton (0 = off) / EZ Proton이 읽는 LSFG 선택 파일(0 = 끔) */
    private static File lsfgFile(android.content.Context ctx) {
        return new File(RootfsInstaller.rootfsDir(ctx), "home/steam/.local/share/ezsteam/lsfg");
    }

    /** LSFG choice, 0 / 2 / 3 (also the HOME menu, DisplayActivity) / LSFG 선택 0 / 2 / 3(HOME 메뉴도 씀) */
    static int lsfgMode(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_LSFG, 0);
    }

    private static final String KEY_LSFG = "game.lsfg";

    private String lsfgLabel(int n) {
        String v = n == 0 ? s(R.string.v_off) : s(R.string.v_times, n);
        // Chosen but nothing to use it with: the button says so instead of a multiplier (ezproton
        // then starts the game without it; user request 2026-10-26)
        // 골랐지만 쓸 것이 없음: 단추에 배수 대신 그렇게 표시함(그때 ezproton은 LSFG 없이 게임을 시작함. 사용자
        // 요청 2026-10-26)
        if (n != 0 && !losslessInstalled(this)) v = s(R.string.v_no_lossless);
        return s(R.string.fmt_name_value, "LSFG", v);
    }

    /**
     * The user's own Lossless.dll (Lossless Scaling, installed by their Steam client) in one of the
     * two libraries ezproton looks in: the rootfs Steam library or the shared EZlator library.
     * 사용자 자신의 Lossless.dll(Lossless Scaling, 사용자의 Steam 클라이언트가 설치)이 ezproton이 찾는 두 라이브러리
     * 중 하나에 있는지: rootfs Steam 라이브러리 또는 공유 EZlator 라이브러리
     */
    static boolean losslessInstalled(android.content.Context ctx) {
        String rel = "steamapps/common/Lossless Scaling/Lossless.dll";
        if (new File(RootfsInstaller.rootfsDir(ctx), "home/steam/.local/share/Steam/" + rel).isFile()) return true;
        File shared = sharedLibrary(ctx) ? app.ezsteam.runtime.SharedStorage.steamLibrary(ctx) : null;
        return shared != null && new File(shared, rel).isFile();
    }

    /** Save the LSFG choice and the file EZ Proton reads; returns an error text or null
     *  LSFG 선택과 EZ Proton이 읽는 파일을 저장. 오류 글 또는 null을 돌려줌 */
    static String setLsfgMode(android.content.Context ctx, int n) {
        ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_LSFG, n).apply();
        File f = lsfgFile(ctx);
        try {
            if (n >= 2) {
                File d = f.getParentFile();
                if (d != null && !d.isDirectory() && !d.mkdirs()) throw new IOException("폴더 없음");
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
                    o.write(String.valueOf(n).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                }
            } else if (f.isFile() && !f.delete()) {
                throw new IOException("지울 수 없음");
            }
        } catch (IOException e) {
            return e.getMessage();
        }
        return null;
    }

    private void setDxNative(boolean on) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_DX_NATIVE, on).apply();
        File f = dxNativeFile();
        try {
            if (on) {
                File d = f.getParentFile();
                if (d != null && !d.isDirectory() && !d.mkdirs()) throw new IOException("폴더 없음");
                if (!f.isFile() && !f.createNewFile()) throw new IOException("만들 수 없음");
            } else if (f.isFile() && !f.delete()) {
                throw new IOException("지울 수 없음");
            }
        } catch (IOException e) {
            log("DirectX Native 설정 실패: " + e.getMessage());
        }
    }

    /**
     * [DirectX 설치]: runs ezs-baseprefix.sh directx in its own PRoot as the Steam user (Steam does
     * not need to be open). Needs Proton (after the first Steam sign-in) and Steamworks Shared
     * (comes with a game download). Progress stays next to the button.
     * [DirectX 설치]: Steam 사용자로 별도 PRoot에서 ezs-baseprefix.sh directx를 돌림(Steam이 열려 있을
     * 필요 없음). Proton(첫 Steam 로그인 뒤)과 Steamworks Shared(게임 다운로드와 함께 옴)가 필요함.
     * 진행 표시는 버튼 옆에 둠
     */
    private void installDirectX() {
        if (dxRunning) return;
        if (!notReady().isEmpty()) {
            dxStatus.setText(s(R.string.dx_need_setup));
            return;
        }
        dxRunning = true;
        dxButton.setEnabled(false);
        dxBar.setVisibility(View.VISIBLE);
        dxStatus.setText(s(R.string.dx_preparing));
        long t0 = android.os.SystemClock.elapsedRealtime();
        final String[] stage = {s(R.string.dx_preparing)};
        // Elapsed time every second / 1초마다 경과 시간
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!dxRunning) return;
                long sec = (android.os.SystemClock.elapsedRealtime() - t0) / 1000;
                dxStatus.setText(stage[0] + String.format(java.util.Locale.ROOT, " · %d:%02d", sec / 60, sec % 60));
                dxStatus.postDelayed(this, 1000);
            }
        };
        dxStatus.post(tick);
        new Thread(() -> {
            String result = null;
            try {
                GlibcLauncher.Config c = config();
                c.changeId = "1000:1000";
                File sharedLib = sharedLibrary(this) ? app.ezsteam.runtime.SharedStorage.steamLibrary(this) : null;
                if (sharedLib != null) {
                    c.binds.add(new String[]{sharedLib.getAbsolutePath(), app.ezsteam.runtime.SharedStorage.GUEST_LIBRARY});
                    c.env.put("EZS_SHARED_LIBRARY", app.ezsteam.runtime.SharedStorage.GUEST_LIBRARY);
                }
                log("== DirectX 설치");
                Process p = GlibcLauncher.startScript(c, "/opt/ezsteam/ezs-baseprefix.sh",
                        java.util.Collections.singletonList("directx"), null, null);
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        if (!l.contains("ezproton: base ")) continue;
                        log(l);
                        String m = l.substring(l.indexOf("ezproton: base ") + 15);
                        if (m.startsWith("build start") || m.startsWith("rebuild")) stage[0] = s(R.string.dx_stage_base);
                        else if (m.startsWith("redist start") && m.toLowerCase(java.util.Locale.ROOT).contains("directx")) stage[0] = s(R.string.dx_stage_directx);
                        else if (m.startsWith("redist start")) stage[0] = s(R.string.dx_stage_vc);
                        else if (m.startsWith("backfill start")) {
                            int a = m.indexOf('('), b = m.indexOf(')');
                            stage[0] = s(R.string.dx_stage_game, a >= 0 && b > a ? m.substring(a + 1, b) : "");
                        } else if (m.startsWith("dx: proton not installed") || m.startsWith("dx: Proton not installed")) {
                            result = s(R.string.dx_need_proton);
                        } else if (m.startsWith("dx: no DirectX installer")) {
                            result = s(R.string.dx_no_installer);
                        } else if (m.startsWith("redist failed") || m.startsWith("late retry") || m.startsWith("error")) {
                            result = s(R.string.dx_failed_log);
                        }
                    }
                }
                int rc = p.waitFor();
                log("DirectX 설치 끝 exit=" + rc);
            } catch (IOException | InterruptedException | RuntimeException e) {
                result = s(R.string.dx_failed, e.getMessage());
                log("DirectX 설치 실패: " + e);
            }
            final String msg = result;
            runOnUiThread(() -> {
                dxRunning = false;
                dxBar.setVisibility(View.GONE);
                boolean done = dxInstalled();
                dxStatus.setText(done ? s(R.string.dx_done) : (msg != null ? msg : s(R.string.dx_not_installed)));
                refreshDirectXRow();
            });
        }, "ezs-directx").start();
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
        taskStep = TASK_NAMES.containsKey(name) ? s(TASK_NAMES.get(name)) : name;
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
    private static final java.util.Map<String, Integer> TASK_NAMES = new java.util.HashMap<>();
    static {
        TASK_NAMES.put("setup", R.string.task_setup);
        TASK_NAMES.put("install", R.string.btn_rootfs);
        TASK_NAMES.put("driver", R.string.btn_driver);
        TASK_NAMES.put("tools", R.string.btn_tools);
        TASK_NAMES.put("steam-install", R.string.btn_steam_install);
        TASK_NAMES.put("prepare", R.string.btn_prepare);
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
                    String detail = s(R.string.task_elapsed, String.format(java.util.Locale.ROOT, "%d:%02d", sec / 60, sec % 60))
                            + (rx < 0 ? "" : "  ·  " + s(R.string.task_received, String.format(java.util.Locale.ROOT, "%.1f", (rx - rx0) / 1e6)))
                            + "\n" + s(R.string.task_keep_open);
                    // Overall share for "[n/m]" steps (bar keeps moving inside a step)
                    // "[n/m]" 단계의 전체 비율(단계 안에서는 막대가 계속 움직임)
                    java.util.regex.Matcher m = STEP.matcher(step);
                    int pct = m.find() ? (Integer.parseInt(m.group(1)) - 1) * 100 / Integer.parseInt(m.group(2)) : -1;
                    runOnUiThread(() -> {
                        taskTitle.setText(step);
                        taskDetail.setText(pct >= 0 ? s(R.string.task_overall, pct) + "  ·  " + detail : detail);
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
