package dock.viewer;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What lands on the pixels: the image itself, quadrant rotation, and the
 * checkerboard behind transparency. Deterministic paint captures — never
 * a screenshot judgement.
 */
class ViewerPaintTest {

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        com.formdev.flatlaf.FlatLaf.registerCustomDefaultsSource("dock.themes");
        com.formdev.flatlaf.FlatLaf.setup(new com.formdev.flatlaf.FlatDarkLaf());
    }

    private JFrame frame;
    private ImageViewerPanel panel;

    private ImageViewerPanel build(String first, String fileName, byte[] bytes)
            throws Exception {
        MemFs fs = new MemFs();
        fs.add("/" + fileName, bytes);
        AtomicReference<ImageViewerPanel> ref = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            panel = new ImageViewerPanel(fs, "/", first, () -> List.of(fileName),
                    n -> {}, () -> {});
            ref.set(panel);
            frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(panel);
            frame.setSize(300, 260);
            frame.setLocation(-2000, 0);
            frame.setVisible(true);
        });
        await(() -> ref.get().stateForTest() != ImageViewerPanel.State.LOADING, "load");
        return ref.get();
    }

    /** Paints the canvas into a fresh ARGB image. */
    private BufferedImage paint() throws Exception {
        AtomicReference<BufferedImage> ref = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            var c = panel.canvasForTest();
            BufferedImage img = new BufferedImage(c.getWidth(), c.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            c.paint(g);
            g.dispose();
            ref.set(img);
        });
        return ref.get();
    }

    private static int channel(int rgb, int shift) { return (rgb >> shift) & 0xFF; }

    @Test
    void paintsTheLoadedImage() throws Exception {
        build("a.png", "a.png", Fixtures.encode(Fixtures.solid(60, 40, 0xCC2222), "png"));
        BufferedImage shot = paint();
        int cx = shot.getWidth() / 2, cy = shot.getHeight() / 2;
        int rgb = shot.getRGB(cx, cy);
        assertTrue(Math.abs(channel(rgb, 16) - 0xCC) < 12
                        && Math.abs(channel(rgb, 8) - 0x22) < 12
                        && Math.abs(channel(rgb, 0) - 0x22) < 12,
                "center shows the image, saw " + Integer.toHexString(rgb));
    }

    @Test
    void quadrantRotationMovesTheRightHalfToTheBottom() throws Exception {
        // A 2×1 image, red left half and blue right half. One clockwise
        // quarter turn stands it upright: red on top, blue underneath.
        // Samples sit inside the pure-color zones: bilinear smoothing
        // blends the seam between the two pixels across half a source
        // pixel on either side, so offsets scale with the zoom.
        BufferedImage half = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        half.setRGB(0, 0, 0xFFCC0000);
        half.setRGB(1, 0, 0xFF0000CC);
        build("a.png", "a.png", Fixtures.encode(half, "png"));
        BufferedImage upright = paint();
        int cx = upright.getWidth() / 2, cy = upright.getHeight() / 2;
        int off = (int) Math.round(panel.scaleForTest() * 0.6);
        assertTrue(Math.abs(channel(upright.getRGB(cx - off, cy), 16) - 0xCC) < 12,
                "before rotating, left of center is red");
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("typed r"));
        BufferedImage turned = paint();
        assertTrue(Math.abs(channel(turned.getRGB(cx, cy - off), 16) - 0xCC) < 12,
                "after a quarter turn, above center is red");
        assertTrue(Math.abs(channel(turned.getRGB(cx, cy + off), 0) - 0xCC) < 12,
                "and below center is blue");
    }

    @Test
    void transparentImagesGetTheCheckerboard() throws Exception {
        build("t.png", "t.png",
                Fixtures.encode(new BufferedImage(60, 60, BufferedImage.TYPE_INT_ARGB), "png"));
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("typed 1"));
        BufferedImage shot = paint();
        int cy = shot.getHeight() / 2;
        int cx = shot.getWidth() / 2;
        // Two points one checker tile apart, both inside the image's rect.
        int a = shot.getRGB(cx - 6, cy) & 0xFFFFFF;
        int b = shot.getRGB(cx + 6, cy) & 0xFFFFFF;
        assertNotEquals(a, b, "adjacent checker tiles alternate");
    }

    @Test
    void animatedGifPaintsItsFirstFrame() throws Exception {
        byte[] gif = Fixtures.gif(4, 4,
                new Fixtures.GifFrame(0, 0, 4, 4, 0, 100, "none"),
                new Fixtures.GifFrame(0, 0, 4, 4, 1, 100, "none"));
        build("a.gif", "a.gif", gif);
        BufferedImage shot = paint();
        int rgb = shot.getRGB(shot.getWidth() / 2, shot.getHeight() / 2);
        assertTrue(Math.abs(channel(rgb, 16) - 0xFF) < 12
                        && channel(rgb, 0) < 12,
                "frame zero is red, saw " + Integer.toHexString(rgb));
    }

    private static void await(java.util.function.BooleanSupplier cond, String what)
            throws InterruptedException {
        for (int i = 0; i < 250 && !cond.getAsBoolean(); i++) Thread.sleep(20);
        assertTrue(cond.getAsBoolean(), "timed out waiting for: " + what);
    }

    @AfterEach
    void dispose() {
        if (frame != null) EventQueue.invokeLater(frame::dispose);
    }
}
