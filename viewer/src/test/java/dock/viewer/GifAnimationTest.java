package dock.viewer;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.EventQueue;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The animation loop: frames advance on their real delays, a click on the
 *  canvas pauses and resumes, and the timer dies with the component. */
class GifAnimationTest {

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        com.formdev.flatlaf.FlatLaf.registerCustomDefaultsSource("dock.themes");
        com.formdev.flatlaf.FlatLaf.setup(new com.formdev.flatlaf.FlatDarkLaf());
    }

    private JFrame frame;
    private ImageViewerPanel panel;

    private ImageViewerPanel build() throws Exception {
        MemFs fs = new MemFs();
        fs.add("/spin.gif", Fixtures.gif(4, 4,
                new Fixtures.GifFrame(0, 0, 4, 4, 0, 10, "none"),
                new Fixtures.GifFrame(0, 0, 4, 4, 1, 10, "none"),
                new Fixtures.GifFrame(0, 0, 4, 4, 2, 10, "none")));
        AtomicReference<ImageViewerPanel> ref = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            panel = new ImageViewerPanel(fs, "/", "spin.gif", () -> List.of("spin.gif"),
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

    @Test
    void playsFramesOnTheirDelaysAndWraps() throws Exception {
        build();
        await(() -> panel.frameForTest() == 1, "second frame (100ms delay)");
        await(() -> panel.frameForTest() == 2, "third frame");
        await(() -> panel.frameForTest() == 0, "wraps to the first frame");
    }

    @Test
    void clickingPausesAndResumesTheAnimation() throws Exception {
        build();
        await(() -> panel.frameForTest() == 1, "animation is running");
        EventQueue.invokeAndWait(() -> click());
        assertTrue(panel.pausedForTest(), "a click pauses");
        int frozen = panel.frameForTest();
        Thread.sleep(350);   // three frame periods must not move it
        assertEquals(frozen, panel.frameForTest(), "paused animation holds its frame");
        EventQueue.invokeAndWait(() -> click());
        assertFalse(panel.pausedForTest(), "a second click resumes");
        await(() -> panel.frameForTest() != frozen, "frames advance again");
    }

    private void click() {
        var c = panel.canvasForTest();
        long when = System.currentTimeMillis();
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, when, 0, 10, 10, 1, false));
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, when, 0, 10, 10, 1, false));
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, when, 0, 10, 10, 1, false));
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
