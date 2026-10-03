package dock.media;

import java.awt.BorderLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * The deterministic engine: every call is recorded, every event fires
 * synchronously — playback state becomes a plain assertion instead of a
 * wait on native code. Its surface paints nothing; the panel's letterbox
 * shows through. The surface is nested like the real thing (vlcj's
 * component is a panel holding the actual video surface), so clicks land
 * on the deepest child exactly as they do live.
 */
final class FakeEngine implements MediaEngine {

    final List<String> openedUrls = new ArrayList<>();
    final List<Long> seeks = new ArrayList<>();
    final List<Integer> volumes = new ArrayList<>();
    final List<Boolean> mutes = new ArrayList<>();
    final List<File> subtitles = new ArrayList<>();
    boolean closed;
    boolean stopCalled;
    private long timeMs = 12_000;
    private long durationMs;
    private boolean playing;
    private Info info;

    private MediaEngine.Listener listener = MediaEngine.Listener.NOTHING;
    private MediaEngine.Overlay overlay;
    // Nested like the real thing: vlcj's component is a panel holding the
    // actual video surface, and clicks land on that deepest child.
    private final JComponent video = new JPanel() {
        @Override protected void paintComponent(Graphics g) {
            // Flat gray stands in for real footage, so paint-order tests
            // can see what the panel layers above the video.
            g.setColor(FOOTAGE);
            g.fillRect(0, 0, getWidth(), getHeight());
            // vlcj composes the overlay inside the video surface's own
            // paint (onPaintOverlay) — the fake must do the same, or the
            // panel's flash would paint nowhere a test could see it. The
            // fake's picture fills its surface: no letterbox to pass.
            if (overlay != null)
                overlay.paint((Graphics2D) g, getWidth(), getHeight(),
                        getWidth(), getHeight());
        }
    };
    private final JPanel surface = new JPanel(new BorderLayout());

    private static final java.awt.Color FOOTAGE = new java.awt.Color(120, 120, 120);

    FakeEngine() {
        video.setOpaque(false);
        surface.add(video, BorderLayout.CENTER);
    }

    void setDuration(long ms) {
        durationMs = ms;
        listener.duration(ms);
    }

    /** What the demuxer would have reported, handed over at once. */
    void setInfo(Info info) { this.info = info; }

    void finish() {
        playing = false;
        listener.finished();
    }

    void fail(String message) {
        playing = false;
        listener.error(message);
    }

    String lastUrl() { return openedUrls.isEmpty() ? null : openedUrls.getLast(); }
    Long lastSeek() { return seeks.isEmpty() ? null : seeks.getLast(); }

    @Override public JComponent surface() { return surface; }

    @Override public void setListener(MediaEngine.Listener listener) {
        this.listener = listener;
    }

    @Override public void setOverlay(MediaEngine.Overlay overlay) {
        this.overlay = overlay;
    }

    @Override public void open(String url) {
        openedUrls.add(url);
        timeMs = 0;
        durationMs = 0;
        playing = true;
        listener.playing();
    }

    @Override public void play() {
        playing = true;
        listener.playing();
    }

    @Override public void pause() {
        playing = false;
        listener.paused();
    }

    @Override public void stop() { stopCalled = true; playing = false; }

    @Override public void seekMs(long ms) {
        timeMs = ms;
        seeks.add(ms);
    }

    @Override public long timeMs() { return timeMs; }
    @Override public long durationMs() { return durationMs; }
    @Override public boolean isPlaying() { return playing; }

    @Override public void setVolume(int percent) { volumes.add(percent); }
    @Override public void setMute(boolean mute) { mutes.add(mute); }
    @Override public Info info() { return info; }

    @Override public void loadSubtitle(File file) { subtitles.add(file); }

    @Override public void close() { closed = true; }
}
