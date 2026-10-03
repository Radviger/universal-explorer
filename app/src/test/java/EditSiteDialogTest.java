import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import dock.core.config.AppPaths;
import dock.core.config.Site;
import dock.core.config.Sites;
import dock.ui.ConnectDialog;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The edit form as an editor: the button saves the entry in place — a
 * rename replaces the old name instead of duplicating it, the stored
 * memory rides along, and nothing dials. The connect-flavored edit (the
 * missing-secret prompt) keeps its Connect button.
 */
@Timeout(60)
class EditSiteDialogTest {

    @TempDir
    static Path configDir;

    @BeforeAll
    static void boot() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
        AppPaths.override(configDir);
    }

    @AfterAll
    static void restore() {
        AppPaths.override(null);
    }

    @Test
    void theEditFormSavesInPlaceWithoutDialing() throws Exception {
        Sites.upsert(new Site("unit-edit", "nas", 22, "root", null));
        Sites.recordPaths("unit-edit", "C:\\work", "/srv");
        Site stored = Sites.load().stream()
                .filter(s -> s.name().equals("unit-edit")).findFirst().orElseThrow();
        AtomicReference<Site> reported = new AtomicReference<>();
        CountDownLatch saved = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            ConnectDialog d = new ConnectDialog(null, stored, s -> {
                reported.set(s);
                saved.countDown();
            });
            assertEquals("Save", d.primaryButtonForTest().getText(),
                    "the edit form's verdict is a save, not a connect");
            d.setNameForTest("unit-renamed");
            d.setHostAndUserForTest("nas2", "root");
            d.primaryButtonForTest().doClick();
        });
        assertTrue(saved.await(10, TimeUnit.SECONDS), "the save completed");
        assertEquals("unit-renamed", reported.get().name());
        List<Site> all = Sites.load();
        assertTrue(all.stream().noneMatch(s -> s.name().equals("unit-edit")),
                "the entry is modified, not duplicated");
        Site s = all.stream().filter(x -> x.name().equals("unit-renamed"))
                .findFirst().orElseThrow();
        assertEquals("nas2", s.host(), "the edit landed");
        assertEquals("C:\\work", s.lastLocalPath(), "the path memory rides along");
        assertEquals("/srv", s.lastRemotePath());
    }

    @Test
    void theConnectFlavoredEditKeepsItsConnectButton() throws Exception {
        Site site = new Site("unit-conn", "nas", 22, "root", null);
        SwingUtilities.invokeAndWait(() -> {
            ConnectDialog d = new ConnectDialog(null, site, (session, name) -> {});
            assertEquals("Connect", d.primaryButtonForTest().getText(),
                    "the missing-secret prompt still dials");
            d.dispose();
        });
    }
}
