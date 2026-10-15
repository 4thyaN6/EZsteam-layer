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

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row);
        addButton(row, "rootfs 설치", v -> runTask("install", this::doInstall));
        addButton(row, "환경 시험", v -> runTask("smoke", this::doSmoke));
        addButton(row, "자식 프로세스", v -> runTask("procs", this::doProcsLive));
        addButton(row, "Steam 준비", v -> runTask("prepare", this::doPrepare));
        addButton(row, "PRoot 상태", v -> runTask("proot", this::doProotInfo));

        // Second row: GPU checks / 두 번째 줄: GPU 확인
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row2);
        addButton(row2, "드라이버 설치", v -> runTask("driver", this::doDriverInstall));
        addButton(row2, "GPU 확인", v -> runTask("gpu", this::doGpuCheck));
        addButton(row2, "시험 도구 설치", v -> runTask("tools", this::doToolsInstall));
        addButton(row2, "그리기 시험", v -> runTask("draw", this::doDrawTest));

        // Third row: live display test per X screen size (16:10 like the device, then the device's own size)
        // 세 번째 줄: X 화면 크기별 실시간 화면 시험(기기와 같은 16:10, 마지막은 기기 자체 해상도)
        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row3);
        addButton(row3, "화면 1280x800", v -> openDisplay(1280, 800));
        addButton(row3, "화면 1920x1200", v -> openDisplay(1920, 1200));
        addButton(row3, "화면 2560x1600", v -> openDisplay(2560, 1600));
        int[] nat = nativeSize();
        addButton(row3, "화면 " + nat[0] + "x" + nat[1], v -> openDisplay(nat[0], nat[1]));

        // Fourth row: Valve Linux ARM64 Steam client (downloaded by the device from Valve)
        // 네 번째 줄: Valve Linux ARM64 Steam 클라이언트(기기가 Valve에서 직접 받음)
        LinearLayout row4 = new LinearLayout(this);
        row4.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row4);
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

        // Screenshot area for the drawing test / 그리기 시험 화면 캡처 영역
        shotView = new android.widget.ImageView(this);
        shotView.setAdjustViewBounds(true);
        shotView.setVisibility(View.GONE);
        root.addView(shotView, new LinearLayout.LayoutParams(
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
        if (!new File(RootfsInstaller.rootfsDir(this), "home/steam/.local/share/Steam/steamrtarm64/steam").isFile()) {
            log("Steam 클라이언트 없음: [시험 도구 설치] → [Steam 설치] 먼저");
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
     * the client already runs without), GPU and network service in the browser process, audio
     * service in process, one renderer process. Steam's own flags come after these and win on
     * conflicts. Effect on the process count is not measured yet.
     * Steam 웹 도우미 보조 프로세스를 줄이는 Chromium 플래그(안드로이드는 모든 앱 합계 추가 프로세스
     * 32개까지 허용): zygote 없음(샌드박스 없이 실행해야 하며 클라이언트는 이미 그렇게 실행 중),
     * GPU·네트워크 서비스를 브라우저 프로세스 안에서, 오디오 서비스도 안에서, 렌더러 1개.
     * Steam 자체 플래그가 뒤에 오므로 겹치면 Steam 값이 우선함. 프로세스 수 효과는 아직 측정 전
     */
    static final String CEF_SLIM_FLAGS = "--no-sandbox --no-zygote --in-process-gpu --renderer-process-limit=1"
            + " --enable-features=NetworkServiceInProcess --disable-features=AudioServiceOutOfProcess";

    /** CEF process saving on (default) or off / CEF 프로세스 절감 켬(기본) 또는 끔 */
    static boolean cefSlim(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_CEF_SLIM, true);
    }

    private static String cefLabel(boolean on) {
        return "CEF 절감: " + (on ? "켬" : "끔");
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

    /** Run one task on a worker thread, logging errors / 작업 하나를 작업 스레드에서 돌리고 오류를 기록함 */
    private void runTask(String name, Task t) {
        if (busy) {
            log("다른 작업 진행 중");
            return;
        }
        busy = true;
        log("== " + name);
        new Thread(() -> {
            try {
                t.run();
            } catch (Throwable e) {
                log("ERROR " + e);
            } finally {
                busy = false;
            }
        }, "ezs-" + name).start();
    }

    /** Thread-safe log append / 스레드 안전 로그 추가 */
    private void log(String line) {
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
