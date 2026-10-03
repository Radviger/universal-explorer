package dock.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import dock.kit.FontRegistry;
import java.awt.EventQueue;
import java.awt.event.MouseWheelEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The page's word-wrap modes: on (the default) the page is exactly its
 * host and code cards fold their long lines into it; off, every line
 * keeps its natural width, the page grows a horizontal scrollbar, and
 * shift+wheel rolls it — the editor's convention, here on the rendered
 * surface. The button that flips it lives with the host (the editor's
 * toolbar); these pin the geometry it drives.
 */
class MarkdownWrapTest {

    /** Wide enough to overflow the 420px frame many times over. */
    private static final String LONG_LINE = "wrapword ".repeat(80);
    private static final String LONG_CODE = "int unwrapped = " + "9".repeat(140) + ";";
    // Enough ordinary paragraphs that the page always overflows vertically
    // too — otherwise plain wheel has only the horizontal axis, and the
    // scroll pane (by its own rule) rolls that one.
    private static final String DOC = "# W\n\n" + LONG_LINE
            + "\n\n```java\n" + LONG_CODE + "\nint tail = 1;\n```\n"
            + "\nfiller paragraph over and over\n".repeat(12);

    private JFrame frame;
    private MarkdownPage page;
    private CodeArea codeArea;

    @BeforeAll
    static void theme() {
        FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @BeforeEach
    void build() throws Exception {
        MemFs fs = new MemFs();
        CountDownLatch built = new CountDownLatch(1);
        EventQueue.invokeAndWait(() -> {
            page = new MarkdownPage(fs, "/");
            page.render(DOC, built::countDown);
            frame = new JFrame("wrap");
            frame.add(page);
            frame.setSize(420, 320);
            frame.setLocation(-2000, 0);
            frame.setVisible(true);
        });
        assertTrue(built.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "the render callback fired");
        await(page::ready, "document renders");
        StyledDocument doc = page.docForTest();
        for (javax.swing.text.Element e : leavesOf(doc)) {
            if (StyleConstants.getComponent(e.getAttributes()) instanceof CodeBlock cb)
                codeArea = cb.codeForTest();
        }
        assertTrue(codeArea != null, "the fence embedded a card");
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
    void wrapOnFoldsEverythingIntoTheHost() throws Exception {
        EventQueue.invokeAndWait(() -> {
            assertTrue(page.text().getScrollableTracksViewportWidth(),
                    "wrapped, the page is exactly the host");
            JScrollPane scroll = page.scroll();
            assertFalse(scroll.getHorizontalScrollBar().isVisible(),
                    "no horizontal scrollbar on a wrapped surface");
        });
        // The long code line folds: rows outnumber the two logical lines.
        EventQueue.invokeAndWait(() -> {
            codeArea.setWrapWidth(300);
            assertTrue(codeArea.rowCount() > 2, "a narrow wrap folds the long line");
        });
    }

    @Test
    void turningWrapOffUnwrapsAndScrollsSideways() throws Exception {
        EventQueue.invokeAndWait(() -> page.setWrap(false));
        assertFalse(page.wraps(), "the mode flipped");
        await(() -> page.scroll().getHorizontalScrollBar().isVisible(),
                "the page grows a horizontal scrollbar");
        EventQueue.invokeAndWait(() -> {
            var text = page.text();
            JScrollPane scroll = page.scroll();
            assertFalse(text.getScrollableTracksViewportWidth(),
                    "unwrapped, the page keeps its own width");
            assertTrue(text.getPreferredSize().width > scroll.getViewport().getWidth(),
                    "the page is wider than the host");
            var hbar = scroll.getHorizontalScrollBar();
            assertTrue(hbar.getVisibleAmount() < hbar.getMaximum(),
                    "the scrollbar has somewhere to go");
            assertEquals(Boolean.TRUE, text.getClientProperty(MarkdownPage.NO_WRAP),
                    "the text carries the no-wrap mark the cards read");
        });
        // The card answers the mode: its two logical lines are two rows —
        // the 150-glyph line stands whole instead of folding.
        EventQueue.invokeAndWait(() -> assertEquals(codeArea.lineCount(),
                codeArea.rowCount(), "the code card stops wrapping"));
        EventQueue.invokeAndWait(() -> assertTrue(codeArea.getPreferredSize().width > 900,
                "the code surface takes its natural width"));
    }

    @Test
    void shiftWheelRollsTheUnwrappedPageSideways() throws Exception {
        EventQueue.invokeAndWait(() -> page.setWrap(false));
        await(() -> page.scroll().getHorizontalScrollBar().isVisible(),
                "the scrollbar is up");
        // Scrolled right by shift+wheel — the editor's convention. AWT
        // retargets the wheel to the nearest wheel-enabled ancestor, which
        // is the scroll pane, whose handler converts shift to sideways.
        int before = hValue();
        EventQueue.invokeAndWait(() -> wheel(true, 1));
        assertTrue(hValue() > before, "shift+wheel scrolls horizontally");
        // Plain wheel never moves the horizontal position.
        int afterShift = hValue();
        EventQueue.invokeAndWait(() -> wheel(false, 1));
        assertEquals(afterShift, hValue(), "plain wheel leaves the page's x alone");
    }

    /** While wrapped, shift+wheel must do nothing at all — there is no
     *  sideways to go, and the JDK's rule (shift with no visible hbar
     *  scrolls nothing) keeps the reading surface inert. */
    @Test
    void shiftWheelOnAWrappedPageScrollsNothing() throws Exception {
        EventQueue.invokeAndWait(() -> wheel(true, 1));
        Thread.sleep(150);
        assertEquals(0, hValue(), "nothing moves sideways while wrapped");
    }

    @Test
    void togglingBackOnFoldsAndRemovesTheScrollbar() throws Exception {
        EventQueue.invokeAndWait(() -> page.setWrap(false));
        await(() -> page.scroll().getHorizontalScrollBar().isVisible(),
                "the scrollbar is up");
        EventQueue.invokeAndWait(() -> page.setWrap(true));
        await(() -> !page.scroll().getHorizontalScrollBar().isVisible(),
                "the scrollbar folds away again");
        EventQueue.invokeAndWait(() -> assertTrue(
                page.text().getScrollableTracksViewportWidth(),
                "the page tracks the viewport once more"));
    }

    private int hValue() throws Exception {
        int[] v = new int[1];
        EventQueue.invokeAndWait(() -> v[0] = page.scroll()
                .getHorizontalScrollBar().getValue());
        return v[0];
    }

    /** A unit wheel notch over the text surface, with or without shift. */
    private void wheel(boolean shift, int rotation) {
        var text = page.text();
        text.dispatchEvent(new MouseWheelEvent(text, MouseWheelEvent.MOUSE_WHEEL,
                System.currentTimeMillis(),
                shift ? MouseWheelEvent.SHIFT_DOWN_MASK : 0,
                50, 50, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation));
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
}
