/*
 * ezs: ALSA playback plugin (alsa-lib "ioplug" external PCM) that hands the sound of programs in
 * the glibc rootfs to the EZSteam app over a UNIX socket; the app plays it with Android AudioTrack.
 * ezs: rootfs 안 프로그램의 소리를 UNIX 소켓으로 EZSteam 앱에 넘기는 ALSA 재생 플러그인
 * (alsa-lib "ioplug" 외부 PCM). 앱이 안드로이드 AudioTrack으로 재생함.
 *
 * Why: Android gives apps no ALSA sound card, so Wine (winealsa) finds no audio device and some
 * games stop with "No audio device could be found". No extra process is started (Android allows
 * 32 extra processes for all apps together); the plugin runs inside the program that plays.
 * 이유: 안드로이드는 앱에 ALSA 사운드 카드를 주지 않으므로 Wine(winealsa)이 오디오 장치를 찾지 못하고
 * 일부 게임은 "No audio device could be found"로 멈춤. 추가 프로세스 없음(안드로이드는 모든 앱 합계
 * 추가 프로세스 32개까지 허용). 플러그인은 소리를 내는 프로그램 안에서 동작함.
 *
 * Stream: fixed S16_LE, 48000 Hz, 2 channels (an ALSA "plug" in front converts everything else,
 * see steam-run.sh). Each opened PCM is one socket connection; the app gives each its own AudioTrack.
 * 스트림: S16_LE, 48000 Hz, 2채널 고정(그 밖의 형식은 앞의 ALSA "plug"가 변환, steam-run.sh 참고).
 * 연 PCM 하나가 소켓 연결 하나이며, 앱은 연결마다 AudioTrack을 하나씩 씀.
 *
 * Protocol: 16-byte header once ("EZSA", version 1, rate, channels; little-endian uint32), then
 * raw interleaved samples. Nothing comes back.
 * 프로토콜: 처음에 16바이트 머리("EZSA", 버전 1, rate, channels, 리틀엔디언 uint32), 그 뒤로는
 * 인터리브된 샘플 그대로. 되돌아오는 데이터 없음.
 *
 * Timing: playback position follows CLOCK_MONOTONIC from start (never past what was written), so
 * the program can only stay one ALSA buffer ahead, as with a real card. The socket send buffer is
 * kept small so the app's AudioTrack, which plays in real time, also paces the data.
 * When the app is not listening, samples are dropped and the program keeps running silently.
 * 시간: 재생 위치는 시작 시점부터 CLOCK_MONOTONIC을 따라감(쓴 양을 넘지 않음). 그래서 프로그램은
 * 실제 카드처럼 ALSA 버퍼 하나만큼만 앞서 쓸 수 있음. 소켓 송신 버퍼를 작게 두어 실시간으로 재생하는
 * 앱의 AudioTrack도 속도를 맞춤. 앱이 듣고 있지 않으면 샘플을 버리고 프로그램은 소리 없이 계속 돎.
 *
 * Capture: silence at the same clock, without a connection. ALSA's own "null" capture cannot be
 * used here: it opens /dev/full, which Android denies to apps (measured: Permission denied).
 * 녹음: 연결 없이 같은 시계로 무음을 줌. ALSA 자체 "null" 녹음은 여기서 못 씀: /dev/full을 여는데
 * 안드로이드가 앱에 허용하지 않음(실측: Permission denied).
 *
 * Config / 설정:
 *   pcm_type.ezs { lib "/opt/ezsteam/lib/libasound_module_pcm_ezs.so" }
 *   pcm.x { type ezs socket "/tmp/ezs-audio.sock" }
 */
#define _GNU_SOURCE
#include <alsa/asoundlib.h>
#include <alsa/pcm_external.h>
#include <errno.h>
#include <poll.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/timerfd.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define EZS_RATE 48000
#define EZS_CHANNELS 2
#define EZS_FRAME_BYTES 4
#define EZS_DEFAULT_SOCKET "/tmp/ezs-audio.sock"
/* Small send buffer: little sound waits in the socket (kernel doubles the value)
   작은 송신 버퍼: 소켓 안에서 기다리는 소리를 적게 함(커널이 값을 두 배로 잡음) */
#define EZS_SNDBUF 16384

typedef struct {
    snd_pcm_ioplug_t io;
    /* Connection to the app, -1 while not connected / 앱 연결, 연결 안 됨이면 -1 */
    int sock;
    /* timerfd, the poll descriptor (wakes the program once per period)
       timerfd, poll 디스크립터(주기마다 프로그램을 깨움) */
    int timer;
    char path[sizeof(((struct sockaddr_un *)0)->sun_path)];
    /* Frames handed over since prepare / prepare 이후 넘긴 프레임 수 */
    uint64_t written;
    /* Clock value that counts as position 0 while running / 실행 중 위치 0에 해당하는 시각 */
    uint64_t start_ns;
    /* Position when not running / 실행 중이 아닐 때의 위치 */
    uint64_t held;
    int running;
    /* Capture stream (silence, no connection) / 녹음 스트림(무음, 연결 없음) */
    int capture;
} ezs_t;

/* Monotonic clock in ns / 단조 시계(ns) */
static uint64_t now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

/*
 * Played frames: clock since start, never past written. When the clock passes what was written
 * (the program fell behind, the device plays silence), the clock is moved so playback resumes
 * from there instead of letting the program dump the missed time at once.
 * 재생된 프레임: 시작 후 시간, 쓴 양을 넘지 않음. 시계가 쓴 양을 넘으면(프로그램이 늦어 장치는
 * 무음 재생) 시계를 옮겨 그 지점부터 이어 감. 놓친 시간만큼을 한꺼번에 쏟아 넣지 않게 함
 */
static uint64_t played(ezs_t *e)
{
    if (!e->running) return e->held;
    uint64_t now = now_ns();
    uint64_t f = (now - e->start_ns) * EZS_RATE / 1000000000ull;
    /* Capture: the clock alone; the program reads what has "arrived" / 녹음: 시계만 따름. 프로그램이 "도착한" 만큼 읽음 */
    if (e->capture) return f;
    if (f > e->written) {
        f = e->written;
        e->start_ns = now - f * 1000000000ull / EZS_RATE;
    }
    return f;
}

/* Write all bytes; on error close the connection and drop the rest (sound stops, program goes on)
   모든 바이트를 씀. 오류면 연결을 닫고 나머지를 버림(소리만 멈추고 프로그램은 계속) */
static void send_all(ezs_t *e, const void *buf, size_t len)
{
    const char *p = buf;
    while (len > 0 && e->sock >= 0) {
        ssize_t n = send(e->sock, p, len, MSG_NOSIGNAL);
        if (n < 0) {
            if (errno == EINTR) continue;
            close(e->sock);
            e->sock = -1;
            return;
        }
        p += n;
        len -= (size_t)n;
    }
}

/* Connect to the app and send the header; silently stays unconnected when the app is not there
   앱에 연결하고 머리를 보냄. 앱이 없으면 조용히 연결 안 된 상태로 둠 */
static void try_connect(ezs_t *e)
{
    if (e->sock >= 0 || e->capture) return;
    int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (s < 0) return;
    struct sockaddr_un a;
    memset(&a, 0, sizeof(a));
    a.sun_family = AF_UNIX;
    memcpy(a.sun_path, e->path, strlen(e->path) + 1);
    if (connect(s, (struct sockaddr *)&a, sizeof(a)) < 0) {
        close(s);
        return;
    }
    int sz = EZS_SNDBUF;
    setsockopt(s, SOL_SOCKET, SO_SNDBUF, &sz, sizeof(sz));
    e->sock = s;
    uint32_t hdr[4];
    memcpy(&hdr[0], "EZSA", 4);
    hdr[1] = 1;
    hdr[2] = EZS_RATE;
    hdr[3] = EZS_CHANNELS;
    send_all(e, hdr, sizeof(hdr));
}

/* Periodic timer for poll: period length while armed, off otherwise
   poll용 주기 타이머: 켜면 주기 길이마다, 아니면 꺼짐 */
static void arm_timer(ezs_t *e, int on)
{
    struct itimerspec it;
    memset(&it, 0, sizeof(it));
    if (on && e->io.period_size > 0) {
        uint64_t ns = (uint64_t)e->io.period_size * 1000000000ull / EZS_RATE;
        it.it_interval.tv_sec = (time_t)(ns / 1000000000ull);
        it.it_interval.tv_nsec = (long)(ns % 1000000000ull);
        it.it_value = it.it_interval;
    }
    timerfd_settime(e->timer, 0, &it, NULL);
}

static int ezs_start(snd_pcm_ioplug_t *io)
{
    ezs_t *e = io->private_data;
    /* Continue from the held position / 멈춰 둔 위치에서 이어 감 */
    e->start_ns = now_ns() - e->held * 1000000000ull / EZS_RATE;
    e->running = 1;
    arm_timer(e, 1);
    return 0;
}

static int ezs_stop(snd_pcm_ioplug_t *io)
{
    ezs_t *e = io->private_data;
    e->held = played(e);
    e->running = 0;
    arm_timer(e, 0);
    return 0;
}

static int ezs_prepare(snd_pcm_ioplug_t *io)
{
    ezs_t *e = io->private_data;
    e->written = 0;
    e->held = 0;
    e->running = 0;
    /* Reconnect when the app came up later / 앱이 나중에 뜬 경우 다시 연결 */
    try_connect(e);
    arm_timer(e, 1);
    return 0;
}

/* Hardware position inside the buffer / 버퍼 안의 하드웨어 위치 */
static snd_pcm_sframes_t ezs_pointer(snd_pcm_ioplug_t *io)
{
    ezs_t *e = io->private_data;
    return (snd_pcm_sframes_t)(played(e) % io->buffer_size);
}

static snd_pcm_sframes_t ezs_transfer(snd_pcm_ioplug_t *io, const snd_pcm_channel_area_t *areas,
                                      snd_pcm_uframes_t offset, snd_pcm_uframes_t size)
{
    ezs_t *e = io->private_data;
    /* Interleaved: one area start for all channels / 인터리브: 모든 채널이 한 영역에서 시작 */
    char *buf = (char *)areas[0].addr + (areas[0].first + areas[0].step * offset) / 8;
    if (e->capture) memset(buf, 0, size * EZS_FRAME_BYTES);
    else send_all(e, buf, size * EZS_FRAME_BYTES);
    e->written += size;
    return (snd_pcm_sframes_t)size;
}

/* Drain: wait until the clock has played everything written / drain: 쓴 것을 시계가 다 재생할 때까지 기다림 */
static int ezs_drain(snd_pcm_ioplug_t *io)
{
    ezs_t *e = io->private_data;
    if (e->capture) return 0;
    if (!e->running) ezs_start(io);
    while (played(e) < e->written) {
        uint64_t left = (e->written - played(e)) * 1000000000ull / EZS_RATE;
        struct timespec ts = { (time_t)(left / 1000000000ull), (long)(left % 1000000000ull) };
        nanosleep(&ts, NULL);
    }
    return 0;
}

static int ezs_poll_revents(snd_pcm_ioplug_t *io, struct pollfd *pfd, unsigned int nfds,
                            unsigned short *revents)
{
    ezs_t *e = io->private_data;
    (void)nfds;
    uint64_t ticks;
    /* Consume timer ticks (non-blocking) / 타이머 틱을 비움(막히지 않음) */
    while (read(e->timer, &ticks, sizeof(ticks)) == (ssize_t)sizeof(ticks)) {}
    *revents = (pfd[0].revents & POLLIN) ? (e->capture ? POLLIN : POLLOUT) : 0;
    return 0;
}

static int ezs_close(snd_pcm_ioplug_t *io)
{
    ezs_t *e = io->private_data;
    if (e->sock >= 0) close(e->sock);
    if (e->timer >= 0) close(e->timer);
    free(e);
    return 0;
}

static const snd_pcm_ioplug_callback_t ezs_callback = {
    .start = ezs_start,
    .stop = ezs_stop,
    .pointer = ezs_pointer,
    .transfer = ezs_transfer,
    .close = ezs_close,
    .prepare = ezs_prepare,
    .drain = ezs_drain,
    .poll_revents = ezs_poll_revents,
};

/* Fixed format, buffer limits from 2 ms periods up to 1 s buffers
   고정 형식, 버퍼 범위는 주기 2 ms부터 버퍼 1초까지 */
static int set_constraints(snd_pcm_ioplug_t *io)
{
    static const unsigned int access[] = { SND_PCM_ACCESS_RW_INTERLEAVED, SND_PCM_ACCESS_MMAP_INTERLEAVED };
    static const unsigned int formats[] = { SND_PCM_FORMAT_S16_LE };
    int err;
    if ((err = snd_pcm_ioplug_set_param_list(io, SND_PCM_IOPLUG_HW_ACCESS, 2, access)) < 0) return err;
    if ((err = snd_pcm_ioplug_set_param_list(io, SND_PCM_IOPLUG_HW_FORMAT, 1, formats)) < 0) return err;
    if ((err = snd_pcm_ioplug_set_param_minmax(io, SND_PCM_IOPLUG_HW_CHANNELS, EZS_CHANNELS, EZS_CHANNELS)) < 0) return err;
    if ((err = snd_pcm_ioplug_set_param_minmax(io, SND_PCM_IOPLUG_HW_RATE, EZS_RATE, EZS_RATE)) < 0) return err;
    if ((err = snd_pcm_ioplug_set_param_minmax(io, SND_PCM_IOPLUG_HW_PERIOD_BYTES,
                                               96 * EZS_FRAME_BYTES, EZS_RATE * EZS_FRAME_BYTES / 2)) < 0) return err;
    if ((err = snd_pcm_ioplug_set_param_minmax(io, SND_PCM_IOPLUG_HW_BUFFER_BYTES,
                                               192 * EZS_FRAME_BYTES, EZS_RATE * EZS_FRAME_BYTES)) < 0) return err;
    if ((err = snd_pcm_ioplug_set_param_minmax(io, SND_PCM_IOPLUG_HW_PERIODS, 2, 64)) < 0) return err;
    return 0;
}

SND_PCM_PLUGIN_DEFINE_FUNC(ezs)
{
    snd_config_iterator_t i, next;
    const char *path = EZS_DEFAULT_SOCKET;
    (void)root;

    snd_config_for_each(i, next, conf) {
        snd_config_t *n = snd_config_iterator_entry(i);
        const char *id;
        if (snd_config_get_id(n, &id) < 0) continue;
        if (!strcmp(id, "comment") || !strcmp(id, "type") || !strcmp(id, "hint")) continue;
        if (!strcmp(id, "socket")) {
            if (snd_config_get_string(n, &path) < 0) {
                SNDERR("ezs: socket must be a string");
                return -EINVAL;
            }
            continue;
        }
        SNDERR("ezs: unknown field %s", id);
        return -EINVAL;
    }
    if (strlen(path) >= sizeof(((struct sockaddr_un *)0)->sun_path)) {
        SNDERR("ezs: socket path too long");
        return -EINVAL;
    }

    ezs_t *e = calloc(1, sizeof(*e));
    if (!e) return -ENOMEM;
    e->sock = -1;
    e->capture = stream == SND_PCM_STREAM_CAPTURE;
    strcpy(e->path, path);
    e->timer = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK | TFD_CLOEXEC);
    if (e->timer < 0) {
        int err = -errno;
        free(e);
        return err;
    }
    try_connect(e);

    e->io.version = SND_PCM_IOPLUG_VERSION;
    e->io.name = "EZSteam audio (Android AudioTrack)";
    e->io.callback = &ezs_callback;
    e->io.private_data = e;
    e->io.poll_fd = e->timer;
    e->io.poll_events = POLLIN;
    e->io.mmap_rw = 0;

    int err = snd_pcm_ioplug_create(&e->io, name, stream, mode);
    if (err < 0) {
        if (e->sock >= 0) close(e->sock);
        close(e->timer);
        free(e);
        return err;
    }
    if ((err = set_constraints(&e->io)) < 0) {
        snd_pcm_ioplug_delete(&e->io);
        return err;
    }
    *pcmp = e->io.pcm;
    return 0;
}

SND_PCM_PLUGIN_SYMBOL(ezs);
