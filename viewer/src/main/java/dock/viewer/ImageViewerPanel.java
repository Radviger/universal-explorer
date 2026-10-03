package dock.viewer;

import dock.core.fs.FileSystem;
import dock.kit.FontRegistry;
import dock.kit.Glyphs;
import dock.kit.Tokens;
import dock.kit.Toast;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
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
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

/**
 * The in-pane image viewer: a control bar, the image canvas, an info strip.
 * It is swapped into a commander pane in place of the file table and stays
 * until closed; reading and decoding run on virtual threads, a generation
 * counter drops results made stale by rapid navigation. Prev/next walk the
 * live {@link #sequence} (the pane's image files in display order), and
 * every shown file is reported through {@link #onNavigate} so the pane's
 * cursor follows — closing the viewer lands on the last image seen.
 *
 * <p>Rotation is deliberately sticky across navigation: a folder of
 * sideways photos wants one correction applied to the whole batch, while
 * zoom resets to fit each image.
 */
public final class ImageViewerPanel extends JPanel {

    private static final double MIN_SCALE = 0.05;
    private static final double MAX_SCALE = 32.0;
    private static final double WHEEL_FACTOR = 1.25;
    private static final int FIT_MARGIN = Tokens.GAP_3;
    private static final int CHECKER = 12;
    /** Refuse before reading: no decode should be able to blow the heap. */
    private static final long MAX_VIEW_BYTES = 512L * 1024 * 1024;
    private static final long CACHE_BUDGET_PIXELS = 48_000_000L;

    /** Package-visible so tests can assert the load state machine. */
    enum State { LOADING, SHOWN, REFUSED }

    private final FileSystem fs;
    private final String dir;
    private final Supplier<List<String>> sequence;
    private final Consumer<String> onNavigate;
    private final Runnable onClose;
    private final ViewerCache cache = new ViewerCache(CACHE_BUDGET_PIXELS);

    private final Canvas canvas = new Canvas();
    private final JLabel strip = new JLabel();

    private String current;
    private DecodedImage image;
    private State state = State.LOADING;

    /** Rotation quadrant 0–3 (×90° clockwise). */
    private int quadrant;
    /** View scale, 1 = 100%; while {@link #fit} holds it follows the pane size. */
    private double scale = 1;
    private boolean fit = true;
    /** Pan offset from the centered position, in view pixels. */
    private double panX, panY;

    private int frame;
    private boolean paused;
    private Timer animator;

    /** Last SVG raster and the scale it was rasterized for; rasters are
     *  rebuilt exactly whenever the zoom moves, so geometry never drifts. */
    private BufferedImage svgRaster;
    private double svgRasterScale;

    private final AtomicLong generation = new AtomicLong();

    public ImageViewerPanel(FileSystem fs, String dir, String firstName,
                            Supplier<List<String>> sequence,
                            Consumer<String> onNavigate, Runnable onClose) {
        super(new BorderLayout());
        this.fs = fs;
        this.dir = dir;
        this.sequence = sequence;
        this.onNavigate = onNavigate;
        this.onClose = onClose;
        add(buildBar(), BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        add(buildStrip(), BorderLayout.SOUTH);
        canvas.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                if (fit) { refit(); canvas.repaint(); }
            }
        });
        load(firstName);
    }

    // ---- chrome ----

    private JComponent buildBar() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(
                Tokens.GAP_1, Tokens.GAP_2, Tokens.GAP_1, Tokens.GAP_2));
        bar.add(button(Glyphs.CHEVRON_LEFT, "Previous (Left)", this::previous));
        bar.add(button(Glyphs.CHEVRON_RIGHT, "Next (Right)", this::next));
        bar.add(groupGap());
        bar.add(button(Glyphs.SEARCH_MINUS, "Zoom out (−)", this::zoomOut));
        bar.add(button(Glyphs.EXPAND, "Fit to pane (0)", this::fitToPane));
        bar.add(button(Glyphs.SEARCH_PLUS, "Zoom in (+)", this::zoomIn));
        bar.add(groupGap());
        bar.add(button(Glyphs.UNDO, "Rotate counterclockwise (Ctrl+Left)", () -> rotate(-1)));
        bar.add(button(Glyphs.REDO, "Rotate clockwise (Ctrl+Right)", () -> rotate(1)));
        bar.add(Box.createHorizontalGlue());
        bar.add(button(Glyphs.CLOSE, "Close (Esc)", this::close));
        return bar;
    }

    private static Component groupGap() {
        JSeparator sep = new JSeparator(javax.swing.SwingConstants.VERTICAL);
        sep.setPreferredSize(new Dimension(1, 18));
        sep.setMaximumSize(new Dimension(1, 18));
        Box wrap = Box.createHorizontalBox();
        wrap.add(Box.createHorizontalStrut(Tokens.GAP_3));
        wrap.add(sep);
        wrap.add(Box.createHorizontalStrut(Tokens.GAP_3));
        // A Box with no alignment of its own answers through its layout
        // (Container defers to BoxLayout), and this one's aggregate comes
        // out 0.0 — top-aligned. The bar's BoxLayout sizes an X row as
        // the max space above plus the max space below across the row's
        // children at their alignments, so a top-aligned 18px child asks
        // 15+18px where the 30px buttons ask 30 — a bar 3px taller than
        // the pane's own, and the toolbar row jumped on every open.
        // Centered, the separator asks no more than the buttons do.
        wrap.setAlignmentY(0.5f);
        return wrap;
    }

    private static JButton button(String glyph, String tooltip, Runnable action) {
        // Uniform ink scaling keeps thin chevrons and dense glyphs the same
        // optical size; 20px ink in a 30px hit area matches the pane toolbar.
        JButton b = new JButton(Glyphs.iconUniform(glyph, Tokens.ICON_LARGE,
                ImageViewerPanel::muted));
        b.setToolTipText(tooltip);
        b.putClientProperty("JButton.buttonType", "borderless");
        b.setRolloverEnabled(true);
        b.setFocusable(false);
        b.setPreferredSize(new Dimension(30, 30));
        b.setMaximumSize(new Dimension(30, 30));
        b.addActionListener(e -> action.run());
        return b;
    }

    private JComponent buildStrip() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0,
                        UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(
                        Tokens.GAP_1, Tokens.GAP_3, Tokens.GAP_1, Tokens.GAP_3)));
        strip.setFont(FontRegistry.mono());
        strip.setForeground(muted());
        p.add(strip, BorderLayout.CENTER);
        return p;
    }

    private static Color muted() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : UIManager.getColor("Label.foreground");
    }

    // ---- geometry ----

    /** Logical image size before rotation. */
    private int baseW() {
        return switch (image) {
            case null -> 0;
            case DecodedImage.Still s -> s.width();
            case DecodedImage.Animated a -> a.width();
            case DecodedImage.Vector v -> v.width();
            case DecodedImage.Unsupported u -> 0;
        };
    }

    private int baseH() {
        return switch (image) {
            case null -> 0;
            case DecodedImage.Still s -> s.height();
            case DecodedImage.Animated a -> a.height();
            case DecodedImage.Vector v -> v.height();
            case DecodedImage.Unsupported u -> 0;
        };
    }

    /** Displayed size after quadrant rotation, before scaling. */
    private int viewW() { return quadrant % 2 == 0 ? baseW() : baseH(); }
    private int viewH() { return quadrant % 2 == 0 ? baseH() : baseW(); }

    private boolean alphaBehind() {
        return switch (image) {
            case DecodedImage.Still s -> s.alpha();
            case DecodedImage.Animated a -> a.alpha();
            case DecodedImage.Vector v -> v.alpha();
            default -> false;
        };
    }

    void refit() {
        int cw = canvas.getWidth() - 2 * FIT_MARGIN;
        int ch = canvas.getHeight() - 2 * FIT_MARGIN;
        if (cw <= 0 || ch <= 0 || viewW() <= 0 || viewH() <= 0) return;
        // Upscaling counts as fitting: a 40×40 icon fills the pane like any
        // viewer does, rather than floating as a postage stamp.
        scale = Math.clamp(Math.min((double) cw / viewW(), (double) ch / viewH()),
                MIN_SCALE, MAX_SCALE);
        panX = panY = 0;
        updateStrip();
    }

    private void clampPan() {
        double mx = Math.max(0, (viewW() * scale - canvas.getWidth()) / 2.0);
        double my = Math.max(0, (viewH() * scale - canvas.getHeight()) / 2.0);
        panX = Math.clamp(panX, -mx, mx);
        panY = Math.clamp(panY, -my, my);
    }

    // ---- zoom / rotate ----

    void zoomIn() { zoomAt(canvas.getWidth() / 2.0, canvas.getHeight() / 2.0, WHEEL_FACTOR); }
    void zoomOut() { zoomAt(canvas.getWidth() / 2.0, canvas.getHeight() / 2.0, 1 / WHEEL_FACTOR); }

    /**
     * Zooms by {@code factor}, keeping the image point under ({@code vx},
     * {@code vy}) under it. Rotation commutes with uniform scaling, so the
     * anchor math never needs to un-rotate: from
     * {@code v = c + pan + R(s·u)} follows
     * {@code pan' = (v−c) − k·(v−c−pan)} with {@code k = s'/s}.
     */
    void zoomAt(double vx, double vy, double factor) {
        if (state != State.SHOWN) return;
        double ns = Math.clamp(scale * factor, MIN_SCALE, MAX_SCALE);
        double k = ns / scale;
        if (k == 1.0) return;
        double ex = vx - canvas.getWidth() / 2.0;
        double ey = vy - canvas.getHeight() / 2.0;
        panX = ex - k * (ex - panX);
        panY = ey - k * (ey - panY);
        scale = ns;
        fit = false;
        clampPan();
        canvas.repaint();
        updateStrip();
    }

    void fitToPane() {
        fit = true;
        refit();
        canvas.repaint();
    }

    void actualSize() {
        fit = false;
        scale = 1;
        panX = panY = 0;
        clampPan();
        canvas.repaint();
        updateStrip();
    }

    void rotate(int steps) {
        quadrant = ((quadrant + steps) % 4 + 4) % 4;
        if (fit) refit();
        clampPan();
        canvas.repaint();
        updateStrip();
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

    private void first() {
        List<String> seq = sequence.get();
        if (!seq.isEmpty() && !seq.getFirst().equals(current)) load(seq.getFirst());
    }

    private void last() {
        List<String> seq = sequence.get();
        if (!seq.isEmpty() && !seq.getLast().equals(current)) load(seq.getLast());
    }

    // ---- loading pipeline ----

    private void load(String name) {
        current = name;
        long gen = generation.incrementAndGet();
        stopAnimator();
        frame = 0;
        paused = false;
        image = null;
        state = State.LOADING;
        updateStrip();
        canvas.repaint();
        String path = fs.child(dir, name);
        Thread.ofVirtual().name("dock-viewer").start(() -> {
            DecodedImage d = cache.get(path);
            if (d == null) {
                d = readDecode(path, name);
                cache.put(path, d);
            }
            final DecodedImage decoded = d;
            SwingUtilities.invokeLater(() -> {
                if (generation.get() != gen) return;  // stale: a newer load won
                show(decoded);
            });
        });
    }

    /** The decode thread's whole life — every blocking call lives here. */
    private DecodedImage readDecode(String path, String name) {
        try {
            long size = fs.stat(path).size();
            if (size > MAX_VIEW_BYTES)
                return new DecodedImage.Unsupported(ext(name),
                        "too large to view (over 512 MB)");
            FileSystem view = fs.streamView();
            byte[] bytes;
            try (InputStream in = view.read(path)) {
                bytes = in.readAllBytes();
            } finally {
                if (view != fs) try { view.close(); } catch (Exception ignored) {}
            }
            return ImageDecoder.decode(bytes, name);
        } catch (Exception e) {
            return new DecodedImage.Unsupported(ext(name), shortError(e));
        }
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static String shortError(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m.split("\n")[0];
    }

    private void show(DecodedImage d) {
        image = d;
        frame = 0;
        paused = false;
        svgRaster = null;
        fit = true;                       // zoom resets per image…
        panX = panY = 0;                  // …rotation stays (sticky by design)
        state = d instanceof DecodedImage.Unsupported ? State.REFUSED : State.SHOWN;
        if (state == State.SHOWN) {
            refit();
            startAnimator();
        } else {
            String reason = ((DecodedImage.Unsupported) d).reason();
            Toast.show(this, "Can't view " + current + ": " + reason, Glyphs.WARNING);
        }
        canvas.requestFocusInWindow();
        updateStrip();
        canvas.repaint();
        onNavigate.accept(current);
        prefetch();
    }

    /** Prefetches the next image so stepping feels instant, budget permitting. */
    private void prefetch() {
        List<String> seq = sequence.get();
        int i = seq.indexOf(current);
        if (i < 0 || i + 1 >= seq.size()) return;
        String next = seq.get(i + 1);
        if (cache.has(fs.child(dir, next))) return;
        Thread.ofVirtual().name("dock-viewer-prefetch").start(() -> {
            String path = fs.child(dir, next);
            if (cache.has(path)) return;
            cache.put(path, readDecode(path, next));
        });
    }

    // ---- animation ----

    private void startAnimator() {
        stopAnimator();
        if (image instanceof DecodedImage.Animated a && a.frames().size() > 1) {
            animator = new Timer(delayOf(a, frame), e -> {
                frame = (frame + 1) % a.frames().size();
                animator.setDelay(delayOf(a, frame));
                canvas.repaint();
            });
            if (!paused) animator.start();
        }
    }

    private static int delayOf(DecodedImage.Animated a, int i) {
        int d = a.delaysMs().get(Math.min(i, a.delaysMs().size() - 1));
        return d <= 0 ? 100 : d;   // the browsers' convention for zero delays
    }

    private void stopAnimator() {
        if (animator != null) {
            animator.stop();
            animator = null;
        }
    }

    void togglePause() {
        paused = !paused;
        if (image instanceof DecodedImage.Animated a && a.frames().size() > 1) {
            if (paused) animator.stop();
            else {
                animator.setDelay(delayOf(a, frame));
                animator.restart();
            }
        }
        updateStrip();
    }

    @Override public void removeNotify() {
        // Swapped out of the pane or the tab closed: no timer may keep
        // firing against a dead component.
        stopAnimator();
        super.removeNotify();
    }

    // ---- painting ----

    private BufferedImage displaySource() {
        return switch (image) {
            case null -> null;
            case DecodedImage.Still s -> s.image();
            case DecodedImage.Animated a -> a.frames().get(
                    Math.min(frame, a.frames().size() - 1));
            case DecodedImage.Vector v -> rasterFor(v);
            case DecodedImage.Unsupported u -> null;
        };
    }

    /** Rasterizes the diagram at exactly the current zoom; repainted without
     *  a zoom change reuses the raster. Capped inside the rasterizer. */
    private BufferedImage rasterFor(DecodedImage.Vector v) {
        if (svgRaster != null && svgRasterScale == scale) return svgRaster;
        svgRaster = ImageDecoder.rasterize(v.diagram(),
                Math.max(1, (int) Math.round(v.width() * scale)),
                Math.max(1, (int) Math.round(v.height() * scale)));
        svgRasterScale = scale;
        return svgRaster;
    }

    private void paintCanvas(Graphics2D g2, JComponent c) {
        int cw = c.getWidth(), ch = c.getHeight();
        if (cw == 0 || ch == 0) return;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        Color well = UIManager.getColor("Dock.viewerBackground");
        if (well == null) well = c.getBackground();
        g2.setColor(well);
        g2.fillRect(0, 0, cw, ch);
        if (state == State.LOADING) {
            paintCentered(g2, cw, ch, Glyphs.SYNC, "Loading " + current + "…");
            return;
        }
        if (state == State.REFUSED) {
            String reason = image instanceof DecodedImage.Unsupported u
                    ? (u.format().isEmpty() ? "" : u.format() + ": ") + u.reason()
                    : "";
            paintCentered(g2, cw, ch, Glyphs.WARNING, reason);
            return;
        }
        BufferedImage src = displaySource();
        if (src == null) return;
        if (fit) refit();
        double iw = viewW() * scale, ih = viewH() * scale;
        if (alphaBehind()) paintChecker(g2, cw, ch, iw, ih, well);
        double cx = cw / 2.0 + panX, cy = ch / 2.0 + panY;
        g2 = (Graphics2D) g2.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.translate(cx, cy);
            g2.rotate(quadrant * Math.PI / 2.0);
            g2.scale(scale, scale);
            g2.translate(-src.getWidth() / 2.0, -src.getHeight() / 2.0);
            // Big zoom-outs halve first: repeated 2× steps keep far more
            // detail than one giant bilinear jump.
            BufferedImage drawn = scale < 0.5 && image instanceof DecodedImage.Still
                    ? halveTo(src, scale) : src;
            g2.drawImage(drawn, 0, 0, src.getWidth(), src.getHeight(), null);
        } finally {
            g2.dispose();
        }
    }

    /** Alternating faint squares where the image's transparency would
     *  otherwise read as the pane's own background. */
    private void paintChecker(Graphics2D g2, int cw, int ch, double iw, double ih, Color well) {
        Rectangle r = new Rectangle(
                (int) Math.floor(cw / 2.0 + panX - iw / 2),
                (int) Math.floor(ch / 2.0 + panY - ih / 2),
                (int) Math.ceil(iw), (int) Math.ceil(ih))
                .intersection(new Rectangle(0, 0, cw, ch));
        if (r.isEmpty()) return;
        Color a = new Color((well.getRed() + 510) / 3, (well.getGreen() + 510) / 3,
                (well.getBlue() + 510) / 3);
        Color b = new Color(well.getRed() * 3 / 4, well.getGreen() * 3 / 4,
                well.getBlue() * 3 / 4);
        Graphics2D g = (Graphics2D) g2.create();
        try {
            g.clip(r);
            for (int y = 0; y < r.y + r.height; y += CHECKER) {
                for (int x = 0; x < r.x + r.width; x += CHECKER) {
                    g.setColor((x / CHECKER + y / CHECKER) % 2 == 0 ? a : b);
                    g.fillRect(x, y, CHECKER, CHECKER);
                }
            }
        } finally {
            g.dispose();
        }
    }

    private static BufferedImage halveTo(BufferedImage src, double scale) {
        int targetW = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int type = src.getColorModel().hasAlpha()
                ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage cur = src;
        while (cur.getWidth() > targetW * 2) {
            BufferedImage next = new BufferedImage(
                    Math.max(1, cur.getWidth() / 2), Math.max(1, cur.getHeight() / 2), type);
            Graphics2D g = next.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(cur, 0, 0, next.getWidth(), next.getHeight(), null);
            } finally {
                g.dispose();
            }
            cur = next;
        }
        return cur;
    }

    private void paintCentered(Graphics2D g2, int cw, int ch, String glyph, String text) {
        Color c = muted();
        java.awt.FontMetrics fm = getFontMetrics(FontRegistry.ui());
        int textW = fm.stringWidth(text);
        var icon = Glyphs.icon(glyph, Tokens.ICON_HERO, () -> c);
        int total = icon.getIconWidth() + Tokens.GAP_3 + textW;
        int x = (cw - total) / 2, y = ch / 2;
        icon.paintIcon(canvas, g2, x, y - icon.getIconHeight() / 2);
        g2.setColor(c);
        g2.setFont(FontRegistry.ui());
        g2.drawString(text, x + icon.getIconWidth() + Tokens.GAP_3,
                y + fm.getAscent() / 2 - 1);
    }

    private void updateStrip() {
        List<String> seq = sequence.get();
        int idx = current == null ? -1 : seq.indexOf(current);
        String pos = idx >= 0 ? " · " + (idx + 1) + "/" + seq.size() : "";
        String text = switch (state) {
            case LOADING -> current + " · loading…" + pos;
            case REFUSED -> image instanceof DecodedImage.Unsupported u
                    ? (u.format().isEmpty() ? u.reason() : u.format() + " — " + u.reason()) + pos
                    : "";
            case SHOWN -> current + " · " + baseW() + "×" + baseH()
                    + (image instanceof DecodedImage.Animated a
                            ? " · " + a.frames().size() + " frames" : "")
                    + " · " + image.format() + pos
                    + " · " + Math.round(scale * 100) + "%"
                    + (paused ? " · paused" : "");
        };
        strip.setText(text);
    }

    /** The keyboard landing point after the pane swap — the canvas owns
     *  every viewer key while it shows. */
    public void focusCanvas() {
        canvas.requestFocusInWindow();
    }

    void close() {
        onClose.run();
    }

    // ---- canvas ----

    /** Package-private so same-package tests can drive the real component. */
    final class Canvas extends JComponent {
        private int pressX, pressY;
        private double pressPanX, pressPanY;
        private boolean dragging;

        Canvas() {
            setFocusable(true);
            setRequestFocusEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            bindKeys();
            addMouseWheelListener(e -> zoomAt(e.getX(), e.getY(),
                    Math.pow(WHEEL_FACTOR, -e.getWheelRotation())));
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    pressX = e.getX(); pressY = e.getY();
                    pressPanX = panX; pressPanY = panY;
                    dragging = true;
                }
                @Override public void mouseReleased(MouseEvent e) { dragging = false; }
                @Override public void mouseClicked(MouseEvent e) {
                    // Click is the animation's pause button; a still image
                    // has nothing to toggle.
                    if (image instanceof DecodedImage.Animated) togglePause();
                }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override public void mouseDragged(MouseEvent e) {
                    if (!dragging) return;
                    fit = false;
                    panX = pressPanX + (e.getX() - pressX);
                    panY = pressPanY + (e.getY() - pressY);
                    clampPan();
                    canvas.repaint();
                    updateStrip();
                }
            });
        }

        /** All viewer keys live on the focused canvas so they beat the
         *  ancestor maps — including the pane-hop arrows of the session. */
        private void bindKeys() {
            InputMap im = getInputMap(WHEN_FOCUSED);
            ActionMap am = getActionMap();
            bind(im, am, "ESCAPE", "close", ImageViewerPanel.this::close);
            // Backspace backs out of the viewer, the way it backs out a
            // directory level in the file panes — it never walks images.
            bind(im, am, "BACK_SPACE", "close", ImageViewerPanel.this::close);
            bind(im, am, "LEFT", "prev", ImageViewerPanel.this::previous);
            bind(im, am, "PAGE_UP", "prev", ImageViewerPanel.this::previous);
            bind(im, am, "RIGHT", "next", ImageViewerPanel.this::next);
            bind(im, am, "PAGE_DOWN", "next", ImageViewerPanel.this::next);
            bind(im, am, "SPACE", "next", ImageViewerPanel.this::next);
            bind(im, am, "HOME", "first", ImageViewerPanel.this::first);
            bind(im, am, "END", "last", ImageViewerPanel.this::last);
            bind(im, am, "typed +", "zoom-in", ImageViewerPanel.this::zoomIn);
            bind(im, am, "typed =", "zoom-in", ImageViewerPanel.this::zoomIn);
            bind(im, am, "ADD", "zoom-in", ImageViewerPanel.this::zoomIn);
            bind(im, am, "typed -", "zoom-out", ImageViewerPanel.this::zoomOut);
            bind(im, am, "SUBTRACT", "zoom-out", ImageViewerPanel.this::zoomOut);
            bind(im, am, "typed 0", "fit", ImageViewerPanel.this::fitToPane);
            bind(im, am, "typed 1", "actual", ImageViewerPanel.this::actualSize);
            bind(im, am, "typed r", "rotate-cw", () -> rotate(1));
            bind(im, am, "typed R", "rotate-ccw", () -> rotate(-1));
            // Arrows with Ctrl turn the image instead of walking it —
            // right winds clockwise, left unwinds.
            bind(im, am, "ctrl RIGHT", "rotate-cw", () -> rotate(1));
            bind(im, am, "ctrl LEFT", "rotate-ccw", () -> rotate(-1));
        }

        private void bind(InputMap im, ActionMap am, String key, String name, Runnable action) {
            KeyStroke ks = KeyStroke.getKeyStroke(key);
            if (ks == null) throw new IllegalStateException("bad keystroke " + key);
            im.put(ks, name);
            am.put(name, new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    action.run();
                }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            paintCanvas((Graphics2D) g, this);
        }
    }

    // ---- for tests (same package) ----

    String currentForTest() { return current; }
    State stateForTest() { return state; }
    double scaleForTest() { return scale; }
    int quadrantForTest() { return quadrant; }
    double panXForTest() { return panX; }
    double panYForTest() { return panY; }
    boolean fitForTest() { return fit; }
    int frameForTest() { return frame; }
    boolean pausedForTest() { return paused; }
    DecodedImage imageForTest() { return image; }
    Canvas canvasForTest() { return canvas; }

    /** Fires the canvas's binding for a keystroke — the real keyboard path. */
    public void fireKeyForTest(String spec) {
        KeyStroke ks = KeyStroke.getKeyStroke(spec);
        Object name = canvas.getInputMap(WHEN_FOCUSED).get(ks);
        if (name == null) throw new IllegalStateException("no binding for " + spec);
        Action action = canvas.getActionMap().get(name);
        if (action == null) throw new IllegalStateException("no action for " + spec);
        action.actionPerformed(new java.awt.event.ActionEvent(canvas, 0, "test"));
    }
}
