/*
 * ezs_pad_steam: the same app-fed controller for the Steam client itself (Big Picture, menus).
 * ezs_pad_steam: 앱이 상태를 넣는 같은 컨트롤러를 Steam 클라이언트 자체(빅픽처, 메뉴)에 줌.
 *
 * Why: the controller reached games (Wine, ezs_pad.c) but not the Steam client, so Big Picture did
 * not react to it (user report). Measured in the client process (earlier input diagnosis): the
 * client loads its own steamrtarm64/libSDL3.so.0, its udev netlink socket is denied, and SDL then
 * watches /dev/input (also denied for Android apps), so it finds no controller.
 * 이유: 컨트롤러가 게임(Wine, ezs_pad.c)에는 갔지만 Steam 클라이언트에는 가지 않아 빅픽처가 반응하지
 * 않았음(사용자 보고). 클라이언트 프로세스 실측(이전 입력 진단): 클라이언트는 자체
 * steamrtarm64/libSDL3.so.0을 불러오고, udev netlink 소켓은 거부되며, SDL은 /dev/input을 감시하려
 * 하는데 이것도 안드로이드 앱에는 막혀 있어 컨트롤러를 찾지 못함.
 *
 * How (own code, SDL3 public API, zlib-licensed headers): in the process whose executable is named
 * "steam", a helper thread waits until the client has loaded SDL3 and started its joystick
 * subsystem, then attaches one SDL3 virtual gamepad (Xbox 360 ids) to that same SDL instance. Its
 * Update callback re-reads the shared file written by the app (same file and layout as ezs_pad.c),
 * so the Steam client sees a normal Xbox controller. If SDL's joystick subsystem is shut down and
 * started again, the pad is attached again.
 * 방식(자체 코드, SDL3 공개 API, zlib 라이선스 헤더): 실행 파일 이름이 "steam"인 프로세스에서 보조
 * 스레드가 클라이언트가 SDL3을 불러오고 조이스틱 하위 시스템을 시작하기를 기다린 뒤, 같은 SDL 인스턴스에
 * SDL3 가상 게임패드(Xbox 360 id) 하나를 붙임. Update 콜백이 앱이 쓰는 공유 파일(ezs_pad.c와 같은 파일·
 * 배치)을 다시 읽으므로 Steam 클라이언트에는 보통 Xbox 컨트롤러로 보임. SDL 조이스틱 하위 시스템이 꺼졌다가
 * 다시 켜지면 다시 붙임.
 *
 * Environment / 환경변수: EZS_PAD_FILE (default /tmp/ezs-pad0.mem)
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <SDL3/SDL.h>

#define PAD_FILE_DEFAULT "/tmp/ezs-pad0.mem"
#define NAXES 6
#define NBUTTONS 15

/* Same layout as ezs_pad.c and the app's PadFile / ezs_pad.c, 앱 PadFile과 같은 배치 */
struct pad_state {
    int16_t axis[NAXES];
    uint8_t btn[NBUTTONS];
    uint8_t hat;
};

static int pad_fd = -1;
static SDL_Joystick *pad_js;
static struct pad_state last;

static SDL_InitFlags (*p_WasInit)(SDL_InitFlags);
static const char *(*p_GetError)(void);
static SDL_JoystickID (*p_AttachVirtualJoystick)(const SDL_VirtualJoystickDesc *);
static SDL_Joystick *(*p_OpenJoystick)(SDL_JoystickID);
static bool (*p_SetJoystickVirtualAxis)(SDL_Joystick *, int, Sint16);
static bool (*p_SetJoystickVirtualButton)(SDL_Joystick *, int, bool);

#define LOG(...) fprintf(stderr, "ezs-pad(steam): " __VA_ARGS__)

/* Called by SDL inside its joystick update (the client's thread): pass changed values on
   SDL 조이스틱 갱신 안에서(클라이언트 스레드) SDL이 부름: 바뀐 값만 넘김 */
static void SDLCALL pad_update(void *userdata)
{
    (void)userdata;
    struct pad_state cur;
    if (pad_fd < 0 || !pad_js) return;
    if (pread(pad_fd, &cur, sizeof(cur), 0) != (ssize_t)sizeof(cur)) return;
    for (int i = 0; i < NAXES; i++)
        if (cur.axis[i] != last.axis[i]) p_SetJoystickVirtualAxis(pad_js, i, cur.axis[i]);
    for (int i = 0; i < NBUTTONS; i++)
        if (cur.btn[i] != last.btn[i]) p_SetJoystickVirtualButton(pad_js, i, cur.btn[i] != 0);
    last = cur;
}

/* SDL detached the pad (subsystem shut down): forget the handle so it is attached again
   SDL이 패드를 뗌(하위 시스템 종료): 다시 붙이도록 핸들을 잊음 */
static void SDLCALL pad_cleanup(void *userdata)
{
    (void)userdata;
    pad_js = NULL;
}

/* Attach the virtual gamepad to the loaded SDL3 / 불러온 SDL3에 가상 게임패드를 붙임 */
static void attach(void)
{
    SDL_VirtualJoystickDesc d;
    SDL_INIT_INTERFACE(&d);
    d.type = SDL_JOYSTICK_TYPE_GAMEPAD;
    d.naxes = NAXES;
    d.nbuttons = NBUTTONS;
    d.vendor_id = 0x045e;
    d.product_id = 0x028e;
    /* Buttons 0..14 and axes 0..5 are SDL's gamepad order, as in the shared file
       버튼 0~14, 축 0~5는 공유 파일과 같은 SDL 게임패드 순서 */
    d.button_mask = (1u << NBUTTONS) - 1;
    d.axis_mask = (1u << NAXES) - 1;
    d.name = "EZSteam Controller";
    d.Update = pad_update;
    d.Cleanup = pad_cleanup;
    SDL_JoystickID id = p_AttachVirtualJoystick(&d);
    if (!id) {
        LOG("attach virtual gamepad failed: %s\n", p_GetError());
        return;
    }
    pad_js = p_OpenJoystick(id);
    if (!pad_js) {
        LOG("open virtual gamepad failed: %s\n", p_GetError());
        return;
    }
    memset(&last, 0, sizeof(last));
    LOG("virtual controller ready for the Steam client (id %u, pid %d)\n", (unsigned)id, (int)getpid());
}

#define SYM(h, name) (p_##name = (typeof(p_##name))dlsym(h, "SDL_" #name))

static void *watch(void *arg)
{
    (void)arg;
    void *sdl = NULL;
    int warned = 0;
    for (;;) {
        sleep(1);
        if (!sdl) {
            /* Only an SDL3 the client already loaded (matched by its soname), never a new one
               클라이언트가 이미 불러온 SDL3만(soname으로 맞춤). 새로 불러오지 않음 */
            sdl = dlopen("libSDL3.so.0", RTLD_NOW | RTLD_NOLOAD);
            if (!sdl) continue;
            if (!SYM(sdl, WasInit) || !SYM(sdl, GetError) || !SYM(sdl, AttachVirtualJoystick)
                || !SYM(sdl, OpenJoystick) || !SYM(sdl, SetJoystickVirtualAxis)
                || !SYM(sdl, SetJoystickVirtualButton)) {
                LOG("client SDL3 without virtual joysticks, no controller in the client\n");
                return NULL;
            }
            LOG("client SDL3 loaded, waiting for its joystick subsystem\n");
        }
        if (!(p_WasInit(SDL_INIT_JOYSTICK) & SDL_INIT_JOYSTICK)) {
            if (!warned && pad_js) LOG("client joystick subsystem stopped\n");
            warned = 1;
            continue;
        }
        warned = 0;
        if (!pad_js) attach();
    }
    return NULL;
}

/* Entry from ezs_pad.c's constructor in the Steam client process
   Steam 클라이언트 프로세스에서 ezs_pad.c 생성자가 부름 */
void ezs_pad_steam_start(void)
{
    const char *path = getenv("EZS_PAD_FILE");
    if (!path || !*path) path = PAD_FILE_DEFAULT;
    pad_fd = open(path, O_RDONLY | O_CLOEXEC);
    if (pad_fd < 0) {
        LOG("no pad file %s (%s), no controller in the client\n", path, strerror(errno));
        return;
    }
    pthread_t t;
    if (pthread_create(&t, NULL, watch, NULL) == 0) pthread_detach(t);
}
