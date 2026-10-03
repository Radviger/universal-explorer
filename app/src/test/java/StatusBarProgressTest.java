import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import dock.core.fs.FileEntry;
import dock.core.fs.LocalFs;
import dock.core.transfer.TransferEngine;
import dock.core.transfer.TransferJob;
import dock.ui.StatusBar;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The footer's overall transfer bar: hidden on a quiet queue, and when a
 * burst finishes it holds its completed state briefly (so the user actually
 * sees 100%) before retiring. The mid-flight math lives in core's
 * OverallProgressTest; this pins the Swing wiring around it.
 */
class StatusBarProgressTest {

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @Test
    void barStaysHiddenWhileQueueIsIdle() {
        StatusBar bar = new StatusBar(new TransferEngine(), () -> {});
        bar.pollForTest();
        assertFalse(bar.progressVisibleForTest());
    }

    @Test
    void theSpeedSegmentRendersInMono() {
        StatusBar bar = new StatusBar(new TransferEngine(), () -> {});
        bar.pollForTest();
        assertEquals("", bar.activitySpeedTextForTest(), "a quiet queue shows no speed");
        assertEquals(dock.kit.FontRegistry.mono().getFamily(),
                bar.activitySpeedFontForTest().getFamily(),
                "the ticking digits read in mono");
    }

    @Test
    void barLingersAtFullWhenBurstCompletesThenRetires() throws Exception {
        TransferEngine engine = new TransferEngine();
        StatusBar bar = new StatusBar(engine, () -> {});

        Path tmp = Files.createTempDirectory("dock-footer");
        Path dst = Files.createDirectories(tmp.resolve("out"));
        Path src = tmp.resolve("payload.bin");
        byte[] payload = new byte[4 * 1024 * 1024];
        new java.util.Random(42).nextBytes(payload);
        Files.write(src, payload);
        engine.enqueue(LocalFs.INSTANCE, tmp.toString(),
                List.of(new FileEntry("payload.bin", false, payload.length, 0, null, false)),
                LocalFs.INSTANCE, dst.toString(), false);
        await(() -> engine.snapshot().stream().noneMatch(TransferJob::isActive));
        assertEquals(-1, Files.mismatch(src, dst.resolve("payload.bin")), "copy must land");

        bar.pollForTest();
        assertTrue(bar.progressVisibleForTest(), "a just-finished burst holds its completed state");
        assertEquals(100, bar.progressValueForTest());
        assertFalse(bar.progressIndeterminateForTest());

        Thread.sleep(2_300); // past the 2s linger
        bar.pollForTest();
        assertFalse(bar.progressVisibleForTest(), "the bar retires once the linger expires");
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
}
