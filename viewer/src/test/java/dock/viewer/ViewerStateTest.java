package dock.viewer;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.EventQueue;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The viewer's state machine: fit math, zoom clamping and anchoring,
 * sticky rotation, and walking the live sequence.
 */
class ViewerStateTest {

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        com.formdev.flatlaf.FlatLaf.registerCustomDefaultsSource("dock.themes");
        com.formdev.flatlaf.FlatLaf.setup(new com.formdev.flatlaf.FlatDarkLaf());
    }

    private static final List<String> SEQ = List.of("a.png", "b.png", "c.png");

    private MemFs fs;
    private JFrame frame;
    private ImageViewerPanel panel;
    private final java.util.List<String> navigated = new java.util.ArrayList<>();

    private ImageViewerPanel build(String first) throws Exception {
        fs = new MemFs();
        fs.add("/a.png", Fixtures.encode(Fixtures.solid(400, 300, 0xCC0000), "png"));
        fs.add("/b.png", Fixtures.encode(Fixtures.solid(40, 30, 0x00CC00), "png"));
        fs.add("/c.png", Fixtures.encode(Fixtures.solid(8, 6, 0x0000CC), "png"));
        AtomicReference<ImageViewerPanel> ref = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            panel = new ImageViewerPanel(fs, "/", first, () -> SEQ,
                    navigated::add, () -> {});
            ref.set(panel);
            frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(panel);
            frame.setSize(320, 280);
            frame.setLocation(-2000, 0);
            frame.setVisible(true);
        });
        await(() -> ref.get().stateForTest() != ImageViewerPanel.State.LOADING, "first load");
        return ref.get();
    }

    @Test
    void fitsLargeImagesDownToThePane() throws Exception {
        build("a.png");
        int cw = panel.canvasForTest().getWidth();
        int ch = panel.canvasForTest().getHeight();
        double expected = Math.min((cw - 24.0) / 400, (ch - 24.0) / 300);
        assertTrue(panel.fitForTest(), "starts in fit mode");
        assertEquals(expected, panel.scaleForTest(), 1e-9, "fit takes the smaller axis");
    }

    @Test
    void fitsSmallImagesUp() throws Exception {
        build("c.png");
        assertTrue(panel.scaleForTest() > 1, "an 8×6 image upscales to fill the pane");
    }

    @Test
    void actualSizeKeyGoesToOneHundredPercent() throws Exception {
        build("a.png");
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("typed 1"));
        assertEquals(1.0, panel.scaleForTest(), 1e-9);
        assertFalse(panel.fitForTest());
    }

    @Test
    void zoomClampsAtBothEnds() throws Exception {
        build("a.png");
        EventQueue.invokeAndWait(() -> {
            panel.fireKeyForTest("typed 1");
            for (int i = 0; i < 80; i++) panel.zoomIn();
        });
        assertEquals(32.0, panel.scaleForTest(), 1e-9, "max zoom is 3200%");
        EventQueue.invokeAndWait(() -> {
            for (int i = 0; i < 200; i++) panel.zoomOut();
        });
        assertEquals(0.05, panel.scaleForTest(), 1e-9, "min zoom is 5%");
    }

    @Test
    void wheelZoomHoldsThePointUnderTheCursor() throws Exception {
        build("a.png");
        EventQueue.invokeAndWait(() -> {
            panel.fireKeyForTest("typed r");   // rotation commutes with zoom
            panel.fireKeyForTest("typed 1");
        });
        double vx = panel.canvasForTest().getWidth() * 0.7;
        double vy = panel.canvasForTest().getHeight() * 0.3;
        // Image point under (vx, vy) before the zoom, in unrotated image
        // coordinates: un-center, un-pan, un-rotate (quadrant 1), un-scale.
        double[] before = imagePointUnder(vx, vy);
        EventQueue.invokeAndWait(() -> panel.zoomAt(vx, vy, 1.6));
        double[] after = imagePointUnder(vx, vy);
        assertEquals(before[0], after[0], 1e-6, "x under the cursor holds");
        assertEquals(before[1], after[1], 1e-6, "y under the cursor holds");
    }

    private double[] imagePointUnder(double vx, double vy) {
        double cw = panel.canvasForTest().getWidth();
        double ch = panel.canvasForTest().getHeight();
        double px = vx - cw / 2 - panel.panXForTest();
        double py = vy - ch / 2 - panel.panYForTest();
        // rotate(+π/2) maps (x, y) -> (-y, x); undo with (y, -x).
        double ux = py;
        double uy = -px;
        return new double[] {ux / panel.scaleForTest(), uy / panel.scaleForTest()};
    }

    @Test
    void rotationIsStickyButZoomResetsPerImage() throws Exception {
        build("a.png");
        EventQueue.invokeAndWait(() -> {
            panel.fireKeyForTest("typed 1");
            panel.fireKeyForTest("typed r");
        });
        assertEquals(1, panel.quadrantForTest());
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("RIGHT"));
        await(() -> "b.png".equals(panel.currentForTest())
                && panel.stateForTest() == ImageViewerPanel.State.SHOWN, "next image shown");
        assertEquals(1, panel.quadrantForTest(), "rotation carries across images");
        assertTrue(panel.fitForTest(), "zoom resets to fit");
    }

    @Test
    void rotationWrapsBothWays() throws Exception {
        build("a.png");
        EventQueue.invokeAndWait(() -> {
            panel.fireKeyForTest("typed R");   // counterclockwise from zero
        });
        assertEquals(3, panel.quadrantForTest(), "one back from zero lands on 270°");
        EventQueue.invokeAndWait(() -> {
            panel.fireKeyForTest("typed r");
        });
        assertEquals(0, panel.quadrantForTest());
    }

    @Test
    void ctrlArrowsRotateInsteadOfWalking() throws Exception {
        build("a.png");
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("ctrl RIGHT"));
        assertEquals(1, panel.quadrantForTest(), "ctrl+right winds clockwise");
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("ctrl LEFT"));
        assertEquals(0, panel.quadrantForTest(), "ctrl+left unwinds");
        assertEquals("a.png", panel.currentForTest(),
                "the arrows under Ctrl never walk the sequence");
    }

    @Test
    void navigationWalksAndClamps() throws Exception {
        build("a.png");
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("RIGHT"));
        await(() -> "b.png".equals(panel.currentForTest()), "b");
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("RIGHT"));
        await(() -> "c.png".equals(panel.currentForTest()), "c");
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("RIGHT"));
        Thread.sleep(150);
        assertEquals("c.png", panel.currentForTest(), "clamped at the last image");
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("END"));
        await(() -> "c.png".equals(panel.currentForTest()), "end");
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("HOME"));
        await(() -> "a.png".equals(panel.currentForTest()), "home");
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("PAGE_UP"));
        Thread.sleep(150);
        assertEquals("a.png", panel.currentForTest(), "clamped at the first image");
        assertEquals(java.util.List.of("a.png", "b.png", "c.png", "a.png"), navigated,
                "each shown file is reported for cursor sync");
    }

    @Test
    void missingFileShowsTheRefusedState() throws Exception {
        build("missing.png");
        await(() -> panel.stateForTest() == ImageViewerPanel.State.REFUSED, "refusal");
        assertTrue(panel.imageForTest() instanceof DecodedImage.Unsupported u
                && u.reason().contains("no such file"));
    }

    @Test
    void undecodableBytesRefuseInsteadOfThrowing() throws Exception {
        fs = new MemFs();
        fs.add("/garbage.png", "not a png at all".getBytes());
        AtomicReference<ImageViewerPanel> ref = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            panel = new ImageViewerPanel(fs, "/", "garbage.png", () -> List.of("garbage.png"),
                    n -> {}, () -> {});
            ref.set(panel);
            frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(panel);
            frame.setSize(320, 280);
            frame.setLocation(-2000, 0);
            frame.setVisible(true);
        });
        await(() -> ref.get().stateForTest() == ImageViewerPanel.State.REFUSED, "refusal");
        assertEquals("not an image",
                ((DecodedImage.Unsupported) ref.get().imageForTest()).reason());
    }

    @Test
    void heicIsRefusedUpFront() throws Exception {
        fs = new MemFs();
        fs.add("/photo.heic", new byte[] {0, 0, 1});
        AtomicReference<ImageViewerPanel> ref = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            panel = new ImageViewerPanel(fs, "/", "photo.heic", () -> List.of("photo.heic"),
                    n -> {}, () -> {});
            ref.set(panel);
            frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(panel);
            frame.setSize(320, 280);
            frame.setLocation(-2000, 0);
            frame.setVisible(true);
        });
        await(() -> ref.get().stateForTest() == ImageViewerPanel.State.REFUSED, "refusal");
        assertEquals("heic",
                ((DecodedImage.Unsupported) ref.get().imageForTest()).format());
    }

    private static void await(java.util.function.BooleanSupplier cond, String what)
            throws InterruptedException {
        for (int i = 0; i < 250 && !cond.getAsBoolean(); i++) Thread.sleep(20);
        assertTrue(cond.getAsBoolean(), "timed out waiting for: " + what);
    }

    @org.junit.jupiter.api.AfterEach
    void dispose() {
        if (frame != null) EventQueue.invokeLater(frame::dispose);
    }
}
