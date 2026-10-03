package dock.viewer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Deterministic fixture bytes for the viewer tests. Everything is generated
 * in place (including a hand-written GIF89a, ICO, and PSD) so the suite
 * never depends on the network; the one committed binary is the WebP sample.
 */
final class Fixtures {

    private Fixtures() {}

    // ---- ImageIO formats ----

    static byte[] encode(BufferedImage img, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!javax.imageio.ImageIO.write(img, format, out))
                throw new IllegalStateException("no writer for " + format);
        } catch (IOException e) {
            throw unchecked(e);
        }
        return out.toByteArray();
    }

    /** A width×height image filled with one opaque color. */
    static BufferedImage solid(int width, int height, int rgb) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) img.setRGB(x, y, 0xFF000000 | rgb);
        return img;
    }

    static byte[] resource(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) throw new IllegalStateException("missing fixture " + name);
            return in.readAllBytes();
        } catch (IOException e) {
            throw unchecked(e);
        }
    }

    // ---- GIF89a ----

    /** One animation frame: a solid patch of a palette color. */
    record GifFrame(int left, int top, int width, int height, int color,
                    int delayHundredths, String disposal) {}

    /**
     * Writes a GIF89a over a fixed 4-color global palette
     * (0=red, 1=blue, 2=green, 3=black). The LZW stream emits a clear code
     * before every literal — the table never grows, so all codes stay at
     * the initial width: tiny, valid, and deterministic.
     */
    static byte[] gif(int screenW, int screenH, GifFrame... frames) {
        ByteOut o = new ByteOut();
        o.ascii("GIF89a");
        o.u16(screenW);
        o.u16(screenH);
        o.u8(0x81);   // global color table, 2^(1+1) = 4 entries
        o.u8(0);      // background index
        o.u8(0);      // aspect
        o.ascii("\u00FF\u0000\u0000");   // 0: red
        o.ascii("\u0000\u0000\u00FF");   // 1: blue
        o.ascii("\u0000\u00FF\u0000");   // 2: green
        o.ascii("\u0000\u0000\u0000");   // 3: black
        // NETSCAPE2.0: loop forever
        o.u8(0x21); o.u8(0xFF); o.u8(11); o.ascii("NETSCAPE2.0");
        o.u8(3); o.u8(1); o.u16(0); o.u8(0);
        for (GifFrame f : frames) {
            o.u8(0x21); o.u8(0xF9); o.u8(4);
            o.u8(disposalCode(f.disposal()) << 2);
            o.u16(f.delayHundredths());
            o.u8(0);   // transparent index (flag not set)
            o.u8(0);   // block terminator
            o.u8(0x2C);
            o.u16(f.left()); o.u16(f.top()); o.u16(f.width()); o.u16(f.height());
            o.u8(0);   // no local table, no interlace
            o.u8(2);   // LZW minimum code size
            lzwSubBlocks(o, f);
        }
        o.u8(0x3B);
        return o.bytes();
    }

    private static int disposalCode(String disposal) {
        return switch (disposal) {
            case "background" -> 2;
            case "previous" -> 3;
            case "doNotDispose" -> 1;
            default -> 0;
        };
    }

    private static void lzwSubBlocks(ByteOut o, GifFrame f) {
        BitWriter bits = new BitWriter();
        for (int i = 0; i < f.width() * f.height(); i++) {
            bits.code(4, 3);            // clear
            bits.code(f.color(), 3);    // literal
        }
        bits.code(5, 3);                // end of information
        bits.flush();
        byte[] data = bits.bytes();
        for (int off = 0; off < data.length; off += 255) {
            int len = Math.min(255, data.length - off);
            o.u8(len);
            o.raw(data, off, len);
        }
        o.u8(0);   // end of image data
    }

    private static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int bits;
        private int count;

        void code(int code, int width) {
            bits |= code << count;
            count += width;
            while (count >= 8) {
                out.write(bits & 0xFF);
                bits >>>= 8;
                count -= 8;
            }
        }

        void flush() {
            if (count > 0) out.write(bits & 0xFF);
            bits = 0;
            count = 0;
        }

        byte[] bytes() { return out.toByteArray(); }
    }

    // ---- ICO ----

    /** A one-image ICO: 32-bit BGRA pixels plus an empty AND mask. */
    static byte[] ico(int w, int h, int[] argb) {
        int andStride = ((w + 31) / 32) * 4;
        int xorSize = w * 4 * h;
        int andSize = andStride * h;
        ByteOut o = new ByteOut();
        o.u16(0); o.u16(1); o.u16(1);   // reserved, icon type, one image
        o.u8(w); o.u8(h); o.u8(0); o.u8(0);
        o.u16(1); o.u16(32);
        o.u32(40 + xorSize + andSize); o.u32(6 + 16);
        o.u32(40); o.u32(w); o.u32(h * 2);
        o.u16(1); o.u16(32); o.u32(0); o.u32(xorSize + andSize);
        o.u32(0); o.u32(0); o.u32(0); o.u32(0);
        for (int y = h - 1; y >= 0; y--) {          // bottom-up
            for (int x = 0; x < w; x++) {
                int p = argb[y * w + x];
                o.u8((p >> 16) & 0xFF);             // B
                o.u8((p >> 8) & 0xFF);              // G
                o.u8(p & 0xFF);                     // R
                o.u8(0xFF);                         // A
            }
        }
        for (int i = 0; i < andSize; i++) o.u8(0);  // opaque mask
        return o.bytes();
    }

    // ---- PSD ----

    /** A minimal version-1 PSD: 8-bit RGB, no resources, no compression
     *  (planar R, G, B rows). PSD is big-endian, unlike GIF/ICO. */
    static byte[] psd(int w, int h, int[] argb) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] head = "8BPS".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        out.writeBytes(head);
        be16(out, 1);              // version
        for (int i = 0; i < 6; i++) out.write(0);   // reserved
        be16(out, 3);              // channels
        be32(out, h); be32(out, w);
        be16(out, 8);              // depth
        be16(out, 3);              // RGB mode
        be32(out, 0); be32(out, 0); be32(out, 0);    // empty sections
        be16(out, 0);              // raw compression
        for (int shift : new int[] {16, 8, 0}) {
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    out.write((argb[y * w + x] >> shift) & 0xFF);
        }
        return out.toByteArray();
    }

    private static void be16(java.io.ByteArrayOutputStream o, int v) {
        o.write((v >> 8) & 0xFF); o.write(v & 0xFF);
    }

    private static void be32(java.io.ByteArrayOutputStream o, int v) {
        o.write((v >> 24) & 0xFF); o.write((v >> 16) & 0xFF);
        o.write((v >> 8) & 0xFF); o.write(v & 0xFF);
    }

    // ---- little-endian byte sink ----

    private static final class ByteOut {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        void u8(int v) { out.write(v & 0xFF); }
        void u16(int v) { out.write(v & 0xFF); out.write((v >> 8) & 0xFF); }
        void u32(int v) {
            out.write(v & 0xFF); out.write((v >> 8) & 0xFF);
            out.write((v >> 16) & 0xFF); out.write((v >> 24) & 0xFF);
        }
        void ascii(String s) { out.writeBytes(s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)); }
        void raw(byte[] b, int off, int len) { out.write(b, off, len); }
        byte[] bytes() { return out.toByteArray(); }
    }

    private static RuntimeException unchecked(IOException e) {
        return new RuntimeException(e);
    }

    // Palette indices used by the tests, kept beside the writer.
    static final int RED = 0xFF0000;
    static final int BLUE = 0x0000FF;
    static final int GREEN = 0x00FF00;
}
