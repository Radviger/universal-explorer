package dock.commander;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import dock.core.fs.FileEntry;
import java.awt.Component;
import java.awt.EventQueue;
import java.util.List;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The numeric columns (size, modified, attrs) render in the mono font so
 * their digits line up row over row. Swing's default cell renderer resets
 * the font to the table's on every render call — a font installed once in
 * a constructor never shows up — so each renderer re-applies its font
 * after super, and the render pass must end in JetBrains Mono, not the
 * window's proportional UI font.
 */
class ColumnFontTest {

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @Test
    void numericColumnsKeepTheirMonoFontThroughTheRenderPass() throws Exception {
        FileEntry file = new FileEntry("a.txt", false, 1536,
                System.currentTimeMillis(), 0100644, false);
        FileTableModel model = new FileTableModel();
        model.setData(List.of(FileEntry.PARENT, file));
        DefaultTableCellRenderer[] renderers = {
                new FileTableModel.SizeRenderer(),
                new FileTableModel.DateRenderer(),
                new FileTableModel.AttrsRenderer() };
        java.awt.Font[] wanted = {
                dock.kit.FontRegistry.mono(),
                dock.kit.FontRegistry.mono(),
                dock.kit.FontRegistry.mono(dock.kit.FontRegistry.BASE_SIZE - 1) };

        EventQueue.invokeAndWait(() -> {
            JTable table = new JTable(model);
            for (int column = 1; column <= 3; column++) {
                Component c = renderers[column - 1].getTableCellRendererComponent(table,
                        model.getValueAt(1, column), false, false, 1, column);
                assertEquals(wanted[column - 1].getFamily(), c.getFont().getFamily(),
                        "column " + column + " keeps its mono family after a render");
                assertEquals(wanted[column - 1].getSize2D(), c.getFont().getSize2D(), 0.01f,
                        "column " + column + " keeps its size after a render");
            }
        });
    }
}
