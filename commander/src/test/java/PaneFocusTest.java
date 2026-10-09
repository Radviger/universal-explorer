import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
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
 * The keyboard-only session flow: a fresh connection moves the keyboard
 * into the remote listing (instead of leaving it on the tab strip) but
 * preselects nothing — scanning starts on the first arrow-down. LEFT/RIGHT
 * hop the keyboard between the panes — left is always local, right always
 * remote — the same way: focus moves, selections never do. A folded
 * pane stays out of the hops.
 */
class PaneFocusTest {

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
    void freshSessionFocusesTheRemoteListingWithoutPreselecting() throws Exception {
        build(root -> {
            Files.createDirectory(root.resolve("alpha"));
            Files.createDirectory(root.resolve("beta"));
        });
        awaitListed(view.remotePane());

        assertEquals(0, view.remotePane().table().getSelectedRowCount(),
                "the session starts focused but unselected");
        assertEquals(0, view.localPane().table().getSelectedRowCount(),
                "the local pane is not selected either");

        // Scanning starts on the first arrow-down: the first real entry.
        EventQueue.invokeAndWait(() -> view.remotePane().fireTableActionForTest("DOWN"));
        assertEquals("alpha", selectedName(view.remotePane()),
                "arrow-down starts on the first entry, never \"..\"");
    }

    @Test
    void arrowKeysHopBetweenThePanes() throws Exception {
        build(root -> {
            Files.createDirectory(root.resolve("alpha"));
            Files.writeString(root.resolve("z.txt"), "x");
        });
        awaitListed(view.remotePane());
        // The local pane walks into the same directory for deterministic rows.
        EventQueue.invokeAndWait(() -> view.localPane().navigate(dir.toString()));
        awaitListed(view.localPane());

        // A hop moves the keyboard without selecting anything.
        EventQueue.invokeAndWait(() -> view.localPane().fireTableActionForTest("RIGHT"));
        assertEquals(0, view.remotePane().table().getSelectedRowCount(),
                "the hop focuses without selecting");
        EventQueue.invokeAndWait(() -> view.remotePane().fireTableActionForTest("DOWN"));
        assertEquals("alpha", selectedName(view.remotePane()), "scanning starts on demand");

        // Hopping away and back never disturbs a cursor that exists.
        EventQueue.invokeAndWait(() -> view.localPane().fireTableActionForTest("LEFT"));
        EventQueue.invokeAndWait(() -> view.localPane().fireTableActionForTest("DOWN"));
        assertEquals("alpha", selectedName(view.localPane()), "the local scan starts too");
        EventQueue.invokeAndWait(() -> view.localPane().fireTableActionForTest("RIGHT"));
        assertEquals("alpha", selectedName(view.remotePane()),
                "the remote cursor survives the round trip");
    }

    @Test
    void hiddenPaneStaysOutOfTheHops() throws Exception {
        build(root -> Files.writeString(root.resolve("z.txt"), "x"));
        awaitListed(view.remotePane());
        EventQueue.invokeAndWait(() -> view.remotePane().fireTableActionForTest("DOWN"));
        assertEquals("z.txt", selectedName(view.remotePane()));

        EventQueue.invokeAndWait(() -> view.setLocalPaneHidden(true));
        EventQueue.invokeAndWait(() -> view.remotePane().fireTableActionForTest("LEFT"));
        assertEquals("z.txt", selectedName(view.remotePane()),
                "the visible pane keeps its cursor");
        assertEquals(0, view.localPane().table().getSelectedRowCount(),
                "the off-screen pane gains nothing");
    }

    // ---- helpers ----

    private void build(IOThrowing<java.nio.file.Path> fill) throws Exception {
        dir = Files.createTempDirectory("dock-panefocus");
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

    private static void awaitListed(dock.commander.FilePane pane)
            throws InterruptedException {
        for (int i = 0; i < 250 && pane.busyForTest(); i++) Thread.sleep(20);
        assertTrue(!pane.busyForTest(), "listing settles");
    }

    private static String selectedName(dock.commander.FilePane pane) throws Exception {
        var n = new AtomicReference<String>();
        EventQueue.invokeAndWait(() -> n.set(nameAt(pane, pane.table().getSelectedRow())));
        return n.get();
    }

    private static String nameAt(dock.commander.FilePane pane, int row) {
        if (row < 0) return null;
        var m = (dock.commander.FileTableModel) pane.table().getModel();
        return m.row(row).name();
    }

    @FunctionalInterface
    private interface IOThrowing<T> {
        void accept(T value) throws java.io.IOException;
    }
}
