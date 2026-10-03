import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.EventQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import javax.swing.JFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-session path memory: a saved session's panes reopen at the
 * directories they last showed, navigation records both sides back under
 * the session's name, and remembered paths that no longer exist fall back
 * to each filesystem's home instead of stranding an empty pane. Archive
 * interiors never count — the last plain directory stays remembered.
 */
class SessionPathMemoryTest {

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

    private interface Fill { void accept(Path root) throws Exception; }

    /** Supplies the site to apply once the temp dir exists. */
    private interface SiteFor { dock.core.config.Site at(Path root) throws Exception; }

    @TempDir
    static Path configDir;

    private dock.commander.SessionView view;
    private JFrame frame;
    private Path dir;

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
        dock.core.config.AppPaths.override(configDir);
    }

    @BeforeEach
    void freshStore() throws Exception {
        Files.deleteIfExists(configDir.resolve("sessions.json"));
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
    void reopeningRestoresBothPanesWhereTheyWereLeft() throws Exception {
        build(root -> {
            Files.createDirectory(root.resolve("docs"));
            Files.writeString(root.resolve("docs").resolve("keep.txt"), "kept");
            Files.createDirectory(root.resolve("local-work"));
            Files.writeString(root.resolve("local-work").resolve("note.txt"), "x");
        }, d -> rememberedSite().withPaths(d.resolve("local-work").toString(),
                d.resolve("docs").toString()));
        var remote = view.remotePane();
        var local = view.localPane();
        await(() -> remote.path().equals(norm(dir.resolve("docs")))
                && !remote.busyForTest(), "remote reopens in docs");
        await(() -> local.path().equals(norm(dir.resolve("local-work")))
                && !local.busyForTest(), "local reopens in local-work");
        assertTrue(remote.rowCount() >= 1, "the restored directory is listed");
    }

    @Test
    void navigationRecordsBothPathsUnderTheSession() throws Exception {
        build(root -> {
            Files.createDirectory(root.resolve("docs"));
            Files.writeString(root.resolve("f.txt"), "filler");
            Files.createDirectory(root.resolve("work"));
        }, d -> rememberedSite());
        var remote = view.remotePane();
        var local = view.localPane();
        await(() -> !remote.busyForTest() && !local.busyForTest(),
                "both panes settle on their homes");

        EventQueue.invokeAndWait(() -> {
            local.navigate(dir.resolve("work").toString());
            remote.navigate(dir.resolve("docs").toString());
        });
        await(() -> {
            var s = remembered();
            return s != null && norm(dir.resolve("work")).equals(s.lastLocalPath())
                    && norm(dir.resolve("docs")).equals(s.lastRemotePath());
        }, "sessions.json carries where both panes stand");
    }

    @Test
    void adHocSessionsRecordNothing() throws Exception {
        build(root -> Files.writeString(root.resolve("f.txt"), "filler"), null);
        var remote = view.remotePane();
        var local = view.localPane();
        await(() -> !remote.busyForTest() && !local.busyForTest(), "homes settle");
        EventQueue.invokeAndWait(() ->
                remote.navigate(dir.toString()));   // a plain reload of home
        Thread.sleep(300);   // any write had every chance
        assertNull(remembered(), "no site was applied — nothing is recorded");
    }

    @Test
    void deadRememberedPathsFallBackToTheirHomes() throws Exception {
        build(root -> Files.writeString(root.resolve("f.txt"), "filler"),
                d -> rememberedSite().withPaths(d.resolve("gone-local").toString(),
                        d.resolve("gone-remote").toString()));
        var remote = view.remotePane();
        var local = view.localPane();
        await(() -> local.path().equals(norm(Path.of(System.getProperty("user.home"))))
                && !local.busyForTest(), "local falls back to the user's home");
        await(() -> remote.path().equals(norm(dir)) && !remote.busyForTest(),
                "remote falls back to the connect directory");
        assertTrue(remote.rowCount() >= 1, "the pane is usable, not stranded empty");
    }

    @Test
    void archiveInteriorsAreNotRemembered() throws Exception {
        build(root -> {
            Files.createDirectory(root.resolve("docs"));
            Files.writeString(root.resolve("f.txt"), "filler");
        }, d -> rememberedSite());
        var remote = view.remotePane();
        var local = view.localPane();
        await(() -> {
            var s = remembered();
            return s != null && s.lastRemotePath() != null && s.lastLocalPath() != null;
        }, "the plain homes are recorded first");
        String recordedRemote = remembered().lastRemotePath();
        String recordedLocal = remembered().lastLocalPath();

        // A second filesystem stands in for a mount; landing inside it must
        // not overwrite what the session remembers.
        var mount = new TempFs(dir.toString());
        EventQueue.invokeAndWait(() ->
                remote.navigatePlace(mount, mount.normalize(dir.resolve("docs").toString())));
        await(() -> !remote.busyForTest(), "the interior listing settles");
        Thread.sleep(300);   // a rogue write had every chance
        var s = remembered();
        assertEquals(recordedRemote, s.lastRemotePath(),
                "the last plain directory stays remembered");
        assertEquals(recordedLocal, s.lastLocalPath());
    }

    // ---- helpers ----

    /** Upserts this test's site and returns it (fresh: nothing recorded). */
    private dock.core.config.Site rememberedSite() throws Exception {
        dock.core.config.Site s = new dock.core.config.Site("memory-unit", "nas", 22,
                "root", null);
        dock.core.config.Sites.upsert(s);
        return s;
    }

    /** The recorded state of this test's site, or null while unrecorded. */
    private dock.core.config.Site remembered() {
        return dock.core.config.Sites.load().stream()
                .filter(x -> x.name().equals("memory-unit"))
                .findFirst().orElse(null);
    }

    private static String norm(Path p) {
        return dock.core.fs.LocalFs.INSTANCE.normalize(p.toString());
    }

    /** Builds the view and applies the site in one EDT stretch — the same
     *  adjacency MainWindow guarantees, so the restores supersede the
     *  default opening listings deterministically. The factory only runs
     *  once the temp dir exists. */
    private void build(Fill fill, SiteFor siteFor) throws Exception {
        dir = Files.createTempDirectory("dock-memory");
        fill.accept(dir);
        dock.core.config.Site site = siteFor == null ? null : siteFor.at(dir);
        EventQueue.invokeAndWait(() -> {
            view = new dock.commander.SessionView(
                    new FakeSession(new TempFs(dir.toString())), "unit");
            if (site != null) view.applySite(site);
        });
        EventQueue.invokeAndWait(() -> {
            frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(view);
            frame.setSize(1000, 600);
            frame.setLocation(-2000, 0);    // off-screen: no flash during tests
            frame.setVisible(true);
        });
    }

    private static void await(java.util.function.BooleanSupplier cond, String what)
            throws InterruptedException {
        for (int i = 0; i < 250 && !cond.getAsBoolean(); i++) Thread.sleep(20);
        assertTrue(cond.getAsBoolean(), "timed out waiting for: " + what);
    }
}
