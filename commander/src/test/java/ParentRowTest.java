import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.EventQueue;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
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
 * The ".." row is the way out of the directory, not an entry that competes
 * for a sorted position: every column and direction leaves it at row zero,
 * dot-prefixed hiding never eats it, and a real header click keeps it put.
 */
class ParentRowTest {

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
        if (frame != null) EventQueue.invokeAndWait(() -> frame.dispose());
        if (dir != null) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); }
                    catch (java.io.IOException e) { throw new RuntimeException(e); }
                });
            }
        }
    }

    @Test
    void parentIsPinnedAboveEveryColumnAndDirection() {
        List<dock.core.fs.FileEntry> raw = List.of(
                dock.core.fs.FileEntry.PARENT,
                new dock.core.fs.FileEntry("beta", true, 0, 5_000, 0755, false),
                new dock.core.fs.FileEntry("Alpha", true, 0, 1_000, 0700, false),
                new dock.core.fs.FileEntry("gamma", true, 0, 9_000, 0755, false),
                new dock.core.fs.FileEntry("delta.txt", false, 300, 8_000, 0644, false),
                new dock.core.fs.FileEntry("a.txt", false, 7_000, 2_000, 0644, false));
        for (dock.commander.FileTableModel.Column col : dock.commander.FileTableModel.Column.values()) {
            for (boolean asc : new boolean[] {true, false}) {
                List<dock.core.fs.FileEntry> out = dock.commander.FileTableModel.displayList(
                        raw, col, asc, false, false);
                assertEquals(dock.core.fs.FileEntry.PARENT, out.get(0),
                        col + (asc ? " asc" : " desc") + " keeps \"..\" at row zero");
                // The pin must not smuggle the row in twice, and the stable
                // dirs-before-files partition still holds below it.
                assertEquals(1, out.stream()
                        .filter(e -> e == dock.core.fs.FileEntry.PARENT).count(), col + " pins once");
                boolean sawFile = false;
                for (dock.core.fs.FileEntry e : out) {
                    if (e == dock.core.fs.FileEntry.PARENT) continue;
                    if (e.directory()) assertTrue(!sawFile,
                            col + " keeps directories before files");
                    else sawFile = true;
                }
            }
        }
    }

    @Test
    void descendingNameSortsTheRestWithoutTheParentRow() {
        List<dock.core.fs.FileEntry> raw = List.of(
                new dock.core.fs.FileEntry("beta", true, 0, 0, null, false),
                dock.core.fs.FileEntry.PARENT,
                new dock.core.fs.FileEntry("Alpha", true, 0, 0, null, false),
                new dock.core.fs.FileEntry("delta.txt", false, 10, 0, null, false),
                new dock.core.fs.FileEntry("a.txt", false, 20, 0, null, false));
        // Name-descending used to drop ".." to the bottom of the directory
        // block; now the reversal applies only to the real entries.
        assertEquals(List.of("..", "beta", "Alpha", "delta.txt", "a.txt"), names(
                dock.commander.FileTableModel.displayList(raw,
                        dock.commander.FileTableModel.Column.NAME, false, false, false)));
    }

    @Test
    void dotPrefixedHidingNeverEatsTheParentRow() {
        List<dock.core.fs.FileEntry> raw = List.of(
                dock.core.fs.FileEntry.PARENT,
                new dock.core.fs.FileEntry(".config", true, 0, 0, null, false),
                new dock.core.fs.FileEntry("readme.md", false, 12, 0, null, false));
        assertEquals(List.of("..", "readme.md"), names(
                dock.commander.FileTableModel.displayList(raw,
                        dock.commander.FileTableModel.Column.NAME, true, false, true)));
    }

    @Test
    void headerClickKeepsTheWayOutAtRowZero() throws Exception {
        dir = Files.createTempDirectory("dock-parent-row");
        Files.createDirectory(dir.resolve("zdir"));
        Files.createDirectory(dir.resolve("adir"));
        Files.writeString(dir.resolve("note.txt"), "x");
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
        var pane = view.remotePane();
        awaitListed(pane);
        // Default sort is name-ascending; one real click on the Name header
        // flips it to descending — the direction that used to sink "..".
        EventQueue.invokeAndWait(() -> {
            var header = pane.table().getTableHeader();
            Rectangle r = header.getHeaderRect(0);
            header.dispatchEvent(new MouseEvent(header, MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(), 0,
                    r.x + r.width / 2, r.y + r.height / 2, 1, false));
        });
        awaitOrder(pane, List.of("..", "zdir", "adir", "note.txt"));
    }

    // ---- helpers ----

    private static List<String> names(List<dock.core.fs.FileEntry> rows) {
        return rows.stream().map(dock.core.fs.FileEntry::name).toList();
    }

    private static void awaitListed(dock.commander.FilePane pane)
            throws InterruptedException {
        for (int i = 0; i < 250 && pane.busyForTest(); i++) Thread.sleep(20);
        assertTrue(!pane.busyForTest(), "listing settles");
    }

    /** A re-sort does not flip the busy flag, so the model itself is the
     *  settle signal: poll the row names on the EDT until they match. */
    private static void awaitOrder(dock.commander.FilePane pane, List<String> wanted)
            throws Exception {
        for (int i = 0; i < 250; i++) {
            var seen = new AtomicReference<List<String>>();
            EventQueue.invokeAndWait(() -> {
                var m = (dock.commander.FileTableModel) pane.table().getModel();
                seen.set(java.util.stream.IntStream.range(0, m.getRowCount())
                        .mapToObj(m::row).map(dock.core.fs.FileEntry::name).toList());
            });
            if (wanted.equals(seen.get())) return;
            Thread.sleep(20);
        }
        throw new AssertionError("sort never settled on " + wanted);
    }
}
