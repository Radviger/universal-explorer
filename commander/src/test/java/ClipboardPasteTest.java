import java.awt.EventQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import javax.swing.JFrame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ctrl+V pastes what the OS file explorer copied: a java-file-list
 * clipboard lands in the pane's directory through the same import as a
 * drop — several files at once, grouped by their source directory into
 * one transfer job per group. A clipboard without files pastes nothing.
 */
class ClipboardPasteTest {

    private dock.commander.FilePane pane;
    private JFrame frame;
    private Path target;
    private Path sourceA;
    private Path sourceB;

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        com.formdev.flatlaf.FlatLaf.registerCustomDefaultsSource("dock.themes");
        com.formdev.flatlaf.FlatLaf.setup(new com.formdev.flatlaf.FlatDarkLaf());
    }

    @AfterAll
    static void restoreClipboard() {
        dock.commander.FilePane.useClipboardForTest(null);
    }

    @AfterEach
    void tearDown() throws Exception {
        EventQueue.invokeAndWait(() -> frame.dispose());
        for (Path root : new Path[] {target, sourceA, sourceB}) {
            if (root == null) continue;
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); }
                    catch (java.io.IOException e) { throw new RuntimeException(e); }
                });
            }
        }
    }

    @Test
    void pastesSeveralExplorerFilesIntoThePaneAtOnce() throws Exception {
        sourceA = Files.createTempDirectory("dock-paste-a");
        sourceB = Files.createTempDirectory("dock-paste-b");
        Files.writeString(sourceA.resolve("one.txt"), "one");
        Files.write(sourceA.resolve("two.bin"), new byte[] {1, 2, 3, 4});
        Files.writeString(sourceB.resolve("three.md"), "three");   // a second source dir
        target = Files.createTempDirectory("dock-paste-target");
        buildPane(target);

        List<java.io.File> copied = List.of(
                sourceA.resolve("one.txt").toFile(),
                sourceA.resolve("two.bin").toFile(),
                sourceB.resolve("three.md").toFile());
        dock.commander.FilePane.useClipboardForTest(() -> new FileList(copied));

        EventQueue.invokeAndWait(() -> pane.fireTableActionForTest("ctrl V"));

        awaitAll("the transfers land");
        assertEquals("one", Files.readString(target.resolve("one.txt")));
        assertEquals(4, Files.size(target.resolve("two.bin")));
        assertEquals("three", Files.readString(target.resolve("three.md")),
                "a second source directory pastes as its own job");
    }

    @Test
    void aClipboardWithoutFilesPastesNothing() throws Exception {
        sourceA = Files.createTempDirectory("dock-paste-src");
        Files.writeString(sourceA.resolve("one.txt"), "one");
        target = Files.createTempDirectory("dock-paste-target");
        buildPane(target);

        dock.commander.FilePane.useClipboardForTest(TextOnly::new);
        EventQueue.invokeAndWait(() -> pane.fireTableActionForTest("ctrl V"));
        Thread.sleep(200);   // any mistaken transfer would be local: instant

        assertEquals(0, Files.list(target).count(), "nothing was pasted");
        assertFalse(Files.exists(target.resolve("one.txt")));
    }

    // ---- helpers ----

    private void buildPane(Path root) throws Exception {
        EventQueue.invokeAndWait(() -> {
            pane = new dock.commander.FilePane(new RootedLocalFs(root.toString()));
            frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(pane);
            frame.setSize(700, 500);
            frame.setLocation(-2000, 0);
            frame.setVisible(true);
        });
        for (int i = 0; i < 250 && pane.busyForTest(); i++) Thread.sleep(20);
        assertTrue(!pane.busyForTest(), "listing settles");
    }

    private void awaitAll(String what) throws InterruptedException {
        for (int i = 0; i < 250; i++) {
            if (Files.exists(target.resolve("one.txt"))
                    && Files.exists(target.resolve("two.bin"))
                    && Files.exists(target.resolve("three.md"))) return;
            Thread.sleep(20);
        }
        assertTrue(Files.exists(target.resolve("one.txt"))
                        && Files.exists(target.resolve("two.bin"))
                        && Files.exists(target.resolve("three.md")),
                what + " (timed out)");
    }

    /** An Explorer-style clipboard: only the java file list. */
    private static final class FileList implements java.awt.datatransfer.Transferable {
        private final List<java.io.File> files;
        FileList(List<java.io.File> files) { this.files = files; }

        @Override public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
            return new java.awt.datatransfer.DataFlavor[]{
                    java.awt.datatransfer.DataFlavor.javaFileListFlavor};
        }

        @Override public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor f) {
            return f.equals(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
        }

        @Override public Object getTransferData(java.awt.datatransfer.DataFlavor f) {
            return files;
        }
    }

    /** Text on the clipboard — the paste must leave it alone. */
    private static final class TextOnly implements java.awt.datatransfer.Transferable {
        @Override public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
            return new java.awt.datatransfer.DataFlavor[]{
                    java.awt.datatransfer.DataFlavor.stringFlavor};
        }

        @Override public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor f) {
            return f.equals(java.awt.datatransfer.DataFlavor.stringFlavor);
        }

        @Override public Object getTransferData(java.awt.datatransfer.DataFlavor f) {
            return "plain text";
        }
    }

    /** LocalFs pinned to one root, so the pane lists only our files. */
    private static final class RootedLocalFs implements dock.core.fs.FileSystem {
        private final dock.core.fs.FileSystem inner = dock.core.fs.LocalFs.INSTANCE;
        private final String home;
        RootedLocalFs(String home) { this.home = home; }

        @Override public String home() { return home; }
        @Override public String label() { return inner.label(); }
        @Override public boolean remote() { return inner.remote(); }
        @Override public String separator() { return inner.separator(); }
        @Override public java.util.List<String> roots() { return inner.roots(); }
        @Override public String normalize(String p) { return inner.normalize(p); }
        @Override public String parent(String p) { return inner.parent(p); }
        @Override public String child(String d, String n) { return inner.child(d, n); }
        @Override public boolean exists(String p) throws java.io.IOException { return inner.exists(p); }
        @Override public java.util.List<dock.core.fs.FileEntry> list(String p) throws java.io.IOException { return inner.list(p); }
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
}
