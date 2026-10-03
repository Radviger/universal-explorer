package dock.viewer;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Both skins ship the viewer's well key, and it is the well actually
 *  painted — the image sits in a frame, not on a panel. */
class ViewerThemeTest {

    @Test
    void bothThemesShipTheViewerWell() throws Exception {
        var colors = new java.util.ArrayList<java.awt.Color>();
        Runnable[] setups = {
                () -> com.formdev.flatlaf.FlatLaf.setup(
                        new com.formdev.flatlaf.FlatDarkLaf()),
                () -> com.formdev.flatlaf.FlatLaf.setup(
                        new com.formdev.flatlaf.FlatLightLaf())
        };
        for (Runnable setup : setups) {
            EventQueue.invokeAndWait(() -> {
                com.formdev.flatlaf.FlatLaf.registerCustomDefaultsSource("dock.themes");
                setup.run();
                colors.add(javax.swing.UIManager.getColor("Dock.viewerBackground"));
            });
        }
        assertEquals(2, colors.size());
        assertNotNull(colors.get(0), "dark ships the well");
        assertNotNull(colors.get(1), "light ships the well");
        assertNotEquals(colors.get(0), colors.get(1), "the skins differ");
    }

    @Test
    void theCanvasPaintsTheWellAroundAFittedImage() throws Exception {
        EventQueue.invokeAndWait(() -> {
            com.formdev.flatlaf.FlatLaf.registerCustomDefaultsSource("dock.themes");
            com.formdev.flatlaf.FlatLaf.setup(new com.formdev.flatlaf.FlatDarkLaf());
        });
        MemFs fs = new MemFs();
        fs.add("/wide.png", Fixtures.encode(Fixtures.solid(300, 40, 0x22AA44), "png"));
        AtomicReference<ImageViewerPanel> ref = new AtomicReference<>();
        JFrame[] frame = new JFrame[1];
        EventQueue.invokeAndWait(() -> {
            ImageViewerPanel p = new ImageViewerPanel(fs, "/", "wide.png",
                    () -> List.of("wide.png"), n -> {}, () -> {});
            ref.set(p);
            frame[0] = new JFrame();
            frame[0].setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame[0].add(p);
            frame[0].setSize(300, 260);
            frame[0].setLocation(-2000, 0);
            frame[0].setVisible(true);
        });
        await(() -> ref.get().stateForTest() == ImageViewerPanel.State.SHOWN, "load");
        // A wide, short image fitted into a taller canvas leaves the well
        // visible above and below it.
        AtomicReference<BufferedImage> cap = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            var c = ref.get().canvasForTest();
            BufferedImage img = new BufferedImage(c.getWidth(), c.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            var g = img.createGraphics();
            c.paint(g);
            g.dispose();
            cap.set(img);
        });
        java.awt.Color well = javax.swing.UIManager.getColor("Dock.viewerBackground");
        int top = cap.get().getRGB(cap.get().getWidth() / 2, 4);
        assertEquals(well.getRGB(), top, "the strip above the fitted image is the well");
        EventQueue.invokeLater(frame[0]::dispose);
    }

    private static void await(java.util.function.BooleanSupplier cond, String what)
            throws InterruptedException {
        for (int i = 0; i < 250 && !cond.getAsBoolean(); i++) Thread.sleep(20);
        assertTrue(cond.getAsBoolean(), "timed out waiting for: " + what);
    }

    @AfterEach
    void resetTheme() {
        EventQueue.invokeLater(() ->
                com.formdev.flatlaf.FlatLaf.setup(new com.formdev.flatlaf.FlatDarkLaf()));
    }
}
