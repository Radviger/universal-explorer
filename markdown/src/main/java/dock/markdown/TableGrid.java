package dock.markdown;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import dock.kit.FontRegistry;

/**
 * A markdown table, embedded inline in the rendered document as a grid
 * of labels. Columns size to their content (GridBag preferred sizes); a
 * table wider than the pane clips at the pane edge while word wrap is on
 * — never a horizontal scrollbar in a reading surface — and keeps its
 * full width, scrollbar and all, when the reader turns wrap off. Header
 * cells carry the code-chip tint and a rule under them, the way
 * rendered READMEs do.
 */
final class TableGrid extends JPanel {

    /** One cell's plain text and presentation. {@code alignment} is one of
     *  the {@code SwingConstants} LEFT / CENTER / RIGHT. */
    record Cell(String text, boolean header, int alignment) {}

    TableGrid(List<List<Cell>> rows, MdTheme theme) {
        super(new GridBagLayout());
        setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 10, 3, 10);
        gbc.fill = GridBagConstraints.NONE;
        for (int r = 0; r < rows.size(); r++) {
            List<Cell> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                Cell cell = row.get(c);
                gbc.gridx = c;
                gbc.gridy = r;
                gbc.anchor = switch (cell.alignment()) {
                    case SwingConstants.CENTER -> GridBagConstraints.CENTER;
                    case SwingConstants.RIGHT -> GridBagConstraints.EAST;
                    default -> GridBagConstraints.WEST;
                };
                add(cell(cell, theme), gbc);
            }
        }
    }

    private static JLabel cell(Cell cell, MdTheme theme) {
        // An empty label has no height; a blank keeps the row on the grid.
        JLabel label = new JLabel(cell.text().isEmpty() ? " " : cell.text());
        label.setForeground(theme.foreground());
        label.setFont(cell.header()
                ? FontRegistry.monoMedium(theme.base().getSize2D())
                : theme.base());
        if (cell.header()) {
            label.setOpaque(true);
            label.setBackground(theme.codeBackground());
            label.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, theme.border()));
        }
        return label;
    }
}
