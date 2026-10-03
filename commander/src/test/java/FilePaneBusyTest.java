import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rescan must never change the pane's layout. The busy indication is
 * the path bar's own background, so no progress component appears or
 * disappears mid-listing and nothing below the toolbar shifts — the old
 * 3px strip under the toolbar made the whole table jump on every load.
 */
class FilePaneBusyTest {

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    /** LocalFs delegate whose next list() parks until the test releases it. */
    private static final class HeldFs implements dock.core.fs.FileSystem {
        private final dock.core.fs.FileSystem inner = dock.core.fs.LocalFs.INSTANCE;
        private volatile boolean hold;
        private volatile CountDownLatch gate = new CountDownLatch(1);
        private final String home;

        HeldFs(String home) { this.home = home; }

        void holdNextList() { hold = true; }
        void release() { gate.countDown(); }

        @Override
        public List<dock.core.fs.FileEntry> list(String path) throws java.io.IOException {
            if (hold) {
                hold = false;
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(e);
                }
            }
            return inner.list(path);
        }

        @Override public String label() { return inner.label(); }
        @Override public boolean remote() { return inner.remote(); }
        @Override public String separator() { return inner.separator(); }
        @Override public String home() { return home; }
        @Override public List<String> roots() { return inner.roots(); }
        @Override public String normalize(String p) { return inner.normalize(p); }
        @Override public String parent(String p) { return inner.parent(p); }
        @Override public String child(String d, String n) { return inner.child(d, n); }
        @Override public boolean exists(String p) throws java.io.IOException { return inner.exists(p); }
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

    @Test
    void rescanBusyNeverShiftsTheTable() throws Exception {
        Path dir = Files.createTempDirectory("dock-busy");
        Files.writeString(dir.resolve("note.txt"), "hello");
        HeldFs fs = new HeldFs(dir.toString());
        var paneRef = new AtomicReference<dock.commander.FilePane>();
        var frameRef = new AtomicReference<JFrame>();
        EventQueue.invokeAndWait(() -> {
            var pane = new dock.commander.FilePane(fs);
            var frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(pane);
            frame.setSize(700, 500);
            frame.setLocation(-2000, 0);    // off-screen: no flash during tests
            frame.setVisible(true);
            paneRef.set(pane);
            frameRef.set(frame);
        });
        try {
            dock.commander.FilePane pane = paneRef.get();
            await(() -> !pane.busyForTest(), "initial listing settles");
            Rectangle before = edtBounds(pane);
            int componentsBefore = count(pane);
            assertTrue(before.height > 200, "table area must be substantial");

            // Rescan with the listing parked mid-flight.
            fs.holdNextList();
            EventQueue.invokeAndWait(pane::reload);
            await(pane::busyForTest, "rescan goes busy");
            assertEquals(before, edtBounds(pane), "the table must not shift while busy");
            assertEquals(componentsBefore, count(pane), "no component may appear for busy state");
            assertFalse(hasProgressBar(pane), "no progress bar may exist in the pane");

            fs.release();
            await(() -> !pane.busyForTest(), "listing settles after release");
            assertEquals(before, edtBounds(pane), "layout identical once idle again");
            assertEquals(componentsBefore, count(pane));
            assertFalse(hasProgressBar(pane));
        } finally {
            EventQueue.invokeAndWait(frameRef.get()::dispose);
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (java.io.IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }

    // ---- helpers ----

    /** Scroll pane bounds inside the pane, read on the EDT. */
    private static Rectangle edtBounds(dock.commander.FilePane pane) throws Exception {
        var r = new AtomicReference<Rectangle>();
        EventQueue.invokeAndWait(() -> {
            JScrollPane scroll = find(pane, JScrollPane.class);
            assertTrue(scroll != null, "the pane must contain the file table's scroll pane");
            r.set(scroll.getBounds());
        });
        return r.get();
    }

    private static <T extends Component> T find(Component c, Class<T> type) {
        if (type.isInstance(c)) return type.cast(c);
        if (c instanceof Container cc) {
            for (Component child : cc.getComponents()) {
                T hit = find(child, type);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static int count(Component c) {
        int n = 1;
        if (c instanceof Container cc) {
            for (Component child : cc.getComponents()) n += count(child);
        }
        return n;
    }

    private static boolean hasProgressBar(Component c) {
        if (c instanceof JProgressBar) return true;
        if (c instanceof Container cc) {
            for (Component child : cc.getComponents()) {
                if (hasProgressBar(child)) return true;
            }
        }
        return false;
    }

    private static void await(java.util.function.BooleanSupplier cond, String what)
            throws InterruptedException {
        for (int i = 0; i < 250 && !cond.getAsBoolean(); i++) Thread.sleep(20);
        assertTrue(cond.getAsBoolean(), "timed out waiting for: " + what);
    }
}
