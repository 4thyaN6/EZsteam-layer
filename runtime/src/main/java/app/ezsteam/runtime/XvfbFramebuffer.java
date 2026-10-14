package app.ezsteam.runtime;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Read-only view of the screen memory of an Xvfb started with "-fbdir" (file "Xvfb_screen0").
 * The file is an XWD dump whose pixel part is the live frame buffer: Xvfb maps it shared and
 * draws into it, so mapping the same file here shows every change without copying through X.
 * Pixels are 32 bits, little endian, bytes B G R X (depth 24).
 * "-fbdir"로 띄운 Xvfb의 화면 메모리(파일 "Xvfb_screen0")를 읽기 전용으로 보여 줌.
 * 이 파일은 XWD 덤프이며 픽셀 부분이 실제 화면 버퍼임. Xvfb가 공유 매핑해 직접 그리므로
 * 같은 파일을 여기서 매핑하면 X를 거친 복사 없이 모든 변경이 보임.
 * 픽셀은 32비트 리틀 엔디언, 바이트 순서 B G R X(depth 24)
 */
public final class XvfbFramebuffer implements Closeable {

    public final int width;
    public final int height;
    /** Bytes per row (may be larger than width * 4) / 한 줄 바이트 수(width * 4보다 클 수 있음) */
    public final int bytesPerLine;
    public final int bitsPerPixel;

    private final RandomAccessFile file;
    private final MappedByteBuffer map;
    private final int pixelOffset;

    private XvfbFramebuffer(RandomAccessFile file, MappedByteBuffer map, int w, int h, int bpl, int bpp, int off) {
        this.file = file;
        this.map = map;
        this.width = w;
        this.height = h;
        this.bytesPerLine = bpl;
        this.bitsPerPixel = bpp;
        this.pixelOffset = off;
    }

    /**
     * Map the screen file and read its XWD header.
     * 화면 파일을 매핑하고 XWD 헤더를 읽음
     */
    public static XvfbFramebuffer open(File f) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            long size = raf.length();
            if (size < 100) throw new IOException("screen file too small: " + size);
            MappedByteBuffer m = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, size);

            // XWD header: 25 CARD32 fields, written most significant byte first; fall back to
            // little endian if the values do not make sense
            // XWD 헤더: CARD32 25개, 큰 바이트 먼저 기록됨. 값이 맞지 않으면 리틀 엔디언으로 다시 읽음
            Header h = Header.read(m, ByteOrder.BIG_ENDIAN);
            if (!h.valid(size)) h = Header.read(m, ByteOrder.LITTLE_ENDIAN);
            if (!h.valid(size)) {
                throw new IOException("unexpected XWD header: size=" + h.headerSize + " " + h.width + "x"
                        + h.height + " bpp=" + h.bpp + " bpl=" + h.bpl + " ncolors=" + h.ncolors);
            }
            if (h.bpp != 32) throw new IOException("only 32 bits per pixel supported, got " + h.bpp);
            int off = h.headerSize + h.ncolors * 12;
            return new XvfbFramebuffer(raf, m, h.width, h.height, h.bpl, h.bpp, off);
        } catch (IOException | RuntimeException e) {
            raf.close();
            throw e;
        }
    }

    /**
     * Pixel memory starting at row 0 (a new direct buffer view each call; no copy).
     * 0번 줄부터 시작하는 픽셀 메모리(호출마다 새 direct 버퍼 뷰, 복사 없음)
     */
    public ByteBuffer pixels() {
        ByteBuffer b = map.duplicate();
        b.position(pixelOffset);
        ByteBuffer s = b.slice();
        s.order(ByteOrder.LITTLE_ENDIAN);
        return s;
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    /** Fields of the XWD header that matter here / 여기서 쓰는 XWD 헤더 항목 */
    private static final class Header {
        int headerSize, version, format, width, height, bpp, bpl, ncolors;

        static Header read(ByteBuffer m, ByteOrder order) {
            ByteBuffer b = m.duplicate().order(order);
            Header h = new Header();
            h.headerSize = b.getInt(0);
            h.version = b.getInt(4);
            h.format = b.getInt(8);
            h.width = b.getInt(16);
            h.height = b.getInt(20);
            h.bpp = b.getInt(44);
            h.bpl = b.getInt(48);
            h.ncolors = b.getInt(76);
            return h;
        }

        /** Version 7, ZPixmap (2), and the pixels fit in the file / 버전 7, ZPixmap(2), 픽셀이 파일 안에 들어감 */
        boolean valid(long fileSize) {
            if (version != 7 || format != 2) return false;
            if (headerSize < 100 || headerSize > 4096 || ncolors < 0 || ncolors > 65536) return false;
            if (width <= 0 || height <= 0 || width > 16384 || height > 16384) return false;
            if (bpl < width * (bpp / 8)) return false;
            long need = (long) headerSize + ncolors * 12L + (long) bpl * height;
            return need <= fileSize;
        }
    }
}
