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

/** The launcher's auto mode: external sources listed below the saved sessions. */
class SourceSectionsLauncherTest {

    private static final String SSH = "From ~/.ssh/config";
    private static final String AWS = "From ~/.aws";

    @TempDir Path config;

    @BeforeAll
    static void boot() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @BeforeEach void isolate() { AppPaths.override(config); }

    @AfterEach void release() { AppPaths.override(null); }

    private static SessionsHome.Section ssh(Site... sites) {
        return new SessionsHome.Section(SSH, "ssh config", List.of(sites));
    }

    private static SessionsHome.Section aws(Site... sites) {
        return new SessionsHome.Section(AWS, "aws profile", List.of(sites));
    }

    private static SessionsHome home(SessionsHome.Section... sections) throws Exception {
        var host = new SessionsHome.Host() {
            @Override public void connect(Site site,
                    BiConsumer<Exception, dock.core.fs.FileSystem> outcome) {}
            @Override public void newSession() {}
            @Override public void localShell() {}
            @Override public void quickConnect(String typed) {}
            @Override public void editSite(Site site) {}
            @Override public List<SessionsHome.Section> sourceSections() {
                return List.of(sections);
            }
        };
        var out = new AtomicReference<SessionsHome>();
        SwingUtilities.invokeAndWait(() -> out.set(new SessionsHome(host)));
        return out.get();
    }

    private void saved(String json) throws Exception {
        Files.writeString(config.resolve("sessions.json"), json);
    }

    private static Site prod() {
        return new Site("prod", "10.0.0.5", 22, "deploy", null);
    }

    @Test
    void sourceEntriesAloneReplaceTheFirstRunHero() throws Exception {
        var h = home(ssh(prod()));
        assertEquals(1, h.rowCount());
        assertTrue(h.fromSourceForTest(0));
        assertTrue(h.sectionShownForTest(SSH));
    }

    @Test
    void sourceEntriesFollowTheSavedSessions() throws Exception {
        saved("""
                [{"name":"mine","host":"a.example","port":22,"user":"u","keyPath":null,"lastUsed":1}]""");
        var h = home(ssh(prod()));
        assertEquals(List.of("mine", "prod"), List.of(h.siteForTest(0).name(), h.siteForTest(1).name()));
        assertFalse(h.fromSourceForTest(0));
        assertTrue(h.fromSourceForTest(1));
    }

    @Test
    void eachSourceGetsItsOwnSectionInOrder() throws Exception {
        var bucket = new Site("AWS work", dock.core.config.Protocol.S3, "s3.amazonaws.com", 443,
                "work", null, true, null, null, false, true, 0);
        var h = home(ssh(prod()), aws(bucket));
        assertEquals(List.of("prod", "AWS work"),
                List.of(h.siteForTest(0).name(), h.siteForTest(1).name()));
        assertTrue(h.sectionShownForTest(SSH));
        assertTrue(h.sectionShownForTest(AWS));
        assertTrue(h.rowSnapshotForTest(1).contains("age=aws profile"));
    }

    @Test
    void aSavedSessionHidesTheSourceEntryItNames() throws Exception {
        saved("""
                [{"name":"prod","host":"10.0.0.5","port":22,"user":"deploy","keyPath":null,"lastUsed":1}]""");
        var h = home(ssh(prod()));
        assertEquals(1, h.rowCount());
        assertFalse(h.fromSourceForTest(0));
        assertFalse(h.sectionShownForTest(SSH), "an emptied section drops its caption");
    }

    @Test
    void anEarlierSourceWinsANameClash() throws Exception {
        var h = home(ssh(prod()), aws(new Site("prod", "elsewhere", 22, "x", null)));
        assertEquals(1, h.rowCount());
        assertEquals("10.0.0.5", h.siteForTest(0).host());
    }

    @Test
    void theCaptionHidesWhenTheFilterHidesEveryEntryOfItsSource() throws Exception {
        saved("""
                [{"name":"mine","host":"a.example","port":22,"user":"u","keyPath":null,"lastUsed":1}]""");
        var h = home(ssh(prod()));
        SwingUtilities.invokeAndWait(() -> h.setSearchTextForTest("mine"));
        assertFalse(h.sectionShownForTest(SSH));
        SwingUtilities.invokeAndWait(() -> h.setSearchTextForTest("prod"));
        assertTrue(h.sectionShownForTest(SSH));
    }

    @Test
    void aSectionCaptionSpansTheCardFromItsLeftEdge() throws Exception {
        var h = home(ssh(prod()));
        var bounds = new AtomicReference<java.awt.Rectangle>();
        SwingUtilities.invokeAndWait(() -> bounds.set(h.sectionBoundsForTest(SSH)));
        assertEquals(0, bounds.get().x, "the caption starts at the card edge, not mid-card");
        assertTrue(bounds.get().width > 500, "and spans the card: " + bounds.get());
    }

    @Test
    void aSourceRowShowsItsSourceInPlaceOfAnAge() throws Exception {
        var h = home(ssh(prod()));
        assertTrue(h.rowSnapshotForTest(0).contains("age=ssh config"));
    }
}
