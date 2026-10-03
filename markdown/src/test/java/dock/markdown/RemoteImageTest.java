package dock.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import dock.kit.FontRegistry;
import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.text.Element;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Remote inline images: the reader shows the document at once and each
 * http(s) target streams in on its own thread, replacing the loading
 * chip that held its place. The network side is pinned against a
 * loopback HTTP server — a real fetch, but one the suite owns: a
 * served image lands, a 404 and an over-cap image degrade to the
 * placeholder chip, nothing gates the document's first paint, and a
 * fetch that outlives its document is dropped.
 */
class RemoteImageTest {

    /** Over the 8 MB image cap, as a declared content length. */
    private static final long HUGE = 9_000_000L;

    /** A badge-shaped SVG — the format shields.io and friends serve. */
    private static final String BADGE = """
            <svg xmlns="http://www.w3.org/2000/svg" width="88" height="20">
              <rect width="88" height="20" rx="3" fill="#44cc11"/>
              <text x="44" y="14" font-size="11" fill="#fff" text-anchor="middle">build</text>
            </svg>
            """;

    /** Both pair handlers wait on this: sequential fetching can never
     *  trip it — only concurrent requests meet in the middle. */
    private static final java.util.concurrent.CyclicBarrier PAIR =
            new java.util.concurrent.CyclicBarrier(2);

    /** Each slow endpoint parks on its own latch until its test lets it
     *  go — the reader is observed mid-flight, deterministically. */
    private static final CountDownLatch SLOW_IMAGE = new CountDownLatch(1);
    private static final CountDownLatch SLOW_GONE = new CountDownLatch(1);
    private static final CountDownLatch SLOW_WALK = new CountDownLatch(1);

    private static com.sun.net.httpserver.HttpServer server;
    private static int port;
    private static final AtomicInteger served = new AtomicInteger();

    private JFrame frame;
    private MarkdownPage page;
    private final AtomicInteger closed = new AtomicInteger();

    @BeforeAll
    static void boot() throws Exception {
        FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());

        byte[] png = pngBytes();
        server = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        // Concurrent handlers, so the pair barrier below measures the
        // reader's concurrency, not the server's dispatch order.
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/img.png", ex -> {
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, png.length);
            try (var out = ex.getResponseBody()) { out.write(png); }
            served.incrementAndGet();
        });
        server.createContext("/badge.svg", ex -> {
            byte[] b = BADGE.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "image/svg+xml");
            ex.sendResponseHeaders(200, b.length);
            try (var out = ex.getResponseBody()) { out.write(b); }
        });
        server.createContext("/gone.png", ex -> ex.sendResponseHeaders(404, -1));
        server.createContext("/huge.png", ex -> {
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, HUGE);
            try (var out = ex.getResponseBody()) { out.write(new byte[0]); }
        });
        server.createContext("/pair-a.png", ex -> paired(png, ex));
        server.createContext("/pair-b.png", ex -> paired(png, ex));
        server.createContext("/slow.png", ex -> {
            await(SLOW_IMAGE);
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, png.length);
            try (var out = ex.getResponseBody()) { out.write(png); }
        });
        server.createContext("/slow-gone.png", ex -> {
            await(SLOW_GONE);
            ex.sendResponseHeaders(404, -1);
        });
        server.createContext("/slow-walk.png", ex -> {
            await(SLOW_WALK);
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, png.length);
            try (var out = ex.getResponseBody()) { out.write(png); }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    /** Serves the png only once its sibling request is in flight too —
     *  sequential fetching would strand the barrier until it broke. */
    private static void paired(byte[] png,
                               com.sun.net.httpserver.HttpExchange ex) {
        try {
            PAIR.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            try { ex.sendResponseHeaders(500, -1); } catch (Exception ignored) {}
            return;
        }
        ex.getResponseHeaders().add("Content-Type", "image/png");
        try {
            ex.sendResponseHeaders(200, png.length);
            try (var out = ex.getResponseBody()) { out.write(png); }
        } catch (Exception ignored) {
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterAll
    static void shutdown() {
        server.stop(0);
    }

    @AfterEach
    void tearDown() {
        EventQueue.invokeLater(() -> frame.dispose());
    }

    @Test
    void remoteHttpImagesEmbedLikeLocalOnes() throws Exception {
        open("# R\n\n![shot](http://127.0.0.1:" + port + "/img.png)\n");
        await(page::ready, "the doc loads");
        await(() -> iconsIn(page.docForTest()) == 1, "the png lands in the doc");
        assertEquals(1, served.get(), "it was actually fetched over HTTP");
        assertEquals(0, chipsIn(page.docForTest()), "no placeholder for a good image");
    }

    @Test
    void missingRemoteFallsBackToTheChip() throws Exception {
        open("# G\n\n![x](http://127.0.0.1:" + port + "/gone.png)\n");
        await(() -> failedChips(page.docForTest()) == 1,
                "the 404 resolves to the failure chip");
        assertEquals(0, iconsIn(page.docForTest()), "a 404 embeds nothing");
        assertEquals(0, pendingChips(page.docForTest()));
    }

    @Test
    void oversizeRemoteIsRefusedBeforeDownloading() throws Exception {
        open("# H\n\n![x](http://127.0.0.1:" + port + "/huge.png?sig=1)\n");
        await(() -> failedChips(page.docForTest()) == 1,
                "the over-cap image resolves to the chip");
        assertEquals(0, iconsIn(page.docForTest()), "an over-cap image never embeds");
    }

    /** Badges — what most real READMEs are made of — arrive as SVG; the
     *  reader rasterizes them like any other image instead of chipping
     *  their URL. */
    @Test
    void svgBadgesRasterizeInline() throws Exception {
        open("# B\n\n![build](http://127.0.0.1:" + port + "/badge.svg)\n");
        await(() -> iconsIn(page.docForTest()) == 1, "the badge rasterizes inline");
        StyledDocument doc = page.docForTest();
        assertEquals(0, chipsIn(doc), "no placeholder for a rasterized badge");
        javax.swing.Icon badge = firstIcon(doc);
        assertNotNull(badge, "the badge is an icon, not a chip");
        assertEquals(88, badge.getIconWidth(), "the raster is the badge's own width");
        assertEquals(20, badge.getIconHeight(),
                "the raster is the badge's own height — no letterbox padding");
    }

    /** Images fetch concurrently: each pair handler serves only once its
     *  sibling is in flight too, so a sequential reader could never get
     *  both through the barrier. */
    @Test
    void imagesFetchConcurrently() throws Exception {
        open("# P\n\n![a](http://127.0.0.1:" + port + "/pair-a.png)\n\n"
                + "![b](http://127.0.0.1:" + port + "/pair-b.png)\n");
        await(() -> iconsIn(page.docForTest()) == 2, "both barriered images land");
        assertEquals(0, chipsIn(page.docForTest()));
    }

    /** The whole point of async: the document paints before the wire
     *  answers. While the server still holds the image, the prose is
     *  already there and the image's place is a loading chip. */
    @Test
    void theDocumentShowsBeforeAnyRemoteImageDoes() throws Exception {
        open("# S\n\nprose first\n\n![slow](http://127.0.0.1:"
                + port + "/slow.png)\n");
        await(page::ready, "the doc loads");
        StyledDocument doc = page.docForTest();
        assertTrue(docText(doc).contains("prose first"),
                "text renders without waiting on the image");
        assertEquals(0, iconsIn(doc), "nothing waits on the wire");
        assertEquals(1, pendingChips(doc), "the image's place is held by a loading chip");
        SLOW_IMAGE.countDown();
        await(() -> iconsIn(page.docForTest()) == 1, "the image lands after the fact");
        assertEquals(0, chipsIn(page.docForTest()), "no chip remains");
    }

    /** A fetch that comes back empty swaps its loading chip for the
     *  failure chip — in the live document, after the fact. */
    @Test
    void aLateFailureTurnsPendingIntoTheFailureChip() throws Exception {
        open("# F\n\n![x](http://127.0.0.1:" + port + "/slow-gone.png)\n");
        await(() -> pendingChips(page.docForTest()) == 1, "the place is held");
        SLOW_GONE.countDown();
        await(() -> failedChips(page.docForTest()) == 1, "the miss resolves to a chip");
        assertEquals(0, pendingChips(page.docForTest()));
        assertEquals(0, iconsIn(page.docForTest()));
    }

    /** Moving on to the next document while a fetch is in flight: a
     *  newer render supersedes the old one (what walking between files
     *  rode), and the late arrival must not touch the new document. */
    @Test
    void renderingPastDropsTheArrivalsOfThePreviousDocument() throws Exception {
        MemFs fs = new MemFs();
        java.util.concurrent.CountDownLatch first =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch second =
                new java.util.concurrent.CountDownLatch(1);
        EventQueue.invokeAndWait(() -> {
            page = new MarkdownPage(fs, "/");
            page.render("# A\n\n![slow](http://127.0.0.1:"
                    + port + "/slow-walk.png)\n", first::countDown);
            frame = new JFrame("walk");
            frame.add(page);
            frame.setSize(500, 400);
            frame.setLocation(-2000, 0);
            frame.setVisible(true);
        });
        assertTrue(first.await(5, java.util.concurrent.TimeUnit.SECONDS));
        await(() -> page.ready() && pendingChips(page.docForTest()) == 1,
                "doc.md shows, fetch in flight");
        EventQueue.invokeAndWait(() -> page.render("# Next\n\nplain\n", second::countDown));
        assertTrue(second.await(5, java.util.concurrent.TimeUnit.SECONDS));
        SLOW_WALK.countDown();
        Thread.sleep(300);   // the stale arrival gets its chance to misbehave
        assertEquals(0, iconsIn(page.docForTest()),
                "the previous document's image never lands in this one");
        assertEquals(0, chipsIn(page.docForTest()), "the new document never had images");
        assertTrue(docText(page.docForTest()).contains("plain")
                        && !docText(page.docForTest()).contains("slow"),
                "the new document stands");
    }

    // ---- helpers ----

    private void open(String source) throws Exception {
        MemFs fs = new MemFs();
        EventQueue.invokeAndWait(() -> {
            page = new MarkdownPage(fs, "/");
            page.render(source, null);
            frame = new JFrame("remote");
            frame.add(page);
            frame.setSize(500, 400);
            frame.setLocation(-2000, 0);
            frame.setVisible(true);
        });
    }

    private static int iconsIn(StyledDocument doc) {
        int n = 0;
        for (Element e : leavesOf(doc))
            if (StyleConstants.getIcon(e.getAttributes()) != null) n++;
        return n;
    }

    private static javax.swing.Icon firstIcon(StyledDocument doc) {
        for (Element e : leavesOf(doc)) {
            javax.swing.Icon icon = StyleConstants.getIcon(e.getAttributes());
            if (icon != null) return icon;
        }
        return null;
    }

    private static int chipsIn(StyledDocument doc) {
        return pendingChips(doc) + failedChips(doc);
    }

    private static int pendingChips(StyledDocument doc) {
        return chipsIn(doc, true);
    }

    private static int failedChips(StyledDocument doc) {
        return chipsIn(doc, false);
    }

    private static int chipsIn(StyledDocument doc, boolean pending) {
        int n = 0;
        for (Element e : leavesOf(doc))
            if (StyleConstants.getComponent(e.getAttributes())
                    instanceof ImageChip chip && chip.pending() == pending) n++;
        return n;
    }

    private static String docText(StyledDocument doc) throws Exception {
        return doc.getText(0, doc.getLength());
    }

    private static List<Element> leavesOf(StyledDocument doc) {
        List<Element> out = new ArrayList<>();
        collectLeaves(doc.getDefaultRootElement(), out);
        return out;
    }

    private static void collectLeaves(Element e, List<Element> out) {
        if (e.isLeaf()) out.add(e);
        else for (int i = 0; i < e.getElementCount(); i++) collectLeaves(e.getElement(i), out);
    }

    private static byte[] pngBytes() throws Exception {
        var img = new BufferedImage(24, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 24; x++) img.setRGB(x, y, 0xFF3366AA);
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static void await(java.util.function.BooleanSupplier cond, String what)
            throws InterruptedException {
        for (int i = 0; i < 250 && !cond.getAsBoolean(); i++) Thread.sleep(20);
        assertTrue(cond.getAsBoolean(), "timed out waiting for: " + what);
    }
}
