package dock.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import dock.kit.FontRegistry;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JCheckBox;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JSeparator;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The renderer's output pinned by document facts: run and paragraph
 *  attributes at known offsets, embedded components in the element tree —
 *  never pixels. */
class RendererDocumentTest {

    @BeforeAll
    static void theme() {
        FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    private static StyledDocument render(String md) {
        return MarkdownRenderer.render(md, MdTheme.current());
    }

    private static String text(StyledDocument d) {
        try {
            return d.getText(0, d.getLength());
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AttributeSet chars(StyledDocument d, int offset) {
        return d.getCharacterElement(offset).getAttributes();
    }

    private static AttributeSet para(StyledDocument d, int offset) {
        return d.getParagraphElement(offset).getAttributes();
    }

    private static List<Component> components(StyledDocument d) {
        List<Component> out = new ArrayList<>();
        walk(d.getDefaultRootElement(), out);
        return out;
    }

    private static void walk(Element e, List<Component> out) {
        Component c = StyleConstants.getComponent(e.getAttributes());
        if (c != null) out.add(c);
        for (int i = 0; i < e.getElementCount(); i++) walk(e.getElement(i), out);
    }

    @Test
    void headingsCarryWeightAndSize() {
        StyledDocument d = render("# Title\n\n## Sub\n\n#### Minor\n");
        String t = text(d);
        AttributeSet h1 = chars(d, t.indexOf("Title"));
        assertEquals("JetBrains Mono Bold", StyleConstants.getFontFamily(h1));
        assertEquals(20, StyleConstants.getFontSize(h1));
        AttributeSet h2 = chars(d, t.indexOf("Sub"));
        assertEquals("JetBrains Mono Bold", StyleConstants.getFontFamily(h2));
        assertEquals(17, StyleConstants.getFontSize(h2));
        AttributeSet h4 = chars(d, t.indexOf("Minor"));
        assertEquals("JetBrains Mono Medium", StyleConstants.getFontFamily(h4));
        assertEquals(13, StyleConstants.getFontSize(h4));
        assertEquals(14f, StyleConstants.getSpaceAbove(para(d, t.indexOf("Title"))), 0.01);
    }

    @Test
    void inlineMarksCompose() {
        MdTheme theme = MdTheme.current();
        StyledDocument d = render("plain **bold** *ital* ~~gone~~ `code` [dock](https://dock.dev)\n");
        String t = text(d);
        assertEquals("JetBrains Mono Regular", StyleConstants.getFontFamily(chars(d, t.indexOf("plain"))));
        assertEquals("JetBrains Mono Bold", StyleConstants.getFontFamily(chars(d, t.indexOf("bold"))));
        assertTrue(StyleConstants.isItalic(chars(d, t.indexOf("ital"))));
        assertTrue(StyleConstants.isStrikeThrough(chars(d, t.indexOf("gone"))));
        AttributeSet code = chars(d, t.indexOf("code"));
        assertEquals("JetBrains Mono Regular", StyleConstants.getFontFamily(code));
        assertEquals(theme.codeBackground(), StyleConstants.getBackground(code));
        AttributeSet link = chars(d, t.indexOf("dock"));
        assertEquals("https://dock.dev", link.getAttribute(MarkdownRenderer.HREF));
        assertTrue(StyleConstants.isUnderline(link));
        assertEquals(theme.accent(), StyleConstants.getForeground(link));
    }

    @Test
    void fencedCodeIsOneContinuousCard() {
        MdTheme theme = MdTheme.current();
        StyledDocument d = render("```java fold\nint x = 1;\nint y = 2;\n```\n");
        List<Component> cards = components(d).stream()
                .filter(c -> c instanceof CodeBlock).toList();
        assertEquals(1, cards.size(), "one card per fence, not one per line");
        CodeBlock card = (CodeBlock) cards.get(0);
        assertEquals("java", card.language(), "the fence's first info word is the language");
        assertTrue(card.codeText().contains("int x = 1;"));
        assertTrue(card.codeText().contains("int y = 2;"));
        assertEquals(theme.codeBackground(), card.fill(), "the card fills with the theme's code surface");
        String t = text(d);
        assertFalse(t.contains("int x"), "the code lives in the card, not the document text");
    }

    @Test
    void indentedCodeHasNoLanguageButStillACopyButton() {
        StyledDocument d = render("text\n\n    plain code line\n");
        CodeBlock card = (CodeBlock) components(d).stream()
                .filter(c -> c instanceof CodeBlock).findFirst().orElse(null);
        assertNotNull(card, "an indented code block embeds a card too");
        assertEquals("", card.language(), "no fence, no language name");
        assertTrue(card.codeText().contains("plain code line"));
        assertEquals(1, buttonsIn(card).size(),
                "the header strip is there anyway — copy needs no language");
    }

    /** One click hands the code to the copier and flips the button to its
     *  copied state. */
    @Test
    void copyButtonCopiesTheCodeAndFlashes() {
        List<String> copied = new ArrayList<>();
        CodeBlock card = new CodeBlock("java", "int x = 1;\nint y = 2;\n",
                MdTheme.current(), 0, copied::add);
        JButton copy = buttonsIn(card).get(0);
        copy.doClick();
        assertEquals(List.of("int x = 1;\nint y = 2;"), copied,
                "one copy of exactly the code shown");
        assertTrue(card.copiedForTest(), "the button flipped to its copied state");
    }

    private static List<JButton> buttonsIn(java.awt.Component root) {
        List<JButton> out = new ArrayList<>();
        collectButtons(root, out);
        return out;
    }

    private static void collectButtons(java.awt.Component c, List<JButton> out) {
        if (c instanceof JButton b) out.add(b);
        if (c instanceof java.awt.Container ct)
            for (java.awt.Component child : ct.getComponents()) collectButtons(child, out);
    }

    /** Focus may enter the card so the code selects like any text surface;
     *  escape is bound to hand focus straight back to the document. */
    @Test
    void codeIsSelectableAndEscapeIsBound() {
        CodeBlock card = cardOf(render("```java\nint x = 1;\n```\n"));
        var code = card.codeForTest();
        assertTrue(code.isFocusable(), "focus can enter the card to select");
        assertNull(code.getSelectedText(), "nothing is selected to start");
        javax.swing.KeyStroke esc = javax.swing.KeyStroke.getKeyStroke("ESCAPE");
        Object name = code.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).get(esc);
        assertNotNull(name, "escape is bound inside the card");
        assertNotNull(code.getActionMap().get(name), "and backed by an action");
        code.select(0, 5);
        assertEquals("int x", code.getSelectedText(), "selection behaves like any text surface");
        code.beginSelection(0);   // the mouse-press path
        code.extendSelection(5);  // the drag path
        assertEquals("int x", code.getSelectedText(), "dragging builds the same range");
    }

    /** Ctrl+A takes the whole card; ctrl+C hands whatever is selected —
     *  or everything, when nothing is — to the card's copier. */
    @Test
    void controlShortcutsSelectAllAndCopy() {
        List<String> copied = new ArrayList<>();
        CodeBlock card = new CodeBlock("java", "int x = 1;\n", MdTheme.current(),
                0, copied::add);
        CodeArea code = card.codeForTest();
        var in = code.getInputMap(javax.swing.JComponent.WHEN_FOCUSED);
        code.getActionMap().get(in.get(javax.swing.KeyStroke.getKeyStroke("ctrl A")))
                .actionPerformed(new java.awt.event.ActionEvent(code, 0, "test"));
        assertEquals("int x = 1;", code.getSelectedText(), "ctrl+A takes the whole card");
        code.getActionMap().get(in.get(javax.swing.KeyStroke.getKeyStroke("ctrl C")))
                .actionPerformed(new java.awt.event.ActionEvent(code, 0, "test"));
        assertEquals(List.of("int x = 1;"), copied, "ctrl+C copies the selection");
        code.select(0, 0);
        code.getActionMap().get(in.get(javax.swing.KeyStroke.getKeyStroke("ctrl C")))
                .actionPerformed(new java.awt.event.ActionEvent(code, 0, "test"));
        assertEquals(List.of("int x = 1;", "int x = 1;"), copied,
                "an empty selection copies the whole card instead");
    }

    /** The gutter numbers the code's logical lines and sits left of them;
     *  each number anchors at its own line's start, so wrapped rows carry
     *  no number of their own. */
    @Test
    void gutterNumbersTheLinesBesideTheCode() {
        StyledDocument doc = render("```java\nint x = 1;\nint y = 2;\n```\n");
        CodeBlock card = cardOf(doc);
        assertEquals(2, card.lineCount(), "one number per logical line");
        mount(doc);
        var gutter = card.gutterForTest();
        var code = card.codeForTest();
        assertTrue(gutter.getWidth() > 0, "the gutter is laid out");
        assertTrue(code.getX() >= gutter.getWidth(),
                "numbers sit left of the code: gutter " + gutter.getWidth()
                        + ", code at " + code.getX());
        int b1 = card.gutterBaselineForTest(0);
        int b2 = card.gutterBaselineForTest(1);
        assertTrue(b1 >= 0 && b2 > b1 + 5,
                "line 2's number is below line 1's: " + b1 + ", " + b2);
    }

    @Test
    void wrappedLinesCarryOneNumber() {
        StyledDocument doc = render(
                "```java\nint aaaa = \"" + "x".repeat(200) + "\";\n```\n");
        CodeBlock card = cardOf(doc);
        mount(doc);
        assertEquals(1, card.lineCount(), "one logical line, however many visual rows");
        int b = card.gutterBaselineForTest(0);
        assertTrue(b >= 0 && b < 30, "its number anchors at the first row: " + b);
    }

    /** The card follows the pane's width — a headless mount, paint, and
     *  geometry read of the embedded component's placement; layout facts,
     *  never rendered pixels. Long lines must wrap inside the card rather
     *  than clip or force a scrollbar. */
    @Test
    void cardFillsThePaneWidthAndWraps() {
        StyledDocument plainDoc = render("```java\nint x = 1;\n```\n");
        CodeBlock plain = cardOf(plainDoc);
        StyledDocument wrappedDoc = render(
                "```java\nint aaaa = \"" + "x".repeat(200) + "\";\n```\n");
        CodeBlock wrapped = cardOf(wrappedDoc);
        // One pane per card, laid out and painted before the next mount
        // tears this one's views down.
        java.awt.Rectangle plainAt = mount(plainDoc);
        java.awt.Rectangle wrappedAt = mount(wrappedDoc);
        // The embedded component's parent is ComponentView's Invalidator,
        // placed in pane coordinates. A pixel or two of float→int floor
        // separates the span from the margin arithmetic — no more.
        int expect = 500 - mountMarginLeft - mountMarginRight;
        assertTrue(Math.abs(expect - plainAt.width) <= 2,
                "the card spans the text width: " + plainAt.width + " of " + expect
                        + " card=" + plain.getBounds()
                        + " pref=" + plain.getPreferredSize()
                        + " invParent=" + plain.getParent().getParent()
                        + " thread=" + Thread.currentThread().getName());
        assertEquals(plainAt.width, plain.getWidth(), "and is sized to that span");
        assertEquals(plain.getPreferredSize().height, plainAt.height,
                "the card's height is its preferred height");
        assertTrue(wrappedAt.height > plainAt.height + 20,
                "a long line wraps inside the card: " + wrappedAt.height
                        + " vs " + plainAt.height);
    }

    private static CodeBlock cardOf(StyledDocument d) {
        return (CodeBlock) components(d).stream()
                .filter(c -> c instanceof CodeBlock).findFirst().orElseThrow();
    }

    private static int mountMarginLeft, mountMarginRight;

    /** Mounts a rendered document in a packed off-screen frame on the
     *  EDT, forces a real layout, and returns the card's placement — the
     *  Invalidator bounds, in pane coordinates. A real hierarchy is the
     *  point: the card sizes itself to its host's width and keeps
     *  exactly that width (its maximum is its preferred), so the
     *  placement must come from a genuine resize-and-layout pass, not
     *  from a bare pane whose flow still carries the spans it measured
     *  before it had a width — the stretch of an unbounded embedded
     *  component used to paper over that, masking what the card actually
     *  asked for. Everything runs inside one EDT dispatch and the frame
     *  never shows, so no paint of it can interleave with the test
     *  thread's document building. A repeat paint settles the rare run
     *  whose first pass defers the embedded component's relayout; three
     *  passes is a bound, not a retry lottery. Painting sets the card's
     *  bounds through the flow, but its own children (gutter, code
     *  area) lay out only on validate, which no paint triggers — the
     *  geometry the gutter reads needs that pass. */
    private static java.awt.Rectangle mount(StyledDocument doc) {
        java.util.concurrent.atomic.AtomicReference<java.awt.Rectangle> at =
                new java.util.concurrent.atomic.AtomicReference<>();
        int[] margins = new int[2];
        try {
            java.awt.EventQueue.invokeAndWait(() -> {
                javax.swing.JFrame frame = new javax.swing.JFrame();
                frame.setLocation(-2000, 0);
                javax.swing.JTextPane pane = new javax.swing.JTextPane();
                pane.setDocument(doc);
                pane.setPreferredSize(new java.awt.Dimension(500, 2000));
                frame.add(pane);
                frame.pack();
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                        500, 2000, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Rectangle placed = new java.awt.Rectangle();
                for (int i = 0; i < 3 && placed.width == 0; i++) {
                    pane.paint(img.getGraphics());
                    placed = cardOf(doc).getParent().getBounds();
                }
                layOutDeep(cardOf(doc));
                java.awt.Insets m = pane.getMargin();
                margins[0] = m.left;
                margins[1] = m.right;
                frame.dispose();
                at.set(placed);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
        mountMarginLeft = margins[0];
        mountMarginRight = margins[1];
        return at.get();
    }

    /** Lays out a subtree by hand: without a native peer, validate() skips
     *  everything, and doLayout never recurses — the gutter's geometry
     *  reads need the card's children sized, not just the card. */
    private static void layOutDeep(java.awt.Component c) {
        if (c instanceof java.awt.Container ct) {
            ct.doLayout();
            for (java.awt.Component child : ct.getComponents()) layOutDeep(child);
        }
    }

    @Test
    void listsMarkIndentAndNumber() {
        StyledDocument d = render("- one\n- two\n\n1. first\n2. second\n");
        String t = text(d);
        assertTrue(t.contains("•  one"));
        assertTrue(t.contains("1.  first"));
        assertTrue(t.contains("2.  second"));
        AttributeSet item = para(d, t.indexOf("one"));
        assertEquals(38f, StyleConstants.getLeftIndent(item), 0.01);   // 18 list + 20 hang
        assertEquals(-20f, StyleConstants.getFirstLineIndent(item), 0.01);
        assertEquals(38f, StyleConstants.getLeftIndent(para(d, t.indexOf("first"))), 0.01);
    }

    @Test
    void nestedListsIndentDeeper() {
        StyledDocument d = render("- outer\n  - inner\n");
        String t = text(d);
        assertTrue(t.contains("inner"));
        assertEquals(56f, StyleConstants.getLeftIndent(para(d, t.indexOf("inner"))), 0.01);
    }

    @Test
    void taskListsRenderCheckboxes() {
        StyledDocument d = render("- [x] done\n- [ ] todo\n");
        String t = text(d);
        assertFalse(t.contains("[x]"), "the marker is a checkbox, not text: " + t);
        assertFalse(t.contains("[ ]"), "the marker is a checkbox, not text: " + t);
        assertFalse(t.contains("•"), "a task item carries no bullet on top");
        List<Component> boxes = components(d).stream()
                .filter(c -> c instanceof JCheckBox).toList();
        assertEquals(2, boxes.size(), "one embedded checkbox per task item");
        JCheckBox done = (JCheckBox) boxes.get(0);
        JCheckBox todo = (JCheckBox) boxes.get(1);
        assertTrue(done.isSelected(), "first item is checked");
        assertFalse(todo.isSelected(), "second item is not");
        assertFalse(done.isEnabled(), "read-only view: the box shows state");
        assertFalse(done.isFocusable(), "and never takes focus from the text");
        assertEquals(38f, StyleConstants.getLeftIndent(para(d, t.indexOf("done"))), 0.01);
    }

    @Test
    void tablesEmbedAGrid() {
        StyledDocument d = render("| a | b |\n| --- | :-: |\n| 1 | 2 |\n");
        TableGrid grid = (TableGrid) components(d).stream()
                .filter(c -> c instanceof TableGrid).findFirst().orElse(null);
        assertNotNull(grid, "a table embeds a grid");
        Component[] cells = grid.getComponents();
        assertEquals(4, cells.length);   // header + one body row, two columns
        assertEquals("a", ((JLabel) cells[0]).getText());
        assertEquals("b", ((JLabel) cells[1]).getText());
        assertEquals("1", ((JLabel) cells[2]).getText());
        assertEquals("2", ((JLabel) cells[3]).getText());
        assertTrue(((JLabel) cells[1]).isOpaque(), "header cells are tinted");
        var gbc = ((java.awt.GridBagLayout) grid.getLayout()).getConstraints(cells[1]);
        assertEquals(java.awt.GridBagConstraints.CENTER, gbc.anchor, ":-: centers the column");
    }

    @Test
    void thematicBreaksEmbedARule() {
        StyledDocument d = render("above\n\n---\n\nbelow\n");
        assertTrue(components(d).stream().anyMatch(c -> c instanceof JSeparator),
                "a thematic break embeds a rule");
        String t = text(d);
        assertTrue(t.contains("above"));
        assertTrue(t.contains("below"));
    }

    @Test
    void quotesIndentAndMute() {
        MdTheme theme = MdTheme.current();
        StyledDocument d = render("> quoted words\n");
        String t = text(d);
        AttributeSet p = para(d, t.indexOf("quoted"));
        assertEquals(16f, StyleConstants.getLeftIndent(p), 0.01);
        assertEquals(16f, StyleConstants.getRightIndent(p), 0.01);
        // The mute rides the run: paragraph attributes don't reach painted
        // text, and an unset run foreground falls back to black.
        assertEquals(theme.muted(), StyleConstants.getForeground(chars(d, t.indexOf("quoted"))));
    }

    @Test
    void rawHtmlStaysLiteral() {
        StyledDocument d = render("<div>x</div>\n\ntext <b>bold</b>\n");
        String t = text(d);
        assertTrue(t.contains("<div>x</div>"), "html blocks are never interpreted");
        assertTrue(t.contains("<b>"));
        assertEquals("JetBrains Mono Regular", StyleConstants.getFontFamily(chars(d, t.indexOf("<div>"))));
    }

    @Test
    void imagesShowTheirTarget() {
        StyledDocument d = render("![alt](pic.png)\n");
        ImageChip chip = (ImageChip) components(d).stream()
                .filter(c -> c instanceof ImageChip).findFirst().orElse(null);
        assertNotNull(chip, "an unloaded image embeds a chip");
        assertTrue(chip.getText().contains("pic.png"));
        assertEquals("JetBrains Mono Regular", chip.getFont().getFontName());
    }

    @Test
    void loadedImagesEmbedInline() {
        javax.swing.Icon icon = new javax.swing.ImageIcon(
                new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_ARGB));
        StyledDocument d = MarkdownRenderer.render("![alt](pic.png)\n", MdTheme.current(),
                java.util.Map.of("pic.png", icon));
        boolean found = false;
        for (javax.swing.text.Element e : leaves(d))
            if (StyleConstants.getIcon(e.getAttributes()) == icon) found = true;
        assertTrue(found, "a loaded image embeds its icon inline");
    }

    private static List<javax.swing.text.Element> leaves(StyledDocument d) {
        List<javax.swing.text.Element> out = new ArrayList<>();
        collectLeaves(d.getDefaultRootElement(), out);
        return out;
    }

    private static void collectLeaves(javax.swing.text.Element e,
                                      List<javax.swing.text.Element> out) {
        if (e.isLeaf()) out.add(e);
        for (int i = 0; i < e.getElementCount(); i++) collectLeaves(e.getElement(i), out);
    }

    @Test
    void rawModeIsPlainMono() {
        StyledDocument d = MarkdownRenderer.raw("# A\n\nalpha\n", MdTheme.current());
        String t = text(d);
        assertTrue(t.startsWith("# A"));
        assertEquals("JetBrains Mono Regular", StyleConstants.getFontFamily(chars(d, t.indexOf("# A"))));
    }
}
