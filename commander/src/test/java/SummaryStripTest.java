import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.EventQueue;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import javax.swing.JTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The strip under the table is the pane's own readout: directory totals at
 * idle, selection stats while a selection exists (the parent row never
 * counts). Hovering a row instead publishes its facts to the window
 * footer's status line — no tooltip floats over the table.
 */
class SummaryStripTest {

    /** LocalFs rooted at the temp dir, so the pane lists only our files. */
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

    private dock.commander.FilePane pane;
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
        dock.kit.StatusLine.clear();
        EventQueue.invokeAndWait(() -> frame.dispose());
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (java.io.IOException e) { throw new RuntimeException(e); }
            });
        }
    }

    @Test
    void selectionStatsReplaceTotalsAndComeBack() throws Exception {
        build(dir -> {
            Files.createDirectory(dir.resolve("alpha"));
            Files.writeString(dir.resolve("a.txt"), "0123456789");          // 10 B
            Files.write(dir.resolve("b.bin"), new byte[2048]);              // 2 KB
            Files.write(dir.resolve("c.md"), new byte[5_000_000]);
        });

        awaitIdle();
        assertEquals("1 directories, 3 files", edtText());

        // Rows: 0 "..", 1 "alpha", 2 "a.txt", 3 "b.bin", 4 "c.md".
        select(2, 3);
        assertEquals("2 selected — 0 directories, 2 files · " + dock.kit.Fmt.bytes(10 + 2048),
                edtText(), "two files selected: count split plus summed size");

        // The size rides its own label in the mono font so digits line up.
        var fam = new AtomicReference<String>();
        EventQueue.invokeAndWait(() ->
                fam.set(pane.summaryBytesFontForTest().getFamily()));
        assertEquals(dock.kit.FontRegistry.mono().getFamily(), fam.get(),
                "the size segment reads in mono");

        select(0, 2);
        assertEquals("1 selected — 0 directories, 1 files · " + dock.kit.Fmt.bytes(10),
                edtText(), "the parent row never counts toward selection stats");

        select(1);
        assertEquals("1 selected — 1 directories, 0 files", edtText(),
                "a directory alone carries no size segment");

        EventQueue.invokeAndWait(() -> pane.table().clearSelection());
        assertEquals("1 directories, 3 files", edtText(),
                "clearing the selection restores the totals");
    }

    @Test
    void hoveringPublishesRowFactsToTheStatusLine() throws Exception {
        build(dir -> {
            Files.createDirectory(dir.resolve("alpha"));
            Files.writeString(dir.resolve("a.txt"), "0123456789");
        });

        awaitIdle();
        var text = new AtomicReference<String>();
        EventQueue.invokeAndWait(() -> {
            JTable t = pane.table();
            // Row 2 is "a.txt": the line is the table's own columns, joined.
            var e = ((dock.commander.FileTableModel) t.getModel()).row(2);
            hover(t, 2);
            text.set("a.txt · " + dock.commander.FileTableModel.sizeString(e)
                    + " · modified " + dock.commander.FileTableModel.dateString(e));
        });
        assertEquals(text.get(), dock.kit.StatusLine.text(), "hover line joins the row's columns");
        assertTrue(dock.kit.StatusLine.mono(), "file facts publish as mono");

        EventQueue.invokeAndWait(() -> hover(pane.table(), 0));
        assertEquals("", dock.kit.StatusLine.text(), "the parent row publishes nothing");

        EventQueue.invokeAndWait(() -> {
            for (var l : pane.table().getMouseMotionListeners())
                if (l instanceof java.awt.event.MouseAdapter a)
                    a.mouseExited(new MouseEvent(pane.table(), MouseEvent.MOUSE_EXITED,
                            System.currentTimeMillis(), 0, 0, 0, 0, false));
        });
        assertEquals("", dock.kit.StatusLine.text(), "leaving the table clears the line");
    }

    // ---- helpers ----

    private void build(IOThrowing<java.nio.file.Path> fill) throws Exception {
        dir = Files.createTempDirectory("dock-summary");
        fill.accept(dir);
        EventQueue.invokeAndWait(() -> {
            pane = new dock.commander.FilePane(new TempFs(dir.toString()));
            frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(pane);
            frame.setSize(700, 500);
            frame.setLocation(-2000, 0);    // off-screen: no flash during tests
            frame.setVisible(true);
        });
    }

    private void awaitIdle() throws InterruptedException {
        for (int i = 0; i < 250 && pane.busyForTest(); i++) Thread.sleep(20);
        assertTrue(!pane.busyForTest(), "listing settles");
    }

    private String edtText() throws Exception {
        var t = new AtomicReference<String>();
        EventQueue.invokeAndWait(() -> t.set(pane.summaryTextForTest()));
        return t.get();
    }

    private void select(int... rows) throws Exception {
        EventQueue.invokeAndWait(() -> pane.table().clearSelection());
        EventQueue.invokeAndWait(() -> {
            for (int r : rows) pane.table().addRowSelectionInterval(r, r);
        });
    }

    /** Dispatches a synthetic move into the middle of {@code row}. */
    private static void hover(JTable t, int row) {
        MouseEvent e = new MouseEvent(t, MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(), 0, 10, row * t.getRowHeight() + 5, 0, false);
        for (var l : t.getMouseMotionListeners()) l.mouseMoved(e);
    }

    @FunctionalInterface
    private interface IOThrowing<T> {
        void accept(T value) throws java.io.IOException;
    }
}
