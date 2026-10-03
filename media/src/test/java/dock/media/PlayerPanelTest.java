package dock.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The player panel against the fake engine: the state machine, the seek
 * row, the keyboard, the sequence walk — and the one end-to-end truth, that
 * the URL the engine opened really serves the file's bytes off the bridge.
 */
class PlayerPanelTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private MemFs fs;
    private FakeEngine engine;
    private PlayerPanel panel;
    private JFrame frame;
    private boolean closed;
    private List<String> navigated;

    @TempDir
    static Path configDir;

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
        // Sidecar fetches land in the app cache — never the real profile.
        dock.core.config.AppPaths.override(configDir);
    }

    @AfterAll
    static void restoreEngineFactory() {
        PlayerPanel.useEngineFactoryForTest(null);
        PlayerPanel.usePreviewFactoryForTest(null);
        dock.core.config.AppPaths.override(null);
    }

    @BeforeEach
    void build() throws Exception {
        fs = new MemFs();
        fs.add("/a.mp4", new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        fs.add("/b.mkv", new byte[] {9, 9, 9});
        fs.add("/c.mp3", new byte[] {7, 7});
        engine = new FakeEngine();
        closed = false;
        navigated = new java.util.ArrayList<>();
        PlayerPanel.useEngineFactoryForTest(() -> engine);
        // No preview source by default: hovers must never spin natives.
        PlayerPanel.usePreviewFactoryForTest(() -> null);
        EventQueue.invokeAndWait(() -> {
            panel = new PlayerPanel(fs, "/", "a.mp4",
                    () -> List.of("a.mp4", "b.mkv", "c.mp3"),
                    n -> navigated.add(n),
                    () -> {
                        closed = true;
                        panel.release();   // what the session view does on close
                    }, null);
            frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(panel);
            frame.setSize(600, 400);
            frame.setLocation(-2000, 0);   // off-screen: no flash during tests
            frame.setVisible(true);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        EventQueue.invokeAndWait(() -> {
            if (panel != null) panel.release();
            frame.dispose();
        });
    }

    @Test
    void openingPlaysTheFileThroughTheBridge() throws Exception {
        await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING, "playback starts");
        assertEquals(1, engine.openedUrls.size(), "the engine opened one URL");
        assertEquals(panel.bridgeUrlForTest(), engine.lastUrl(),
                "the engine plays the panel's own bridge token");

        // The URL is not decorative: it serves the file's real bytes.
        HttpResponse<byte[]> body = HTTP.send(HttpRequest.newBuilder(
                        URI.create(engine.lastUrl())).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, body.statusCode());
        assertEquals(8, body.body().length, "the mp4's bytes came off the filesystem");
        assertEquals(List.of("a.mp4"), navigated, "the pane cursor follows");
    }

    @Test
    void spaceTogglesPauseAndPlay() throws Exception {
        await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING, "playback starts");
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("SPACE"));
        assertEquals(PlayerPanel.State.PAUSED, edt(panel::stateForTest));
        assertFalse(engine.isPlaying());
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("SPACE"));
        assertEquals(PlayerPanel.State.PLAYING, edt(panel::stateForTest));
        assertTrue(engine.isPlaying());
    }

    @Test
    void clickingTheVideoTogglesWithAFadingGlyph() throws Exception {
        await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING, "playback starts");

        // The click lands on the engine's nested video child — the exact
        // component a real click reaches (and where slice 1's listener,
        // parked on the outer panel, never heard it).
        EventQueue.invokeAndWait(() -> panel.fireClickForTest());
        assertEquals(PlayerPanel.State.PAUSED, edt(panel::stateForTest));
        assertFalse(engine.isPlaying(), "the click paused playback");
        assertEquals(dock.kit.Glyphs.PAUSE, edt(panel::flashGlyphForTest),
                "the glyph of the state just entered");
        float lit = edt(panel::flashAlphaForTest);
        assertTrue(lit > 0.5f && lit <= 1f,
                "the flash starts near full and only fades (" + lit + ")");
        for (int i = 0; i < 20; i++)
            EventQueue.invokeAndWait(panel::stepFadeForTest);   // ≥ the fade's ticks
        assertNull(edt(panel::flashGlyphForTest), "the glyph fades away completely");
        assertEquals(0f, edt(panel::flashAlphaForTest));

        EventQueue.invokeAndWait(() -> panel.fireClickForTest());
        assertEquals(PlayerPanel.State.PLAYING, edt(panel::stateForTest));
        assertEquals(dock.kit.Glyphs.PLAY, edt(panel::flashGlyphForTest),
                "resuming flashes the play glyph from full again");
    }

    @Test
    void aRefusedPlayerIgnoresClicksEntirely() throws Exception {
        PlayerPanel.useEngineFactoryForTest(() -> null);
        AtomicReference<PlayerPanel> bare = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> bare.set(new PlayerPanel(fs, "/", "a.mp4",
                () -> List.of("a.mp4"), n -> {}, () -> {}, null)));
        await(() -> bare.get().stateForTest() == PlayerPanel.State.REFUSED,
                "no engine means a refusal");
        EventQueue.invokeAndWait(() -> {
            bare.get().setSize(600, 400);
            bare.get().fireClickForTest();
            bare.get().fireClickAtForTest(540, 200, 1);   // zone clicks too
            bare.get().fireClickAtForTest(540, 200, 2);
        });
        assertNull(edt(bare.get()::flashGlyphForTest),
                "a dead player flashes nothing");
        bare.get().release();
    }

    @Test
    void doubleClickInTheSideZonesSkipsTenSeconds() throws Exception {
        await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING, "playback starts");
        int w = edt(() -> panel.surfaceForTest().getWidth());
        int h = edt(() -> panel.surfaceForTest().getHeight());
        int right = (int) (w * 0.9), left = (int) (w * 0.1);

        EventQueue.invokeAndWait(() -> panel.fireClickAtForTest(right, h / 2, 1));
        assertTrue(edt(panel::zoneTogglePendingForTest),
                "the first zone click waits out the double-click window");
        assertEquals(PlayerPanel.State.PLAYING, edt(panel::stateForTest),
                "no toggle while the window is open");
        EventQueue.invokeAndWait(() -> panel.fireClickAtForTest(right, h / 2, 2));
        assertEquals(10_000L, engine.lastSeek(), "double-click right skips ahead 10s");
        assertEquals(PlayerPanel.State.PLAYING, edt(panel::stateForTest),
                "a skip never touches the play state");
        assertEquals(dock.kit.Glyphs.CHEVRON_RIGHT + dock.kit.Glyphs.CHEVRON_RIGHT,
                edt(panel::flashGlyphForTest), "the double chevron of the direction");
        assertFalse(edt(panel::zoneTogglePendingForTest),
                "the pending toggle died with the second click");

        EventQueue.invokeAndWait(() -> panel.fireClickAtForTest(left, h / 2, 1));
        EventQueue.invokeAndWait(() -> panel.fireClickAtForTest(left, h / 2, 2));
        assertEquals(0L, engine.lastSeek(),
                "double-click left skips back 10s (clamped at the top of the file)");
    }

    @Test
    void aZoneSingleClickTogglesAfterTheDoubleClickWindowCloses() throws Exception {
        await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING, "playback starts");
        int w = edt(() -> panel.surfaceForTest().getWidth());
        int h = edt(() -> panel.surfaceForTest().getHeight());
        EventQueue.invokeAndWait(() ->
                panel.fireClickAtForTest((int) (w * 0.1), h / 2, 1));
        assertTrue(edt(panel::zoneTogglePendingForTest),
                "the toggle is armed, not fired");
        assertEquals(PlayerPanel.State.PLAYING, edt(panel::stateForTest),
                "still playing while the window is open");
        EventQueue.invokeAndWait(panel::fireZoneToggleForTest);
        assertEquals(PlayerPanel.State.PAUSED, edt(panel::stateForTest),
                "the window closed with no second click: the toggle lands");
        assertFalse(edt(panel::zoneTogglePendingForTest));
    }

    @Test
    void theFlashCompositesInsideTheEngineSurfacePaint() throws Exception {
        await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING, "playback starts");

        // The glyph must live inside the engine's own paint — the video
        // surface repaints itself every frame and Swing repaints only that
        // opaque subtree, so anything painted above it flickers. Painting
        // the engine surface directly proves where the glyph composites.
        AtomicReference<int[]> before = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> before.set(captureEnginePaint()));
        assertEquals(0, brightPixels(before.get()),
                "nothing glows before any click");

        // Click and capture inside one EDT runnable: the fader cannot
        // tick between them, so the glyph is at full alpha.
        AtomicReference<int[]> lit = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            panel.fireClickForTest();
            lit.set(captureEnginePaint());
        });
        assertTrue(brightPixels(lit.get()) > 50,
                "the white glyph paints within the engine surface ("
                        + brightPixels(lit.get()) + " bright pixels)");

        for (int i = 0; i < 20; i++)
            EventQueue.invokeAndWait(panel::stepFadeForTest);
        AtomicReference<int[]> gone = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> gone.set(captureEnginePaint()));
        assertEquals(0, brightPixels(gone.get()),
                "after the fade the glyph is fully gone");
    }

    @Test
    void theFlashScalesWithThePane() throws Exception {
        await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING, "playback starts");
        int small = flashPixelsAtPaneSize(600, 400);
        int big = flashPixelsAtPaneSize(1400, 900);
        assertTrue(big > small * 2,
                "the glyph grows with the pane (" + small + " → "
                        + big + " bright pixels)");
    }

    @Test
    void theFlashGlyphFillsItsDisc() throws Exception {
        await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING, "playback starts");
        // Click and capture in one EDT runnable: the fader cannot tick
        // between them, so the glyph is at full alpha.
        AtomicReference<int[]> px = new AtomicReference<>();
        final int[] dims = new int[2];
        EventQueue.invokeAndWait(() -> {
            dims[0] = engine.surface().getWidth();
            dims[1] = engine.surface().getHeight();
            panel.fireClickForTest();
            px.set(captureEnginePaint());
        });
        int[] ink = brightBBox(px.get(), dims[0], dims[1]);
        // The fake's picture fills its surface, so the disc measures the
        // surface directly: 17% of the smaller dimension.
        int d = Math.max(24, Math.round(
                Math.min(dims[0], dims[1]) * 0.175f));
        assertTrue(ink[3] >= Math.round(d * 0.42f)
                        && ink[3] <= Math.round(d * 0.60f),
                "the glyph fills about half the disc (ink height "
                        + ink[3] + " of disc " + d + ")");
    }

    /** Bounding box of the white glyph ink: {x, y, w, h}. */
    private static int[] brightBBox(int[] rgb, int w, int h) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE,
                maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int p = rgb[y * w + x];
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                if (r >= 200 && g >= 200 && b >= 200) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        return new int[] {minX, minY, maxX - minX + 1, maxY - minY + 1};
    }

    /** Resizes the pane, clicks, and captures in one EDT runnable — the
     *  fader cannot tick in between, so both counts are at full alpha. */
    private int flashPixelsAtPaneSize(int w, int h) throws Exception {
        AtomicReference<int[]> px = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            frame.setSize(w, h);
            frame.validate();
            panel.fireClickForTest();
            px.set(captureEnginePaint());
        });
        return brightPixels(px.get());
    }

    /** Renders the engine's surface tree into pixels — no RepaintManager,
     *  no real frame clock: just what its own paint produces. */
    private int[] captureEnginePaint() {
        javax.swing.JComponent s = engine.surface();
        int w = Math.max(1, s.getWidth()), h = Math.max(1, s.getHeight());
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        s.paint(g);
        g.dispose();
        return img.getRGB(0, 0, w, h, null, 0, w);
    }

    /** Pixels at or near white — the glyph's fixed color on the disc. */
    private static int brightPixels(int[] rgb) {
        int n = 0;
        for (int p : rgb) {
            int r = (p >> 16) & 0xFF, gg = (p >> 8) & 0xFF, b = p & 0xFF;
            if (r >= 200 && gg >= 200 && b >= 200) n++;
        }
        return n;
    }

    @Test
    void durationThenASeekReleasePositionsTheEngine() throws Exception {
        await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING, "playback starts");
        EventQueue.invokeAndWait(() -> engine.setDuration(60_000));
        assertEquals(60_000, edt(panel::durationForTest));
        EventQueue.invokeAndWait(() -> panel.seekToForTest(500));
        assertEquals(30_000L, engine.lastSeek(), "the slider's midpoint is half the file");
    }

    @Test
    void arrowKeysNudgeVolumeAndMute() throws Exception {
        await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING, "playback starts");
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("DOWN"));
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("DOWN"));
        assertEquals(90, edt(panel::volumeForTest), "two arrow-downs take five each");
        assertEquals(90, engine.volumes.getLast());
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("typed m"));
        assertTrue(edt(panel::mutedForTest));
        assertEquals(Boolean.TRUE, engine.mutes.getLast());
    }

    @Test
    void nextWalksTheSequenceAndRevokesTheOldToken() throws Exception {
        await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING, "playback starts");
        String firstUrl = panel.bridgeUrlForTest();
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("PAGE_DOWN"));
        await(() -> "b.mkv".equals(panel.currentForTest()), "the walk moved on");
        await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING, "b plays");

        HttpResponse<byte[]> old = HTTP.send(HttpRequest.newBuilder(
                        URI.create(firstUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(404, old.statusCode(), "a.mp4's token died with the step");
        HttpResponse<byte[]> fresh = HTTP.send(HttpRequest.newBuilder(
                        URI.create(panel.bridgeUrlForTest())).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(3, fresh.body().length, "the new token serves b.mkv's bytes");
        assertEquals(2, engine.openedUrls.size(), "the reused engine opened the next URL");
    }

    @Test
    void escapeClosesReleasesTheEngineAndKillsTheToken() throws Exception {
        await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING, "playback starts");
        String url = panel.bridgeUrlForTest();
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("ESCAPE"));
        assertTrue(closed, "the host's close callback ran");
        await(() -> engine.closed, "the engine is released");
        HttpResponse<byte[]> dead = HTTP.send(HttpRequest.newBuilder(
                        URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(404, dead.statusCode(), "the bridge token died with the panel");
        // A released panel must not mount anything later generations bring.
        assertNull(edt(panel::bridgeUrlForTest));
    }

    @Test
    void aMissingEngineRefusesInsteadOfBreaking() throws Exception {
        PlayerPanel.useEngineFactoryForTest(() -> null);
        AtomicReference<PlayerPanel> bare = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> bare.set(new PlayerPanel(fs, "/", "a.mp4",
                () -> List.of("a.mp4"), n -> {}, () -> {}, null)));
        await(() -> bare.get().stateForTest() == PlayerPanel.State.REFUSED,
                "no engine means a refusal, not a hang");
        bare.get().release();   // and the refusal still tears down cleanly
    }

    @Test
    void keyboardFocusLandsAndStaysOnTheSurface() throws Exception {
        await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING, "playback starts");
        assertEquals(panel.surfaceForTest(), edt(PlayerPanelTest::focusOwner),
                "the mount request puts focus on the surface, where the keys live");
        EventQueue.invokeAndWait(() -> panel.fireClickForTest());
        assertEquals(panel.surfaceForTest(), edt(PlayerPanelTest::focusOwner),
                "a click on the video re-claims focus for the surface");
    }

    private static java.awt.Component focusOwner() {
        return java.awt.KeyboardFocusManager
                .getCurrentKeyboardFocusManager().getFocusOwner();
    }

    // ---- the redesign: footer facts + fading overlay controls ----

    @Test
    void theFooterCarriesTheMediaFacts() throws Exception {
        await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING, "playback starts");
        EventQueue.invokeAndWait(() -> {
            engine.setInfo(new MediaEngine.Info("h264", "aac", 1920, 1080));
            engine.setDuration(60_000);
        });
        await(() -> panel.infoTextForTest().contains("H264/AAC"), "the probe lands");
        String info = panel.infoTextForTest();
        assertTrue(info.contains("1920×1080"), "resolution: " + info);
        assertTrue(info.contains("1:00"), "duration: " + info);
        assertTrue(info.contains("8 B"), "the stat's size: " + info);
        assertTrue(panel.footerForTest().startsWith("a.mp4"), "name leads the strip");
    }

    @Test
    void controlsHideWhenIdleAndWakeOnActivity() throws Exception {
        PlayerPanel.useAutoHideDelayForTest(200);
        try {
            await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING,
                    "playback starts");
            await(() -> !panel.controlsVisibleForTest(),
                    "idle playback fades the controls away");
            assertTrue(panel.cursorHiddenForTest(),
                    "the pointer hides with them, full-screen-player style");
            // Hidden means gone as an obstacle too: the click lands on the
            // video beneath, not on an invisible bar.
            EventQueue.invokeAndWait(() -> panel.fireClickForTest());
            assertEquals(PlayerPanel.State.PAUSED, edt(panel::stateForTest),
                    "the click reached the video through the hidden bar");
            assertTrue(panel.controlsVisibleForTest(),
                    "the pause lights them again and keeps them");
            EventQueue.invokeAndWait(() -> panel.fireClickForTest());   // resume
            await(() -> !panel.controlsVisibleForTest(), "idle hides them again");
            EventQueue.invokeAndWait(() -> panel.fireMotionForTest());
            await(() -> panel.controlsVisibleForTest(), "plain motion wakes them");
        } finally {
            PlayerPanel.useAutoHideDelayForTest(0);
        }
    }

    @Test
    void aDragKeepsTheBarAndReleaseLetsItFade() throws Exception {
        await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING, "playback starts");
        // A thumb mid-drag never loses its bar — the idle verdict restarts.
        EventQueue.invokeAndWait(() -> panel.seekAdjustingForTest(true));
        EventQueue.invokeAndWait(() -> panel.fireAutoHideForTest());
        assertTrue(panel.controlsVisibleForTest(), "a live drag stays lit");
        EventQueue.invokeAndWait(() -> panel.seekAdjustingForTest(false));
        // Released and idle: the fade walks down and ends invisible.
        EventQueue.invokeAndWait(() -> panel.fireAutoHideForTest());
        for (int i = 0; i < 15; i++)
            EventQueue.invokeAndWait(panel::stepControlsFadeForTest);   // ≥ its steps
        assertFalse(panel.controlsVisibleForTest(), "the bar fades away");
        assertEquals(0f, panel.controlsAlphaForTest());
    }

    @Test
    void controlsFloatAboveTheFootageAndVanishWhenHidden() throws Exception {
        PlayerPanel.useAutoHideDelayForTest(60_000);   // stay lit for the captures
        try {
            await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING,
                    "playback starts");
            await(() -> panel.controlsAlphaForTest() >= 0.99f,
                    "the controls settle at fully lit");
            AtomicReference<int[]> stripLit = new AtomicReference<>();
            AtomicReference<int[]> footageLit = new AtomicReference<>();
            EventQueue.invokeAndWait(() -> {
                java.awt.Rectangle bar = panel.controlsBoundsForTest();
                stripLit.set(captureSurfacePaint(bar.x + 2, bar.y + bar.height / 2));
                footageLit.set(captureSurfacePaint(2, 2));
            });
            assertTrue(red(stripLit.get()) < 90,
                    "the scrim darkens the footage behind it ("
                            + red(stripLit.get()) + ")");
            assertTrue(red(footageLit.get()) > 100,
                    "the footage itself still paints beneath ("
                            + red(footageLit.get()) + ")");

            EventQueue.invokeAndWait(() -> panel.fireAutoHideForTest());
            for (int i = 0; i < 15; i++)
                EventQueue.invokeAndWait(panel::stepControlsFadeForTest);
            AtomicReference<int[]> stripGone = new AtomicReference<>();
            EventQueue.invokeAndWait(() -> {
                java.awt.Rectangle bar = panel.controlsBoundsForTest();
                stripGone.set(captureSurfacePaint(bar.x + 2, bar.y + bar.height / 2));
            });
            assertTrue(red(stripGone.get()) > 100,
                    "hidden means the strip shows raw footage again ("
                            + red(stripGone.get()) + ")");
        } finally {
            PlayerPanel.useAutoHideDelayForTest(0);
        }
    }

    @Test
    void theControlsSitAcrossTheBottomOfTheVideo() throws Exception {
        await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING, "playback starts");
        java.awt.Rectangle bar = edt(() -> panel.controlsBoundsForTest());
        int sw = edt(() -> panel.surfaceForTest().getWidth());
        int sh = edt(() -> panel.surfaceForTest().getHeight());
        assertEquals(0, bar.x, "flush left");
        assertEquals(sw, bar.width, "full width of the video area");
        assertEquals(sh, bar.y + bar.height, "flush along the bottom");
    }

    /** One pixel of the surface tree's own paint — where footage, scrim
     *  and controls composite exactly as on screen. */
    private int[] captureSurfacePaint(int x, int y) {
        javax.swing.JComponent s = panel.surfaceForTest();
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.translate(-x, -y);
        s.paint(g);
        g.dispose();
        return img.getRGB(0, 0, 1, 1, null, 0, 1);
    }

    private static int red(int[] rgb) {
        return (rgb[0] >> 16) & 0xFF;
    }

    @Test
    void hoveringTheSeekBarShowsSlidingTimestamps() throws Exception {
        await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING, "playback starts");
        int w = edt(panel::seekWidthForTest);
        assertTrue(w > 100, "the seek bar is laid out (" + w + ")");
        assertNull(panel.scrubTipForTest(w / 2),
                "no duration known yet: no tip at all");

        EventQueue.invokeAndWait(() -> engine.setDuration(60_000));
        await(() -> panel.durationForTest() == 60_000, "the duration lands");
        assertEquals("0:00", panel.scrubTipForTest(0), "the track's left end");
        assertEquals("0:30", panel.scrubTipForTest(w / 2), "the exact middle");
        assertEquals("1:00", panel.scrubTipForTest(w), "the track's right end");

        java.awt.Point left = panel.scrubTipLocationForTest(10);
        java.awt.Point mid = panel.scrubTipLocationForTest(w / 2);
        java.awt.Point right = panel.scrubTipLocationForTest(w - 10);
        assertTrue(left.y < 0 && left.y == mid.y && mid.y == right.y,
                "one fixed height above the bar (" + mid.y + ")");
        assertTrue(left.x < mid.x && mid.x < right.x, "the box slices along x");
        // Two spots equidistant from the middle move the box by exactly
        // their distance: it is centered on the pointer, not leading it.
        java.awt.Point a = panel.scrubTipLocationForTest(w / 2 - 30);
        java.awt.Point b = panel.scrubTipLocationForTest(w / 2 + 30);
        assertEquals(60, b.x - a.x, "the box centers on the pointer");
        assertEquals(0, panel.scrubTipLocationForTest(0).x,
                "clamped flush at the left end");
    }

    // ---- frame previews in the scrub tip ----

    @Test
    void hoveringFetchesFramePreviewsIntoTheTip() throws Exception {
        FakePreview fake = new FakePreview();
        PlayerPanel.usePreviewFactoryForTest(() -> fake);
        try {
            await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING,
                    "playback starts");
            EventQueue.invokeAndWait(() -> engine.setDuration(600_000));
            await(() -> panel.durationForTest() == 600_000, "ten minutes");
            int w = seekWidthSettled();

            Dimension textual = edt(() -> panel.scrubTipSizeForTest(w / 2));
            await(() -> !fake.requested.isEmpty(),
                    "the hover reached the preview source");
            assertTrue(fake.lastUrl.startsWith("http://127.0.0.1:"),
                    "the preview rides its own bridge token ("
                            + fake.lastUrl + ")");
            assertEquals(panel.bridgeUrlForTest(), engine.lastUrl(),
                    "and not the playing engine's token");
            assertEquals(300_000L, fake.requested.get(0),
                    "the five-second bucket at the middle of ten minutes");

            BufferedImage frame =
                    new BufferedImage(176, 99, java.awt.image.BufferedImage.TYPE_INT_RGB);
            EventQueue.invokeAndWait(() -> fake.deliver(frame));
            await(() -> panel.previewCacheSizeForTest() == 1, "the frame is cached");
            Dimension withImage = edt(() -> panel.scrubTipSizeForTest(w / 2));
            assertTrue(withImage.height >= textual.height + 99,
                    "the tip grew an image row (" + textual.height + " → "
                            + withImage.height + ")");
            assertTrue(withImage.width >= 176, "as wide as the frame");

            int asked = fake.requested.size();
            edt(() -> panel.scrubTipSizeForTest(w / 2));
            assertEquals(asked, fake.requested.size(),
                    "a cached bucket never re-requests");
        } finally {
            PlayerPanel.usePreviewFactoryForTest(() -> null);
        }
    }

    @Test
    void steppingToTheNextFileRetiresThePreviewTokenAndTheCache() throws Exception {
        FakePreview fake = new FakePreview();
        PlayerPanel.usePreviewFactoryForTest(() -> fake);
        try {
            await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING,
                    "playback starts");
            EventQueue.invokeAndWait(() -> engine.setDuration(600_000));
            await(() -> panel.durationForTest() == 600_000, "ten minutes");
            int w = seekWidthSettled();
            edt(() -> panel.scrubTipSizeForTest(w / 2));
            await(() -> !fake.requested.isEmpty(), "a request went out");
            EventQueue.invokeAndWait(() -> fake.deliver(
                    new BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB)));
            await(() -> panel.previewCacheSizeForTest() == 1, "cached");
            String aUrl = fake.lastUrl;

            EventQueue.invokeAndWait(() -> panel.fireKeyForTest("PAGE_DOWN"));
            await(() -> "b.mkv".equals(panel.currentForTest()), "the walk moved on");
            await(() -> panel.previewCacheSizeForTest() == 0,
                    "the cache died with the file");
            assertFalse(fake.closed,
                    "the engine itself lives on — one per panel");

            // The next hover opens the new file through a fresh token; the
            // old one is dead on the bridge.
            await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING, "b plays");
            EventQueue.invokeAndWait(() -> engine.setDuration(600_000));
            await(() -> panel.durationForTest() == 600_000, "b's duration");
            int w2 = seekWidthSettled();
            edt(() -> panel.scrubTipSizeForTest(w2 / 2));
            await(() -> !aUrl.equals(fake.lastUrl), "a fresh token for b.mkv");
            HttpResponse<byte[]> dead = HTTP.send(HttpRequest.newBuilder(
                            URI.create(aUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(404, dead.statusCode(),
                    "a.mp4's preview token died with the step");
        } finally {
            PlayerPanel.usePreviewFactoryForTest(() -> null);
        }
    }

    @Test
    void aFailedPreviewLeavesTheTipTextual() throws Exception {
        FakePreview fake = new FakePreview();
        PlayerPanel.usePreviewFactoryForTest(() -> fake);
        try {
            await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING,
                    "playback starts");
            EventQueue.invokeAndWait(() -> engine.setDuration(600_000));
            await(() -> panel.durationForTest() == 600_000, "ten minutes");
            int w = seekWidthSettled();
            edt(() -> panel.scrubTipSizeForTest(w / 2));
            await(() -> !fake.requested.isEmpty(), "a request went out");
            EventQueue.invokeAndWait(() -> fake.deliver(null));
            await(() -> panel.previewIdleForTest(), "the flight retired");
            assertEquals(0, panel.previewCacheSizeForTest(), "nothing cached");
            Dimension textual = edt(() -> panel.scrubTipSizeForTest(w / 2));
            assertTrue(textual.height < 80,
                    "the box carries no image row (" + textual.height + ")");
        } finally {
            PlayerPanel.usePreviewFactoryForTest(() -> null);
        }
    }

    @Test
    void releasingThePanelClosesThePreviewEngine() throws Exception {
        FakePreview fake = new FakePreview();
        PlayerPanel.usePreviewFactoryForTest(() -> fake);
        try {
            await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING,
                    "playback starts");
            EventQueue.invokeAndWait(() -> engine.setDuration(600_000));
            await(() -> panel.durationForTest() == 600_000, "ten minutes");
            int w = seekWidthSettled();
            edt(() -> panel.scrubTipSizeForTest(w / 2));
            await(() -> !fake.requested.isEmpty(), "the engine exists");
            EventQueue.invokeAndWait(() -> panel.fireKeyForTest("ESCAPE"));
            assertTrue(closed, "the host's close callback ran");
            await(() -> fake.closed, "the preview engine released with the panel");
        } finally {
            PlayerPanel.usePreviewFactoryForTest(() -> null);
        }
    }

    // ---- playback memory ----

    /** A recording store: what the panel remembered, as path@position. */
    private static final class RecordingMemory implements dock.media.PlaybackMemory {
        final List<String> wrote = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final Long remembered;

        RecordingMemory(Long remembered) { this.remembered = remembered; }

        @Override public Long positionOf(String path, long mtimeMs) {
            return remembered;
        }

        @Override public void record(String path, long mtimeMs, long positionMs) {
            wrote.add(path + "@" + positionMs);
        }
    }

    /** A bare panel remembering through the given store — the sequence
     *  walk it needs and nothing else. */
    private PlayerPanel rememberingPanel(dock.media.PlaybackMemory memory)
            throws Exception {
        AtomicReference<PlayerPanel> p = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> p.set(new PlayerPanel(fs, "/", "a.mp4",
                () -> List.of("a.mp4"), n -> {}, () -> p.get().release(), memory)));
        return p.get();
    }

    @Test
    void closingRecordsWherePlaybackStood() throws Exception {
        RecordingMemory memory = new RecordingMemory(null);
        PlayerPanel p = rememberingPanel(memory);
        try {
            await(() -> p.stateForTest() == PlayerPanel.State.PLAYING,
                    "playback starts");
            EventQueue.invokeAndWait(() -> engine.setDuration(600_000));
            await(() -> p.durationForTest() == 600_000, "ten minutes");
            EventQueue.invokeAndWait(() -> p.seekToForTest(500));   // half-way
            EventQueue.invokeAndWait(() -> p.fireKeyForTest("ESCAPE"));
            await(() -> !memory.wrote.isEmpty(), "the position is remembered");
            assertEquals("/a.mp4@300000", memory.wrote.get(0));
        } finally {
            EventQueue.invokeAndWait(() -> p.release());
        }
    }

    @Test
    void aWatchedThroughFileForgetsItsPosition() throws Exception {
        RecordingMemory memory = new RecordingMemory(null);
        PlayerPanel p = rememberingPanel(memory);
        try {
            await(() -> p.stateForTest() == PlayerPanel.State.PLAYING,
                    "playback starts");
            EventQueue.invokeAndWait(() -> engine.setDuration(600_000));
            await(() -> p.durationForTest() == 600_000, "ten minutes");
            EventQueue.invokeAndWait(() -> p.seekToForTest(1000));   // the end
            EventQueue.invokeAndWait(engine::finish);
            await(() -> !memory.wrote.isEmpty(), "the end is remembered");
            assertEquals("/a.mp4@0", memory.wrote.get(0),
                    "ten seconds from the end counts as watched through");
        } finally {
            EventQueue.invokeAndWait(() -> p.release());
        }
    }

    @Test
    void aRememberedPositionWithinTheWindowOffersResume() throws Exception {
        RecordingMemory memory = new RecordingMemory(240_000L);   // 4:00 of 10:00
        List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        PlayerPanel.useResumeAskForTest((parent, file, at) -> {
            asked.add(file + "@" + at);
            return true;
        });
        PlayerPanel p = rememberingPanel(memory);
        try {
            await(() -> p.stateForTest() == PlayerPanel.State.PLAYING,
                    "playback starts");
            EventQueue.invokeAndWait(() -> engine.setDuration(600_000));
            await(() -> !asked.isEmpty(), "the offer landed");
            assertEquals("a.mp4@4:00", asked.get(0));
            await(() -> engine.lastSeek() != null && engine.lastSeek() == 240_000L,
                    "YES seeks to the bookmark");
        } finally {
            EventQueue.invokeAndWait(() -> p.release());
            PlayerPanel.useResumeAskForTest(null);
        }
    }

    @Test
    void decliningTheOfferPlaysFromTheTop() throws Exception {
        RecordingMemory memory = new RecordingMemory(240_000L);
        PlayerPanel.useResumeAskForTest((parent, file, at) -> false);
        PlayerPanel p = rememberingPanel(memory);
        try {
            await(() -> p.stateForTest() == PlayerPanel.State.PLAYING,
                    "playback starts");
            EventQueue.invokeAndWait(() -> engine.setDuration(600_000));
            await(() -> p.durationForTest() == 600_000, "the duration landed");
            Thread.sleep(200);   // any offer had every chance
            assertTrue(engine.seeks.stream().noneMatch(s -> s == 240_000L),
                    "NO leaves playback at the top of the file");
        } finally {
            EventQueue.invokeAndWait(() -> p.release());
            PlayerPanel.useResumeAskForTest(null);
        }
    }

    @Test
    void positionsOutsideTheWindowNeverAsk() throws Exception {
        RecordingMemory memory = new RecordingMemory(10_000L);   // too early
        List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        PlayerPanel.useResumeAskForTest((parent, file, at) -> {
            asked.add(file);
            return true;
        });
        PlayerPanel p = rememberingPanel(memory);
        try {
            await(() -> p.stateForTest() == PlayerPanel.State.PLAYING,
                    "playback starts");
            EventQueue.invokeAndWait(() -> engine.setDuration(600_000));
            await(() -> p.durationForTest() == 600_000, "the duration landed");
            Thread.sleep(200);   // any offer had every chance
            assertTrue(asked.isEmpty(), "a ten-second bookmark offers nothing");
        } finally {
            EventQueue.invokeAndWait(() -> p.release());
            PlayerPanel.useResumeAskForTest(null);
        }
    }

    // ---- helpers ----

    // ---- subtitle sidecars ----

    @Test
    void moviesWithoutSidecarsKeepTheCcButtonHidden() throws Exception {
        await(() -> panel.stateForTest() == PlayerPanel.State.PLAYING, "playback starts");
        await(() -> panel.subtitleCountForTest() == 0, "no sidecar discovery");
        assertFalse(edt(panel::ccVisibleForTest), "no CC affordance at all");
    }

    @Test
    void cyclesSidecarsOnAndOffThroughTheEngine() throws Exception {
        fs.add("/a.en.srt", "EN-SUBS".getBytes());
        fs.add("/a.srt", "PLAIN-SUBS".getBytes());
        fs.add("/a.rus.ass", "NOT-A-MATCH".getBytes());   // lang variants are srt-only
        fs.add("/b.mkv", new byte[] {4});
        PlayerPanel p = subtitlePanel();

        await(() -> p.subtitleCountForTest() == 2, "both sidecars discovered");
        assertTrue(edt(p::ccVisibleForTest));
        assertEquals(-1, edt(p::subtitleIndexForTest), "the cycle starts parked off");
        assertTrue(engine.subtitles.isEmpty());

        // The C key drives the first cycle — off → a.en.srt (sorted first).
        EventQueue.invokeAndWait(() -> p.fireKeyForTest("typed c"));
        await(() -> p.subtitleTempForTest("a.en.srt") != null, "the sidecar fetched");
        File english = p.subtitleTempForTest("a.en.srt");
        assertEquals("EN-SUBS", Files.readString(english.toPath()),
                "the temp carries the sidecar's real bytes");
        await(() -> engine.subtitles.size() == 1, "the engine got the temp file");
        assertSame(english, engine.subtitles.get(0));

        // The button drives the second — a.en.srt → a.srt.
        EventQueue.invokeAndWait(p::cycleSubtitleForTest);
        await(() -> p.subtitleTempForTest("a.srt") != null, "the second fetched");
        File plain = p.subtitleTempForTest("a.srt");
        assertEquals("PLAIN-SUBS", Files.readString(plain.toPath()));
        await(() -> engine.subtitles.size() == 2, "the second sidecar applied");

        // Off again — null to the engine, the cycle parked.
        EventQueue.invokeAndWait(p::cycleSubtitleForTest);
        await(() -> engine.subtitles.size() == 3, "the off step applied");
        assertNull(engine.subtitles.get(2), "off is a null loadSubtitle");
        assertEquals(-1, edt(p::subtitleIndexForTest));

        // Wrapping around reuses the fetched temp — no second fetch.
        EventQueue.invokeAndWait(p::cycleSubtitleForTest);
        await(() -> engine.subtitles.size() == 4, "the wrap applied");
        assertSame(english, engine.subtitles.get(3), "the cached temp, reused");

        // Walking to a movie without sidecars hides the button and
        // deletes both temps.
        EventQueue.invokeAndWait(() -> p.fireKeyForTest("PAGE_DOWN"));
        await(() -> p.currentForTest().equals("b.mkv"), "navigation lands");
        await(() -> p.subtitleCountForTest() == 0, "the cycle resets per file");
        assertFalse(edt(p::ccVisibleForTest));
        await(() -> !english.exists() && !plain.exists(), "temps deleted");

        EventQueue.invokeAndWait(p::release);
    }

    @Test
    void aSidecarThatCannotBeFetchedParksTheCycleBackOff() throws Exception {
        fs.add("/a.en.srt", "EN-SUBS".getBytes());
        fs.unreadable("/a.en.srt");
        PlayerPanel p = subtitlePanel();

        await(() -> p.subtitleCountForTest() == 1, "the sidecar is discovered");
        EventQueue.invokeAndWait(p::cycleSubtitleForTest);
        await(() -> p.subtitleIndexForTest() == -1,
                "the failed fetch parks the cycle back at off");
        assertTrue(engine.subtitles.isEmpty(), "the engine never heard from it");
        assertTrue(edt(p::ccVisibleForTest), "the affordance stays for a retry");

        EventQueue.invokeAndWait(p::release);
    }

    /** A second panel on the same fs and frame, for tests that add files
     *  after the shared one was built — discovery runs at load. */
    private PlayerPanel subtitlePanel() throws Exception {
        PlayerPanel[] holder = new PlayerPanel[1];
        EventQueue.invokeAndWait(() -> {
            PlayerPanel fresh = new PlayerPanel(fs, "/", "a.mp4",
                    () -> List.of("a.mp4", "b.mkv"), n -> { }, () -> { }, null);
            holder[0] = fresh;
            frame.getContentPane().removeAll();
            frame.add(fresh);
            frame.revalidate();
        });
        return holder[0];
    }

    /** The seek bar's width with any pending relayout already applied —
     *  a landed duration rewrites the total label ("0:00" → "10:00") and
     *  re-shapes the seek row asynchronously; a width read before that
     *  lands maps a stale mid-x past the track's center. */
    private int seekWidthSettled() throws Exception {
        return edt(() -> {
            frame.validate();
            return panel.seekWidthForTest();
        });
    }

    private static void await(java.util.function.BooleanSupplier until, String what)
            throws InterruptedException {
        for (int i = 0; i < 250 && !until.getAsBoolean(); i++) Thread.sleep(20);
        assertTrue(until.getAsBoolean(), what + " (timed out)");
    }

    private interface EdtSupplier<T> { T get(); }

    private static <T> T edt(EdtSupplier<T> read) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> out.set(read.get()));
        return out.get();
    }
}
