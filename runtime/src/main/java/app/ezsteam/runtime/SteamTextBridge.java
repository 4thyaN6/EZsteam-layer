package app.ezsteam.runtime;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.nio.charset.StandardCharsets;

/**
 * Keyboard text for the Steam UI, handed to ezs-agent, which puts it into the Steam page that has
 * a focused text field through Chromium's DevTools pipe (Input.insertText for committed text,
 * Input.imeSetComposition for the syllable being composed). Why: Hangul typed through X (a spare
 * keycode remapped to the character) showed the first syllable for every later one in the Steam
 * UI, while the X server held the right mapping (device log 2026-11-02 22:39); Chromium does not
 * follow remapped keycodes. This path uses no keycode at all.
 * Two FIFOs in the rootfs made by the agent: requests as JSON lines to /tmp/ezs-text.in
 * ({"id","op","text"}), answers on /tmp/ezs-text.out ({"id","ok"}). ok false = no Steam page has a
 * focused text field (a game has the input): the caller then types through X as before.
 * Used from one thread only (the app's keyboard queue). The text is never logged.
 * Steam 화면용 키보드 글자를 ezs-agent에 넘김. 에이전트는 Chromium DevTools 파이프로 텍스트 칸에 포커스가 있는 Steam
 * 페이지에 넣음(확정 글자는 Input.insertText, 조합 중인 글자는 Input.imeSetComposition). 이유: X로 친 한글(예비 키코드를
 * 그 글자로 바꿈)은 X 서버에 맞는 배치가 있었는데도 Steam 화면에서 나중 글자가 모두 첫 음절로 보였음(기기 로그
 * 2026-11-02 22:39). Chromium이 바뀐 키코드를 따라가지 않음. 이 경로는 키코드를 전혀 쓰지 않음.
 * 에이전트가 rootfs에 만든 FIFO 둘: 요청은 JSON 줄로 /tmp/ezs-text.in({"id","op","text"}), 답은 /tmp/ezs-text.out
 * ({"id","ok"}). ok false = 텍스트 칸에 포커스가 있는 Steam 페이지가 없음(게임이 입력을 가짐). 그러면 부르는 쪽이 전처럼 X로 침.
 * 한 스레드(앱의 키보드 줄)에서만 씀. 글자는 기록하지 않음
 */
public final class SteamTextBridge implements Closeable {

    /** Guest paths of the agent's FIFOs / 에이전트 FIFO의 guest 경로 */
    public static final String GUEST_IN = "/tmp/ezs-text.in", GUEST_OUT = "/tmp/ezs-text.out";

    private final FileDescriptor wfd, rfd;
    private final StringBuilder pendingLine = new StringBuilder();
    private int nextId;
    private boolean broken;

    private SteamTextBridge(FileDescriptor wfd, FileDescriptor rfd) {
        this.wfd = wfd;
        this.rfd = rfd;
    }

    /**
     * Open the agent's FIFOs; null when they are not there or nobody reads them (agent not
     * running). Non-blocking opens, so a missing agent never stalls the keyboard.
     * 에이전트 FIFO를 엶. 없거나 읽는 쪽이 없으면(에이전트가 안 돎) null. 막히지 않는 열기라 에이전트가 없어도 키보드가 멈추지 않음
     */
    public static SteamTextBridge open(File rootfs) {
        File in = new File(rootfs, GUEST_IN.substring(1)), out = new File(rootfs, GUEST_OUT.substring(1));
        if (!in.exists() || !out.exists()) return null;
        FileDescriptor w = null;
        try {
            // Write end: ENXIO when no reader / 쓰기 끝: 읽는 쪽이 없으면 ENXIO
            w = Os.open(in.getAbsolutePath(), OsConstants.O_WRONLY | OsConstants.O_NONBLOCK | OsConstants.O_CLOEXEC, 0);
            FileDescriptor r = Os.open(out.getAbsolutePath(), OsConstants.O_RDONLY | OsConstants.O_NONBLOCK | OsConstants.O_CLOEXEC, 0);
            return new SteamTextBridge(w, r);
        } catch (ErrnoException e) {
            if (w != null) {
                try {
                    Os.close(w);
                } catch (ErrnoException ignored) {
                }
            }
            return null;
        }
    }

    /** False after a write or read error (agent restarted); the caller opens a new one
     *  쓰기·읽기 오류 뒤에는 false(에이전트가 다시 시작됨). 부르는 쪽이 새로 엶 */
    public boolean usable() {
        return !broken;
    }

    /** request() answers / request()의 답 */
    public static final int OK = 1, NOT_HERE = 0, NO_ANSWER = -1, FAILED = -2;

    /**
     * One request. OK: the agent put the text into a Steam text field. NOT_HERE: no Steam page has a
     * focused text field (type through X instead). NO_ANSWER: no answer within timeoutMs; the agent
     * may still put the text in later, so the caller must not type it again through X (that is
     * how a syllable could appear twice). FAILED: the FIFOs broke (agent gone), X is safe.
     * op: "commit" (final text) or "compose" (the syllable being composed, "" = none).
     * 요청 하나. OK: 에이전트가 Steam 텍스트 칸에 넣음. NOT_HERE: 텍스트 칸에 포커스가 있는 Steam 페이지가 없음(대신 X로 침).
     * NO_ANSWER: timeoutMs 안에 답이 없음. 에이전트가 나중에 넣을 수 있으므로 부르는 쪽은 X로 다시 치면 안 됨(그러면 글자가
     * 두 번 들어갈 수 있음). FAILED: FIFO가 깨짐(에이전트 없음), X로 쳐도 됨.
     * op: "commit"(확정 글자) 또는 "compose"(조합 중인 글자, "" = 없음)
     */
    public int request(String op, String text, long timeoutMs) {
        if (broken) return FAILED;
        int id = ++nextId;
        try {
            byte[] line = (new JSONObject().put("id", id).put("op", op).put("text", text).toString() + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            int off = 0;
            while (off < line.length) {
                try {
                    off += Os.write(wfd, line, off, line.length - off);
                } catch (ErrnoException e) {
                    // FIFO full for a moment: wait a little / FIFO가 잠시 가득 참: 조금 기다림
                    if (e.errno != OsConstants.EAGAIN) throw e;
                    Os.poll(new StructPollfd[]{pollFor(wfd, OsConstants.POLLOUT)}, 20);
                }
            }
            long end = System.nanoTime() + timeoutMs * 1_000_000L;
            byte[] buf = new byte[4096];
            while (true) {
                // Whole answer lines already read / 이미 읽은 완전한 답 줄
                int nl;
                while ((nl = pendingLine.indexOf("\n")) >= 0) {
                    String l = pendingLine.substring(0, nl);
                    pendingLine.delete(0, nl + 1);
                    try {
                        JSONObject a = new JSONObject(l);
                        // Late answers of earlier requests are skipped / 앞 요청의 늦은 답은 건너뜀
                        if (a.optInt("id") == id) return a.optBoolean("ok") ? OK : NOT_HERE;
                    } catch (JSONException ignored) {
                    }
                }
                long left = (end - System.nanoTime()) / 1_000_000L;
                if (left <= 0) return NO_ANSWER;
                Os.poll(new StructPollfd[]{pollFor(rfd, OsConstants.POLLIN)}, (int) Math.min(left, 100));
                try {
                    int n = Os.read(rfd, buf, 0, buf.length);
                    if (n > 0) pendingLine.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                } catch (ErrnoException e) {
                    if (e.errno != OsConstants.EAGAIN) throw e;
                }
            }
        } catch (ErrnoException | JSONException | java.io.InterruptedIOException e) {
            broken = true;
            return FAILED;
        }
    }

    private static StructPollfd pollFor(FileDescriptor fd, int events) {
        StructPollfd p = new StructPollfd();
        p.fd = fd;
        p.events = (short) events;
        return p;
    }

    @Override
    public void close() {
        try {
            Os.close(wfd);
        } catch (ErrnoException ignored) {
        }
        try {
            Os.close(rfd);
        } catch (ErrnoException ignored) {
        }
    }
}
