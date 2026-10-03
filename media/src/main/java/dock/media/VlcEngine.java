package dock.media;

import com.sun.jna.NativeLibrary;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.JComponent;
import uk.co.caprica.vlcj.media.AudioTrackInfo;
import uk.co.caprica.vlcj.media.VideoTrackInfo;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent;
import uk.co.caprica.vlcj.player.component.callback.ScaledCallbackImagePainter;

/**
 * The libVLC engine (vlcj): plays every container and codec the runtime
 * knows, renders frames into a plain Swing surface (so the video composites
 * with FlatLaf and paints into screenshots — no native-window overlay), and
 * reports state through {@link MediaEngine.Listener}.
 *
 * <p><b>Native discovery</b> — batteries included: the app ships the
 * official VLC runtime in an {@code app/vlc/} directory, found from the
 * working directory (Gradle {@code run}, jpackage app dir) or beside the
 * classpath; a {@code dock.vlc} property or {@code DOCK_VLC} environment
 * variable overrides, and a system install is the last resort. The plugin
 * tree sits next to {@code libvlc.dll}, which is where libVLC looks for it
 * anyway (portable-VLC layout), so no environment surgery is needed.
 */
final class VlcEngine implements MediaEngine {

    private static volatile Path home;

    private final CallbackMediaPlayerComponent component;
    private final RecordingImagePainter imagePainter = new RecordingImagePainter();
    private MediaEngine.Listener listener = MediaEngine.Listener.NOTHING;
    private volatile MediaEngine.Overlay overlay;

    /** Where JNA should look for the VLC libraries before anything loads. */
    private static synchronized Path discover() {
        Path found = home;
        if (found != null) return found;
        for (Path candidate : candidates()) {
            if (Files.isRegularFile(candidate.resolve("libvlc.dll"))) {
                // Register before the first LibVLC class initialization;
                // JNA resolves "vlc"/"libvlc" against these paths, trying
                // the lib-prefixed spelling on Windows.
                NativeLibrary.addSearchPath("vlc", candidate.toString());
                NativeLibrary.addSearchPath("libvlc", candidate.toString());
                home = found = candidate;
                return found;
            }
        }
        home = Path.of("");   // negative result caches too — probing is not free
        return Path.of("");
    }

    private static List<Path> candidates() {
        java.util.ArrayList<Path> out = new java.util.ArrayList<>();
        String explicit = System.getProperty("dock.vlc");
        if (explicit != null && !explicit.isBlank()) out.add(Path.of(explicit));
        String env = System.getenv("DOCK_VLC");
        if (env != null && !env.isBlank()) out.add(Path.of(env));
        // The bundled copy: the working directory (Gradle run lands in
        // app/, the packaged app in its install dir) and its parents cover
        // every launch shape, and the classpath location covers IDE runs.
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            out.add(p.resolve("vlc"));
            if (out.size() > 8) break;   // don't walk to the drive root
        }
        out.add(codeLocation().resolve("vlc"));
        String pf = System.getenv("ProgramFiles");
        if (pf != null) out.add(Path.of(pf, "VideoLAN", "VLC"));
        String pf86 = System.getenv("ProgramFiles(x86)");
        if (pf86 != null) out.add(Path.of(pf86, "VideoLAN", "VLC"));
        return out;
    }

    private static Path codeLocation() {
        try {
            var loc = MethodHandles.lookup().lookupClass()
                    .getProtectionDomain().getCodeSource().getLocation();
            return Path.of(loc.toURI()).getParent();
        } catch (Exception e) {
            return Path.of(".");
        }
    }

    /** True when a VLC runtime was found; the factory returns null if not. */
    static boolean available() {
        Path dir = discover();
        return !dir.toString().isEmpty();
    }

    static final class Factory implements MediaEngine.Factory {
        @Override public MediaEngine create() {
            if (!available()) return null;
            // Constructing the component loads the natives and may fail on
            // a broken runtime — that is a missing engine, not a crash. The
            // load happens here, off the EDT by contract (the panel's load
            // thread calls the factory).
            try {
                return new VlcEngine();
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private VlcEngine() {
        // onPaintOverlay runs inside the video surface's own paint, right
        // after the frame — the one place layered UI composites atomically
        // with a surface that repaints itself every frame. The graphics it
        // receives still carries the image painter's letterbox transform,
        // so the hook first maps it back to component space.
        component = new CallbackMediaPlayerComponent() {
            @Override protected void onPaintOverlay(Graphics2D g) {
                MediaEngine.Overlay painter = overlay;
                if (painter == null) return;
                JComponent video = videoSurfaceComponent();
                imagePainter.undoLetterbox(g, video);
                int w = video.getWidth(), h = video.getHeight();
                int[] movie = imagePainter.fittedBox(w, h);
                painter.paint(g, w, h,
                        movie == null ? w : movie[0],
                        movie == null ? h : movie[1]);
            }
        };
        component.setImagePainter(imagePainter);
        component.mediaPlayer().events().addMediaPlayerEventListener(
                new MediaPlayerEventAdapter() {
                    @Override public void playing(MediaPlayer p) { listener.playing(); }
                    @Override public void paused(MediaPlayer p) { listener.paused(); }
                    @Override public void finished(MediaPlayer p) { listener.finished(); }
                    @Override public void error(MediaPlayer p) {
                        listener.error("the engine could not play this file");
                    }
                    @Override public void timeChanged(MediaPlayer p, long t) {
                        listener.time(t);
                    }
                    @Override public void lengthChanged(MediaPlayer p, long l) {
                        listener.duration(l);
                    }
                    @Override public void buffering(MediaPlayer p, float cache) {
                        listener.buffering(cache);
                    }
                });
    }

    @Override public JComponent surface() { return component; }

    @Override public void setListener(MediaEngine.Listener listener) {
        this.listener = listener == null ? MediaEngine.Listener.NOTHING : listener;
    }

    @Override public void setOverlay(MediaEngine.Overlay overlay) {
        this.overlay = overlay;
    }

    @Override public void open(String url) {
        // network-caching buys the source's round-trip latency — and it also
        // prices every seek, since the engine re-fills that much stream
        // before resuming. 600ms suits the loopback bridge (a new range is
        // answered instantly) while still absorbing a remote hiccup.
        component.mediaPlayer().media().play(url, ":network-caching=600");
    }

    @Override public void play() { component.mediaPlayer().controls().play(); }
    @Override public void pause() { component.mediaPlayer().controls().setPause(true); }

    @Override public void stop() {
        // stop() drains synchronously — never on the EDT.
        component.mediaPlayer().controls().stop();
    }

    @Override public void seekMs(long ms) {
        component.mediaPlayer().controls().setTime(Math.max(0, ms));
    }

    @Override public long timeMs() { return component.mediaPlayer().status().time(); }
    @Override public long durationMs() { return component.mediaPlayer().status().length(); }
    @Override public boolean isPlaying() {
        return component.mediaPlayer().status().isPlaying();
    }

    // VLC's own scale tops at 200; the seam's 100 is its nominal level.
    @Override public void setVolume(int percent) {
        component.mediaPlayer().audio().setVolume(Math.clamp(percent, 0, 100));
    }

    @Override public void setMute(boolean mute) {
        component.mediaPlayer().audio().setMute(mute);
    }

    /** The demuxer's own track list — fourccs and pixel size for the
     *  footer strip. Empty until the engine has actually probed the file
     *  (a moment into playback), null-checked because callers poll. */
    @Override public Info info() {
        try {
            var api = component.mediaPlayer().media().info();
            if (api == null) return null;
            List<VideoTrackInfo> video = api.videoTracks();
            List<AudioTrackInfo> audio = api.audioTracks();
            if (video.isEmpty() && audio.isEmpty()) return null;
            return new Info(
                    video.isEmpty() ? null : video.get(0).codecName(),
                    audio.isEmpty() ? null : audio.get(0).codecName(),
                    video.isEmpty() ? 0 : video.get(0).width(),
                    video.isEmpty() ? 0 : video.get(0).height());
        } catch (Throwable t) {
            // Asked too early, or a native hiccup — a missing footer line,
            // not a dead player.
            return null;
        }
    }

    @Override public void loadSubtitle(File file) {
        // addSlave attaches and auto-selects the track; -1 deselects —
        // the off step of the panel's sidecar cycle.
        if (file == null) component.mediaPlayer().subpictures().setTrack(-1);
        else component.mediaPlayer().subpictures().setSubTitleFile(file);
    }

    @Override public void close() {
        component.release();
    }

    /**
     * Delegates to vlcj's {@link ScaledCallbackImagePainter} while
     * remembering the frame's native size, so the overlay hook can undo
     * the letterbox transform that painter leaves behind: it translates
     * the graphics to the aspect-fitted video's top-left and scales it
     * into video-pixel space, and {@code onPaintOverlay} inherits that
     * transform — an overlay centered there lands off-center by the
     * letterbox margins and mis-sized by the scale factor.
     */
    static final class RecordingImagePainter extends ScaledCallbackImagePainter {
        private volatile int imageWidth;
        private volatile int imageHeight;

        @Override public void paint(Graphics2D g, JComponent component,
                                    BufferedImage image) {
            imageWidth = image == null ? 0 : image.getWidth();
            imageHeight = image == null ? 0 : image.getHeight();
            super.paint(g, component, image);
        }

        /** Composes out exactly the translate+scale the scaled painter
         *  applied, putting {@code g} back in the video area's component
         *  space. A no-op before the first frame (no transform applied
         *  then, and no sizes recorded). */
        void undoLetterbox(Graphics2D g, JComponent video) {
            int iw = imageWidth, ih = imageHeight;
            int w = video.getWidth(), h = video.getHeight();
            if (iw <= 0 || ih <= 0 || w <= 0 || h <= 0) return;
            float scale = Math.min(w / (float) iw, h / (float) ih);
            if (scale != 1f) g.scale(1f / scale, 1f / scale);
            g.translate(-(w - iw * scale) / 2f, -(h - ih * scale) / 2f);
        }

        /** The picture's fitted box in component pixels — the frame under
         *  the same aspect-fit the painter applies. This is what overlays
         *  should size against: the movie, letterbox bars excluded. Null
         *  before the first frame (audio-only files never record one). */
        int[] fittedBox(int w, int h) {
            if (imageWidth <= 0 || imageHeight <= 0 || w <= 0 || h <= 0)
                return null;
            float sf = Math.min(w / (float) imageWidth, h / (float) imageHeight);
            return new int[] {
                    Math.round(imageWidth * sf), Math.round(imageHeight * sf)};
        }
    }
}
