package dock;

import dock.kit.AppIcon;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The icon artifacts are structure-checked byte by byte: the ICO parses as
 * a valid little-endian DIB-framed directory (offsets/sizes consistent,
 * every frame a BITMAPINFOHEADER + BGRA + AND-mask block the Windows
 * resource editor accepts — never a compressed PNG frame), and the renders
 * decode back to the requested dimensions with actual ink in them.
 */
class AppIconTest {

    @Test
    void icoIsAValidDibFramedDirectoryTheWindowsResourceEditorAccepts() throws Exception {
        int[] sizes = {16, 32, 256};
        byte[] ico = AppIcon.ico(sizes);

        // Little-endian accessors: the ICO format reads like Windows writes.
        assertEquals(0, leShort(ico, 0), "reserved");
        assertEquals(1, leShort(ico, 2), "type: icon");
        assertEquals(sizes.length, leShort(ico, 4), "frame count");

        int[] frameOffset = new int[sizes.length];
        int[] frameSize = new int[sizes.length];
        int next = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            int e = 6 + 16 * i;
            int width = ico[e] & 0xFF;
            assertEquals(sizes[i] >= 256 ? 0 : sizes[i], width, "width byte of frame " + i);
            assertEquals(width, ico[e + 1] & 0xFF, "height byte mirrors width");
            assertEquals(0, ico[e + 2] & 0xFF, "no palette");
            assertEquals(0, ico[e + 3] & 0xFF, "reserved");
            assertEquals(1, leShort(ico, e + 4), "one plane");
            assertEquals(32, leShort(ico, e + 6), "32bpp");
            frameSize[i] = leInt(ico, e + 8);
            // 40 bytes of header + BGRA pixels + the padded all-zero AND mask:
            // the exact size of an uncompressed DIB frame, so no frame can be
            // a smuggled-in PNG.
            int maskRow = (sizes[i] + 31) / 32 * 4;
            assertEquals(40 + sizes[i] * sizes[i] * 4 + maskRow * sizes[i], frameSize[i],
                    "frame " + i + " is an uncompressed DIB block");
            frameOffset[i] = leInt(ico, e + 12);
            assertEquals(next, frameOffset[i], "frame " + i + " sits right after the previous");
            next += frameSize[i];
        }
        assertEquals(ico.length, next, "directory accounts for every byte of the file");

        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            int p = frameOffset[i];
            assertNotEquals(0x89, ico[p] & 0xFF, "frame " + i + " must not start as a PNG (0x89)");
            assertEquals(40, leInt(ico, p), "frame " + i + " opens with a BITMAPINFOHEADER");
            assertEquals(s, leInt(ico, p + 4), "frame " + i + " width");
            assertEquals(s * 2, leInt(ico, p + 8), "frame " + i + " height counts XOR and AND rows");
            assertEquals(1, leShort(ico, p + 12), "frame " + i + " planes");
            assertEquals(32, leShort(ico, p + 14), "frame " + i + " bpp");
            assertEquals(0, leInt(ico, p + 16), "frame " + i + " is BI_RGB, never PNG-compressed");

            // The pixels: bottom-up BGRA rows of the brand-colored mark.
            int maskRow = (s + 31) / 32 * 4;
            int brand = AppIcon.BRAND.getRGB() & 0xFFFFFF;
            int ink = 0;
            int wrongColor = 0;
            for (int y = 0; y < s; y++) {
                for (int x = 0; x < s; x++) {
                    int q = p + 40 + ((s - 1 - y) * s + x) * 4;
                    int b = ico[q] & 0xFF, g = ico[q + 1] & 0xFF, r = ico[q + 2] & 0xFF;
                    int a = ico[q + 3] & 0xFF;
                    if (a > 0) {
                        ink++;
                        if (a > 32) {
                            int dr = Math.abs(r - ((brand >> 16) & 0xFF));
                            int dg = Math.abs(g - ((brand >> 8) & 0xFF));
                            int db = Math.abs(b - (brand & 0xFF));
                            if (Math.max(dr, Math.max(dg, db)) > 12) wrongColor++;
                        }
                    }
                }
            }
            assertTrue(ink > s * s / 16, s + "px frame carries ink, got " + ink);
            assertEquals(0, wrongColor, s + "px frame is the accent color in BGRA");
            // The AND mask exists and is all zeros: alpha alone governs.
            int mask = p + 40 + s * s * 4;
            for (int q = mask; q < frameOffset[i] + frameSize[i]; q++) {
                assertEquals(0, ico[q] & 0xFF, "AND mask byte " + (q - mask) + " of frame " + i);
            }
        }
    }

    private static int leShort(byte[] buf, int off) {
        return (buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8);
    }

    private static int leInt(byte[] buf, int off) {
        return (buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8)
                | ((buf[off + 2] & 0xFF) << 16) | ((buf[off + 3] & 0xFF) << 24);
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
