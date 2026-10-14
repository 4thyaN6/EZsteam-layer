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
    public static final int SCALE_LINEAR = 0, SCALE_BICUBIC = 1, SCALE_SHARP = 2;

    /** Key events from the soft keyboard's input connection (UI thread)
     *  소프트 키보드 입력 연결에서 오는 키 이벤트(UI 스레드) */
    public interface KeyListener {
        void onKey(android.view.KeyEvent e);
    }

    private volatile XvfbFramebuffer fb;
    private volatile int scaleMode = SCALE_SHARP;
    private volatile KeyListener keyListener;
    private int uSrc, uMode, uSharp;
    private volatile StatsListener stats;

    private int program, texture, aPos, aTex;
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

    public int getScaleMode() {
        return scaleMode;
    }

    public void setKeyListener(KeyListener l) {
        keyListener = l;
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

    // ---- soft keyboard: ask the input method for raw key events (no text composition)
    // ---- 소프트 키보드: 입력기에 글자 조합 없이 키 이벤트를 그대로 보내도록 요청함
    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_NULL;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        return new KeyInputConnection();
    }

    /**
     * Turns what the soft keyboard sends into key events: some keyboards send letters as text
     * (commitText) instead of key events even with TYPE_NULL.
     * 소프트 키보드가 보내는 것을 키 이벤트로 바꿈: 일부 키보드는 TYPE_NULL이어도 글자를 키
     * 이벤트 대신 텍스트(commitText)로 보냄
     */
    private final class KeyInputConnection extends BaseInputConnection {
        KeyInputConnection() {
            super(FramebufferView.this, false);
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            sendChars(text);
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            // No composition: keys go out as typed / 조합하지 않음: 입력한 대로 바로 보냄
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
        public boolean sendKeyEvent(android.view.KeyEvent e) {
            deliver(e);
            return true;
        }
    }

    /** Text to key events with the virtual keyboard character map / 가상 키보드 문자 배치로 텍스트 → 키 이벤트 */
    public void sendChars(CharSequence text) {
        if (text == null || text.length() == 0) return;
        android.view.KeyCharacterMap map = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD);
        android.view.KeyEvent[] evs = map.getEvents(text.toString().toCharArray());
        if (evs == null) return;
        for (android.view.KeyEvent e : evs) deliver(e);
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

        GLES20.glUseProgram(program);
        GLES20.glUniform2f(uSrc, f.width, f.height);
        GLES20.glUniform1i(uMode, scaleMode);
        GLES20.glUniform1f(uSharp, 0.5f);
        quad.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aPos);
        quad.position(2);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

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
