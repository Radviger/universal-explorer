import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import dock.core.config.AppPaths;
import dock.ui.ConnectDialog;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Test Connection dials and hangs up: the verdict lands in the open form. */
class TestConnectionTest {

    @TempDir Path config;

    @BeforeAll
    static void boot() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @BeforeEach void isolate() { AppPaths.override(config); }

    @AfterEach void release() { AppPaths.override(null); }

    private static int closedPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void aFailedTestReportsInTheFormAndSavesNothing() throws Exception {
        var connected = new AtomicBoolean();
        var dialog = new AtomicReference<ConnectDialog>();
        int port = closedPort();
        SwingUtilities.invokeAndWait(() -> {
            ConnectDialog d = new ConnectDialog(null, (session, site) -> connected.set(true));
            d.setHostAndUserForTest("127.0.0.1", "nobody");
            d.setPortForTest(String.valueOf(port));
            d.setPasswordForTest("wrong");
            d.testButtonForTest().doClick();
            dialog.set(d);
        });
        ConnectDialog d = dialog.get();

        long deadline = System.currentTimeMillis() + 20_000;
        var status = new AtomicReference<String>();
        var idle = new AtomicBoolean();
        while (System.currentTimeMillis() < deadline) {
            SwingUtilities.invokeAndWait(() -> {
                status.set(d.statusForTest());
                idle.set(d.testButtonForTest().isEnabled());
            });
            if (status.get() != null && idle.get()) break;
            Thread.sleep(50);
        }
        assertNotNull(status.get(), "the failure lands in the form");
        assertTrue(idle.get(), "the buttons come back after the test");
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("Test Connection", d.testButtonForTest().getText());
            assertEquals("Connect", d.primaryButtonForTest().getText());
            d.dispose();
        });
        assertFalse(connected.get(), "a test never opens a session");
        assertFalse(Files.exists(config.resolve("sessions.json")), "a test saves nothing");
    }

    @Test
    void anIncompleteFormIsCaughtBeforeDialing() throws Exception {
        var status = new AtomicReference<String>();
        SwingUtilities.invokeAndWait(() -> {
            ConnectDialog d = new ConnectDialog(null, (session, site) -> {});
            d.setHostAndUserForTest("", "nobody");
            d.testButtonForTest().doClick();
            status.set(d.statusForTest());
            d.dispose();
        });
        assertEquals("Host is required.", status.get());
    }
}
