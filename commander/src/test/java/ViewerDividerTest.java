import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The viewer stands in for a pane at the exact width that pane had:
 * swapping a split child re-arms the layout's reset-to-preferred-sizes
 * pass, so every swap re-asserts the separator — it must never visibly
 * move, in either direction, including while two viewers are open.
 */
class ViewerDividerTest {

    /** LocalFs rooted at the temp dir, so the remote pane lists our files. */
    private static final class TempFs implements dock.core.fs.FileSystem {
        private final dock.core.fs.FileSystem inner = dock.core.fs.LocalFs.INSTANCE;
        private final String home;
        TempFs(String home) { this.home = home; }
        @Override public String home() { return home; }
        @Override public String label() { return inner.label(); }
        @Override public boolean remote() { return inner.remote(); }
        @Override public String separator() { return inner.separator(); }
        @Override public List<String> roots() { return inner.roots(); }
        @Override public String normalize(String p) { return inner.normalize(p); }
        @Override public String parent(String p) { return inner.parent(p); }
        @Override public String child(String d, String n) { return inner.child(d, n); }
        @Override public boolean exists(String p) throws java.io.IOException { return inner.exists(p); }
        @Override public List<dock.core.fs.FileEntry> list(String p) throws java.io.IOException { return inner.list(p); }
        @Override public dock.core.fs.FileEntry stat(String p) throws java.io.IOException { return inner.stat(p); }
        @Override public void mkdir(String p) throws java.io.IOException { inner.mkdir(p); }
        @Override public void delete(String p) throws java.io.IOException { inner.delete(p); }
        @Override public void rename(String f, String t) throws java.io.IOException { inner.rename(f, t); }
        @Override public java.io.InputStream read(String p) throws java.io.IOException { return inner.read(p); }
        @Override public java.io.OutputStream write(String p, boolean a) throws java.io.IOException { return inner.write(p, a); }
        @Override public void setTimes(String p, long m) throws java.io.IOException { inner.setTimes(p, m); }
        @Override public void setPerms(String p, int x) throws java.io.IOException { inner.setPerms(p, x); }
        @Override public void close() {}
    }

    /** A session double over the fake filesystem — no protocol involved. */
    private static final class FakeSession implements dock.core.session.Session {
        private final dock.core.fs.FileSystem fs;
        FakeSession(dock.core.fs.FileSystem fs) { this.fs = fs; }
        @Override public dock.core.fs.FileSystem fs() { return fs; }
        @Override public dock.core.fs.FileSystem reconnect() { return fs; }
        @Override public void onConnectionLost(Runnable callback) { }
        @Override public boolean reconnectable() { return false; }
        @Override public void close() { }
    }

    /** An off-center separator position no preferred-size layout lands on. */
    private static final int DIVIDER = 307;

    private dock.commander.SessionView view;
    private JFrame frame;
    private Path dir;

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @AfterEach
    void tearDown() throws Exception {
        EventQueue.invokeAndWait(() -> frame.dispose());
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (java.io.IOException e) { throw new RuntimeException(e); }
            });
        }
    }

    @Test
    void theSeparatorHoldsThroughTheViewerRoundTrip() throws Exception {
        build(root -> image(root.resolve("a.png"), 0xCC2222));
        var remote = view.remotePane();
        awaitListed(remote);
        placeDivider();
        int paneWidth = widthOf(remote);

        EventQueue.invokeAndWait(() -> {
            remote.selectEntry("a.png");
            remote.fireTableActionForTest("ENTER");
            frame.validate();
        });
        assertEquals(DIVIDER, view.dividerLocationForTest(),
                "opening the viewer does not move the separator");
        var standing = view.splitSideForTest(remote);
        assertTrue(standing instanceof dock.viewer.ImageViewerPanel,
                "the viewer stands in the pane's slot");
        assertEquals(paneWidth, standing.getWidth(),
                "the viewer fills the exact width the pane had");

        EventQueue.invokeAndWait(() -> {
            view.fireViewerKeyForTest(remote, "ESCAPE");
            frame.validate();
        });
        assertEquals(DIVIDER, view.dividerLocationForTest(),
                "closing the viewer does not move it either");
        assertSame(remote, view.splitSideForTest(remote), "the pane is back");
        assertEquals(paneWidth, widthOf(remote), "with its width intact");
    }

    @Test
    void closingOneOfTwoViewersStillHoldsTheSeparator() throws Exception {
        build(root -> {
            image(root.resolve("l.png"), 0x22CC22);
            image(root.resolve("r.png"), 0x2222CC);
        });
        var remote = view.remotePane();
        awaitListed(remote);
        var local = view.localPane();
        EventQueue.invokeAndWait(() -> local.navigate(dir.toString()));
        awaitListed(local);
        placeDivider();

        EventQueue.invokeAndWait(() -> {
            local.selectEntry("l.png");
            local.fireTableActionForTest("ENTER");
            frame.validate();
        });
        assertTrue(view.showingViewer(local));
        EventQueue.invokeAndWait(() -> {
            remote.selectEntry("r.png");
            remote.fireTableActionForTest("ENTER");
            frame.validate();
        });
        assertTrue(view.showingViewer(remote));
        assertEquals(DIVIDER, view.dividerLocationForTest(),
                "the second swap holds the separator too");

        // One closes while the other keeps the screen: the swap back must
        // not be the one that finally lets the divider go.
        EventQueue.invokeAndWait(() -> {
            view.fireViewerKeyForTest(remote, "ESCAPE");
            frame.validate();
        });
        assertFalse(view.showingViewer(remote));
        assertTrue(view.showingViewer(local), "the other viewer survives");
        assertEquals(DIVIDER, view.dividerLocationForTest(),
                "closing one of two holds the separator");

        EventQueue.invokeAndWait(() -> {
            view.fireViewerKeyForTest(local, "ESCAPE");
            frame.validate();
        });
        assertEquals(DIVIDER, view.dividerLocationForTest());
    }

    @Test
    void aDragMadeWhileViewingSurvivesTheClose() throws Exception {
        build(root -> image(root.resolve("a.png"), 0x445566));
        var remote = view.remotePane();
        awaitListed(remote);
        placeDivider();
        EventQueue.invokeAndWait(() -> {
            remote.selectEntry("a.png");
            remote.fireTableActionForTest("ENTER");
            frame.validate();
        });

        // A separator drag while a viewer is open is a deliberate user
        // position: closing must keep it, not snap back to pre-open.
        EventQueue.invokeAndWait(() -> {
            view.moveDividerForTest(452);
            frame.validate();
        });
        assertEquals(452, view.dividerLocationForTest());
        EventQueue.invokeAndWait(() -> {
            view.fireViewerKeyForTest(remote, "ESCAPE");
            frame.validate();
        });
        assertEquals(452, view.dividerLocationForTest(),
                "the close keeps where the user dragged the separator");
    }

    // ---- helpers ----

    private void build(IOThrowing<java.nio.file.Path> fill) throws Exception {
        dir = Files.createTempDirectory("dock-viewerdiv");
        fill.accept(dir);
        EventQueue.invokeAndWait(() -> {
            view = new dock.commander.SessionView(
                    new FakeSession(new TempFs(dir.toString())), "unit");
            frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(view);
            frame.setSize(1000, 600);
            frame.setLocation(-2000, 0);    // off-screen: no flash during tests
            frame.setVisible(true);
        });
    }

    /** Parks the separator off-center and lets the layout land there. */
    private void placeDivider() throws Exception {
        EventQueue.invokeAndWait(() -> {
            view.moveDividerForTest(DIVIDER);
            frame.validate();
        });
        assertEquals(DIVIDER, view.dividerLocationForTest(),
                "the placement took (the window realized and laid out)");
    }

    private int widthOf(dock.commander.FilePane side) throws Exception {
        var w = new AtomicInteger();
        EventQueue.invokeAndWait(() -> w.set(view.splitSideForTest(side).getWidth()));
        return w.get();
    }

    /** A real little PNG on disk — the viewer reads actual bytes. */
    private static void image(Path file, int rgb) throws java.io.IOException {
        BufferedImage img = new BufferedImage(20, 14, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 14; y++)
            for (int x = 0; x < 20; x++) img.setRGB(x, y, 0xFF000000 | rgb);
        Files.write(file, pngBytes(img));
    }

    private static byte[] pngBytes(BufferedImage img) throws java.io.IOException {
        var out = new java.io.ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static void awaitListed(dock.commander.FilePane pane)
            throws InterruptedException {
        for (int i = 0; i < 250 && pane.busyForTest(); i++) Thread.sleep(20);
        assertTrue(!pane.busyForTest(), "listing settles");
    }

    @FunctionalInterface
    private interface IOThrowing<T> {
        void accept(T value) throws java.io.IOException;
    }
}
