package dock.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import dock.kit.FontRegistry;
import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.text.Element;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Inline images decode from the page's filesystem and embed inline;
 *  link clicks resolve by target — web to the browser (swapped in
 *  tests), same-folder markdown to the host's opener (the reader walked
 *  itself once; the editor opens the linked file). */
class MarkdownImagesLinksTest {

    private static final String DOC = "# A\n\n![shot](shot.png) ![gone](missing.png)"
            + " ![web](https://example.com/x.png)\n\n[home](https://dock.dev) "
            + "[next](b.md) [terms](LICENSE)\n";

    private JFrame frame;
    private MarkdownPage page;
    private final AtomicReference<String> browsed = new AtomicReference<>();
    private final List<String> opened = new ArrayList<>();

    @BeforeAll
    static void theme() {
        FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @BeforeEach
    void build() throws Exception {
        MemFs fs = new MemFs();
        fs.add("/shot.png", png(60, 40, 0xCC3366));
        CountDownLatch built = new CountDownLatch(1);
        EventQueue.invokeAndWait(() -> {
            page = new MarkdownPage(fs, "/");
            page.browserForTest(browsed::set);
            page.onMarkdownLink(opened::add);
            page.render(DOC, built::countDown);
            frame = new JFrame("md-img");
            frame.add(page);
            frame.setSize(500, 400);
            frame.setLocation(-2000, 0);
            frame.setVisible(true);
        });
        assertTrue(built.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "the render callback fired");
        await(page::ready, "a.md rendered");
    }

    @AfterEach
    void tearDown() {
        EventQueue.invokeLater(() -> frame.dispose());
    }

    private static void await(java.util.function.BooleanSupplier cond, String what)
            throws InterruptedException {
        for (int i = 0; i < 250 && !cond.getAsBoolean(); i++) Thread.sleep(20);
        assertTrue(cond.getAsBoolean(), "timed out waiting for: " + what);
    }

    @Test
    void localImagesEmbedAndMissingOnesChip() throws Exception {
        // The remote target fetches after the document shows; its chip
        // reads "loading" until the wire answers, so wait for the settle.
        await(() -> settledRemoteChip(), "the remote image resolves to its chip");
        StyledDocument doc = page.docForTest();
        var icons = new ArrayList<javax.swing.Icon>();
        var chips = new ArrayList<ImageChip>();
        for (Element e : leaves(doc)) {
            javax.swing.Icon icon = StyleConstants.getIcon(e.getAttributes());
            if (icon != null) icons.add(icon);
            if (StyleConstants.getComponent(e.getAttributes()) instanceof ImageChip chip)
                chips.add(chip);
        }
        assertEquals(1, icons.size(), "the real png embeds inline");
        assertTrue(icons.getFirst().getIconWidth() == 60,
                "a small image renders at native width");
        assertEquals(2, chips.size(), "missing and remote images chip: " + chips.size());
        assertTrue(chips.stream().anyMatch(c -> c.getText().contains("missing.png")));
        assertTrue(chips.stream().anyMatch(c -> c.getText().contains("example.com")));
    }

    private boolean settledRemoteChip() {
        for (Element e : leaves(page.docForTest()))
            if (StyleConstants.getComponent(e.getAttributes())
                    instanceof ImageChip chip && !chip.pending()
                    && chip.getText().contains("example.com"))
                return true;
        return false;
    }

    @Test
    void webLinksGoToTheBrowserAndMarkdownLinksHandToTheHost() throws Exception {
        StyledDocument doc = page.docForTest();
        int web = hrefOffset(doc, "https://dock.dev");
        int md = hrefOffset(doc, "b.md");
        assertTrue(web >= 0 && md >= 0, "both links carry targets");

        EventQueue.invokeAndWait(() -> page.followAt(web));
        assertEquals("https://dock.dev", browsed.get(), "web links browse");

        EventQueue.invokeAndWait(() -> page.followAt(md));
        assertEquals(List.of("b.md"), opened, "markdown links go to the host's opener");
    }

    @Test
    void extensionlessRepoDocLinksHandToTheHostToo() throws Exception {
        StyledDocument doc = page.docForTest();
        int terms = hrefOffset(doc, "LICENSE");
        assertTrue(terms >= 0, "the license link carries a target");

        EventQueue.invokeAndWait(() -> page.followAt(terms));
        assertEquals(List.of("LICENSE"), opened,
                "extensionless repo docs open without an extension");
    }

    @Test
    void plainOffsetsAreNotLinks() throws Exception {
        StyledDocument doc = page.docForTest();
        int plain = doc.getDefaultRootElement().getStartOffset();
        EventQueue.invokeAndWait(() -> page.followAt(plain));
        assertEquals(null, browsed.get());
        assertTrue(opened.isEmpty(), "nothing opened from a plain offset");
    }

    // ---- helpers ----

    private static int hrefOffset(StyledDocument d, String target) {
        for (Element e : leaves(d))
            if (target.equals(e.getAttributes().getAttribute(MarkdownRenderer.HREF)))
                return e.getStartOffset();
        return -1;
    }

    private static List<Element> leaves(StyledDocument d) {
        List<Element> out = new ArrayList<>();
        collectLeaves(d.getDefaultRootElement(), out);
        return out;
    }

    private static void collectLeaves(Element e, List<Element> out) {
        if (e.isLeaf()) out.add(e);
        for (int i = 0; i < e.getElementCount(); i++) collectLeaves(e.getElement(i), out);
    }

    /** A real little PNG — the page reads actual bytes and decodes. */
    private static byte[] png(int w, int h, int rgb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) img.setRGB(x, y, 0xFF000000 | rgb);
        var out = new java.io.ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }
}
