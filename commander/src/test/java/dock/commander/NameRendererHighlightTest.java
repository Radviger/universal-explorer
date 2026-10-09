package dock.commander;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import dock.core.fs.FileEntry;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.JTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The speed search's find-in-page cue: every name holding the query gets a
 * marker behind the matching letters — print-test2.2 and print-test2.3 both
 * show "print-test2" marked, print-test1.9 shows nothing.
 */
class NameRendererHighlightTest {

    private static final List<FileEntry> ENTRIES = List.of(
            FileEntry.PARENT,
            new FileEntry("print-test1.9", true, 0, 0, null, false),
            new FileEntry("print-test2.2", true, 0, 0, null, false),
            new FileEntry("print-test2.3", true, 0, 0, null, false),
            new FileEntry("notes.txt", false, 1, 0, null, false));

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    private static JTable table(String needle) {
        FileTableModel model = new FileTableModel();
        model.setData(ENTRIES);
        JTable table = new JTable(model);
        table.putClientProperty(SpeedSearch.NEEDLE, needle);
        return table;
    }

    private static int[] hit(String needle, int row) throws Exception {
        var out = new AtomicReference<int[]>();
        EventQueue.invokeAndWait(() -> {
            var r = new FileTableModel.NameRenderer();
            r.getTableCellRendererComponent(table(needle), ENTRIES.get(row).name(),
                    false, false, row, 0);
            out.set(r.hitForTest());
        });
        return out.get();
    }

    @Test
    void everyMatchingNameMarksTheQuery() throws Exception {
        assertArrayEquals(new int[] {0, 11}, hit("print-test2", 2));
        assertArrayEquals(new int[] {0, 11}, hit("print-test2", 3));
        assertNull(hit("print-test2", 1), "print-test1.9 does not hold the query");
    }

    @Test
    void theMarkFindsTheQueryInsideTheNameIgnoringCase() throws Exception {
        assertArrayEquals(new int[] {6, 5}, hit("TEST2", 2));
    }

    @Test
    void theParentShortcutIsNeverMarked() throws Exception {
        assertNull(hit(".", 0));
    }

    @Test
    void withoutASearchNothingIsMarked() throws Exception {
        assertNull(hit(null, 2));
    }

    private static BufferedImage paint(String needle, int row, int width) throws Exception {
        var out = new AtomicReference<BufferedImage>();
        EventQueue.invokeAndWait(() -> {
            var r = new FileTableModel.NameRenderer();
            var c = (JComponent) r.getTableCellRendererComponent(table(needle),
                    ENTRIES.get(row).name(), false, false, row, 0);
            c.setSize(width, 24);
            c.doLayout();
            BufferedImage img = new BufferedImage(width, 24, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            c.paint(g);
            g.dispose();
            out.set(img);
        });
        return out.get();
    }

    private static int diff(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) if (a.getRGB(x, y) != b.getRGB(x, y)) n++;
        }
        return n;
    }

    @Test
    void theMarkPaintsBehindTheMatchingLetters() throws Exception {
        assertTrue(diff(paint(null, 2, 300), paint("test2", 2, 300)) > 30,
                "the marker changes the cell");
    }

    @Test
    void aMatchElidedOutOfAThinColumnPaintsNoMark() throws Exception {
        assertEquals(0, diff(paint(null, 2, 70), paint("2.2", 2, 70)),
                "the hit sits past the '...' — nothing to mark");
    }
}
