package app.ezsteam.runtime;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.text.InputType;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Shows an XvfbFramebuffer on screen with OpenGL ES 3: every display frame the whole X screen is
 * uploaded into one texture (glTexSubImage2D straight from the mapped file) and drawn scaled to
 * fit, keeping the aspect ratio. Scaling is done by the GPU (linear filter).
 * Scaling modes: linear, bicubic (Catmull-Rom), bicubic + contrast adaptive sharpening (CAS style),
 * for showing a lower X resolution (e.g. 1280x800) sharper on the larger device screen.
 * Also reports frames per second, average upload time and how many frames had new content.
 * XvfbFramebuffer를 OpenGL ES 3로 화면에 보여 줌: 화면 갱신마다 X 화면 전체를 텍스처 하나로
 * 올리고(매핑된 파일에서 바로 glTexSubImage2D) 비율을 유지해 맞춰 그림. 확대·축소는 GPU가 함(선형 필터).
 * 확대 방식: 선형, 바이큐빅(Catmull-Rom), 바이큐빅 + 대비 적응 선명화(CAS 방식).
 * 낮은 X 해상도(예: 1280x800)를 큰 기기 화면에 더 선명하게 보여 주기 위함.
 * 초당 프레임, 평균 업로드 시간, 내용이 바뀐 프레임 수도 알려 줌
 */
public class FramebufferView extends GLSurfaceView implements GLSurfaceView.Renderer {

    /** Statistics callback, about once per second (render thread) / 통계 콜백, 약 1초마다(렌더 스레드) */
    public interface StatsListener {
        void onStats(float fps, float uploadMs, float changedFps, int width, int height);
    }

    /** Scaling modes / 확대 방식 */
    public static final int SCALE_LINEAR = 0, SCALE_BICUBIC = 1, SCALE_SHARP = 2, SCALE_FSR = 3;

    /** Key events from the soft keyboard's input connection (UI thread)
     *  소프트 키보드 입력 연결에서 오는 키 이벤트(UI 스레드) */
    public interface KeyListener {
        void onKey(android.view.KeyEvent e);
    }

    /** A character that has no key on the keyboard map (Hangul and other scripts), as a code point
     *  키 배치에 키가 없는 글자(한글 등 다른 문자), 코드 포인트로 */
    public interface CharListener {
        void onChar(int codePoint);
    }

    private volatile XvfbFramebuffer fb;
    private volatile int scaleMode = SCALE_SHARP;
    private volatile KeyListener keyListener;
    private volatile CharListener charListener;
    private int uSrc, uMode, uSharp;
    private volatile StatsListener stats;

    private int program, texture, aPos, aTex;
    // FSR 1 (two passes): EASU into an offscreen texture at output size, then RCAS to the screen
    // FSR 1(2단계): EASU로 출력 크기의 화면 밖 텍스처에 그린 뒤 RCAS로 화면에 그림
    private int easuProg, rcasProg, fsrFbo, fsrTex, fsrW, fsrH;
    /** FSR to a middle size (e.g. 1920x1200), then a plain linear scale to the display; 0 = FSR to
     *  the display size. Second offscreen target and the copy program for that last step.
     *  중간 크기(예: 1920x1200)까지 FSR, 그다음 화면까지 단순 선형 확대. 0 = 화면 크기까지 FSR.
     *  그 마지막 단계용 두 번째 화면 밖 대상과 복사 프로그램 */
    private volatile int fsrTargetW, fsrTargetH;
    private int fsrFbo2, fsrTex2, fsrW2, fsrH2, copyProg, cpPos, cpTex;
    private int eaPos, eaTex, eSrc, raPos, raTex, rOut, rCon;
    /** FSR shader or offscreen setup failed (that mode then draws like bicubic + sharpen)
     *  FSR 셰이더나 화면 밖 버퍼 준비가 실패함(그때 그 모드는 바이큐빅+선명처럼 그림) */
    private volatile boolean fsrFailed;
    private int texW, texH;
    private int viewW, viewH;
    /** Area of the view that shows the X screen (letterbox) / X 화면이 그려지는 뷰 영역(레터박스) */
    private volatile int dstX, dstY, dstW, dstH;

    private final FloatBuffer quad;
    private long statStart;
    private int statFrames, statChanged;
    private long statUploadNs;
    private int[] lastSample = new int[0];

    public FramebufferView(Context ctx) {
        super(ctx);
        setEGLContextClientVersion(3);
        setRenderer(this);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        // Focusable so key events and the soft keyboard reach this view
        // 키 이벤트와 소프트 키보드가 이 뷰로 오도록 포커스 가능하게 함
        setFocusable(true);
        setFocusableInTouchMode(true);

        // Full-screen quad: x, y, u, v (v = 0 is the top row of the X screen)
        // 화면 전체 사각형: x, y, u, v(v = 0이 X 화면 맨 윗줄)
        float[] q = {-1, 1, 0, 0, -1, -1, 0, 1, 1, 1, 1, 0, 1, -1, 1, 1};
        quad = ByteBuffer.allocateDirect(q.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(q).position(0);
    }

    /** Set or clear the screen to show / 보여 줄 화면을 지정하거나 지움 */
    public void setFramebuffer(XvfbFramebuffer f) {
        fb = f;
    }

    public void setStatsListener(StatsListener l) {
        stats = l;
    }

    /** Choose the scaling mode (SCALE_*) / 확대 방식 선택(SCALE_*) */
    public void setScaleMode(int m) {
        scaleMode = m;
    }

    /** FSR RCAS sharpness in stops (0 strongest .. 2 weak; FSR's usual default 0.2), or -1 for none
     *  FSR RCAS 선명도 단(0 가장 강함 .. 2 약함, FSR 보통 기본값 0.2), -1이면 선명화 없음 */
    private volatile float fsrSharpness = 0.2f;

    public void setFsrSharpness(float stops) {
        fsrSharpness = stops;
    }

    /**
     * Size FSR scales up to before the last plain linear step; 0 = the display size. FSR 1 is made
     * for up to 2x (its Performance mode); 1280x800 to a 3040-pixel panel is 2.4x, so 1920x1200
     * (1.5x, its Quality mode) and then a small linear step can be compared (user request
     * 2026-10-24). Kept to the display's aspect ratio inside the given box.
     * 마지막 단순 선형 확대 전까지 FSR로 키울 크기. 0 = 화면 크기. FSR 1은 2배(Performance 모드)까지를
     * 위한 것임. 1280x800 → 가로 3040화소 화면은 2.4배라, 1920x1200(1.5배, Quality 모드)까지 FSR 뒤 작은
     * 선형 확대와 비교할 수 있게 함(사용자 요청 2026-10-24). 주어진 상자 안에서 화면 비율을 따름
     */
    public void setFsrTarget(int w, int h) {
        fsrTargetW = Math.max(0, w);
        fsrTargetH = Math.max(0, h);
    }

    public int getScaleMode() {
        return scaleMode;
    }

    public void setKeyListener(KeyListener l) {
        keyListener = l;
    }

    public void setCharListener(CharListener l) {
        charListener = l;
    }

    /**
     * Convert a view position to X screen coordinates (clamped), or null before the first frame.
     * 뷰 좌표를 X 화면 좌표로 바꿈(범위 안으로 제한). 첫 프레임 전에는 null
     */
    public int[] toScreen(float x, float y) {
        XvfbFramebuffer f = fb;
        if (f == null || dstW <= 0 || dstH <= 0) return null;
        int sx = (int) ((x - dstX) * f.width / dstW);
        int sy = (int) ((y - dstY) * f.height / dstH);
        sx = Math.max(0, Math.min(f.width - 1, sx));
        sy = Math.max(0, Math.min(f.height - 1, sy));
        return new int[]{sx, sy};
    }

    // ---- soft keyboard: letters typed directly, Korean (and other composed scripts) sent when a
    //      syllable is finished
    // ---- 소프트 키보드: 영문 등은 바로 보내고, 한글(조합하는 문자)은 글자 하나가 완성되면 보냄
    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    /**
     * Text field without suggestions: with TYPE_NULL keyboards offered no Korean (only English came
     * out, user report 2026-10-22). NO_SUGGESTIONS keeps Latin letters going out key by key; Korean
     * keyboards compose a syllable and commit it. No enter action, so Enter stays a key.
     * 추천 없는 텍스트 칸: TYPE_NULL에서는 키보드가 한글을 주지 않았음(영문만 입력됨, 사용자 제보
     * 2026-10-22). NO_SUGGESTIONS로 영문은 한 글자씩 바로 나가고, 한글 키보드는 글자를 조합해 확정함.
     * 엔터 동작 없음으로 Enter는 키로 남음
     */
    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_ENTER_ACTION | EditorInfo.IME_ACTION_NONE;
        return new KeyInputConnection();
    }

    /**
     * Turns what the soft keyboard sends into key events and characters. A syllable being composed
     * (setComposingText) is kept here and sent once it is committed or finished; the X side has no
     * input method, so a half-built syllable cannot be shown there.
     * 소프트 키보드가 보내는 것을 키 이벤트와 글자로 바꿈. 조합 중인 글자(setComposingText)는 여기에
     * 두었다가 확정·완료될 때 보냄. X 쪽에는 입력기가 없어 조합 중인 글자를 보여 줄 수 없음
     */
    private final class KeyInputConnection extends BaseInputConnection {
        /** Syllable being composed, not sent yet / 조합 중이라 아직 보내지 않은 글자 */
        private String composing = "";

        KeyInputConnection() {
            super(FramebufferView.this, false);
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            // The committed text replaces the composing one / 확정된 글자가 조합 중인 글자를 대신함
            composing = "";
            sendChars(text);
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            composing = text == null ? "" : text.toString();
            return true;
        }

        @Override
        public boolean finishComposingText() {
            // Keyboard closed or composition ended: send what was composed / 키보드 닫힘·조합 끝: 조합한 것을 보냄
            String c = composing;
            composing = "";
            sendChars(c);
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int before, int after) {
            for (int i = 0; i < Math.max(1, before); i++) {
                deliver(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL));
                deliver(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL));
            }
            return true;
        }

        @Override
        public boolean performEditorAction(int actionCode) {
            // Some keyboards still send Enter as an action / 일부 키보드는 Enter를 여전히 동작으로 보냄
            deliver(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER));
            deliver(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER));
            return true;
        }

        @Override
        public boolean sendKeyEvent(android.view.KeyEvent e) {
            // A key pressed while composing ends the composition first / 조합 중 누른 키는 조합을 먼저 끝냄
            if (!composing.isEmpty() && e.getAction() == android.view.KeyEvent.ACTION_DOWN) finishComposingText();
            deliver(e);
            return true;
        }
    }

    /**
     * Text to input: characters the virtual keyboard map knows go out as key events, the rest
     * (Hangul syllables and jamo, other scripts) as code points to the character listener.
     * 텍스트 → 입력: 가상 키보드 배치에 있는 글자는 키 이벤트로, 나머지(한글 음절·자모, 다른 문자)는
     * 코드 포인트로 글자 리스너에 보냄
     */
    public void sendChars(CharSequence text) {
        if (text == null || text.length() == 0) return;
        android.view.KeyCharacterMap map = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD);
        String t = text.toString();
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            i += Character.charCount(cp);
            android.view.KeyEvent[] evs = cp < 0x10000 ? map.getEvents(new char[]{(char) cp}) : null;
            if (evs != null) {
                for (android.view.KeyEvent e : evs) deliver(e);
            } else {
                CharListener l = charListener;
                if (l != null) l.onChar(cp);
            }
        }
    }

    private void deliver(android.view.KeyEvent e) {
        KeyListener l = keyListener;
        if (l != null) l.onKey(e);
    }

    // ---- renderer / 렌더러

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        String vs = "attribute vec2 aPos; attribute vec2 aTex; varying vec2 vTex;\n"
                + "void main() { gl_Position = vec4(aPos, 0.0, 1.0); vTex = aTex; }\n";
        // Pixels are B G R X bytes uploaded as RGBA: swap red and blue, ignore X
        // 픽셀은 B G R X 바이트를 RGBA로 올린 것이므로 빨강·파랑을 바꾸고 X는 무시함
        // uMode 0: linear. 1: Catmull-Rom bicubic from 9 linear taps. 2: bicubic + contrast adaptive
        // sharpening on the 4 source neighbours (weights as in AMD FidelityFX CAS, MIT)
        // uMode 0: 선형. 1: 선형 샘플 9개로 만든 Catmull-Rom 바이큐빅. 2: 바이큐빅 + 원본 이웃 4개로
        // 대비 적응 선명화(가중치는 AMD FidelityFX CAS 방식, MIT)
        String fs = "precision highp float; uniform sampler2D uTex; uniform vec2 uSrc; uniform int uMode;\n"
                + "uniform float uSharp; varying vec2 vTex;\n"
                + "vec3 px(vec2 p) { return texture2D(uTex, p).bgr; }\n"
                + "vec3 bicubic(vec2 uv) {\n"
                + "  vec2 sp = uv * uSrc; vec2 t1 = floor(sp - 0.5) + 0.5; vec2 f = sp - t1;\n"
                + "  vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f)); vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);\n"
                + "  vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f)); vec2 w3 = f * f * (-0.5 + 0.5 * f);\n"
                + "  vec2 w12 = w1 + w2; vec2 t0 = (t1 - 1.0) / uSrc; vec2 t3 = (t1 + 2.0) / uSrc;\n"
                + "  vec2 t12 = (t1 + w2 / w12) / uSrc;\n"
                + "  vec3 r = px(vec2(t0.x, t0.y)) * w0.x * w0.y + px(vec2(t12.x, t0.y)) * w12.x * w0.y\n"
                + "    + px(vec2(t3.x, t0.y)) * w3.x * w0.y + px(vec2(t0.x, t12.y)) * w0.x * w12.y\n"
                + "    + px(t12) * w12.x * w12.y + px(vec2(t3.x, t12.y)) * w3.x * w12.y\n"
                + "    + px(vec2(t0.x, t3.y)) * w0.x * w3.y + px(vec2(t12.x, t3.y)) * w12.x * w3.y\n"
                + "    + px(vec2(t3.x, t3.y)) * w3.x * w3.y;\n"
                + "  return clamp(r, 0.0, 1.0); }\n"
                + "void main() {\n"
                + "  if (uMode == 0) { gl_FragColor = vec4(px(vTex), 1.0); return; }\n"
                + "  vec3 c = bicubic(vTex);\n"
                + "  if (uMode == 2) {\n"
                + "    vec2 t = 1.0 / uSrc;\n"
                + "    vec3 n = px(vTex - vec2(0.0, t.y)); vec3 s = px(vTex + vec2(0.0, t.y));\n"
                + "    vec3 e = px(vTex + vec2(t.x, 0.0)); vec3 w = px(vTex - vec2(t.x, 0.0));\n"
                + "    vec3 mn = min(c, min(min(n, s), min(e, w))); vec3 mx = max(c, max(max(n, s), max(e, w)));\n"
                + "    vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(1e-4)), 0.0, 1.0));\n"
                + "    vec3 wt = amp * (-1.0 / mix(8.0, 5.0, uSharp));\n"
                + "    c = clamp((c + wt * (n + s + e + w)) / (1.0 + 4.0 * wt), 0.0, 1.0);\n"
                + "  }\n"
                + "  gl_FragColor = vec4(c, 1.0); }\n";
        program = link(vs, fs);
        if (program == 0) {
            // Scaling shader rejected by the driver: fall back to plain linear output
            // 드라이버가 확대 셰이더를 거부하면 단순 선형 출력으로 대체함
            program = link(vs, "precision mediump float; uniform sampler2D uTex; varying vec2 vTex;\n"
                    + "void main() { gl_FragColor = vec4(texture2D(uTex, vTex).bgr, 1.0); }\n");
        }
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aTex = GLES20.glGetAttribLocation(program, "aTex");
        linkFsr(vs);
        uSrc = GLES20.glGetUniformLocation(program, "uSrc");
        uMode = GLES20.glGetUniformLocation(program, "uMode");
        uSharp = GLES20.glGetUniformLocation(program, "uSharp");

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        texture = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        texW = texH = 0;
        statStart = System.nanoTime();
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        viewW = w;
        viewH = h;
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        GLES20.glViewport(0, 0, viewW, viewH);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        XvfbFramebuffer f = fb;
        if (f == null) return;

        // (Re)allocate the texture when the X screen size changes / X 화면 크기가 바뀌면 텍스처를 다시 잡음
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        if (texW != f.width || texH != f.height) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, f.width, f.height, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            texW = f.width;
            texH = f.height;
        }

        // Upload the whole screen; row length covers padded rows / 화면 전체 업로드. 줄 길이로 줄 끝 여백 처리
        ByteBuffer px = f.pixels();
        boolean changed = sampleChanged(px.asIntBuffer(), f);
        long t0 = System.nanoTime();
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, f.bytesPerLine / 4);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, f.width, f.height,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0);
        long t1 = System.nanoTime();

        // Fit with the aspect ratio kept (black bars) / 비율을 유지해 맞춤(남는 곳은 검은 띠)
        float s = Math.min((float) viewW / f.width, (float) viewH / f.height);
        int w = Math.round(f.width * s), h = Math.round(f.height * s);
        int x = (viewW - w) / 2, y = (viewH - h) / 2;
        dstX = x;
        dstY = y;
        dstW = w;
        dstH = h;
        // GL viewport origin is bottom-left / GL 뷰포트 원점은 왼쪽 아래
        GLES20.glViewport(x, viewH - y - h, w, h);

        if (scaleMode == SCALE_FSR && drawFsr(f, x, viewH - y - h, w, h)) {
            // Drawn by the two FSR passes / FSR 2단계로 그림
        } else {
            GLES20.glUseProgram(program);
            GLES20.glUniform2f(uSrc, f.width, f.height);
            // FSR not available: same look as bicubic + sharpen / FSR을 못 쓰면 바이큐빅+선명과 같게 그림
            GLES20.glUniform1i(uMode, scaleMode == SCALE_FSR ? SCALE_SHARP : scaleMode);
            GLES20.glUniform1f(uSharp, 0.5f);
            drawQuad(aPos, aTex);
        }

        // Statistics / 통계
        statFrames++;
        statUploadNs += t1 - t0;
        if (changed) statChanged++;
        long now = System.nanoTime();
        if (now - statStart >= 1_000_000_000L) {
            float sec = (now - statStart) / 1e9f;
            StatsListener l = stats;
            if (l != null) {
                l.onStats(statFrames / sec, statUploadNs / 1e6f / Math.max(1, statFrames),
                        statChanged / sec, f.width, f.height);
            }
            statStart = now;
            statFrames = statChanged = 0;
            statUploadNs = 0;
        }
    }

    /**
     * Compare a 32 x 18 grid of pixels with the previous frame: a cheap "did the screen change" test
     * (counts frames with new content, e.g. new vkcube frames, up to the display rate).
     * 32 x 18 격자 픽셀을 이전 프레임과 비교함: 저렴한 "화면이 바뀌었나" 확인
     * (vkcube 새 프레임처럼 내용이 바뀐 프레임 수를 셈, 최대 화면 갱신 속도까지)
     */
    private boolean sampleChanged(IntBuffer p, XvfbFramebuffer f) {
        final int gx = 32, gy = 18;
        if (lastSample.length != gx * gy) lastSample = new int[gx * gy];
        boolean changed = false;
        int stride = f.bytesPerLine / 4;
        for (int j = 0; j < gy; j++) {
            int row = (int) ((j + 0.5f) * f.height / gy);
            for (int i = 0; i < gx; i++) {
                int col = (int) ((i + 0.5f) * f.width / gx);
                int v = p.get(row * stride + col);
                int k = j * gx + i;
                if (lastSample[k] != v) {
                    lastSample[k] = v;
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** Last shader error (null when none), for the host's log / 마지막 셰이더 오류(없으면 null), 호스트 로그용 */
    public volatile String shaderError;

    /** Draw the full quad with the given attribute locations / 지정한 속성 위치로 사각형 전체를 그림 */
    private void drawQuad(int pos, int tex) {
        quad.position(0);
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(pos);
        quad.position(2);
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(tex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /**
     * Compile the two FSR 1 passes. Own GLSL ES port of the published AMD FidelityFX Super
     * Resolution 1.0 algorithm (EASU: edge adaptive spatial upsampling, RCAS: robust contrast
     * adaptive sharpening). FidelityFX FSR is MIT licensed, Copyright (c) 2021 Advanced Micro
     * Devices, Inc.; this port keeps the math, not the source layout.
     * FSR 1 두 단계를 컴파일함. 공개된 AMD FidelityFX Super Resolution 1.0 알고리즘(EASU: 경계 적응
     * 공간 확대, RCAS: 대비 적응 선명화)을 GLSL ES로 직접 옮긴 것임. FidelityFX FSR은 MIT 라이선스
     * (Copyright (c) 2021 Advanced Micro Devices, Inc.)이며 계산 방식만 따르고 원본 소스 구조는 따르지 않음
     */
    private void linkFsr(String vs) {
        fsrFbo = fsrTex = fsrW = fsrH = 0;
        // EASU: 12 source taps around the 2x2 quad f g / j k (b c above, n o below, e i left, h l right).
        // Luma gradients of the quad give an edge direction and length; each tap is weighted by a
        // Lanczos-2-like kernel stretched along the edge, then clamped to the quad's min/max.
        // EASU: 2x2 묶음 f g / j k 주변 원본 12개 표본(위 b c, 아래 n o, 왼쪽 e i, 오른쪽 h l).
        // 묶음의 밝기 기울기로 경계 방향과 길이를 구하고, 경계를 따라 늘인 Lanczos-2 근사 커널로 각 표본에
        // 가중치를 준 뒤 묶음의 최소·최대로 제한함(링잉 방지)
        String easu = "precision highp float; uniform sampler2D uTex; uniform vec2 uSrc; varying vec2 vTex;\n"
                + "vec3 tp(vec2 fp, vec2 o) { return texture2D(uTex, (fp + o + 0.5) / uSrc).bgr; }\n"
                + "float lu(vec3 c) { return c.b * 0.5 + c.r * 0.5 + c.g; }\n"
                // Accumulate direction and length for one quad pixel (up a, left b, centre c, right d, down e)
                // 묶음 픽셀 하나의 방향·길이 누적(위 a, 왼쪽 b, 가운데 c, 오른쪽 d, 아래 e)
                + "void st(inout vec2 dir, inout float len, float w, float a, float b, float c, float d, float e) {\n"
                + "  float lx = max(abs(d - c), abs(c - b)); float dx = d - b;\n"
                + "  lx = clamp(abs(dx) / max(lx, 1e-5), 0.0, 1.0); lx *= lx;\n"
                + "  float ly = max(abs(e - c), abs(c - a)); float dy = e - a;\n"
                + "  ly = clamp(abs(dy) / max(ly, 1e-5), 0.0, 1.0); ly *= ly;\n"
                + "  dir += vec2(dx, dy) * w; len += (lx + ly) * w; }\n"
                // One filtered tap / 필터 표본 하나
                + "void tap(inout vec3 ac, inout float aw, vec2 off, vec2 dir, vec2 len2, float lob, float clp, vec3 c) {\n"
                + "  vec2 v = vec2(off.x * dir.x + off.y * dir.y, -off.x * dir.y + off.y * dir.x) * len2;\n"
                + "  float d2 = min(dot(v, v), clp);\n"
                + "  float wb = 0.4 * d2 - 1.0; float wa = lob * d2 - 1.0; wb *= wb; wa *= wa;\n"
                + "  wb = 1.5625 * wb - 0.5625; float w = wb * wa; ac += c * w; aw += w; }\n"
                + "void main() {\n"
                + "  vec2 pp = vTex * uSrc - 0.5; vec2 fp = floor(pp); pp -= fp;\n"
                + "  vec3 b = tp(fp, vec2(0.0, -1.0)), c = tp(fp, vec2(1.0, -1.0));\n"
                + "  vec3 e = tp(fp, vec2(-1.0, 0.0)), f = tp(fp, vec2(0.0, 0.0)), g = tp(fp, vec2(1.0, 0.0)), h = tp(fp, vec2(2.0, 0.0));\n"
                + "  vec3 i = tp(fp, vec2(-1.0, 1.0)), j = tp(fp, vec2(0.0, 1.0)), k = tp(fp, vec2(1.0, 1.0)), l = tp(fp, vec2(2.0, 1.0));\n"
                + "  vec3 n = tp(fp, vec2(0.0, 2.0)), o = tp(fp, vec2(1.0, 2.0));\n"
                + "  float bL = lu(b), cL = lu(c), eL = lu(e), fL = lu(f), gL = lu(g), hL = lu(h);\n"
                + "  float iL = lu(i), jL = lu(j), kL = lu(k), lL = lu(l), nL = lu(n), oL = lu(o);\n"
                + "  vec2 dir = vec2(0.0); float len = 0.0;\n"
                + "  st(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);\n"
                + "  st(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);\n"
                + "  st(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);\n"
                + "  st(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);\n"
                // Normalise the direction (flat area: axis aligned) / 방향 정규화(평탄한 곳은 축 방향)
                + "  float dr = dot(dir, dir); if (dr < 1.0 / 32768.0) dir = vec2(1.0, 0.0); else dir *= inversesqrt(dr);\n"
                + "  len = len * 0.5; len *= len;\n"
                + "  float stretch = dot(dir, dir) / max(abs(dir.x), abs(dir.y));\n"
                + "  vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);\n"
                + "  float lob = 0.5 + (0.21 - 0.5) * len; float clp = 1.0 / lob;\n"
                + "  vec3 ac = vec3(0.0); float aw = 0.0;\n"
                + "  tap(ac, aw, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, b);\n"
                + "  tap(ac, aw, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, c);\n"
                + "  tap(ac, aw, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, i);\n"
                + "  tap(ac, aw, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, j);\n"
                + "  tap(ac, aw, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, f);\n"
                + "  tap(ac, aw, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, e);\n"
                + "  tap(ac, aw, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, k);\n"
                + "  tap(ac, aw, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, l);\n"
                + "  tap(ac, aw, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, h);\n"
                + "  tap(ac, aw, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, g);\n"
                + "  tap(ac, aw, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, o);\n"
                + "  tap(ac, aw, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, n);\n"
                + "  vec3 mn = min(min(f, g), min(j, k)); vec3 mx = max(max(f, g), max(j, k));\n"
                + "  gl_FragColor = vec4(clamp(ac / max(aw, 1e-5), mn, mx), 1.0); }\n";
        // RCAS: per channel, the largest negative lobe that keeps the cross neighbourhood (b d f h)
        // from clipping, scaled by uCon (2^-sharpness) and lowered on noise; the offscreen texture
        // is upside down relative to the quad, so its rows are read flipped.
        // RCAS: 채널마다 십자 이웃(b d f h)이 잘리지 않는 가장 큰 음수 로브를 구하고 uCon(2^-선명도)을
        // 곱한 뒤 잡음이면 낮춤. 화면 밖 텍스처는 사각형 기준으로 위아래가 뒤집혀 있어 줄을 거꾸로 읽음
        String rcas = "precision highp float; uniform sampler2D uTex; uniform vec2 uOut; uniform float uCon;\n"
                + "varying vec2 vTex;\n"
                + "vec3 tp(vec2 p, vec2 o) { return texture2D(uTex, p + o / uOut).rgb; }\n"
                + "float lu(vec3 c) { return c.b * 0.5 + c.r * 0.5 + c.g; }\n"
                + "void main() {\n"
                + "  vec2 p = vec2(vTex.x, 1.0 - vTex.y);\n"
                + "  vec3 b = tp(p, vec2(0.0, -1.0)), d = tp(p, vec2(-1.0, 0.0)), e = tp(p, vec2(0.0));\n"
                + "  vec3 f = tp(p, vec2(1.0, 0.0)), h = tp(p, vec2(0.0, 1.0));\n"
                + "  vec3 mn4 = min(min(b, d), min(f, h)); vec3 mx4 = max(max(b, d), max(f, h));\n"
                + "  vec3 hitMin = mn4 / max(4.0 * mx4, vec3(1e-5));\n"
                + "  vec3 hitMax = (1.0 - mx4) / min(4.0 * mn4 - 4.0, vec3(-1e-5));\n"
                + "  vec3 lb = max(-hitMin, hitMax);\n"
                + "  float lobe = max(-0.1875, min(max(lb.r, max(lb.g, lb.b)), 0.0)) * uCon;\n"
                // Noise: a lone pixel against a flat cross gets less sharpening / 잡음: 평탄한 십자 속 외딴 점은 덜 선명하게
                + "  float bL = lu(b), dL = lu(d), eL = lu(e), fL = lu(f), hL = lu(h);\n"
                + "  float nz = 0.25 * (bL + dL + fL + hL) - eL;\n"
                + "  float rg = max(max(max(bL, dL), max(eL, fL)), hL) - min(min(min(bL, dL), min(eL, fL)), hL);\n"
                + "  nz = clamp(abs(nz) / max(rg, 1e-5), 0.0, 1.0); lobe *= -0.5 * nz + 1.0;\n"
                + "  gl_FragColor = vec4(clamp((lobe * (b + d + f + h) + e) / (4.0 * lobe + 1.0), 0.0, 1.0), 1.0); }\n";
        String before = shaderError;
        easuProg = link(vs, easu);
        rcasProg = link(vs, rcas);
        fsrFailed = easuProg == 0 || rcasProg == 0;
        if (fsrFailed) {
            // Keep the main shader's own error text first / 기본 셰이더 오류 문구를 앞에 둠
            shaderError = (before == null ? "" : before + " / ") + "fsr: " + shaderError;
            return;
        }
        eaPos = GLES20.glGetAttribLocation(easuProg, "aPos");
        eaTex = GLES20.glGetAttribLocation(easuProg, "aTex");
        eSrc = GLES20.glGetUniformLocation(easuProg, "uSrc");
        raPos = GLES20.glGetAttribLocation(rcasProg, "aPos");
        raTex = GLES20.glGetAttribLocation(rcasProg, "aTex");
        rOut = GLES20.glGetUniformLocation(rcasProg, "uOut");
        rCon = GLES20.glGetUniformLocation(rcasProg, "uCon");
        // Plain copy with linear filtering for the step after a middle-size FSR (colours already
        // in order). Upside down on purpose: every pass that reads an offscreen texture with the
        // shared quad turns the picture over, so with this third pass it came out upside down
        // (user report 2026-10-24); flipping here makes it an even number again.
        // 중간 크기 FSR 뒤 단계용 선형 필터 단순 복사(색 순서는 이미 맞음). 일부러 뒤집음: 공용 사각형으로
        // 화면 밖 텍스처를 읽는 단계마다 그림이 뒤집혀, 이 세 번째 단계가 더해지자 위아래가 뒤집혔음(사용자
        // 제보 2026-10-24). 여기서 뒤집어 다시 짝수 번이 되게 함
        copyProg = link(vs, "precision mediump float; uniform sampler2D uTex; varying vec2 vTex;\n"
                + "void main() { gl_FragColor = vec4(texture2D(uTex, vec2(vTex.x, 1.0 - vTex.y)).rgb, 1.0); }\n");
        if (copyProg != 0) {
            cpPos = GLES20.glGetAttribLocation(copyProg, "aPos");
            cpTex = GLES20.glGetAttribLocation(copyProg, "aTex");
        }
        fsrFbo2 = fsrTex2 = fsrW2 = fsrH2 = 0;
    }

    /** Texture + framebuffer of a size (filter as given); 0 on failure / 크기만큼의 텍스처 + 프레임버퍼(필터 지정). 실패하면 0 */
    private int[] makeTarget(int tex, int fbo, int w, int h, int filter) {
        int[] a = new int[1];
        if (tex == 0) {
            GLES20.glGenTextures(1, a, 0);
            tex = a[0];
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        if (fbo == 0) {
            GLES20.glGenFramebuffers(1, a, 0);
            fbo = a[0];
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, tex, 0);
        int st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (st != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            shaderError = (shaderError == null ? "" : shaderError + " / ") + "fsr fbo: 0x" + Integer.toHexString(st);
            return null;
        }
        return new int[]{tex, fbo};
    }

    /**
     * Draw with FSR 1 into the given screen area; false when FSR is not usable (caller draws the
     * normal way). The offscreen texture follows the output size.
     * 주어진 화면 영역에 FSR 1로 그림. FSR을 못 쓰면 false(호출한 쪽이 보통 방식으로 그림).
     * 화면 밖 텍스처는 출력 크기를 따름
     */
    private boolean drawFsr(XvfbFramebuffer f, int vx, int vy, int w, int h) {
        if (fsrFailed || w <= 0 || h <= 0) return false;
        // FSR output size: the display area, or the middle size kept to the area's aspect ratio
        // FSR 출력 크기: 화면 영역, 또는 영역 비율을 따른 중간 크기
        int tw = w, th = h;
        int bw = fsrTargetW, bh = fsrTargetH;
        if (bw > 0 && bh > 0 && copyProg != 0) {
            float k = Math.min(bw / (float) w, bh / (float) h);
            if (k < 1f) {
                tw = Math.max(1, Math.round(w * k));
                th = Math.max(1, Math.round(h * k));
            }
        }
        boolean middle = tw != w || th != h;
        if (fsrW != tw || fsrH != th) {
            // Nearest: RCAS reads exact pixels / 최근접: RCAS가 픽셀을 정확히 읽음
            int[] t = makeTarget(fsrTex, fsrFbo, tw, th, GLES20.GL_NEAREST);
            if (t == null) {
                fsrFailed = true;
                return false;
            }
            fsrTex = t[0];
            fsrFbo = t[1];
            fsrW = tw;
            fsrH = th;
        }
        if (middle && (fsrW2 != tw || fsrH2 != th)) {
            // Linear: the last step scales this up / 선형: 마지막 단계가 이것을 키움
            int[] t = makeTarget(fsrTex2, fsrFbo2, tw, th, GLES20.GL_LINEAR);
            if (t == null) {
                fsrFailed = true;
                return false;
            }
            fsrTex2 = t[0];
            fsrFbo2 = t[1];
            fsrW2 = tw;
            fsrH2 = th;
        }
        // Pass 1: EASU, X screen → offscreen texture / 1단계: EASU, X 화면 → 화면 밖 텍스처
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fsrFbo);
        GLES20.glViewport(0, 0, tw, th);
        GLES20.glUseProgram(easuProg);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform2f(eSrc, f.width, f.height);
        drawQuad(eaPos, eaTex);
        // Pass 2: RCAS, to the screen area or to the second offscreen texture
        // 2단계: RCAS, 화면 영역 또는 두 번째 화면 밖 텍스처로
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, middle ? fsrFbo2 : 0);
        if (middle) GLES20.glViewport(0, 0, tw, th);
        else GLES20.glViewport(vx, vy, w, h);
        GLES20.glUseProgram(rcasProg);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fsrTex);
        GLES20.glUniform2f(rOut, tw, th);
        // Sharpness in stops: 0 = strongest, each stop halves it; off = 0 (EASU only)
        // 선명도 단: 0 = 가장 강함, 1단마다 절반. 끔 = 0(EASU 확대만)
        float stops = fsrSharpness;
        GLES20.glUniform1f(rCon, stops < 0 ? 0f : (float) Math.pow(2.0, -stops));
        drawQuad(raPos, raTex);
        if (middle) {
            // Pass 3: plain linear scale to the screen area / 3단계: 화면 영역까지 단순 선형 확대
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(vx, vy, w, h);
            GLES20.glUseProgram(copyProg);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fsrTex2);
            drawQuad(cpPos, cpTex);
        }
        return true;
    }

    /** Compile and link a program; 0 and shaderError set on failure
     *  프로그램 컴파일·링크. 실패하면 0을 돌려주고 shaderError를 채움 */
    private int link(String vs, String fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            shaderError = (shaderError == null ? "" : shaderError + " / ") + GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            return 0;
        }
        return p;
    }

    private int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) shaderError = "shader: " + GLES20.glGetShaderInfoLog(s);
        return s;
    }
}
