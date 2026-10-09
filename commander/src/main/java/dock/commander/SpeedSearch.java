package dock.commander;

import dock.kit.FontRegistry;
import dock.kit.Glyphs;
import dock.kit.Tokens;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.RenderingHints;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.UIManager;

/**
 * IntelliJ-style speed search for a file table: typing while the table has
 * focus starts an incremental match that jumps the selection row by row.
 * Names that <em>start</em> with the query always outrank names that merely
 * contain it; a query like "md" only lands on gamma.md when nothing begins
 * with "md". Backspace edits the query, Escape reverts to where the search
 * started, Enter commits and performs the normal open action. Ctrl+F
 * (Cmd+F on a Mac) opens an empty search for those who look for a key
 * rather than just typing. While a search runs, every row's matching
 * letters are highlighted (the browser's find-in-page cue), the badge
 * — centered over the top of the list — counts the matching rows, and the
 * Up/Down arrows hop between the matches (wrapping) instead of walking
 * every row, the search staying open.
 *
 * <p>Printable characters have no InputMap entries anywhere, so they are
 * intercepted in the table's {@code processKeyEvent}. Backspace / Enter /
 * Escape <em>are</em> bound (Backspace to parent navigation), and Swing
 * dispatches bound keystrokes before the component sees them — so those three
 * are routed through the pane's bindings and call the same handlers here
 * ({@link #backspace}, {@link #commit}, {@link #escape}); the processKeyEvent
 * path is the second, order-agnostic line of defense.
 */
final class SpeedSearch {

    private static final Color NO_MATCH = new Color(0xE5484D);

    /** Table client property: the text every name cell highlights, or null. */
    static final String NEEDLE = "dock.speedSearch.needle";

    private JTable table;
    private JComponent host;
    private Runnable open;

    private final StringBuilder query = new StringBuilder();
    private boolean active;
    private boolean noMatch;
    private int[] preSearchRows = new int[0];

    /** Wires the counterparties; called once from the pane constructor. */
    void attach(JTable table, JComponent host, Runnable open) {
        this.table = table;
        this.host = host;
        this.open = open;
    }

    boolean active() { return active; }
    String query() { return query.toString(); }
    boolean noMatch() { return noMatch; }

    /**
     * @return true when the event was consumed and must not reach the
     *         table's default handling.
     */
    boolean process(KeyEvent e) {
        if (e.getID() == KeyEvent.KEY_TYPED) {
            char c = e.getKeyChar();
            if (isSearchable(e, c)) {
                type(c);
                e.consume();
                return true;
            }
            return false;
        }
        if (e.getID() == KeyEvent.KEY_PRESSED && active) {
            boolean plain = (e.getModifiersEx() & (KeyEvent.SHIFT_DOWN_MASK
                    | KeyEvent.CTRL_DOWN_MASK | KeyEvent.ALT_DOWN_MASK
                    | KeyEvent.META_DOWN_MASK)) == 0;
            switch (e.getKeyCode()) {
                case KeyEvent.VK_BACK_SPACE -> { backspace(null); e.consume(); return true; }
                case KeyEvent.VK_ESCAPE -> { escape(); e.consume(); return true; }
                case KeyEvent.VK_ENTER -> { commit(); e.consume(); return true; }
                case KeyEvent.VK_UP, KeyEvent.VK_KP_UP -> {
                    if (plain && step(-1)) { e.consume(); return true; }
                }
                case KeyEvent.VK_DOWN, KeyEvent.VK_KP_DOWN -> {
                    if (plain && step(1)) { e.consume(); return true; }
                }
                default -> { }
            }
        }
        return false;
    }

    /** Ctrl/Alt/Meta combos are shortcuts; plain characters start a search. */
    private static boolean isSearchable(KeyEvent e, char c) {
        if (c < ' ' || c == 0x7F || c == KeyEvent.CHAR_UNDEFINED) return false;
        int mask = KeyEvent.CTRL_DOWN_MASK | KeyEvent.ALT_DOWN_MASK | KeyEvent.META_DOWN_MASK;
        return (e.getModifiersEx() & mask) == 0;
    }

    private void type(char c) {
        if (!active) {
            active = true;
            noMatch = false;
            preSearchRows = table.getSelectedRows().clone();
        }
        query.append(c);
        // "dd" cycles through the d-items instead of matching "dd*" —
        // the classic tree speed-search convention.
        update(isSingleCharRun(query));
        changed();
    }

    /**
     * Backspace edits the query while a search is active; with no active
     * search the fallback (parent navigation) runs.
     */
    void backspace(Runnable fallback) {
        if (!active) {
            if (fallback != null) fallback.run();
            return;
        }
        if (query.length() > 0) query.setLength(query.length() - 1);
        if (query.length() == 0) {
            active = false;
            noMatch = false;
        } else {
            update(false);
        }
        changed();
    }

    /**
     * Opens an empty search (the hotkey path): the badge shows and waits for
     * typing; Backspace or Escape closes it again. No-op while one runs.
     */
    void start() {
        if (active) return;
        active = true;
        noMatch = false;
        preSearchRows = table.getSelectedRows().clone();
        changed();
    }

    /**
     * The arrows while a query runs: selects the next ({@code dir} 1) or
     * previous (-1) matching row, wrapping around the listing; the search
     * stays open. False without a query — the arrows then move as usual.
     */
    boolean step(int dir) {
        String needle = needle();
        if (needle == null) return false;
        int n = table.getRowCount();
        int cur = table.getSelectedRow();
        int base = cur >= 0 ? cur : dir > 0 ? -1 : n;
        FileTableModel model = (FileTableModel) table.getModel();
        for (int i = 1; i <= n; i++) {
            int r = Math.floorMod(base + dir * i, n);
            if (model.row(r).equals(dock.core.fs.FileEntry.PARENT)) continue;
            if (matchIndex(r, needle) >= 0) {
                table.setRowSelectionInterval(r, r);
                table.scrollRectToVisible(table.getCellRect(r, 0, true));
                noMatch = false;
                changed();
                return true;
            }
        }
        noMatch = true;   // nothing to hop to: stay put, still searching
        changed();
        return true;
    }

    /** Escape reverts the selection to where the search started. */
    void escape() {
        if (!active) return;
        active = false;
        query.setLength(0);
        noMatch = false;
        table.clearSelection();
        for (int r : preSearchRows) {
            if (r < table.getRowCount()) table.addRowSelectionInterval(r, r);
        }
        if (preSearchRows.length > 0 && preSearchRows[0] < table.getRowCount()) {
            table.scrollRectToVisible(table.getCellRect(preSearchRows[0], 0, true));
        }
        changed();
    }

    /** Enter ends the search and performs the normal open action. */
    void commit() {
        if (!active) {
            open.run();
            return;
        }
        active = false;
        query.setLength(0);
        noMatch = false;
        changed();
        open.run();
    }

    /** A new listing invalidates the query (navigation, refresh, re-sort). */
    void reset() {
        if (!active && query.length() == 0) return;
        active = false;
        query.setLength(0);
        noMatch = false;
        changed();
    }

    private void update(boolean advance) {
        int n = table.getRowCount();
        if (n == 0) {
            noMatch = true;
            return;
        }
        String q = query.toString();
        String needle = advance ? q.substring(0, 1) : q;
        int cur = table.getSelectedRow();
        if (!advance && cur >= 0 && matchIndex(cur, needle) >= 0) {
            noMatch = false; // narrowing kept the current row valid
            return;
        }
        int from = cur < 0 ? 0 : (advance ? cur + 1 : cur);
        // Prefix hits outrank in-name hits wherever they sit in the listing;
        // each pass scans the full cycle, the second only runs when the
        // first found nothing.
        int hit = findMatch(needle, from, true);
        if (hit < 0) hit = findMatch(needle, from, false);
        noMatch = hit < 0;
        if (hit >= 0) {
            table.setRowSelectionInterval(hit, hit);
            table.scrollRectToVisible(table.getCellRect(hit, 0, true));
        }
    }

    /** Cyclic scan from {@code from}; {@code prefixPass} picks the tier. */
    private int findMatch(String needle, int from, boolean prefixPass) {
        int n = table.getRowCount();
        for (int i = 0; i < n; i++) {
            int r = (from + i) % n;
            int at = matchIndex(r, needle);
            if (prefixPass ? at == 0 : at > 0) return r;
        }
        return -1;
    }

    /**
     * Case-insensitive match position in a row's name: 0 = the needle is a
     * prefix, &gt;0 = the needle occurs inside the name at that index,
     * -1 = no match.
     */
    private int matchIndex(int row, String needle) {
        if (needle.isEmpty()) return 0;
        FileTableModel model = (FileTableModel) table.getModel();
        if (row < 0 || row >= model.getRowCount()) return -1;
        return matchAt(model.row(row).name(), needle);
    }

    /** First case-insensitive occurrence of {@code needle} in {@code name}, or -1. */
    static int matchAt(String name, String needle) {
        int limit = name.length() - needle.length();
        for (int i = 0; i <= limit; i++) {
            if (name.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
    }

    /** What the rows match against right now: the query, or its first
     *  letter while a repeated letter cycles; null without a query. */
    String needle() {
        if (!active || query.isEmpty()) return null;
        return isSingleCharRun(query) ? query.substring(0, 1) : query.toString();
    }

    /** Rows whose name holds the needle (the ".." shortcut never counts). */
    int matchCount() {
        String needle = needle();
        if (needle == null) return 0;
        FileTableModel model = (FileTableModel) table.getModel();
        int n = 0;
        for (int r = 0; r < model.getRowCount(); r++) {
            if (!model.row(r).equals(dock.core.fs.FileEntry.PARENT)
                    && matchIndex(r, needle) >= 0) n++;
        }
        return n;
    }

    private static boolean isSingleCharRun(CharSequence s) {
        if (s.length() < 2) return false;
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) != s.charAt(0)) return false;
        }
        return true;
    }

    /** Incremented by every {@link #changed()} call; tests observe the
     *  viewport listener through it. */
    int changedCount;

    void changed() {
        changedCount++;
        // The name cells read the needle at render time; the host repaint
        // covers the table beneath it.
        if (table != null) table.putClientProperty(NEEDLE, needle());
        if (host != null) host.repaint();
    }

    // ---- badge ----

    /** Where the badge sits over {@code area} (the list's viewport):
     *  centered across, just below the column headers. */
    Rectangle badgeBounds(Rectangle area) {
        if (!active || host == null) return null;
        FontMetrics fm = host.getFontMetrics(FontRegistry.mono(Tokens.ICON_SMALL));
        FontMetrics small = host.getFontMetrics(FontRegistry.ui(Tokens.ICON_SMALL));
        int w = PAD_X + 12 + GAP + fm.stringWidth(queryText())
                + GAP * 2 + small.stringWidth(countText()) + PAD_X;
        w = Math.min(Math.max(w, 120), Math.max(120, area.width - 2 * Tokens.GAP_2));
        return new Rectangle(area.x + (area.width - w) / 2, area.y + Tokens.GAP_2,
                w, BADGE_H);
    }

    private static final int PAD_X = 10;
    private static final int GAP = 6;
    private static final int BADGE_H = 26;

    private String queryText() {
        return query.isEmpty() ? "Type to search" : query.toString();
    }

    /** "2/5" while the selection sits on a match (the arrows' position),
     *  else how many rows match. */
    String countText() {
        if (query.isEmpty()) return "";
        int n = matchCount();
        if (n == 0) return "no matches";
        int at = matchPosition();
        if (at > 0) return at + "/" + n;
        return n == 1 ? "1 match" : n + " matches";
    }

    /** 1-based place of the selected row among the matches; 0 when the
     *  selection is not on one. */
    private int matchPosition() {
        String needle = needle();
        int sel = table.getSelectedRow();
        if (needle == null || sel < 0) return 0;
        FileTableModel model = (FileTableModel) table.getModel();
        if (model.row(sel).equals(dock.core.fs.FileEntry.PARENT)
                || matchIndex(sel, needle) < 0) return 0;
        int at = 0;
        for (int r = 0; r <= sel; r++) {
            if (!model.row(r).equals(dock.core.fs.FileEntry.PARENT)
                    && matchIndex(r, needle) >= 0) at++;
        }
        return at;
    }

    /** Floating query badge over the top of the file list. */
    void paintBadge(Graphics2D g, Rectangle area) {
        Rectangle b = badgeBounds(area);
        if (b == null) return;
        Font font = FontRegistry.mono(Tokens.ICON_SMALL);
        FontMetrics fm = host.getFontMetrics(font);
        Font smallFont = FontRegistry.ui(Tokens.ICON_SMALL);
        FontMetrics small = host.getFontMetrics(smallFont);
        Icon icon = Glyphs.icon(Glyphs.SEARCH, 12, FileTableModel::muted);
        String text = queryText();
        String count = countText();
        int h = b.height;

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color bg = UIManager.getColor("Dock.tileBackground");
        if (bg == null) bg = UIManager.getColor("Panel.background");
        Color border = UIManager.getColor("Component.borderColor");
        g.setColor(bg);
        g.fillRoundRect(b.x, b.y, b.width, b.height, Tokens.ARC, Tokens.ARC);
        if (border != null) {
            g.setColor(border);
            g.drawRoundRect(b.x, b.y, b.width - 1, b.height - 1, Tokens.ARC, Tokens.ARC);
        }
        java.awt.Shape clip = g.getClip();
        g.clipRect(b.x, b.y, b.width - PAD_X / 2, b.height);
        icon.paintIcon(host, g, b.x + PAD_X, b.y + (h - icon.getIconHeight()) / 2);
        g.setFont(font);
        g.setColor(query.isEmpty() ? FileTableModel.muted()
                : noMatch ? NO_MATCH : UIManager.getColor("Label.foreground"));
        int tx = b.x + PAD_X + icon.getIconWidth() + GAP;
        g.drawString(text, tx, b.y + (h - fm.getHeight()) / 2 + fm.getAscent());
        if (!count.isEmpty()) {
            g.setFont(smallFont);
            g.setColor(noMatch ? NO_MATCH : FileTableModel.muted());
            g.drawString(count, b.x + b.width - PAD_X - small.stringWidth(count),
                    b.y + (h - small.getHeight()) / 2 + small.getAscent());
        }
        g.setClip(clip);
    }
}
