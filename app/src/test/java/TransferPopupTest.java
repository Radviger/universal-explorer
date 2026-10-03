import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import dock.core.fs.FileEntry;
import dock.core.fs.LocalFs;
import dock.core.transfer.TransferEngine;
import dock.core.transfer.TransferJob;
import dock.ui.StatusBar;
import dock.ui.TransferPopup;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import javax.swing.JLabel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transfers popup: hidden until toggled, floating above and
 * right-aligned with the footer, one row per job with its bar, and the
 * dismissal/lingering rules (Esc, outside click, settled rows dropping out
 * while failures stick).
 */
class TransferPopupTest {

    private static JFrame frame;
    private TransferEngine engine;
    private StatusBar anchor;
    private TransferPopup popup;

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @BeforeEach
    void boot() {
        engine = new TransferEngine();
        onEdt(() -> {
            frame = new JFrame();
            anchor = new StatusBar(engine, () -> {});
            frame.add(anchor, BorderLayout.SOUTH);
            frame.setSize(900, 160);
            frame.setLocation(50, 50);
            frame.setVisible(true);
            popup = new TransferPopup(frame, engine, anchor);
        });
    }

    @AfterEach
    void shutdown() {
        onEdt(() -> {
            popup.hidePopup();
            frame.dispose();
        });
    }

    @Test
    void startsHiddenTogglesAboveTheAnchorAndBack() {
        assertFalse(popup.popupVisibleForTest());

        popup.toggleForTest();
        assertTrue(popup.popupVisibleForTest());
        Window w = popup.windowForTest();
        Rectangle bounds = onEdtGet(w::getBounds);
        Point anchorBase = onEdtGet(anchor::getLocationOnScreen);
        int anchorRight = anchorBase.x + onEdtGet(anchor::getWidth);
        assertTrue(bounds.y + bounds.height <= anchorBase.y + 2,
                "popup sits above the footer, not over it");
        assertTrue(Math.abs(bounds.x + bounds.width - anchorRight) <= 2,
                "right-aligned with the footer");

        popup.toggleForTest();
        assertFalse(popup.popupVisibleForTest());
    }

    @Test
    void finishedBurstLingersAsADoneRow() throws Exception {
        Path tmp = Files.createTempDirectory("dock-popup");
        Path dst = Files.createDirectories(tmp.resolve("out"));
        Path src = tmp.resolve("payload.bin");
        byte[] payload = new byte[4 * 1024 * 1024];
        new java.util.Random(7).nextBytes(payload);
        Files.write(src, payload);
        engine.enqueue(LocalFs.INSTANCE, tmp.toString(),
                List.of(new FileEntry("payload.bin", false, payload.length, 0, null, false)),
                LocalFs.INSTANCE, dst.toString(), false);
        await(() -> engine.snapshot().stream().noneMatch(TransferJob::isActive));

        popup.toggleForTest();
        assertEquals(1, popup.rowCountForTest());
        assertEquals("payload.bin", popup.rowNameForTest(0));
        assertEquals(100, popup.rowPercentForTest(0));
        assertTrue(popup.rowDetailForTest(0).startsWith("Done — "), popup.rowDetailForTest(0));
        assertFalse(popup.rowRetryVisibleForTest(0));

        // The footer's number segment (empty once the burst settled) is
        // its own label in the mono font.
        assertEquals("", popup.footerNumsTextForTest());
        assertEquals(dock.kit.FontRegistry.mono().getFamily(),
                popup.footerNumsFontForTest().getFamily(),
                "speed and percent read in mono");
    }

    @Test
    void failuresStickAroundWithARetryControl() throws Exception {
        Path tmp = Files.createTempDirectory("dock-popup-fail");
        Path dst = Files.createDirectories(tmp.resolve("out"));
        Path src = tmp.resolve("good.bin");
        Files.write(src, new byte[64 * 1024]);
        Path blocker = tmp.resolve("blocker");
        Files.writeString(blocker, "i am a file, not a directory");

        engine.enqueue(LocalFs.INSTANCE, tmp.toString(),
                List.of(new FileEntry("good.bin", false, 64 * 1024, 0, null, false)),
                LocalFs.INSTANCE, dst.toString(), false);
        // The target root is a plain file, so the write must fail.
        engine.enqueue(LocalFs.INSTANCE, tmp.toString(),
                List.of(new FileEntry("blocker", false, Files.size(blocker), 0, null, false)),
                LocalFs.INSTANCE, blocker.toString(), false);
        await(() -> engine.snapshot().stream().noneMatch(TransferJob::isActive));

        popup.toggleForTest();
        assertEquals(2, popup.rowCountForTest());
        int failedRow = "blocker".equals(popup.rowNameForTest(0)) ? 0 : 1;
        assertTrue(popup.rowDetailForTest(failedRow).startsWith("Failed — "),
                popup.rowDetailForTest(failedRow));
        assertTrue(popup.rowRetryVisibleForTest(failedRow));

        // Failures outlive the linger; settled successes drop out of scope.
        List<TransferJob> later = TransferPopup.shownJobs(engine.snapshot(),
                System.currentTimeMillis() + 60_000);
        assertEquals(1, later.size());
        assertEquals("blocker", later.get(0).name());
    }

    @Test
    void escDismissesAndOutsidePressesRouteToDismissal() throws Exception {
        popup.toggleForTest();
        assertTrue(popup.popupVisibleForTest());

        // A real Esc through the event queue — proves the global listener
        // is registered and hides the popup.
        postEscape(frame);
        await(() -> !popup.popupVisibleForTest());

        // Press routing shares that listener (and the same hide call), so
        // pinning the predicate covers the click side deterministically —
        // synthetic mouse presses can't be driven through the native pump
        // the way keys can.
        popup.toggleForTest();
        Window w = popup.windowForTest();
        assertTrue(TransferPopup.clickDismisses(frame, w, anchor),
                "a press on the window behind dismisses");
        assertFalse(TransferPopup.clickDismisses(((java.awt.Container) w).getComponent(0), w, anchor),
                "presses inside the popup pass through");
        assertFalse(TransferPopup.clickDismisses(anchor, w, anchor),
                "footer presses pass through to its own toggle");
    }

    @Test
    void idleQueueShowsAHintInsteadOfRows() {
        popup.toggleForTest();
        assertEquals(0, popup.rowCountForTest());
        assertEquals("No recent transfers.", popup.emptyHintForTest());
        assertTrue(popup.popupVisibleForTest());
    }

    @Test
    void clipEndsWithEllipsisAndFitsTheWidth() {
        JLabel probe = new JLabel();
        probe.setFont(dock.kit.FontRegistry.mono());
        java.awt.FontMetrics fm = probe.getFontMetrics(probe.getFont());
        String line = "12.4 MB/s · 1m 12s left · 1.2 MB / 4.0 MB";
        int wide = fm.stringWidth(line);

        assertEquals(line, TransferPopup.clipToWidth(line, fm, wide),
                "text that fits comes back unchanged");
        String clipped = TransferPopup.clipToWidth(line, fm, wide / 2);
        assertTrue(clipped.endsWith("…"), clipped);
        assertTrue(clipped.length() < line.length());
        assertTrue(fm.stringWidth(clipped) <= wide / 2, "clipped text fits the width");
        assertEquals("…", TransferPopup.clipToWidth(line, fm, fm.stringWidth("…")),
                "degenerate width still shows the ellipsis");
    }

    @Test
    void longFileNamesEllipsizeInsteadOfOverflowing() throws Exception {
        Path tmp = Files.createTempDirectory("dock-popup-long");
        Path dst = Files.createDirectories(tmp.resolve("out"));
        String longName = "a-very-long-transfer-file-name-" + "x".repeat(120) + ".bin";
        Path src = tmp.resolve(longName);
        Files.write(src, new byte[8 * 1024]);
        engine.enqueue(LocalFs.INSTANCE, tmp.toString(),
                List.of(new FileEntry(longName, false, 8 * 1024, 0, null, false)),
                LocalFs.INSTANCE, dst.toString(), false);
        await(() -> engine.snapshot().stream().noneMatch(TransferJob::isActive));

        popup.toggleForTest();
        assertEquals(longName, popup.rowNameForTest(0), "the model keeps the full name");
        String shown = popup.rowNameDisplayForTest(0);
        assertTrue(shown.endsWith("…"), "painted name is ellipsized: " + shown);
        assertTrue(shown.length() < longName.length());
    }

    // ---- harness ----

    /** Posts a real Esc into the event queue; the popup's global listener sees it. */
    private static void postEscape(Component src) {
        Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(new KeyEvent(
                src, KeyEvent.KEY_PRESSED, EventQueue.getMostRecentEventTime(), 0,
                KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED));
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean()) {
            try { Thread.sleep(25); } catch (InterruptedException e) { return; }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within 15s");
            }
        }
    }

    private static void onEdt(Runnable r) {
        try {
            EventQueue.invokeAndWait(r);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static <T> T onEdtGet(java.util.function.Supplier<T> read) {
        AtomicReference<T> out = new AtomicReference<>();
        onEdt(() -> out.set(read.get()));
        return out.get();
    }
}
