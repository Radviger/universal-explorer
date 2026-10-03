package dock.viewer;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * The decoder's format matrix. Still formats round-trip through ImageIO
 * (the JDK writers) or the hand-written fixture writers; the animation
 * test exercises patch compositing and disposal methods on a real GIF89a.
 */
class DecoderFormatTest {

    @Test
    void decodesPngExactly() {
        byte[] png = Fixtures.encode(Fixtures.solid(3, 2, 0x123456), "png");
        DecodedImage.Still s = assertInstanceOf(DecodedImage.Still.class,
                ImageDecoder.decode(png, "a.png"));
        assertEquals("png", s.format());
        assertEquals(3, s.width());
        assertEquals(2, s.height());
        assertEquals(0xFF123456, s.image().getRGB(1, 1));
        assertTrue(s.alpha());
    }

    @Test
    void decodesJpegBmpAndTiff() {
        var img = new java.awt.image.BufferedImage(5, 4,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 5; x++) img.setRGB(x, y, 0xFF4080C0);
        for (String fmt : new String[] {"jpg", "bmp", "tif"}) {
            DecodedImage.Still s = assertInstanceOf(DecodedImage.Still.class,
                    ImageDecoder.decode(Fixtures.encode(img, fmt), "f." + fmt),
                    fmt + " decodes");
            assertEquals(5, s.width(), fmt);
            assertEquals(4, s.height(), fmt);
        }
    }

    @Test
    void decodesTheWebpFixture() {
        DecodedImage d = ImageDecoder.decode(Fixtures.resource("blue_tile.webp"), "tile.webp");
        DecodedImage.Still s = assertInstanceOf(DecodedImage.Still.class, d);
        assertEquals(256, s.width());
        assertEquals(256, s.height());
        assertFalse(s.alpha());
    }

    @Test
    void decodesHandWrittenIco() {
        int[] px = {
                0xFF0000FF, 0x00FF00FF,   // argb: red, green
                0x0000FFFF, 0xFF0000FF,   // blue, red
        };
        DecodedImage.Still s = assertInstanceOf(DecodedImage.Still.class,
                ImageDecoder.decode(Fixtures.ico(2, 2, px), "i.ico"));
        assertEquals(2, s.width());
        assertEquals(2, s.height());
        assertEquals("ico", s.format());
        int tl = s.image().getRGB(0, 0) & 0xFFFFFF;
        assertEquals(0xFF0000, tl, "top-left stays red through BGRA");
    }

    @Test
    void decodesHandWrittenPsd() {
        int[] px = {0xFF102030, 0xFF405060, 0xFF708090,
                    0xFFA0B0C0, 0xFFD0E0F0, 0xFF0F1E2D};
        DecodedImage.Still s = assertInstanceOf(DecodedImage.Still.class,
                ImageDecoder.decode(Fixtures.psd(3, 2, px), "p.psd"));
        assertEquals(3, s.width());
        assertEquals(2, s.height());
        assertEquals("psd", s.format());
        assertEquals(0xFF102030, s.image().getRGB(0, 0), "planar RGB reassembles");
    }

    @Test
    void decodesSvgAsVectorAndRasterizes() {
        String svg = """
                <?xml version="1.0"?>
                <svg xmlns="http://www.w3.org/2000/svg" width="20" height="10">
                  <rect width="20" height="10" fill="#FF0000"/>
                </svg>
                """;
        DecodedImage.Vector v = assertInstanceOf(DecodedImage.Vector.class,
                ImageDecoder.decode(svg.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "r.svg"));
        assertEquals(20, v.width());
        assertEquals(10, v.height());
        var raster = ImageDecoder.rasterize(v.diagram(), 20, 10);
        assertEquals(20, raster.getWidth());
        assertEquals(0xFFFF0000, raster.getRGB(10, 5));
    }

    @Test
    void gifCompositesPatchesAndHonorsDisposal() {
        // 4×4 screen. f0 paints everything red; f1 paints a 2×2 blue patch
        // top-left and disposes to background; f2 touches one far corner
        // green — after f2 the blue patch must be cleared, proving the
        // disposal ran between frames.
        byte[] gif = Fixtures.gif(4, 4,
                new Fixtures.GifFrame(0, 0, 4, 4, 0, 10, "none"),
                new Fixtures.GifFrame(0, 0, 2, 2, 1, 20, "background"),
                new Fixtures.GifFrame(3, 3, 1, 1, 2, 30, "none"));
        DecodedImage.Animated a = assertInstanceOf(DecodedImage.Animated.class,
                ImageDecoder.decode(gif, "a.gif"));
        assertEquals(3, a.frames().size());
        assertEquals(java.util.List.of(100, 200, 300), a.delaysMs());
        assertEquals(0xFF0000, a.frames().get(0).getRGB(1, 1) & 0xFFFFFF);

        var f1 = a.frames().get(1);
        assertEquals(0xFF, (f1.getRGB(0, 0) >>> 24) & 0xFF, "patch drawn opaque");
        assertEquals(0x0000FF, f1.getRGB(0, 0) & 0xFFFFFF, "blue patch");
        assertEquals(0xFF0000, f1.getRGB(3, 3) & 0xFFFFFF, "red remains");

        var f2 = a.frames().get(2);
        assertEquals(0x00FF00, f2.getRGB(3, 3) & 0xFFFFFF, "green corner");
        assertEquals(0, (f2.getRGB(0, 0) >>> 24) & 0xFF,
                "background disposal cleared the patch area");
        assertEquals(0xFF0000, f2.getRGB(2, 2) & 0xFFFFFF, "rest stays red");
    }

    @Test
    void gifPreviousDisposalRestoresTheCanvas() {
        // f1 disposes "previous": whatever f2 shows must match f0 under
        // f1's patch, not f1's own paint.
        byte[] gif = Fixtures.gif(2, 2,
                new Fixtures.GifFrame(0, 0, 2, 2, 0, 10, "none"),
                new Fixtures.GifFrame(0, 0, 1, 1, 1, 10, "previous"),
                new Fixtures.GifFrame(1, 1, 1, 1, 2, 10, "none"));
        DecodedImage.Animated a = assertInstanceOf(DecodedImage.Animated.class,
                ImageDecoder.decode(gif, "b.gif"));
        assertEquals(0x0000FF, a.frames().get(1).getRGB(0, 0) & 0xFFFFFF,
                "f1 paints its blue patch");
        assertEquals(0xFF0000, a.frames().get(2).getRGB(0, 0) & 0xFFFFFF,
                "previous disposal restored f0 under the patch");
    }

    @Test
    void refusesHeicWithoutThrowing() {
        DecodedImage.Unsupported u = assertInstanceOf(DecodedImage.Unsupported.class,
                ImageDecoder.decode(new byte[] {1, 2, 3}, "photo.heic"));
        assertEquals("heic", u.format());
        assertTrue(u.reason().contains("no decoder"));
    }

    @Test
    void garbageAnswersNotAnImage() {
        DecodedImage.Unsupported u = assertInstanceOf(DecodedImage.Unsupported.class,
                ImageDecoder.decode("plain text".getBytes(), "note.txt"));
        assertEquals("not an image", u.reason());
    }
}
