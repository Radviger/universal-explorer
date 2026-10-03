package dock.commander;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import dock.core.fs.FileEntry;
import java.awt.EventQueue;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The name column renders FileIcons' cached icon for each row's kind —
 * including the parent shortcut, folders, typed files and the generic page —
 * rather than a fixed three-icon set.
 */
class NameRendererIconTest {

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @Test
    void rowsGetTheirKindIcon() throws Exception {
        List<FileEntry> entries = List.of(
                FileEntry.PARENT,
                new FileEntry("src", true, 0, 0, null, false),
                new FileEntry("media", true, 0, 0, null, false, true),
                new FileEntry("main.py", false, 1, 0, null, false),
                new FileEntry("server.log", false, 1, 0, null, false),
                new FileEntry("odd.zzz", false, 1, 0, null, false));
        FileTableModel model = new FileTableModel();
        model.setData(entries);
        FileTableModel.NameRenderer renderer = new FileTableModel.NameRenderer();
        EventQueue.invokeAndWait(() -> {
            JTable table = new JTable(model);
            for (int row = 0; row < entries.size(); row++) {
                var c = renderer.getTableCellRendererComponent(table,
                        entries.get(row).name(), false, false, row, 0);
                assertSame(FileIcons.icon(FileIcons.kindOf(entries.get(row))),
                        ((JLabel) c).getIcon(), entries.get(row).name());
            }
        });
    }
}
