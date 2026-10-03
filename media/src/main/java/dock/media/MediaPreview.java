package dock.media;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * One frame of a media file at a time of your choosing — the scrub tip's
 * preview row. libVLC 3 ships no native thumbnailer (that is a 4.0 API),
 * so the production implementation is a hidden, muted second player (see
 * {@link VlcPreviewEngine}); the seam keeps natives away from tests and
 * the harness, exactly like {@link MediaEngine}.
 *
 * <p>One engine per player panel, re-opened per file. All methods may
 * block (native calls); the factory and {@link #open} run off the EDT by
 * contract, and every delivery — the frame, or the null of a failed
 * request — arrives on the EDT, at most once per request. A new request
 * may supersede one still in flight.
 */
public interface MediaPreview extends AutoCloseable {

    /** Creates a preview engine, or null when the runtime cannot. */
    interface Factory { MediaPreview create(); }

    /** Points the engine at a file URL (its own bridge token) and spins
     *  it up to a paused stop; requests arriving before the spin-up
     *  parks are held and issued then. */
    void open(String url);

    /** Asks for the frame at {@code timeMs}. */
    void request(long timeMs, Consumer<BufferedImage> into);

    /** Stops and releases everything — off the EDT. Idempotent. */
    @Override void close();
}
