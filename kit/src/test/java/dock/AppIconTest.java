package dock;

import dock.kit.AppIcon;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The icon artifacts are structure-checked byte by byte: the ICO parses as
 * a valid PNG-in-ICO directory (offsets/sizes consistent, every frame a
 * real PNG), and the renders decode back to the requested dimensions with
 * actual ink in them.
 */
class AppIconTest {

    @Test
    void icoIsAValidPngFramedDirectory() throws Exception {
        int[] sizes = {16, 32, 256};
        byte[] ico = AppIcon.ico(sizes);
        var in = new DataInputStream(new ByteArrayInputStream(ico));

        assertEquals(0, in.readShort(), "reserved");
        assertEquals(1, in.readShort(), "type: icon");
        assertEquals(sizes.length, in.readShort(), "frame count");

        int[] frameOffset = new int[sizes.length];
        int[] frameSize = new int[sizes.length];
        int next = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            int width = in.readUnsignedByte();
            assertEquals(sizes[i] >= 256 ? 0 : sizes[i], width, "width byte of frame " + i);
            assertEquals(width, in.readUnsignedByte(), "height byte mirrors width");
            assertEquals(0, in.readUnsignedByte(), "no palette");
            assertEquals(0, in.readUnsignedByte(), "reserved");
            assertEquals(1, in.readShort(), "one plane");
            assertEquals(32, in.readShort(), "32bpp");
            frameSize[i] = in.readInt();
            assertTrue(frameSize[i] > 0, "frame has bytes");
            frameOffset[i] = in.readInt();
            assertEquals(next, frameOffset[i], "frame " + i + " sits right after the previous");
            next += frameSize[i];
        }
        assertEquals(ico.length, next, "directory accounts for every byte of the file");

        for (int i = 0; i < sizes.length; i++) {
            int p = frameOffset[i];
            assertEquals((byte) 0x89, ico[p], "PNG magic 0 of frame " + i);
            assertEquals((byte) 0x50, ico[p + 1], "'P'");
            assertEquals((byte) 0x4E, ico[p + 2], "'N'");
            assertEquals((byte) 0x47, ico[p + 3], "'G'");
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(
                    ico, p, frameSize[i]));
            assertEquals(sizes[i], img.getWidth(), "frame " + i + " width");
            assertEquals(sizes[i], img.getHeight(), "frame " + i + " height");
        }
    }

    @Test
    void rendersAreABackgroundlessPlanetMark() throws Exception {
        for (int size : new int[]{16, 48, 256}) {
            BufferedImage img = ImageIO.read(
                    new ByteArrayInputStream(AppIcon.png(size)));
            assertEquals(size, img.getWidth());
            int brand = AppIcon.BRAND.getRGB() & 0xFFFFFF;
            int br = (brand >> 16) & 0xFF, bg = (brand >> 8) & 0xFF, bb = brand & 0xFF;
            int ink = 0;
            int wrongColor = 0;
            int minX = Integer.MAX_VALUE, maxX = -1;
            int minY = Integer.MAX_VALUE, maxY = -1;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int rgb = img.getRGB(x, y);
                    int alpha = rgb >>> 24;
                    if (alpha != 0) {
                        ink++;
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minY = Math.min(minY, y);
                        maxY = Math.max(maxY, y);
                        // Solid-ish pixels only: faint antialiased fringe can
                        // round a channel by a step through the premultiplied
                        // raster round-trip.
                        if (alpha > 32) {
                            int dr = Math.abs(((rgb >> 16) & 0xFF) - br);
                            int dg = Math.abs(((rgb >> 8) & 0xFF) - bg);
                            int db = Math.abs((rgb & 0xFF) - bb);
                            if (Math.max(dr, Math.max(dg, db)) > 12) wrongColor++;
                        }
                    }
                }
            }
            // Sizing: the diagonal mark fills ~97% of the square's height
            // and ~91% of its width (the ring is taller than wide once
            // tilted) — an upright flat ring caps height at ~55%.
            assertTrue(maxX - minX + 1 >= size * 85 / 100,
                    size + "px mark must span the square's width, got " + (maxX - minX + 1));
            assertTrue(maxY - minY + 1 >= size * 4 / 5,
                    size + "px diagonal mark must fill the square's height, got "
                            + (maxY - minY + 1));
            // No background: the corners stay transparent and every painted
            // pixel is the accent itself (antialiased edges keep the color,
            // only the alpha varies).
            int[][] corners = {{0, 0}, {size - 1, 0}, {0, size - 1}, {size - 1, size - 1}};
            for (int[] c : corners) {
                assertEquals(0, img.getRGB(c[0], c[1]) >>> 24,
                        size + "px corner must be transparent — no background tile");
            }
            assertTrue(ink > size * size / 16,
                    size + "px mark carries ink, got " + ink);
            assertEquals(0, wrongColor,
                    size + "px mark is a single accent color throughout");
        }
    }
}
