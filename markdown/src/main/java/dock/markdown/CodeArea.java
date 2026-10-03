package dock.markdown;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import javax.swing.AbstractAction;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import dock.syntax.SyntaxPalette;
import dock.syntax.SyntaxSpan;

/**
 * The code card's text surface, painted by hand. A real text component
 * embedded in the document brings its whole machinery — TextUI, caret,
 * the Laf's focus chrome — and that machinery destabilizes the
 * surrounding component views: a focus event re-floods the pane's
 * repaint and the code blanks until the next interaction. This plain
 * component owns its glyphs, its wrapping and its selection outright,
 * so nothing it does can move the document's layout, while the reader
 * still gets text behavior: drag selects, double-click takes a word,
 * ctrl+A takes all, ctrl+C copies (the selection, or everything when
 * nothing is selected), escape hands focus back to the document.
 * Syntax spans tint the runs per segment — unspanned glyphs, and any
 * kind the palette has no color for, stay the foreground ink.
 */
final class CodeArea extends JComponent {

    /** Text inset from the card's inner edge. */
    private static final int PAD_X = 8;
    private static final int PAD_Y = 6;
    /** A tab never reaches us (the card expands them), but painting must
     *  stay honest if one slips through. */
    private static final int TAB_COLS = 4;

    private final String text;
    private final Color ink;
    private final java.util.List<SyntaxSpan> spans;
    private final SyntaxPalette palette;
    private final Color selectionFill;
    private final java.util.function.Consumer<String> copier;

    /** Selection as text offsets, the anchor being where the press
     *  started; empty when anchor == caret. */
    private int anchor;
    private int caret;

    private int wrapWidth;
    /** Layout cache — pure data, valid without ever being shown, keyed on
     *  the font and the wrap width. */
    private Font layoutFont;
    private int laidOutWidth = -1;
    private FontMetrics fm;
    private String[] lines;
    private int[] lineOffsets;
    private int[] rowOfLine;
    private int[] rowLine, rowStart, rowEnd;
    private int totalRows;

    CodeArea(String text, Font font, Color ink, java.util.List<SyntaxSpan> spans,
             SyntaxPalette palette, Color accent,
             java.util.function.Consumer<String> copier) {
        this.text = text;
        this.ink = ink;
        this.spans = java.util.List.copyOf(spans);
        this.palette = palette;
        // A wash of the theme accent rather than the Laf's selection pair:
        // the theme record is the only palette the renderer may read, and a
        // translucent fill keeps the normal ink readable in both themes.
        this.selectionFill = new Color(accent.getRed(), accent.getGreen(),
                accent.getBlue(), 64);
        this.copier = copier;
        setFont(font);
        setOpaque(false);
        setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
        mouseSelects();
        bindKeys();
    }

    /** The code verbatim — what the copy paths hand out. */
    String text() { return text; }

    // ---- for tests (same package) ----

    java.util.List<SyntaxSpan> spans() { return spans; }

    SyntaxPalette palette() { return palette; }

    /** The logical lines the gutter numbers. */
    int lineCount() {
        ensureLayout();
        return lines.length;
    }

    /** The rows the text occupies — more than the lines once a line
     *  wraps, equal to them with wrap off. */
    int rowCount() {
        ensureLayout();
        return totalRows;
    }

    /** The width the text wraps at — the card assigns it before asking
     *  for its height, the standard size-then-ask handshake. Zero or
     *  less means no wrap: every logical line stays one row and the
     *  surface takes its natural width. */
    void setWrapWidth(int width) {
        if (width == wrapWidth) return;
        wrapWidth = width;
        revalidate();
        repaint();
    }

    /** Baseline of a logical line's number-bearing first row; −1 for a
     *  line that doesn't exist. */
    int lineBaselineY(int line) {
        ensureLayout();
        if (line < 0 || line >= lines.length) return -1;
        return PAD_Y + rowOfLine[line] * fm.getHeight() + fm.getAscent();
    }

    /** Selects {@code [start, end)} like a text component would. */
    void select(int start, int end) {
        anchor = clamp(start);
        caret = clamp(end);
        repaint();
    }

    /** The selection's text, or null when empty — text-component semantics. */
    String getSelectedText() {
        int a = Math.min(anchor, caret), b = Math.max(anchor, caret);
        return a < b ? text.substring(a, b) : null;
    }

    // ---- selection, driven by the mouse ----

    /** A press at an offset — where a drag anchors. */
    void beginSelection(int offset) {
        anchor = caret = clamp(offset);
        repaint();
    }

    /** A drag to an offset — extends from the anchor. */
    void extendSelection(int offset) {
        int at = clamp(offset);
        if (at == caret) return;
        caret = at;
        repaint();
    }

    /** Text offset under a point, clamped to the nearest row's span. */
    int offsetAt(int x, int y) {
        ensureLayout();
        int lh = fm.getHeight();
        int row = Math.max(0, Math.min((y - PAD_Y) / lh, totalRows - 1));
        String s = lines[rowLine[row]];
        int from = rowStart[row];
        int w = 0;
        while (from < rowEnd[row] && w < x - PAD_X) w += charW(s.charAt(from++));
        return lineOffsets[rowLine[row]] + from;
    }

    // ---- size ----

    @Override public Dimension getPreferredSize() {
        ensureLayout();
        int w = wrapWidth > 0 ? Math.max(wrapWidth, 80)
                : Math.max(longestLineWidth() + PAD_X * 2, 80);
        return new Dimension(w, PAD_Y * 2 + totalRows * fm.getHeight());
    }

    /** The widest logical line — the surface's natural span when the
     *  reader turns word wrap off. */
    private int longestLineWidth() {
        int w = 0;
        for (String s : lines) w = Math.max(w, widthOf(s, 0, s.length()));
        return w;
    }

    @Override public Dimension getMinimumSize() {
        ensureLayout();
        return new Dimension(40, PAD_Y * 2 + fm.getHeight());
    }

    // ---- painting ----

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        ensureLayout();
        g2.setFont(getFont());
        int lh = fm.getHeight();
        int selA = Math.min(anchor, caret), selB = Math.max(anchor, caret);
        int spanCursor = 0;
        for (int row = 0; row < totalRows; row++) {
            String s = lines[rowLine[row]];
            int rowFrom = rowStart[row];
            int lineAt = lineOffsets[rowLine[row]];
            int gs = lineAt + rowFrom;
            int ge = gs + (rowEnd[row] - rowFrom);
            int x = PAD_X, y = PAD_Y + row * lh;
            int a = Math.max(selA, gs), b = Math.min(selB, ge);
            if (a < b) {
                int xa = x + widthOf(s, rowFrom, rowFrom + (a - gs));
                g2.setColor(selectionFill);
                g2.fillRect(xa, y, Math.max(widthOf(s, rowFrom + (a - gs),
                        rowFrom + (b - gs)), 2), lh);
            }
            if (spans.isEmpty()) {
                g2.setColor(ink);
                g2.drawString(s.substring(rowFrom, rowEnd[row]), x, y + fm.getAscent());
            } else {
                spanCursor = paintRow(g2, s, rowFrom, rowEnd[row], lineAt,
                        spanCursor, x, y);
            }
        }
        if (hasFocus()) {
            int[] at = caretXY();
            g2.setColor(ink);
            g2.fillRect(at[0], at[1], 2, lh);
        }
        g2.dispose();
    }

    /** One row painted as colored segments: spans (text offsets) clip to
     *  the row's range within its line, the gaps between them paint in
     *  the plain ink. The cursor skips spans that ended before this row
     *  and is handed back — rows paint in order, so it only moves
     *  forward, and a span crossing several rows is re-visited by each
     *  until it ends. The consumption test reads the span's own end,
     *  unclamped by the row: an empty row clamps every end to its start
     *  and would swallow the rest of the list (the bug this pins down).
     *  Returns the index the next row resumes from. */
    private int paintRow(Graphics2D g2, String s, int rowFrom, int rowTo,
                         int lineAt, int cursor, int x, int y) {
        int segStart = rowFrom;
        for (int i = cursor; i < spans.size(); i++) {
            SyntaxSpan span = spans.get(i);
            int start = span.start() - lineAt, end = span.end() - lineAt;
            if (end <= rowFrom) {
                cursor = i + 1;   // ended before this row: never needed again
                continue;
            }
            if (start >= rowTo) break;
            int a = Math.max(start, rowFrom), b = Math.min(end, rowTo);
            if (a > segStart) x = drawSeg(g2, s, segStart, a, x, y, ink);
            Color c = palette.of(span.kind());
            x = drawSeg(g2, s, a, b, x, y, c != null ? c : ink);
            segStart = b;
        }
        if (segStart < rowTo) drawSeg(g2, s, segStart, rowTo, x, y, ink);
        return cursor;
    }

    private int drawSeg(Graphics2D g2, String s, int from, int to,
                        int x, int y, Color c) {
        g2.setColor(c);
        g2.drawString(s.substring(from, to), x, y + fm.getAscent());
        return x + widthOf(s, from, to);
    }

    /** The caret's (x, y) — the focused reader's insertion hint. */
    private int[] caretXY() {
        int line = lineOf(caret);
        String s = lines[line];
        int local = Math.min(Math.max(caret - lineOffsets[line], 0), s.length());
        int row = rowOfLine[line];
        while (row + 1 < totalRows && rowLine[row + 1] == line
                && local >= rowEnd[row]) row++;
        int x = PAD_X + widthOf(s, rowStart[row], Math.min(local, rowEnd[row]));
        return new int[] {x, PAD_Y + row * fm.getHeight()};
    }

    // ---- layout: wrapping as pure data ----

    private void ensureLayout() {
        Font f = getFont();
        if (f.equals(layoutFont) && lines != null && laidOutWidth == wrapWidth) return;
        layoutFont = f;
        laidOutWidth = wrapWidth;
        fm = getFontMetrics(f);
        splitLines();
        int width = Math.max(wrapWidth, 0);
        int[] rl = new int[lines.length * 2];   // rows per line are few;
        int[] rs = new int[lines.length * 2];   // grown on demand below
        int[] re = new int[lines.length * 2];
        int rows = 0;
        rowOfLine = new int[lines.length];
        for (int i = 0; i < lines.length; i++) {
            rowOfLine[i] = rows;
            int start = 0;
            String s = lines[i];
            while (true) {
                int end = width <= 0 ? s.length() : wrapPoint(s, start, width);
                if (rows == rl.length) {
                    rl = java.util.Arrays.copyOf(rl, rl.length * 2);
                    rs = java.util.Arrays.copyOf(rs, rs.length * 2);
                    re = java.util.Arrays.copyOf(re, re.length * 2);
                }
                rl[rows] = i;
                rs[rows] = start;
                re[rows] = end;
                rows++;
                if (end >= s.length()) break;
                start = end;
            }
        }
        rowLine = java.util.Arrays.copyOf(rl, rows);
        rowStart = java.util.Arrays.copyOf(rs, rows);
        rowEnd = java.util.Arrays.copyOf(re, rows);
        totalRows = rows;
    }

    /** Split into logical lines the way the gutter counts them: a
     *  trailing newline ends the last line rather than opening an empty
     *  one. */
    private void splitLines() {
        String[] all = text.split("\n", -1);
        int count = all.length;
        if (count > 1 && all[count - 1].isEmpty()) count--;
        lines = java.util.Arrays.copyOf(all, count);
        lineOffsets = new int[count];
        int at = 0;
        for (int i = 0; i < count; i++) {
            lineOffsets[i] = at;
            at += lines[i].length() + 1;
        }
    }

    /** End of the row that starts at {@code start}: the whole rest when it
     *  fits, else just past the last space that fits, else as far as fits
     *  (a word longer than the row breaks where it must). */
    private int wrapPoint(String s, int start, int width) {
        int end = start;
        int afterSpace = -1;
        while (end < s.length()) {
            if (widthOf(s, start, end + 1) > width) break;
            end++;
            if (s.charAt(end - 1) == ' ') afterSpace = end;
        }
        if (end >= s.length()) return end;
        if (afterSpace > start) return afterSpace;
        if (end > start) return end;
        return start + 1;   // one glyph wider than the row: still progress
    }

    private int lineOf(int offset) {
        int line = 0;
        while (line + 1 < lines.length && lineOffsets[line + 1] <= offset) line++;
        return line;
    }

    private int clamp(int offset) {
        return Math.max(0, Math.min(offset, text.length()));
    }

    private int charW(char c) {
        return c == '\t' ? TAB_COLS * fm.charWidth(' ') : fm.charWidth(c);
    }

    private int widthOf(String s, int from, int to) {
        int w = 0;
        for (int i = from; i < to; i++) w += charW(s.charAt(i));
        return w;
    }

    // ---- input ----

    private void mouseSelects() {
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                int at = offsetAt(e.getX(), e.getY());
                if (e.getClickCount() >= 3) {
                    select(0, text.length());
                } else if (e.getClickCount() == 2) {
                    selectWordAt(at);
                } else {
                    beginSelection(at);
                }
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                extendSelection(offsetAt(e.getX(), e.getY()));
            }
        });
        addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent e) {
                repaint();
            }

            @Override public void focusLost(java.awt.event.FocusEvent e) {
                repaint();
            }
        });
    }

    /** The word (identifier-shaped) around an offset. */
    private void selectWordAt(int at) {
        int a = at, b = at;
        while (a > 0 && wordChar(text.charAt(a - 1))) a--;
        while (b < text.length() && wordChar(text.charAt(b))) b++;
        select(a, b);
    }

    private static boolean wordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** While the code area holds focus the viewer's keys (escape closes,
     *  arrows walk files) are inert — escape inside the card hands focus
     *  straight back to the document so the close convention survives.
     *  Ctrl+A/ctrl+C select and copy the way any text surface would. */
    private void bindKeys() {
        InputMap in = getInputMap(WHEN_FOCUSED);
        var actions = getActionMap();
        in.put(KeyStroke.getKeyStroke("ESCAPE"), "dockBackToDocument");
        actions.put("dockBackToDocument", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                java.awt.Component host = SwingUtilities.getAncestorOfClass(
                        javax.swing.text.JTextComponent.class, CodeArea.this);
                if (host != null) host.requestFocusInWindow();
            }
        });
        in.put(KeyStroke.getKeyStroke("ctrl A"), "dockSelectAll");
        actions.put("dockSelectAll", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                select(0, text.length());
            }
        });
        Runnable copy = this::copy;
        in.put(KeyStroke.getKeyStroke("ctrl C"), "dockCopy");
        in.put(KeyStroke.getKeyStroke("ctrl INSERT"), "dockCopy");
        actions.put("dockCopy", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                copy.run();
            }
        });
    }

    /** Copies the selection — or the whole card when nothing is selected,
     *  the reader-friendly reading of ctrl+C on a snippet. */
    private void copy() {
        String sel = getSelectedText();
        copier.accept(sel != null ? sel : text);
    }
}
