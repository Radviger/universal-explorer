package dock.kit;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.UIManager;

/**
 * The application mark — the ringed planet glyph in the accent color, on
 * transparency: no background tile, so the taskbar shows the icon itself.
 * Rendered at any size, plus the packaging artifact built from it: a
 * multi-size ICO and PNGs, written by the {@code icon} Gradle task
 * (pure Java; the same render path feeds the window icons at runtime, so
 * the packaged icon can never drift from the in-app one).
 */
public final class AppIcon {

    /** Brand fallback when the theme provides no accent at render time. */
    public static final Color BRAND = new Color(0x7AA2F7);

    private AppIcon() {}

    /** The mark at {@code size}; color comes from the live theme. */
    public static BufferedImage render(int size) {
        Color accent = UIManager.getColor("Dock.accent");
        return render(size, accent != null ? accent : BRAND);
    }

    public static BufferedImage render(int size, Color ink) {
        FontRegistry.install();
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            // The mark rides the diagonal (ring near 45°), so its ink fills
            // ~0.97 x 0.91 of the square — an upright flat ring would cap
            // the height at ~0.57. The size is pushed past the square
            // because uniform icons fill only 85% of the requested size.
            var icon = Glyphs.iconRotated(Glyphs.PLANET, size * 1.14f, () -> ink,
                    Glyphs.PLANET_TILT_DEG);
            icon.paintIcon(null, g2, (size - icon.getIconWidth()) / 2,
                    (size - icon.getIconHeight()) / 2);
        } finally {
            g2.dispose();
        }
        return img;
    }

    /** Window icons in the sizes the taskbar and title bar ask for. */
    public static List<Image> windowImages() {
        int[] sizes = {16, 20, 32, 48};
        List<Image> images = new ArrayList<>(sizes.length);
        for (int size : sizes) images.add(render(size));
        return images;
    }

    public static byte[] png(int size) throws IOException {
        var out = new ByteArrayOutputStream();
        if (!ImageIO.write(render(size, BRAND), "png", out)) {
            throw new IOException("no PNG writer");
        }
        return out.toByteArray();
    }

    /**
     * Multi-size ICO of classic uncompressed DIB frames (Vista-era 32-bit
     * BGRA plus an empty AND mask), every field little-endian as the
     * Windows format demands. The tempting compressed variant — whole PNGs
     * as frames — is smaller, but the resource editor that stamps the icon
     * into executables (jpackage on Windows) rejects it as invalid data.
     */
    public static byte[] ico(int... sizes) throws IOException {
        byte[][] frames = new byte[sizes.length][];
        long total = 6 + 16L * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            frames[i] = dibFrame(sizes[i]);
            total += frames[i].length;
        }
        var out = new ByteArrayOutputStream((int) total);
        shortLE(out, 0);          // reserved
        shortLE(out, 1);          // type: icon
        shortLE(out, sizes.length);
        long frameOffset = 6 + 16L * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            out.write(s >= 256 ? 0 : s);  // 0 means 256
            out.write(s >= 256 ? 0 : s);
            out.write(0);                  // palette
            out.write(0);                  // reserved
            shortLE(out, 1);               // planes
            shortLE(out, 32);              // bits
            intLE(out, frames[i].length);
            intLE(out, (int) frameOffset);
            frameOffset += frames[i].length;
        }
        for (byte[] f : frames) out.write(f, 0, f.length);
        return out.toByteArray();
    }

    /** One ICO frame: BITMAPINFOHEADER, the mark bottom-up in BGRA, and
     *  the all-zero AND mask the 32-bit alpha makes redundant (rows padded
     *  to 32 bits, as the format requires). */
    private static byte[] dibFrame(int size) {
        BufferedImage img = render(size, BRAND);
        int maskRow = (size + 31) / 32 * 4;
        var out = new ByteArrayOutputStream(40 + size * size * 4 + maskRow * size);
        intLE(out, 40);               // BITMAPINFOHEADER size
        intLE(out, size);             // biWidth
        intLE(out, size * 2);         // biHeight: XOR rows plus AND rows
        shortLE(out, 1);              // biPlanes
        shortLE(out, 32);             // biBitCount
        intLE(out, 0);                // BI_RGB
        intLE(out, size * size * 4 + maskRow * size);  // biSizeImage
        intLE(out, 0);                // x pels per meter
        intLE(out, 0);                // y pels per meter
        intLE(out, 0);                // colors used
        intLE(out, 0);                // colors important
        for (int y = size - 1; y >= 0; y--) {
            for (int x = 0; x < size; x++) {
                int argb = img.getRGB(x, y);
                out.write(argb);        // blue
                out.write(argb >> 8);   // green
                out.write(argb >> 16);  // red
                out.write(argb >> 24);  // alpha
            }
        }
        out.write(new byte[maskRow * size], 0, maskRow * size);  // AND mask
        return out.toByteArray();
    }

    private static void shortLE(ByteArrayOutputStream out, int v) {
        out.write(v);
        out.write(v >> 8);
    }

    private static void intLE(ByteArrayOutputStream out, int v) {
        for (int i = 0; i < 4; i++) out.write(v >> (8 * i));
    }

    /** Build-task entry: writes dock.ico, a few PNGs and the macOS iconset to a directory. */
    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "build/icon");
        Files.createDirectories(dir);
        Files.write(dir.resolve("dock.ico"), ico(16, 24, 32, 48, 64, 128, 256));
        for (int s : new int[]{16, 32, 256}) {
            Files.write(dir.resolve("dock-" + s + ".png"), png(s));
        }
        // macOS: the iconset iconutil turns into dock.icns (1x and @2x per size).
        Path iconset = Files.createDirectories(dir.resolve("dock.iconset"));
        for (int s : new int[]{16, 32, 128, 256, 512}) {
            Files.write(iconset.resolve("icon_" + s + "x" + s + ".png"), png(s));
            Files.write(iconset.resolve("icon_" + s + "x" + s + "@2x.png"), png(s * 2));
        }
        System.out.println("icons written to " + dir.toAbsolutePath());
    }
}
