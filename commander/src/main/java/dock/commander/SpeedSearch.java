package dock.commander;

import dock.core.fs.FileEntry;
import dock.kit.FontRegistry;
import dock.kit.Glyphs;
import dock.kit.Tokens;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;

/**
 * IntelliJ-style speed search for a file table: typing while the table has
 * focus starts an incremental match that jumps the selection row by row.
 * Names that <em>start</em> with the query always outrank names that merely
 * contain it; a query like "md" only lands on gamma.md when nothing begins
 * with "md". Backspace edits the query, Escape reverts to where the search
 * started, Enter commits and performs the normal open action. Ctrl+F
 * (Cmd+F on a Mac) opens the search with the caret in its box, for those
 * who look for a key rather than just typing. While a search runs, every
 * row's matching letters are highlighted (the browser's find-in-page cue),
 * the box — centered over the top of the list — counts the matching rows,
 * and the Up/Down arrows hop between the matches (wrapping) instead of
 * walking every row, the search staying open.
 *
 * <p>The box is a real text field: a click puts the caret in it, and
 * paste, selection and caret keys work as in any field. Typing into the
 * table never moves the focus — the characters land at the end of the box
 * while the table keeps every shortcut. Inside the focused box, Enter,
 * Escape and the arrows still drive the search, and Backspace on an empty
 * box closes it.
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

    /** The box's width while the list leaves room for it. */
    static final int BOX_WIDTH = 360;
    private static final int BOX_HEIGHT = 30;

    private JTable table;
    private JComponent host;
    private Runnable open;

    private final JTextField field = new JTextField();
    private final JLabel count = new JLabel();
    private final Box box = new Box();
    private boolean active;
    private boolean noMatch;
    private int[] preSearchRows = new int[0];
    /** The query's length at the last edit — a growing run of one letter
     *  cycles; a shrinking one narrows back. */
    private int lastLength;

    SpeedSearch() {
        field.setFont(FontRegistry.mono(Tokens.ICON_SMALL + 1));
        field.setBorder(BorderFactory.createEmptyBorder());
        field.setOpaque(false);
        field.putClientProperty("JTextField.placeholderText", "Type to search");
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { edited(); }
            @Override public void removeUpdate(DocumentEvent e) { edited(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        bindFieldKeys();
        // The tile's edge turns accent while the caret is in the box.
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent e) { box.repaint(); }
            @Override public void focusLost(java.awt.event.FocusEvent e) { box.repaint(); }
        });
        count.setFont(FontRegistry.ui(Tokens.ICON_SMALL));
        box.setVisible(false);
    }

    /** Wires the counterparties; called once from the pane constructor. */
    void attach(JTable table, JComponent host, Runnable open) {
        this.table = table;
        this.host = host;
        this.open = open;
    }

    /** The search box, for the pane to lay over its list. */
    JComponent box() { return box; }

    /** The box's text field (tests). */
    JTextField field() { return field; }

    boolean active() { return active; }
    String query() { return field.getText(); }
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

    /** A character typed into the table: it lands at the end of the box. */
    private void type(char c) {
        if (!active) activate();
        try {
            field.getDocument().insertString(field.getDocument().getLength(),
                    String.valueOf(c), null);
        } catch (BadLocationException ignored) {
            // the end of the document is always a valid offset
        }
    }

    /**
     * Backspace from the table edits the query while a search is active —
     * emptying it ends the search; with no active search the fallback
     * (parent navigation) runs.
     */
    void backspace(Runnable fallback) {
        if (!active) {
            if (fallback != null) fallback.run();
            return;
        }
        int len = field.getDocument().getLength();
        if (len <= 1) {
            close();
            return;
        }
        try {
            field.getDocument().remove(len - 1, 1);
        } catch (BadLocationException ignored) {
            // len - 1 is in range
        }
    }

    /**
     * Opens the search with the caret in its box (the hotkey path): the box
     * shows and waits for typing or a paste. With a search already running
     * it just takes the focus, its text selected for replacing.
     */
    void start() {
        if (!active) activate();
        field.selectAll();
        field.requestFocusInWindow();
    }

    private void activate() {
        active = true;
        noMatch = false;
        lastLength = 0;
        preSearchRows = table.getSelectedRows().clone();
        box.setVisible(true);
        changed();
    }

    /** Ends the search without touching selection; the focus, if the box
     *  held it, goes back to the table. */
    private void close() {
        boolean hadFocus = field.isFocusOwner();
        active = false;
        noMatch = false;
        field.setText("");
        lastLength = 0;
        box.setVisible(false);
        if (hadFocus) table.requestFocusInWindow();
        changed();
    }

    /** Any edit of the box — typed, pasted, deleted — re-runs the match. */
    private void edited() {
        if (!active) return;
        String q = field.getText();
        boolean grew = q.length() > lastLength;
        lastLength = q.length();
        if (q.isEmpty()) {
            noMatch = false;
        } else {
            // "dd" cycles through the d-items instead of matching "dd*" —
            // the classic tree speed-search convention.
            update(grew && isSingleCharRun(q));
        }
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
            if (model.row(r).equals(FileEntry.PARENT)) continue;
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
        table.clearSelection();
        for (int r : preSearchRows) {
            if (r < table.getRowCount()) table.addRowSelectionInterval(r, r);
        }
        if (preSearchRows.length > 0 && preSearchRows[0] < table.getRowCount()) {
            table.scrollRectToVisible(table.getCellRect(preSearchRows[0], 0, true));
        }
        close();
    }

    /** Enter ends the search and performs the normal open action. */
    void commit() {
        if (active) close();
        open.run();
    }

    /** A new listing invalidates the query (navigation, refresh, re-sort). */
    void reset() {
        if (!active && field.getDocument().getLength() == 0) return;
        close();
    }

    private void update(boolean advance) {
        int n = table.getRowCount();
        if (n == 0) {
            noMatch = true;
            return;
        }
        String q = field.getText();
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
        String q = field.getText();
        if (!active || q.isEmpty()) return null;
        return isSingleCharRun(q) ? q.substring(0, 1) : q;
    }

    /** Rows whose name holds the needle (the ".." shortcut never counts). */
    int matchCount() {
        String needle = needle();
        if (needle == null) return 0;
        FileTableModel model = (FileTableModel) table.getModel();
        int n = 0;
        for (int r = 0; r < model.getRowCount(); r++) {
            if (!model.row(r).equals(FileEntry.PARENT) && matchIndex(r, needle) >= 0) n++;
        }
        return n;
    }

    /** "2/5" while the selection sits on a match (the arrows' position),
     *  else how many rows match. */
    String countText() {
        if (needle() == null) return "";
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
        if (model.row(sel).equals(FileEntry.PARENT) || matchIndex(sel, needle) < 0) return 0;
        int at = 0;
        for (int r = 0; r <= sel; r++) {
            if (!model.row(r).equals(FileEntry.PARENT) && matchIndex(r, needle) >= 0) at++;
        }
        return at;
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
        Color ink = UIManager.getColor("TextField.foreground");
        field.setForeground(noMatch ? NO_MATCH : ink);
        String text = table == null ? "" : countText();
        if (!text.equals(count.getText())) count.setText(text);
        count.setForeground(noMatch ? NO_MATCH : FileTableModel.muted());
        if (host != null) host.repaint();
    }

    // ---- the box ----

    /** Where the box sits over {@code area} (the list's viewport): centered
     *  across, just below the column headers. */
    static Rectangle boxBounds(Rectangle area) {
        int w = Math.min(BOX_WIDTH, Math.max(120, area.width - 2 * Tokens.GAP_2));
        return new Rectangle(area.x + (area.width - w) / 2, area.y + Tokens.GAP_2,
                w, BOX_HEIGHT);
    }

    /** Enter, Escape and the arrows drive the search from inside the box
     *  too; Backspace on an empty box closes it. */
    private void bindFieldKeys() {
        var im = field.getInputMap(JComponent.WHEN_FOCUSED);
        var am = field.getActionMap();
        im.put(KeyStroke.getKeyStroke("ENTER"), "dock-search-commit");
        am.put("dock-search-commit", action(this::commit));
        im.put(KeyStroke.getKeyStroke("ESCAPE"), "dock-search-escape");
        am.put("dock-search-escape", action(this::escape));
        for (String key : new String[] {"UP", "KP_UP"}) {
            im.put(KeyStroke.getKeyStroke(key), "dock-search-up");
        }
        am.put("dock-search-up", action(() -> stepOrMove(-1, "selectPreviousRow")));
        for (String key : new String[] {"DOWN", "KP_DOWN"}) {
            im.put(KeyStroke.getKeyStroke(key), "dock-search-down");
        }
        am.put("dock-search-down", action(() -> stepOrMove(1, "selectNextRow")));
        Object deleteName = im.get(KeyStroke.getKeyStroke("BACK_SPACE"));
        Action delete = deleteName == null ? null : am.get(deleteName);
        im.put(KeyStroke.getKeyStroke("BACK_SPACE"), "dock-search-backspace");
        am.put("dock-search-backspace", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (field.getDocument().getLength() == 0) close();
                else if (delete != null) delete.actionPerformed(e);
            }
        });
    }

    /** An arrow in the box: hop between matches, or — without a query —
     *  walk the table one row as its own binding would. */
    private void stepOrMove(int dir, String tableAction) {
        if (step(dir)) return;
        Action a = table.getActionMap().get(tableAction);
        if (a != null) a.actionPerformed(new ActionEvent(table, ActionEvent.ACTION_PERFORMED, ""));
    }

    private static Action action(Runnable r) {
        return new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        };
    }

    /** The floating box: search glyph, the field, the match count — on a
     *  rounded tile. A click anywhere on it puts the caret in the field. */
    private final class Box extends JPanel {
        Box() {
            super(new BorderLayout(Tokens.GAP_2, 0));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
            add(new JLabel(Glyphs.icon(Glyphs.SEARCH, 12, FileTableModel::muted)),
                    BorderLayout.WEST);
            add(field, BorderLayout.CENTER);
            add(count, BorderLayout.EAST);
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    field.requestFocusInWindow();
                }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg = UIManager.getColor("Dock.tileBackground");
                if (bg == null) bg = UIManager.getColor("Panel.background");
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), Tokens.ARC, Tokens.ARC);
                Color edge = field.isFocusOwner() ? UIManager.getColor("Dock.accent")
                        : UIManager.getColor("Component.borderColor");
                if (edge != null) {
                    g2.setColor(edge);
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1,
                            Tokens.ARC, Tokens.ARC);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
