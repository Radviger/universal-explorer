import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Window;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
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
 * Pane visibility: the View-menu checkbox and the footer's two edge
 * buttons fold either pane of the selected session away — the other fills
 * the window. A folded pane is only removed from the display (it keeps
 * its directory so transfers still target it), and switching back
 * restores the split with the divider where it stood.
 */
class ExplorerModeTest {

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @Test
    void paneHidingSwapsLayoutAndControlsFlipIt() throws Exception {
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
                    assertFalse(view.localPaneHidden());
                    assertTrue(item.isEnabled(), "menu item enabled with a session open");
                    assertFalse(item.getState());

                    // On: remote alone fills the width; the local pane is out
                    // of the window but alive (still parented inside the split).
                    EventQueue.invokeAndWait(item::doClick);
                    assertTrue(view.localPaneHidden());
                    await(() -> remote.getWidth() > 1100, "remote fills the window");
                    assertFalse(SwingUtilities.isDescendingFrom(local, w.get()),
                            "local pane detached from the window");
                    assertTrue(local.getParent() != null, "local pane still alive");
                    assertTrue(item.getState(), "checkbox follows the mode");

                    // Off: back to side-by-side with the divider where it stood.
                    EventQueue.invokeAndWait(item::doClick);
                    assertFalse(view.localPaneHidden());
                    await(() -> SwingUtilities.isDescendingFrom(local, w.get())
                            && local.getWidth() > 400 && remote.getWidth() > 400,
                            "commander restored");
                    assertFalse(item.getState());

                    // The footer's edge buttons fold either pane: the right
                    // one takes the remote side away, and while only one
                    // pane can fold, the left button rests.
                    dock.ui.StatusBar footer = findStatusBar(w.get());
                    JButton rightPane = footer.remotePaneButtonForTest();
                    JButton leftPane = footer.localPaneButtonForTest();
                    EventQueue.invokeAndWait(rightPane::doClick);
                    assertTrue(view.remotePaneHidden());
                    await(() -> local.getWidth() > 1100, "local fills the window");
                    assertFalse(SwingUtilities.isDescendingFrom(remote, w.get()),
                            "remote pane detached from the window");
                    assertFalse(leftPane.isEnabled(), "the last visible pane cannot fold");
                    assertFalse(item.isEnabled(), "the menu agrees");
                    EventQueue.invokeAndWait(rightPane::doClick);
                    assertFalse(view.remotePaneHidden());
                    await(() -> local.getWidth() > 400 && remote.getWidth() > 400,
                            "commander restored from the footer");

                    // The footer's left button mirrors the Explorer-mode
                    // menu: same toggle, same checkbox.
                    EventQueue.invokeAndWait(leftPane::doClick);
                    assertTrue(view.localPaneHidden());
                    assertTrue(item.getState(), "checkbox follows the footer button");
                    EventQueue.invokeAndWait(leftPane::doClick);
                    assertFalse(view.localPaneHidden());
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

    private static dock.ui.StatusBar findStatusBar(Window window) {
        var found = new AtomicReference<dock.ui.StatusBar>();
        walk(window, c -> {
            if (c instanceof dock.ui.StatusBar b && found.get() == null) found.set(b);
        });
        dock.ui.StatusBar b = found.get();
        assertTrue(b != null, "the footer must exist inside the window");
        return b;
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
