import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.EventQueue;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.TransferHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SMB's share selection — the server root listing — is virtual: its rows
 * are shares the server exported, not folders here. The pane treats it as
 * immutable: the mutating actions are absent from the menu, the keys
 * behind them no-op, and neither a drop nor an F5 transfer lands on it.
 */
class ImmutableListingTest {

    /** LocalFs rooted at the temp dir, so the pane lists only our files. */
    static class TempFs implements dock.core.fs.FileSystem {
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

    /** The same disk with the root posing as the server's share list. */
    private static final class ShareListFs extends TempFs {
        ShareListFs(String home) { super(home); }

        @Override public boolean immutableListing(String p) {
            return normalize(p).equals(home());
        }
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

    private dock.commander.FilePane pane;
    private dock.commander.SessionView view;
    private ShareListFs fs;
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
    void theMenuDropsItsMutatingActionsAtTheShareList() throws Exception {
        buildPane();
        Map<String, JMenuItem> items = menuItems();
        assertFalse(items.containsKey("New folder"), "no drafts among shares");
        assertFalse(items.containsKey("Rename…"));
        assertFalse(items.containsKey("Delete"));
        assertTrue(items.containsKey("Properties…"), "viewing stays");
        assertTrue(items.containsKey("Refresh"));
    }

    @Test
    void ctrlDAndDeleteNoOpAtTheShareList() throws Exception {
        buildPane();
        int before = pane.table().getRowCount();

        EventQueue.invokeAndWait(() -> pane.fireTableActionForTest("ctrl D"));
        assertFalse(pane.table().isEditing(), "no draft row appears");
        assertEquals(before, pane.table().getRowCount());

        selectByName("Public");
        EventQueue.invokeAndWait(() -> pane.fireTableActionForTest("DELETE"));
        awaitIdle();
        assertTrue(Files.isDirectory(dir.resolve("Public")), "nothing was deleted");
        assertEquals("Public", selectedName(), "the cursor is untouched");
    }

    @Test
    void dropsBounceOffTheShareList() throws Exception {
        buildPane();
        Path src = Files.createTempFile(dir.getParent(), "dock-share-drop", ".txt");
        try {
            Files.writeString(src, "payload");
            var handler = pane.getTransferHandler();
            EventQueue.invokeAndWait(() -> {
                var support = new TransferHandler.TransferSupport(pane, fileList(src));
                assertFalse(handler.canImport(support), "the pane is no drop target");
                assertFalse(handler.importData(support), "the drop bounces");
            });
            assertTrue(Files.notExists(dir.resolve(src.getFileName().toString())),
                    "nothing landed in the share list");
        } finally {
            Files.deleteIfExists(src);
        }
    }

    @Test
    void f5RefusesToTransferAShareList() throws Exception {
        buildView();
        selectByName(view.remotePane(), "Public");
        EventQueue.invokeAndWait(() -> view.remotePane().fireTableActionForTest("F5"));
        assertTrue(dock.core.transfer.TransferEngine.GLOBAL.snapshot().stream()
                        .noneMatch(j -> j.src() == fs || j.dst() == fs),
                "no job is enqueued against the share list");
    }

    // ---- helpers ----

    private void buildPane() throws Exception {
        dir = Files.createTempDirectory("dock-immutable");
        Files.createDirectory(dir.resolve("Public"));
        Files.createDirectory(dir.resolve("Docs"));
        fs = new ShareListFs(dir.toString());
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

    private void buildView() throws Exception {
        dir = Files.createTempDirectory("dock-immutable-view");
        Files.createDirectory(dir.resolve("Public"));
        Files.createDirectory(dir.resolve("Docs"));
        fs = new ShareListFs(dir.toString());
        EventQueue.invokeAndWait(() -> {
            view = new dock.commander.SessionView(new FakeSession(fs), "unit");
            frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(view);
            frame.setSize(1000, 600);
            frame.setLocation(-2000, 0);
            frame.setVisible(true);
        });
        awaitIdle();
    }

    private Map<String, JMenuItem> menuItems() throws Exception {
        var out = new AtomicReference<Map<String, JMenuItem>>();
        EventQueue.invokeAndWait(() -> {
            Map<String, JMenuItem> byText = new LinkedHashMap<>();
            javax.swing.JPopupMenu menu = pane.buildContextMenu();
            for (java.awt.Component c : menu.getComponents()) {
                if (c instanceof JMenuItem mi) byText.put(mi.getText(), mi);
            }
            out.set(byText);
        });
        return out.get();
    }

    /** A one-file OS file list — what an Explorer drag hands over. */
    private static Transferable fileList(Path file) {
        return new Transferable() {
            @Override public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[]{DataFlavor.javaFileListFlavor};
            }
            @Override public boolean isDataFlavorSupported(DataFlavor f) {
                return f.equals(DataFlavor.javaFileListFlavor);
            }
            @Override public Object getTransferData(DataFlavor f) {
                return List.of(file.toFile());
            }
        };
    }

    private void selectByName(String name) throws Exception {
        selectByName(pane, name);
    }

    private static void selectByName(dock.commander.FilePane target, String name)
            throws Exception {
        EventQueue.invokeAndWait(() -> {
            var t = target.table();
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
        EventQueue.invokeAndWait(() -> {
            int r = pane.table().getSelectedRow();
            if (r < 0) { n.set(null); return; }
            n.set(((dock.commander.FileTableModel) pane.table().getModel()).row(r).name());
        });
        return n.get();
    }

    private void awaitIdle() throws InterruptedException {
        dock.commander.FilePane target = pane != null ? pane : view.remotePane();
        for (int i = 0; i < 250 && target.busyForTest(); i++) Thread.sleep(20);
        assertTrue(!target.busyForTest(), "listing settles");
    }
}
