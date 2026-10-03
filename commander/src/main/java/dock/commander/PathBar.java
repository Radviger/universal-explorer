package dock.commander;

import dock.kit.FontRegistry;
import dock.kit.Glyphs;
import dock.kit.Tokens;
import dock.core.fs.FileSystem;
import java.awt.AWTEvent;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.awt.geom.RoundRectangle2D;
import java.beans.PropertyChangeListener;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

/**
 * Breadcrumb path bar. Browse mode shows clickable path segments
 * ("pathlets") from the root down; leading segments that no longer fit
 * collapse into an ellipsis pathlet with the hidden ancestors behind a
 * popup. On the local pane, the deepest Windows shell folder on the path
 * (home, Desktop, Documents, Downloads, Pictures, Music, Videos) absorbs
 * the whole drive-to-folder prefix into one leading icon — the local
 * counterpart of the remote server mark — so a path inside Downloads is
 * just "[download] ›"; clicking that icon decays the prefix into its real
 * crumbs, and the first press outside the bar (or a window switch)
 * collapses it back. Clicking the empty area right of the last pathlet
 * (or Ctrl+L) switches to a plain text editor; Enter navigates, Esc or
 * focus loss reverts. Both modes share the tinted rounded chrome painted
 * here, below the content — chrome first, text second.
 *
 * The bar is also its pane's progress indicator: while a listing is in
 * flight, {@link #setBusy(boolean)} paints an indeterminate accent wash
 * and sweeping band into the chrome itself. No separate progress strip
 * exists anywhere in the pane — one that appears and disappears would
 * reflow the toolbar and shift the file table under it.
 */
public final class PathBar extends JPanel {

    /** A tail closer than this to the bar's right end is stretched shut. */
    private static final int SNAP = 8;

    private final Supplier<FileSystem> fs;
    private final Consumer<String> navigate;
    /** Cross-filesystem hops (host crumbs while an archive is mounted). */
    private final java.util.function.BiConsumer<FileSystem, String> crossNavigate;
    private final BrowsePanel browse = new BrowsePanel();
    private final JTextField editor = new JTextField();
    private final CardLayout cards = new CardLayout();

    private List<JButton> pathlets = List.of();
    private List<Target> prefixes = List.of();
    private List<JLabel> chevrons = List.of();
    /** Closes a chain whose last crumb is icon-only (server root, shell folders). */
    private JLabel tailChevron;
    /** The known-folder head is decayed into its real path crumbs. */
    private boolean decayed;
    /** Outside-press guard while decayed (null when detached). */
    private AWTEventListener decayPressWatcher;
    /** Window-switch guard while decayed (null when detached). */
    private PropertyChangeListener decayWindowWatcher;
    private JButton ellipsis;
    private String current = "";
    private boolean editing;
    /** Listing in flight; painted into the chrome, never laid out. */
    private volatile boolean busy;
    /** Drives the sweep animation; created on first use, runs only while busy. */
    private Timer busyTimer;

    public PathBar(Supplier<FileSystem> fs, Consumer<String> navigate) {
        this(fs, navigate, null);
    }

    public PathBar(Supplier<FileSystem> fs, Consumer<String> navigate,
                   java.util.function.BiConsumer<FileSystem, String> crossNavigate) {
        this.fs = fs;
        this.navigate = navigate;
        this.crossNavigate = crossNavigate;
        setLayout(cards);
        setOpaque(false);

        editor.setFont(FontRegistry.mono());
        editor.setOpaque(false);
        editor.setBorder(BorderFactory.createEmptyBorder(7, Tokens.GAP_2, 7, Tokens.GAP_2));
        editor.addActionListener(e -> commit());
        editor.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) {
                // Revert rather than commit: clicking away abandons the edit.
                SwingUtilities.invokeLater(() -> { if (editing) cancel(); });
            }
        });
        var im = editor.getInputMap(JComponent.WHEN_FOCUSED);
        var am = editor.getActionMap();
        im.put(KeyStroke.getKeyStroke("ESCAPE"), "dock.pathBar.cancel");
        am.put("dock.pathBar.cancel", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { cancel(); }
        });

        add(browse, "browse");
        add(editor, "edit");
    }

    /** Rebuilds the breadcrumbs and primes the editor (unless editing). */
    public void setPath(String path) {
        current = path == null ? "" : path;
        decayed = false;          // navigation/refresh collapses the head
        detachDecayGuards();
        buildBrowse(current);
        if (!editing) editor.setText(current);
        revalidate();
        repaint();
    }

    /** Switches to the text editor, selected, focused (Ctrl+L / click empty area). */
    public void beginEdit() {
        editing = true;
        editor.setText(current);
        cards.show(this, "edit");
        editor.requestFocusInWindow();
        SwingUtilities.invokeLater(editor::selectAll);
    }

    /** Back to breadcrumbs without navigating. */
    public void cancel() {
        editing = false;
        editor.setText(current);
        cards.show(this, "browse");
        collapse();
    }

    /**
     * Marks a listing in flight. The bar's own background becomes an
     * indeterminate progress indicator; nothing is added to any layout,
     * so busy state can never move the content around it.
     */
    public void setBusy(boolean b) {
        if (busy == b) return;
        busy = b;
        if (b) {
            if (busyTimer == null) busyTimer = new Timer(40, e -> repaint());
            busyTimer.start();
        } else if (busyTimer != null) {
            busyTimer.stop();
        }
        repaint();
    }

    @Override public void addNotify() {
        super.addNotify();
        // Resume the sweep if a listing was still in flight when the bar
        // was detached (e.g. its pane hidden by Explorer mode).
        if (busy && busyTimer != null) busyTimer.start();
    }

    @Override public void removeNotify() {
        // A detached bar cannot repaint; the flag keeps the state so the
        // sweep resumes on reattach.
        if (busyTimer != null) busyTimer.stop();
        // A hidden bar cannot receive the outside press that would collapse
        // a decayed head; drop the global listeners and start collapsed.
        // The next setPath rebuilds the crumbs.
        decayed = false;
        detachDecayGuards();
        super.removeNotify();
    }

    private void commit() {
        String text = editor.getText().trim();
        editing = false;
        cards.show(this, "browse");
        if (!text.isEmpty()) navigate.accept(text);
    }

    public boolean editing() { return editing; }

    /** Whether a listing is in flight (tests). */
    public boolean busyForTest() { return busy; }

    /** Number of pathlets, root included (screenshot verifier / tests). */
    public int pathletCount() { return pathlets.size(); }

    /** Text of pathlet {@code index}; "" when the crumb is icon-only (tests). */
    public String pathletTextForTest(int index) { return pathlets.get(index).getText(); }

    /** Tooltip of pathlet {@code index} (tests). */
    public String pathletTooltipForTest(int index) {
        return pathlets.get(index).getToolTipText();
    }

    /** Icon of pathlet {@code index}; null for text crumbs (tests). */
    public Icon pathletIconForTest(int index) { return pathlets.get(index).getIcon(); }

    /** Whether the chain ends on a separator closing an icon-only crumb (tests). */
    public boolean tailSeparatorForTest() { return tailChevron != null; }

    /** Whether the known-folder head is decayed into text crumbs (tests). */
    public boolean decayedForTest() { return decayed; }

    /** Collapses a decayed head straight back (tests). */
    public void collapseForTest() { collapse(); }

    /** The breadcrumb card (tests dispatch presses into it). */
    public Container browseCardForTest() { return browse; }

    /** Bounds of that separator after layout; null when absent (tests). */
    public java.awt.Rectangle tailSeparatorBoundsForTest() {
        return tailChevron == null ? null : tailChevron.getBounds();
    }

    /** Fires the click of pathlet {@code index} (tests). */
    public void clickPathlet(int index) { pathlets.get(index).doClick(); }

    /** The fallback editor (tests). */
    public JTextField editorForTest() { return editor; }

    /** Preferred size of pathlet {@code index} (tests). */
    public Dimension pathletSizeForTest(int index) {
        return pathlets.get(index).getPreferredSize();
    }

    /** Lays the browse panel out at the bar's current size (tests). */
    public void layoutBrowseForTest() {
        browse.setSize(getWidth(), getHeight());
        browse.doLayout();
    }

    /** Bounds of pathlet {@code index} after layout (tests). */
    public java.awt.Rectangle pathletBoundsForTest(int index) {
        return pathlets.get(index).getBounds();
    }

    /** Cap flags of pathlet {@code index} after layout: "", "L", "R" (tests). */
    public String capsForTest(int index) {
        Crumb c = (Crumb) pathlets.get(index);
        return (c.leftCap ? "L" : "") + (c.rightCap ? "R" : "");
    }

    /** Standalone paint of pathlet {@code index} with a forced hover state (tests). */
    public java.awt.image.BufferedImage paintPathletForTest(int index, boolean hover) {
        Crumb c = (Crumb) pathlets.get(index);
        c.hover = hover;
        var img = new java.awt.image.BufferedImage(Math.max(1, c.getWidth()),
                Math.max(1, c.getHeight()), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        try {
            c.paint(g);
        } finally {
            g.dispose();
        }
        c.hover = false;
        return img;
    }

    // ---- construction ----

    /** The remote head mark: the owning backend's, the generic server by default. */
    private static String remoteHeadGlyph(FileSystem fs) {
        for (var backend : dock.core.spi.Backends.all()) {
            String glyph = backend.glyphOf(fs);
            if (glyph != null) return glyph;
        }
        return Glyphs.SERVER;
    }

    private void buildBrowse(String path) {
        FileSystem f = fs.get();
        List<JButton> buttons = new ArrayList<>();
        List<Target> prefixesOut = new ArrayList<>();
        if (f instanceof dock.archive.ArchiveFs a) {
            // A mounted archive: the host's crumbs, then the archive itself
            // — archive glyph, full file name — then the inner tree.
            buildHostChain(a.host(), a.hostDir(), buttons, prefixesOut);
            buttons.add(archiveCrumb(a));
            prefixesOut.add(new Target(null, "/"));
            List<String> parts = new ArrayList<>();
            for (String s : path.split("/")) {
                if (!s.isEmpty()) parts.add(s);
            }
            String joined = "";
            for (String part : parts) {
                joined += "/" + part;
                buttons.add(pathlet(part, new Target(null, joined)));
                prefixesOut.add(new Target(null, joined));
            }
        } else {
            buildHostChain(f, path, buttons, prefixesOut);
        }

        List<JLabel> chevs = new ArrayList<>();
        for (int i = 1; i < buttons.size(); i++) chevs.add(chevron());
        ellipsis = ellipsisPathlet();
        // A chain that ends on an icon-only crumb (the server mark at the
        // remote root, a collapsed known-folder head standing alone) carries
        // a trailing separator: without one the location reads as a lone
        // toolbar button rather than the end of a path.
        JButton last = buttons.get(buttons.size() - 1);
        boolean endsOnIcon = last.getIcon() != null
                && (last.getText() == null || last.getText().isEmpty());
        tailChevron = endsOnIcon ? chevron() : null;

        browse.removeAll();
        browse.add(ellipsis);
        browse.add(buttons.get(0));
        for (int i = 1; i < buttons.size(); i++) {
            browse.add(chevs.get(i - 1));
            browse.add(buttons.get(i));
        }
        if (tailChevron != null) browse.add(tailChevron);
        this.pathlets = buttons;
        this.prefixes = prefixesOut;
        this.chevrons = chevs;
    }

    /** Where one crumb click goes: a filesystem hop, or null for the bar's
     *  own filesystem (a bare string through {@link #navigate}). */
    private record Target(FileSystem fs, String path) {}

    private void fire(Target t) {
        if (t.fs() == null || crossNavigate == null) navigate.accept(t.path());
        else crossNavigate.accept(t.fs(), t.path());
    }

    /** The archive crumb: the archive glyph and the archive's own file name —
     *  never shortened — with the full host path in its tooltip. Clicking
     *  returns to the inner root. */
    private JButton archiveCrumb(dock.archive.ArchiveFs a) {
        String name = a.archivePath();
        int cut = Math.max(name.lastIndexOf(a.host().separator()),
                name.lastIndexOf('/'));
        Crumb b = new Crumb(cut >= 0 ? name.substring(cut + 1) : name,
                Glyphs.iconUniform(Glyphs.ARCHIVE, Tokens.ICON,
                        () -> UIManager.getColor("Dock.fileArchive")));
        b.setToolTipText(a.archivePath());
        stylePathlet(b);
        b.addActionListener(e -> fire(new Target(null, "/")));
        return b;
    }

    /**
     * Appends the root crumb and text crumbs of {@code hostDir} as they
     * would render on {@code f} alone: the server mark for remotes, the
     * decaying known-folder head locally, the drive otherwise.
     */
    private void buildHostChain(FileSystem f, String hostDir,
                                List<JButton> buttons, List<Target> prefixesOut) {
        String sep = f.separator();
        List<String> parts = new ArrayList<>();
        for (String s : hostDir.split(Pattern.quote(sep))) {
            if (!s.isEmpty()) parts.add(s);
        }

        String rootPrefix;
        JButton root;
        List<String> tail;
        if (f.remote()) {
            rootPrefix = "/";
            root = iconPathlet(remoteHeadGlyph(f), "Go to root");
            tail = parts;
        } else {
            Head head = decayed ? null : knownHead(sep, parts);
            if (head != null) {
                rootPrefix = head.prefix();
                root = iconPathlet(head.folder().glyph(),
                        head.folder().label() + " — " + head.prefix(),
                        head.folder().colorKey());
                root.addActionListener(e -> decay());
                tail = parts.subList(head.covered(), parts.size());
            } else if (!parts.isEmpty()) {
                rootPrefix = parts.get(0) + sep;
                root = pathlet(parts.get(0), new Target(f, rootPrefix));
                tail = parts.subList(1, parts.size());
            } else {
                rootPrefix = sep;
                root = pathlet(sep, new Target(f, rootPrefix));
                tail = parts;
            }
        }

        buttons.add(root);
        prefixesOut.add(new Target(f, rootPrefix));
        String joined = rootPrefix;
        for (String part : tail) {
            joined = joined.endsWith(sep) ? joined + part : joined + sep + part;
            buttons.add(pathlet(part, new Target(f, joined)));
            prefixesOut.add(new Target(f, joined));
        }
    }

    /** A collapsed known-folder head: the folder, the parts it covers, its path. */
    private record Head(KnownFolders.Folder folder, int covered, String prefix) {}

    /**
     * The deepest known shell folder on this local path — Downloads rather
     * than the home it sits inside — or null when the path misses them all.
     */
    private static Head knownHead(String sep, List<String> parts) {
        Map<String, KnownFolders.Folder> known = KnownFolders.snapshot();
        if (known.isEmpty() || parts.isEmpty()) return null;
        String joined = parts.get(0) + sep;
        KnownFolders.Folder best = null;
        int covered = 0;
        String prefix = null;
        for (int i = 1; i < parts.size(); i++) {
            joined = joined.endsWith(sep) ? joined + parts.get(i) : joined + sep + parts.get(i);
            KnownFolders.Folder hit = known.get(joined.toLowerCase(Locale.ROOT));
            if (hit != null) {
                best = hit;
                covered = i + 1;
                prefix = joined;
            }
        }
        return best == null ? null : new Head(best, covered, prefix);
    }

    // ---- known-folder head: one icon, decayed on demand ----

    /** Expands the collapsed head into the real crumbs of its prefix. */
    private void decay() {
        if (decayed) return;
        decayed = true;
        attachDecayGuards();
        buildBrowse(current);
        revalidate();
        repaint();
    }

    /** Collapses the head again (outside press, window switch, navigation). */
    private void collapse() {
        if (!decayed) return;
        decayed = false;
        detachDecayGuards();
        buildBrowse(current);
        revalidate();
        repaint();
    }

    /**
     * While decayed, the bar behaves like an open popup: the first mouse
     * press outside it, or the window losing activation, closes it back to
     * the icon. Focus alone is not a reliable trigger — crumbs are not
     * focusable, so clicking one never moves the focus owner.
     */
    private void attachDecayGuards() {
        if (decayPressWatcher == null) {
            decayPressWatcher = e -> {
                if (e instanceof MouseEvent me
                        && me.getID() == MouseEvent.MOUSE_PRESSED
                        && !SwingUtilities.isDescendingFrom(me.getComponent(), this)) {
                    collapse();
                }
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(decayPressWatcher,
                    AWTEvent.MOUSE_EVENT_MASK);
        }
        if (decayWindowWatcher == null) {
            decayWindowWatcher = e -> {
                Object w = e.getNewValue();
                if (!(w instanceof Window win)
                        || !SwingUtilities.isDescendingFrom(this, win)) {
                    collapse();
                }
            };
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .addPropertyChangeListener("activeWindow", decayWindowWatcher);
        }
    }

    private void detachDecayGuards() {
        if (decayPressWatcher != null) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(decayPressWatcher);
            decayPressWatcher = null;
        }
        if (decayWindowWatcher != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .removePropertyChangeListener("activeWindow", decayWindowWatcher);
            decayWindowWatcher = null;
        }
    }

    private JButton pathlet(String text, Target target) {
        Crumb b = new Crumb(text, null);
        stylePathlet(b);
        b.addActionListener(e -> fire(target));
        return b;
    }

    /** The root: a server mark for remotes, the drive label for locals. */
    private JButton iconPathlet(String glyph, String tooltip) {
        return iconPathlet(glyph, tooltip, null);
    }

    /**
     * Icon-only crumb — remote roots and collapsed shell folders. A null
     * colorKey keeps the chrome's muted ink; shell folders take their hue
     * from the theme, resolved at paint time like every other glyph icon.
     */
    private JButton iconPathlet(String glyph, String tooltip, String colorKey) {
        Crumb b = new Crumb(null, Glyphs.iconUniform(glyph, Tokens.ICON, colorKey == null
                ? FileTableModel::muted
                : () -> UIManager.getColor(colorKey)));
        b.setToolTipText(tooltip);
        stylePathlet(b);
        return b;
    }

    private JButton ellipsisPathlet() {
        Crumb b = new Crumb("\u2026", null); // …
        b.setToolTipText("Hidden folders");
        stylePathlet(b);
        b.addActionListener(e -> showHiddenPopup());
        return b;
    }

    private void stylePathlet(Crumb b) {
        b.setFont(FontRegistry.mono());
        // A plain content-less button, not a FlatLaf borderless one: the
        // segment fill (full-height, outer corners capped to the bar's arc)
        // is painted by Crumb itself — the borderless type would fight it
        // with its own smaller rounded pill. The border keeps the compact
        // 6px sides that fixed the "C:" ballooning.
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setOpaque(false);
        b.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        b.setFocusable(false);
    }

    /**
     * One breadcrumb segment. Laid out edge-to-edge across the bar's full
     * inner height; the chain's first crumb carries the bar's rounded left
     * corners and the last the rounded right ones, so a hovered segment
     * reads as part of the bar chrome rather than a floating chip.
     */
    private static final class Crumb extends JButton {
        boolean leftCap;
        boolean rightCap;
        private boolean hover;
        private boolean pressed;

        Crumb(String text, javax.swing.Icon icon) {
            super(text, icon);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true;    repaint(); }
                @Override public void mouseExited(MouseEvent e)  { hover = false;   repaint(); }
                @Override public void mousePressed(MouseEvent e) { pressed = true;  repaint(); }
                @Override public void mouseReleased(MouseEvent e) { pressed = false; repaint(); }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            if (hover || pressed) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    Color fill = UIManager.getColor(pressed
                            ? "Button.toolbar.pressedBackground"
                            : "Button.toolbar.hoverBackground");
                    if (fill != null) {
                        g2.setColor(fill);
                        fillSegment(g2, getWidth(), getHeight(), leftCap, rightCap);
                    }
                } finally {
                    g2.dispose();
                }
            }
            super.paintComponent(g);
        }
    }

    /**
     * Paints a segment — see {@link dock.kit.Segments#fill} for the
     * geometry contract. Kept as a pass-through for the inner painter.
     */
    static void fillSegment(Graphics2D g2, int w, int h,
                            boolean leftCap, boolean rightCap) {
        dock.kit.Segments.fill(g2, w, h, leftCap, rightCap);
    }

    private JLabel chevron() {
        JLabel l = new JLabel(Glyphs.icon(Glyphs.CHEVRON_RIGHT, 10,
                FileTableModel::muted));
        l.setBorder(BorderFactory.createEmptyBorder(0, 1, 0, 1));
        return l;
    }

    private void showHiddenPopup() {
        JPopupMenu popup = new JPopupMenu();
        for (int i = 1; i < pathlets.size(); i++) {
            if (!pathlets.get(i).isVisible()) {
                Target t = prefixes.get(i);
                String sep = t.fs() == null ? fs.get().separator() : t.fs().separator();
                String name = t.path().substring(t.path().lastIndexOf(sep) + 1);
                JMenuItem mi = new JMenuItem(name.isEmpty() ? t.path() : name);
                mi.setFont(FontRegistry.mono());
                mi.addActionListener(ev -> fire(t));
                popup.add(mi);
            }
        }
        popup.show(ellipsis, 0, ellipsis.getHeight());
    }

    // ---- browse layout: keep the tail visible, collapse the head ----

    private final class BrowsePanel extends JPanel {
        BrowsePanel() {
            super(null);
            setOpaque(false);
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    // Click right of the last visible pathlet: manual edit.
                    int lastRight = 2;
                    for (Component c : getComponents()) {
                        if (c.isVisible() && c != ellipsis) {
                            lastRight = Math.max(lastRight, c.getX() + c.getWidth());
                        }
                    }
                    if (SwingUtilities.isLeftMouseButton(e)
                            && e.getX() > lastRight + 2) {
                        beginEdit();
                    }
                }
            });
        }

        @Override public void doLayout() {
            int avail = getWidth() - 4;
            int n = pathlets.size();
            int tail = tailChevron == null ? 0 : pref(tailChevron);
            for (Component c : getComponents()) c.setVisible(false);

            // Full path fits? (index 0 is the root, placed by layoutFrom)
            if (totalWidth(n) + tail <= avail) {
                layoutFrom(1, false);
                return;
            }
            // Drop leading non-root pathlets until [root … tail] fits.
            for (int keep = n - 1; keep >= 0; keep--) {
                int w = pref(pathlets.get(0)) + pref(ellipsis)
                        + tailWidth(keep) + tail;
                if (w <= avail || keep == 0) {
                    layoutFrom(n - keep, true);
                    return;
                }
            }
        }

        private int totalWidth(int count) {
            int w = 0;
            for (int i = 0; i < count; i++) {
                if (i > 0) w += pref(chevrons.get(i - 1));
                w += pref(pathlets.get(i));
            }
            return w;
        }

        private int tailWidth(int keep) {
            int w = 0;
            for (int i = pathlets.size() - keep; i < pathlets.size(); i++) {
                w += pref(chevrons.get(i - 1)) + pref(pathlets.get(i));
            }
            return w;
        }

        /** Lays out root, optionally ellipsis, then the last {@code keep} pathlets. */
        private void layoutFrom(int first, boolean withEllipsis) {
            for (JButton b : pathlets) clearCaps(b);
            clearCaps(ellipsis);
            int x = place(pathlets.get(0), 0);
            JButton last = pathlets.get(0);
            if (withEllipsis) {
                x = place(ellipsis, x);
                last = ellipsis;
            }
            for (int i = first; i < pathlets.size(); i++) {
                x = place(chevrons.get(i - 1), x);
                x = place(pathlets.get(i), x);
                last = pathlets.get(i);
            }
            cap(pathlets.get(0), true);
            if (tailChevron != null) {
                place(tailChevron, x);
                snapTail(tailChevron);
            } else {
                snapTail(last);
            }
        }

        /**
         * The right cap only makes sense at the bar's right end. Whatever
         * ends the chain — the last crumb, or the separator closing an
         * icon-only tail — and reaches within {@code SNAP} px of it would
         * leave an awkward sliver of bare chrome: stretch it shut, and
         * round its corners when it is a crumb. A tail that ends well
         * short stays square (the space right of it is the click-to-edit
         * area).
         */
        private void snapTail(Component end) {
            int barRight = getWidth() - 1;   // the chrome paints w-1 px
            int gap = barRight - (end.getX() + end.getWidth());
            if (gap >= 0 && gap <= SNAP) {
                end.setBounds(end.getX(), end.getY(), end.getWidth() + gap, end.getHeight());
                if (end instanceof Crumb c) c.rightCap = true;
            }
        }

        private void clearCaps(JButton b) {
            if (b instanceof Crumb c) { c.leftCap = false; c.rightCap = false; }
        }

        private void cap(JButton b, boolean left) {
            if (b instanceof Crumb c) {
                if (left) c.leftCap = true; else c.rightCap = true;
            }
        }

        private int place(Component c, int x) {
            if (c instanceof Crumb) {
                // Segments fill the bar's inner height edge to edge; the
                // bar chrome paints (width-1, height-1), so match that.
                int w = c.getPreferredSize().width;
                c.setBounds(x, 0, w, getHeight() - 1);
            } else {
                Dimension d = c.getPreferredSize();
                c.setBounds(x, (getHeight() - d.height) / 2, d.width, d.height);
            }
            c.setVisible(true);
            return x + c.getWidth();
        }

        private int pref(Component c) {
            return c.getPreferredSize().width;
        }
    }

    @Override protected void paintComponent(Graphics g) {
        // Shared chrome under both modes; content paints on top.
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(UIManager.getColor("Dock.fieldBackground"));
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1,
                    Tokens.ARC, Tokens.ARC);
            if (busy) paintBusy(g2);
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }

    /**
     * Indeterminate progress in the chrome itself: a faint accent wash
     * (the track, covering the whole bar) and a brighter band sweeping
     * across it once every ~1.4s. Both are clipped to the chrome's
     * rounded rectangle so the fill follows the bar's corners, and both
     * paint in {@code paintComponent} — breadcrumbs and the editor always
     * render above them, fully legible, mid-sweep.
     */
    private void paintBusy(Graphics2D g2) {
        Color accent = UIManager.getColor("Dock.accent");
        if (accent == null) return;
        int w = getWidth() - 1;
        int h = getHeight() - 1;
        if (w <= 0 || h <= 0) return;
        g2.clip(new RoundRectangle2D.Float(0, 0, w, h, Tokens.ARC, Tokens.ARC));
        g2.setColor(withAlpha(accent, 36));                 // ~14% wash
        g2.fillRect(0, 0, w, h);
        double band = Math.max(48, w * 0.22);
        double cycle = 1_400_000_000.0;
        double pos = (System.nanoTime() % cycle) / cycle * (w + band) - band;
        g2.setColor(withAlpha(accent, 96));                 // ~38% sweep
        g2.fillRoundRect((int) Math.round(pos), 0, (int) Math.round(band), h,
                Tokens.ARC, Tokens.ARC);
    }

    private static Color withAlpha(Color c, int alpha) {
        return new Color((c.getRGB() & 0x00_FF_FF_FF) | (alpha << 24), true);
    }
}
