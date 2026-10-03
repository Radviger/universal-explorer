package dock;

import dock.kit.FontRegistry;
import dock.kit.ThemeManager;
import dock.ui.MainWindow;
import java.nio.file.Path;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/** Universal Explorer — a fast multi-protocol file client for Windows. */
public final class DockApp {

    private DockApp() {}

    public static void main(String[] args) throws Exception {
        Path screenshots = null;
        boolean withSession = false;
        boolean terminalShot = false;
        boolean viewerShot = false;
        boolean mdShot = false;
        boolean edShot = false;
        for (String a : args) {
            if (a.equals("--screenshot")) {
                screenshots = Path.of("build", "screenshots");
            } else if (a.startsWith("--screenshot=")) {
                screenshots = Path.of(a.substring("--screenshot=".length()));
            } else if (a.equals("--screenshot-session")) {
                screenshots = Path.of("build", "screenshots");
                withSession = true;
            } else if (a.equals("--screenshot-terminal")) {
                screenshots = Path.of("build", "screenshots");
                withSession = true;
                terminalShot = true;
            } else if (a.equals("--screenshot-viewer")) {
                screenshots = Path.of("build", "screenshots");
                withSession = true;
                viewerShot = true;
            } else if (a.equals("--screenshot-md")) {
                screenshots = Path.of("build", "screenshots");
                withSession = true;
                mdShot = true;
            } else if (a.equals("--screenshot-ed")) {
                screenshots = Path.of("build", "screenshots");
                withSession = true;
                edShot = true;
            }
        }

        FontRegistry.install();
        ThemeManager.init();
        JFrame.setDefaultLookAndFeelDecorated(true);
        JDialog.setDefaultLookAndFeelDecorated(true);

        if (screenshots != null) {
            Screens.captureAll(screenshots, withSession, terminalShot, viewerShot,
                    mdShot, edShot);
            System.exit(0);
            return;
        }
        if (java.util.Arrays.asList(args).contains("--selftest-queue")) {
            SelftestQueue.run();
            return;
        }
        if (java.util.Arrays.asList(args).contains("--demo")) {
            var handle = dock.sftp.DemoServer.start();
            var fs = dock.sftp.SshSessions.connect("demo", "127.0.0.1", handle.port(),
                    "demo".toCharArray(), null, null,
                    org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier.INSTANCE);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                fs.close();
                try { handle.close(); } catch (Exception ignored) {}
            }));
            SwingUtilities.invokeLater(() -> {
                var w = new MainWindow();
                w.setVisible(true);
                w.openSession(new dock.sftp.SftpSession(fs, null), null);
                dock.kit.Toast.show(w, "Demo session connected to a local throwaway server.",
                        dock.kit.Glyphs.INFO);
            });
            return;
        }
        SwingUtilities.invokeLater(() -> new MainWindow().setVisible(true));
    }
}
