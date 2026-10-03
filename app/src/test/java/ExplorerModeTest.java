import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Window;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.MenuElement;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explorer mode: the View-menu checkbox swaps the selected session between
 * the commander split and a lone remote pane. The local pane is only removed
 * from the display (it keeps its directory so transfers still target it),
 * and switching back restores the split with a re-centered divider.
 */
class ExplorerModeTest {

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @Test
    void explorerModeSwapsLayoutAndMenuFlipsIt() throws Exception {
        try (dock.sftp.DemoServer.Handle demo = dock.sftp.DemoServer.start()) {
            var fs = dock.sftp.SshSessions.connect("demo", "127.0.0.1", demo.port(),
                    "demo".toCharArray(), null, null,
                    org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier.INSTANCE);
            try {
                var w = new AtomicReference<dock.ui.MainWindow>();
                EventQueue.invokeAndWait(() -> {
                    var win = new dock.ui.MainWindow();
                    win.setSize(1200, 700);
                    win.setLocation(-2000, 0); // off-screen: no flash during tests
                    win.setVisible(true);
                    win.openSession(new dock.sftp.SftpSession(fs, new dock.sftp.SshSessions.ConnectionSpec(
                            "demo", "127.0.0.1", demo.port(),
                            "demo".toCharArray(), null, null)), null);
                    w.set(win);
                });
                try {
                    dock.commander.SessionView view = findSessionView(w.get());
                    dock.commander.FilePane local = view.localPane();
                    dock.commander.FilePane remote = view.remotePane();
                    JCheckBoxMenuItem item = findExplorerItem(w.get());

                    // Commander default: side-by-side, item synced and usable.
                    await(() -> local.getWidth() > 400 && remote.getWidth() > 400,
                            "commander panes laid out");
                    assertTrue(remote.getX() > local.getX(), "remote sits right of local");
                    assertFalse(view.explorerMode());
                    assertTrue(item.isEnabled(), "menu item enabled with a session open");
                    assertFalse(item.getState());

                    // On: remote alone fills the width; the local pane is out
                    // of the window but alive (still parented inside the split).
                    EventQueue.invokeAndWait(item::doClick);
                    assertTrue(view.explorerMode());
                    await(() -> remote.getWidth() > 1100, "remote fills the window");
                    assertFalse(SwingUtilities.isDescendingFrom(local, w.get()),
                            "local pane detached from the window");
                    assertTrue(local.getParent() != null, "local pane still alive");
                    assertTrue(item.getState(), "checkbox follows the mode");

                    // Off: back to side-by-side with the divider re-centered.
                    EventQueue.invokeAndWait(item::doClick);
                    assertFalse(view.explorerMode());
                    await(() -> SwingUtilities.isDescendingFrom(local, w.get())
                            && local.getWidth() > 400 && remote.getWidth() > 400,
                            "commander restored");
                    assertFalse(item.getState());
                } finally {
                    EventQueue.invokeAndWait(w.get()::dispose);
                }
            } finally {
                fs.close();
            }
        }
    }

    // ---- helpers ----

    private static dock.commander.SessionView findSessionView(Window window) {
        var found = new AtomicReference<dock.commander.SessionView>();
        walk(window, c -> {
            if (c instanceof dock.commander.SessionView v && found.get() == null) found.set(v);
        });
        dock.commander.SessionView v = found.get();
        assertTrue(v != null, "a session tab must exist inside the window");
        return v;
    }

    private static JCheckBoxMenuItem findExplorerItem(JFrame frame) {
        var mb = frame.getJMenuBar();
        for (int i = 0; i < mb.getMenuCount(); i++) {
            JMenu menu = mb.getMenu(i);
            for (MenuElement el : menu.getSubElements()) {
                for (MenuElement sub : el.getSubElements()) {
                    if (sub instanceof JCheckBoxMenuItem mi
                            && "Explorer Mode".equals(mi.getText())) {
                        return mi;
                    }
                }
            }
        }
        throw new AssertionError("View > Explorer Mode menu item not found");
    }

    private static void walk(Component c, java.util.function.Consumer<Component> f) {
        f.accept(c);
        if (c instanceof java.awt.Container cc) {
            for (Component child : cc.getComponents()) walk(child, f);
        }
    }

    private static void await(java.util.function.BooleanSupplier cond, String what)
            throws InterruptedException {
        for (int i = 0; i < 250 && !cond.getAsBoolean(); i++) Thread.sleep(20);
        assertTrue(cond.getAsBoolean(), "timed out waiting for: " + what);
    }
}
