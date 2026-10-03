package dock.kit;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
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
 * multi-size PNG-in-ICO and PNGs, written by the {@code icon} Gradle task
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
     * Multi-size PNG-in-ICO (Vista+; every frame a full PNG file — the
     * format Windows/jpackage accept for large sizes and tolerate for all).
     */
    public static byte[] ico(int... sizes) throws IOException {
        byte[][] frames = new byte[sizes.length][];
        long offset = 6 + 16L * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            frames[i] = png(sizes[i]);
            offset += frames[i].length;
        }
        var out = new ByteArrayOutputStream((int) offset);
        var d = new DataOutputStream(out);
        d.writeShort(0);          // reserved
        d.writeShort(1);          // type: icon
        d.writeShort(sizes.length);
        long frameOffset = 6 + 16L * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            d.writeByte(s >= 256 ? 0 : s);  // 0 means 256
            d.writeByte(s >= 256 ? 0 : s);
            d.writeByte(0);                  // palette
            d.writeByte(0);                  // reserved
            d.writeShort(1);                 // planes
            d.writeShort(32);                // bits
            d.writeInt(frames[i].length);
            d.writeInt((int) frameOffset);
            frameOffset += frames[i].length;
        }
        for (byte[] f : frames) d.write(f);
        return out.toByteArray();
    }

    /** Build-task entry: writes dock.ico and a few PNGs to a directory. */
    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "build/icon");
        Files.createDirectories(dir);
        Files.write(dir.resolve("dock.ico"), ico(16, 24, 32, 48, 64, 128, 256));
        for (int s : new int[]{16, 32, 256}) {
            Files.write(dir.resolve("dock-" + s + ".png"), png(s));
        }
        System.out.println("icons written to " + dir.toAbsolutePath());
    }
}
