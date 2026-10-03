package dock.markdown;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import dock.kit.FontRegistry;
import dock.kit.Glyphs;
import dock.kit.Tokens;
import dock.syntax.Syntax;

/**
 * A fenced or indented code block as one continuous card embedded in the
 * rendered document: the language named in the fence rides as the card's
 * header with a copy button at the far edge, the code fills it below in the
 * app's mono face with its lines numbered down the left edge. The card
 * follows the text pane's width — one block, not a paragraph per
 * line with gaps between the stripes — and long lines wrap inside it,
 * because a reading surface never grows a horizontal scrollbar. The one
 * exception is the reader's word-wrap toggle: turned off, the card keeps
 * its longest line whole and the pane scrolls sideways for it instead.
 * The code is selectable and syntax-highlighted: the fence's language
 * drives the syntax module's lexer, unknown languages stay plain, and
 * the spans lex from the exact prepared text the area paints.
 */
public final class CodeBlock extends JPanel {

    /** Corner radius — the app's TextComponent.arc family. */
    private static final int ARC = 8;
    /** Never thinner than this, however narrow the pane. */
    private static final int MIN_WIDTH = 160;
    /** Breathing room between the gutter's numbers and the code. */
    private static final int GUTTER_GAP = 10;

    private final JLabel lang;
    private final JPanel header;
    private final CodeArea code;
    private final javax.swing.JComponent gutter;
    private final JButton copy;
    private final java.util.function.Consumer<String> copier;
    private final Color fill;
    private final Color border;
    private final Color muted;
    private final String language;
    /** Horizontal indent of the paragraph the card sits in (list/quote
     *  depth) — the card sizes to the width actually available to it. */
    private final int inset;
    private boolean copied;
    private javax.swing.Timer copiedBack;

    CodeBlock(String info, String literal, MdTheme theme, int inset) {
        this(info, literal, theme, inset, text ->
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new java.awt.datatransfer.StringSelection(text), null));
    }

    CodeBlock(String info, String literal, MdTheme theme, int inset,
              java.util.function.Consumer<String> copier) {
        super(new BorderLayout());
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        this.inset = inset;
        this.copier = copier;
        this.fill = theme.codeBackground();
        this.border = theme.border();
        this.muted = theme.muted();
        this.language = info == null || info.isBlank()
                ? "" : info.strip().split("\\s+", 2)[0];
        // The spans must lex from the prepared text — tab expansion and
        // trailing-strip change the offsets, and spans over any other
        // string would color the wrong glyphs.
        String prepared = prepare(literal);
        // A reader, not an editor — but the code is text a reader wants to
        // grab: the hand-painted area selects and copies like any text
        // surface while keeping the document's layout inert (its own
        // header comment says why that matters).
        this.code = new CodeArea(prepared, theme.mono(), theme.foreground(),
                Syntax.highlight(prepared, language), theme.code(),
                theme.accent(), copier);
        this.lang = new JLabel(language);
        lang.setFont(FontRegistry.monoMedium(theme.mono().getSize2D() - 1f));
        lang.setForeground(muted);
        this.copy = copyButton(theme);
        this.header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, border),
                BorderFactory.createEmptyBorder(0, 0, 6, 0)));
        if (!language.isEmpty()) header.add(lang, BorderLayout.WEST);
        header.add(copy, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        this.gutter = new LineGutter();
        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        body.add(gutter, BorderLayout.WEST);
        body.add(code, BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);
    }

    /** The fence's first info word — the block's language. */
    public String language() { return language; }

    /** The code the card carries. */
    public String codeText() { return code.text(); }

    /** Rows the gutter numbers — the code's logical lines. */
    public int lineCount() { return code.lineCount(); }

    /** The color the card fills with — the theme's code surface. */
    public Color fill() { return fill; }

    /** The colored runs painted over {@link #codeText()} — the syntax
     *  module's spans, lexed from the fence's language; empty when the
     *  fence named nothing the lexers know. */
    public java.util.List<dock.syntax.SyntaxSpan> spans() { return code.spans(); }

    /** The token palette the spans paint with — the theme snapshot's
     *  code colors, carried for verification. */
    public dock.syntax.SyntaxPalette codePalette() { return code.palette(); }

    // ---- for tests (same package) ----

    CodeArea codeForTest() { return code; }
    javax.swing.JComponent gutterForTest() { return gutter; }
    boolean copiedForTest() { return copied; }

    /** Where the gutter draws line {@code line}'s number — the same math
     *  {@link LineGutter} paints with; −1 before the code is laid out. */
    int gutterBaselineForTest(int line) {
        return code.lineBaselineY(line);
    }

    private static String prepare(String literal) {
        String s = literal.replace("\t", "    ").stripTrailing();
        return s.isEmpty() ? " " : s;
    }

    /** The header's copy control, to the right of the language name: a
     *  borderless glyph button like the toolbar's, never taking focus. */
    private JButton copyButton(MdTheme theme) {
        JButton b = new JButton(Glyphs.iconUniform(Glyphs.COPY, Tokens.ICON,
                () -> muted));
        b.setToolTipText("Copy code");
        b.putClientProperty("JButton.buttonType", "borderless");
        b.setRolloverEnabled(true);
        b.setFocusable(false);
        b.setPreferredSize(new Dimension(26, 20));
        b.addActionListener(e -> copyCode(theme));
        return b;
    }

    private void copyCode(MdTheme theme) {
        copier.accept(code.text());
        copied = true;
        copy.setIcon(Glyphs.iconUniform(Glyphs.CHECK, Tokens.ICON,
                () -> theme.accent()));
        copy.setToolTipText("Copied");
        if (copiedBack == null) {
            copiedBack = new javax.swing.Timer(1500, e -> resetCopy());
            copiedBack.setRepeats(false);
            copiedBack.start();
        } else {
            copiedBack.restart();
        }
    }

    private void resetCopy() {
        copied = false;
        copy.setIcon(Glyphs.iconUniform(Glyphs.COPY, Tokens.ICON, () -> muted));
        copy.setToolTipText("Copy code");
    }

    /** Preferred width follows the text pane, so the card reads as
     *  full-width at whatever the window is; the height is the code
     *  wrapped at exactly that width. A hair narrower than the available
     *  span on purpose: the flow layout floors the allocation, and a
     *  preferred width above what's painted would wrap the height wrong.
     *
     * <p>With the reader's word wrap off, the card keeps its full-width
     * look but never shrinks below its longest line plus the gutter —
     * the pane widens past it and scrolls sideways, the editor's way. */
    @Override public Dimension getPreferredSize() {
        Insets in = getInsets();
        int span = Math.max(MIN_WIDTH, hostWidth() - inset - 2);
        if (!wrapsToHost()) {
            code.setWrapWidth(-1);
            Dimension codeSize = code.getPreferredSize();
            int need = in.left + in.right + gutterWidth() + codeSize.width;
            return new Dimension(Math.max(span, need), in.top + in.bottom
                    + codeSize.height + header.getPreferredSize().height);
        }
        int inner = Math.max(span - in.left - in.right, 40);
        int h = in.top + in.bottom
                + wrappedHeight(Math.max(inner - gutterWidth(), 40))
                + header.getPreferredSize().height;
        return new Dimension(span, h);
    }

    /** Whether the host pane still wraps: it drops the {@link
     *  MarkdownPage#NO_WRAP} client property when the reader turned
     *  word wrap off; hostless cards (tests) wrap. */
    private boolean wrapsToHost() {
        javax.swing.text.JTextComponent host = (javax.swing.text.JTextComponent)
                SwingUtilities.getAncestorOfClass(javax.swing.text.JTextComponent.class, this);
        return host == null
                || !Boolean.TRUE.equals(host.getClientProperty(MarkdownPage.NO_WRAP));
    }

    /** Minimum stays tiny so the text pane keeps tracking its viewport's
     *  width while wrapped — one wide card must not force a horizontal
     *  scrollbar on a reading surface. */
    @Override public Dimension getMinimumSize() {
        return new Dimension(MIN_WIDTH, 20);
    }

    /** Maximum equals preferred. An embedded component answers through
     *  {@link javax.swing.text.ComponentView}, whose maximum span is the
     *  component's maximum size — unbounded for a plain panel — and a
     *  row tiles its children across the span it was allocated, so a
     *  card with no ceiling stretches to the row's width: unwrapped,
     *  every row spans the document's natural width and short code
     *  would sit in card stretched kilometers wide. Capped, the card
     *  keeps the width it asked for. */
    @Override public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    /** The reading surface's width. Wrapped, the text tracks its scroll
     *  pane's viewport, so the host's own width is that width; unwrapped,
     *  the host grows to the whole document's natural width — however
     *  wide the longest line anywhere ran — and sizing cards to the host
     *  would stretch every one of them to the document's width, acres of
     *  empty card around short code. The viewport stays the pane's width
     *  however wide the document runs: that is the span a full-width
     *  card means, and a card still exceeds it when its longest line
     *  does — the pane scrolls sideways for it, the editor's way. */
    private int hostWidth() {
        javax.swing.text.JTextComponent host = (javax.swing.text.JTextComponent)
                SwingUtilities.getAncestorOfClass(javax.swing.text.JTextComponent.class, this);
        if (host == null) return MIN_WIDTH;
        int w = host.getParent() instanceof javax.swing.JViewport vp
                ? vp.getWidth() : host.getWidth();
        Insets m = host.getMargin();
        return w - m.left - m.right;
    }

    /** Height of the code wrapped to the given inner width — the card
     *  assigns the wrap width, the area answers with its row count. */
    private int wrappedHeight(int inner) {
        code.setWrapWidth(inner);
        return code.getPreferredSize().height;
    }

    /** The gutter's width: the widest line number plus its gap to the code. */
    private int gutterWidth() {
        java.awt.FontMetrics fm = code.getFontMetrics(code.getFont());
        int digits = String.valueOf(code.lineCount()).length();
        return fm.stringWidth("9".repeat(digits)) + GUTTER_GAP;
    }

    /** Line numbers down the card's left edge, in the code's own mono face
     *  at the muted color — the reader's ruler. */
    private final class LineGutter extends javax.swing.JComponent {
        LineGutter() { setOpaque(false); }

        @Override public Dimension getPreferredSize() {
            return new Dimension(gutterWidth(), code.getPreferredSize().height);
        }

        @Override public Dimension getMinimumSize() {
            return new Dimension(gutterWidth(), 16);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(code.getFont());
            g2.setColor(muted);
            java.awt.FontMetrics fm = g2.getFontMetrics();
            int right = fm.stringWidth("9".repeat(
                    String.valueOf(code.lineCount()).length()));
            for (int i = 0; i < code.lineCount(); i++) {
                int y = code.lineBaselineY(i);
                if (y < 0) continue;
                String n = String.valueOf(i + 1);
                g2.drawString(n, right - fm.stringWidth(n), y);
            }
            g2.dispose();
        }
    }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        RoundRectangle2D r = new RoundRectangle2D.Float(
                0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, ARC, ARC);
        g2.setColor(fill);
        g2.fill(r);
        g2.setColor(border);
        g2.draw(r);
        g2.dispose();
    }
}
