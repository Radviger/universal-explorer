package dock.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import dock.kit.FontRegistry;
import java.awt.EventQueue;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import javax.swing.JFrame;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The page's own life: render lands ready with the built document, the
 *  raw flip swaps documents in place, links show the hand, escape inside
 *  a code card hands focus back, and the surface spans its host edge to
 *  edge. The file loading and refusal around it live with the editor
 *  that hosts the page (EditorPanelTest). */
class MarkdownPageTest {

    private static final String DOC = "# A\n\nalpha text\n";

    private JFrame frame;
    private MarkdownPage page;
    private StyledDocument doc;

    @BeforeAll
    static void theme() {
        FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @BeforeEach
    void build() throws Exception {
        MemFs fs = new MemFs();
        fs.add("/a.md", DOC.getBytes(StandardCharsets.UTF_8));
        CountDownLatch built = new CountDownLatch(1);
        EventQueue.invokeAndWait(() -> {
            page = new MarkdownPage(fs, "/");
            page.render(DOC, built::countDown);
            frame = new JFrame("page");
            frame.add(page);
            frame.setSize(500, 400);
            frame.setLocation(-2000, 0);
            frame.setVisible(true);
        });
        assertTrue(built.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "the render callback fired");
        await(page::ready, "the document installs");
        doc = page.docForTest();
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
    void rendersTheDocument() throws Exception {
        String t = docText(doc);
        assertTrue(t.contains("alpha text"), "the markdown renders to text");
        assertEquals("JetBrains Mono Bold",
                StyleConstants.getFontFamily(doc.getCharacterElement(0).getAttributes()));
    }

    /** A re-render replaces the document: a newer render supersedes an
     *  older one, which is what walking between files used to ride. */
    @Test
    void reRenderingReplacesTheDocument() throws Exception {
        CountDownLatch again = new CountDownLatch(1);
        EventQueue.invokeAndWait(() -> page.render("# B\n\nbeta\n", again::countDown));
        assertTrue(again.await(5, java.util.concurrent.TimeUnit.SECONDS));
        await(() -> docText(page.docForTest()).contains("beta"), "beta shows");
        assertTrue(!docText(page.docForTest()).contains("alpha"),
                "the previous document is gone");
    }

    @Test
    void rawToggleSwapsDocuments() throws Exception {
        assertTrue(docText(doc).contains("alpha text"));
        EventQueue.invokeAndWait(() -> page.toggleRaw());
        assertTrue(page.showingRaw());
        String raw = docText(page.docForTest());
        assertTrue(raw.startsWith("# A"), "raw shows the source: " + raw);
        assertEquals("JetBrains Mono Regular", StyleConstants.getFontFamily(
                page.docForTest().getCharacterElement(0).getAttributes()));
        EventQueue.invokeAndWait(() -> page.toggleRaw());
        assertTrue(docText(page.docForTest()).contains("alpha text"));
        assertTrue(!page.showingRaw());
    }

    /** The hand cursor on hover only worked once the adapter was also
     *  registered as a motion listener — MOUSE_MOVED never reaches a
     *  plain MouseListener registration. This drives the real listener
     *  with component-relative points from laid-out model coordinates. */
    @Test
    void linksShowTheHandOnHover() throws Exception {
        String withLink = "[dock](https://dock.dev) plain\n";
        CountDownLatch built = new CountDownLatch(1);
        MemFs fs = new MemFs();
        EventQueue.invokeAndWait(() -> {
            page = new MarkdownPage(fs, "/");
            page.render(withLink, built::countDown);
            frame.getContentPane().removeAll();
            frame.add(page);
            frame.revalidate();
        });
        assertTrue(built.await(5, java.util.concurrent.TimeUnit.SECONDS));
        await(() -> page.ready(), "link doc loads");
        EventQueue.invokeAndWait(() -> {
            var text = page.text();
            // Force a real layout so model→view coordinates exist.
            text.setSize(500, 400);
            var img = new java.awt.image.BufferedImage(500, 400,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            text.paint(img.getGraphics());
            String t = docText(page.docForTest());
            try {
                hover(text, text.modelToView2D(t.indexOf("dock")));
                assertEquals(java.awt.Cursor.HAND_CURSOR, text.getCursor().getType(),
                        "over a link run");
                hover(text, text.modelToView2D(t.indexOf("plain")));
                assertEquals(java.awt.Cursor.TEXT_CURSOR, text.getCursor().getType(),
                        "over plain text");
            } catch (javax.swing.text.BadLocationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** Focus entering a code card must not orphan the surface: escape
     *  inside the card hands focus back to the document. */
    @Test
    void escapeInsideACodeCardReturnsFocusToTheDocument() throws Exception {
        String withCode = "# C\n\n```java\nint q = 1;\n```\n";
        CountDownLatch built = new CountDownLatch(1);
        MemFs fs = new MemFs();
        EventQueue.invokeAndWait(() -> {
            page = new MarkdownPage(fs, "/");
            page.render(withCode, built::countDown);
            frame.getContentPane().removeAll();
            frame.add(page);
            frame.revalidate();
        });
        assertTrue(built.await(5, java.util.concurrent.TimeUnit.SECONDS));
        await(page::ready, "code doc loads");
        CodeBlock card = null;
        for (javax.swing.text.Element e : leavesOf(page.docForTest())) {
            Object c = StyleConstants.getComponent(e.getAttributes());
            if (c instanceof CodeBlock cb) card = cb;
        }
        assertTrue(card != null, "the fence embedded a card");
        CodeArea code = card.codeForTest();
        EventQueue.invokeAndWait(() -> code.requestFocusInWindow());
        await(() -> code.isFocusOwner(), "focus enters the card's code");
        EventQueue.invokeAndWait(() -> {
            javax.swing.KeyStroke esc = javax.swing.KeyStroke.getKeyStroke("ESCAPE");
            Object name = code.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).get(esc);
            code.getActionMap().get(name)
                    .actionPerformed(new java.awt.event.ActionEvent(code, 0, "test"));
        });
        await(() -> page.text().isFocusOwner(), "focus is back on the document");
    }

    /** The page reaches every edge of its host: whatever the document
     *  doesn't cover — the padding frame, the space below a short file —
     *  is the viewport's to paint, and it paints page, so none of it
     *  reads as a gap in the surface. */
    @Test
    void thePageReachesEveryEdgeOfTheHost() throws Exception {
        String shortDoc = "# Tiny\n\ntwo words\n";
        CountDownLatch built = new CountDownLatch(1);
        MemFs fs = new MemFs();
        EventQueue.invokeAndWait(() -> {
            page = new MarkdownPage(fs, "/");
            page.render(shortDoc, built::countDown);
            frame.getContentPane().removeAll();
            frame.add(page);
            frame.revalidate();
        });
        assertTrue(built.await(5, java.util.concurrent.TimeUnit.SECONDS));
        await(page::ready, "short doc loads");
        EventQueue.invokeAndWait(() -> {
            var scroll = page.scroll();
            assertEquals(0, scroll.getX(), "nothing pads the reading surface sideways");
            assertEquals(page.getWidth(), scroll.getWidth(),
                    "the page spans the host's width edge to edge");
            assertEquals(page.text().getBackground(),
                    scroll.getViewport().getBackground(),
                    "the frame and the space below a short doc paint as page");
            assertTrue(page.text().getHeight() <= scroll.getViewport().getHeight(),
                    "a short doc ends above the fold — the leftover is page, not a gap");
        });
    }

    /** An embedded block — a code card, a table — is its paragraph's
     *  only meaningful height: the glyph that hosts it and the newline
     *  that closes the paragraph are two-pixel runs, and the paragraph
     *  carries no line spacing, so no phantom line box or spacing
     *  surcharge pads the block's row. (At the body metrics that
     *  padding was a quarter of the block's own height, reading as
     *  huge bottom padding that grew with the block.) */
    @Test
    void embeddedBlocksSizeTheirOwnRowsExactly() throws Exception {
        String withBlocks = """
                # D

                before

                ```java
                int a = 1;
                int b = 2;
                ```

                middle

                | h |
                | - |
                | v |

                after
                """;
        CountDownLatch built = new CountDownLatch(1);
        MemFs fs = new MemFs();
        EventQueue.invokeAndWait(() -> {
            page = new MarkdownPage(fs, "/");
            page.render(withBlocks, built::countDown);
            frame.getContentPane().removeAll();
            frame.add(page);
            frame.revalidate();
        });
        assertTrue(built.await(5, java.util.concurrent.TimeUnit.SECONDS));
        await(page::ready, "block doc loads");
        EventQueue.invokeAndWait(() -> {
            var text = page.text();
            text.setSize(500, 600);
            var img = new java.awt.image.BufferedImage(500, 600,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            text.paint(img.getGraphics());
            javax.swing.text.View section = ((javax.swing.plaf.TextUI)
                    text.getUI()).getRootView(text).getView(0);
            int componentRows = 0;
            List<String> slack = new ArrayList<>();
            for (int i = 0; i < section.getViewCount(); i++) {
                javax.swing.text.View para = section.getView(i);
                for (int r = 0; r < para.getViewCount(); r++) {
                    javax.swing.text.View row = para.getView(r);
                    for (int k = 0; k < row.getViewCount(); k++) {
                        javax.swing.text.View child = row.getView(k);
                        if (!(child instanceof javax.swing.text.ComponentView)) continue;
                        componentRows++;
                        float rowH = row.getPreferredSpan(javax.swing.text.View.Y_AXIS);
                        float childH = child.getPreferredSpan(javax.swing.text.View.Y_AXIS);
                        if (rowH > childH + 1f)
                            slack.add(String.format("row %.0f vs block %.0f", rowH, childH));
                    }
                }
            }
            assertTrue(componentRows >= 2, "the doc embeds the card and the table");
            assertTrue(slack.isEmpty(), "no padded rows: " + slack);
        });
    }

    private static List<javax.swing.text.Element> leavesOf(StyledDocument d) {
        List<javax.swing.text.Element> out = new ArrayList<>();
        collectLeaves(d.getDefaultRootElement(), out);
        return out;
    }

    private static void collectLeaves(javax.swing.text.Element e,
                                      List<javax.swing.text.Element> out) {
        if (e.isLeaf()) out.add(e);
        for (int i = 0; i < e.getElementCount(); i++) collectLeaves(e.getElement(i), out);
    }

    private static void hover(javax.swing.text.JTextComponent text,
                              java.awt.geom.Rectangle2D r) {
        java.awt.event.MouseEvent e = new java.awt.event.MouseEvent(text,
                java.awt.event.MouseEvent.MOUSE_MOVED, System.currentTimeMillis(),
                0, (int) (r.getX() + r.getWidth() / 2),
                (int) (r.getY() + r.getHeight() / 2), 0, false);
        for (java.awt.event.MouseMotionListener l : text.getMouseMotionListeners())
            l.mouseMoved(e);
    }

    private static String docText(StyledDocument d) {
        try {
            return d.getText(0, d.getLength());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
