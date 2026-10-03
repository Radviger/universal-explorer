import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The toolbar row keeps its height when a file opens: the editor and
 * image viewer that stand in for a pane carry their own toolbar in the
 * slot where the pane's sat, and a height difference between the two
 * would move everything under it — the row must measure the same with
 * the pane, with either stand-in, and against the other pane standing
 * beside it in the same frame.
 */
class StandinToolbarHeightTest {

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

    private static final class FakeSession implements dock.core.session.Session {
        private final dock.core.fs.FileSystem fs;
        FakeSession(dock.core.fs.FileSystem fs) { this.fs = fs; }
        @Override public dock.core.fs.FileSystem fs() { return fs; }
        @Override public dock.core.fs.FileSystem reconnect() { return fs; }
        @Override public void onConnectionLost(Runnable callback) { }
        @Override public boolean reconnectable() { return false; }
        @Override public void close() { }
    }

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
    void theToolbarRowHoldsItsHeightThroughEveryOpen() throws Exception {
        dir = Files.createTempDirectory("dock-toolbar-height");
        Files.writeString(dir.resolve("Main.java"), "class Main {}\n");
        Files.writeString(dir.resolve("readme.md"), "# T\n\nprose\n");
        image(dir.resolve("a.png"), 0x3366AA);
        EventQueue.invokeAndWait(() -> {
            view = new dock.commander.SessionView(
                    new FakeSession(new TempFs(dir.toString())), "unit");
            frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(view);
            frame.setSize(1000, 600);
            frame.setLocation(-2000, 0);
            frame.setVisible(true);
        });
        var remote = view.remotePane();
        var local = view.localPane();
        awaitListed(remote);

        int paneBar = barHeightOnEdt(remote);
        assertTrue(paneBar > 0, "the pane's toolbar is in the tree");
        int otherPaneBar = barHeightOnEdt(local);
        assertEquals(paneBar, otherPaneBar,
                "the two panes' toolbars agree before anything opens");

        // The editor, both of its faces: F4 on the source, the open path
        // (Enter) on the rendered page — the render bar carries one more
        // button and must not grow for it.
        open(remote, "Main.java", "F4");
        assertTrue(view.showingEditor(remote), "the editor stands in");
        assertEquals(paneBar, barHeightOnEdt(standIn()),
                "the editor's toolbar is the pane toolbar's height");
        assertEquals(paneBar, barHeightOnEdt(local),
                "the pane beside the editor is unchanged");
        EventQueue.invokeAndWait(() -> view.fireEditorKeyForTest(remote, "ESCAPE"));

        open(remote, "readme.md", "ENTER");
        assertTrue(view.showingEditor(remote), "markdown opens the editor");
        var editor = (dock.editor.EditorPanel) standIn();
        for (int i = 0; i < 250 && !viewOnEdt(editor::showingRender); i++)
            Thread.sleep(20);
        assertTrue(viewOnEdt(editor::showingRender), "the rendered page shows");
        assertEquals(paneBar, barHeightOnEdt(standIn()),
                "the render toolbar is the pane toolbar's height too");
        EventQueue.invokeAndWait(() -> view.fireEditorKeyForTest(remote, "ESCAPE"));

        // The image viewer, with its two grouped separators.
        open(remote, "a.png", "ENTER");
        assertTrue(view.showingViewer(remote), "the viewer stands in");
        assertEquals(paneBar, barHeightOnEdt(standIn()),
                "the viewer's toolbar is the pane toolbar's height");
        EventQueue.invokeAndWait(() -> view.fireViewerKeyForTest(remote, "ESCAPE"));

        assertEquals(paneBar, barHeightOnEdt(remote),
                "closing every stand-in restores the same toolbar height");
    }

    // ---- helpers ----

    private void open(dock.commander.FilePane side, String name, String key)
            throws Exception {
        EventQueue.invokeAndWait(() -> side.selectEntry(name));
        EventQueue.invokeAndWait(() -> side.fireTableActionForTest(key));
        EventQueue.invokeAndWait(() -> frame.validate());
    }

    private Container standIn() throws Exception {
        return viewOnEdt(() -> (Container) view.splitSideForTest(view.remotePane()));
    }

    /** The toolbar row's realized height: for a pane it hangs directly
     *  north; the editor seats a top strip (bar above, find row below)
     *  whose north is the bar — the viewer, like the pane, hangs the bar
     *  north directly. */
    private static Component barOf(Container paneOrStandIn) {
        Component north = ((BorderLayout) paneOrStandIn.getLayout())
                .getLayoutComponent(paneOrStandIn, BorderLayout.NORTH);
        if (north instanceof Container c && c.getLayout() instanceof BorderLayout) {
            north = ((BorderLayout) c.getLayout())
                    .getLayoutComponent(c, BorderLayout.NORTH);
        }
        return north;
    }

    private int barHeightOnEdt(Container paneOrStandIn) throws Exception {
        int h = viewOnEdt(() -> barOf(paneOrStandIn).getHeight());
        assertTrue(h > 0, "the toolbar row is laid out");
        return h;
    }

    /** A real little PNG on disk — the viewer reads actual bytes. */
    private static void image(Path file, int rgb) throws java.io.IOException {
        var img = new java.awt.image.BufferedImage(20, 14,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 14; y++)
            for (int x = 0; x < 20; x++) img.setRGB(x, y, 0xFF000000 | rgb);
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        Files.write(file, out.toByteArray());
    }

    private interface EdtSupplier<T> { T get(); }

    private static <T> T viewOnEdt(EdtSupplier<T> read) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> out.set(read.get()));
        return out.get();
    }

    private static void awaitListed(dock.commander.FilePane pane)
            throws InterruptedException {
        for (int i = 0; i < 250 && pane.busyForTest(); i++) Thread.sleep(20);
        assertTrue(!pane.busyForTest(), "listing settles");
    }
}
