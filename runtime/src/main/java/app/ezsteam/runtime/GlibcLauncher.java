package app.ezsteam.runtime;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Starts a glibc aarch64 program from the app-private rootfs through PRoot.
 * PRoot를 거쳐 앱 전용 rootfs의 glibc aarch64 프로그램을 띄움
 *
 * PRoot makes the rootfs look like "/" (so Valve's binaries find /lib/ld-linux-aarch64.so.1
 * and their fixed paths unchanged) and answers syscalls that Android's app seccomp filter
 * blocks (rseq, set_robust_list, faccessat2 ...) with ENOSYS instead of letting SIGSYS kill
 * the process. It needs neither root nor developer options; it adds one tracer process.
 * PRoot는 rootfs를 "/"로 보이게 해서 Valve 파일이 /lib/ld-linux-aarch64.so.1과 고정 경로를
 * 그대로 찾게 하고, 안드로이드 앱 seccomp이 막는 호출(rseq, set_robust_list, faccessat2 등)을
 * SIGSYS로 죽이는 대신 ENOSYS로 돌려줌. 루트·개발자 옵션 불필요, 추적 프로세스 1개가 늘어남
 *
 * Requirements: targetSdk 28 or lower, and libproot.so / libproot-loader.so extracted into
 * nativeLibraryDir (host app packaging: jniLibs useLegacyPackaging = true).
 * 조건: targetSdk 28 이하, libproot.so·libproot-loader.so가 nativeLibraryDir에 풀려 있어야 함
 * (호스트 앱 패키징: jniLibs useLegacyPackaging = true)
 */
public final class GlibcLauncher {

    /** PRoot binary and loader names inside nativeLibraryDir / nativeLibraryDir 안 PRoot·로더 이름 */
    public static final String PROOT = "libproot.so";
    public static final String PROOT_LOADER = "libproot-loader.so";

    /**
     * Launch settings. Paths are real Android paths unless noted "guest".
     * 실행 설정. "guest"라고 적은 것 외에는 실제 안드로이드 경로임
     */
    public static final class Config {
        /** Rootfs directory / rootfs 폴더 */
        public File rootfs;
        /** Host app's nativeLibraryDir (holds libproot.so) / 호스트 앱 nativeLibraryDir(libproot.so 위치) */
        public File nativeLibDir;
        /** Runtime data outside the rootfs (PRoot temp files, /dev/shm backing)
         *  rootfs 밖 런타임 데이터(PRoot 임시 파일, /dev/shm 실제 폴더) */
        public File dataDir;
        /** HOME inside the guest / guest 안의 HOME */
        public String guestHome = "/home/steam";
        /** X display of the app X server / 앱 X 서버 디스플레이 */
        public String display = ":0";
        /** Extra binds: {host path, guest path}, e.g. the app X server socket folder to /tmp/.X11-unix
         *  추가 바인드: {실제 경로, guest 경로}. 예: 앱 X 서버 소켓 폴더 → /tmp/.X11-unix */
        public List<String[]> binds = new ArrayList<>();
        /** Hard links become symlinks (Android app storage refuses link()) / 하드 링크를 심볼릭 링크로 바꿈(앱 저장소는 link() 거부) */
        public boolean link2symlink = true;
        /** Emulate System V IPC (X MIT-SHM, some libraries) / System V IPC 흉내(X MIT-SHM, 일부 라이브러리) */
        public boolean sysvipc = true;
        /** Fake root (PRoot -0) for package installs with apt/dpkg only. Never for Steam,
         *  which refuses to run as root.
         *  apt/dpkg 패키지 설치 전용 가짜 root(PRoot -0). Steam은 root 실행을 거부하므로 쓰지 않음 */
        public boolean rootId = false;
        /** PRoot log level (0 = quiet) / PRoot 로그 수준(0 = 조용히) */
        public int verbose = 0;
        /** Extra guest environment (e.g. Vulkan driver selection from DriverInstaller.apply)
         *  guest 추가 환경변수(예: DriverInstaller.apply가 넣는 Vulkan 드라이버 선택) */
        public Map<String, String> env = new LinkedHashMap<>();
    }

    private GlibcLauncher() {
    }

    /** Folder for PRoot temp files / PRoot 임시 파일 폴더 */
    public static File prootTmp(Config c) {
        return new File(c.dataDir, "ptmp");
    }

    /** Real folder bound to /dev/shm / /dev/shm으로 바인드되는 실제 폴더 */
    public static File shmDir(Config c) {
        return new File(c.dataDir, "shm");
    }

    /** True when PRoot and its loader are present / PRoot와 로더가 있으면 참 */
    public static boolean available(Config c) {
        return new File(c.nativeLibDir, PROOT).isFile() && new File(c.nativeLibDir, PROOT_LOADER).isFile();
    }

    /**
     * Build the environment from scratch (the app's own env holds bionic LD_* values).
     * PROOT_* variables are read by PRoot itself; the rest is what the guest program sees.
     * 환경변수를 처음부터 만듦(앱 자체 환경에는 bionic용 LD_* 값이 있음).
     * PROOT_* 는 PRoot가 읽고, 나머지는 guest 프로그램이 보는 값임
     */
    public static Map<String, String> buildEnv(Config c) {
        Map<String, String> env = new LinkedHashMap<>();
        // PRoot settings / PRoot 설정
        env.put("PROOT_LOADER", new File(c.nativeLibDir, PROOT_LOADER).getAbsolutePath());
        env.put("PROOT_TMP_DIR", prootTmp(c).getAbsolutePath());
        if (c.verbose > 0) env.put("PROOT_VERBOSE", Integer.toString(c.verbose));

        // Guest environment (guest paths) / guest 환경(guest 경로)
        env.put("PATH", "/usr/local/bin:/usr/bin:/bin");
        env.put("HOME", c.guestHome);
        env.put("USER", "steam");
        env.put("LANG", "C.UTF-8");
        env.put("TMPDIR", "/tmp");
        env.put("XDG_RUNTIME_DIR", "/run/user/ezg");
        env.put("DISPLAY", c.display);
        env.putAll(c.env);
        return env;
    }

    /**
     * PRoot command line up to (not including) the guest program.
     * guest 프로그램 앞까지의 PRoot 명령
     */
    public static List<String> prootArgs(Config c) {
        List<String> cmd = new ArrayList<>();
        cmd.add(new File(c.nativeLibDir, PROOT).getAbsolutePath());
        // Kill the whole guest tree when the first program ends / 첫 프로그램이 끝나면 guest 전체 종료
        cmd.add("--kill-on-exit");
        cmd.add("--rootfs=" + c.rootfs.getAbsolutePath());
        cmd.add("--cwd=" + c.guestHome);
        // Kernel interfaces shared with Android; /dev/shm gets a writable app folder
        // 안드로이드와 공유하는 커널 인터페이스. /dev/shm은 쓰기 가능한 앱 폴더로 연결
        cmd.add("--bind=/dev");
        cmd.add("--bind=/proc");
        cmd.add("--bind=/sys");
        cmd.add("--bind=" + shmDir(c).getAbsolutePath() + ":/dev/shm");
        for (String[] b : c.binds) cmd.add("--bind=" + b[0] + ":" + b[1]);
        if (c.link2symlink) cmd.add("--link2symlink");
        if (c.sysvipc) cmd.add("--sysvipc");
        if (c.rootId) cmd.add("--root-id");
        return cmd;
    }

    /** Create the folders PRoot and the guest expect / PRoot와 guest가 쓰는 폴더 생성 */
    private static void ensureDirs(Config c) throws IOException {
        for (File d : new File[]{prootTmp(c), shmDir(c), new File(c.rootfs, "tmp"),
                new File(c.rootfs, "run/user/ezg"),
                new File(c.rootfs, c.guestHome.substring(1))}) {
            if (!d.isDirectory() && !d.mkdirs()) throw new IOException("cannot create " + d);
        }
    }

    /**
     * Start a guest program. programInGuest is a guest absolute path (e.g. "/usr/bin/true").
     * guest 프로그램을 띄움. programInGuest는 guest 절대 경로임(예: "/usr/bin/true")
     */
    public static Process start(Config c, String programInGuest, List<String> args,
                                Map<String, String> extraEnv, File workDir) throws IOException {
        if (!available(c)) throw new IOException("PRoot not found in " + c.nativeLibDir);
        ensureDirs(c);

        List<String> cmd = prootArgs(c);
        cmd.add(programInGuest);
        if (args != null) cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        env.clear();
        env.putAll(buildEnv(c));
        if (extraEnv != null) env.putAll(extraEnv);
        if (workDir != null) pb.directory(workDir);
        pb.redirectErrorStream(true);
        return pb.start();
    }

    /**
     * Run a shell script (e.g. a guest "steam.sh") through the guest /bin/sh.
     * 셸 스크립트(예: guest의 "steam.sh")를 guest /bin/sh로 실행함
     */
    public static Process startScript(Config c, String scriptInGuest, List<String> args,
                                      Map<String, String> extraEnv, File workDir) throws IOException {
        List<String> shArgs = new ArrayList<>();
        shArgs.add(scriptInGuest);
        if (args != null) shArgs.addAll(args);
        return start(c, "/bin/sh", shArgs, extraEnv, workDir);
    }

    /**
     * Run /opt/ezsteam/ezsteam-prepare.sh and wait (sdkarm64 link + EZ Proton tool).
     * Returns the script output for the app log.
     * /opt/ezsteam/ezsteam-prepare.sh를 실행하고 기다림(sdkarm64 링크 + EZ Proton 도구).
     * 앱 기록용으로 스크립트 출력을 돌려줌
     */
    public static String prepareSteam(Config c) throws IOException, InterruptedException {
        Process p = startScript(c, "/opt/ezsteam/ezsteam-prepare.sh", null, null, null);
        byte[] out = readAll(p.getInputStream());
        int rc = p.waitFor();
        return new String(out, java.nio.charset.StandardCharsets.UTF_8) + "exit=" + rc + "\n";
    }

    /** Read a stream fully (API 26 has no InputStream.readAllBytes)
     *  스트림을 끝까지 읽음(API 26에는 InputStream.readAllBytes가 없음) */
    private static byte[] readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
