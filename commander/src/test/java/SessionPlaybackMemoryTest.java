import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.EventQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import javax.swing.JFrame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The player's bookmark lands in the session file: closing the player
 * records where playback stood under the session's site, and a bookmark
 * from an earlier run offers itself the next time the file opens.
 */
class SessionPlaybackMemoryTest {

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

    /** The deterministic engine: duration on demand, the clock by seek. */
    private static final class FakeEngine implements dock.media.MediaEngine {
        final List<Long> seeks = new java.util.ArrayList<>();
        volatile boolean opened;
        private dock.media.MediaEngine.Listener listener =
                dock.media.MediaEngine.Listener.NOTHING;
        private final javax.swing.JLabel surface = new javax.swing.JLabel();
        private boolean playing;
        private long timeMs;

        void setDuration(long ms) { listener.duration(ms); }

        @Override public javax.swing.JComponent surface() { return surface; }
        @Override public void setListener(dock.media.MediaEngine.Listener l) { listener = l; }
        @Override public void setOverlay(dock.media.MediaEngine.Overlay o) { }
        @Override public void open(String url) { opened = true; playing = true; listener.playing(); }
        @Override public void play() { playing = true; listener.playing(); }
        @Override public void pause() { playing = false; listener.paused(); }
        @Override public void stop() { playing = false; }
        @Override public void seekMs(long ms) { timeMs = ms; seeks.add(ms); }
        @Override public long timeMs() { return timeMs; }
        @Override public long durationMs() { return 0; }
        @Override public boolean isPlaying() { return playing; }
        @Override public void setVolume(int percent) { }
        @Override public void setMute(boolean mute) { }
        @Override public void loadSubtitle(java.io.File file) { }
        @Override public void close() { }
    }

    private static FakeEngine engine;

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
        dock.media.PlayerPanel.useEngineFactoryForTest(() -> engine);
    }

    @AfterAll
    static void restore() {
        dock.media.PlayerPanel.useEngineFactoryForTest(null);
        dock.media.PlayerPanel.useResumeAskForTest(null);
    }

    @BeforeEach
    void freshStore() throws Exception {
        // A fresh engine per test: the shared one would carry a stale
        // "opened" from the previous test, and a duration fired at that
        // ghost would be dropped before the new panel arms its listener.
        engine = new FakeEngine();
        Files.deleteIfExists(configDir.resolve("sessions.json"));
        dock.core.config.Sites.upsert(new dock.core.config.Site(
                "playback-unit", "nas", 22, "root", null));
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
    void closingThePlayerRecordsThePositionInTheSessionFile() throws Exception {
        build(root -> Files.write(root.resolve("film.mp4"), new byte[32]));
        var remote = view.remotePane();
        await(() -> !remote.busyForTest(), "the listing settles");
        EventQueue.invokeAndWait(() -> remote.selectEntry("film.mp4"));
        EventQueue.invokeAndWait(() -> remote.fireTableActionForTest("ENTER"));
        assertTrue(view.showingPlayer(remote), "the player stands in for the pane");
        await(() -> engine.opened, "the engine is wired to the file");
        EventQueue.invokeAndWait(() -> engine.setDuration(600_000));
        EventQueue.invokeAndWait(() -> engine.seekMs(300_000));   // half-way
        EventQueue.invokeAndWait(() -> view.firePlayerKeyForTest(remote, "ESCAPE"));
        String file = norm(dir.resolve("film.mp4"));
        await(() -> {
            var s = remembered();
            return s != null && s.playback() != null
                    && s.playback().get(file) != null;
        }, "sessions.json carries where playback stood");
        assertEquals(300_000L, remembered().playback().get(file).positionMs());
    }

    @Test
    void aBookmarkFromAnEarlierRunOffersResume() throws Exception {
        build(root -> Files.write(root.resolve("film.mp4"), new byte[32]));
        String file = norm(dir.resolve("film.mp4"));
        long mtime = Files.getLastModifiedTime(Path.of(file)).toMillis();
        dock.core.config.Sites.recordPlayback(
                "playback-unit", file, mtime, 240_000L);
        List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        dock.media.PlayerPanel.useResumeAskForTest((parent, name, at) -> {
            asked.add(name + "@" + at);
            return true;
        });
        var remote = view.remotePane();
        await(() -> !remote.busyForTest(), "the listing settles");
        EventQueue.invokeAndWait(() -> remote.selectEntry("film.mp4"));
        EventQueue.invokeAndWait(() -> remote.fireTableActionForTest("ENTER"));
        assertTrue(view.showingPlayer(remote), "the player stands in for the pane");
        await(() -> engine.opened, "the engine is wired to the file");
        EventQueue.invokeAndWait(() -> engine.setDuration(600_000));
        await(() -> !asked.isEmpty(), "the bookmark offered itself");
        assertTrue(asked.get(0).startsWith("film.mp4@"),
                "the offer names the file and time (" + asked.get(0) + ")");
        await(() -> !engine.seeks.isEmpty()
                        && engine.seeks.getLast() == 240_000L,
                "YES seeks to the bookmark");
        EventQueue.invokeAndWait(() -> view.firePlayerKeyForTest(remote, "ESCAPE"));
    }

    // ---- helpers ----

    /** The recorded state of this test's site, or null while unrecorded. */
    private dock.core.config.Site remembered() {
        return dock.core.config.Sites.load().stream()
                .filter(x -> x.name().equals("playback-unit"))
                .findFirst().orElse(null);
    }

    private static String norm(Path p) {
        return dock.core.fs.LocalFs.INSTANCE.normalize(p.toString());
    }

    /** Supplies the pane's files once the temp dir exists. */
    private interface Fill { void accept(Path root) throws Exception; }

    /** Builds the seated view with the site applied, like MainWindow does. */
    private void build(Fill fill) throws Exception {
        dir = Files.createTempDirectory("dock-playback");
        fill.accept(dir);
        EventQueue.invokeAndWait(() -> {
            view = new dock.commander.SessionView(
                    new FakeSession(new TempFs(dir.toString())), "unit");
            view.applySite(dock.core.config.Sites.load().get(0));
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

    private static void await(java.util.function.BooleanSupplier until, String what)
            throws InterruptedException {
        for (int i = 0; i < 250 && !until.getAsBoolean(); i++) Thread.sleep(20);
        assertTrue(until.getAsBoolean(), what + " (timed out)");
    }
}
