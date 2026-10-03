package dock.markdown;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JCheckBox;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.CustomNode;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

/**
 * Turns markdown source into a {@link StyledDocument} for the viewer's
 * text pane: an AST walk that maps blocks to paragraph attributes
 * (indents, spacing, background chips) and inline marks to character
 * attributes (fonts, colors, strikethrough, link targets). Building a
 * document is pure computation over an {@link MdTheme} snapshot, so it
 * runs on the panel's worker thread and is installed on the EDT only
 * when finished.
 *
 * <p>Tables, thematic breaks, and code blocks are embedded as
 * components inside the text; links carry their target under
 * {@link #HREF} for the panel's click resolution. Raw HTML is shown
 * literally, never interpreted.
 */
final class MarkdownRenderer {

    /** Character-attribute key holding a link's destination — read back
     *  when a click lands inside the run. */
    static final Object HREF = new Object();

    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create(), StrikethroughExtension.create(),
                    TaskListItemsExtension.create(), AutolinkExtension.create()))
            .build();

    /** Per-level indent of list content and blockquote bars, in px. */
    private static final float LIST_INDENT = 18f;
    private static final float QUOTE_INDENT = 16f;
    /** Width the list marker hangs into from the wrapped text. */
    private static final float MARKER_HANG = 20f;

    private MarkdownRenderer() {}

    /** Destinations of the document's inline images, unique, in document
     *  order — what the panel loads before rendering. */
    static List<String> imageTargets(String source) {
        List<String> out = new ArrayList<>();
        try {
            collectImages(PARSER.parse(source), out);
        } catch (RuntimeException e) {
            // Degrades to "no images" — the render's own fallback shows text.
        }
        return out;
    }

    private static void collectImages(Node node, List<String> out) {
        if (node instanceof Image img) {
            String d = img.getDestination();
            if (!d.isEmpty() && !out.contains(d)) out.add(d);
        }
        for (Node c = node.getFirstChild(); c != null; c = c.getNext()) collectImages(c, out);
    }

    /**
     * Decomposes a Font into the attribute form Swing text resolves. The
     * full face name (not the family) is the key: the bundled static
     * weights carry their weight in the name — "Inter Bold" reports
     * {@code isBold() == false} — so family+bold would silently degrade
     * bold runs to regular. Italic stays a flag because it is always
     * synthesized here, never a face of its own.
     */
    private static void applyFont(javax.swing.text.MutableAttributeSet a, Font f) {
        StyleConstants.setFontFamily(a, f.getFontName());
        StyleConstants.setFontSize(a, (int) f.getSize2D());
        StyleConstants.setBold(a, f.isBold());
        StyleConstants.setItalic(a, f.isItalic());
    }

    static StyledDocument render(String source, MdTheme theme) {
        return render(source, theme, java.util.Map.of());
    }

    static StyledDocument render(String source, MdTheme theme,
                                 java.util.Map<String, javax.swing.Icon> images) {
        return render(source, theme, images, java.util.Set.of());
    }

    /** {@code pending} holds image targets whose fetch is still in
     *  flight — they render as a loading chip the panel swaps for the
     *  icon (or the failure chip) when the fetch lands. */
    static StyledDocument render(String source, MdTheme theme,
                                 java.util.Map<String, javax.swing.Icon> images,
                                 java.util.Set<String> pending) {
        Doc d = new Doc(theme, images, pending);
        try {
            PARSER.parse(source).accept(d);
        } catch (RuntimeException e) {
            // A parser bug on hostile input must degrade to the raw text,
            // never kill the viewer.
            d = new Doc(theme, images, pending);
            d.appendRaw(source, theme.mono(), theme.foreground());
        }
        return d.doc;
    }

    /** The source as a plain mono document — the viewer's raw mode. */
    static StyledDocument raw(String source, MdTheme theme) {
        Doc d = new Doc(theme);
        d.appendRaw(source, theme.mono(), theme.foreground());
        return d.doc;
    }

    // ---- the AST walk ----

    private static final class Doc extends AbstractVisitor {
        final DefaultStyledDocument doc = new DefaultStyledDocument();
        private final MdTheme theme;
        private final java.util.Map<String, javax.swing.Icon> images;
        private final java.util.Set<String> pending;

        // Inline style state, composed per run as the walk descends.
        private boolean bold, italic, strike, code;
        private String href;
        /** Non-null while inside a heading — runs must carry the heading
         *  font explicitly or the paragraph default would be shadowed. */
        private Font headingFont;

        // Block context.
        private int listDepth, quoteDepth;
        /** Marker the enclosing list prepared for the next item. */
        private String marker;

        Doc(MdTheme theme) { this(theme, java.util.Map.of(), java.util.Set.of()); }

        Doc(MdTheme theme, java.util.Map<String, javax.swing.Icon> images,
                java.util.Set<String> pending) {
            this.theme = theme;
            this.images = images;
            this.pending = pending;
        }

        // -- blocks --

        @Override public void visit(Heading h) {
            int start = doc.getLength();
            int level = Math.clamp(h.getLevel(), 1, 6);
            headingFont = theme.headings()[level - 1];
            visitChildren(h);
            headingFont = null;
            SimpleAttributeSet p = bodyPara(headingSpacing(level)[1]);
            StyleConstants.setSpaceAbove(p, headingSpacing(level)[0]);
            applyFont(p, theme.headings()[level - 1]);
            StyleConstants.setLineSpacing(p, 0.15f);
            applyContext(p, false);
            closeParagraph(start, p);
        }

        @Override public void visit(Paragraph node) {
            int start = doc.getLength();
            visitChildren(node);
            // A paragraph that is nothing but an image is one tall row;
            // body line spacing would quarter again the image's height
            // below it.
            SimpleAttributeSet p = (node.getFirstChild() instanceof Image
                    && node.getFirstChild().getNext() == null)
                    ? blockPara(listDepth > 0 ? 2f : 8f)
                    : bodyPara(listDepth > 0 ? 2f : 8f);
            applyContext(p, false);
            closeParagraph(start, p);
        }

        @Override public void visit(BlockQuote node) {
            quoteDepth++;
            visitChildren(node);
            quoteDepth--;
        }

        @Override public void visit(BulletList node) {
            int keep = listDepth;
            listDepth++;
            for (Node c = node.getFirstChild(); c != null; ) {
                Node next = c.getNext();
                marker = "•";
                c.accept(this);
                c = next;
            }
            listDepth = keep;
        }

        @Override public void visit(OrderedList node) {
            int keep = listDepth;
            listDepth++;
            int n = node.getMarkerStartNumber();
            for (Node c = node.getFirstChild(); c != null; ) {
                Node next = c.getNext();
                marker = n++ + ".";
                c.accept(this);
                c = next;
            }
            listDepth = keep;
        }

        @Override public void visit(ListItem item) {
            int start = doc.getLength();
            String mark = marker;
            marker = null;
            Node c = item.getFirstChild();
            boolean task = false;
            if (c instanceof TaskListItemMarker m) {
                // A task item carries its own marker — no bullet on top.
                task = true;
                insertComponent(taskBox(m.isChecked(), theme));
                append(" ", null);
                c = c.getNext();
            }
            if (!task) append((mark == null ? "•" : mark) + "  ", theme.muted());
            if (c instanceof Paragraph p) {
                visitChildren(p);
                c = p.getNext();
            }
            SimpleAttributeSet pa = bodyPara(2f);
            applyContext(pa, true);
            closeParagraph(start, pa);
            while (c != null) {
                Node next = c.getNext();
                c.accept(this);
                c = next;
            }
        }

        @Override public void visit(FencedCodeBlock node) { codeBlock(node.getInfo(), node.getLiteral()); }
        @Override public void visit(IndentedCodeBlock node) { codeBlock(null, node.getLiteral()); }

        @Override public void visit(HtmlBlock node) {
            // Never interpreted: raw HTML reads as what it is, source.
            for (String line : lines(node.getLiteral()))
                appendRaw(line, theme.mono(), theme.muted());
            newline();
        }

        @Override public void visit(ThematicBreak node) {
            int start = doc.getLength();
            // A separator wider than any pane: the view clips it to the
            // text width, which is exactly a full-width rule.
            JSeparator sep = new JSeparator(SwingConstants.HORIZONTAL);
            sep.setPreferredSize(new Dimension(4096, 2));
            insertComponent(sep);
            SimpleAttributeSet p = bodyPara(6f);
            StyleConstants.setSpaceAbove(p, 6f);
            StyleConstants.setAlignment(p, StyleConstants.ALIGN_CENTER);
            applyContext(p, false);
            closeParagraph(start, p);
        }

        @Override public void visit(CustomBlock node) {
            if (node instanceof TableBlock) table((TableBlock) node);
            else visitChildren(node);
        }

        @Override public void visit(CustomNode node) {
            if (node instanceof Strikethrough) {
                strike = true;
                visitChildren(node);
                strike = false;
            } else {
                // Task markers are consumed by visit(ListItem); anything
                // unknown renders as its children.
                visitChildren(node);
            }
        }

        // -- inline --

        @Override public void visit(Text node) { append(node.getLiteral(), null); }
        @Override public void visit(Code node) {
            code = true;
            append(node.getLiteral(), null);
            code = false;
        }
        @Override public void visit(Emphasis node) {
            italic = true;
            visitChildren(node);
            italic = false;
        }
        @Override public void visit(StrongEmphasis node) {
            bold = true;
            visitChildren(node);
            bold = false;
        }
        @Override public void visit(Link node) {
            href = node.getDestination();
            visitChildren(node);
            href = null;
        }
        @Override public void visit(Image node) {
            javax.swing.Icon icon = images.get(node.getDestination());
            if (icon != null) {
                SimpleAttributeSet a = new SimpleAttributeSet();
                StyleConstants.setIcon(a, icon);
                tiny(a);
                insert(" ", a);
            } else if (pending.contains(node.getDestination())) {
                insertComponent(new ImageChip(node.getDestination(), theme, true));
            } else {
                insertComponent(new ImageChip(node.getDestination(), theme, false));
            }
        }
        @Override public void visit(SoftLineBreak node) { append(" ", null); }
        // A hard break is a line break inside one paragraph; Swing text
        // can't express that (every newline ends a paragraph), so it
        // degrades to the soft-break space.
        @Override public void visit(HardLineBreak node) { append(" ", null); }
        @Override public void visit(HtmlInline node) {
            appendRaw(node.getLiteral(), theme.mono(), theme.muted());
        }
        @Override public void visit(LinkReferenceDefinition node) { /* nothing to show */ }

        // -- shared machinery --

        private void codeBlock(String info, String literal) {
            // One continuous card, not a paragraph per line — per-line
            // paragraph backgrounds read as striped chips with gaps
            // between them. The card carries the language as its header.
            float left = LIST_INDENT * listDepth + QUOTE_INDENT * quoteDepth;
            float right = quoteDepth > 0 ? QUOTE_INDENT : 0;
            int start = doc.getLength();
            insertComponent(new CodeBlock(info, literal, theme, Math.round(left + right)));
            SimpleAttributeSet p = blockPara(8f);
            StyleConstants.setSpaceAbove(p, 6f);
            if (left > 0) StyleConstants.setLeftIndent(p, left);
            if (right > 0) StyleConstants.setRightIndent(p, right);
            closeParagraph(start, p);
        }

        private void table(TableBlock node) {
            List<List<TableGrid.Cell>> rows = new ArrayList<>();
            for (Node section = node.getFirstChild(); section != null; section = section.getNext()) {
                for (Node r = section.getFirstChild(); r != null; r = r.getNext()) {
                    if (!(r instanceof TableRow)) continue;
                    List<TableGrid.Cell> cells = new ArrayList<>();
                    for (Node cell = r.getFirstChild(); cell != null; cell = cell.getNext()) {
                        if (cell instanceof TableCell tc) {
                            cells.add(new TableGrid.Cell(plainText(tc), tc.isHeader(),
                                    alignmentOf(tc)));
                        }
                    }
                    rows.add(cells);
                }
            }
            if (rows.isEmpty()) return;
            int start = doc.getLength();
            insertComponent(new TableGrid(rows, theme));
            SimpleAttributeSet p = blockPara(8f);
            applyContext(p, false);
            closeParagraph(start, p);
        }

        private static int alignmentOf(TableCell cell) {
            // Columns without colons in the delimiter row carry no
            // alignment — a null enum would explode a switch.
            TableCell.Alignment a = cell.getAlignment();
            return a == TableCell.Alignment.CENTER ? SwingConstants.CENTER
                    : a == TableCell.Alignment.RIGHT ? SwingConstants.RIGHT
                    : SwingConstants.LEFT;
        }

        /** Concatenation of a node's inline content — table cells and
         *  image alts are plain text, not styled runs. */
        private String plainText(Node node) {
            StringBuilder sb = new StringBuilder();
            collectText(node, sb);
            return sb.toString();
        }

        private static void collectText(Node node, StringBuilder sb) {
            if (node instanceof Text t) sb.append(t.getLiteral());
            else if (node instanceof Code c) sb.append(c.getLiteral());
            else if (node instanceof SoftLineBreak) sb.append(' ');
            for (Node c = node.getFirstChild(); c != null; c = c.getNext()) collectText(c, sb);
        }

        private Font runFont() {
            if (code) return bold ? dock.kit.FontRegistry.monoBold(theme.mono().getSize2D())
                    : theme.mono();
            Font f = headingFont != null ? headingFont
                    : bold ? dock.kit.FontRegistry.monoBold(theme.base().getSize2D())
                    : theme.base();
            if (italic) f = f.deriveFont(f.getStyle() | Font.ITALIC);
            return f;
        }

        private void append(String text, Color color) {
            if (text.isEmpty()) return;
            SimpleAttributeSet a = new SimpleAttributeSet();
            applyFont(a, runFont());
            if (strike) StyleConstants.setStrikeThrough(a, true);
            if (code) StyleConstants.setBackground(a, theme.codeBackground());
            // Every run carries its foreground explicitly: an unset one
            // falls back to Color.black at paint time, which reads as
            // black-on-dark in the dark theme. Quote context mutes here
            // because paragraph attributes no longer color the runs.
            if (href != null) {
                StyleConstants.setForeground(a, theme.accent());
                StyleConstants.setUnderline(a, true);
                a.addAttribute(HREF, href);
            } else {
                StyleConstants.setForeground(a, color != null ? color
                        : quoteDepth > 0 ? theme.muted() : theme.foreground());
            }
            insert(text, a);
        }

        /** Unstyled-font run for literals shown verbatim (raw mode, HTML). */
        void appendRaw(String text, Font font, Color color) {
            String[] ls = lines(text);
            for (int i = 0; i < ls.length; i++) {
                SimpleAttributeSet a = new SimpleAttributeSet();
                applyFont(a, font);
                if (color != null) StyleConstants.setForeground(a, color);
                StyleConstants.setLineSpacing(a, 0.1f);
                insert(ls[i], a);
                newline();
                setParagraph(a);
            }
        }

        private void insertComponent(java.awt.Component component) {
            SimpleAttributeSet a = new SimpleAttributeSet();
            StyleConstants.setComponent(a, component);
            tiny(a);
            insert(" ", a);
        }

        /** The glyph a component rides on must not size its row. At the
         *  body metrics the space's line box stacks against the
         *  component's own alignment and inflates the row well past the
         *  component — an empty band above and below every card, table
         *  and chip, growing with the component's height and reading as
         *  padding on it. A two-pixel run leaves the component the
         *  row's only meaningful height. */
        private static void tiny(SimpleAttributeSet a) {
            StyleConstants.setFontSize(a, 2);
        }

        /** A task item's state as the look-and-feel's own checkbox.
         *  Disabled — this is a reader: the box shows the file's state,
         *  it is not a control over it (an enabled one would toggle on
         *  click and then lie about the file).
         *  {@code alignmentY} is set explicitly because the text layout
         *  parks an unset alignment's center on the text baseline,
         *  which leaves the box hanging below the words. */
        private static JCheckBox taskBox(boolean checked, MdTheme theme) {
            JCheckBox box = new JCheckBox();
            box.setSelected(checked);
            box.setEnabled(false);
            box.setFocusable(false);
            box.setOpaque(false);
            int h = box.getPreferredSize().height;
            if (h > 0) {
                // Aim the box's center at the body line's visual middle
                // (halfway between ascent and descent), in baseline
                // fractions — pure font metrics, no screen needed.
                var lm = theme.base().getLineMetrics("Ag",
                        new java.awt.font.FontRenderContext(null, false, false));
                float mid = (lm.getAscent() - lm.getDescent()) / 2f;
                box.setAlignmentY(Math.clamp((h / 2f + mid) / h, 0f, 1f));
            }
            return box;
        }

        private SimpleAttributeSet bodyPara(float spaceBelow) {
            SimpleAttributeSet p = new SimpleAttributeSet();
            StyleConstants.setLineSpacing(p, 0.25f);
            StyleConstants.setSpaceBelow(p, spaceBelow);
            return p;
        }

        /** A paragraph that holds one embedded component — a code card,
         *  a table, a lone image — is a single row with no line rhythm
         *  to keep: the body's line spacing would add a quarter of the
         *  component's height below it (ParagraphView spaces each row
         *  by a factor of the row's own height), padding that grows
         *  with the block and reads as part of it. These paragraphs
         *  carry none. */
        private SimpleAttributeSet blockPara(float spaceBelow) {
            SimpleAttributeSet p = bodyPara(spaceBelow);
            StyleConstants.setLineSpacing(p, 0f);
            return p;
        }

        private static float[] headingSpacing(int level) {
            return level <= 2 ? new float[] {14f, 6f} : new float[] {10f, 3f};
        }

        /** Folds the current block context (list depth, quote depth) into
         *  a paragraph's indents; {@code hang} reserves room for a list
         *  marker on the first line. */
        private void applyContext(SimpleAttributeSet p, boolean hang) {
            float left = LIST_INDENT * listDepth + QUOTE_INDENT * quoteDepth;
            if (hang || listDepth > 0) {
                left += MARKER_HANG;
                StyleConstants.setFirstLineIndent(p, -MARKER_HANG);
            }
            if (left > 0) StyleConstants.setLeftIndent(p, left);
            if (quoteDepth > 0) StyleConstants.setRightIndent(p, QUOTE_INDENT);
        }

        private void closeParagraph(int start, SimpleAttributeSet p) {
            newline();
            doc.setParagraphAttributes(start, doc.getLength() - start, p, true);
        }

        /** The paragraph-closing newline is an invisible glyph, but it
         *  still has a line box: at the document's default metrics it
         *  shares the last row with whatever the paragraph ends in and,
         *  against a component (a code card, a table, an image), the
         *  two alignments stack the row well past the component — an
         *  empty band under every block that reads as padding. A
         *  two-pixel newline leaves text paragraphs untouched (their
         *  rows are sized by the text) and components the row's only
         *  meaningful height. */
        private void newline() {
            SimpleAttributeSet a = new SimpleAttributeSet();
            tiny(a);
            insert("\n", a);
        }

        private void setParagraph(SimpleAttributeSet p) {
            doc.setParagraphAttributes(Math.max(0, doc.getLength() - 1), 1, p, true);
        }

        private void insert(String text, SimpleAttributeSet a) {
            try {
                doc.insertString(doc.getLength(), text, a);
            } catch (BadLocationException e) {
                throw new IllegalStateException("append at doc end", e);
            }
        }

        private static String[] lines(String text) {
            String[] ls = text.replace("\t", "    ").split("\n", -1);
            return ls.length > 1 && ls[ls.length - 1].isEmpty()
                    ? java.util.Arrays.copyOf(ls, ls.length - 1) : ls;
        }
    }
}
