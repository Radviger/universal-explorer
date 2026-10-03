package dock.markdown;

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
import org.junit.jupiter.api.Test;

/**
 * Word wrap off sizes the cards to the reading surface, not to the
 * document: the unwrapped text pane is as wide as the longest line
 * anywhere ran, and a card that measured itself against the host (or let
 * its row stretch it — an embedded component's maximum span is unbounded
 * unless it says otherwise) would fill with empty card around short
 * code. A card spans the viewport until its own longest line outgrows
 * that, and then the pane scrolls sideways for it.
 */
class WrapOffCardsTest {

    private static final String LONG_LINE =
            "const packed = \"" + "x".repeat(150) + "\"; // long tail";
    private static final String DOC = """
            # Two cards

            prose before

            ```java
            int a = 1;
            int b = 2;
            ```

            ```js
            head = 3;
            """ + LONG_LINE + """
            tail = 4;
            ```

            closing prose
            """;

    private JFrame frame;
    private MarkdownPage page;

    @BeforeAll
    static void theme() {
        FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
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

    private interface ThrowingCondition { boolean holds() throws Exception; }

    private static void awaitEx(ThrowingCondition cond, String what) throws Exception {
        for (int i = 0; i < 250 && !cond.holds(); i++) Thread.sleep(20);
        assertTrue(cond.holds(), "timed out waiting for: " + what);
    }

    private List<CodeBlock> cards() {
        List<CodeBlock> out = new ArrayList<>();
        StyledDocument doc = page.docForTest();
        for (javax.swing.text.Element e : leaves(doc))
            if (StyleConstants.getComponent(e.getAttributes()) instanceof CodeBlock cb)
                out.add(cb);
        return out;
    }

    private static List<javax.swing.text.Element> leaves(StyledDocument doc) {
        List<javax.swing.text.Element> out = new ArrayList<>();
        collect(doc.getDefaultRootElement(), out);
        return out;
    }

    private static void collect(javax.swing.text.Element e,
            List<javax.swing.text.Element> out) {
        if (e.isLeaf()) {
            out.add(e);
            return;
        }
        for (int i = 0; i < e.getElementCount(); i++) collect(e.getElement(i), out);
    }

    @Test
    void unwrappedCardsKeepTheReadingSurfaceWidth() throws Exception {
        MemFs fs = new MemFs();
        fs.add("/a.md", DOC.getBytes(StandardCharsets.UTF_8));
        CountDownLatch built = new CountDownLatch(1);
        EventQueue.invokeAndWait(() -> {
            page = new MarkdownPage(fs, "/");
            page.render(DOC, built::countDown);
            frame = new JFrame("wrap-off");
            frame.add(page);
            frame.setSize(520, 500);
            frame.setLocation(-2000, 0);
            frame.setVisible(true);
        });
        assertTrue(built.await(5, java.util.concurrent.TimeUnit.SECONDS));
        await(page::ready, "the document installs");
        EventQueue.invokeAndWait(() -> frame.validate());
        await(() -> !cards().isEmpty() && cards().size() == 2, "both cards embed");

        // Wrapped baseline: both cards span the surface, the long card
        // folds taller than the short one.
        int shortW = cardWidth(0);
        int longH = cardHeight(1);
        var vp = page.scroll().getViewport();
        assertTrue(shortW <= vp.getWidth(),
                "wrapped, the card fits the surface (" + shortW + " ≤ " + vp.getWidth() + ")");
        assertTrue(longH > cardHeight(0), "the long card wraps taller while folded");

        // Wrap off: the short card keeps the surface's width — not the
        // document's, which the long card's line just pushed past the
        // pane — and loses its folds; the long card keeps its full line.
        EventQueue.invokeAndWait(() -> {
            page.setWrap(false);
            frame.validate();
        });
        awaitEx(() -> cardWidth(0) <= vp.getWidth() + 1, "the short card stops stretching");
        int shortWOff = cardWidth(0);
        int longWOff = cardWidth(1);
        assertTrue(Math.abs(shortWOff - shortW) <= 2,
                "the short card is the surface's width wrapped and unwrapped: "
                        + shortW + " → " + shortWOff);
        assertTrue(longWOff > vp.getWidth(),
                "the long card outgrows the surface (" + longWOff + " > " + vp.getWidth() + ")");
        assertTrue(page.text().getPreferredSize().width > vp.getWidth(),
                "the page still scrolls sideways for the long line");
        assertTrue(cardHeight(1) < longH, "the long card unfolds");
        assertTrue(cardWidth(0) == cardPrefWidth(0)
                        && cardWidth(1) == cardPrefWidth(1),
                "realized cards are the size they asked for, not stretched by their rows");
        assertNoPaddedRows("unwrapped");

        // Wrap back on: everything folds to the surface again.
        EventQueue.invokeAndWait(() -> {
            page.setWrap(true);
            frame.validate();
        });
        awaitEx(() -> Math.abs(cardWidth(0) - shortW) <= 2, "the short card folds back");
        assertTrue(page.text().getPreferredSize().width <= vp.getWidth() + 1,
                "the horizontal scrollbar folds away again");
        assertNoPaddedRows("wrapped");
    }

    /** Every row carrying an embedded component is exactly that
     *  component's height, in either wrap mode — the paragraph ends
     *  where the block does. */
    private void assertNoPaddedRows(String mode) throws Exception {
        EventQueue.invokeAndWait(() -> {
            var text = page.text();
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
            assertTrue(componentRows >= 2, mode + ": the doc embeds both cards");
            assertTrue(slack.isEmpty(), mode + ": no padded rows: " + slack);
        });
    }

    private int cardWidth(int i) throws Exception {
        return readOnEdt(() -> cards().get(i).getWidth());
    }

    private int cardPrefWidth(int i) throws Exception {
        return readOnEdt(() -> cards().get(i).getPreferredSize().width);
    }

    private int cardHeight(int i) throws Exception {
        return readOnEdt(() -> cards().get(i).getHeight());
    }

    private interface EdtSupplier<T> { T get(); }

    private static <T> T readOnEdt(EdtSupplier<T> read) throws Exception {
        java.util.concurrent.atomic.AtomicReference<T> out =
                new java.util.concurrent.atomic.AtomicReference<>();
        EventQueue.invokeAndWait(() -> out.set(read.get()));
        return out.get();
    }
}
