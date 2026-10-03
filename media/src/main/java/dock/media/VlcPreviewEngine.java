package dock.media;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallbackAdapter;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat;

/**
 * Frame previews without a thumbnailer: a second, muted libVLC player
 * that never reaches a screen. Each request plays the file from the
 * wanted time — VLC's own {@code :start-time} option, so no pause,
 * resume or seek correlation is ever needed (a vmem player paused
 * before its first display never displays again, and its time events
 * are not to be trusted) — takes the second frame the file produces
 * (the first can be pre-roll black), delivers it, and stops. Frames
 * arrive as raw RV32 ints on the render thread, into a buffer the
 * native side reuses; each is copied and scaled to tip width before
 * anything else sees it. A watchdog bounds every request, and the only
 * thing that ever touches the EDT is the delivery.
 */
final class VlcPreviewEngine implements MediaPreview {

    /** Tip width of delivered frames; height follows the aspect. */
    private static final int PREVIEW_WIDTH = 176;
    /** Frames to let the file produce before taking one: the first can
     *  be pre-roll black. */
    private static final int FRAMES_TO_SKIP = 1;
    /** A request that has produced no frame by now delivers its best
     *  effort — the newest frame, or null. Big files over slow sources
     *  need the room. */
    private static final long REQUEST_TIMEOUT_MS = 5_000;

    static final class Factory implements MediaPreview.Factory {
        @Override public MediaPreview create() {
            if (!VlcEngine.available()) return null;
            try {
                return new VlcPreviewEngine();
            } catch (Throwable t) {
                return null;   // a broken native stack is a missing preview
            }
        }
    }

    private final CallbackMediaPlayerComponent player;
    private final FrameGrab grab = new FrameGrab();
    /** The frame index a live request is counting from; MAX when idle. */
    private final AtomicInteger wantSeq = new AtomicInteger(Integer.MAX_VALUE);
    private volatile Consumer<BufferedImage> sink;
    private volatile String url = "";

    private VlcPreviewEngine() {
        // No surface, no painter, no input events: frames flow straight
        // into the grab, and nothing about this player is ever shown.
        player = new CallbackMediaPlayerComponent(null, null, null, true,
                null, grab, grab, null);
        player.mediaPlayer().events().addMediaPlayerEventListener(
                new MediaPlayerEventAdapter() {
                    @Override public void error(MediaPlayer p) {
                        complete(null);
                    }
                });
    }

    @Override public void open(String url) {
        // Just the target: every request opens the file fresh, from its
        // own start time.
        this.url = url == null ? "" : url;
    }

    @Override public void request(long timeMs, Consumer<BufferedImage> into) {
        String media = url;
        if (media.isBlank()) {
            SwingUtilities.invokeLater(() -> into.accept(null));
            return;
        }
        complete(null);   // a superseded request dies quietly
        sink = into;
        wantSeq.set(grab.seq.get() + FRAMES_TO_SKIP);
        player.mediaPlayer().media().play(media, ":no-audio",
                ":start-time=" + Math.max(0, timeMs) / 1000.0);
        Thread.ofVirtual().name("dock-media-preview-watch").start(() -> {
            try {
                Thread.sleep(REQUEST_TIMEOUT_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (sink == into) complete(grab.frame);   // best effort or null
        });
    }

    /** Retires the live request, delivers to it on the EDT, and leaves
     *  the player stopped — idle costs nothing. */
    private void complete(BufferedImage image) {
        Consumer<BufferedImage> into = sink;
        sink = null;
        wantSeq.set(Integer.MAX_VALUE);
        if (into != null)
            SwingUtilities.invokeLater(() -> into.accept(image));
        if (into != null)
            Thread.ofVirtual().name("dock-media-preview-stop").start(() -> {
                try { player.mediaPlayer().controls().stop(); }
                catch (Throwable ignored) {}
            });
    }

    @Override public void close() {
        complete(null);
        try { player.mediaPlayer().controls().stop(); } catch (Throwable ignored) {}
        try { player.release(); } catch (Throwable ignored) {}
    }

    /**
     * The frame tap. The render thread delivers each frame as the RV32
     * ints of a buffer the native side reuses, so every grab is copied
     * out (and scaled) the moment it arrives, before delivery. No
     * native calls from in here — controlling the player from inside
     * the display callback wedges the vout.
     */
    private final class FrameGrab extends RenderCallbackAdapter
            implements BufferFormatCallback {

        final AtomicInteger seq = new AtomicInteger();
        volatile BufferedImage frame;
        private int[] pixels = new int[0];
        private int width, height;

        @Override public BufferFormat getBufferFormat(int sourceWidth, int sourceHeight) {
            width = sourceWidth;
            height = sourceHeight;
            pixels = new int[sourceWidth * sourceHeight];
            setBuffer(pixels);
            return new RV32BufferFormat(sourceWidth, sourceHeight);
        }

        @Override public void allocatedBuffers(java.nio.ByteBuffer[] buffers) {
            // The int[] path needs nothing here.
        }

        @Override protected void onDisplay(MediaPlayer p, int[] data) {
            BufferedImage shot = scaled(data, width, height);
            if (shot == null) return;
            frame = shot;
            if (seq.incrementAndGet() > wantSeq.get() && sink != null)
                complete(shot);
        }

        private BufferedImage scaled(int[] data, int w, int h) {
            if (w <= 0 || h <= 0 || data.length < w * h) return null;
            BufferedImage view = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            view.getRaster().setDataElements(0, 0, w, h, data);
            int tw = Math.min(PREVIEW_WIDTH, w);
            int th = Math.max(1, Math.round(h * (tw / (float) w)));
            BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(view, 0, 0, tw, th, null);
            } finally {
                g.dispose();
            }
            return out;
        }
    }
}
