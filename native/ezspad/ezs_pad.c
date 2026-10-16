/*
 * ezs_pad: preload library that gives Wine a game controller fed by the EZSteam app.
 * ezs_pad: EZSteam 앱이 상태를 넣는 게임 컨트롤러를 Wine에 주는 preload 라이브러리.
 *
 * Why: Wine finds controllers through winebus.sys (udev, SDL). In the rootfs there is no udev, and
 * Android does not let apps open /dev/input, so Wine sees no controller (measured: "udev_bus_init
 * UDEV monitor creation failed", "sdl_bus_init could not init SDL: Could not initialize UDEV").
 * The app reads the Android controller itself and writes its state into a small shared file.
 * 이유: Wine은 winebus.sys(udev, SDL)로 컨트롤러를 찾음. rootfs에는 udev가 없고 안드로이드는 앱이
 * /dev/input을 열지 못하게 하므로 Wine에는 컨트롤러가 없음(실측 로그: "udev_bus_init UDEV monitor
 * creation failed", "sdl_bus_init could not init SDL: Could not initialize UDEV"). 앱이 안드로이드
 * 컨트롤러를 직접 읽어 그 상태를 작은 공유 파일에 씀.
 *
 * How (same design as EZlator/Winlator evshim, MIT; own code): in the process that runs winebus
 * (winedevice.exe), before Wine's SDL starts, load the same libSDL2, turn off its udev use
 * (SDL_JOYSTICK_DISABLE_UDEV), and attach one SDL virtual game controller with the Xbox 360 USB ids.
 * Wine's SDL bus then sees it like a real pad and games get it through XInput/DirectInput.
 * The virtual pad's Update callback (run whenever SDL updates joysticks, i.e. in Wine's SDL loop)
 * re-reads the shared file. No extra process and no extra thread.
 * 방식(EZlator/Winlator evshim과 같은 설계, MIT, 코드는 새로 작성): winebus가 도는 프로세스
 * (winedevice.exe)에서 Wine의 SDL이 시작되기 전에 같은 libSDL2를 불러 udev 사용을 끄고
 * (SDL_JOYSTICK_DISABLE_UDEV), Xbox 360 USB id를 가진 SDL 가상 게임 컨트롤러를 하나 붙임.
 * 그러면 Wine SDL 버스가 실제 패드처럼 보고 게임은 XInput/DirectInput으로 받음.
 * 가상 패드의 Update 콜백(SDL이 조이스틱을 갱신할 때, 즉 Wine SDL 루프 안에서 불림)이 공유 파일을
 * 다시 읽음. 추가 프로세스·스레드 없음.
 *
 * Shared file (written by the app, little endian, 64 bytes; layout of EZlator's gamepad.mem):
 *   int16 lx, ly, rx, ry, lt, rt   sticks -32767..32767 (down/right positive), triggers -32767..32767
 *   uint8 btn[15]                  SDL controller order: A B X Y Back Guide Start LS RS LB RB Up Down Left Right
 *   uint8 hat                      unused (d-pad is in btn)
 * 공유 파일(앱이 씀, 리틀 엔디언 64바이트, EZlator gamepad.mem 배치):
 *   int16 lx, ly, rx, ry, lt, rt   스틱 -32767..32767(아래·오른쪽이 +), 트리거 -32767..32767
 *   uint8 btn[15]                  SDL 컨트롤러 순서: A B X Y Back Guide Start LS RS LB RB 위 아래 왼쪽 오른쪽
 *   uint8 hat                      안 씀(십자키는 btn에 있음)
 *
 * Environment / 환경변수: EZS_PAD_FILE (default /tmp/ezs-pad0.mem), EZS_PAD_ALL=1 (every process,
 * for tests / 시험용으로 모든 프로세스에서)
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <SDL2/SDL.h>

#define PAD_FILE_DEFAULT "/tmp/ezs-pad0.mem"
#define NAXES 6
#define NBUTTONS 15

struct pad_state {
    int16_t axis[NAXES];
    uint8_t btn[NBUTTONS];
    uint8_t hat;
};

static int pad_fd = -1;
static SDL_Joystick *pad_js;
static struct pad_state last;

static int (*p_SDL_Init)(Uint32);
static const char *(*p_SDL_GetError)(void);
static int (*p_SDL_JoystickAttachVirtualEx)(const SDL_VirtualJoystickDesc *);
static SDL_Joystick *(*p_SDL_JoystickOpen)(int);
static int (*p_SDL_JoystickSetVirtualAxis)(SDL_Joystick *, int, Sint16);
static int (*p_SDL_JoystickSetVirtualButton)(SDL_Joystick *, int, Uint8);

/* Messages go to stderr, which Proton writes into the game's Proton log
   메시지는 stderr로 감. Proton이 게임의 Proton 로그에 씀 */
#define LOG(...) fprintf(stderr, "ezs-pad: " __VA_ARGS__)

/* True when /proc/self/cmdline names winedevice.exe (the process that runs winebus.sys)
   /proc/self/cmdline에 winedevice.exe가 있으면 참(winebus.sys가 도는 프로세스) */
static int is_winedevice(void)
{
    char buf[4096];
    int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return 0;
    for (ssize_t i = 0; i < n; i++) if (buf[i] == 0) buf[i] = ' ';
    buf[n] = 0;
    return strstr(buf, "winedevice.exe") != NULL;
}

/* Re-read the shared file and pass changes to SDL (called by SDL inside its joystick update)
   공유 파일을 다시 읽어 바뀐 값만 SDL에 넘김(SDL 조이스틱 갱신 안에서 SDL이 부름) */
static void SDLCALL pad_update(void *userdata)
{
    (void)userdata;
    struct pad_state cur;
    if (pad_fd < 0 || !pad_js) return;
    if (pread(pad_fd, &cur, sizeof(cur), 0) != (ssize_t)sizeof(cur)) return;
    for (int i = 0; i < NAXES; i++) {
        if (cur.axis[i] != last.axis[i]) p_SDL_JoystickSetVirtualAxis(pad_js, i, cur.axis[i]);
    }
    for (int i = 0; i < NBUTTONS; i++) {
        if (cur.btn[i] != last.btn[i]) p_SDL_JoystickSetVirtualButton(pad_js, i, cur.btn[i] ? 1 : 0);
    }
    last = cur;
}

#define SYM(name) (p_##name = (typeof(p_##name))dlsym(sdl, #name))

__attribute__((constructor))
static void ezs_pad_init(void)
{
    const char *all = getenv("EZS_PAD_ALL");
    if (!(all && *all == '1') && !is_winedevice()) return;

    const char *path = getenv("EZS_PAD_FILE");
    if (!path || !*path) path = PAD_FILE_DEFAULT;
    pad_fd = open(path, O_RDONLY | O_CLOEXEC);
    if (pad_fd < 0) {
        LOG("no pad file %s (%s), no controller\n", path, strerror(errno));
        return;
    }

    /* Same library Wine loads (SONAME_LIBSDL2), kept global so Wine gets this instance
       Wine이 부르는 것과 같은 라이브러리(SONAME_LIBSDL2). 전역으로 두어 Wine이 이 인스턴스를 씀 */
    void *sdl = dlopen("libSDL2-2.0.so.0", RTLD_NOW | RTLD_GLOBAL);
    if (!sdl) {
        LOG("dlopen libSDL2-2.0.so.0 failed: %s\n", dlerror());
        return;
    }
    if (!SYM(SDL_Init) || !SYM(SDL_GetError) || !SYM(SDL_JoystickAttachVirtualEx) || !SYM(SDL_JoystickOpen)
        || !SYM(SDL_JoystickSetVirtualAxis) || !SYM(SDL_JoystickSetVirtualButton)) {
        LOG("SDL2 without virtual joysticks (needs 2.24 or later)\n");
        return;
    }

    /* No udev in the rootfs: SDL's Linux joystick driver would fail its whole init; without
       udev it only scans /dev/input (not readable for Android apps, so it finds nothing).
       HIDAPI scanning is useless here as well. Set before any SDL_Init in this process.
       rootfs에는 udev가 없음: SDL 리눅스 조이스틱 드라이버의 초기화 전체가 실패함. udev 없이는
       /dev/input만 훑음(안드로이드 앱은 못 읽으므로 아무것도 찾지 않음). HIDAPI 탐색도 쓸모없음.
       이 프로세스의 어떤 SDL_Init보다 먼저 정함 */
    setenv("SDL_JOYSTICK_DISABLE_UDEV", "1", 1);
    setenv("SDL_JOYSTICK_HIDAPI", "0", 0);

    if (p_SDL_Init(SDL_INIT_JOYSTICK) != 0) {
        LOG("SDL_Init(JOYSTICK) failed: %s\n", p_SDL_GetError());
        return;
    }

    SDL_VirtualJoystickDesc d;
    memset(&d, 0, sizeof(d));
    d.version = SDL_VIRTUAL_JOYSTICK_DESC_VERSION;
    d.type = SDL_JOYSTICK_TYPE_GAMECONTROLLER;
    d.naxes = NAXES;
    d.nbuttons = NBUTTONS;
    d.nhats = 0;
    /* Xbox 360 controller USB ids: Wine and games treat it as an XInput pad
       Xbox 360 컨트롤러 USB id: Wine과 게임이 XInput 패드로 다룸 */
    d.vendor_id = 0x045e;
    d.product_id = 0x028e;
    /* Buttons 0..14 and axes 0..5 map one to one to the SDL controller layout
       버튼 0~14, 축 0~5가 SDL 컨트롤러 배치와 일대일로 대응함 */
    d.button_mask = (1u << NBUTTONS) - 1;
    d.axis_mask = (1u << NAXES) - 1;
    d.name = "EZSteam Controller";
    d.Update = pad_update;

    int index = p_SDL_JoystickAttachVirtualEx(&d);
    if (index < 0) {
        LOG("attach virtual controller failed: %s\n", p_SDL_GetError());
        return;
    }
    pad_js = p_SDL_JoystickOpen(index);
    if (!pad_js) {
        LOG("open virtual controller failed: %s\n", p_SDL_GetError());
        return;
    }
    /* All zero as the starting point; the first update sends whatever differs in the file
       시작점은 모두 0. 첫 갱신 때 파일과 다른 값을 보냄 */
    memset(&last, 0, sizeof(last));
    LOG("virtual controller ready (index %d, file %s, pid %d)\n", index, path, (int)getpid());
}
