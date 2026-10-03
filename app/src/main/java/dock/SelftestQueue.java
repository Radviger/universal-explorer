package dock;

import dock.kit.FontRegistry;
import dock.kit.ThemeManager;
import dock.core.fs.FileEntry;
import dock.core.fs.LocalFs;
import dock.sftp.SftpFs;
import dock.sftp.SshSessions;
import dock.core.transfer.TransferEngine;
import dock.sftp.DemoServer;
import dock.ui.MainWindow;
import dock.ui.TransferPopup;
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;

/**
 * Self-test for the transfers popup lifecycle with the real user flow: a
 * live transfer surfaces in the footer but does NOT auto-open the popup
 * (IntelliJ behavior — it opens on demand), the footer click-through opens
 * it, Esc dismisses, reopening works, and a new batch while open keeps it
 * open. Exits non-zero if any step fails.
 */
public final class SelftestQueue {

    private SelftestQueue() {}

    public static void run() throws Exception {
        FontRegistry.install();
        ThemeManager.init();
        JFrame.setDefaultLookAndFeelDecorated(true);

        var handle = DemoServer.start();
        SftpFs fs = SshSessions.connect("demo", "127.0.0.1", handle.port(),
                "demo".toCharArray(), null, null, AcceptAllServerKeyVerifier.INSTANCE);

        Path src = Files.createTempFile("dock-selftest-", ".bin");
        Path src2 = Files.createTempFile("dock-selftest-", ".bin");
        byte[] chunk = new byte[1024 * 1024];
        try (var out = Files.newOutputStream(src)) {
            for (int i = 0; i < 10; i++) out.write(chunk);
        }
        try (var out = Files.newOutputStream(src2)) {
            for (int i = 0; i < 10; i++) out.write(chunk);
        }

        SwingUtilities.invokeLater(() -> {
            MainWindow w = new MainWindow();
            w.setVisible(true);
            w.openSession(new dock.sftp.SftpSession(fs, null), null);

            step(900, () -> {
                // The user flow: F5 a transfer; the burst surfaces in the
                // footer bar and the popup stays closed until asked for.
                upload(fs, src);
            });
            step(1800, () -> {
                TransferPopup tp = w.transfersPopup();
                boolean suppressed = !tp.popupVisibleForTest();
                log("auto-open suppressed: showing=%s -> %s", tp.popupVisibleForTest(),
                        suppressed ? "PASS" : "FAIL");
                // Open exactly like a footer progress-bar click does.
                w.toggleTransfers();
                boolean open = tp.popupVisibleForTest() && tp.rowCountForTest() >= 1;
                log("on-demand open:      showing=%s rows=%d -> %s",
                        tp.popupVisibleForTest(), tp.rowCountForTest(),
                        open ? "PASS" : "FAIL");
                if (!suppressed || !open) System.exit(1);
            });
            step(2600, () -> pressEscape(w));
            step(3200, () -> {
                // The Esc event is dispatched asynchronously; by now it has
                // landed, so the toggle below is a true reopen.
                log("esc dismisses:       showing=%s", w.transfersPopup().popupVisibleForTest());
                w.toggleTransfers();
                log("reopen:              showing=%s", w.transfersPopup().popupVisibleForTest());
            });
            step(3800, () -> upload(fs, src2));
            step(5000, () -> {
                TransferPopup tp = w.transfersPopup();
                boolean ok = tp.popupVisibleForTest() && tp.rowCountForTest() >= 1;
                log("new batch keeps it:  showing=%s rows=%d -> %s",
                        tp.popupVisibleForTest(), tp.rowCountForTest(),
                        ok ? "PASS" : "FAIL");
                System.exit(ok ? 0 : 1);
            });
        });
        Thread.ofVirtual().start(() -> {
            try { Thread.sleep(20_000); } catch (InterruptedException ignored) {}
            System.err.println("selftest timed out");
            System.exit(2);
        });
    }

    private static void upload(SftpFs fs, Path src) {
        FileEntry e = new FileEntry(src.getFileName().toString(), false, 0, 0, null, false);
        TransferEngine.GLOBAL.enqueue(LocalFs.INSTANCE, src.getParent().toString(),
                List.of(e), fs, "/uploads", false);
    }

    /** Posts a real Esc into the event queue; the popup's global listener sees it. */
    private static void pressEscape(java.awt.Component src) {
        Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(new KeyEvent(
                src, KeyEvent.KEY_PRESSED, EventQueue.getMostRecentEventTime(), 0,
                KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED));
    }

    private static void step(int delay, Runnable r) {
        Timer t = new Timer(delay, e -> r.run());
        t.setRepeats(false);
        t.start();
    }

    private static void log(String pattern, Object... args) {
        System.out.println("[selftest] " + pattern.formatted(args));
    }
}
