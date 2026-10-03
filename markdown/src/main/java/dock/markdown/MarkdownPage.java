package dock.markdown;

import dock.core.fs.FileSystem;
import dock.kit.FontRegistry;
import dock.kit.Glyphs;
import dock.kit.Tokens;
import dock.kit.Toast;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * The rendered markdown surface on its own: the styled document in its
 * scroll pane, the images that embed into it, the links that open out
 * of it, the raw flip, and the word-wrap modes that size it. Everything
 * here is what a host needs to show one rendered page — the reader's
 * chrome (the file walk, the strip, the close key) lives with the host,
 * not the page. Building runs on a worker with a generation counter, so
 * a rapid re-render drops stale results: local images ride the build,
 * remote ones show as chips and swap in as their fetches arrive, and a
 * fetch that outlives its document is dropped.
 *
 * <p>The page is embeddable but not self-sufficient on purpose: the host
 * installs its own key bindings on {@link #text()} (the reader binds
 * escape/close and the walk; the editor binds its close and save), and
 * decides when to focus it.
 */
public final class MarkdownPage extends JPanel {

    /** How many inline images a document may embed, and how big one may
     *  be before it renders as a chip instead. */
    private static final int MAX_IMAGES = 40;
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;
    /** Inline images never render wider than this. */
    private static final int IMAGE_MAX_WIDTH = 640;

    /** Fetches remote images: redirects followed, a tight connect budget —
     *  one dead host must not stall the build worker for long. */
    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient
            .newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();

    /** Client property the {@code Text} pane carries while the reader's
     *  word wrap is off — the embedded code cards read it and keep their
     *  longest lines whole instead of wrapping to the host's width. */
    static final String NO_WRAP = "dock.md.noWrap";

    private final FileSystem fs;
    private final String dir;

    private final Text text = new Text();
    private final JScrollPane scroll = new JScrollPane(text);

    private String source;
    private StyledDocument renderedDoc;
    private StyledDocument rawDoc;
    /** Whether a document has ever been installed — re-renders only touch
     *  the live text pane once one is showing. */
    private boolean shown;
    private boolean raw;
    /** Word wrap: on (default), long lines fold into the pane; off, the
     *  page keeps its natural width and scrolls sideways. */
    private boolean wrap = true;

    /** Inline images of the shown document — local ones from the build,
     *  remote ones added as their fetches land — kept so a theme-flip
     *  re-render embeds them again instead of degrading them to chips. */
    private Map<String, javax.swing.Icon> imageIcons = Map.of();
    /** The document's remote targets still worth waiting for (neither
     *  landed nor failed); drives re-renders and late arrivals. */
    private List<String> remoteTargets = List.of();
    private final java.util.Set<String> failedTargets = new java.util.HashSet<>();
    /** Remote icons this page has already fetched — walking back to a
     *  document re-embeds them with no round trip. Bounded: icons are
     *  big. */
    private final Map<String, javax.swing.Icon> remoteCache =
            java.util.Collections.synchronizedMap(new LinkedHashMap<
                    String, javax.swing.Icon>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(
                        Map.Entry<String, javax.swing.Icon> eldest) {
                    return size() > 64;
                }
            });

    /** Where external links go; tests swap it out. */
    private Consumer<String> browser = MarkdownPage::openInSystemBrowser;
    /** Where same-folder markdown links go — the reader walks itself, the
     *  editor opens the linked file; null explains itself with a toast. */
    private Consumer<String> markdownOpener;

    /** Same-folder markdown links hand their target to {@code opener} —
     *  the host decides what opening means (the reader walks, the
     *  editor opens a new file). */
    public void onMarkdownLink(Consumer<String> opener) {
        markdownOpener = opener;
    }

    private final java.util.concurrent.atomic.AtomicLong generation =
            new java.util.concurrent.atomic.AtomicLong();

    public MarkdownPage(FileSystem fs, String dir) {
        super(new BorderLayout());
        this.fs = fs;
        this.dir = dir;
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        // The hbar exists for exactly one state — word wrap off over long
        // lines. Wrapped, the page tracks the viewport's width, so the
        // bar never shows and the surface reads exactly as before.
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setViewportBorder(BorderFactory.createEmptyBorder(
                Tokens.GAP_3, Tokens.GAP_4, Tokens.GAP_3, Tokens.GAP_4));
        scroll.setBorder(null);
        // The page reaches every edge of the host: whatever the document
        // doesn't cover — the padding frame, the space below a short file —
        // is the viewport's to paint, and it paints the page color, so
        // none of it reads as a gap in the surface.
        scroll.getViewport().setBackground(surface());
        scroll.setBackground(surface());
        text.setEditable(false);
        text.setFont(FontRegistry.mono());
        text.setBackground(surface());
        text.setCaretColor(muted());
        add(scroll, BorderLayout.CENTER);
    }

    // ---- rendering pipeline ----

    /** Builds and installs the rendered (and raw) documents for
     *  {@code src} on a worker; {@code whenBuilt} runs on the EDT once
     *  the documents land (or never, if a newer render superseded this
     *  one). Re-rendering is the whole life of a walk: each new source
     *  bumps the generation and drops whatever was still in flight. */
    public void render(String src, Runnable whenBuilt) {
        source = src;
        raw = false;
        long gen = generation.incrementAndGet();
        MdTheme theme = MdTheme.current();
        Thread.ofVirtual().name("dock-markdown").start(() -> {
            Built b = build(src, theme);
            SwingUtilities.invokeLater(() -> {
                if (generation.get() != gen) return;  // stale: a newer render won
                renderedDoc = b.rendered();
                rawDoc = b.raw();
                imageIcons = new LinkedHashMap<>(b.icons());
                failedTargets.clear();
                remoteTargets = b.remote();
                shown = true;
                text.setDocument(renderedDoc);
                text.setCaretPosition(0);
                if (whenBuilt != null) whenBuilt.run();
                fetchRemotes(gen, remoteTargets);
            });
        });
    }

    private record Built(StyledDocument rendered, StyledDocument raw,
                         Map<String, javax.swing.Icon> icons,
                         List<String> remote) {}

    /** The worker's whole life — every blocking call lives here or in
     *  the image loaders. A render failure degrades to the raw text,
     *  never to a blank page. */
    private Built build(String src, MdTheme theme) {
        List<String> targets;
        try {
            targets = MarkdownRenderer.imageTargets(src);
        } catch (RuntimeException e) {
            targets = List.of();   // degrades to "no images"
        }
        List<String> batch = targets.size() > MAX_IMAGES
                ? targets.subList(0, MAX_IMAGES) : targets;
        List<String> local = new ArrayList<>(), remote = new ArrayList<>();
        for (String t : batch) {
            if (t.startsWith("http://") || t.startsWith("https://")) remote.add(t);
            else if (!t.startsWith("mailto:") && !t.startsWith("#")) local.add(t);
        }
        Map<String, javax.swing.Icon> icons = loadImages(local);
        java.util.Set<String> pending = new java.util.LinkedHashSet<>();
        for (String t : remote) {
            javax.swing.Icon cached = remoteCache.get(t);
            if (cached != null) icons.put(t, cached);
            else pending.add(t);
        }
        StyledDocument rendered;
        try {
            rendered = MarkdownRenderer.render(src, theme, icons, pending);
        } catch (RuntimeException e) {
            rendered = MarkdownRenderer.raw(src, theme);
        }
        return new Built(rendered, MarkdownRenderer.raw(src, theme),
                icons, List.copyOf(pending));
    }

    // ---- inline images ----

    /** Reads the document's local image targets through the host's
     *  filesystem, all at once — they ride the same wire the document
     *  did, so they belong to the synchronous build. Anything missing,
     *  oversized, or undecodable stays a chip. Runs on the worker
     *  thread — every blocking call lives here or in {@link #loadImage}. */
    private Map<String, javax.swing.Icon> loadImages(List<String> targets) {
        record Fetched(String target, javax.swing.Icon icon) {}
        java.util.Queue<Fetched> done = new java.util.concurrent.ConcurrentLinkedQueue<>();
        List<Thread> fetchers = new ArrayList<>();
        for (String t : targets) {
            fetchers.add(Thread.ofVirtual().name("dock-md-image").unstarted(() -> {
                javax.swing.Icon icon = loadImage(t);
                if (icon != null) done.add(new Fetched(t, icon));
            }));
        }
        fetchers.forEach(Thread::start);
        for (Thread f : fetchers) {
            try {
                f.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Map<String, javax.swing.Icon> out = new LinkedHashMap<>();
        for (Fetched f : done) out.put(f.target(), f.icon());
        return out;
    }

    private javax.swing.Icon loadImage(String target) {
        try {
            String path = fs.normalize(
                    target.startsWith("/") ? target : fs.child(dir, target));
            if (fs.stat(path).size() > MAX_IMAGE_BYTES) return null;
            FileSystem view = fs.streamView();
            byte[] bytes;
            try (InputStream in = view.read(path)) {
                bytes = in.readAllBytes();
            } finally {
                if (view != fs) try { view.close(); } catch (Exception ignored) {}
            }
            return decode(bytes, path);
        } catch (Exception e) {
            return null;   // the chip explains itself
        }
    }

    /** One remote image, capped like a local one: the declared length is
     *  honored before a byte is read, and the read itself is bounded so a
     *  lying header can't flood the heap either. */
    private javax.swing.Icon loadRemote(String url) {
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest
                    .newBuilder(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("User-Agent", "Universal Explorer markdown reader")
                    .GET().build();
            java.net.http.HttpResponse<InputStream> response = HTTP.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) return null;
            java.util.Optional<String> declared =
                    response.headers().firstValue("Content-Length");
            if (declared.isPresent() && Long.parseLong(declared.get()) > MAX_IMAGE_BYTES)
                return null;
            byte[] bytes;
            try (InputStream in = response.body()) {
                bytes = in.readNBytes((int) MAX_IMAGE_BYTES + 1);
            }
            if (bytes.length > MAX_IMAGE_BYTES) return null;
            String clean = url.split("[?#]", 2)[0];
            return decode(bytes, clean);
        } catch (Exception e) {
            return null;   // offline or dead host: the chip explains itself
        }
    }

    private static javax.swing.Icon decode(byte[] bytes, String path) {
        var decoded = dock.viewer.ImageDecoder.decode(bytes, nameOf(path));
        BufferedImage img = null;
        if (decoded instanceof dock.viewer.DecodedImage.Still s) img = s.image();
        else if (decoded instanceof dock.viewer.DecodedImage.Animated a
                && !a.frames().isEmpty()) img = a.frames().getFirst();
        else if (decoded instanceof dock.viewer.DecodedImage.Vector v)
            img = dock.viewer.ImageDecoder.rasterize(v, IMAGE_MAX_WIDTH);
        return img == null ? null : scaled(img);
    }

    /** Downscales to the width cap, bilinear; smaller images show as-is. */
    private static javax.swing.Icon scaled(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        int tw = Math.min(w, IMAGE_MAX_WIDTH);
        if (tw == w) return new javax.swing.ImageIcon(src);
        int th = Math.max(1, Math.round(h * (tw / (float) w)));
        BufferedImage out = new BufferedImage(
                tw, th, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, tw, th, null);
        } finally {
            g.dispose();
        }
        return new javax.swing.ImageIcon(out);
    }

    private static String nameOf(String path) {
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return cut < 0 ? path : path.substring(cut + 1);
    }

    /** Puts the document's remote images on the wire — one thread each,
     *  so the slowest host can't queue the rest — and lands every
     *  result. The document is already on screen by now; fetches only
     *  ever add to it. */
    private void fetchRemotes(long gen, List<String> targets) {
        for (String t : targets) {
            Thread.ofVirtual().name("dock-md-image").start(() -> {
                javax.swing.Icon icon = remoteCache.get(t);
                if (icon == null) {
                    icon = loadRemote(t);
                    if (icon != null) remoteCache.put(t, icon);
                }
                javax.swing.Icon landed = icon;
                SwingUtilities.invokeLater(() -> landRemote(gen, t, landed));
            });
        }
    }

    /** One fetch's result joins the document it belongs to — or is
     *  dropped, if a newer render superseded it. */
    private void landRemote(long gen, String target, javax.swing.Icon icon) {
        if (generation.get() != gen) return;
        if (icon == null) failedTargets.add(target);
        else imageIcons.put(target, icon);
        if (renderedDoc == null) return;
        SimpleAttributeSet a = new SimpleAttributeSet();
        if (icon != null) StyleConstants.setIcon(a, icon);
        else StyleConstants.setComponent(a,
                new ImageChip(target, MdTheme.current(), false));
        List<Integer> at = new ArrayList<>();
        collectChips(renderedDoc.getDefaultRootElement(), target, at);
        // Offsets are gathered first: applying attributes rebuilds the
        // element tree, and the walk must not read it mid-mutation.
        // replace=true — the new set must displace the chip attribute,
        // not merge with it, or the leaf paints both.
        for (int offset : at)
            renderedDoc.setCharacterAttributes(offset, 1, a, true);
    }

    /** Offsets of every pending chip holding {@code target} — the same
     *  URL can appear more than once in a document. */
    private static void collectChips(Element e, String target, List<Integer> out) {
        if (e.isLeaf()) {
            if (StyleConstants.getComponent(e.getAttributes())
                    instanceof ImageChip chip && chip.pending()
                    && chip.target().equals(target))
                out.add(e.getStartOffset());
            return;
        }
        for (int i = 0; i < e.getElementCount(); i++)
            collectChips(e.getElement(i), target, out);
    }

    // ---- links ----

    /** The link target under a document offset, or null. */
    private String hrefAt(int offset) {
        if (offset < 0 || offset >= text.getDocument().getLength()) return null;
        Object href = ((StyledDocument) text.getDocument())
                .getCharacterElement(offset).getAttributes()
                .getAttribute(MarkdownRenderer.HREF);
        return href == null ? null : href.toString();
    }

    /** Resolves a click inside a link run: web targets go to the system
     *  browser, same-folder markdown goes to the host's opener, anything
     *  else explains itself. */
    public void followAt(int offset) {
        String href = hrefAt(offset);
        if (href == null) return;
        if (href.startsWith("http://") || href.startsWith("https://")
                || href.startsWith("mailto:")) {
            browser.accept(href);
            return;
        }
        if (href.contains("/") || href.contains("\\")) {
            Toast.show(this, "Links outside this folder aren't followed yet.", Glyphs.INFO);
            return;
        }
        if (MarkdownDocs.renders(href)) {
            if (markdownOpener != null) markdownOpener.accept(href);
            return;
        }
        Toast.show(this, href + " isn't viewable in the reader.", Glyphs.INFO);
    }

    private static void openInSystemBrowser(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop()
                    .isSupported(java.awt.Desktop.Action.BROWSE))
                java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
        } catch (Exception ignored) {
            // Best effort — the page stays usable whatever the desktop does.
        }
    }

    // ---- raw flip and wrap ----

    /** Flips rendered ↔ raw; both documents are already in hand, so the
     *  flip costs no work. The reading position survives it. */
    public void toggleRaw() {
        if (renderedDoc == null) return;
        raw = !raw;
        int caret = text.getCaretPosition();
        text.setDocument(raw ? rawDoc : renderedDoc);
        text.setCaretPosition(Math.min(caret, text.getDocument().getLength()));
    }

    public boolean showingRaw() { return raw; }

    /** Flips the page's word wrap. On, the page is exactly the host and
     *  long lines fold — prose, code cards, tables, nothing leaves the
     *  screen. Off, every line keeps its natural width and the host
     *  scrolls sideways, rolled by shift+wheel the editor's way. The
     *  client property is the card-side half of the contract; the text
     *  pane's scrollable answer below is the other. */
    public void setWrap(boolean on) {
        if (wrap == on) return;
        wrap = on;
        text.putClientProperty(NO_WRAP, wrap ? null : Boolean.TRUE);
        text.revalidate();
        text.repaint();
    }

    public boolean wraps() { return wrap; }

    // ---- theme ----

    /** The look-and-feel can flip under a shown page (Dark follows
     *  Windows); re-render so colors follow it instead of freezing. The
     *  already-decoded images re-embed — a theme flip costs no I/O. */
    @Override public void updateUI() {
        super.updateUI();
        // The JPanel constructor fires updateUI before field initializers
        // run — nothing to restyle yet.
        if (text == null || source == null) return;
        text.setBackground(surface());
        text.setCaretColor(muted());
        scroll.getViewport().setBackground(surface());
        scroll.setBackground(surface());
        MdTheme theme = MdTheme.current();
        // Still-worth-waiting-for = this document's remote targets minus
        // what already landed minus what already failed; a fetch that
        // lands during the flip replaces into this new document by target.
        java.util.Set<String> pending = new java.util.HashSet<>(remoteTargets);
        pending.removeAll(imageIcons.keySet());
        pending.removeAll(failedTargets);
        renderedDoc = MarkdownRenderer.render(source, theme, imageIcons, pending);
        rawDoc = MarkdownRenderer.raw(source, theme);
        if (shown) text.setDocument(raw ? rawDoc : renderedDoc);
    }

    private static Color muted() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : UIManager.getColor("Label.foreground");
    }

    private static Color surface() {
        return MdTheme.page();
    }

    // ---- access for hosts and tests ----

    /** The page's text surface — the host installs its own key bindings
     *  here and focuses it when the page shows. */
    public JTextComponent text() { return text; }

    public JScrollPane scroll() { return scroll; }

    /** True once a document has been installed — what a host (or test)
     *  awaits after {@link #render}. */
    public boolean ready() { return shown; }

    /** The document currently installed — hosts and their tests read
     *  the built page through it. */
    public StyledDocument document() { return (StyledDocument) text.getDocument(); }

    StyledDocument docForTest() { return document(); }
    boolean rawForTest() { return raw; }

    void browserForTest(Consumer<String> b) { browser = b; }

    // ---- the text surface ----

    /** A read-only text pane carrying the link clicks and the wrap
     *  geometry; hosts bind their own keys on it. */
    final class Text extends javax.swing.JTextPane {
        Text() {
            setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
            java.awt.event.MouseAdapter mouse = new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e.getButton() != java.awt.event.MouseEvent.BUTTON1) return;
                    followAt(viewToModel2D(e.getPoint()));
                }
                @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                    setCursor(hrefAt(viewToModel2D(e.getPoint())) != null
                            ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                            : Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
                }
            };
            // MouseAdapter implements both listener faces, but each
            // registration only subscribes its own event class — motion
            // events never reach a listener registered as a plain
            // MouseListener, which is why the hover hand never showed.
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        /** Wrapped, the page is exactly the host — the viewport forces its
         *  width on the text and nothing scrolls sideways. Unwrapped, the
         *  text stops tracking, keeps its natural width, and the scroll
         *  pane grows the horizontal scrollbar; shift+wheel rolls it
         *  (the scroll pane's own shift conversion, the editor's rule). */
        @Override public boolean getScrollableTracksViewportWidth() {
            return wrap;
        }

        /** Unwrapped, the preferred width is the document's natural width:
         *  the root view is laid out impossibly wide — each paragraph then
         *  reports its longest logical line, each embedded card its
         *  natural size — and its span read back. The layout pass that
         *  follows the viewport's assignment restores the real width, so
         *  the wide layout is measured, never painted. */
        @Override public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            if (!wrap) {
                java.awt.Insets m = getMargin();
                d.width = Math.max(d.width,
                        naturalWidth() + m.left + m.right);
            }
            return d;
        }

        private int naturalWidth() {
            var root = getUI().getRootView(this);
            root.setSize(Integer.MAX_VALUE, Integer.MAX_VALUE);
            return (int) Math.ceil(root.getPreferredSpan(
                    javax.swing.text.View.X_AXIS));
        }
    }
}
