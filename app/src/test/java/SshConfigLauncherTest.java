import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import dock.core.config.AppPaths;
import dock.core.config.Site;
import dock.ui.SessionsHome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The launcher's auto mode: ~/.ssh/config hosts listed below the saved sessions. */
class SshConfigLauncherTest {

    @TempDir Path config;

    @BeforeAll
    static void boot() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @BeforeEach void isolate() { AppPaths.override(config); }

    @AfterEach void release() { AppPaths.override(null); }

    private static SessionsHome.Host host(List<Site> ssh) {
        return new SessionsHome.Host() {
            @Override public void connect(Site site,
                    BiConsumer<Exception, dock.core.fs.FileSystem> outcome) {}
            @Override public void newSession() {}
            @Override public void localShell() {}
            @Override public void quickConnect(String typed) {}
            @Override public void editSite(Site site) {}
            @Override public List<Site> sshConfigSites() { return ssh; }
        };
    }

    private static SessionsHome home(List<Site> ssh) throws Exception {
        var out = new AtomicReference<SessionsHome>();
        SwingUtilities.invokeAndWait(() -> out.set(new SessionsHome(host(ssh))));
        return out.get();
    }

    private void saved(String json) throws Exception {
        Files.writeString(config.resolve("sessions.json"), json);
    }

    @Test
    void sshHostsAloneReplaceTheFirstRunHero() throws Exception {
        var h = home(List.of(new Site("prod", "10.0.0.5", 22, "deploy", null)));
        assertEquals(1, h.rowCount());
        assertTrue(h.fromSshConfigForTest(0));
        assertTrue(h.sshSectionShownForTest());
    }

    @Test
    void sshHostsFollowTheSavedSessions() throws Exception {
        saved("""
                [{"name":"mine","host":"a.example","port":22,"user":"u","keyPath":null,"lastUsed":1}]""");
        var h = home(List.of(new Site("prod", "10.0.0.5", 22, "deploy", null)));
        assertEquals(List.of("mine", "prod"), List.of(h.siteForTest(0).name(), h.siteForTest(1).name()));
        assertFalse(h.fromSshConfigForTest(0));
        assertTrue(h.fromSshConfigForTest(1));
    }

    @Test
    void aSavedSessionHidesTheSshHostItNames() throws Exception {
        saved("""
                [{"name":"prod","host":"10.0.0.5","port":22,"user":"deploy","keyPath":null,"lastUsed":1}]""");
        var h = home(List.of(new Site("prod", "10.0.0.5", 22, "deploy", null)));
        assertEquals(1, h.rowCount());
        assertFalse(h.fromSshConfigForTest(0));
        assertFalse(h.sshSectionShownForTest());
    }

    @Test
    void theCaptionHidesWhenTheFilterHidesEverySshHost() throws Exception {
        saved("""
                [{"name":"mine","host":"a.example","port":22,"user":"u","keyPath":null,"lastUsed":1}]""");
        var h = home(List.of(new Site("prod", "10.0.0.5", 22, "deploy", null)));
        SwingUtilities.invokeAndWait(() -> h.setSearchTextForTest("mine"));
        assertFalse(h.sshSectionShownForTest());
        SwingUtilities.invokeAndWait(() -> h.setSearchTextForTest("prod"));
        assertTrue(h.sshSectionShownForTest());
    }

    @Test
    void anSshRowShowsItsSourceInPlaceOfAnAge() throws Exception {
        var h = home(List.of(new Site("prod", "10.0.0.5", 22, "deploy", null)));
        assertTrue(h.rowSnapshotForTest(0).contains("age=ssh config"));
    }
}
