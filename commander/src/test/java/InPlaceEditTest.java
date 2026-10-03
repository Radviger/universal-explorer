import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.EventQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import javax.swing.JTable;
import javax.swing.JTextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keyboard edits stay in the flow: Ctrl+D names a new folder in place — a
 * draft row below ".." whose name cell is a text field, committed to the
 * filesystem only by Enter (Escape discards it) — and after a delete the
 * cursor continues on the entries around the deleted one instead of
 * restarting from the top of the listing.
 */
class InPlaceEditTest {

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
    void ctrlDNamesTheFolderInPlaceAndEnterCommitsIt() throws Exception {
        build(root -> {
            Files.createDirectory(root.resolve("alpha"));
            Files.writeString(root.resolve("z.txt"), "x");
        });
        awaitIdle();
        int before = pane.table().getRowCount();

        press("ctrl D");
        assertTrue(pane.table().isEditing(), "the draft row's name cell is editing");
        var nameRef = new AtomicReference<String>();
        EventQueue.invokeAndWait(() -> {
            var m = (dock.commander.FileTableModel) pane.table().getModel();
            nameRef.set(m.row(0).name() + "|" + m.row(1).name());
        });
        assertEquals("..|", nameRef.get(), "the draft row sits right below \"..\"");
        EventQueue.invokeAndWait(() -> {
            JTextField field = (JTextField) pane.table().getEditorComponent();
            assertEquals("New folder", field.getText());
            assertEquals("New folder", field.getSelectedText(), "the default name is preselected");
            field.setText("brand");
        });

        EventQueue.invokeAndWait(() ->
                pane.table().getCellEditor().stopCellEditing());
        awaitIdle();

        assertTrue(Files.isDirectory(dir.resolve("brand")),
                "Enter is what commits the mkdir");
        assertEquals("brand", selectedName(), "the cursor lands on the new folder");
        assertEquals(before + 1, pane.table().getRowCount(),
                "the listing holds the new folder, not a draft row");
    }

    @Test
    void escapeDiscardsTheDraftWithoutTouchingTheDisk() throws Exception {
        build(root -> Files.writeString(root.resolve("z.txt"), "x"));
        awaitIdle();
        int before = pane.table().getRowCount();

        press("ctrl D");
        assertTrue(pane.table().isEditing());
        EventQueue.invokeAndWait(() ->
                pane.table().getCellEditor().cancelCellEditing());
        awaitIdle();

        assertEquals(before, pane.table().getRowCount(), "the draft row is dropped");
        assertFalse(Files.exists(dir.resolve("New folder")), "nothing was created");
        assertEquals(0, selectedCount());
    }

    @Test
    void deleteContinuesFromTheEntriesAroundTheDeletedOne() throws Exception {
        build(root -> {
            Files.writeString(root.resolve("a.txt"), "1");
            Files.writeString(root.resolve("b.txt"), "2");
            Files.writeString(root.resolve("c.txt"), "3");
            Files.writeString(root.resolve("d.txt"), "4");
        });
        EventQueue.invokeAndWait(pane::autoConfirmDeleteForTest);
        awaitIdle();

        // Deleting a middle entry lands the cursor on the one after it.
        selectByName("c.txt");
        press("DELETE");
        awaitIdle();
        assertTrue(Files.notExists(dir.resolve("c.txt")));
        assertEquals("d.txt", selectedName(), "the cursor continues past the deleted entry");
        press("UP");
        assertEquals("b.txt", selectedName(), "arrow-up walks the neighbours normally");
        press("DOWN");
        assertEquals("d.txt", selectedName(), "arrow-down walks the neighbours normally");

        // Deleting the last entry leaves the cursor on the one before it.
        press("DELETE");
        awaitIdle();
        assertTrue(Files.notExists(dir.resolve("d.txt")));
        assertEquals("b.txt", selectedName(), "the cursor clamps to the entry above");

        // Emptying the directory leaves nothing selected — arrow-down
        // takes ".." from there, the usual fresh-directory rule.
        press("DELETE");
        awaitIdle();
        press("DELETE");
        awaitIdle();
        assertEquals(0, selectedCount(), "an emptied directory starts unselected");
        press("DOWN");
        assertEquals("..", selectedName());
    }

    // ---- helpers ----

    private void build(IOThrowing<java.nio.file.Path> fill) throws Exception {
        dir = Files.createTempDirectory("dock-inplace");
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

    @Test
    void theDraftRowPaintsAsOneSelectedFolderRowWhileEditing() throws Exception {
        build(root -> Files.writeString(root.resolve("z.txt"), "x"));
        awaitIdle();
        press("ctrl D");
        assertTrue(pane.table().isEditing(), "the draft row's name cell is editing");
        // Focus moves from the table to the editor asynchronously, and the
        // table's selection color follows it — capture only once the edit
        // has settled, and read the color in the same EDT instant as the
        // paint.
        assertTrue(awaitEdt(() -> {
            var ec = pane.table().getEditorComponent();
            return ec != null && ec.isFocusOwner();
        }), "the editor takes the focus");
        EventQueue.invokeAndWait(() -> {
            JTable t = pane.table();
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                    t.getWidth(), t.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics g = img.createGraphics();
            t.paint(g);
            g.dispose();
            var m = (dock.commander.FileTableModel) t.getModel();
            int row = -1;
            for (int r = 0; r < t.getRowCount(); r++)
                if (m.row(r).name().isEmpty()) row = r;
            assertTrue(row > 0, "the draft row sits below \"..\"");
            int nameW = t.getColumnModel().getColumn(0).getWidth();
            int y = row * t.getRowHeight() + t.getRowHeight() / 2;
            int sel = t.getSelectionBackground().getRGB();
            assertEquals(sel, img.getRGB(nameW - 40, y),
                    "the editor paints the row's own selection, past the text");
            assertEquals(sel, img.getRGB(nameW + 40, y),
                    "the size column paints the same strip");
            assertEquals(sel, img.getRGB(t.getWidth() - 20, y),
                    "the row's last column too — no hole to the right");
            int glyphInk = 0;
            for (int yy = row * t.getRowHeight() + 3;
                    yy < (row + 1) * t.getRowHeight() - 3; yy++)
                for (int xx = 8; xx < 26; xx++)   // the glyph's seat; text starts at 32
                    if (img.getRGB(xx, yy) != sel) glyphInk++;
            assertTrue(glyphInk > 10,
                    "the folder glyph inks left of the input (" + glyphInk + " px)");
        });
        EventQueue.invokeAndWait(() ->
                pane.table().getCellEditor().cancelCellEditing());
        awaitIdle();
    }

    @Test
    void ctrlDScrollsTheDraftRowIntoViewWhenScrolledAway() throws Exception {
        build(root -> {
            for (int i = 0; i < 60; i++) Files.writeString(root.resolve("f" + i + ".txt"), "x");
        });
        awaitIdle();
        assertTrue(pane.table().getRowCount() > 20, "the listing overflows the pane");
        EventQueue.invokeAndWait(() -> {
            JTable t = pane.table();
            t.scrollRectToVisible(t.getCellRect(t.getRowCount() - 1, 0, true));
        });
        assertTrue(awaitEdt(() -> {
            var vp = (javax.swing.JViewport) javax.swing.SwingUtilities
                    .getAncestorOfClass(javax.swing.JViewport.class, pane.table());
            return vp != null && vp.getViewPosition().y > 0;
        }), "the pane starts scrolled away from the top");
        press("ctrl D");
        assertTrue(pane.table().isEditing(), "the draft row's name cell is editing");
        assertTrue(awaitEdt(() -> {
            JTable t = pane.table();
            var vp = (javax.swing.JViewport) javax.swing.SwingUtilities
                    .getAncestorOfClass(javax.swing.JViewport.class, t);
            return vp != null && vp.getViewRect().intersects(t.getCellRect(1, 0, true));
        }), "the draft row is scrolled back into view");
        EventQueue.invokeAndWait(() ->
                pane.table().getCellEditor().cancelCellEditing());
        awaitIdle();
    }

    /** Awaits a condition evaluated on the EDT; false on timeout. */
    private boolean awaitEdt(EdtCheck until)
            throws InterruptedException, java.lang.reflect.InvocationTargetException {
        for (int i = 0; i < 250; i++) {
            var ok = new AtomicReference<Boolean>();
            EventQueue.invokeAndWait(() -> ok.set(until.check()));
            if (Boolean.TRUE.equals(ok.get())) return true;
            Thread.sleep(20);
        }
        return false;
    }

    private interface EdtCheck { boolean check(); }

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

    @FunctionalInterface
    private interface IOThrowing<T> {
        void accept(T value) throws java.io.IOException;
    }
}
