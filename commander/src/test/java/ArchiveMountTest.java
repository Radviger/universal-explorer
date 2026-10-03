import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.EventQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The archive mount as the pane lives it: Enter on an archive file swaps in
 * the virtual filesystem, ".." and Backspace exit to the folder holding the
 * archive (cursor back on the file), Back/Forward cross the mount boundary,
 * typed "archive!/inner" paths mount and continue inside, the menu inside a
 * mount drops its mutating actions, "Open as archive" peeks into files no
 * extension would vouch for, and the exit row wears the archive mark and
 * amber tint at every depth.
 */
class ArchiveMountTest {

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
    private TempFs fs;

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @AfterEach
    void tearDown() throws Exception {
        EventQueue.invokeAndWait(() -> {
            pane.releaseMounts();
            frame.dispose();
        });
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (java.io.IOException e) { throw new RuntimeException(e); }
            });
        }
    }

    @Test
    void enterMountsTheArchiveAndBackspaceExitsToTheFolder() throws Exception {
        build();
        selectByName("b.zip");
        press("ENTER");
        awaitIdle();
        assertTrue(pane.fs() instanceof dock.archive.ArchiveFs, "the pane shows the mount");
        assertEquals("/", pane.path());
        assertEquals("..", nameAt(0), "the archive root keeps its way out");
        press("BACK_SPACE");
        awaitIdle();
        assertEquals(fs, pane.fs(), "Backspace at the root lands on the folder");
        assertEquals(dir.toString(), pane.path());
        assertEquals("b.zip", selectedName(), "the cursor returns to the archive file");
    }

    @Test
    void innerDirectoriesNavigateAndTheParentRowExitsOneLevel() throws Exception {
        build();
        selectByName("b.zip");
        press("ENTER");
        awaitIdle();
        selectByName("docs");
        press("ENTER");
        awaitIdle();
        assertEquals("/docs", pane.path());
        assertEquals(List.of("report.txt"), rowNamesExceptParent());

        selectByName("..");
        press("ENTER");
        awaitIdle();
        assertEquals("/", pane.path(), "the parent row exits one level, not out of the mount");
    }

    @Test
    void backAndForwardCrossTheMountBoundary() throws Exception {
        build();
        selectByName("b.zip");
        press("ENTER");
        awaitIdle();

        firePaneAction("alt LEFT");
        awaitIdle();
        assertEquals(fs, pane.fs(), "back walks out of the mount");
        assertEquals(dir.toString(), pane.path());

        firePaneAction("alt RIGHT");
        awaitIdle();
        assertTrue(pane.fs() instanceof dock.archive.ArchiveFs, "forward walks back in");
        assertEquals("/", pane.path());
    }

    @Test
    void theMenuInsideAMountDropsMutationsAndOffersOpenAsArchive() throws Exception {
        build();
        selectByName("b.zip");
        press("ENTER");
        awaitIdle();
        selectByName("docs");
        Map<String, JMenuItem> items = menuItems();
        assertFalse(items.containsKey("New folder"), "no drafts inside an archive");
        assertFalse(items.containsKey("Rename…"));
        assertFalse(items.containsKey("Delete"));
        assertTrue(items.containsKey("Properties…"));
        assertTrue(items.containsKey("Refresh"));
        assertTrue(items.containsKey("Open as archive"),
                "nested archives stay reachable through the menu");
    }

    @Test
    void openAsArchivePeeksInsideAnExtensionlessFile() throws Exception {
        build();
        Path zip = dir.resolve("b.zip");
        Files.copy(zip, dir.resolve("mystery"));
        EventQueue.invokeAndWait(() -> pane.reload());
        awaitIdle();
        selectByName("mystery");
        EventQueue.invokeAndWait(() -> menuItems().get("Open as archive").doClick());
        awaitIdle();
        assertTrue(pane.fs() instanceof dock.archive.ArchiveFs,
                "magic bytes, not the extension, decide");
        assertEquals("/", pane.path());
    }

    @Test
    void typedBangPathsMountAndContinueInside() throws Exception {
        build();
        awaitIdle();
        EventQueue.invokeAndWait(() -> pane.navigate(
                dir.resolve("b.zip") + "!/docs"));
        awaitIdle();
        assertTrue(pane.fs() instanceof dock.archive.ArchiveFs);
        assertEquals("/docs", pane.path());
        assertEquals(List.of("report.txt"), rowNamesExceptParent());
    }

    @Test
    void setFileSystemWhileMountedLandsOnTheFolder() throws Exception {
        build();
        selectByName("b.zip");
        press("ENTER");
        awaitIdle();

        TempFs reconnected = new TempFs(dir.toString());
        EventQueue.invokeAndWait(() -> pane.setFileSystem(reconnected));
        awaitIdle();
        assertEquals(reconnected, pane.fs(), "the mount dies with its host line");
        assertEquals(dir.toString(), pane.path(), "the pane lands on the holding folder");
    }

    @Test
    void theArchiveCrumbCarriesTheFullNameAndClicksBackToTheInnerRoot() throws Exception {
        build();
        selectByName("b.zip");
        press("ENTER");
        awaitIdle();
        selectByName("docs");
        press("ENTER");
        awaitIdle();
        var bar = pathBar();
        var crumb = new AtomicReference<Integer>();
        EventQueue.invokeAndWait(() -> {
            for (int i = 0; i < bar.pathletCount(); i++) {
                if ("b.zip".equals(bar.pathletTextForTest(i))) { crumb.set(i); return; }
            }
        });
        assertNotNull(crumb.get(), "the crumb carries the archive's own file name");
        int i = crumb.get();
        EventQueue.invokeAndWait(() -> {
            assertNotNull(bar.pathletIconForTest(i), "the archive mark rides the crumb");
            assertEquals(dir.resolve("b.zip").toString(), bar.pathletTooltipForTest(i),
                    "the tooltip keeps the full host path");
            bar.clickPathlet(i);
        });
        awaitIdle();
        assertEquals("/", pane.path(), "the crumb click returns to the inner root");
    }

    @Test
    void theParentRowWearsTheArchiveMarkAtEveryDepth() throws Exception {
        build();
        selectByName("b.zip");
        press("ENTER");
        awaitIdle();
        assertParentIcon(dock.commander.FileIcons.Kind.ARCHIVE,
                "at the root, \"..\" is the way out of the archive");
        selectByName("docs");
        press("ENTER");
        awaitIdle();
        assertParentIcon(dock.commander.FileIcons.Kind.ARCHIVE,
                "deeper, \"..\" still wears the archive mark");
    }

    @Test
    void theExitRowCarriesTheAmberTintAtEveryDepth() throws Exception {
        build();
        java.awt.image.BufferedImage[] plain = tintPair();
        assertFalse(differs(plain[0], plain[1], rowBand()),
                "a plain folder's \"..\" row stays untinted");
        selectByName("b.zip");
        press("ENTER");
        awaitIdle();
        java.awt.image.BufferedImage[] root = tintPair();
        assertTrue(differs(root[0], root[1], rowBand()),
                "the exit row carries the tint");
        assertFalse(differs(root[0], root[1], below(rowBand())),
                "the tint stays on the exit row alone");
        // A selection still wins: the way out must stay findable while picked.
        EventQueue.invokeAndWait(() -> pane.table().setRowSelectionInterval(0, 0));
        java.awt.image.BufferedImage[] picked = tintPair();
        assertFalse(differs(picked[0], picked[1], rowBand()),
                "a selected exit row reads as selected");
        EventQueue.invokeAndWait(() -> pane.table().clearSelection());
        selectByName("docs");
        press("ENTER");
        awaitIdle();
        java.awt.image.BufferedImage[] deep = tintPair();
        assertTrue(differs(deep[0], deep[1], rowBand()),
                "deeper in, the exit row keeps the tint");
    }

    private void assertParentIcon(dock.commander.FileIcons.Kind kind, String message)
            throws Exception {
        EventQueue.invokeAndWait(() -> {
            var t = pane.table();
            var c = t.getCellRenderer(0, 0).getTableCellRendererComponent(
                    t, t.getValueAt(0, 0), false, false, 0, 0);
            assertSame(dock.commander.FileIcons.icon(kind),
                    ((javax.swing.JLabel) c).getIcon(), message);
        });
    }

    private dock.commander.PathBar pathBar() {
        return (dock.commander.PathBar) find(pane, c -> c instanceof dock.commander.PathBar);
    }

    private static java.awt.Component find(java.awt.Component root,
                                           java.util.function.Predicate<java.awt.Component> p) {
        if (p.test(root)) return root;
        if (root instanceof java.awt.Container c) {
            for (var ch : c.getComponents()) {
                var hit = find(ch, p);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /** The table painted twice — with the theme's tint, then with the tint
     *  made fully transparent: whatever differs is the tint's doing. */
    private java.awt.image.BufferedImage[] tintPair() throws Exception {
        var out = new java.awt.image.BufferedImage[2];
        EventQueue.invokeAndWait(() -> {
            var t = pane.table();
            out[0] = paint(t);
            UIManager.put("Dock.archiveRowTint",
                    new javax.swing.plaf.ColorUIResource(new java.awt.Color(0, 0, 0, 0)));
            out[1] = paint(t);
            UIManager.put("Dock.archiveRowTint", null);    // back to the theme
        });
        return out;
    }

    /** The full-width band of row 0, where the exit row's ".." lives. */
    private java.awt.Rectangle rowBand() throws Exception {
        var r = new AtomicReference<java.awt.Rectangle>();
        EventQueue.invokeAndWait(() -> {
            var cell = pane.table().getCellRect(0, 0, true);
            r.set(new java.awt.Rectangle(0, cell.y,
                    Math.max(1, pane.table().getWidth()), cell.height));
        });
        return r.get();
    }

    /** Everything below {@code band} — generous, differs() clamps. */
    private static java.awt.Rectangle below(java.awt.Rectangle band) {
        return new java.awt.Rectangle(0, band.y + band.height, 4096, 4096);
    }

    private static java.awt.image.BufferedImage paint(java.awt.Component c) {
        var img = new java.awt.image.BufferedImage(Math.max(1, c.getWidth()),
                Math.max(1, c.getHeight()), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        c.paint(g);
        g.dispose();
        return img;
    }

    private static boolean differs(java.awt.image.BufferedImage a, java.awt.image.BufferedImage b,
                                   java.awt.Rectangle r) {
        for (int y = Math.max(0, r.y); y < r.y + r.height && y < a.getHeight(); y++) {
            for (int x = Math.max(0, r.x); x < r.x + r.width && x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) return true;
            }
        }
        return false;
    }

    // ---- fixtures & helpers ----
    private void build() throws Exception {
        dir = Files.createTempDirectory("dock-mount");
        Files.writeString(dir.resolve("z.txt"), "sibling");
        try (var out = new ZipOutputStream(Files.newOutputStream(dir.resolve("b.zip")))) {
            out.putNextEntry(new ZipEntry("docs/"));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("docs/report.txt"));
            out.write("the report".getBytes());
            out.closeEntry();
            out.putNextEntry(new ZipEntry("data.bin"));
            out.write(new byte[16]);
            out.closeEntry();
        }
        fs = new TempFs(dir.toString());
        EventQueue.invokeAndWait(() -> {
            pane = new dock.commander.FilePane(fs);
            frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(pane);
            frame.setSize(700, 500);
            frame.setLocation(-2000, 0);    // off-screen: no flash during tests
            frame.setVisible(true);
        });
        awaitIdle();
    }

    private void awaitIdle() throws InterruptedException {
        for (int i = 0; i < 250 && pane.busyForTest(); i++) Thread.sleep(20);
        assertTrue(!pane.busyForTest(), "listing settles");
    }

    private void press(String spec) throws Exception {
        EventQueue.invokeAndWait(() -> pane.fireTableActionForTest(spec));
    }

    /** Pane-level bindings (alt+Left/Right) live above the table's maps. */
    private void firePaneAction(String spec) throws Exception {
        EventQueue.invokeAndWait(() -> {
            KeyStroke ks = KeyStroke.getKeyStroke(spec);
            Object name = pane.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(ks);
            javax.swing.Action action = pane.getActionMap().get(name);
            action.actionPerformed(new java.awt.event.ActionEvent(pane, 0, "test"));
        });
    }

    private void selectByName(String name) throws Exception {
        EventQueue.invokeAndWait(() -> {
            var t = pane.table();
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

    private String selectedName() throws Exception {
        var n = new AtomicReference<String>();
        EventQueue.invokeAndWait(() -> n.set(nameAt(pane.table().getSelectedRow())));
        return n.get();
    }

    private String nameAt(int row) {
        if (row < 0) return null;
        var m = (dock.commander.FileTableModel) pane.table().getModel();
        return m.row(row).name();
    }

    private List<String> rowNamesExceptParent() throws Exception {
        var out = new AtomicReference<List<String>>();
        EventQueue.invokeAndWait(() -> {
            var names = new java.util.ArrayList<String>();
            var m = (dock.commander.FileTableModel) pane.table().getModel();
            for (int r = 0; r < pane.table().getRowCount(); r++) {
                if (m.row(r) != dock.core.fs.FileEntry.PARENT) names.add(m.row(r).name());
            }
            names.sort(Comparator.naturalOrder());
            out.set(names);
        });
        return out.get();
    }

    private Map<String, JMenuItem> menuItems() {
        Map<String, JMenuItem> byText = new LinkedHashMap<>();
        javax.swing.JPopupMenu menu = pane.buildContextMenu();
        for (java.awt.Component c : menu.getComponents()) {
            if (c instanceof JMenuItem mi) byText.put(mi.getText(), mi);
        }
        return byText;
    }
}
