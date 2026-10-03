import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.EventQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.swing.JFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A '!' in a folder's own name is just a character — real servers are full
 * of such folders ("Toradora!", "Feuer Frei!"). Only a pre-bang segment
 * that names an archive earns the "archive.zip!/inner" mount syntax; any
 * other bang path navigates like an ordinary directory.
 */
class BangFolderNavigationTest {

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
    void aFolderNamedWithABangNavigatesLikeAnyFolder() throws Exception {
        build();
        String bangDir = dir.resolve("Toradora!").toString();
        EventQueue.invokeAndWait(() -> pane.navigate(bangDir));
        awaitIdle();
        assertEquals(fs, pane.fs(), "no mount was attempted");
        assertEquals(bangDir, pane.path());
        assertEquals(List.of("Season 2!", "episode-01.mkv"), rowNamesExceptParent());
    }

    @Test
    void aBangInADeeperSegmentStillNavigates() throws Exception {
        build();
        String deep = dir.resolve("Toradora!").resolve("Season 2!").toString();
        EventQueue.invokeAndWait(() -> pane.navigate(deep));
        awaitIdle();
        assertEquals(fs, pane.fs());
        assertEquals(deep, pane.path());
        assertEquals(List.of("episode-01.mkv"), rowNamesExceptParent());
    }

    @Test
    void anArchiveBangPathStillMounts() throws Exception {
        build();
        EventQueue.invokeAndWait(() -> pane.navigate(dir.resolve("b.zip") + "!/docs"));
        awaitIdle();
        assertTrue(pane.fs() instanceof dock.archive.ArchiveFs, "the mount syntax survives");
        assertEquals("/docs", pane.path());
        assertEquals(List.of("report.txt"), rowNamesExceptParent());
    }

    private void build() throws Exception {
        dir = Files.createTempDirectory("dock-bang");
        Files.writeString(dir.resolve("readme.txt"), "sibling");
        var nested = dir.resolve("Toradora!");
        Files.createDirectories(nested.resolve("Season 2!"));
        Files.writeString(nested.resolve("episode-01.mkv"), "x");
        Files.writeString(nested.resolve("Season 2!").resolve("episode-01.mkv"), "x");
        try (var out = new ZipOutputStream(Files.newOutputStream(dir.resolve("b.zip")))) {
            out.putNextEntry(new ZipEntry("docs/"));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("docs/report.txt"));
            out.write("the report".getBytes());
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

    private List<String> rowNamesExceptParent() throws Exception {
        var names = new java.util.ArrayList<String>();
        EventQueue.invokeAndWait(() -> {
            var t = pane.table();
            var m = (dock.commander.FileTableModel) t.getModel();
            for (int r = 0; r < t.getRowCount(); r++) {
                String n = m.row(r).name();
                if (!n.equals("..")) names.add(n);
            }
        });
        return names;
    }
}
