package dock.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import dock.kit.FontRegistry;
import dock.syntax.SyntaxKind;
import dock.syntax.SyntaxSpan;
import java.util.List;
import javax.swing.UIManager;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Fenced code lands in the card as colored spans — the syntax module's
 * IR wired through the renderer, lexed from the exact text the card
 * paints, with the theme's palette riding the snapshot.
 */
class CodeHighlightTest {

    @BeforeAll
    static void setup() {
        FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @Test
    void fencedJavaLexesIntoSpansOnThePaintedText() {
        StyledDocument doc = MarkdownRenderer.render("""
                # readme

                ```java
                // demo
                public class Demo {
                    String s = "hi";
                }
                ```
                """, MdTheme.current());
        CodeArea area = cardOf(doc).codeForTest();
        List<SyntaxSpan> spans = area.spans();
        assertTrue(!spans.isEmpty(), "the java fence lexes");
        String text = area.text();
        assertTrue(slices(text, spans, SyntaxKind.COMMENT).contains("// demo"),
                "comments color: " + spans);
        assertTrue(slices(text, spans, SyntaxKind.KEYWORD).contains("public class"),
                "keywords color and adjacent ones merge: " + spans);
        assertTrue(slices(text, spans, SyntaxKind.STRING).contains("\"hi\""),
                "strings color including quotes: " + spans);
    }

    @Test
    void unknownLanguagesStayPlain() {
        StyledDocument doc = MarkdownRenderer.render("""
                ```somenewlang
                x = ^ y
                ```
                """, MdTheme.current());
        CodeBlock card = cardOf(doc);
        assertEquals("somenewlang", card.language());
        assertTrue(card.codeForTest().spans().isEmpty(),
                "an unknown fence is a look, not an error");
    }

    @Test
    void thePaletteRidesTheThemeSnapshot() {
        StyledDocument doc = MarkdownRenderer.render("""
                ```python
                x = "dock"
                ```
                """, MdTheme.current());
        assertEquals(UIManager.getColor("Dock.codeKeyword"),
                cardOf(doc).codeForTest().palette().of(SyntaxKind.KEYWORD),
                "the card paints keywords with the theme's token color");
    }

    private static CodeBlock cardOf(StyledDocument d) {
        for (javax.swing.text.Element e : leaves(d))
            if (StyleConstants.getComponent(e.getAttributes()) instanceof CodeBlock c)
                return c;
        throw new AssertionError("no code card in the document");
    }

    private static List<javax.swing.text.Element> leaves(StyledDocument d) {
        List<javax.swing.text.Element> out = new java.util.ArrayList<>();
        collect(d.getDefaultRootElement(), out);
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

    private static List<String> slices(String text, List<SyntaxSpan> spans,
                                       SyntaxKind kind) {
        return spans.stream()
                .filter(s -> s.kind() == kind)
                .map(s -> text.substring(s.start(), s.end()))
                .toList();
    }
}
