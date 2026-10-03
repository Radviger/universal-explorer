import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Window;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JTabbedPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session tabs are titled by the session's preferred name (the saved site's
 * name); user@host only appears when no name exists — and then as a tooltip,
 * so the endpoint stays discoverable on named tabs.
 */
class SessionTabTitleTest {

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @Test
    void tabsAreTitledByPreferredName() throws Exception {
        try (dock.sftp.DemoServer.Handle demo = dock.sftp.DemoServer.start()) {
            var spec = new dock.sftp.SshSessions.ConnectionSpec(
                    "demo", "127.0.0.1", demo.port(), "demo".toCharArray(), null, null);
            var verifier = org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier.INSTANCE;
            var fs = new AtomicReference<dock.sftp.SftpFs>();
            var fs2 = new AtomicReference<dock.sftp.SftpFs>();
            var w = new AtomicReference<dock.ui.MainWindow>();
            EventQueue.invokeAndWait(() -> {
                try {
                    fs.set(dock.sftp.SshSessions.connect(spec, verifier, null));
                    fs2.set(dock.sftp.SshSessions.connect(spec, verifier, null));
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
                var win = new dock.ui.MainWindow();
                win.setSize(1200, 700);
                win.setLocation(-2000, 0); // off-screen: no flash during tests
                win.setVisible(true);
                win.openSession(new dock.sftp.SftpSession(fs.get(), spec), "My VPS");
                win.openSession(new dock.sftp.SftpSession(fs2.get(), spec), null);
                w.set(win);
            });
            try {
                JTabbedPane tabs = tabPane(w.get());
                assertEquals(2, tabs.getTabCount());
                assertEquals("My VPS", tabs.getTitleAt(0), "named session titles its tab");
                assertEquals(fs.get().label(), tabs.getToolTipTextAt(0),
                        "endpoint moves into the tooltip when a name replaces it");
                assertEquals(fs2.get().label(), tabs.getTitleAt(1),
                        "unnamed session falls back to user@host");
                assertNull(tabs.getToolTipTextAt(1), "fallback tab needs no tooltip");

                // The session's own terminal tab carries the same name.
                EventQueue.invokeAndWait(w.get()::openTerminalForScreenshots);
                assertEquals("My VPS — terminal", tabs.getTitleAt(2));
            } finally {
                EventQueue.invokeAndWait(() -> {
                    // closeSession() semantics: shells stop before dispose.
                    JTabbedPane tabs = tabPane(w.get());
                    for (int i = tabs.getTabCount() - 1; i >= 0; i--) {
                        if (tabs.getComponentAt(i) instanceof dock.terminal.TerminalView t) t.close();
                        tabs.remove(i);
                    }
                    w.get().dispose();
                });
                fs.get().close();
                fs2.get().close();
            }
        }
    }

    private static JTabbedPane tabPane(Window window) {
        var found = new AtomicReference<JTabbedPane>();
        walk(window, c -> {
            if (c instanceof JTabbedPane p && found.get() == null) found.set(p);
        });
        JTabbedPane p = found.get();
        assertTrue(p != null, "a tab pane must exist inside the window");
        return p;
    }

    private static void walk(Component c, java.util.function.Consumer<Component> f) {
        f.accept(c);
        if (c instanceof java.awt.Container cc) {
            for (Component child : cc.getComponents()) walk(child, f);
        }
    }
}
