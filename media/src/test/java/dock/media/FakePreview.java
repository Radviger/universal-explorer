package dock.media;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * The deterministic preview: requests recorded, frames handed over on
 * demand — or null, the failure path. Deliveries ride the EDT like the
 * real engine's.
 */
final class FakePreview implements MediaPreview {

    final List<Long> requested = new ArrayList<>();
    volatile String lastUrl;
    volatile boolean closed;
    private volatile Consumer<BufferedImage> sink;

    @Override public void open(String url) {
        lastUrl = url;
    }

    @Override public void request(long timeMs, Consumer<BufferedImage> into) {
        requested.add(timeMs);
        sink = into;
    }

    /** Delivers to the live request, or nothing when none is pending. */
    void deliver(BufferedImage image) {
        Consumer<BufferedImage> into = sink;
        sink = null;
        if (into != null) SwingUtilities.invokeLater(() -> into.accept(image));
    }

    boolean pending() { return sink != null; }

    @Override public void close() {
        closed = true;
        deliver(null);
    }
}
