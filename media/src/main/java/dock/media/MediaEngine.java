package dock.media;

import java.io.File;
import javax.swing.JComponent;

/**
 * The playback engine seam: everything the player panel needs, nothing the
 * engine's own types leak through. The production implementation rides
 * libVLC via vlcj; tests and the screenshot harness inject fakes here, so
 * no native library is ever required to verify the panel or the wiring.
 *
 * <p>All methods may block (native calls); event callbacks arrive on the
 * engine's own threads.
 */
public interface MediaEngine extends AutoCloseable {

    /** Creates an engine, or null when the native runtime is unavailable. */
    interface Factory { MediaEngine create(); }

    /** Playback events; every method defaults to nothing. */
    interface Listener {
        Listener NOTHING = new Listener() {};

        default void playing() {}
        default void paused() {}
        default void finished() {}
        default void error(String message) {}
        /** 0..1 while the engine refills its buffers. */
        default void buffering(float percent) {}
        default void time(long ms) {}
        default void duration(long ms) {}
    }

    /** Paints layered UI from inside the engine's own video painting.
     *  The video surface repaints itself on its own schedule (every frame
     *  while playing), and Swing repaints only that opaque subtree — an
     *  overlay painted by anything above it is clobbered between the
     *  overlay's own repaints, which reads as flicker. Compositing inside
     *  the surface's single paint makes frames and overlay atomic. */
    interface Overlay {
        /** {@code g} is translated to the video area's origin; paint
         *  within {@code width} × {@code height}. {@code movieW/H} is the
         *  aspect-fitted picture inside that area (equal to the area when
         *  nothing letterboxes, audio say) — size overlays against the
         *  picture, not the window frame, or pillarboxed footage makes
         *  them oversized. */
        void paint(java.awt.Graphics2D g, int width, int height,
                int movieW, int movieH);
    }

    /** The Swing surface video renders into (audio plays through it too). */
    JComponent surface();

    /** Arms the listener before the first {@link #open}; replaces any prior. */
    void setListener(Listener listener);

    /** Arms the overlay painter (the panel's click flash); null clears.
     *  Called before {@link #open}. */
    void setOverlay(Overlay overlay);

    /** Loads the URL and starts playing it. */
    void open(String url);

    void play();
    void pause();
    void stop();

    /** Positions playback; may round to the nearest keyframe. */
    void seekMs(long ms);

    long timeMs();
    long durationMs();
    boolean isPlaying();

    /** 0–100, 100 being the engine's nominal level. */
    void setVolume(int percent);
    void setMute(boolean mute);

    /** What the demuxer reports once it has probed the file: codec fourccs
     *  and pixel size, any of which may be null/zero. */
    record Info(String videoCodec, String audioCodec, int width, int height) {}

    /** Track summary of the loaded file, or null while the engine has
     *  none yet. A native call — ask off the EDT, and only ever for the
     *  file being played, never per listing. */
    default Info info() { return null; }

    /** Renders a subtitle file over the video; null turns subtitles off.
     *  Effective once a file is playing — adding a track to an unstarted
     *  media is a silent no-op in libVLC. */
    void loadSubtitle(File file);

    @Override void close();
}
