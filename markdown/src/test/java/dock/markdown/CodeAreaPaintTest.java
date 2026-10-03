package dock.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import dock.kit.FontRegistry;
import dock.syntax.SyntaxKind;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The card's painting carries token colors past blank lines — a regression
 * on the reported stompjs snippet, where an empty line swallowed the
 * cursor's span list and everything below it painted plain. Asserted by
 * counting exact-palette pixels in each line's painted band: the glyphs'
 * interiors render at the exact theme color, so a line that lexes and
 * paints shows the hue and a line that doesn't shows none.
 */
class CodeAreaPaintTest {

    @BeforeAll
    static void setup() {
        FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    /** The snippet that broke: js with a blank line after the import, so
     *  the second painted line is where swallowed spans used to vanish. */
    private static final String JS = """
            import { Client } from '@stomp/stompjs';

            const client = new Client({
              brokerURL: 'ws://localhost:15674/ws',
              onConnect: () => {
                client.subscribe('/topic/test01', message =>
                  console.log(`Received: ${message.body}`)
                );
                client.publish({ destination: '/topic/test01', body: 'First Message' });
              },
            });

            client.activate();""";

    @Test
    void colorsPaintPastBlankLines() {
        CodeArea area = areaOf("```js\n" + JS + "\n```\n");
        BufferedImage img = paint(area);
        Color keyword = MdTheme.current().code().of(SyntaxKind.KEYWORD);
        Color string = MdTheme.current().code().of(SyntaxKind.STRING);

        assertTrue(pixels(img, area, 0, keyword) > 0, "line 1: import colors");
        assertTrue(pixels(img, area, 0, string) > 0, "line 1: the url string colors");
        assertEquals(0, pixels(img, area, 1, keyword) + pixels(img, area, 1, string),
                "the blank line carries no color");
        assertTrue(pixels(img, area, 2, keyword) > 0,
                "line 3 past the blank: const/new color");
        assertTrue(pixels(img, area, 3, string) > 0, "line 4: brokerURL's string colors");
        assertTrue(pixels(img, area, 6, string) > 0, "line 7: the template literal colors");
        assertTrue(pixels(img, area, 8, string) > 0, "line 9: publish's strings color");
        assertEquals(0, pixels(img, area, 12, keyword) + pixels(img, area, 12, string),
                "a line of plain identifiers carries no color");
    }

    /** A snippet whose colors live entirely after two leading blank lines —
     *  the blank line could sit anywhere and the swallow was positional. */
    @Test
    void colorsPaintWhenBlanksLeadTheCard() {
        CodeArea area = areaOf("```java\n\n\nint x = 1;\n```\n");
        BufferedImage img = paint(area);
        assertTrue(pixels(img, area, 2, MdTheme.current().code().of(SyntaxKind.NUMBER)) > 0,
                "the literal on the third line colors");
    }

    private static CodeArea areaOf(String md) {
        StyledDocument doc = MarkdownRenderer.render(md, MdTheme.current());
        for (javax.swing.text.Element e : leaves(doc))
            if (StyleConstants.getComponent(e.getAttributes()) instanceof CodeBlock c)
                return c.codeForTest();
        throw new AssertionError("no code card in the document");
    }

    /** The area painted at its natural size, wide enough not to wrap. */
    private static BufferedImage paint(CodeArea area) {
        area.setWrapWidth(900);
        var size = area.getPreferredSize();
        area.setSize(size);
        BufferedImage img = new BufferedImage(size.width, size.height,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        area.paint(g);
        g.dispose();
        return img;
    }

    /** Exact-color pixels in a logical line's glyph band. */
    private static int pixels(BufferedImage img, CodeArea area, int line, Color c) {
        int baseline = area.lineBaselineY(line);
        int n = 0;
        for (int y = baseline - 12; y <= baseline + 4; y++)
            for (int x = 0; x < img.getWidth(); x++)
                if (new Color(img.getRGB(x, y), true).equals(c)) n++;
        return n;
    }

    private static java.util.List<javax.swing.text.Element> leaves(StyledDocument d) {
        java.util.List<javax.swing.text.Element> out = new java.util.ArrayList<>();
        collect(d.getDefaultRootElement(), out);
        return out;
    }

    private static void collect(javax.swing.text.Element e,
                                java.util.List<javax.swing.text.Element> out) {
        if (e.isLeaf()) {
            out.add(e);
            return;
        }
        for (int i = 0; i < e.getElementCount(); i++) collect(e.getElement(i), out);
    }
}
