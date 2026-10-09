import dock.core.fs.FileEntry;
import dock.core.fs.FileSystem;
import dock.commander.FilePane;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IntelliJ-style speed search in the file table: typing jumps the selection
 * to prefix matches first, then to matches inside the name; repeated letters
 * cycle, Backspace edits (and still navigates up when no search is running),
 * Escape reverts, Enter commits and opens. Verified through real KeyEvents
 * and the table's key bindings.
 */
class SpeedSearchTest {

    /** In-memory backend: "/home/user" seeds the listing under test. */
    private static final class FakeFs implements FileSystem {
        final Map<String, List<FileEntry>> dirs = new LinkedHashMap<>();

        FakeFs() {
            dirs.put("/home/user", List.of(
                    entry("archive.zip", false),
                    entry("backup.tmp", false),
                    entry("beta", true),
                    entry("gamma.md", false),
                    entry("readme.md", false)));
            dirs.put("/home/user/beta", List.of(
                    entry("one.txt", false),
                    entry("two.txt", false)));
        }

        private static FileEntry entry(String name, boolean dir) {
            return new FileEntry(name, dir, 128, 1_700_000_000_000L, null, false);
        }

        @Override public String label() { return "fake"; }
        @Override public boolean remote() { return true; }
        @Override public String separator() { return "/"; }
        @Override public String home() { return "/home/user"; }
        @Override public List<String> roots() { return List.of("/"); }
        @Override public String normalize(String p) {
            String t = p.endsWith("/") && p.length() > 1
                    ? p.substring(0, p.length() - 1) : p;
            return t.isEmpty() ? "/" : t;
        }
        @Override public String parent(String p) {
            if (p.equals("/")) return "/";
            int i = p.lastIndexOf('/');
            return i <= 0 ? "/" : p.substring(0, i);
        }
        @Override public String child(String dir, String name) {
            return dir.equals("/") ? "/" + name : dir + "/" + name;
        }
        @Override public boolean exists(String p) { return dirs.containsKey(p); }
        @Override public List<FileEntry> list(String p) { return dirs.getOrDefault(p, List.of()); }
        @Override public FileEntry stat(String p) {
            String name = p.substring(p.lastIndexOf('/') + 1);
            for (FileEntry e : dirs.getOrDefault(parent(p), List.of())) {
                if (e.name().equals(name)) return e;
            }
            throw new RuntimeException("no such entry " + p);
        }
        @Override public void mkdir(String p) { }
        @Override public void delete(String p) { }
        @Override public void rename(String from, String to) { }
        @Override public java.io.InputStream read(String p) { throw new UnsupportedOperationException(); }
        @Override public java.io.OutputStream write(String p, boolean append) {
            throw new UnsupportedOperationException();
        }
        @Override public void setTimes(String p, long mtimeMillis) { }
        @Override public void setPerms(String p, int posix) { }
        @Override public void close() { }
    }

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        com.formdev.flatlaf.FlatLaf.registerCustomDefaultsSource("dock.themes");
        com.formdev.flatlaf.FlatLaf.setup(new com.formdev.flatlaf.FlatDarkLaf());
    }

    private FilePane paneReady(int expectedRows) {
        FilePane pane = new FilePane(new FakeFs());
        await(() -> onEdt(pane::rowCount) == expectedRows && pane.path().equals("/home/user"));
        return pane;
    }

    // View order (directories partition first): .., beta, archive.zip,
    // backup.tmp, gamma.md, readme.md → rows 0..5.

    @Test
    void typingJumpsToPrefixMatchCaseInsensitive() {
        FilePane pane = paneReady(6); // ".." + five entries
        onEdt(() -> pane.typeForTest("re"));
        assertEquals(5, onEdt(() -> pane.table().getSelectedRow()), "readme.md");
        assertEquals("re", pane.searchQueryForTest());
        assertTrue(pane.searchActiveForTest());

        onEdt(() -> pane.pressForTest(java.awt.event.KeyEvent.VK_ESCAPE));
        onEdt(() -> pane.typeForTest("R"));
        assertEquals(5, onEdt(() -> pane.table().getSelectedRow()), "'R' matches readme.md");
    }

    @Test
    void narrowingKeepsCurrentRowAndReportsNoMatch() {
        FilePane pane = paneReady(6);
        onEdt(() -> pane.typeForTest("a")); // archive.zip
        assertEquals(2, onEdt(() -> pane.table().getSelectedRow()));
        onEdt(() -> pane.typeForTest("b")); // "ab": nothing matches
        assertTrue(pane.searchNoMatchForTest());
        assertEquals(2, onEdt(() -> pane.table().getSelectedRow()), "no-match keeps selection");
        onEdt(() -> pane.typeForTest("c")); // still nothing; must not crash
        assertTrue(pane.searchNoMatchForTest());
        onEdt(() -> pane.pressForTest(java.awt.event.KeyEvent.VK_BACK_SPACE)); // "ab"
        onEdt(() -> pane.pressForTest(java.awt.event.KeyEvent.VK_BACK_SPACE)); // "a"
        assertFalse(pane.searchNoMatchForTest());
        assertEquals(2, onEdt(() -> pane.table().getSelectedRow()));
    }

    @Test
    void repeatedLetterCyclesThroughMatches() {
        FilePane pane = paneReady(6);
        onEdt(() -> pane.typeForTest("b")); // beta (first b-entry)
        assertEquals(1, onEdt(() -> pane.table().getSelectedRow()));
        onEdt(() -> pane.typeForTest("b")); // "bb" advances to backup.tmp
        assertEquals(3, onEdt(() -> pane.table().getSelectedRow()), "second 'b' advances");
        onEdt(() -> pane.typeForTest("b")); // wraps back to beta
        assertEquals(1, onEdt(() -> pane.table().getSelectedRow()));
        // Typing a different letter switches back to plain narrowing.
        onEdt(() -> pane.typeForTest("e"));
        assertEquals("bbbe", pane.searchQueryForTest());
        assertTrue(pane.searchNoMatchForTest(), "no 'bbbe*' entry exists");
    }

    @Test
    void inNameMatchJumpsWhenNoNameStartsWithNeedle() {
        FilePane pane = paneReady(6);
        onEdt(() -> pane.typeForTest("md")); // no name starts with "md"
        assertEquals(4, onEdt(() -> pane.table().getSelectedRow()),
                "gamma.md contains 'md'");
        assertFalse(pane.searchNoMatchForTest());
        onEdt(() -> pane.pressForTest(java.awt.event.KeyEvent.VK_ESCAPE));
        onEdt(() -> pane.typeForTest("z")); // no name starts with 'z'
        assertEquals(2, onEdt(() -> pane.table().getSelectedRow()),
                "archive.zip contains 'z'");
        assertFalse(pane.searchNoMatchForTest());
    }

    @Test
    void prefixMatchOutranksEarlierInNameMatch() {
        FilePane pane = paneReady(6);
        // beta (row 1) contains 'a' inside, archive.zip (row 2) starts with
        // it — the prefix hit must win even though it sits lower.
        onEdt(() -> pane.typeForTest("a"));
        assertEquals(2, onEdt(() -> pane.table().getSelectedRow()),
                "archive.zip wins over the earlier in-name hit beta");
    }

    @Test
    void repeatedLetterCyclesThroughInNameMatchesWhenNoPrefix() {
        FilePane pane = paneReady(6);
        onEdt(() -> pane.typeForTest("e")); // 'e' inside beta
        assertEquals(1, onEdt(() -> pane.table().getSelectedRow()));
        onEdt(() -> pane.typeForTest("e")); // "ee" → archive.zip
        assertEquals(2, onEdt(() -> pane.table().getSelectedRow()));
        onEdt(() -> pane.typeForTest("e")); // "eee" → readme.md
        assertEquals(5, onEdt(() -> pane.table().getSelectedRow()));
        onEdt(() -> pane.typeForTest("e")); // wraps back to beta
        assertEquals(1, onEdt(() -> pane.table().getSelectedRow()));
    }

    @Test
    void narrowingKeepsCurrentRowOnInNameMatch() {
        FilePane pane = paneReady(6);
        onEdt(() -> pane.table().setRowSelectionInterval(5, 5)); // readme.md
        onEdt(() -> pane.typeForTest("e")); // 'e' sits inside readme.md
        assertEquals(5, onEdt(() -> pane.table().getSelectedRow()), "stays put");
        assertFalse(pane.searchNoMatchForTest());
        onEdt(() -> pane.typeForTest("a")); // "ea" still inside readme.md
        assertEquals(5, onEdt(() -> pane.table().getSelectedRow()));
        onEdt(() -> pane.typeForTest("x")); // "eax" matches nothing
        assertTrue(pane.searchNoMatchForTest());
        assertEquals(5, onEdt(() -> pane.table().getSelectedRow()),
                "no-match keeps selection");
    }

    @Test
    void escapeRestoresPreSearchSelection() {
        FilePane pane = paneReady(6);
        onEdt(() -> pane.table().setRowSelectionInterval(4, 4)); // gamma.md
        onEdt(() -> pane.typeForTest("r"));
        assertEquals(5, onEdt(() -> pane.table().getSelectedRow()), "readme.md");
        onEdt(() -> pane.pressForTest(java.awt.event.KeyEvent.VK_ESCAPE));
        assertFalse(pane.searchActiveForTest());
        assertEquals("", pane.searchQueryForTest());
        assertEquals(4, onEdt(() -> pane.table().getSelectedRow()), "back to gamma.md");
    }

    @Test
    void backspaceBindingEditsQueryWhileActiveNavigatesOtherwise() {
        FilePane pane = paneReady(6);
        onEdt(() -> pane.typeForTest("re"));
        onEdt(() -> pane.fireTableActionForTest("BACK_SPACE")); // binding route
        assertTrue(pane.searchActiveForTest());
        assertEquals("r", pane.searchQueryForTest());
        assertEquals(5, onEdt(() -> pane.table().getSelectedRow()), "readme.md matches 'r'");
        onEdt(() -> pane.fireTableActionForTest("BACK_SPACE")); // empties, search ends
        assertFalse(pane.searchActiveForTest());
        onEdt(() -> pane.fireTableActionForTest("BACK_SPACE")); // now navigates up
        await(() -> pane.path().equals("/home") && onEdt(pane::rowCount) == 1);
    }

    @Test
    void enterCommitsSearchAndOpensDirectory() {
        FilePane pane = paneReady(6);
        onEdt(() -> pane.typeForTest("be")); // beta
        assertEquals(1, onEdt(() -> pane.table().getSelectedRow()));
        onEdt(() -> pane.pressForTest(java.awt.event.KeyEvent.VK_ENTER));
        assertFalse(pane.searchActiveForTest(), "Enter commits the search");
        await(() -> pane.path().equals("/home/user/beta") && onEdt(pane::rowCount) == 3);
    }

    @Test
    void enterWithoutSearchOpensSelection() {
        FilePane pane = paneReady(6);
        onEdt(() -> pane.table().setRowSelectionInterval(1, 1)); // beta/
        onEdt(() -> pane.fireTableActionForTest("ENTER"));
        await(() -> pane.path().equals("/home/user/beta"));
    }

    @Test
    void reloadResetsActiveSearch() {
        FilePane pane = paneReady(6);
        onEdt(() -> pane.typeForTest("re"));
        assertTrue(pane.searchActiveForTest());
        onEdt(pane::reload);
        await(() -> !pane.searchActiveForTest() && onEdt(pane::rowCount) == 6);
    }

    @Test
    void badgeSitsCenteredOverTheTopOfTheList() {
        FilePane pane = paneReady(6);
        // Pre-select the row the search will land on, so the pixel changes
        // while searching are the badge and the name highlight.
        onEdt(() -> pane.table().setRowSelectionInterval(5, 5));
        pane.setSize(760, 480);
        onEdt(() -> layoutAll(pane));
        BufferedImage before = render(pane);

        onEdt(() -> pane.typeForTest("re"));
        BufferedImage after = render(pane);

        java.awt.Rectangle badge = onEdt(pane::searchBadgeBoundsForTest);
        java.awt.Rectangle list = onEdt(pane::listAreaForTest);
        assertTrue(Math.abs(badge.getCenterX() - list.getCenterX()) <= 1,
                "centered across the list: badge " + badge + " list " + list);
        assertTrue(badge.y >= list.y && badge.y <= list.y + 12,
                "just below the column headers: badge " + badge + " list " + list);
        assertTrue(changedPixels(before, after, badge) > 200, "the badge paints");

        onEdt(() -> pane.pressForTest(java.awt.event.KeyEvent.VK_ESCAPE));
        assertEquals(null, onEdt(pane::searchBadgeBoundsForTest));
        BufferedImage reverted = render(pane);
        assertEquals(0, changedPixels(before, reverted,
                new java.awt.Rectangle(0, 0, before.getWidth(), before.getHeight())),
                "Escape restores the pre-search pixels, highlight included");
    }

    @Test
    void ctrlOrCmdFOpensAnEmptySearchThatTypingFills() {
        FilePane pane = paneReady(6);
        onEdt(() -> pane.fireTableActionForTest("ctrl F"));
        assertTrue(pane.searchActiveForTest(), "the hotkey opens the search");
        assertEquals("", pane.searchQueryForTest());
        assertTrue(onEdt(pane::searchBadgeBoundsForTest) != null, "the badge shows at once");

        onEdt(() -> pane.typeForTest("re"));
        assertEquals("re", pane.searchQueryForTest());
        assertEquals(5, onEdt(() -> pane.table().getSelectedRow()), "readme.md");

        onEdt(() -> pane.pressForTest(java.awt.event.KeyEvent.VK_ESCAPE));
        onEdt(() -> pane.fireTableActionForTest("meta F"));
        assertTrue(pane.searchActiveForTest(), "Cmd+F is the Mac spelling");
    }

    @Test
    void backspaceClosesAnEmptySearchWithoutLeavingTheFolder() {
        FilePane pane = paneReady(6);
        onEdt(() -> pane.fireTableActionForTest("ctrl F"));
        onEdt(() -> pane.fireTableActionForTest("BACK_SPACE"));
        assertFalse(pane.searchActiveForTest());
        assertEquals("/home/user", pane.path(), "closing the search is not navigation");
    }

    @Test
    void theBadgeCountsEveryRowHoldingTheQuery() {
        FilePane pane = paneReady(6);
        onEdt(() -> pane.typeForTest("md"));
        assertEquals(2, onEdt(pane::searchMatchCountForTest), "gamma.md and readme.md");
        onEdt(() -> pane.pressForTest(java.awt.event.KeyEvent.VK_ESCAPE));
        onEdt(() -> pane.typeForTest("."));
        assertEquals(4, onEdt(pane::searchMatchCountForTest),
                "every dotted file, never the '..' shortcut");
    }

    @Test
    void arrowsHopBetweenMatchesAndKeepTheSearchOpen() {
        FilePane pane = paneReady(6);
        onEdt(() -> pane.typeForTest("md"));
        assertEquals(4, onEdt(() -> pane.table().getSelectedRow()), "gamma.md");
        assertEquals("1/2", onEdt(pane::searchCountTextForTest));

        onEdt(() -> pane.pressForTest(java.awt.event.KeyEvent.VK_DOWN));
        assertEquals(5, onEdt(() -> pane.table().getSelectedRow()), "readme.md");
        assertTrue(pane.searchActiveForTest(), "the search stays open");
        assertEquals("md", pane.searchQueryForTest());
        assertEquals("2/2", onEdt(pane::searchCountTextForTest));

        onEdt(() -> pane.pressForTest(java.awt.event.KeyEvent.VK_DOWN));
        assertEquals(4, onEdt(() -> pane.table().getSelectedRow()), "wraps to gamma.md");
        onEdt(() -> pane.pressForTest(java.awt.event.KeyEvent.VK_UP));
        assertEquals(5, onEdt(() -> pane.table().getSelectedRow()), "wraps back up");
    }

    @Test
    void theArrowBindingsHopTooWhileSearching() {
        FilePane pane = paneReady(6);
        onEdt(() -> pane.typeForTest("md"));
        onEdt(() -> pane.fireTableActionForTest("DOWN"));
        assertEquals(5, onEdt(() -> pane.table().getSelectedRow()));
        onEdt(() -> pane.fireTableActionForTest("UP"));
        assertEquals(4, onEdt(() -> pane.table().getSelectedRow()));
        assertTrue(pane.searchActiveForTest());
    }

    @Test
    void withoutAQueryTheArrowsWalkEveryRow() {
        FilePane pane = paneReady(6);
        onEdt(() -> pane.table().setRowSelectionInterval(2, 2));
        onEdt(() -> pane.fireTableActionForTest("ctrl F"));   // open, nothing typed
        onEdt(() -> pane.fireTableActionForTest("DOWN"));
        assertEquals(3, onEdt(() -> pane.table().getSelectedRow()), "one row down, as usual");
        assertTrue(pane.searchActiveForTest(), "the empty search stays open");
    }

    @Test
    void arrowsWithNothingToHopToStayPut() {
        FilePane pane = paneReady(6);
        onEdt(() -> pane.table().setRowSelectionInterval(2, 2));
        onEdt(() -> pane.typeForTest("qx"));
        assertTrue(pane.searchNoMatchForTest());
        onEdt(() -> pane.pressForTest(java.awt.event.KeyEvent.VK_DOWN));
        assertEquals(2, onEdt(() -> pane.table().getSelectedRow()));
        assertTrue(pane.searchActiveForTest());
        assertEquals("no matches", onEdt(pane::searchCountTextForTest));
    }

    @Test
    void theCountReadsAsATotalOffAMatch() {
        FilePane pane = paneReady(6);
        onEdt(() -> pane.typeForTest("md"));
        onEdt(() -> pane.table().setRowSelectionInterval(1, 1));   // beta, not a match
        assertEquals("2 matches", onEdt(pane::searchCountTextForTest));
    }

    @Test
    void theNameCellsLearnWhatToHighlight() {
        FilePane pane = paneReady(6);
        onEdt(() -> pane.typeForTest("md"));
        assertEquals("md", onEdt(() -> pane.table().getClientProperty("dock.speedSearch.needle")));
        onEdt(() -> pane.pressForTest(java.awt.event.KeyEvent.VK_ESCAPE));
        assertEquals(null, onEdt(() -> pane.table().getClientProperty("dock.speedSearch.needle")));
        onEdt(() -> pane.typeForTest("bb"));
        assertEquals("b", onEdt(() -> pane.table().getClientProperty("dock.speedSearch.needle")),
                "a repeated letter cycles, so the single letter is what matches");
    }

    @Test
    void badgeSurvivesTableScrolling() {
        FilePane pane = paneReady(6);
        pane.setSize(760, 220); // short pane so the six rows overflow it
        onEdt(() -> layoutAll(pane));

        // Blit scrolling copies raw pixels of the viewport region — it would
        // drag the parent-painted badge along with the table.
        assertTrue(onEdt(() -> ((javax.swing.JViewport) pane.table().getParent())
                .getScrollMode() == javax.swing.JViewport.SIMPLE_SCROLL_MODE),
                "viewport must not blit under the badge overlay");

        onEdt(() -> pane.table().setRowSelectionInterval(5, 5));
        onEdt(() -> pane.typeForTest("re")); // badge on, selection stays put

        // Scroll through the real viewport machinery while the badge shows:
        // down to the last row (proving the listing overflows), back to top.
        int[] yAtBottom = new int[1];
        onEdt(() -> {
            pane.table().scrollRectToVisible(pane.table().getCellRect(5, 0, true));
            yAtBottom[0] = ((javax.swing.JViewport) pane.table().getParent())
                    .getViewPosition().y;
        });
        assertTrue(yAtBottom[0] > 0, "rows must overflow the 220px pane");

        int beforeScroll = pane.searchChangedCountForTest();
        onEdt(() -> pane.table().scrollRectToVisible(pane.table().getCellRect(0, 0, true)));
        assertTrue(pane.searchChangedCountForTest() > beforeScroll,
                "viewport moves must schedule a repaint of the pane itself");

        java.awt.Rectangle anchored = onEdt(pane::searchBadgeBoundsForTest);
        BufferedImage scrolled = render(pane);
        onEdt(pane::searchResetForTest);
        BufferedImage scrolledQuiet = render(pane);

        assertTrue(changedPixels(scrolledQuiet, scrolled, anchored) > 200,
                "badge must still paint after scrolling");
        java.awt.Rectangle list = onEdt(pane::listAreaForTest);
        assertTrue(anchored.y >= list.y && anchored.y <= list.y + 12,
                "badge stays anchored over the top of the list: " + anchored);
    }

    // ---- helpers ----

    /** Runs the read on the EDT (establishes happens-before with Swing). */
    private static <T> T onEdt(java.util.function.Supplier<T> read) {
        AtomicReference<T> out = new AtomicReference<>();
        runOnEdt(() -> out.set(read.get()));
        return out.get();
    }

    private static void onEdt(Runnable r) {
        runOnEdt(r);
    }

    private static void runOnEdt(Runnable r) {
        try {
            EventQueue.invokeAndWait(r);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void await(java.util.function.BooleanSupplier cond) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!cond.getAsBoolean()) {
            try { Thread.sleep(10); } catch (InterruptedException e) { return; }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within 5s");
            }
        }
    }

    private static void layoutAll(java.awt.Container c) {
        c.doLayout();
        for (java.awt.Component child : c.getComponents()) {
            if (child instanceof java.awt.Container cc) layoutAll(cc);
            else child.doLayout();
        }
    }

    /** Pixels that differ between two renders inside {@code r}. */
    private static int changedPixels(BufferedImage a, BufferedImage b, java.awt.Rectangle r) {
        int n = 0;
        for (int y = Math.max(0, r.y); y < Math.min(a.getHeight(), r.y + r.height); y++) {
            for (int x = Math.max(0, r.x); x < Math.min(a.getWidth(), r.x + r.width); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) n++;
            }
        }
        return n;
    }

    private static BufferedImage render(FilePane pane) {
        BufferedImage img = new BufferedImage(pane.getWidth(), pane.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        onEdt(() -> pane.paint(g));
        g.dispose();
        return img;
    }
}
