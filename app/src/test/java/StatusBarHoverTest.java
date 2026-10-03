import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.EventQueue;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The footer's left side is the status line's hover text: hovering a file
 * row shows that row's facts there — commander-style — and clearing the
 * line empties the footer again. No tooltip ever floats over the file
 * table for this.
 */
class StatusBarHoverTest {

    private dock.ui.StatusBar bar;

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @AfterEach
    void reset() {
        dock.kit.StatusLine.clear();
    }

    @Test
    void hoverTextOccupiesTheFooter() throws Exception {
        var ref = new AtomicReference<dock.ui.StatusBar>();
        EventQueue.invokeAndWait(() ->
                ref.set(new dock.ui.StatusBar(new dock.core.transfer.TransferEngine(), () -> {})));
        bar = ref.get();

        assertFalse(bar.hoverVisibleForTest(), "idle footer shows nothing on the left");
        assertEquals("", bar.hoverTextForTest());

        String line = "report.txt · 12.4 KB · modified 2026-09-24 14:03";
        EventQueue.invokeAndWait(() -> dock.kit.StatusLine.publish(line));
        assertTrue(bar.hoverVisibleForTest(), "hover text shows in the footer");
        assertEquals(line, bar.hoverTextForTest());

        EventQueue.invokeAndWait(() -> dock.kit.StatusLine.clear());
        assertFalse(bar.hoverVisibleForTest(), "clearing the line empties the footer");
        assertEquals("", bar.hoverTextForTest());
    }

    @Test
    void fileFactsReadMonoSentencesReadUi() throws Exception {
        var ref = new AtomicReference<dock.ui.StatusBar>();
        EventQueue.invokeAndWait(() ->
                ref.set(new dock.ui.StatusBar(new dock.core.transfer.TransferEngine(), () -> {})));
        bar = ref.get();

        EventQueue.invokeAndWait(() -> dock.kit.StatusLine.publish(
                "report.tar · 1.5 KB · modified 2026-09-24 14:03", true));
        assertEquals(dock.kit.FontRegistry.mono().getFamily(),
                bar.hoverFontForTest().getFamily(), "file facts render in mono");

        EventQueue.invokeAndWait(() -> dock.kit.StatusLine.publish("Connect to unit-nas"));
        assertEquals(dock.kit.FontRegistry.ui().getFamily(),
                bar.hoverFontForTest().getFamily(), "sentences render in the UI font");
    }
}
