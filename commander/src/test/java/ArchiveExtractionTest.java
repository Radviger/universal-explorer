import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.EventQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.swing.JFrame;
import javax.swing.TransferHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extraction is an ordinary transfer with a mount on one side: F5 from a
 * mounted archive copies entries out byte-for-byte, F6 (a move) is refused
 * because the archive can't lose rows, and nothing drops into the mount.
 */
class ArchiveExtractionTest {

    /** LocalFs rooted at the temp dir, so the remote pane lists only ours. */
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

    private static final byte[] PAYLOAD = new byte[100_000];

    static {
        for (int i = 0; i < PAYLOAD.length; i++) PAYLOAD[i] = (byte) (i * 13 + 5);
    }

    private dock.commander.SessionView view;
    private JFrame frame;
    private Path dir;
    private Path dest;

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @AfterEach
    void tearDown() throws Exception {
        EventQueue.invokeAndWait(() -> {
            view.localPane().releaseMounts();
            view.remotePane().releaseMounts();
            frame.dispose();
        });
        try (var walk = Files.walk(dir.getParent())) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                if (p.startsWith(dir) || p.startsWith(dest)) {
                    try { Files.deleteIfExists(p); }
                    catch (java.io.IOException e) { throw new RuntimeException(e); }
                }
            });
        }
    }

    @Test
    void f5FromAMountExtractsToTheOtherPane() throws Exception {
        build();
        mount();
        selectByName(view.remotePane(), "data.bin");
        EventQueue.invokeAndWait(() -> view.remotePane().fireTableActionForTest("F5"));
        await(() -> Files.exists(dest.resolve("data.bin"))
                && dock.core.transfer.TransferEngine.GLOBAL.snapshot().stream()
                        .noneMatch(dock.core.transfer.TransferJob::isActive));
        assertArrayEquals(PAYLOAD, Files.readAllBytes(dest.resolve("data.bin")),
                "extraction is a byte-for-byte copy");
        assertTrue(view.remotePane().fs() instanceof dock.archive.ArchiveFs,
                "the mount itself is untouched");
    }

    @Test
    void f6FromAMountIsRefusedAsAMove() throws Exception {
        build();
        mount();
        selectByName(view.remotePane(), "data.bin");
        EventQueue.invokeAndWait(() -> view.remotePane().fireTableActionForTest("F6"));
        var mountFs = new AtomicReference<dock.core.fs.FileSystem>();
        EventQueue.invokeAndWait(() -> mountFs.set(view.remotePane().fs()));
        assertTrue(dock.core.transfer.TransferEngine.GLOBAL.snapshot().stream()
                        .noneMatch(j -> j.src() == mountFs.get()),
                "a read-only source can't move rows out");
        assertFalse(Files.exists(dest.resolve("data.bin")));
    }

    @Test
    void dropsBounceOffTheMountedPane() throws Exception {
        build();
        mount();
        Path src = Files.createTempFile(dir.getParent(), "dock-extract-drop", ".txt");
        try {
            Files.writeString(src, "payload");
            var handler = view.remotePane().getTransferHandler();
            EventQueue.invokeAndWait(() -> {
                var support = fileList(view.remotePane(), src);
                assertFalse(handler.canImport(support), "the mount is no drop target");
                assertFalse(handler.importData(support), "the drop bounces");
            });
        } finally {
            Files.deleteIfExists(src);
        }
    }

    // ---- fixtures & helpers ----

    private void build() throws Exception {
        dir = Files.createTempDirectory("dock-extract");
        dest = Files.createTempDirectory("dock-extract-out");
        Files.writeString(dir.resolve("z.txt"), "sibling");
        try (var out = new ZipOutputStream(Files.newOutputStream(dir.resolve("b.zip")))) {
            out.putNextEntry(new ZipEntry("data.bin"));
            out.write(PAYLOAD);
            out.closeEntry();
        }
        EventQueue.invokeAndWait(() -> {
            view = new dock.commander.SessionView(new FakeSession(new TempFs(dir.toString())), "unit");
            frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(view);
            frame.setSize(1000, 600);
            frame.setLocation(-2000, 0);
            frame.setVisible(true);
        });
        awaitListed(view.remotePane());
        EventQueue.invokeAndWait(() -> view.localPane().navigate(dest.toString()));
        awaitListed(view.localPane());
    }

    private void mount() throws Exception {
        selectByName(view.remotePane(), "b.zip");
        EventQueue.invokeAndWait(() -> view.remotePane().fireTableActionForTest("ENTER"));
        awaitListed(view.remotePane());
        assertTrue(view.remotePane().fs() instanceof dock.archive.ArchiveFs);
    }

    /** A one-file OS file list — what an Explorer drag hands over. */
    private static TransferHandler.TransferSupport fileList(java.awt.Component dropTarget,
                                                            Path file) {
        return new TransferHandler.TransferSupport(dropTarget,
                new java.awt.datatransfer.Transferable() {
            @Override public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
                return new java.awt.datatransfer.DataFlavor[]{
                        java.awt.datatransfer.DataFlavor.javaFileListFlavor};
            }
            @Override public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor f) {
                return f.equals(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
            }
            @Override public Object getTransferData(java.awt.datatransfer.DataFlavor f) {
                return List.of(file.toFile());
            }
        });
    }

    private static void selectByName(dock.commander.FilePane pane, String name)
            throws Exception {
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

    private static void awaitListed(dock.commander.FilePane pane) throws InterruptedException {
        for (int i = 0; i < 250 && pane.busyForTest(); i++) Thread.sleep(20);
        assertTrue(!pane.busyForTest(), "listing settles");
    }

    private static void await(java.util.function.BooleanSupplier cond) throws InterruptedException {
        for (int i = 0; i < 500 && !cond.getAsBoolean(); i++) Thread.sleep(20);
        assertTrue(cond.getAsBoolean(), "condition not met within 10s");
    }
}
