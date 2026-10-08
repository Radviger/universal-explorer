import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import dock.core.config.AppPaths;
import dock.core.config.Site;
import dock.core.config.Sites;
import dock.sftp.SftpFs;
import dock.ui.ConnectDialog;
import dock.kit.Fmt;
import dock.ui.SessionsHome;
import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * SessionsHome launcher contract: recency order, one-click connect, live
 * filtering, Enter behavior, quick-connect spec parsing, delete, and the
 * Sites/Site persistence bits the launcher leans on.
 */
class SessionsHomeTest {

    private static Path configDir;
    private static final long DAY = 86_400_000L;

    @BeforeAll
    static void boot() throws Exception {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
        configDir = Files.createTempDirectory("dock-home-test");
        AppPaths.override(configDir);
        seed();
    }

    private static void seed() throws Exception {
        long now = System.currentTimeMillis();
        // "unit-" prefix: the delete path touches the real Windows Credential
        // Manager, so these must never collide with a genuine saved session.
        Files.writeString(configDir.resolve("sessions.json"), """
                [
                  {"name":"unit-nas","host":"nas.local","port":22,"user":"admin","keyPath":null,"lastUsed":%d},
                  {"name":"unit-hetzner-build","host":"hetzner.example.com","port":22,"user":"root","keyPath":"C:\\\\keys\\\\id_ed25519","lastUsed":%d},
                  {"name":"unit-media","host":"192.0.2.50","port":2222,"user":"demo","keyPath":null,"lastUsed":0}
                ]""".formatted(now - 2 * 3_600_000L, now - 9 * DAY));
    }

    /** Host double: records every intent, hands outcomes back synchronously. */
    private static final class RecordingHost implements SessionsHome.Host {
        final List<String> connectRequested = new ArrayList<>();
        final List<String> quickConnectTyped = new ArrayList<>();
        final List<String> editRequested = new ArrayList<>();
        BiConsumer<Exception, dock.core.fs.FileSystem> lastOutcome;

        @Override public void connect(Site site, BiConsumer<Exception, dock.core.fs.FileSystem> outcome) {
            connectRequested.add(site.name());
            lastOutcome = outcome;
        }
        @Override public void newSession() {}
        @Override public void localShell() {}
        @Override public void quickConnect(String typed) { quickConnectTyped.add(typed); }
        @Override public void editSite(Site site) { editRequested.add(site.name()); }
    }

    private static SessionsHome build(RecordingHost host) throws Exception {
        seed();
        return construct(host);
    }

    private static SessionsHome construct(RecordingHost host) throws Exception {
        SessionsHome[] home = new SessionsHome[1];
        SwingUtilities.invokeAndWait(() -> home[0] = new SessionsHome(host));
        return home[0];
    }

    /** Top-down recursive layout for off-screen components (validate is a
     *  no-op without a peer). */
    private static void layoutAll(java.awt.Component c) {
        c.doLayout();
        if (c instanceof java.awt.Container ct) {
            for (java.awt.Component child : ct.getComponents()) layoutAll(child);
        }
    }

    private static java.awt.Component find(java.awt.Component root,
            java.util.function.Predicate<java.awt.Component> p) {
        if (p.test(root)) return root;
        if (root instanceof java.awt.Container ct) {
            for (java.awt.Component c : ct.getComponents()) {
                java.awt.Component hit = find(c, p);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    @Test
    void hoverHintsGoToTheFooterInsteadOfPopups() throws Exception {
        SessionsHome home = build(new RecordingHost());
        java.awt.Component row = home.rowForTest(0);

        SwingUtilities.invokeAndWait(() -> row.dispatchEvent(new java.awt.event.MouseEvent(
                row, java.awt.event.MouseEvent.MOUSE_ENTERED,
                System.currentTimeMillis(), 0, 10, 10, 0, false)));
        assertEquals("Connect to unit-nas", dock.kit.StatusLine.text(),
                "hovering a row publishes its hint to the footer");
        assertNull(((javax.swing.JComponent) row).getToolTipText(),
                "no floating tooltip is left on the row");

        SwingUtilities.invokeAndWait(() -> row.dispatchEvent(new java.awt.event.MouseEvent(
                row, java.awt.event.MouseEvent.MOUSE_EXITED,
                System.currentTimeMillis(), 0, 10, 10, 0, false)));
        assertEquals("", dock.kit.StatusLine.text(), "leaving the row reverts the footer");
        assertNull(home.searchFieldForTest().getToolTipText(),
                "the search field's hint moved to the footer too");
    }

    @Test
    void theSecondLineIsTheEndpointOnly() throws Exception {
        SessionsHome home = build(new RecordingHost());
        // unit-hetzner-build: user "root", a key file set — the row's
        // second line says only where, never who or how.
        String snapshot = home.rowSnapshotForTest(1);
        assertTrue(snapshot.contains("secondary=hetzner.example.com"),
                "the endpoint renders: " + snapshot);
        assertFalse(snapshot.contains("root@") || snapshot.contains("key file")
                        || snapshot.contains("password") || snapshot.contains("agent"),
                "no user or auth-method hint on the launcher row");
    }

    @Test
    void typedCharactersJumpToTheSearchField() throws Exception {
        SessionsHome home = build(new RecordingHost());

        java.awt.event.KeyEvent plain = new java.awt.event.KeyEvent(home,
                java.awt.event.KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0,
                java.awt.event.KeyEvent.VK_UNDEFINED, 'h');
        SwingUtilities.invokeAndWait(() -> assertTrue(home.typeAheadForTest(plain)));
        assertEquals("h", home.searchFieldForTest().getText());
        assertEquals(1, home.visibleRowCount(), "only unit-hetzner-build matches 'h'");

        // Modifier combos and control characters are shortcuts, not search text.
        java.awt.event.KeyEvent combo = new java.awt.event.KeyEvent(home,
                java.awt.event.KeyEvent.KEY_TYPED, System.currentTimeMillis(),
                java.awt.event.InputEvent.CTRL_DOWN_MASK,
                java.awt.event.KeyEvent.VK_UNDEFINED, 'h');
        SwingUtilities.invokeAndWait(() -> assertFalse(home.typeAheadForTest(combo)));
        assertEquals("h", home.searchFieldForTest().getText(), "combos are not stolen");
    }

    @Test
    void longNamesKeepTheRowMenuInsideTheCard() throws Exception {
        String longName = "extremely-long-saved-session-name-" + "x".repeat(90);
        Files.writeString(configDir.resolve("sessions.json"), """
                [{"name":"%s","host":"nas.local","port":22,"user":"admin","keyPath":null,"lastUsed":0}]
                """.formatted(longName));
        try {
            SessionsHome home = construct(new RecordingHost());
            home.setSize(700, 600);
            layoutAll(home);
            assertTrue(home.moreButtonFitsForTest(0),
                    "the ⋯ button must stay fully inside the row");
            assertTrue(home.rowWidthForTest(0) <= home.viewportWidthForTest(0),
                    "row (" + home.rowWidthForTest(0) + "px) must not exceed the viewport ("
                            + home.viewportWidthForTest(0) + "px)");
        } finally {
            seed();
        }
    }

    @Test
    void rowsSortByRecencyNeverUsedLast() throws Exception {
        SessionsHome home = build(new RecordingHost());
        assertEquals(3, home.rowCount());
        assertEquals("unit-nas", home.siteForTest(0).name(), "most recently used first");
        assertEquals("unit-hetzner-build", home.siteForTest(1).name());
        assertEquals("unit-media", home.siteForTest(2).name(), "never-used sorts last");
    }

    @Test
    void oneClickConnectsTheClickedSite() throws Exception {
        RecordingHost host = new RecordingHost();
        SessionsHome home = build(host);
        home.connectForTest(1);
        assertEquals(List.of("unit-hetzner-build"), host.connectRequested);
    }

    @Test
    void filterNarrowsAndEnterConnectsFirstMatch() throws Exception {
        RecordingHost host = new RecordingHost();
        SessionsHome home = build(host);
        home.setSearchTextForTest("hetz");
        assertEquals(1, home.visibleRowCount(), "only unit-hetzner-build matches");
        assertEquals(3, home.rowCount(), "all rows still built");
        home.searchFieldForTest().postActionEvent();   // Enter
        assertEquals(List.of("unit-hetzner-build"), host.connectRequested);
    }

    @Test
    void enterWithNoMatchesHandsOffToQuickConnect() throws Exception {
        RecordingHost host = new RecordingHost();
        SessionsHome home = build(host);
        home.setSearchTextForTest("root@1.2.3.4:2222");
        assertEquals(0, home.visibleRowCount());
        home.searchFieldForTest().postActionEvent();   // Enter
        assertEquals(List.of("root@1.2.3.4:2222"), host.quickConnectTyped);
        assertTrue(host.connectRequested.isEmpty(), "no blind connect on a spec");
    }

    @Test
    void errorOutcomeReleasesTheRowForAnotherTry() throws Exception {
        RecordingHost host = new RecordingHost();
        SessionsHome home = build(host);
        home.connectForTest(0);
        host.lastOutcome.accept(new RuntimeException("boom"), null);
        home.connectForTest(0);                        // must not be stuck busy
        assertEquals(List.of("unit-nas", "unit-nas"), host.connectRequested);
    }

    @Test
    void abortedOutcomeReleasesTheRowToo() throws Exception {
        RecordingHost host = new RecordingHost();
        SessionsHome home = build(host);
        home.connectForTest(0);
        host.lastOutcome.accept(null, null);           // dialog took over
        home.connectForTest(0);
        assertEquals(List.of("unit-nas", "unit-nas"), host.connectRequested);
    }

    @Test
    void rowTextAlignsLeftAndStaysPutAcrossRows() throws Exception {
        SessionsHome home = build(new RecordingHost());
        home.setSize(700, 600);
        layoutAll(home);
        for (int i = 0; i < home.rowCount(); i++) {
            assertEquals(home.rowNameXForTest(i), home.rowSecondaryXForTest(i),
                    "row " + i + ": both text lines must share one left edge");
        }
        int x0 = home.rowNameXForTest(0);
        for (int i = 1; i < home.rowCount(); i++) {
            assertEquals(x0, home.rowNameXForTest(i),
                    "row " + i + ": text left edge must match the first row");
        }
        assertTrue(x0 < home.rowWidthForTest(0) / 2,
                "the text block must hug the left side, not center in the row");
    }

    @Test
    void connectingTouchesOnlyTheBusyStrip() throws Exception {
        RecordingHost host = new RecordingHost();
        SessionsHome home = build(host);
        home.setSize(700, 600);
        layoutAll(home);
        String idle = home.rowSnapshotForTest(0);
        assertTrue(!home.busyForTest(0), "row starts idle");
        assertTrue(!home.busyStripPaintsForTest(0), "an idle strip must paint nothing");

        home.connectForTest(0);
        assertTrue(home.busyForTest(0));
        assertTrue(home.busyStripPaintsForTest(0),
                "the indeterminate strip must paint while connecting");
        assertEquals(idle, home.rowSnapshotForTest(0),
                "nothing but the strip may change while connecting");

        host.lastOutcome.accept(new RuntimeException("boom"), null);
        assertTrue(!home.busyForTest(0), "failure must release the row");
        assertTrue(!home.busyStripPaintsForTest(0), "a released strip must paint nothing");
        assertEquals(idle, home.rowSnapshotForTest(0),
                "failure must restore exactly the idle row");
    }

    @Test
    void hoverTintRunsEdgeToEdge() throws Exception {
        SessionsHome home = build(new RecordingHost());
        home.setSize(700, 600);
        layoutAll(home);
        java.awt.image.BufferedImage idle = home.paintRowForTest(0, false);
        java.awt.image.BufferedImage hover = home.paintRowForTest(0, true);
        int w = idle.getWidth(), h = idle.getHeight();
        // The tint is the row's background across the full width and height;
        // only the outermost pixel column stays clear — the card's border.
        int[][] painted = {{1, h / 2}, {w - 2, h / 2}, {w / 2, 0}, {w / 2, h - 1}};
        for (int[] p : painted) {
            assertEquals(0, idle.getRGB(p[0], p[1]) >>> 24,
                    "idle row must be transparent at " + p[0] + "," + p[1]);
            assertTrue((hover.getRGB(p[0], p[1]) >>> 24) != 0,
                    "hover tint must reach " + p[0] + "," + p[1]);
        }
        for (int[] p : new int[][] {{0, h / 2}, {w - 1, h / 2}}) {
            assertEquals(0, hover.getRGB(p[0], p[1]) >>> 24,
                    "the border's pixel must stay clear at " + p[0] + "," + p[1]);
        }
    }

    @Test
    void searchAndRowsFillTheCardEdgeToEdge() throws Exception {
        SessionsHome home = build(new RecordingHost());
        home.setSize(700, 600);
        layoutAll(home);
        java.awt.Component card = find(home, c -> c instanceof dock.kit.CardPanel);
        assertNotNull(card, "launcher card must be in the tree");
        javax.swing.JComponent chrome = home.searchChromeForTest();
        Rectangle cardB = SwingUtilities.convertRectangle(card,
                new Rectangle(0, 0, card.getWidth(), card.getHeight()), home);
        Rectangle chromeB = SwingUtilities.convertRectangle(chrome,
                new Rectangle(0, 0, chrome.getWidth(), chrome.getHeight()), home);
        assertEquals(cardB.x, chromeB.x, "search chrome must start at the card's left edge");
        assertEquals(cardB.width, chromeB.width, "search chrome must span the card width");
        assertEquals(cardB.y, chromeB.y, "search chrome must sit flush at the card top");
        for (int i = 0; i < home.rowCount(); i++) {
            assertEquals(cardB.width, home.rowWidthForTest(i),
                    "row " + i + " must span the full card width");
        }
    }

    @Test
    void deleteRemovesTheSiteAndRefreshes() throws Exception {
        SessionsHome home = build(new RecordingHost());
        home.deleteForTest(0);
        assertEquals(2, home.rowCount());
        assertTrue(Sites.load().stream().noneMatch(s -> s.name().equals("unit-nas")),
                "sessions.json must not contain unit-nas anymore");
    }

    @Test
    void editForwardsTheSite() throws Exception {
        RecordingHost host = new RecordingHost();
        SessionsHome home = build(host);
        home.editForTest(2);
        assertEquals(List.of("unit-media"), host.editRequested);
    }

    @Test
    void quickSpecParsing() {
        String[] full = ConnectDialog.parseQuickSpec("root@1.2.3.4:2222");
        assertNotNull(full);
        assertEquals("root", full[0]);
        assertEquals("1.2.3.4", full[1]);
        assertEquals("2222", full[2]);

        String[] hostOnly = ConnectDialog.parseQuickSpec("nas.local");
        assertNotNull(hostOnly);
        assertNull(hostOnly[0]);
        assertEquals("nas.local", hostOnly[1]);

        assertNull(ConnectDialog.parseQuickSpec("nas"), "bare word is a filter query, not a host");
        assertNull(ConnectDialog.parseQuickSpec("hello world"));
        assertNull(ConnectDialog.parseQuickSpec(""));
        assertNull(ConnectDialog.parseQuickSpec(null));
    }

    @Test
    void legacySessionsFileWithoutLastUsedLoads() throws Exception {
        Path legacyDir = Files.createTempDirectory("dock-legacy");
        AppPaths.override(legacyDir);
        try {
            Files.writeString(legacyDir.resolve("sessions.json"), """
                    [{"name":"old-box","host":"198.51.100.9","port":22,"user":"admin","keyPath":null}]
                    """);
            List<Site> loaded = Sites.load();
            assertEquals(1, loaded.size());
            assertEquals(0, loaded.get(0).lastUsed(), "missing field loads as never-used");
        } finally {
            AppPaths.override(configDir);
        }
    }

    @Test
    void siteRecordDefaults() {
        Site s = new Site("  ", "h", 0, "u", null);
        assertEquals("u@h", s.name(), "blank name falls back to user@host");
        assertEquals(22, s.port());
        Site touched = s.withLastUsed(123);
        assertEquals(123, touched.lastUsed());
        assertEquals(0, touched.withLastUsed(-5).lastUsed(), "negative normalizes to never");
        assertEquals("Universal Explorer/u@h", touched.secretTarget());
    }

    @Test
    void relativeAgeRendering() {
        assertEquals("never", Fmt.age(0));
        long now = System.currentTimeMillis();
        // Solidly inside each bucket so a few ms of drift can't change it.
        assertEquals("just now", Fmt.age(now));
        assertEquals("5m ago", Fmt.age(now - 5 * 60_000L - 10_000));
        assertEquals("3h ago", Fmt.age(now - 3 * 3_600_000L - 60_000));
        assertEquals("2d ago", Fmt.age(now - 2 * DAY - 3_600_000));
    }

    // ---- arrow navigation ----

    /** Focus transfers are asynchronous; poll on the EDT. */
    private static void awaitFocus(java.awt.Component c) throws Exception {
        for (int i = 0; i < 200; i++) {
            boolean[] owner = {false};
            SwingUtilities.invokeAndWait(() -> owner[0] = c.isFocusOwner());
            if (owner[0]) return;
            Thread.sleep(10);
        }
        fail("focus never landed on " + c);
    }

    private static void enterTheRows(SessionsHome home) throws Exception {
        SwingUtilities.invokeAndWait(() -> home.searchFieldForTest().getActionMap()
                .get("dock.firstRow")
                .actionPerformed(new java.awt.event.ActionEvent(home, 0, "dock.firstRow")));
    }

    @Test
    void arrowsWalkTheRowsAndReturnToTheSearch() throws Exception {
        SessionsHome home = build(new RecordingHost());
        javax.swing.JFrame frame = new javax.swing.JFrame();
        frame.setLocation(-2000, 0);
        frame.add(home);
        SwingUtilities.invokeAndWait(() -> {
            frame.pack();
            frame.setVisible(true);
            home.focusSearch();
        });
        try {
            awaitFocus(home.searchFieldForTest());
            enterTheRows(home);
            awaitFocus(home.rowForTest(0));
            SwingUtilities.invokeAndWait(() -> home.fireRowActionForTest(0, "DOWN"));
            awaitFocus(home.rowForTest(1));
            SwingUtilities.invokeAndWait(() -> home.fireRowActionForTest(1, "DOWN"));
            awaitFocus(home.rowForTest(2));
            // Down on the last row stays put.
            SwingUtilities.invokeAndWait(() -> home.fireRowActionForTest(2, "DOWN"));
            awaitFocus(home.rowForTest(2));
            SwingUtilities.invokeAndWait(() -> home.fireRowActionForTest(2, "UP"));
            awaitFocus(home.rowForTest(1));
            SwingUtilities.invokeAndWait(() -> home.fireRowActionForTest(1, "UP"));
            awaitFocus(home.rowForTest(0));
            SwingUtilities.invokeAndWait(() -> home.fireRowActionForTest(0, "UP"));
            awaitFocus(home.searchFieldForTest());
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    @Test
    void arrowsSkipRowsTheFilterHid() throws Exception {
        SessionsHome home = build(new RecordingHost());
        javax.swing.JFrame frame = new javax.swing.JFrame();
        frame.setLocation(-2000, 0);
        frame.add(home);
        SwingUtilities.invokeAndWait(() -> {
            frame.pack();
            frame.setVisible(true);
        });
        try {
            // "e" matches hetzner-build and media-pi; unit-nas hides.
            SwingUtilities.invokeAndWait(() -> home.setSearchTextForTest("e"));
            assertEquals(2, home.visibleRowCount());
            enterTheRows(home);
            awaitFocus(home.rowForTest(1));
            SwingUtilities.invokeAndWait(() -> home.fireRowActionForTest(1, "DOWN"));
            awaitFocus(home.rowForTest(2));
            SwingUtilities.invokeAndWait(() -> home.fireRowActionForTest(2, "KP_UP"));
            awaitFocus(home.rowForTest(1));
            // Up past the first visible row lands in the search field,
            // skipping the hidden row 0 entirely.
            SwingUtilities.invokeAndWait(() -> home.fireRowActionForTest(1, "UP"));
            awaitFocus(home.searchFieldForTest());
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }
}
