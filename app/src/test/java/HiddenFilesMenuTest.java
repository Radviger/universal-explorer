import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hidden files are a View-menu setting (Ctrl+H), not a per-pane eye button.
 * A second, Windows-only setting also hides dot-prefixed names in local
 * panes — Linux servers already treat those as hidden at the fs layer.
 */
class HiddenFilesMenuTest {

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @Test
    void hiddenFilesAreAViewMenuSetting() throws Exception {
        Path dir = Files.createTempDirectory("dock-hidden");
        Files.writeString(dir.resolve("visible.txt"), "plain\n");
        Files.writeString(dir.resolve(".dotfile"), "dot\n");
        Files.createDirectory(dir.resolve(".dotdir"));
        Files.writeString(dir.resolve("hidden.txt"), "dos\n");
        Files.setAttribute(dir.resolve("hidden.txt"), "dos:hidden", true);

        try (dock.sftp.DemoServer.Handle demo = dock.sftp.DemoServer.start()) {
            var spec = new dock.sftp.SshSessions.ConnectionSpec(
                    "demo", "127.0.0.1", demo.port(), "demo".toCharArray(), null, null);
            var verifier = org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier.INSTANCE;
            var fs = new AtomicReference<dock.sftp.SftpFs>();
            var w = new AtomicReference<dock.ui.MainWindow>();
            EventQueue.invokeAndWait(() -> {
                try {
                    fs.set(dock.sftp.SshSessions.connect(spec, verifier, null));
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
                var win = new dock.ui.MainWindow();
                win.setSize(1200, 700);
                win.setLocation(-2000, 0); // off-screen: no flash during tests
                win.setVisible(true);
                win.openSession(new dock.sftp.SftpSession(fs.get(), spec), null);
                w.set(win);
            });
            try {
                var view = findSessionView(w.get());
                var local = view.localPane();
                var remote = view.remotePane();
                EventQueue.invokeAndWait(() -> local.navigate(dir.toString()));

                // Defaults: DOS-hidden gone everywhere, dot names visible in the
                // local (Windows) pane but hidden on the remote side.
                await(() -> names(local).contains("visible.txt"), "local listing loads");
                await(() -> names(remote).contains("readme.md"), "remote listing loads");
                Set<String> localNames = names(local);
                assertFalse(localNames.contains("hidden.txt"), "DOS-hidden file is filtered");
                assertTrue(localNames.contains(".dotfile"),
                        "dot file shows in the local pane on Windows by default");
                assertFalse(names(remote).contains(".hidden-config"),
                        "remote dot file is hidden by default (fs-level)");

                // The eye button is gone; hidden files live in the View menu.
                // (PathBar's "Hidden folders" ellipsis crumb is the collapsed
                // path-ancestor feature — different thing, still allowed.)
                var tips = new AtomicReference<List<String>>();
                EventQueue.invokeAndWait(() -> {
                    List<String> found = new ArrayList<>();
                    walk(local, c -> {
                        if (c instanceof JButton b && b.getToolTipText() != null) {
                            found.add(b.getToolTipText());
                        }
                    });
                    tips.set(found);
                });
                assertTrue(tips.get().stream().noneMatch("Show hidden files"::equals),
                        "the eye button is gone from the toolbar: " + tips.get());

                var showHidden = new AtomicReference<JCheckBoxMenuItem>();
                var hideDot = new AtomicReference<JCheckBoxMenuItem>();
                EventQueue.invokeAndWait(() -> {
                    showHidden.set(checkbox(w.get(), "Show Hidden Files"));
                    hideDot.set(checkbox(w.get(), "Hide Dot-Prefixed Files on Windows"));
                });
                assertFalse(showHidden.get().isSelected(), "show-hidden off by default");
                assertFalse(hideDot.get().isSelected(), "dot-hiding off by default");
                assertEquals(KeyStroke.getKeyStroke("ctrl H"), showHidden.get().getAccelerator());

                // Windows dot-hiding: the local pane joins the remote convention.
                EventQueue.invokeAndWait(hideDot.get()::doClick);
                await(() -> !names(local).contains(".dotfile"), "dot file hides on Windows too");
                Set<String> filtered = names(local);
                assertFalse(filtered.contains(".dotdir"), "dot directory hides as well");
                assertTrue(filtered.contains("visible.txt"), "plain file stays");
                assertTrue(filtered.contains(".."), "parent row is never dot-filtered");

                // Show-hidden reveals everything, in both panes.
                EventQueue.invokeAndWait(showHidden.get()::doClick);
                await(() -> names(local).contains("hidden.txt"), "DOS-hidden file revealed");
                assertTrue(names(local).contains(".dotfile"), "show-hidden beats the dot filter");
                await(() -> names(remote).contains(".hidden-config"), "remote dot file revealed");
            } finally {
                dock.kit.ViewSettings.setShowHidden(false);
                dock.kit.ViewSettings.setHideDotPrefixedOnWindows(false);
                EventQueue.invokeAndWait(() -> {
                    JTabbedPane tabs = tabPane(w.get());
                    for (int i = tabs.getTabCount() - 1; i >= 0; i--) {
                        if (tabs.getComponentAt(i) instanceof dock.terminal.TerminalView t) t.close();
                        tabs.remove(i);
                    }
                    w.get().dispose();
                });
                fs.get().close();
            }
        }
    }

    /**
     * Snapshot of the pane's row names. The listing swap between rowCount()
     * and row() can shrink mid-poll; a partial set is fine — await() polls
     * again, and assertions only run once the listing has settled.
     */
    private static Set<String> names(dock.commander.FilePane pane) {
        var model = (dock.commander.FileTableModel) pane.table().getModel();
        int rows = pane.rowCount();
        Set<String> out = new HashSet<>();
        for (int i = 0; i < rows; i++) {
            try {
                out.add(model.row(i).name());
            } catch (IndexOutOfBoundsException swapped) {
                return out;
            }
        }
        return out;
    }

    /** Finds a checkbox item by text — popup items live in the menu, not the window tree. */
    private static JCheckBoxMenuItem checkbox(dock.ui.MainWindow win, String text) {
        var bar = win.getJMenuBar();
        for (int m = 0; m < bar.getMenuCount(); m++) {
            for (Component c : bar.getMenu(m).getMenuComponents()) {
                if (c instanceof JCheckBoxMenuItem mi && text.equals(mi.getText())) return mi;
            }
        }
        throw new AssertionError("menu item not found: " + text);
    }

    private static dock.commander.SessionView findSessionView(Window window) {
        var found = new AtomicReference<dock.commander.SessionView>();
        walk(window, c -> {
            if (c instanceof dock.commander.SessionView v && found.get() == null) found.set(v);
        });
        dock.commander.SessionView v = found.get();
        assertNotNull(v, "a session view is open");
        return v;
    }

    private static JTabbedPane tabPane(Window window) {
        var found = new AtomicReference<JTabbedPane>();
        walk(window, c -> {
            if (c instanceof JTabbedPane t && found.get() == null) found.set(t);
        });
        assertNotNull(found.get(), "tabbed pane exists");
        return found.get();
    }

    private static void await(java.util.function.BooleanSupplier cond, String what)
            throws InterruptedException {
        for (int i = 0; i < 250 && !cond.getAsBoolean(); i++) Thread.sleep(20);
        assertTrue(cond.getAsBoolean(), what);
    }

    private static void walk(Component c, java.util.function.Consumer<Component> f) {
        f.accept(c);
        if (c instanceof java.awt.Container con) {
            for (Component child : con.getComponents()) walk(child, f);
        }
    }
}
