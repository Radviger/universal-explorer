package dock.media;

import dock.core.config.AppPaths;
import dock.core.fs.FileSystem;
import dock.kit.FontRegistry;
import dock.kit.Fmt;
import dock.kit.Glyphs;
import dock.kit.Tokens;
import dock.kit.Toast;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Composite;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLayer;
import javax.swing.JLayeredPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JToolTip;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.plaf.LayerUI;

/**
 * The in-pane streaming player. The video fills the pane with the transport
 * floating over it — play, seek, volume on a bar that fades away after a
 * moment of idle playback, web-player style — and a footer strip beneath
 * carries the facts: file name, codecs, resolution, duration, size. The file
 * itself is never downloaded: it is served on the loopback {@link MediaBridge}
 * and opened by URL, so seeking costs one ranged request and only the bytes
 * behind the play head ever move.
 *
 * <p>Playback rides the {@link MediaEngine} seam — libVLC in production, a
 * recording fake in tests, which is also why every state transition here is
 * an EDT event and never a native observation. A generation counter drops
 * results made stale by rapid prev/next, exactly like the image viewer.
 * Prev/next walk the live {@link #sequence} (the pane's media files in
 * display order, video and audio together), and every played file is
 * reported through {@link #onNavigate} so the pane's cursor follows.
 * Subtitle sidecars found beside the movie ({@code film.en.srt} and kin)
 * are fetched to temp files and cycled off → each → off by the CC button
 * or the C key.
 *
 * <p>The controls survive the video's frame-rate repaints only because
 * {@link #claim} marks the engine's whole tree non-opaque: a video repaint
 * then walks up to this panel's opaque {@link Surface} as paint root and
 * repaints the entire subtree in Z-order, so the bar paints after the
 * footage every frame. An opaque video would repaint alone and clobber
 * anything above it — the flicker the click flash's paint hook fixed.
 */
public final class PlayerPanel extends JPanel {

    private static final long SEEK_SMALL_MS = 10_000;
    private static final long SEEK_BIG_MS = 60_000;
    private static final int VOLUME_STEP = 5;
    /** Fade steps at 30ms each — roughly half a second of afterglow. */
    private static final int FADE_TICKS = 16;
    /** The double-tap seek zones: the outer share of the video on each
     *  side. Everything between them toggles playback on a plain click. */
    private static final float SEEK_ZONE = 0.3f;

    /** A remembered position is only worth an offer between these bounds:
     *  under the floor it's a cold start anyway, past the tail the
     *  credits are rolling. */
    private static final long RESUME_FLOOR_MS = 30_000;
    private static final long RESUME_TAIL_MS = 60_000;
    /** This close to the end counts as watched through — remembered as
     *  nothing, so a finished film starts fresh next time. */
    private static final long REMEMBER_END_MS = 10_000;

    /** Idle playback before the controls fade off the video. */
    private static final int CONTROLS_HIDE_MS = 2500;
    /** One 30ms tick of the controls fade — ten steps ≈ a third of a second. */
    private static final float CONTROLS_FADE_STEP = 0.1f;

    /** Fixed white-ish ink for everything floating on the video — the well
     *  is near-black in both themes, and either theme's own foreground
     *  could vanish against bright footage (the click flash's precedent). */
    private static final Color OVERLAY_INK = new Color(232, 232, 232);
    /** The glass the controls bar lays over the footage behind it. */
    private static final Color SCRIM = new Color(10, 10, 12, 168);

    private static final Cursor BLANK_CURSOR = Toolkit.getDefaultToolkit()
            .createCustomCursor(
                    new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
                    new Point(0, 0), "blank");
    private static final Cursor POINT_CURSOR =
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);

    /** Package-visible so tests can assert the playback state machine. */
    enum State { LOADING, BUFFERING, PLAYING, PAUSED, REFUSED }

    private static volatile MediaEngine.Factory engineFactory = new VlcEngine.Factory();
    private static volatile MediaPreview.Factory previewFactory = new VlcPreviewEngine.Factory();
    private static volatile int hideDelayForTest;
    private static volatile ResumeAsk resumeAsk = ResumeAsk.DIALOG;

    private final FileSystem fs;
    private final String dir;
    private final Supplier<List<String>> sequence;
    private final Consumer<String> onNavigate;
    private final Runnable onClose;
    /** The bookmark store — the session file's playback memory, or null
     *  to not remember (tests, memory-less hosts). */
    private final PlaybackMemory memory;

    /** One shared activity handler: the surface hands it out recursively,
     *  because the real click lands deep inside the engine's component
     *  tree (vlcj nests its video surface one panel down). Every press
     *  also parks keyboard focus on the surface — the video tree is kept
     *  non-focusable, and without this the keys (space, arrows, M) would
     *  only work in the lucky window where the mount-time focus request
     *  happened to stick — and every touch wakes the fading controls. */
    private final MouseAdapter clickToggle = new MouseAdapter() {
        @Override public void mousePressed(MouseEvent e) {
            wake();
            surface.requestFocusInWindow();
        }

        @Override public void mouseClicked(MouseEvent e) { handleClick(e); }

        @Override public void mouseMoved(MouseEvent e) { wake(); }
    };

    private final ControlsFade controlsFade = new ControlsFade();
    private final JLayer<JComponent> controlsLayer;
    private final Stage stage = new Stage();
    private final Surface surface = new Surface();

    private final JLabel nameLabel = new JLabel();
    private final JLabel infoLabel = new JLabel();
    private final JButton playPauseButton = new JButton();
    private final JButton muteButton = new JButton();
    private final JButton ccButton = new JButton();
    private final ScrubBar seek = new ScrubBar();
    private final JSlider volume = new JSlider(0, 100);
    private final JLabel nowLabel = new JLabel("0:00", JLabel.RIGHT);
    private final JLabel totalLabel = new JLabel("0:00", JLabel.LEFT);

    /** Scrub-tip frames, bucketed to five seconds and LRU-capped — a
     *  176px frame costs ~50KB, the cap a couple of mebibytes. */
    private static final int PREVIEW_CAP = 24;
    private static final long PREVIEW_BUCKET_MS = 5_000;
    private final LinkedHashMap<Long, BufferedImage> previewCache =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(
                        Map.Entry<Long, BufferedImage> eldest) {
                    return size() > PREVIEW_CAP;
                }
            };
    /** The bucket being fetched right now — an EDT field, one flight at
     *  a time; the next hover retries whatever the flight missed. */
    private long previewInFlight = -1;
    /** One preview engine and one bridge token per panel; the token
     *  rotates with the file, the engine lives as long as the panel. */
    private volatile MediaPreview preview;
    private volatile MediaBridge.Registration previewReg;
    private volatile String previewUrl;

    /** Subtitle sidecars found beside the current file, cycle order —
     *  empty means no CC affordance at all. EDT field. */
    private final List<String> sidecars = new ArrayList<>();
    /** The cycle position: -1 off, else an index into {@link #sidecars}. */
    private int subtitleIndex = -1;
    /** Fetched sidecars as local temp files, by name — one fetch per name,
     *  re-cycling to it is instant. The map owns their deletion. */
    private final Map<String, File> subtitleFiles = new LinkedHashMap<>();
    /** Names with a fetch in flight, so a quick cycle-back never double-
     *  fetches (and never leaks a temp the map no longer tracks). */
    private final Set<String> subtitleFetching = new HashSet<>();

    /** Read from the load thread and the EDT alike — the fields the tests
     *  also poll from their own threads. */
    private volatile String current;
    private volatile State state = State.LOADING;
    private volatile String footerText = "";
    private volatile String infoText = "";
    private String refusal = "";

    /** Reused across files — one engine, one native pipeline per panel. */
    private volatile MediaEngine engine;
    private volatile MediaBridge.Registration registration;
    private volatile MediaEngine.Info info;
    private long durationMs;
    private long sizeBytes = -1;
    private long mtimeMs;
    /** A remembered position for the file being opened, offered once its
     *  duration lands. */
    private Long pendingResume;
    private boolean muted;
    private int volumePercent = 100;
    private boolean released;

    /** Guards the seek slider against fighting its own programmatic updates. */
    private boolean updatingSeek;
    private Timer ticker;
    private final AtomicLong generation = new AtomicLong();

    /** The click feedback: the glyph of the state just entered, fading out
     *  over the video. Null glyph means no flash is showing. */
    private String flashGlyph;
    private float flashAlpha;
    private int flashStep;
    private Timer fader;

    /** The controls' fade: target alpha the ticker walks toward, 30ms at a
     *  time. 0 = gone (and the layer made invisible so clicks pass through
     *  to the video beneath), 1 = fully lit. */
    private float controlsTarget;
    private final Timer hideTimer = new Timer(CONTROLS_HIDE_MS, e -> autoHide());
    private final Timer controlsFader = new Timer(30, e -> stepControlsFade());

    /** A zone click's play/pause, held until the double-click window
     *  closes — a second click in the window is a seek, not a toggle. */
    private Timer zoneToggle;

    public PlayerPanel(FileSystem fs, String dir, String firstName,
                       Supplier<List<String>> sequence,
                       Consumer<String> onNavigate, Runnable onClose,
                       PlaybackMemory memory) {
        super(new BorderLayout());
        this.fs = fs;
        this.dir = dir;
        this.sequence = sequence;
        this.onNavigate = onNavigate;
        this.onClose = onClose;
        this.memory = memory;
        controlsLayer = new JLayer<>(buildControlsBar(), controlsFade);
        controlsLayer.setPreferredSize(new Dimension(
                10, controlsLayer.getPreferredSize().height));
        stage.add(controlsLayer, JLayeredPane.PALETTE_LAYER);
        add(surface, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        load(firstName);
    }

    // ---- chrome ----

    /** The floating transport: play/prev/next, the seek row, volume — on a
     *  dark scrim across the bottom of the footage. */
    private JComponent buildControlsBar() {
        JPanel bar = new JPanel(new BorderLayout(Tokens.GAP_3, 0)) {
            @Override protected void paintComponent(Graphics g) {
                // The scrim: footage keeps moving behind translucent glass.
                g.setColor(SCRIM);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(
                Tokens.GAP_1, Tokens.GAP_3, Tokens.GAP_1, Tokens.GAP_3));

        JPanel transport = new JPanel();
        transport.setLayout(new BoxLayout(transport, BoxLayout.X_AXIS));
        transport.setOpaque(false);
        transport.add(button(Glyphs.CHEVRON_LEFT, "Previous (Page Up)", this::previous));
        configure(playPauseButton, Glyphs.PLAY, "Play (Space)", this::playPause);
        transport.add(playPauseButton);
        transport.add(button(Glyphs.CHEVRON_RIGHT, "Next (Page Down)", this::next));
        bar.add(transport, BorderLayout.WEST);

        seek.setOpaque(false);
        seek.setFocusable(false);   // player keys live on the surface alone
        seek.setForeground(OVERLAY_INK);
        // JSlider(min,max) is born at the midpoint — for a seek bar that
        // would read as half-played. Start at the top of the file.
        seek.setValue(0);
        seek.addChangeListener(e -> {
            if (seek.getValueIsAdjusting()) wake();   // a live drag stays lit
            if (updatingSeek || seek.getValueIsAdjusting()) return;
            if (engine != null && durationMs > 0) {
                engine.seekMs(Math.round(seek.getValue() / 1000.0 * durationMs));
            }
        });
        nowLabel.setFont(FontRegistry.mono());
        nowLabel.setForeground(OVERLAY_INK);
        totalLabel.setFont(FontRegistry.mono());
        totalLabel.setForeground(OVERLAY_INK);
        JPanel seekRow = new JPanel(new BorderLayout(Tokens.GAP_2, 0));
        seekRow.setOpaque(false);
        seekRow.add(nowLabel, BorderLayout.WEST);
        seekRow.add(seek, BorderLayout.CENTER);
        seekRow.add(totalLabel, BorderLayout.EAST);
        bar.add(seekRow, BorderLayout.CENTER);

        JPanel output = new JPanel();
        output.setLayout(new BoxLayout(output, BoxLayout.X_AXIS));
        output.setOpaque(false);
        volume.setOpaque(false);
        volume.setFocusable(false);   // player keys live on the surface alone
        volume.setForeground(OVERLAY_INK);
        volume.setValue(volumePercent);   // JSlider(min,max) is born at 50
        volume.setPreferredSize(new Dimension(88, 24));
        volume.setMaximumSize(new Dimension(88, 24));
        volume.setToolTipText("Volume (↑/↓)");
        volume.addChangeListener(e -> {
            if (volume.getValueIsAdjusting()) wake();
            volumePercent = volume.getValue();
            if (engine != null) engine.setVolume(volumePercent);
        });
        output.add(volume);
        output.add(Box.createHorizontalStrut(Tokens.GAP_1));
        configure(muteButton, Glyphs.VOLUME_HIGH, "Mute (M)", this::toggleMute);
        output.add(muteButton);
        configure(ccButton, Glyphs.CLOSED_CAPTION, "Subtitles (C)", this::cycleSubtitle);
        ccButton.setVisible(false);   // no sidecars beside this file — or not yet
        output.add(ccButton);
        output.add(button(Glyphs.CLOSE, "Close (Esc)", this::close));
        bar.add(output, BorderLayout.EAST);
        return bar;
    }

    /** The fact strip under the video: name and state left, codecs,
     *  resolution, duration and size right. */
    private JComponent buildFooter() {
        nameLabel.setFont(FontRegistry.mono());
        nameLabel.setForeground(muted());
        infoLabel.setFont(FontRegistry.mono());
        infoLabel.setForeground(muted());
        JPanel row = new JPanel(new BorderLayout(Tokens.GAP_3, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0,
                        UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(
                        Tokens.GAP_1, Tokens.GAP_3, Tokens.GAP_1, Tokens.GAP_3)));
        row.add(nameLabel, BorderLayout.WEST);
        row.add(infoLabel, BorderLayout.EAST);
        return row;
    }

    private void configure(JButton b, String glyph, String tooltip, Runnable action) {
        b.setIcon(Glyphs.iconUniform(glyph, Tokens.ICON_LARGE, () -> OVERLAY_INK));
        b.setToolTipText(tooltip);
        b.putClientProperty("JButton.buttonType", "borderless");
        b.setRolloverEnabled(true);
        b.setFocusable(false);   // player keys live on the surface alone
        b.setPreferredSize(new Dimension(30, 30));
        b.setMaximumSize(new Dimension(30, 30));
        b.addActionListener(e -> {
            wake();
            action.run();
        });
    }

    private JButton button(String glyph, String tooltip, Runnable action) {
        JButton b = new JButton();
        configure(b, glyph, tooltip, action);
        return b;
    }

    private static Color muted() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : UIManager.getColor("Label.foreground");
    }

    /** Reflects the state machine onto every chrome affordance at once. */
    private void updateChrome() {
        boolean active = state == State.PLAYING || state == State.BUFFERING;
        playPauseButton.setIcon(Glyphs.iconUniform(
                active ? Glyphs.PAUSE : Glyphs.PLAY,
                Tokens.ICON_LARGE, () -> OVERLAY_INK));
        muteButton.setIcon(Glyphs.iconUniform(
                muted ? Glyphs.VOLUME_OFF : Glyphs.VOLUME_HIGH,
                Tokens.ICON_LARGE, () -> OVERLAY_INK));
        String suffix = switch (state) {
            case LOADING -> " · streaming…";
            case BUFFERING -> " · buffering…";
            case PAUSED -> " · paused";
            case REFUSED -> " · stopped";
            case PLAYING -> "";
        };
        footerText = (current == null ? "" : current) + suffix;
        nameLabel.setText(footerText);
        infoText = assembleInfo();
        infoLabel.setText(infoText);
        nowLabel.setText(clock(engine == null ? 0 : Math.max(0, engine.timeMs())));
        totalLabel.setText(durationMs > 0 ? clock(durationMs) : "0:00");
    }

    /** Every part of the footer's right side is optional until it is
     *  known: codecs need the engine's probe, duration needs the engine's
     *  clock, size is there from the first stat. */
    private String assembleInfo() {
        List<String> parts = new java.util.ArrayList<>();
        if (info != null) {
            String v = codec(info.videoCodec()), a = codec(info.audioCodec());
            if (v != null && a != null) parts.add(v + "/" + a);
            else if (v != null) parts.add(v);
            else if (a != null) parts.add(a);
            if (info.width() > 0 && info.height() > 0)
                parts.add(info.width() + "×" + info.height());
        }
        if (durationMs > 0) parts.add(clock(durationMs));
        if (sizeBytes > 0) parts.add(Fmt.bytes(sizeBytes));
        return String.join(" · ", parts);
    }

    /** The demuxer's fourcc, uppercased for the strip ("h264" → "H264"). */
    private static String codec(String fourcc) {
        return fourcc == null || fourcc.isBlank()
                ? null : fourcc.trim().toUpperCase(Locale.ROOT);
    }

    // ---- controls visibility ----

    /** Lights the controls and restarts the idle clock. Any activity calls
     *  this — motion, clicks, keys, drags, state changes; while paused or
     *  buffering the idle clock's verdict is simply "not now". */
    private void wake() {
        if (state == State.LOADING || state == State.REFUSED) return;
        controlsLayer.setVisible(true);
        controlsTarget = 1f;
        if (controlsFade.alpha() < 1f) {
            controlsFader.restart();
        }
        hideTimer.setDelay(hideDelayMs());
        hideTimer.setInitialDelay(hideDelayMs());
        hideTimer.restart();
        applyCursor();
    }

    private static int hideDelayMs() {
        int override = hideDelayForTest;
        return override > 0 ? override : CONTROLS_HIDE_MS;
    }

    /** The idle clock ran out: fade the bar off the video — unless
     *  playback has stopped, a thumb is mid-drag, or the pointer rests on
     *  the bar itself. */
    private void autoHide() {
        if (state != State.PLAYING) {
            hideTimer.stop();   // re-armed by the next wake
            return;
        }
        if (seek.getValueIsAdjusting() || volume.getValueIsAdjusting()
                || pointerOverControls()) {
            hideTimer.restart();
            return;
        }
        hideTimer.stop();
        controlsTarget = 0f;
        controlsFader.restart();
    }

    private void stepControlsFade() {
        float a = controlsFade.alpha();
        if (a == controlsTarget) {
            controlsFader.stop();
            if (a == 0f) controlsLayer.setVisible(false);
            applyCursor();
            return;
        }
        float step = CONTROLS_FADE_STEP * (controlsTarget > a ? 1f : -1f);
        controlsFade.setAlpha(Math.clamp(a + step, 0f, 1f));
    }

    /** LOADING and REFUSED carry no transport to hide behind: the bar goes
     *  out at once, no afterglow over a message pane. */
    private void hideControlsImmediately() {
        hideTimer.stop();
        controlsFader.stop();
        controlsTarget = 0f;
        controlsFade.setAlpha(0f);
        controlsLayer.setVisible(false);
        applyCursor();
    }

    private boolean pointerOverControls() {
        if (!controlsLayer.isVisible()) return false;
        Point p = stage.getMousePosition();   // null when outside the window
        return p != null && controlsLayer.getBounds().contains(p);
    }

    /** The pointer vanishes with the controls during playback, as every
     *  full-screen player does; any other state keeps the pointing hand. */
    private void applyCursor() {
        Cursor c = !controlsLayer.isVisible() && state == State.PLAYING
                ? BLANK_CURSOR : POINT_CURSOR;
        surface.setCursor(c);
        stage.setCursor(c);
    }

    // ---- scrub previews ----

    /** The preview frame for a hover time, EDT-side: the cached frame, or
     *  null with a fetch underway — the tip grows its image row when the
     *  fetch lands and the hover nudge re-runs the pipeline. */
    BufferedImage previewFor(long timeMs) {
        if (durationMs <= 0 || released) return null;
        long bucket = timeMs / PREVIEW_BUCKET_MS * PREVIEW_BUCKET_MS;
        BufferedImage hit = previewCache.get(bucket);
        if (hit != null) return hit;
        fetchPreview(bucket);
        return null;
    }

    private void fetchPreview(long bucket) {
        if (previewInFlight >= 0) return;
        previewInFlight = bucket;
        FileSystem files = fs;
        String path = fs.child(dir, current == null ? "" : current);
        long gen = generation.get();
        Thread.ofVirtual().name("dock-media-preview").start(() -> {
            try {
                MediaPreview source = preview;
                if (source == null) source = preview = previewFactory.create();
                if (source == null) {
                    SwingUtilities.invokeLater(PlayerPanel.this::previewDone);
                    return;
                }
                // The preview's own token: its own remote stream, so a
                // preview seek never serializes behind playback reads.
                MediaBridge.Registration reg = previewReg;
                if (reg == null) reg = previewReg = MediaBridge.serve(files, path);
                String url = reg.url();
                if (!url.equals(previewUrl)) {
                    previewUrl = url;
                    source.open(url);
                }
                source.request(bucket, image -> SwingUtilities.invokeLater(
                        () -> previewLanded(bucket, gen, image)));
            } catch (Exception e) {
                SwingUtilities.invokeLater(this::previewDone);
            }
        });
    }

    private void previewLanded(long bucket, long gen, BufferedImage image) {
        previewDone();
        if (image != null && gen == generation.get() && !released) {
            previewCache.put(bucket, image);
            seek.nudgeTip();
        }
    }

    /** A fetch came back empty: the tip stays textual, the next hover
     *  retries. */
    private void previewDone() {
        previewInFlight = -1;
    }

    // ---- playback memory ----

    /** Where this file stood, into the session file — on its own thread,
     *  the store is disk I/O. The engine's clock is read here, on the
     *  EDT, exactly as the ticker reads it. */
    private void rememberPlaybackOf(String name, long mtime) {
        if (memory == null || name == null || durationMs <= 0) return;
        PlaybackMemory store = memory;
        String path = fs.child(dir, name);
        long duration = durationMs;
        long pos = Math.max(0, engine == null ? 0 : engine.timeMs());
        long value = pos >= duration - REMEMBER_END_MS ? 0 : pos;
        Thread.ofVirtual().name("dock-media-memory").start(() ->
                store.record(path, mtime, value));
    }

    /** Offers a remembered position once the duration makes the window
     *  meaningful: between half a minute in and a minute before the end.
     *  One shot per file — the answer, either way, is final. */
    private void maybeOfferResume() {
        Long remembered = pendingResume;
        pendingResume = null;
        if (remembered == null || durationMs <= 0 || released) return;
        if (remembered < RESUME_FLOOR_MS || remembered > durationMs - RESUME_TAIL_MS)
            return;
        boolean yes = resumeAsk.ask(this, current, clock(remembered));
        if (yes && engine != null && !released) engine.seekMs(remembered);
    }

    /** Asks whether to resume a remembered position; injectable so tests
     *  answer deterministically. */
    public interface ResumeAsk {
        boolean ask(java.awt.Component parent, String fileName, String atClock);

        ResumeAsk DIALOG = (parent, file, at) ->
                javax.swing.JOptionPane.showConfirmDialog(parent,
                        "Resume playback of " + file + " from " + at + "?",
                        "Resume", javax.swing.JOptionPane.YES_NO_OPTION,
                        javax.swing.JOptionPane.QUESTION_MESSAGE)
                        == javax.swing.JOptionPane.YES_OPTION;
    }

    // ---- subtitles ----

    /** Swaps the sidecar cycle for the file being opened: fresh list,
     *  cycle parked off, the previous file's temps deleted. The engine
     *  side needs nothing — a new media starts with its own track set. */
    private void applySidecars(List<String> names) {
        sidecars.clear();
        sidecars.addAll(names);
        subtitleIndex = -1;
        ccButton.setVisible(!sidecars.isEmpty());
        ccButton.setToolTipText("Subtitles (C)");
        discardSubtitleTemps();
    }

    /** The CC button and the C key: off → first sidecar → … → off. */
    private void cycleSubtitle() {
        if (sidecars.isEmpty() || engine == null
                || state == State.LOADING || state == State.REFUSED) return;
        subtitleIndex = subtitleIndex + 1 >= sidecars.size() ? -1 : subtitleIndex + 1;
        applySubtitle();
    }

    private void applySubtitle() {
        if (subtitleIndex < 0) {
            ccButton.setToolTipText("Subtitles: off (C)");
            if (engine != null) engine.loadSubtitle(null);
            return;
        }
        String name = sidecars.get(subtitleIndex);
        ccButton.setToolTipText("Subtitles: " + name);
        File local = subtitleFiles.get(name);
        if (local != null) {
            engine.loadSubtitle(local);
            return;
        }
        if (!subtitleFetching.add(name)) return;   // already coming
        long gen = generation.get();
        String path = fs.child(dir, name);
        Thread.ofVirtual().name("dock-media-subs").start(() ->
                sidecarLanded(name, gen, fetchSidecar(path, name)));
    }

    /** One sidecar to a temp file in the app cache — a subtitle is small,
     *  and VLC wants a local file it can re-read across seeks. */
    private File fetchSidecar(String path, String name) {
        int dot = name.lastIndexOf('.');
        String suffix = dot < 0 ? ".srt" : name.substring(dot);
        try (InputStream in = fs.streamView().read(path)) {
            Path temp = Files.createTempFile(AppPaths.cache(), "dock-sub-", suffix);
            try (OutputStream out = Files.newOutputStream(temp)) {
                in.transferTo(out);
            }
            return temp.toFile();
        } catch (Exception e) {
            return null;
        }
    }

    /** The fetch landed: cache the temp, hand it to the engine if the
     *  selection is still the one that asked for it. */
    private void sidecarLanded(String name, long gen, File fetched) {
        subtitleFetching.remove(name);
        boolean stale = gen != generation.get() || released;
        if (fetched == null || stale) {
            if (fetched != null) deleteQuietly(fetched);
            if (!stale && subtitleIndex >= 0
                    && name.equals(sidecars.get(subtitleIndex))) {
                subtitleIndex = -1;   // the cycle honestly back at off
                ccButton.setToolTipText("Subtitles: off (C)");
                Toast.show(this, "Can't fetch " + name, Glyphs.WARNING);
            }
            return;
        }
        subtitleFiles.put(name, fetched);
        if (subtitleIndex >= 0 && name.equals(sidecars.get(subtitleIndex))
                && engine != null) {
            engine.loadSubtitle(fetched);
        }
    }

    /** Deletes the temps the map owns, off the EDT — disk I/O. */
    private void discardSubtitleTemps() {
        subtitleFetching.clear();
        if (subtitleFiles.isEmpty()) return;
        List<File> doomed = new ArrayList<>(subtitleFiles.values());
        subtitleFiles.clear();
        Thread.ofVirtual().name("dock-media-subs-clean").start(() ->
                doomed.forEach(PlayerPanel::deleteQuietly));
    }

    private static void deleteQuietly(File f) {
        try {
            Files.deleteIfExists(f.toPath());
        } catch (Exception ignored) {
            // A temp that won't die dies with the OS temp-cleaning story.
        }
    }

    // ---- navigation ----

    private void previous() { step(-1); }
    private void next() { step(1); }

    private void step(int delta) {
        List<String> seq = sequence.get();
        if (seq.isEmpty()) return;
        int i = seq.indexOf(current);
        if (i < 0) i = delta > 0 ? -1 : seq.size();   // vanished: resume at the edge
        int j = i + delta;
        if (j < 0 || j >= seq.size()) return;         // clamped at the ends
        load(seq.get(j));
    }

    // ---- loading pipeline ----

    private void load(String name) {
        rememberPlaybackOf(current, mtimeMs);   // the old file's stand, while its clock still runs
        current = name;
        long gen = generation.incrementAndGet();
        stopTicker();
        durationMs = 0;
        info = null;
        sizeBytes = -1;
        pendingResume = null;
        state = State.LOADING;
        // The old file's audio stops the moment the walk moves — the new
        // one re-arms playback in mountEngine.
        if (engine != null) engine.pause();
        MediaBridge.Registration oldRegistration = registration;
        MediaBridge.Registration oldPreviewReg = previewReg;
        registration = null;
        previewReg = null;
        previewInFlight = -1;
        previewCache.clear();
        hideControlsImmediately();
        updateChrome();
        surface.repaint();
        String path = fs.child(dir, name);
        Thread.ofVirtual().name("dock-media").start(() -> {
            // Revoking the previous file's token is remote I/O — off the EDT.
            if (oldRegistration != null) oldRegistration.close();
            if (oldPreviewReg != null) oldPreviewReg.close();
            // The footer's size line and the bookmark's staleness guard:
            // one stat, at the moment of opening.
            long size = -1;
            long mtime = 0;
            try {
                var st = fs.stat(path);
                size = st.size();
                mtime = st.mtimeMillis();
            } catch (Exception e) {
                // Decorative — the bridge refuses unreadable files itself.
            }
            long bytes = size, stamp = mtime;
            // Subtitle sidecars: one listing of the movie's directory,
            // the same cost class as the stat above.
            List<String> found = List.of();
            try {
                found = Sidecars.find(fs.list(dir).stream()
                        .map(dock.core.fs.FileEntry::name).toList(), name);
            } catch (Exception e) {
                // No listing, no sidecars — the movie itself is unaffected.
            }
            List<String> subs = found;
            Long remembered = null;
            if (memory != null) {
                try {
                    remembered = memory.positionOf(path, stamp);
                } catch (Exception e) {
                    // A broken memory is a missing bookmark.
                }
            }
            Long resume = remembered;
            try {
                MediaBridge.Registration reg = MediaBridge.serve(fs, path);
                MediaEngine eng = engine != null ? engine : engineFactory.create();
                SwingUtilities.invokeLater(() -> {
                    if (generation.get() != gen) {
                        reg.close();       // a newer load won the pane
                        return;
                    }
                    sizeBytes = bytes;
                    mtimeMs = stamp;
                    pendingResume = resume;
                    applySidecars(subs);
                    if (eng == null) {
                        reg.close();
                        refuse("no playback engine — the VLC runtime is missing");
                        return;
                    }
                    engine = eng;
                    registration = reg;
                    mountEngine(gen);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    if (generation.get() == gen) refuse(shortError(e));
                });
            }
        });
    }

    /** Wires the (reused) engine to the fresh file and starts playback. */
    private void mountEngine(long gen) {
        engine.setListener(listenerFor(gen));
        if (engine.surface().getParent() != stage) surface.setVideo(engine.surface());
        // The flash rides the engine's own painting: the video surface
        // repaints itself every frame and Swing repaints only that opaque
        // subtree, so anything painted above it flickers. Inside the
        // surface's paint, frame and glyph are atomic.
        engine.setOverlay(this::paintOverlayTo);
        engine.setVolume(volumePercent);
        engine.setMute(muted);
        state = State.BUFFERING;
        updateChrome();
        wake();
        engine.open(registration.url());
        startTicker(gen);
        surface.requestFocusInWindow();
        onNavigate.accept(current);
    }

    /** Events arrive on the engine's threads; stale ones die at the gen. */
    private MediaEngine.Listener listenerFor(long gen) {
        return new MediaEngine.Listener() {
            @Override public void playing() { onEdt(gen, () -> {
                state = State.PLAYING;
                updateChrome();
                wake();
            }); }
            @Override public void paused() { onEdt(gen, () -> {
                state = State.PAUSED;
                updateChrome();
                wake();
            }); }
            @Override public void finished() { onEdt(gen, () -> {
                // End of file parks at the end, one press away from a replay.
                state = State.PAUSED;
                updateChrome();
                wake();
                rememberPlaybackOf(current, mtimeMs);   // watched through
            }); }
            @Override public void error(String message) { onEdt(gen, () ->
                    refuse(message)); }
            @Override public void buffering(float percent) { onEdt(gen, () -> {
                if (state == State.BUFFERING && percent >= 1f) {
                    state = engine != null && engine.isPlaying()
                            ? State.PLAYING : State.PAUSED;
                    updateChrome();
                    wake();
                }
            }); }
            @Override public void duration(long ms) { onEdt(gen, () -> {
                durationMs = ms;
                updateChrome();
                maybeOfferResume();
            }); }
        };
    }

    private void onEdt(long gen, Runnable r) {
        SwingUtilities.invokeLater(() -> {
            if (generation.get() == gen && !released) r.run();
        });
    }

    private void refuse(String reason) {
        state = State.REFUSED;
        refusal = reason;
        stopTicker();
        hideControlsImmediately();
        updateChrome();
        surface.repaint();
        Toast.show(this, "Can't play " + current + ": " + reason, Glyphs.WARNING);
    }

    // ---- transport ----

    private void playPause() {
        if (engine == null || state == State.REFUSED || state == State.LOADING) return;
        boolean pausing = state == State.PLAYING || state == State.BUFFERING;
        flash(pausing ? Glyphs.PAUSE : Glyphs.PLAY);
        if (pausing) engine.pause();
        else engine.play();
    }

    /** Click routing: the center toggles at once; the outer zones toggle
     *  only after the double-click window closes, because a second click
     *  there is a ±10 s skip (the double-tap seek every phone player
     *  has) — an immediate toggle would blip pause/play on every skip. */
    private void handleClick(MouseEvent e) {
        if (e.getClickCount() >= 2) {
            cancelZoneToggle();
            if (zoneSeek(e)) return;
        }
        if (e.getClickCount() == 1 && inSeekZone(e)) scheduleZoneToggle();
        else playPause();
    }

    private static boolean inSeekZone(MouseEvent e) {
        float fx = xFraction(e);
        return fx < SEEK_ZONE || fx > 1f - SEEK_ZONE;
    }

    private static float xFraction(MouseEvent e) {
        int w = e.getComponent().getWidth();
        return w <= 0 ? 0.5f : e.getX() / (float) w;
    }

    /** A double click in a seek zone: skips ±10 s without touching the
     *  play state, flashing the double chevron of the direction. */
    private boolean zoneSeek(MouseEvent e) {
        float fx = xFraction(e);
        if (fx >= SEEK_ZONE && fx <= 1f - SEEK_ZONE) return false;
        boolean back = fx < 0.5f;
        if (engine != null && state != State.REFUSED && state != State.LOADING) {
            flash(back ? Glyphs.CHEVRON_LEFT + Glyphs.CHEVRON_LEFT
                       : Glyphs.CHEVRON_RIGHT + Glyphs.CHEVRON_RIGHT);
            nudge(back ? -SEEK_SMALL_MS : SEEK_SMALL_MS);
        }
        return true;
    }

    private void scheduleZoneToggle() {
        cancelZoneToggle();
        zoneToggle = new Timer(multiClickIntervalMs(), e -> runZoneToggle());
        zoneToggle.setRepeats(false);
        zoneToggle.start();
    }

    private void runZoneToggle() {
        zoneToggle = null;
        playPause();
    }

    private void cancelZoneToggle() {
        if (zoneToggle != null) {
            zoneToggle.stop();
            zoneToggle = null;
        }
    }

    /** The same threshold the OS uses to call two clicks a double click —
     *  with anything shorter, a slow double click would toggle first and
     *  then seek, blipping the audio exactly when nobody asked for it. */
    private static int multiClickIntervalMs() {
        Object v = Toolkit.getDefaultToolkit()
                .getDesktopProperty("awt.multiClickInterval");
        return v instanceof Integer ms && ms > 0 ? ms : 500;
    }

    /** Flashes what a toggle just did: the glyph of the state entered,
     *  fading out over the video. A rapid re-click restarts at full. */
    private void flash(String glyph) {
        flashGlyph = glyph;
        flashStep = 0;
        flashAlpha = 1f;
        if (fader == null) {
            fader = new Timer(30, e -> stepFade());
            fader.start();
        } else {
            fader.restart();
        }
        repaintVideo();
    }

    private void stepFade() {
        if (flashGlyph == null) return;
        flashStep++;
        flashAlpha = 1f - flashStep / (float) FADE_TICKS;
        if (flashStep >= FADE_TICKS) {
            flashGlyph = null;
            flashAlpha = 0f;
            stopFader();
        }
        repaintVideo();
    }

    /** The glyph lives inside the engine's paint, so the fade must dirty
     *  the engine's surface — not this panel, whose repaint alone would
     *  never reach the overlay painter while the video stays idle. */
    private void repaintVideo() {
        MediaEngine eng = engine;
        if (eng != null) eng.surface().repaint();
        else surface.repaint();
    }

    private void stopFader() {
        if (fader != null) {
            fader.stop();
            fader = null;
        }
    }

    private void nudge(long deltaMs) {
        if (engine == null || state == State.REFUSED) return;
        engine.seekMs(Math.clamp(engine.timeMs() + deltaMs, 0,
                durationMs > 0 ? durationMs : Long.MAX_VALUE));
    }

    private void toggleMute() {
        muted = !muted;
        if (engine != null) engine.setMute(muted);
        updateChrome();
    }

    private void nudgeVolume(int delta) {
        volumePercent = Math.clamp(volumePercent + delta, 0, 100);
        volume.setValue(volumePercent);
        if (engine != null) engine.setVolume(volumePercent);
    }

    /** Drives the seek row from the engine's clock four times a second,
     *  and until the probe lands, keeps asking the engine for the
     *  footer's track facts. */
    private void startTicker(long gen) {
        stopTicker();
        ticker = new Timer(250, e -> {
            if (engine == null || released) return;
            long t = Math.max(0, engine.timeMs());
            nowLabel.setText(clock(t));
            if (durationMs > 0 && !seek.getValueIsAdjusting()) {
                updatingSeek = true;
                seek.setValue((int) Math.clamp(
                        Math.round(t * 1000.0 / durationMs), 0, 1000));
                updatingSeek = false;
            }
            if (info == null) pollInfo(gen);
        });
        ticker.start();
    }

    /** Track facts are a native call — asked on their own thread and
     *  applied behind the generation guard, like every async result in
     *  the pane. Repeated until the probe answers something. */
    private void pollInfo(long gen) {
        MediaEngine eng = engine;
        Thread.ofVirtual().name("dock-media-info").start(() -> {
            MediaEngine.Info found = null;
            try {
                found = eng.info();
            } catch (Throwable ignored) {
                // A failed probe just leaves the footer without codecs.
            }
            MediaEngine.Info result = found;
            SwingUtilities.invokeLater(() -> {
                if (result == null || released || generation.get() != gen
                        || info != null) return;
                info = result;
                updateChrome();
            });
        });
    }

    private void stopTicker() {
        if (ticker != null) {
            ticker.stop();
            ticker = null;
        }
    }

    @Override public void removeNotify() {
        // Swapped out of the pane or the tab closed: no timer may keep
        // firing against a dead component.
        stopTicker();
        stopFader();
        cancelZoneToggle();
        hideTimer.stop();
        controlsFader.stop();
        super.removeNotify();
    }

    /** The keyboard landing point after the pane swap. */
    public void focusSurface() {
        surface.requestFocusInWindow();
    }

    void close() {
        onClose.run();
    }

    /**
     * Retires the panel: stops and releases the engine, revokes the
     * bridge token, all off the EDT (native teardown, remote closes).
     * Idempotent — the session view calls this on its way to unmounting.
     */
    public void release() {
        if (released) return;
        rememberPlaybackOf(current, mtimeMs);
        released = true;
        generation.incrementAndGet();   // pending events die here
        stopTicker();
        stopFader();
        cancelZoneToggle();
        hideTimer.stop();
        controlsFader.stop();
        MediaEngine eng = engine;
        engine = null;
        MediaBridge.Registration reg = registration;
        registration = null;
        MediaPreview prev = preview;
        preview = null;
        MediaBridge.Registration prevReg = previewReg;
        previewReg = null;
        discardSubtitleTemps();
        Thread.ofVirtual().name("dock-media-release").start(() -> {
            if (eng != null) {
                try { eng.stop(); } catch (Throwable ignored) {}
                try { eng.close(); } catch (Throwable ignored) {}
            }
            if (reg != null) reg.close();
            if (prev != null) {
                try { prev.close(); } catch (Throwable ignored) {}
            }
            if (prevReg != null) prevReg.close();
        });
    }

    /** Mouse events stop at the deepest component under the cursor — AWT
     *  never bubbles them — and vlcj keeps its actual video surface one
     *  panel down, with its own listeners already on it. Take the whole
     *  tree (and the focus with it, keeping the keys on this surface) or
     *  a click on the playing video reaches nobody. The tree is also made
     *  non-opaque so its frame repaints climb to the surface as paint
     *  root — that is what lets the controls survive every frame (see
     *  the class comment). */
    private void claim(Component c) {
        c.setFocusable(false);
        if (c instanceof JComponent j) j.setOpaque(false);
        c.addMouseListener(clickToggle);
        c.addMouseMotionListener(clickToggle);
        if (c instanceof Container box)
            for (Component child : box.getComponents()) claim(child);
    }

    // ---- surface ----

    /** The video well: letterbox paint, the engine's component inside. */
    final class Surface extends JPanel {
        Surface() {
            super(new BorderLayout());
            setFocusable(true);
            setRequestFocusEnabled(true);
            setCursor(POINT_CURSOR);
            bindKeys();
            // Letterbox clicks land here; clicks on the video itself land
            // inside the engine's tree, claimed by the stage's setVideo.
            addMouseListener(clickToggle);
            addMouseMotionListener(clickToggle);
            add(stage, BorderLayout.CENTER);
        }

        void setVideo(JComponent video) {
            stage.setVideo(video);
            revalidate();
            repaint();
        }

        /** All player keys live on the focused surface so they beat the
         *  ancestor maps — including the pane-hop arrows of the session. */
        private void bindKeys() {
            InputMap im = getInputMap(WHEN_FOCUSED);
            ActionMap am = getActionMap();
            bind(im, am, "ESCAPE", "close", PlayerPanel.this::close);
            bind(im, am, "BACK_SPACE", "close", PlayerPanel.this::close);
            bind(im, am, "SPACE", "play-pause", PlayerPanel.this::playPause);
            bind(im, am, "LEFT", "back-small", () -> nudge(-SEEK_SMALL_MS));
            bind(im, am, "shift LEFT", "back-big", () -> nudge(-SEEK_BIG_MS));
            bind(im, am, "RIGHT", "ahead-small", () -> nudge(SEEK_SMALL_MS));
            bind(im, am, "shift RIGHT", "ahead-big", () -> nudge(SEEK_BIG_MS));
            bind(im, am, "UP", "vol-up", () -> nudgeVolume(VOLUME_STEP));
            bind(im, am, "DOWN", "vol-down", () -> nudgeVolume(-VOLUME_STEP));
            bind(im, am, "typed m", "mute", PlayerPanel.this::toggleMute);
            bind(im, am, "typed c", "subs", PlayerPanel.this::cycleSubtitle);
            bind(im, am, "HOME", "home", () -> { if (engine != null) engine.seekMs(0); });
            bind(im, am, "END", "end", () -> { if (engine != null && durationMs > 0)
                    engine.seekMs(durationMs); });
            bind(im, am, "PAGE_UP", "prev", PlayerPanel.this::previous);
            bind(im, am, "PAGE_DOWN", "next", PlayerPanel.this::next);
        }

        private void bind(InputMap im, ActionMap am, String key, String name, Runnable action) {
            KeyStroke ks = KeyStroke.getKeyStroke(key);
            if (ks == null) throw new IllegalStateException("bad keystroke " + key);
            im.put(ks, name);
            am.put(name, new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    action.run();
                    wake();   // key activity lights the controls, as motion does
                }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            paintWell((Graphics2D) g, this);
        }
    }

    /** The video and its floating controls, layered. Null layout: the
     *  video fills the stage and the controls bar claims the strip along
     *  its bottom, exactly like every web player's overlay. */
    final class Stage extends JLayeredPane {
        private JComponent video;

        Stage() {
            setOpaque(false);
            // The click path when no engine tree covers the stage yet
            // (LOADING, REFUSED) — the deepest component at the point.
            addMouseListener(clickToggle);
            addMouseMotionListener(clickToggle);
        }

        void setVideo(JComponent v) {
            if (video != null) remove(video);
            video = v;
            claim(v);
            add(v, JLayeredPane.DEFAULT_LAYER);
            revalidate();
            repaint();
        }

        @Override public void doLayout() {
            int w = getWidth(), h = getHeight();
            if (video != null) video.setBounds(0, 0, w, h);
            int oh = controlsLayer.getPreferredSize().height;
            controlsLayer.setBounds(0, Math.max(0, h - oh), w, oh);
        }
    }

    /** The seek slider with its scrub tooltip: hovering shows the
     *  timestamp at the pointer — and the preview frame above it once
     *  one has been fetched — the box sliding along x at one fixed
     *  height above the bar. The time under the cursor comes from the
     *  slider's own value-for-position math, so the tip always agrees
     *  with where a click would land. */
    final class ScrubBar extends JSlider {
        /** Reused to measure the tip box before it exists — an unparented
         *  ScrubTip sizes itself from its text and the bar's frame. */
        private ScrubTip measurer;
        /** The pointer's last hover x, for nudging the pipeline when a
         *  fetched frame lands. */
        private int hoverX = -1;
        /** The preview frame for the hover in progress, once cached. */
        private BufferedImage image;

        ScrubBar() {
            super(0, 1000);
            // Registers with the ToolTipManager; the dynamic override
            // below supplies the real text (null shows nothing).
            setToolTipText("");
        }

        @Override public JToolTip createToolTip() {
            return new ScrubTip();
        }

        /** The timestamp under the pointer — and the fetch trigger: the
         *  hover IS the request. Null while the duration is unknown,
         *  which suppresses the tip entirely. */
        @Override public String getToolTipText(MouseEvent e) {
            if (durationMs <= 0) {
                image = null;
                return null;
            }
            hoverX = e.getX();
            long t = Math.round(perMilleAt(e.getX()) / 1000.0 * durationMs);
            image = previewFor(t);
            return clock(t);
        }

        /** The box centered on the pointer, clamped to the track's ends,
         *  at one fixed height above the bar — it slices along x only.
         *  Once a frame is cached the box already carries its image row,
         *  so it never re-centers for the picture. */
        @Override public Point getToolTipLocation(MouseEvent e) {
            String text = getToolTipText(e);
            if (text == null || getWidth() <= 0) return null;
            Dimension box = tipSize(text);
            int x = Math.clamp(e.getX() - box.width / 2, 0, getWidth() - box.width);
            return new Point(x, -box.height - Tokens.GAP_2);
        }

        /** Re-runs the tip pipeline without waiting for a mouse move —
         *  a landed frame re-reads text and place, and the box shows its
         *  image. The real event queue, the real dispatch path. */
        void nudgeTip() {
            if (hoverX < 0 || !isShowing()) return;
            Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(
                    new MouseEvent(this, MouseEvent.MOUSE_MOVED,
                            System.currentTimeMillis(), 0, hoverX, 5, 0, false));
        }

        /** The slider's own inverse mapping — flat slider UIs all inherit
         *  it — with a linear fallback for any exotic UI. */
        private int perMilleAt(int x) {
            if (getUI() instanceof javax.swing.plaf.basic.BasicSliderUI ui)
                return ui.valueForXPosition(x);
            return getWidth() <= 0 ? 0
                    : Math.clamp(Math.round(x / (float) getWidth() * 1000), 0, 1000);
        }

        private Dimension tipSize(String text) {
            if (measurer == null) measurer = (ScrubTip) createToolTip();
            measurer.setTipText(text);
            return measurer.getPreferredSize();
        }

        /** The tip as two rows: the fetched frame (once one exists for
         *  the hover) above the time, both centered. Sizing and painting
         *  pull the bar's live frame, so a landed fetch needs only the
         *  nudge to appear. */
        final class ScrubTip extends JToolTip {
            private String time = "";

            ScrubTip() {
                setFont(FontRegistry.mono());   // a time reads in mono, like the row
            }

            /** The manager's text lands here; the UI's own text stays
             *  empty because this layout is ours. */
            @Override public void setTipText(String text) {
                time = text == null ? "" : text;
                super.setTipText("");
            }

            @Override public Dimension getPreferredSize() {
                FontMetrics fm = getFontMetrics(getFont());
                Insets in = getInsets();
                int w = Math.max(fm.stringWidth(time),
                        image == null ? 0 : image.getWidth());
                int h = fm.getHeight() + (image == null ? 0
                        : image.getHeight() + Tokens.GAP_1);
                return new Dimension(w + in.left + in.right,
                        h + in.top + in.bottom);
            }

            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);   // the themed background
                Graphics2D g2 = (Graphics2D) g.create();
                FontMetrics fm = getFontMetrics(getFont());
                Insets in = getInsets();
                int cw = getWidth() - in.left - in.right;
                int y = in.top;
                if (image != null) {
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.drawImage(image,
                            in.left + (cw - image.getWidth()) / 2, y, null);
                    y += image.getHeight() + Tokens.GAP_1;
                }
                g2.setColor(getForeground());
                g2.setFont(getFont());
                g2.drawString(time, in.left + (cw - fm.stringWidth(time)) / 2,
                        y + fm.getAscent());
                g2.dispose();
            }
        }
    }

    /** Paints the whole controls bar — scrim, buttons, sliders — through a
     *  stepping alpha: the JLayer way to fade a component tree. */
    private static final class ControlsFade extends LayerUI<JComponent> {
        private float alpha = 1f;

        float alpha() { return alpha; }

        void setAlpha(float alpha) {
            float old = this.alpha;
            this.alpha = alpha;
            firePropertyChange("alpha", old, alpha);
        }

        @Override public void applyPropertyChange(java.beans.PropertyChangeEvent e,
                JLayer<? extends JComponent> l) {
            if ("alpha".equals(e.getPropertyName())) l.repaint();
        }

        @Override public void paint(Graphics g, JComponent c) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setComposite(AlphaComposite.SrcOver.derive(alpha));
            super.paint(g2, c);
            g2.dispose();
        }
    }

    private void paintWell(Graphics2D g2, JComponent c) {
        int cw = c.getWidth(), ch = c.getHeight();
        if (cw == 0 || ch == 0) return;
        Color well = UIManager.getColor("Dock.playerSurface");
        if (well == null) well = UIManager.getColor("Dock.viewerBackground");
        if (well == null) well = c.getBackground();
        g2.setColor(well);
        g2.fillRect(0, 0, cw, ch);
        if (state == State.LOADING) {
            paintCentered(g2, c, Glyphs.SYNC, "Streaming " + current + "…");
        } else if (state == State.REFUSED) {
            paintCentered(g2, c, Glyphs.WARNING, refusal);
        }
    }

    private void paintCentered(Graphics2D g2, JComponent c, String glyph, String text) {
        int cw = c.getWidth(), ch = c.getHeight();
        Color ink = muted();
        java.awt.FontMetrics fm = getFontMetrics(FontRegistry.ui());
        int textW = fm.stringWidth(text);
        var icon = Glyphs.icon(glyph, Tokens.ICON_HERO, () -> ink);
        int total = icon.getIconWidth() + Tokens.GAP_3 + textW;
        int x = (cw - total) / 2, y = ch / 2;
        icon.paintIcon(c, g2, x, y - icon.getIconHeight() / 2);
        g2.setColor(ink);
        g2.setFont(FontRegistry.ui());
        g2.drawString(text, x + icon.getIconWidth() + Tokens.GAP_3,
                y + fm.getAscent() / 2 - 1);
    }

    /** The click feedback: a translucent dark disc carrying the white
     *  glyph of the state just entered, fading out over the video. Disc
     *  and glyph size to the picture (the engine's fitted movie box, not
     *  the window frame — a pillarboxed movie must not get an oversized
     *  stamp), the disc at ~17% of the picture's smaller dimension, and
     *  the glyph uniform-filled to ~half the disc, so play, pause and the
     *  skip chevrons all read at the same optical weight. Fixed
     *  white-on-dark like the near-black well it floats on — either
     *  theme's foreground would vanish against bright footage. Painted
     *  from inside the engine's surface (see {@link #mountEngine}). */
    private void paintOverlayTo(Graphics2D g2, int w, int h, int movieW, int movieH) {
        if (flashGlyph == null || flashAlpha <= 0f) return;
        if (w == 0 || h == 0) return;
        Composite oldComposite = g2.getComposite();
        Object oldAA = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setComposite(AlphaComposite.SrcOver.derive(flashAlpha));
        int d = Math.max(24, Math.round(Math.min(movieW, movieH) * 0.175f));
        g2.setColor(new Color(0, 0, 0, 140));
        g2.fillOval((w - d) / 2, (h - d) / 2, d, d);
        var icon = Glyphs.iconUniform(flashGlyph, Math.round(d * 0.6f),
                () -> Color.WHITE);
        icon.paintIcon(surface, g2,
                (w - icon.getIconWidth()) / 2, (h - icon.getIconHeight()) / 2);
        g2.setComposite(oldComposite);
        if (oldAA != null) g2.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING, oldAA);
    }

    private static String clock(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? "%d:%02d:%02d".formatted(h, m, sec)
                : "%d:%02d".formatted(m, sec);
    }

    private static String shortError(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m.split("\n")[0];
    }

    // ---- for tests (same package) ----

    /** Swaps the engine factory (a recording fake); null restores production. */
    public static void useEngineFactoryForTest(MediaEngine.Factory factory) {
        engineFactory = factory == null ? new VlcEngine.Factory() : factory;
    }

    /** Swaps the preview factory (a recording fake); null restores
     *  production. */
    public static void usePreviewFactoryForTest(MediaPreview.Factory factory) {
        previewFactory = factory == null ? new VlcPreviewEngine.Factory() : factory;
    }

    /** Swaps the resume ask (a canned answer); null restores the dialog. */
    public static void useResumeAskForTest(ResumeAsk ask) {
        resumeAsk = ask == null ? ResumeAsk.DIALOG : ask;
    }

    /** Shortens (or restores) the idle delay before the controls fade. */
    public static void useAutoHideDelayForTest(int ms) {
        hideDelayForTest = ms;
    }

    /** The file now playing — integration tests pin the walk. */
    public String currentForTest() { return current; }
    State stateForTest() { return state; }
    MediaEngine engineForTest() { return engine; }
    boolean mutedForTest() { return muted; }
    int volumeForTest() { return volumePercent; }
    long durationForTest() { return durationMs; }
    String footerForTest() { return footerText; }
    String infoTextForTest() { return infoText; }

    /** The live bridge URL — integration tests fetch the real file bytes
     *  through it, proving the panel wired filesystem to engine. */
    public String bridgeUrlForTest() {
        MediaBridge.Registration reg = registration;
        return reg == null ? null : reg.url();
    }

    /** Moves the seek slider exactly as a user drag release would. */
    void seekToForTest(int perMille) {
        seek.setValue(perMille);
    }

    /** Pretends a thumb drag is (not) in progress — the auto-hide must
     *  never take the bar out from under a live drag. */
    void seekAdjustingForTest(boolean adjusting) {
        seek.getModel().setValueIsAdjusting(adjusting);
    }

    /** The scrub tooltip's text at a pointer x — the real override behind
     *  a synthetic move, none of the ToolTipManager's delays. */
    String scrubTipForTest(int x) {
        return seek.getToolTipText(motion(seek, x));
    }

    /** The scrub tooltip's place at a pointer x — same synthetic move. */
    java.awt.Point scrubTipLocationForTest(int x) {
        return seek.getToolTipLocation(motion(seek, x));
    }

    int seekWidthForTest() { return seek.getWidth(); }

    /** The tip box at a pointer x, image row included when a frame is
     *  cached — drives the real tooltip pipeline. */
    Dimension scrubTipSizeForTest(int x) {
        String text = seek.getToolTipText(motion(seek, x));
        return text == null ? null : seek.tipSize(text);
    }

    int previewCacheSizeForTest() { return previewCache.size(); }

    /** True when no preview fetch is out. */
    boolean previewIdleForTest() { return previewInFlight < 0; }

    int subtitleCountForTest() { return sidecars.size(); }

    /** The cycle position: -1 off, else an index into the sidecars. */
    int subtitleIndexForTest() { return subtitleIndex; }

    boolean ccVisibleForTest() { return ccButton.isVisible(); }

    /** The CC button's action body — a cycle click without the mouse. */
    void cycleSubtitleForTest() { cycleSubtitle(); }

    /** The local temp a sidecar fetched to, when it has one. */
    File subtitleTempForTest(String name) { return subtitleFiles.get(name); }

    private static MouseEvent motion(Component c, int x) {
        return new MouseEvent(c, MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(), 0, x, 5, 0, false);
    }

    boolean controlsVisibleForTest() { return controlsLayer.isVisible(); }
    float controlsAlphaForTest() { return controlsFade.alpha(); }

    /** The controls strip's bounds within the stage — geometry tests. */
    java.awt.Rectangle controlsBoundsForTest() { return controlsLayer.getBounds(); }

    /** Fires the idle clock's verdict — the timer body, no sleeping
     *  through a real delay. */
    void fireAutoHideForTest() { autoHide(); }

    /** Winds the controls fade forward one tick — the fader's own body. */
    void stepControlsFadeForTest() { stepControlsFade(); }

    boolean cursorHiddenForTest() { return surface.getCursor() == BLANK_CURSOR; }

    String flashGlyphForTest() { return flashGlyph; }
    float flashAlphaForTest() { return flashAlpha; }

    /** Winds the fade forward one tick — the fader's own body, no sleeps. */
    void stepFadeForTest() { stepFade(); }

    /** Clicks the middle of the surface, on the exact component a real
     *  click would land on — with a real engine that sits deep inside its
     *  component tree, not on the surface itself. */
    public void fireClickForTest() {
        fireClickAtForTest(surface.getWidth() / 2, surface.getHeight() / 2, 1);
    }

    /** Clicks a surface point — the real dispatch path, coordinates
     *  translated to the deepest component just as the OS delivers them,
     *  press/release/click and all. {@code clickCount} 2 plays the second
     *  click of a double click. */
    public void fireClickAtForTest(int x, int y, int clickCount) {
        Component at = surface.findComponentAt(x, y);
        Component target = at != null ? at : surface;
        java.awt.Point p = SwingUtilities.convertPoint(surface, x, y, target);
        long when = System.currentTimeMillis();
        for (int id : new int[] {MouseEvent.MOUSE_PRESSED,
                MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED}) {
            target.dispatchEvent(new MouseEvent(target, id, when, 0,
                    p.x, p.y, clickCount, false));
        }
    }

    /** Moves the pointer over the middle of the video — the real dispatch
     *  path, the deepest component at the point, as the OS delivers it. */
    public void fireMotionForTest() {
        fireMotionAtForTest(surface.getWidth() / 2, surface.getHeight() / 2);
    }

    public void fireMotionAtForTest(int x, int y) {
        Component at = surface.findComponentAt(x, y);
        Component target = at != null ? at : surface;
        java.awt.Point p = SwingUtilities.convertPoint(surface, x, y, target);
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(), 0, p.x, p.y, 0, false));
    }

    /** Whether a zone click's toggle is still waiting out the
     *  double-click window. */
    boolean zoneTogglePendingForTest() { return zoneToggle != null; }

    /** Fires the pending zone toggle — the window expiring, without
     *  sleeping through a real one. */
    void fireZoneToggleForTest() { runZoneToggle(); }

    Surface surfaceForTest() { return surface; }

    /** Fires the surface's binding for a keystroke — the real keyboard path. */
    public void fireKeyForTest(String spec) {
        KeyStroke ks = KeyStroke.getKeyStroke(spec);
        Object name = surface.getInputMap(WHEN_FOCUSED).get(ks);
        if (name == null) throw new IllegalStateException("no binding for " + spec);
        Action action = surface.getActionMap().get(name);
        if (action == null) throw new IllegalStateException("no action for " + spec);
        action.actionPerformed(new java.awt.event.ActionEvent(surface, 0, "test"));
    }
}
