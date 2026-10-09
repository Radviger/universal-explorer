package dock.commander;

import dock.kit.FontRegistry;
import dock.kit.Tokens;
import dock.core.fs.FileEntry;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Rectangle;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.swing.JTable;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Read-only model over an already-filtered, already-sorted display list.
 * Sorting and filtering happen off the EDT in FilePane; the model only
 * swaps in the finished list, which keeps huge directories smooth.
 */
public final class FileTableModel extends AbstractTableModel {

    public enum Column { NAME, SIZE, MODIFIED, ATTRS }

    private static final String[] HEADERS = {"Name", "Size", "Modified", "Attrs"};
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private List<FileEntry> rows = List.of();

    /** The glyph kind of the ".." row: the folder-open everywhere except
     *  inside a mount, where every ".." — root or deeper — wears the
     *  archive mark: the row is the thread back out of the virtual view. */
    FileIcons.Kind parentKind = FileIcons.Kind.PARENT;

    public void setData(List<FileEntry> rows) {
        this.rows = rows;
        fireTableDataChanged();
    }

    public FileEntry row(int viewIndex) { return rows.get(viewIndex); }

    /**
     * Builds the display list: ".." pinned at the top, then hidden filter
     * (optionally extended to dot-prefixed names), then sort, dirs first.
     * ".." is the way out of the directory, not an entry that competes for
     * a sorted position — every column and direction leaves it at row 0.
     */
    public static List<FileEntry> displayList(List<FileEntry> raw, Column sort, boolean asc,
                                               boolean showHidden, boolean hideDotPrefixed) {
        List<FileEntry> visible = new ArrayList<>(raw.size());
        FileEntry parent = null;
        for (FileEntry e : raw) {
            if (e == FileEntry.PARENT) { parent = e; continue; }
            boolean dotHidden = hideDotPrefixed && e.name().startsWith(".");
            if (showHidden || !(e.hidden() || dotHidden)) visible.add(e);
        }
        Comparator<FileEntry> cmp = switch (sort) {
            case NAME -> Comparator.comparing(FileEntry::name, String.CASE_INSENSITIVE_ORDER);
            case SIZE -> Comparator.comparingLong(FileEntry::size);
            case MODIFIED -> Comparator.comparingLong(FileEntry::mtimeMillis);
            case ATTRS -> Comparator.comparing(e -> attrsString(e));
        };
        if (!asc) cmp = cmp.reversed();
        visible.sort(cmp);
        // Stable partition: directories always before files, regardless of order.
        List<FileEntry> out = new ArrayList<>(visible.size() + 1);
        if (parent != null) out.add(parent);
        for (FileEntry e : visible) if (e.directory()) out.add(e);
        for (FileEntry e : visible) if (!e.directory()) out.add(e);
        return out;
    }

    public static String sizeString(FileEntry e) {
        if (e.directory()) return "";
        long b = e.size();
        if (b < 1024) return b + " B";
        double kb = b / 1024.0;
        if (kb < 1024) return "%.1f KB".formatted(kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return "%.1f MB".formatted(mb);
        return "%.1f GB".formatted(mb / 1024.0);
    }

    public static String dateString(FileEntry e) {
        if (e.mtimeMillis() <= 0) return "";
        return DATE.format(Instant.ofEpochMilli(e.mtimeMillis()));
    }

    public static String attrsString(FileEntry e) {
        Integer p = e.posixPerms();
        if (p == null) return "";
        char type = e.directory() ? 'd' : '-';
        return String.format("%c%c%c%c%c%c%c%c%c%c",
                type,
                bit(p, 0400, 'r'), bit(p, 0200, 'w'), bit(p, 0100, 'x'),
                bit(p, 0040, 'r'), bit(p, 0020, 'w'), bit(p, 0010, 'x'),
                bit(p, 0004, 'r'), bit(p, 0002, 'w'), bit(p, 0001, 'x'));
    }

    private static char bit(int perms, int mask, char on) {
        return (perms & mask) != 0 ? on : '-';
    }

    // ---- table plumbing ----

    @Override public int getRowCount() { return rows.size(); }
    @Override public int getColumnCount() { return HEADERS.length; }
    @Override public String getColumnName(int col) { return HEADERS[col]; }
    @Override public Class<?> getColumnClass(int col) { return String.class; }

    @Override public Object getValueAt(int row, int col) {
        FileEntry e = rows.get(row);
        return switch (Column.values()[col]) {
            case NAME -> e.name();
            case SIZE -> sizeString(e);
            case MODIFIED -> dateString(e);
            case ATTRS -> attrsString(e);
        };
    }

    // ---- draft row (the in-place new-folder entry) ----

    private FileEntry draft;

    /** Marks the entry carrying the in-place new-folder field; its name
     *  cell is the only editable cell in the table. */
    void setDraft(FileEntry draft) { this.draft = draft; }

    /** Reroutes the ".." row's icon (the exit mark at a mount's root). */
    void setParentKind(FileIcons.Kind kind) { this.parentKind = kind; }

    /** Inserts the draft row at {@code index} — the pane places it right
     *  below "..". A draft is never part of a listing. */
    void insertRow(int index, FileEntry e) {
        List<FileEntry> next = new ArrayList<>(rows);
        next.add(index, e);
        rows = next;
        fireTableRowsInserted(index, index);
    }

    /** Drops the draft row (commit or cancel); anything else is ignored. */
    void removeRow(FileEntry e) {
        int i = rows.indexOf(e);
        if (i < 0) return;
        if (e == draft) draft = null;
        List<FileEntry> next = new ArrayList<>(rows);
        next.remove(i);
        rows = next;
        fireTableRowsDeleted(i, i);
    }

    @Override public boolean isCellEditable(int row, int col) {
        return col == 0 && rows.get(row) == draft;
    }

    /** The draft's commit is handled by the pane's editor listener. */
    @Override public void setValueAt(Object value, int row, int col) { }

    // ---- renderers ----

    static final class NameRenderer extends DefaultTableCellRenderer {
        private final Font plain = FontRegistry.mono();
        private final Font dirFont = FontRegistry.monoMedium(FontRegistry.BASE_SIZE);
        /** The speed-search hit in this cell's name: start and length, or -1. */
        private int hitStart = -1;
        private int hitLength;

        NameRenderer() {
            setIconTextGap(Tokens.GAP_2);
            setBorder(javax.swing.BorderFactory.createEmptyBorder(0, Tokens.GAP_2, 0, Tokens.GAP_2));
        }

        @Override protected void setValue(Object value) { setText(value == null ? "" : value.toString()); }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            var c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            FileTableModel m = (FileTableModel) table.getModel();
            FileEntry e = m.row(row);
            boolean selected = table.getSelectedRows().length > 0 && isSelected;
            setIcon(FileIcons.icon(e.equals(FileEntry.PARENT)
                    ? m.parentKind : FileIcons.kindOf(e)));
            if (e.equals(FileEntry.PARENT)) {
                setFont(dirFont);
                setForeground(selected ? c.getForeground() : muted());
            } else if (e.directory()) {
                setFont(dirFont);
            } else {
                setFont(plain);
            }
            hitStart = -1;
            if (!e.equals(FileEntry.PARENT)
                    && table.getClientProperty(SpeedSearch.NEEDLE) instanceof String needle) {
                hitStart = SpeedSearch.matchAt(e.name(), needle);
                hitLength = needle.length();
            }
            return c;
        }

        /** The highlighted run as {start, length}; null when none (tests). */
        int[] hitForTest() {
            return hitStart < 0 ? null : new int[] {hitStart, hitLength};
        }

        /**
         * The browser's find-in-page cue: a marker behind the matching
         * letters, painted under the text. The background is filled here
         * first, so the label paints transparent over the marker.
         */
        @Override protected void paintComponent(java.awt.Graphics g) {
            Rectangle hit = hitStart < 0 ? null : hitRect(g);
            if (hit == null) {
                super.paintComponent(g);
                return;
            }
            boolean opaque = isOpaque();
            if (opaque) {
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
            }
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            Color mark = UIManager.getColor("Dock.searchHit");
            g2.setColor(mark != null ? mark : new Color(0xE0, 0xAF, 0x68, 0x70));
            g2.fillRoundRect(hit.x, hit.y, hit.width, hit.height, 4, 4);
            g2.dispose();
            setOpaque(false);
            try {
                super.paintComponent(g);
            } finally {
                setOpaque(opaque);
            }
        }

        /** The marker's box over the visible (possibly elided) text, or null
         *  when the hit lies beyond what the column shows. */
        private Rectangle hitRect(java.awt.Graphics g) {
            String text = getText();
            if (text == null || hitStart + hitLength > text.length()) return null;
            java.awt.FontMetrics fm = g.getFontMetrics(getFont());
            java.awt.Insets in = getInsets();
            Rectangle view = new Rectangle(in.left, in.top,
                    getWidth() - in.left - in.right, getHeight() - in.top - in.bottom);
            Rectangle iconR = new Rectangle();
            Rectangle textR = new Rectangle();
            String shown = javax.swing.SwingUtilities.layoutCompoundLabel(this, fm, text,
                    getIcon(), getVerticalAlignment(), getHorizontalAlignment(),
                    getVerticalTextPosition(), getHorizontalTextPosition(),
                    view, iconR, textR, getIconTextGap());
            int visible = shown.equals(text) ? text.length()
                    : Math.max(0, shown.length() - 3);   // the "..." elision
            if (hitStart >= visible) return null;
            int end = Math.min(hitStart + hitLength, visible);
            int x = textR.x + fm.stringWidth(text.substring(0, hitStart));
            int w = fm.stringWidth(text.substring(hitStart, end));
            return new Rectangle(x - 1, textR.y, w + 2, textR.height);
        }
    }

    /** A renderer that keeps its column monospaced: Swing's default cell
     *  renderer resets the font to the table's on every render call, so a
     *  font installed once in a constructor never shows up and the
     *  column's digits refuse to line up. */
    private abstract static class MonoRenderer extends DefaultTableCellRenderer {
        private final Font font;
        MonoRenderer(Font font) { this.font = font; }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            var c = super.getTableCellRendererComponent(table, value, isSelected,
                    hasFocus, row, column);
            setFont(font);
            return c;
        }
    }

    static final class SizeRenderer extends MonoRenderer {
        SizeRenderer() {
            super(FontRegistry.mono());
            setHorizontalAlignment(TRAILING);
            setForeground(muted());
        }
    }

    static final class DateRenderer extends MonoRenderer {
        DateRenderer() {
            super(FontRegistry.mono());
            setForeground(muted());
        }
    }

    static final class AttrsRenderer extends MonoRenderer {
        AttrsRenderer() {
            super(FontRegistry.mono(FontRegistry.BASE_SIZE - 1));
            setForeground(muted());
        }
    }

    static Color muted() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : UIManager.getColor("Label.foreground");
    }
}
