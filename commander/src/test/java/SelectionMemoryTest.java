import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.EventQueue;
import java.awt.Rectangle;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scanning folders on the keyboard — enter, check contents, Backspace,
 * arrow down, enter — needs the pane to remember where the cursor was:
 * leaving a directory records its selection, returning restores it
 * (scrolled into view). A first visit or a vanished name selects nothing,
 * and the first arrow-down lands on a real entry, never on ".." —
 * keyboard landings (a fresh session, a pane hop) only focus, never
 * select.
 */
class SelectionMemoryTest {

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
        EventQueue.invokeAndWait(() -> frame.dispose());
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (java.io.IOException e) { throw new RuntimeException(e); }
            });
        }
    }

    @Test
    void keyboardScanLoopPutsTheCursorBack() throws Exception {
        build(root -> {
            Files.createDirectory(root.resolve("alpha"));
            Files.createDirectory(root.resolve("beta"));
            Files.createDirectory(root.resolve("gamma"));
            Files.writeString(root.resolve("z.txt"), "hello");
        });
        awaitIdle();

        // Enter "beta" with the real ENTER binding.
        selectByName("beta");
        press("ENTER");
        awaitIdle();
        assertTrue(pane.path().endsWith("beta"), "we are inside beta");
        assertEquals(0, selectedCount(), "a fresh directory selects nothing");

        // Backspace returns to the root and the cursor is back on beta.
        press("BACK_SPACE");
        awaitIdle();
        assertEquals("beta", selectedName(), "returning restores the row we left");

        // The loop continues: arrow down to the next sibling, enter, back.
        selectByName("gamma");
        press("ENTER");
        awaitIdle();
        press("BACK_SPACE");
        awaitIdle();
        assertEquals("gamma", selectedName(), "the scan continues from the next folder");
    }

    @Test
    void reloadKeepsTheCursor() throws Exception {
        build(root -> {
            Files.createDirectory(root.resolve("alpha"));
            Files.writeString(root.resolve("z.txt"), "hello");
        });
        awaitIdle();
        selectByName("z.txt");
        EventQueue.invokeAndWait(pane::reload);
        awaitIdle();
        assertEquals("z.txt", selectedName(), "a plain refresh must not drop the cursor");
    }

    @Test
    void returningScrollsTheCursorIntoView() throws Exception {
        build(root -> {
            Files.createDirectory(root.resolve("sub"));
            for (int i = 0; i < 200; i++) {
                Files.writeString(root.resolve("f%03d".formatted(i)), "x");
            }
        });
        awaitIdle();

        // Leave from a row far below the fold, then come back.
        selectByName("f180");
        EventQueue.invokeAndWait(() -> pane.navigate(dir.resolve("sub").toString()));
        awaitIdle();
        EventQueue.invokeAndWait(() -> pane.navigate(dir.toString()));
        awaitIdle();

        assertEquals("f180", selectedName(), "deep row restored");
        var cellRef = new AtomicReference<Rectangle>();
        EventQueue.invokeAndWait(() -> {
            JTable t = pane.table();
            cellRef.set(t.getCellRect(t.getSelectedRow(), 0, true));
            assertTrue(t.getVisibleRect().contains(cellRef.get()),
                    "the restored row must be scrolled into view");
        });
    }

    @Test
    void vanishedEntrySelectsNothing() throws Exception {
        build(root -> {
            Files.createDirectory(root.resolve("alpha"));
            Files.createDirectory(root.resolve("beta"));
        });
        awaitIdle();
        selectByName("beta");
        press("ENTER");
        awaitIdle();
        deleteRecursive(dir.resolve("beta"));

        press("BACK_SPACE");
        awaitIdle();
        assertNull(selectedName(), "a name gone from the listing selects nothing");
        assertEquals("1 directories, 0 files", summaryText(),
                "the strip falls back to the directory's totals");
    }

    @Test
    void arrowDownNeverStartsOnTheParentRow() throws Exception {
        build(root -> {
            Files.createDirectory(root.resolve("alpha"));
            Files.createDirectory(root.resolve("beta"));
        });
        awaitIdle();
        assertEquals(0, selectedCount(), "a fresh directory selects nothing");

        // The first arrow-down lands on the first real entry, not "..".
        press("DOWN");
        assertEquals("alpha", selectedName(), "arrow-down starts below the parent row");

        // ".." stays reachable, and arrow-down moves off it normally.
        press("UP");
        assertEquals("..", selectedName(), "arrow-up still reaches the parent row");
        press("DOWN");
        assertEquals("alpha", selectedName(), "arrow-down moves off the parent row normally");

        // A directory that holds nothing else still selects its only row.
        selectByName("alpha");
        press("ENTER");
        awaitIdle();
        assertEquals(0, selectedCount(), "an empty listing starts unselected");
        press("DOWN");
        assertEquals("..", selectedName(), "an empty directory selects its only row");
    }

    @Test
    void focusingTheTableNeverTouchesTheSelection() throws Exception {
        build(root -> {
            Files.createDirectory(root.resolve("alpha"));
            Files.writeString(root.resolve("z.txt"), "x");
        });
        awaitIdle();

        // A keyboard landing focuses the table; scanning is the user's move.
        EventQueue.invokeAndWait(pane::focusTable);
        assertEquals(0, selectedCount(), "a fresh directory still selects nothing");
        press("DOWN");
        assertEquals("alpha", selectedName(), "arrow-down starts the scan on \"..\"-skip");

        // A landing never moves a cursor that already exists.
        selectByName("z.txt");
        EventQueue.invokeAndWait(pane::focusTable);
        assertEquals("z.txt", selectedName(), "an existing selection is left alone");
    }

    // ---- helpers ----

    private void build(IOThrowing<java.nio.file.Path> fill) throws Exception {
        dir = Files.createTempDirectory("dock-selmem");
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

    /** Selects the row showing {@code name} (the model's row order). */
    private void selectByName(String name) throws Exception {
        EventQueue.invokeAndWait(() -> {
            JTable t = pane.table();
            var m = (dock.commander.FileTableModel) t.getModel();
            for (int r = 0; r < t.getRowCount(); r++) {
                if (name.equals(m.row(r).name())) {
                    t.setRowSelectionInterval(r, r);
                    return;
                }
            }
            throw new IllegalStateException("no row named " + name);
        });
    }

    /** Fires the table's key binding — the real keyboard path. */
    private void press(String spec) throws Exception {
        EventQueue.invokeAndWait(() -> pane.fireTableActionForTest(spec));
    }

    private String selectedName() throws Exception {
        var n = new AtomicReference<String>();
        EventQueue.invokeAndWait(() -> {
            int r = pane.table().getSelectedRow();
            if (r < 0) { n.set(null); return; }
            n.set(((dock.commander.FileTableModel) pane.table().getModel()).row(r).name());
        });
        return n.get();
    }

    private int selectedCount() throws Exception {
        var c = new AtomicReference<Integer>();
        EventQueue.invokeAndWait(() -> c.set(pane.table().getSelectedRowCount()));
        return c.get();
    }

    private String summaryText() throws Exception {
        var t = new AtomicReference<String>();
        EventQueue.invokeAndWait(() -> t.set(pane.summaryTextForTest()));
        return t.get();
    }

    private static void deleteRecursive(Path p) throws Exception {
        try (var walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(x -> {
                try { Files.deleteIfExists(x); }
                catch (java.io.IOException e) { throw new RuntimeException(e); }
            });
        }
    }

    @FunctionalInterface
    private interface IOThrowing<T> {
        void accept(T value) throws java.io.IOException;
    }
}
