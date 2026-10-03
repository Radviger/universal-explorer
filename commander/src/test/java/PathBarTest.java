import dock.core.fs.LocalFs;
import dock.commander.KnownFolders;
import dock.commander.KnownFolders.Folder;
import dock.commander.PathBar;
import java.awt.Color;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PathBar interaction contract: pathlet structure, per-pathlet navigation
 * prefixes (drive root carries the separator), and the edit fallback
 * (commit on Enter, cancel reverts).
 */
class PathBarTest {

    @org.junit.jupiter.api.BeforeAll
    static void fonts() {
        dock.kit.FontRegistry.install();
        // The segment fill reads Button.toolbar.hoverBackground from the
        // app's theme defaults; without FlatLaf it resolves null and paints
        // nothing.
        com.formdev.flatlaf.FlatLaf.registerCustomDefaultsSource("dock.themes");
        com.formdev.flatlaf.FlatLaf.setup(new com.formdev.flatlaf.FlatDarkLaf());
        // Pin an empty shell-folder table: these tests use real paths on
        // this machine ("C:\Users\<me>\...") and must not collapse segments
        // to icon pathlets just because they are known folders.
        KnownFolders.installForTest(Map.of());
    }

    @AfterEach
    void resetShellFolders() { KnownFolders.installForTest(Map.of()); }

    @AfterAll
    static void releaseShellFolders() { KnownFolders.clearForTest(); }

    @Test
    void pathletsNavigateToTheirPrefixes() {
        List<String> navigated = new ArrayList<>();
        PathBar bar = new PathBar(() -> LocalFs.INSTANCE, navigated::add);
        bar.setSize(800, 30);
        bar.setPath("C:\\Users\\demo\\projects");

        assertEquals(4, bar.pathletCount(), "C: › Users › demo › projects");
        int w = bar.pathletSizeForTest(0).width;
        assertTrue(w < 34, "short pathlets must stay compact, root is " + w + "px");
        int h = bar.pathletSizeForTest(0).height;
        assertTrue(h >= 24 && h <= 30, "pathlets are near-full-height pills in the 30px bar, got "
                + h + "px");
        bar.clickPathlet(0);
        assertEquals("C:\\", navigated.get(0), "drive root keeps the separator");
        bar.clickPathlet(2);
        assertEquals("C:\\Users\\demo", navigated.get(1));
        bar.clickPathlet(3);
        assertEquals("C:\\Users\\demo\\projects", navigated.get(2));
    }

    @Test
    void crumbsFillTheBarEdgeToEdge() {
        List<String> navigated = new ArrayList<>();
        PathBar bar = new PathBar(() -> LocalFs.INSTANCE, navigated::add);
        bar.setSize(800, 30);
        bar.setPath("C:\\Users\\demo\\projects");
        bar.layoutBrowseForTest();

        var first = bar.pathletBoundsForTest(0);
        assertEquals(0, first.x, "chain starts flush at the bar's left edge");
        assertEquals(0, first.y, "no top inset");
        assertEquals(29, first.height, "segments fill the bar's inner height (30-1 chrome)");
        var last = bar.pathletBoundsForTest(3);
        assertEquals(0, last.y);
        assertEquals(29, last.height);

        assertEquals("L", bar.capsForTest(0), "first crumb carries the bar's left corners");
        for (int i = 1; i < 4; i++) {
            assertEquals("", bar.capsForTest(i),
                    "crumb " + i + " ends mid-bar — square, no right cap");
        }
    }

    @Test
    void tailSnapsToTheBarsRightEndWithinTheSliverWindow() {
        PathBar bar = new PathBar(() -> LocalFs.INSTANCE, p -> {});
        bar.setSize(800, 30);
        bar.setPath("C:\\Users\\demo\\projects");
        bar.layoutBrowseForTest();
        var natural = bar.pathletBoundsForTest(3);
        int naturalEnd = natural.x + natural.width;

        // 6px short of the end: stretch the last crumb shut and round it.
        bar.setSize(naturalEnd + 6, 30);
        bar.layoutBrowseForTest();
        var snapped = bar.pathletBoundsForTest(3);
        assertEquals("R", bar.capsForTest(3), "snapped tail carries the right corners");
        assertEquals(bar.getWidth() - 1, snapped.x + snapped.width,
                "chain now reaches the chrome's right edge");

        // Well short of the end: untouched, square.
        bar.setSize(naturalEnd + 40, 30);
        bar.layoutBrowseForTest();
        assertEquals("", bar.capsForTest(3));
        var loose = bar.pathletBoundsForTest(3);
        assertEquals(naturalEnd, loose.x + loose.width, "no stretch outside the snap window");
    }

    @Test
    void hoveredSegmentFillsTheBarWithCappedOuterCorners() {
        PathBar bar = new PathBar(() -> LocalFs.INSTANCE, p -> {});
        bar.setSize(800, 30);
        bar.setPath("C:\\Users\\demo");
        bar.layoutBrowseForTest();

        // At rest a segment paints nothing of its own (the bar chrome shows).
        var rest = bar.paintPathletForTest(0, false);
        assertTrue(alpha(rest, rest.getWidth() / 2, 0) == 0, "rest paints no fill");

        // Hovered: full-height fill, rounded only on the capped outer corner.
        var first = bar.paintPathletForTest(0, true);
        int h = first.getHeight();
        assertTrue(alpha(first, first.getWidth() / 2, 0) > 0, "fill reaches the top edge");
        assertTrue(alpha(first, first.getWidth() / 2, h - 1) > 0, "fill reaches the bottom edge");
        assertTrue(alpha(first, 0, 0) == 0, "top-left corner is rounded (left cap)");
        assertTrue(alpha(first, 0, h / 2) > 0, "left edge fills between the corners");
        assertTrue(alpha(first, first.getWidth() - 1, h / 2) > 0, "inner side is square");

        var middle = bar.paintPathletForTest(1, true);
        assertTrue(alpha(middle, 0, 0) > 0, "middle segments have square corners");

        // Right cap: only once the tail is snapped against the bar's end.
        bar.setSize(bar.pathletBoundsForTest(2).x + bar.pathletBoundsForTest(2).width + 6, 30);
        bar.layoutBrowseForTest();
        var last = bar.paintPathletForTest(2, true);
        assertTrue(alpha(last, last.getWidth() - 1, 0) == 0, "top-right corner rounded (right cap)");
        assertTrue(alpha(last, last.getWidth() - 1, last.getHeight() - 1) == 0,
                "bottom-right corner rounded (right cap)");
    }

    @Test
    void cappedCornersMatchTheBarChromeGeometryExactly() {
        PathBar bar = new PathBar(() -> LocalFs.INSTANCE, p -> {});
        bar.setSize(800, 30);
        bar.setPath("C:\\Users\\demo");
        bar.layoutBrowseForTest();

        // The hovered first crumb vs a reference fillRoundRect — the exact
        // primitive that painted the bar chrome. Corner boxes only (text
        // never reaches them); every pixel must classify identically.
        var crumb = bar.paintPathletForTest(0, true);
        int w = crumb.getWidth(), h = crumb.getHeight();

        var ref = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var gr = ref.createGraphics();
        gr.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        gr.setColor(Color.WHITE);
        gr.fillRoundRect(0, 0, w, h, 8, 8);
        gr.dispose();

        int[][] boxes = {{0, 0, 9, 9}, {0, h - 9, 9, 9}};   // top-left, bottom-left
        int mismatched = 0;
        for (int[] b : boxes) {
            for (int y = b[1]; y < b[1] + b[3]; y++) {
                for (int x = b[0]; x < b[0] + b[2]; x++) {
                    // The hover fill is translucent (alpha ≈ 22), the
                    // reference opaque — classify each at half coverage.
                    boolean a = (crumb.getRGB(x, y) >>> 24) > 9;
                    boolean r = (ref.getRGB(x, y) >>> 24) > 128;
                    if (a != r) mismatched++;
                }
            }
        }
        assertEquals(0, mismatched,
                "cap corner pixels must classify identically to the chrome");
    }

    private static int alpha(java.awt.image.BufferedImage img, int x, int y) {
        return img.getRGB(Math.min(Math.max(x, 0), img.getWidth() - 1),
                Math.min(Math.max(y, 0), img.getHeight() - 1)) >>> 24;
    }

    @Test
    void setPathWhileEditingPrimesEditorAfterwards() {
        List<String> navigated = new ArrayList<>();
        PathBar bar = new PathBar(() -> LocalFs.INSTANCE, navigated::add);
        bar.setSize(800, 30);
        bar.setPath("C:\\Users");
        bar.beginEdit();
        assertTrue(bar.editing());
        bar.setPath("D:\\other");           // e.g. a listing refresh mid-edit
        assertTrue(bar.editing(), "refresh must not kick the user out of editing");
        bar.cancel();
        assertFalse(bar.editing());
    }

    @Test
    void commitNavigatesAndReturnsToBrowse() {
        List<String> navigated = new ArrayList<>();
        PathBar bar = new PathBar(() -> LocalFs.INSTANCE, navigated::add);
        bar.setSize(800, 30);
        bar.setPath("C:\\Users");
        bar.beginEdit();
        var editor = bar.editorForTest();
        editor.setText("D:\\tmp");
        editor.postActionEvent();           // Enter
        assertFalse(bar.editing());
        assertEquals(List.of("D:\\tmp"), navigated);
    }

    @Test
    void knownFolderHeadIsTheSoleLeadingIcon() {
        KnownFolders.installForTest(Map.of(
                "c:\\users\\demo", Folder.HOME,
                "c:\\users\\demo\\documents", Folder.DOCUMENTS,
                "c:\\users\\demo\\downloads", Folder.DOWNLOADS));
        List<String> navigated = new ArrayList<>();
        PathBar bar = new PathBar(() -> LocalFs.INSTANCE, navigated::add);
        bar.setSize(800, 30);

        // The deepest folder wins and the whole drive-to-folder prefix
        // folds into it: C: › Users › demo › Documents › proj is gone.
        bar.setPath("C:\\Users\\demo\\Documents\\proj");
        assertEquals(2, bar.pathletCount(), "[documents] › proj");
        assertEquals("", bar.pathletTextForTest(0));
        assertEquals("Documents — C:\\Users\\demo\\Documents",
                bar.pathletTooltipForTest(0), "the tooltip carries the real path");
        assertNotNull(bar.pathletIconForTest(0));
        assertEquals("proj", bar.pathletTextForTest(1));
        assertFalse(bar.tailSeparatorForTest(), "text tail stays open");
        bar.clickPathlet(1);
        assertEquals("C:\\Users\\demo\\Documents\\proj", navigated.get(0));

        // Sitting in Downloads: one icon and the separator, nothing more.
        bar.setPath("C:\\Users\\demo\\Downloads");
        assertEquals(1, bar.pathletCount(), "[downloads] ›");
        assertEquals("Downloads — C:\\Users\\demo\\Downloads",
                bar.pathletTooltipForTest(0));
        assertTrue(bar.tailSeparatorForTest());

        // Deepest wins over the home it sits inside; matching is
        // case-insensitive like the paths it replaces.
        bar.setPath("C:\\USERS\\DEMO");
        assertEquals(1, bar.pathletCount());
        assertEquals("Home — C:\\USERS\\DEMO", bar.pathletTooltipForTest(0));
        assertTrue(bar.tailSeparatorForTest());

        // Unknown territory: plain text chain, and the root still navigates.
        bar.setPath("C:\\Tools\\bin");
        assertEquals(3, bar.pathletCount());
        assertEquals("C:", bar.pathletTextForTest(0));
        assertNull(bar.pathletIconForTest(0));
        bar.clickPathlet(0);
        assertEquals("C:\\", navigated.get(1));
    }

    @Test
    void decayedHeadExpandsIntoRealCrumbsAndCollapsesAgain() {
        KnownFolders.installForTest(Map.of(
                "c:\\users\\demo", Folder.HOME,
                "c:\\users\\demo\\downloads", Folder.DOWNLOADS));
        List<String> navigated = new ArrayList<>();
        PathBar bar = new PathBar(() -> LocalFs.INSTANCE, navigated::add);
        bar.setSize(800, 30);
        bar.setPath("C:\\Users\\demo\\Downloads\\iso");
        assertEquals(2, bar.pathletCount(), "[downloads] › iso");
        assertFalse(bar.decayedForTest());

        // Clicking the icon decays the prefix into its real parts.
        bar.clickPathlet(0);
        assertTrue(bar.decayedForTest());
        assertEquals(5, bar.pathletCount(), "C: › Users › demo › Downloads › iso");
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            texts.add(bar.pathletTextForTest(i));
            assertNull(bar.pathletIconForTest(i), "decayed crumbs are plain text");
        }
        assertEquals(List.of("C:", "Users", "demo", "Downloads", "iso"), texts);
        assertFalse(bar.tailSeparatorForTest());

        // A press inside the bar leaves it decayed; one outside collapses.
        press(bar.browseCardForTest(), 5, 5);
        assertTrue(bar.decayedForTest(), "presses inside the bar do not collapse");
        javax.swing.JLabel elsewhere = new javax.swing.JLabel();
        press(elsewhere, 0, 0);
        assertFalse(bar.decayedForTest(), "outside press collapses the head");
        assertEquals(2, bar.pathletCount(), "back to [downloads] › iso");

        // Navigating from a decayed chain collapses on the next setPath.
        bar.clickPathlet(0);
        assertTrue(bar.decayedForTest());
        bar.clickPathlet(2);
        assertEquals("C:\\Users\\demo", navigated.get(0));
        bar.setPath("C:\\Users\\demo");
        assertFalse(bar.decayedForTest());
        assertEquals(1, bar.pathletCount(), "[person] ›");
    }

    /** Dispatches a real mouse press into {@code c} (AWT listeners see it). */
    private static void press(java.awt.Component c, int x, int y) {
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0, x, y, 1, false));
    }

    @Test
    void iconOnlyTailsCarryATrailingSeparator() {
        KnownFolders.installForTest(Map.of("c:\\users\\demo", Folder.HOME));
        PathBar bar = new PathBar(() -> LocalFs.INSTANCE, p -> {});
        bar.setSize(800, 30);

        // The head icon is the whole chain: closed by a separator.
        bar.setPath("C:\\Users\\demo");
        assertTrue(bar.tailSeparatorForTest());
        bar.layoutBrowseForTest();
        var tail = bar.tailSeparatorBoundsForTest();
        var icon = bar.pathletBoundsForTest(0);
        assertTrue(tail.x >= icon.x + icon.width,
                "the separator trails the icon crumb");

        // Deeper: text tail — open.
        bar.setPath("C:\\Users\\demo\\proj");
        assertFalse(bar.tailSeparatorForTest());
    }

    @Test
    void remoteBreadcrumbsNeverCollapseToShellIcons() {
        KnownFolders.installForTest(Map.of("/home/u", Folder.HOME));
        PathBar bar = new PathBar(() -> new FakeRemoteFs(), p -> {});
        bar.setSize(800, 30);
        bar.setPath("/home/u/docs");

        assertEquals(4, bar.pathletCount(), "root › home › u › docs");
        assertEquals("", bar.pathletTextForTest(0));
        assertNotNull(bar.pathletIconForTest(0));
        assertEquals("Go to root", bar.pathletTooltipForTest(0));
        for (int i = 1; i < 4; i++) {
            assertTrue(!bar.pathletTextForTest(i).isEmpty(),
                    "remote segment " + i + " stays text");
            assertNull(bar.pathletIconForTest(i),
                    "shell folders are a local-machine concept");
        }
    }

    /** Minimal remote filesystem: everything PathBar touches and no more. */
    private static final class FakeRemoteFs implements dock.core.fs.FileSystem {
        @Override public String label() { return "fake"; }
        @Override public boolean remote() { return true; }
        @Override public String separator() { return "/"; }
        @Override public String home() { return "/home/u"; }
        @Override public List<String> roots() { return List.of("/"); }
        @Override public String normalize(String p) { return p; }
        @Override public String parent(String p) { return "/"; }
        @Override public String child(String d, String n) { return d + "/" + n; }
        @Override public boolean exists(String p) { return true; }
        @Override public List<dock.core.fs.FileEntry> list(String p) { return List.of(); }
        @Override public dock.core.fs.FileEntry stat(String p) { throw new UnsupportedOperationException(); }
        @Override public void mkdir(String p) { throw new UnsupportedOperationException(); }
        @Override public void delete(String p) { throw new UnsupportedOperationException(); }
        @Override public void rename(String f, String t) { throw new UnsupportedOperationException(); }
        @Override public java.io.InputStream read(String p) { throw new UnsupportedOperationException(); }
        @Override public java.io.OutputStream write(String p, boolean a) { throw new UnsupportedOperationException(); }
        @Override public void setTimes(String p, long m) { throw new UnsupportedOperationException(); }
        @Override public void setPerms(String p, int x) { throw new UnsupportedOperationException(); }
        @Override public void close() {}
    }

    @Test
    void remoteRootAloneCarriesTheTrailingSeparator() {
        PathBar bar = new PathBar(() -> new FakeRemoteFs(), p -> {});
        bar.setSize(800, 30);
        bar.setPath("/");
        assertEquals(1, bar.pathletCount(), "the server mark is the whole chain");
        assertEquals("Go to root", bar.pathletTooltipForTest(0));
        assertTrue(bar.tailSeparatorForTest(), "a lone icon gets its closing >");
        bar.layoutBrowseForTest();
        assertNotNull(bar.tailSeparatorBoundsForTest());

        // Deeper remote paths end in text and stay open.
        bar.setPath("/home/u/docs");
        assertEquals(4, bar.pathletCount());
        assertFalse(bar.tailSeparatorForTest());
    }

    @Test
    void busyPaintsIntoTheChromeWithoutTouchingLayout() throws Exception {
        PathBar bar = new PathBar(() -> LocalFs.INSTANCE, p -> {});
        bar.setSize(800, 30);
        bar.setPath("C:\\Users");
        bar.layoutBrowseForTest();
        bar.doLayout();
        assertFalse(bar.busyForTest(), "starts idle");

        var idle = paint(bar);
        var rootBefore = bar.pathletBoundsForTest(0);

        bar.setBusy(true);
        assertTrue(bar.busyForTest());
        assertEquals(rootBefore, bar.pathletBoundsForTest(0),
                "busy state must not reflow the crumbs");

        var busyA = paint(bar);
        Thread.sleep(25);                   // let the sweep band advance
        var busyB = paint(bar);

        // Track: the empty chrome right of the last crumb carries the
        // accent wash everywhere — the whole background is the indicator.
        var last = bar.pathletBoundsForTest(bar.pathletCount() - 1);
        int sampled = 0;
        int tinted = 0;
        for (int x = last.x + last.width + 6; x < 790; x += 5) {
            sampled++;
            Color a = new Color(idle.getRGB(x, 15), true);
            Color b = new Color(busyA.getRGB(x, 15), true);
            int delta = Math.abs(a.getRed() - b.getRed())
                    + Math.abs(a.getGreen() - b.getGreen())
                    + Math.abs(a.getBlue() - b.getBlue());
            if (delta > 6) tinted++;
        }
        assertTrue(sampled > 30, "sample strip must be substantial, got " + sampled);
        assertEquals(sampled, tinted, "the wash must cover the entire empty chrome");

        // Sweep: consecutive busy renders differ — the band moves on.
        assertTrue(differs(busyA, busyB), "the band must advance between renders");

        // And it all washes out: idle again is pixel-identical to before.
        bar.setBusy(false);
        assertFalse(bar.busyForTest());
        var idleAgain = paint(bar);
        assertArrayEquals(rgb(idle), rgb(idleAgain),
                "idle must repaint pixel-identical after busy — no residue");
    }

    private static java.awt.image.BufferedImage paint(PathBar bar) {
        var img = new java.awt.image.BufferedImage(Math.max(1, bar.getWidth()),
                Math.max(1, bar.getHeight()), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        try {
            bar.paint(g);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static boolean differs(java.awt.image.BufferedImage a,
                                   java.awt.image.BufferedImage b) {
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) return true;
            }
        }
        return false;
    }

    private static int[] rgb(java.awt.image.BufferedImage img) {
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }
}
