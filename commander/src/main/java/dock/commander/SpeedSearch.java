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
 * started, Enter commits and performs the normal open action.
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
            switch (e.getKeyCode()) {
                case KeyEvent.VK_BACK_SPACE -> { backspace(null); e.consume(); return true; }
                case KeyEvent.VK_ESCAPE -> { escape(); e.consume(); return true; }
                case KeyEvent.VK_ENTER -> { commit(); e.consume(); return true; }
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
        String name = model.row(row).name();
        int limit = name.length() - needle.length();
        for (int i = 0; i <= limit; i++) {
            if (name.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
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
        if (host != null) host.repaint();
    }

    // ---- badge ----

    /** Floating query badge, bottom-left of the file area (IntelliJ-style). */
    void paintBadge(Graphics2D g, Rectangle area) {
        if (!active || host == null) return;
        Font font = FontRegistry.mono(Tokens.ICON_SMALL);
        FontMetrics fm = host.getFontMetrics(font);
        Icon icon = Glyphs.icon(Glyphs.SEARCH, 12, FileTableModel::muted);
        String text = query.toString();

        int padX = 10;
        int gap = 6;
        int h = 26;
        int w = padX + icon.getIconWidth() + gap + fm.stringWidth(text) + padX;
        Rectangle b = new Rectangle(area.x + Tokens.GAP_2,
                area.y + area.height - h - Tokens.GAP_2, Math.max(w, 44), h);

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
        icon.paintIcon(host, g, b.x + padX, b.y + (h - icon.getIconHeight()) / 2);
        g.setFont(font);
        g.setColor(noMatch ? NO_MATCH : UIManager.getColor("Label.foreground"));
        int tx = b.x + padX + icon.getIconWidth() + gap;
        g.drawString(text, tx, b.y + (h - fm.getHeight()) / 2 + fm.getAscent());
    }
}
