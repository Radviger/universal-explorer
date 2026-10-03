import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.JLabel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Properties dialog contract: the facts section renders the entry's data in
 * the listing snapshot, local (POSIX-less) entries get no permission grid,
 * and the grid's octal edits flow through setPerms with Apply/OK/error
 * semantics — all without a real network backend.
 */
class PropertiesDialogTest {

    private static final long MTIME = Instant.parse("2026-01-15T10:30:00Z").toEpochMilli();

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @Test
    void localFileShowsFactsAndNoPermsGrid() throws Exception {
        var entry = new dock.core.fs.FileEntry("notes.txt", false, 1234, MTIME, null, false);
        String[] texts = new String[1];
        EventQueue.invokeAndWait(() -> {
            var d = new dock.commander.PropertiesDialog(null, dock.core.fs.LocalFs.INSTANCE,
                    "C:\\temp", entry, () -> {});
            texts[0] = allTexts(d);
        });
        assertTrue(texts[0].contains("notes.txt"), "name shown: " + texts[0]);
        assertTrue(texts[0].contains("File"), "type shown: " + texts[0]);
        assertTrue(texts[0].contains("C:\\temp"), "location shown: " + texts[0]);
        assertTrue(texts[0].contains("1234 bytes"), "exact size shown: " + texts[0]);
        // Locale-dependent decimal separator: expect the same rendering the
        // formatter produces, not a hardcoded "1.2".
        assertTrue(texts[0].contains("%.1f KB".formatted(1234 / 1024.0)),
                "human size shown: " + texts[0]);
        assertTrue(texts[0].contains(expectedStamp()), "mtime shown: " + texts[0]);
        assertFalse(texts[0].contains("Octal:"), "no perms section for local entries");
    }

    @Test
    void directoryShowsFolderTypeAndDashSize() throws Exception {
        var entry = new dock.core.fs.FileEntry("backup", true, 4096, MTIME, 0755, false);
        String[] texts = new String[1];
        EventQueue.invokeAndWait(() -> {
            var d = new dock.commander.PropertiesDialog(null, dock.core.fs.LocalFs.INSTANCE,
                    "C:\\temp", entry, () -> {});
            texts[0] = allTexts(d);
        });
        assertTrue(texts[0].contains("Folder"), "type shown: " + texts[0]);
        assertTrue(texts[0].contains("—"), "directories show no size: " + texts[0]);
        assertFalse(texts[0].contains("4096"), "raw dir size not shown: " + texts[0]);
    }

    @Test
    void nameRowCarriesTheTypeIcon() throws Exception {
        var log = new dock.core.fs.FileEntry("server.log", false, 512, MTIME, null, false);
        var dir = new dock.core.fs.FileEntry("backup", true, 4096, MTIME, 0755, false);
        var dialogs = new dock.commander.PropertiesDialog[2];
        EventQueue.invokeAndWait(() -> {
            dialogs[0] = new dock.commander.PropertiesDialog(null, dock.core.fs.LocalFs.INSTANCE,
                    "C:\\temp", log, () -> {});
            dialogs[1] = new dock.commander.PropertiesDialog(null, dock.core.fs.LocalFs.INSTANCE,
                    "C:\\temp", dir, () -> {});
        });
        var icons = new javax.swing.Icon[2];
        EventQueue.invokeAndWait(() -> {
            icons[0] = dialogs[0].nameIconForTest();
            icons[1] = dialogs[1].nameIconForTest();
        });
        assertSame(dock.commander.FileIcons.icon(dock.commander.FileIcons.Kind.LOG,
                dock.kit.Tokens.ICON_LARGE), icons[0], "log entry gets the scroll icon");
        assertSame(dock.commander.FileIcons.icon(dock.commander.FileIcons.Kind.FOLDER,
                dock.kit.Tokens.ICON_LARGE), icons[1], "directory gets the folder icon");
    }

    @Test
    void permEditsApplyAndOkCloses() throws Exception {
        FakeRemoteFs fs = new FakeRemoteFs();
        var entry = new dock.core.fs.FileEntry("script.sh", false, 512, MTIME, 0644, false);
        var dialog = new dock.commander.PropertiesDialog[1];
        boolean[] changed = new boolean[1];
        EventQueue.invokeAndWait(() -> dialog[0] = new dock.commander.PropertiesDialog(
                null, fs, "/srv", entry, () -> changed[0] = true));

        EventQueue.invokeAndWait(() -> {
            assertTrue("0644".equals(dialog[0].octalForTest()), "initial octal");
            assertFalse(dialog[0].applyButtonForTest().isEnabled(), "apply idle while clean");
            // Index 7 = others-write (order: owner rwx, group rwx, others rwx).
            dialog[0].permBoxForTest(7).doClick();
        });
        assertTrue("0646".equals(dialog[0].octalForTest()), "octal after others-write");
        assertTrue(dialog[0].applyButtonForTest().isEnabled(), "apply armed when dirty");

        EventQueue.invokeAndWait(() -> dialog[0].applyButtonForTest().doClick());
        await(() -> !fs.calls.isEmpty(), "setPerms called");
        assertTrue(fs.calls.get(0)[0] == 0646 && fs.paths.get(0).equals("/srv/script.sh"),
                "applied 0646 to " + fs.paths.get(0));
        await(() -> changed[0], "onChanged fired after apply");
        assertFalse(dialog[0].applyButtonForTest().isEnabled(), "apply idle after write");

        // Flip back and close via OK, which applies first.
        EventQueue.invokeAndWait(() -> {
            dialog[0].permBoxForTest(7).doClick();
            dialog[0].okButtonForTest().doClick();
        });
        await(() -> fs.calls.size() == 2, "second setPerms called");
        assertTrue(fs.calls.get(1)[0] == 0644, "restored 0644");
        await(() -> !dialog[0].isDisplayable(), "dialog disposed after OK");
    }

    @Test
    void failedApplyShowsErrorAndKeepsDialogOpen() throws Exception {
        FakeRemoteFs fs = new FakeRemoteFs();
        fs.fail = true;
        var entry = new dock.core.fs.FileEntry("script.sh", false, 512, MTIME, 0600, false);
        var dialog = new dock.commander.PropertiesDialog[1];
        EventQueue.invokeAndWait(() -> dialog[0] = new dock.commander.PropertiesDialog(
                null, fs, "/srv", entry, () -> {}));
        EventQueue.invokeAndWait(() -> {
            dialog[0].permBoxForTest(2).doClick(); // owner execute
            dialog[0].applyButtonForTest().doClick();
        });
        await(() -> dialog[0].errorVisibleForTest(), "error surfaced");
        assertTrue(dialog[0].applyButtonForTest().isEnabled(),
                "apply re-armed after failure so the user can retry");
        assertTrue(dialog[0].okButtonForTest().isEnabled(), "OK usable after failure");
        assertTrue(fs.calls.isEmpty(), "failed write recorded nothing");
    }

    @Test
    void immutableListingsShowFactsButNoPermsGrid() throws Exception {
        var fs = new FakeRemoteFs() {
            @Override public boolean immutableListing(String p) { return true; }
        };
        var entry = new dock.core.fs.FileEntry("inzip.sh", false, 512, MTIME, 0644, false);
        var dialog = new dock.commander.PropertiesDialog[1];
        String[] texts = new String[1];
        EventQueue.invokeAndWait(() -> {
            dialog[0] = new dock.commander.PropertiesDialog(null, fs, "/mnt", entry, () -> {});
            texts[0] = allTexts(dialog[0]);
        });
        assertTrue(texts[0].contains("inzip.sh"), "facts render");
        assertTrue(texts[0].contains("512 bytes"), "size renders");
        assertFalse(texts[0].contains("Octal:"),
                "modes read in the table's attrs column, never an editable grid here");
        assertFalse(dialog[0].permsSectionVisibleForTest());
    }

    // ---- helpers ----

    private static String expectedStamp() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(MTIME));
    }

    private static String allTexts(Container c) {
        List<String> out = new ArrayList<>();
        collect(c, out);
        return String.join("\n", out);
    }

    private static void collect(Container c, List<String> out) {
        for (Component child : c.getComponents()) {
            if (child instanceof JLabel l && !l.getText().isEmpty()) out.add(l.getText());
            if (child instanceof Container cc) collect(cc, out);
        }
    }

    private static void await(java.util.function.BooleanSupplier cond, String what)
            throws InterruptedException {
        for (int i = 0; i < 250 && !cond.getAsBoolean(); i++) Thread.sleep(20);
        assertTrue(cond.getAsBoolean(), "timed out waiting for: " + what);
    }

    /** Minimal remote filesystem recording setPerms; everything else is unused. */
    static class FakeRemoteFs implements dock.core.fs.FileSystem {
        final List<int[]> calls = new CopyOnWriteArrayList<>();
        final List<String> paths = new CopyOnWriteArrayList<>();
        volatile boolean fail;

        @Override public String label() { return "fake"; }
        @Override public boolean remote() { return true; }
        @Override public String separator() { return "/"; }
        @Override public String home() { return "/home/u"; }
        @Override public List<String> roots() { return List.of("/"); }
        @Override public String normalize(String p) { return p; }
        @Override public String parent(String p) {
            int cut = p.lastIndexOf('/');
            return cut <= 0 ? "/" : p.substring(0, cut);
        }
        @Override public String child(String d, String n) {
            return (d.endsWith("/") ? d : d + "/") + n;
        }
        @Override public boolean exists(String p) { return true; }
        @Override public List<dock.core.fs.FileEntry> list(String p) { return List.of(); }
        @Override public dock.core.fs.FileEntry stat(String p) {
            throw new UnsupportedOperationException();
        }
        @Override public void mkdir(String p) { throw new UnsupportedOperationException(); }
        @Override public void delete(String p) { throw new UnsupportedOperationException(); }
        @Override public void rename(String f, String t) { throw new UnsupportedOperationException(); }
        @Override public java.io.InputStream read(String p) { throw new UnsupportedOperationException(); }
        @Override public java.io.OutputStream write(String p, boolean a) {
            throw new UnsupportedOperationException();
        }
        @Override public void setTimes(String p, long m) { throw new UnsupportedOperationException(); }
        @Override public void setPerms(String p, int perms) throws java.io.IOException {
            if (fail) throw new java.io.IOException("permission denied");
            paths.add(p);
            calls.add(new int[]{perms});
        }
        @Override public void close() {}
    }
}
