import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.EventQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.JFrame;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A failed navigation must leave the pane exactly where it was. The path
 * commits only when the listing succeeds — a dead link (reported by SMB as
 * an unchecked exception, by SFTP as an IOException) must not advance the
 * path bar click by click, and must not wedge the busy state either.
 */
class FilePaneNavFailureTest {

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    /** LocalFs delegate that fails listing anything under a poison directory. */
    private static final class FlakyFs implements dock.core.fs.FileSystem {
        private final dock.core.fs.FileSystem inner = dock.core.fs.LocalFs.INSTANCE;
        private volatile Supplier<RuntimeException> poison;

        void failNextWith(RuntimeException e) { poison = () -> e; }

        @Override
        public List<dock.core.fs.FileEntry> list(String path) throws java.io.IOException {
            Supplier<RuntimeException> p = poison;
            if (p != null && path.contains("poison")) {
                poison = null;
                throw p.get();
            }
            return inner.list(path);
        }

        @Override public String label() { return inner.label(); }
        @Override public boolean remote() { return inner.remote(); }
        @Override public String separator() { return inner.separator(); }
        @Override public String home() { return inner.home(); }
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
    void runtimeFailureRevertsPathAndClearsBusy() throws Exception {
        Path dir = Files.createTempDirectory("dock-nav1");
        Files.writeString(dir.resolve("note.txt"), "hello");
        FlakyFs fs = new FlakyFs();
        var paneRef = new AtomicReference<dock.commander.FilePane>();
        var frameRef = new AtomicReference<JFrame>();
        EventQueue.invokeAndWait(() -> {
            // Off-screen: no flash during tests.
            var pane = new dock.commander.FilePane(fs);
            var frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(pane);
            frame.setSize(700, 500);
            frame.setLocation(-2000, 0);
            frame.setVisible(true);
            paneRef.set(pane);
            frameRef.set(frame);
        });
        try {
            dock.commander.FilePane pane = paneRef.get();
            await(() -> !pane.busyForTest(), "initial listing settles");
            String home = pane.path();
            int rows = pane.rowCount();

            // The SMB flavor: a dead connection surfaces as an unchecked
            // exception, which the old catch (IOException) never saw — the
            // pane stayed busy forever and kept the appended path.
            fs.failNextWith(new RuntimeException("Connection reset"));
            String deadEnd = fs.child(home, "poison");
            EventQueue.invokeAndWait(() -> pane.navigate(deadEnd));
            await(() -> !pane.busyForTest(), "busy clears after runtime failure");
            assertEquals(home, pane.path(), "path must revert to the shown directory");
            assertEquals(rows, pane.rowCount(), "table keeps the listing it still has");

            // The pane recovers: a real directory still loads.
            Path good = Files.createDirectory(dir.resolve("good"));
            EventQueue.invokeAndWait(() -> pane.navigate(good.toString()));
            await(() -> !pane.busyForTest(), "good navigation settles");
            assertEquals(fs.normalize(good.toString()), pane.path());
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

    @Test
    void ioFailureRevertsPathAndClearsBusy() throws Exception {
        Path dir = Files.createTempDirectory("dock-nav2");
        Files.writeString(dir.resolve("note.txt"), "hello");
        FlakyFs fs = new FlakyFs();
        var paneRef = new AtomicReference<dock.commander.FilePane>();
        var frameRef = new AtomicReference<JFrame>();
        EventQueue.invokeAndWait(() -> {
            var pane = new dock.commander.FilePane(fs);
            var frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(pane);
            frame.setSize(700, 500);
            frame.setLocation(-2000, 0);
            frame.setVisible(true);
            paneRef.set(pane);
            frameRef.set(frame);
        });
        try {
            dock.commander.FilePane pane = paneRef.get();
            await(() -> !pane.busyForTest(), "initial listing settles");
            String home = pane.path();

            // The SFTP flavor: a plain IOException. The old code teleported
            // the pane to home on any failure; the shown directory stays.
            fs.failNextWith(new java.io.UncheckedIOException(
                    new java.io.IOException("permission denied")));
            EventQueue.invokeAndWait(() ->
                    pane.navigate(fs.child(home, "poison")));
            await(() -> !pane.busyForTest(), "busy clears after IO failure");
            assertEquals(home, pane.path(), "path must revert to the shown directory");
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

    private static void await(java.util.function.BooleanSupplier cond, String what)
            throws InterruptedException {
        for (int i = 0; i < 250 && !cond.getAsBoolean(); i++) Thread.sleep(20);
        assertTrue(cond.getAsBoolean(), "timed out waiting for: " + what);
    }
}
