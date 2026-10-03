package dock.ui;

import dock.core.transfer.Progress;
import dock.core.transfer.TransferEngine;
import dock.core.transfer.TransferJob;
import dock.kit.Fmt;
import dock.kit.FontRegistry;
import dock.kit.Glyphs;
import dock.kit.Tokens;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FontMetrics;
import java.awt.GraphicsConfiguration;
import java.awt.IllegalComponentStateException;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JWindow;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

/**
 * The transfers popup anchored above the footer bar — IntelliJ's background
 * tasks view, not a window: one slim bar per in-flight file with its name and
 * percentage above and speed/ETA below. Clicking the footer's progress bar
 * (or Ctrl+Alt+Q) toggles it; Esc or a click outside dismisses. It never
 * steals focus and never opens on its own — bursts surface in the footer
 * bar, this is the detail view you ask for.
 */
public final class TransferPopup {

    /** How long settled rows linger before dropping out of the list. */
    private static final long ROW_LINGER_MS = 10_000;
    private static final int WIDTH = 380;
    /** Roughly five rows before the list starts scrolling. */
    private static final int MAX_LIST_HEIGHT = 320;
    private static final int MIN_LIST_HEIGHT = 48;

    private final TransferEngine engine;
    private final Component anchor;
    private final JWindow window;
    private final RowsPanel rows = new RowsPanel();
    private final JScrollPane scroll = new JScrollPane();
    private final FitLabel summary = new FitLabel();
    /** The footer's numbers — speed, percent — their own label so they
     *  read in mono and their ticking never jitters. */
    private final FitLabel summaryNums = new FitLabel();
    private final JButton pauseAll = ghost(Glyphs.PAUSE, "Pause all");
    private final JButton resumeAll = ghost(Glyphs.PLAY, "Resume all");
    private final JButton clear = ghost(Glyphs.TRASH, "Clear finished");
    private final JLabel empty = new JLabel("No recent transfers.");

    private boolean visible;
    private boolean watching;
    private List<UUID> rowOrder = List.of();
    private final List<Row> rowList = new ArrayList<>();
    private final Map<UUID, Row> rowViews = new LinkedHashMap<>();
    private final AWTEventListener watch;

    public TransferPopup(JFrame owner, TransferEngine engine, Component anchor) {
        this.engine = engine;
        this.anchor = anchor;
        window = new JWindow(owner);
        this.watch = event -> {
            if (!visible) return;
            if (event instanceof MouseEvent me && me.getID() == MouseEvent.MOUSE_PRESSED) {
                if (clickDismisses(me.getComponent(), window, anchor)) hidePopup();
            } else if (event instanceof KeyEvent ke
                    && ke.getID() == KeyEvent.KEY_PRESSED
                    && ke.getKeyCode() == KeyEvent.VK_ESCAPE) {
                hidePopup();
            }
        };

        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        scroll.setViewportView(rows);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        empty.setFont(FontRegistry.ui(Tokens.ICON_SMALL));
        empty.setForeground(Tokens.muted());
        empty.setAlignmentX(Component.CENTER_ALIGNMENT);
        empty.setBorder(BorderFactory.createEmptyBorder(Tokens.GAP_5, 0, Tokens.GAP_5, 0));

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(popupBorder(), 1),
                BorderFactory.createEmptyBorder(Tokens.GAP_2, Tokens.GAP_3,
                        Tokens.GAP_2, Tokens.GAP_3)));
        root.add(header(), BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);

        window.setFocusableWindowState(false);
        window.setContentPane(root);

        new Timer(150, e -> {
            if (visible) rebuild();
        }).start();
    }

    // ---- visibility ----

    public void toggle() {
        if (visible) hidePopup(); else showPopup();
    }

    public void showPopup() {
        if (visible) return;
        visible = true;
        rebuild();
        window.setVisible(true);
        installWatch();
    }

    public void hidePopup() {
        if (!visible) return;
        visible = false;
        window.setVisible(false);
        removeWatch();
    }

    /**
     * A press dismisses unless it lands inside the popup or on the footer
     * anchor — the anchor's own press toggles, and the AWT listener fires
     * before the component's handlers, so its clicks must pass through.
     */
    public static boolean clickDismisses(Component pressed, Window popup, Component anchor) {
        if (pressed == null) return false;
        return !SwingUtilities.isDescendingFrom(pressed, popup)
                && !SwingUtilities.isDescendingFrom(pressed, anchor);
    }

    private void installWatch() {
        if (watching) return;
        watching = true;
        Toolkit.getDefaultToolkit().addAWTEventListener(watch,
                AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
    }

    private void removeWatch() {
        if (!watching) return;
        watching = false;
        Toolkit.getDefaultToolkit().removeAWTEventListener(watch);
    }

    // ---- refresh ----

    /**
     * The rows a popup shows: active jobs, failures (they stick around
     * until cleared — an error shouldn't silently scroll away), and jobs
     * that settled within {@link #ROW_LINGER_MS}.
     */
    public static List<TransferJob> shownJobs(List<TransferJob> snapshot, long nowMillis) {
        List<TransferJob> out = new ArrayList<>();
        for (TransferJob j : snapshot) {
            if (j.isActive() || j.state() == TransferJob.State.FAILED
                    || (j.isFinished() && nowMillis - j.finishedAtMillis() <= ROW_LINGER_MS)) {
                out.add(j);
            }
        }
        return out;
    }

    private void rebuild() {
        List<TransferJob> shown = shownJobs(engine.snapshot(), System.currentTimeMillis());
        List<UUID> ids = new ArrayList<>();
        for (TransferJob j : shown) ids.add(j.id());

        // Rebuild only when the row set changes; otherwise update in place
        // so a refresh never swaps the button under the cursor mid-click.
        if (!ids.equals(rowOrder)) {
            rowOrder = ids;
            rows.removeAll();
            rowViews.clear();
            rowList.clear();
            if (shown.isEmpty()) {
                rows.add(empty);
            } else {
                for (TransferJob j : shown) {
                    Row r = new Row(j);
                    rowViews.put(j.id(), r);
                    rowList.add(r);
                    rows.add(r);
                }
            }
            rows.revalidate();
            rows.repaint();
        } else {
            for (TransferJob j : shown) rowViews.get(j.id()).update();
        }
        updateFooter(shown);
        relayout();
    }

    private void updateFooter(List<TransferJob> shown) {
        List<TransferJob> all = engine.snapshot();
        Progress p = TransferEngine.overall(all, System.currentTimeMillis(), ROW_LINGER_MS);
        long failed = all.stream().filter(j -> j.state() == TransferJob.State.FAILED).count();

        String text, nums;
        if (p.active()) {
            text = p.running() + (p.running() == 1 ? " transfer" : " transfers")
                    + (p.queued() > 0 ? " · " + p.queued() + " queued" : "");
            // Speed and percent are numbers: they ride the mono label.
            nums = "";
            if (p.speedBytesPerSec() > 0) nums = Fmt.speed(p.speedBytesPerSec());
            if (p.determinate()) {
                nums = nums.isEmpty() ? p.percent() + "%" : nums + " · " + p.percent() + "%";
            }
            if (!nums.isEmpty()) nums = "· " + nums;
        } else if (failed > 0) {
            text = failed + (failed == 1 ? " failure" : " failures");
            nums = "";
        } else if (!shown.isEmpty()) {
            text = "finished";
            nums = "";
        } else {
            text = "idle";
            nums = "";
        }
        summary.setFullText(text);
        summaryNums.setFullText(nums);

        pauseAll.setVisible(shown.stream().anyMatch(j -> j.state() == TransferJob.State.RUNNING));
        resumeAll.setVisible(shown.stream().anyMatch(j -> j.state() == TransferJob.State.PAUSED));
        clear.setVisible(shown.stream().anyMatch(TransferJob::isFinished));
    }

    /** Keeps the bottom edge pinned above the anchor as rows come and go. */
    private void relayout() {
        int listH = Math.max(Math.min(rows.getPreferredSize().height, MAX_LIST_HEIGHT),
                MIN_LIST_HEIGHT);
        scroll.setPreferredSize(new Dimension(WIDTH, listH));
        window.pack();
        place();
    }

    private void place() {
        Point base;
        try {
            base = anchor.getLocationOnScreen();
        } catch (IllegalComponentStateException e) {
            return; // anchor not on screen yet; the next refresh repositions
        }
        int x = base.x + anchor.getWidth() - window.getWidth();
        int y = base.y - window.getHeight() - Tokens.GAP_1;
        GraphicsConfiguration gc = anchor.getGraphicsConfiguration();
        if (gc != null) {
            Rectangle screen = gc.getBounds();
            if (x < screen.x) x = screen.x;
            if (y < screen.y) y = screen.y;
        }
        window.setLocation(x, y);
    }

    // ---- row ----

    private final class Row extends JPanel {
        private final TransferJob job;
        private final FitLabel name = new FitLabel();
        private final JLabel percent = new JLabel("", SwingConstants.RIGHT);
        private final JProgressBar bar = new JProgressBar();
        private final FitLabel detail = new FitLabel();
        /** One slot that flips between cancel (active) and retry (failed):
         *  BorderLayout keeps a single CENTER, so two buttons there would
         *  fight over the region. */
        private final JButton action = ghost(Glyphs.CLOSE, "Cancel");
        private boolean actionIsRetry;

        Row(TransferJob job) {
            this.job = job;
            setLayout(new BorderLayout(Tokens.GAP_2, 0));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(Tokens.GAP_2, Tokens.GAP_1,
                    Tokens.GAP_2, Tokens.GAP_1));

            name.setFont(FontRegistry.ui());
            percent.setFont(FontRegistry.mono(FontRegistry.BASE_SIZE - 1));
            percent.setForeground(Tokens.muted());
            JPanel top = new JPanel(new BorderLayout(Tokens.GAP_2, 0));
            top.setOpaque(false);
            top.add(name, BorderLayout.CENTER);
            top.add(percent, BorderLayout.EAST);

            bar.setFocusable(false);
            bar.setPreferredSize(new Dimension(WIDTH - 96, 4));
            bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 4));

            detail.setFont(FontRegistry.mono(FontRegistry.BASE_SIZE - 1));
            detail.setForeground(Tokens.muted());

            JPanel text = new JPanel();
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            text.setOpaque(false);
            // BoxLayout never grows a JLabel past its preferred width, and
            // centers what it can't stretch — the lines must hug the left,
            // stretch to the row width, and then ellipsize.
            top.setAlignmentX(0f);
            bar.setAlignmentX(0f);
            detail.setAlignmentX(0f);
            top.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
            detail.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
            text.add(top);
            text.add(Box.createVerticalStrut(3));
            text.add(bar);
            text.add(Box.createVerticalStrut(3));
            text.add(detail);
            add(text, BorderLayout.CENTER);

            // Fixed-width side column: the row must not jitter as the
            // button appears and disappears.
            JPanel side = new JPanel(new BorderLayout());
            side.setOpaque(false);
            side.setPreferredSize(new Dimension(26, Tokens.ICON));
            side.setMinimumSize(new Dimension(26, 0));
            side.setMaximumSize(new Dimension(26, Integer.MAX_VALUE));
            side.add(action, BorderLayout.CENTER);
            add(side, BorderLayout.EAST);

            action.addActionListener(e -> {
                if (actionIsRetry) engine.retry(job.id()); else engine.cancel(job.id());
            });
            update();
        }

        void update() {
            TransferJob j = job;
            name.setFullText(j.name());
            name.setToolTipText(j.srcPath() + "  →  " + j.dstPath());

            long size = j.size();
            long done = j.transferred();
            boolean determinate = j.kind() == TransferJob.Kind.FILE && size > 0;
            int pct = determinate ? (int) Math.min(100, done * 100 / size) : 0;
            percent.setText(determinate ? pct + "%" : "");
            bar.setIndeterminate(!determinate && j.state() == TransferJob.State.RUNNING);
            bar.setValue(pct);

            detail.setFullText(detailText(j));
            detail.setForeground(detailColor(j));
            detail.setToolTipText(j.error());

            boolean active = j.isActive();
            boolean failed = j.state() == TransferJob.State.FAILED;
            action.setVisible(active || failed);
            boolean retry = !active && failed;
            if (retry != actionIsRetry) {
                actionIsRetry = retry;
                action.setIcon(Glyphs.icon(retry ? Glyphs.SYNC : Glyphs.CLOSE,
                        Tokens.ICON_SMALL, Tokens::muted));
                action.setToolTipText(retry ? "Retry" : "Cancel");
            }
        }
    }

    private static String detailText(TransferJob j) {
        long size = j.size();
        long done = j.transferred();
        return switch (j.state()) {
            case RUNNING -> j.kind() == TransferJob.Kind.DIRECTORY ? "Scanning…"
                    : size > 0 ? runningDetail(j) : "Starting…";
            case QUEUED -> "Queued";
            case PAUSED -> size > 0
                    ? "Paused — " + Fmt.bytes(done) + " / " + Fmt.bytes(size)
                    : "Paused";
            case DONE -> j.kind() == TransferJob.Kind.DIRECTORY ? "Scanned"
                    : "Done — " + Fmt.bytes(Math.max(size, done));
            case FAILED -> "Failed — " + firstLine(j.error());
            case CANCELLED -> "Cancelled";
            case SKIPPED -> "Skipped";
        };
    }

    /** Speed and ETA lead; the byte counter trails so an ellipsis eats
     *  the least important end first. */
    private static String runningDetail(TransferJob j) {
        String counter = Fmt.bytes(j.transferred()) + " / " + Fmt.bytes(j.size());
        long speed = j.speedBytesPerSec();
        if (speed <= 0) return counter;
        long remaining = Math.max(0, j.size() - j.transferred());
        return Fmt.speed(speed) + " · " + Fmt.eta(remaining / speed) + " left · " + counter;
    }

    private static Color detailColor(TransferJob j) {
        return switch (j.state()) {
            case FAILED -> new Color(0xE5484D);
            case DONE -> ok();
            default -> Tokens.muted();
        };
    }

    private static Color ok() {
        Color c = UIManager.getColor("Actions.Green");
        return c != null ? c : new Color(0x46A758);
    }

    private static String firstLine(String s) {
        if (s == null) return "unknown error";
        return s.split("\n")[0];
    }

    // ---- chrome ----

    private JComponent header() {
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0,
                        UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(0, 0, Tokens.GAP_2, 0)));
        JLabel title = new JLabel("Transfers",
                Glyphs.icon(Glyphs.EXCHANGE, Tokens.ICON_SMALL, Tokens::muted),
                SwingConstants.LEFT);
        title.setFont(FontRegistry.uiMedium());
        head.add(title, BorderLayout.WEST);
        JButton close = ghost(Glyphs.CLOSE, "Close (Esc)");
        close.addActionListener(e -> hidePopup());
        head.add(close, BorderLayout.EAST);
        return head;
    }

    private JComponent footer() {
        JPanel strip = new JPanel(new BorderLayout(Tokens.GAP_2, 0));
        strip.setOpaque(false);
        strip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0,
                        UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(Tokens.GAP_2, 0, 0, 0)));
        summary.setFont(FontRegistry.ui(Tokens.ICON_SMALL));
        summary.setForeground(Tokens.muted());
        summaryNums.setFont(FontRegistry.mono(Tokens.ICON_SMALL));
        summaryNums.setForeground(Tokens.muted());
        JPanel line = new JPanel();
        line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
        line.setOpaque(false);
        // BoxLayout centers what it can't stretch; the lines must hug the
        // left and leave the slack on the right.
        summary.setAlignmentX(0f);
        summaryNums.setAlignmentX(0f);
        line.add(summary);
        line.add(Box.createHorizontalStrut(Tokens.GAP_1));
        line.add(summaryNums);
        strip.add(line, BorderLayout.CENTER);

        pauseAll.addActionListener(e -> engine.pauseAll());
        resumeAll.addActionListener(e -> engine.resumeAll());
        clear.addActionListener(e -> engine.clearFinished());
        JPanel actions = new JPanel();
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        actions.setOpaque(false);
        actions.add(pauseAll);
        actions.add(Box.createHorizontalStrut(Tokens.GAP_1));
        actions.add(resumeAll);
        actions.add(Box.createHorizontalStrut(Tokens.GAP_1));
        actions.add(clear);
        strip.add(actions, BorderLayout.EAST);
        return strip;
    }

    /** Clips {@code s} to {@code width} pixels, ending in an ellipsis. */
    public static String clipToWidth(String s, FontMetrics fm, int width) {
        if (s.isEmpty() || fm.stringWidth(s) <= width) return s;
        String ell = "…";
        int ellW = fm.stringWidth(ell);
        if (ellW >= width) return ell;
        while (!s.isEmpty() && fm.stringWidth(s) + ellW > width) {
            s = s.substring(0, s.length() - 1);
        }
        return s + ell;
    }

    /**
     * A label that ellipsizes its text to whatever width layout gives it.
     * The full text stays the model value (tooltips, tests); the painted
     * text re-fits on every resize and content change.
     */
    private static final class FitLabel extends JLabel {
        private String fullText = "";

        void setFullText(String s) {
            fullText = s == null ? "" : s;
            fitText();
        }

        String fullText() {
            return fullText;
        }

        @Override public void setBounds(int x, int y, int w, int h) {
            super.setBounds(x, y, w, h);
            fitText();
        }

        private void fitText() {
            // Pre-layout there is no width yet — the full text drives the
            // preferred size; the first layout pass clips it.
            if (getWidth() <= 0) {
                setText(fullText);
                return;
            }
            setText(clipToWidth(fullText, getFontMetrics(getFont()), getWidth()));
        }
    }

    /**
     * The rows list. Tracking the viewport width is what keeps long names
     * inside the popup: without it the view grows to the widest row's
     * preferred width and the viewport merely crops it — pushing the
     * percentage, the cancel button, and any long text past the popup's
     * edge.
     */
    private static final class RowsPanel extends JPanel
            implements javax.swing.Scrollable {
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
        @Override public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }
        @Override public int getScrollableUnitIncrement(Rectangle vis, int ori, int dir) {
            return 16;
        }
        @Override public int getScrollableBlockIncrement(Rectangle vis, int ori, int dir) {
            return Math.max(vis.height, 16);
        }
    }

    private static JButton ghost(String glyph, String tooltip) {
        JButton b = new JButton(Glyphs.icon(glyph, Tokens.ICON_SMALL, Tokens::muted));
        b.setToolTipText(tooltip);
        b.setFocusable(false);
        b.putClientProperty("JButton.buttonType", "borderless");
        b.setRolloverEnabled(true);
        return b;
    }

    private static Color popupBorder() {
        Color c = UIManager.getColor("Popup.borderColor");
        return c != null ? c : UIManager.getColor("Component.borderColor");
    }

    // ---- test access (tests live in the default package) ----

    /** One synchronous refresh on the EDT — the same work the swing timer does. */
    public void refreshForTest() { onEdt(this::rebuild); }
    public void toggleForTest() { onEdt(this::toggle); }
    public boolean popupVisibleForTest() { return onEdt(() -> visible); }
    public int rowCountForTest() { return onEdt(rowList::size); }
    /** The footer's number segment (tests): speed/percent text. */
    public String footerNumsTextForTest() { return onEdt(summaryNums::fullText); }
    /** The footer's number segment font (tests) — mono. */
    public java.awt.Font footerNumsFontForTest() { return onEdt(summaryNums::getFont); }
    /** The row's full file name (the model value, never ellipsized). */
    public String rowNameForTest(int i) { return onEdt(() -> rowList.get(i).name.fullText()); }
    /** The name as currently painted — ellipsized when it overflows. */
    public String rowNameDisplayForTest(int i) { return onEdt(() -> rowList.get(i).name.getText()); }
    public int rowPercentForTest(int i) { return onEdt(() -> rowList.get(i).bar.getValue()); }
    public String rowDetailForTest(int i) { return onEdt(() -> rowList.get(i).detail.fullText()); }
    public boolean rowRetryVisibleForTest(int i) {
        return onEdt(() -> rowList.get(i).actionIsRetry && rowList.get(i).action.isVisible());
    }
    public String emptyHintForTest() { return onEdt(() -> rowOrder.isEmpty() ? empty.getText() : null); }
    public Window windowForTest() { return onEdt(() -> window); }
    public int barCountForTest() { return onEdt(() -> countProgressBars(rows)); }

    private static int countProgressBars(Component c) {
        if (c instanceof JProgressBar) return 1;
        if (!(c instanceof java.awt.Container cont)) return 0;
        int n = 0;
        for (Component child : cont.getComponents()) n += countProgressBars(child);
        return n;
    }

    private static void onEdt(Runnable r) {
        if (EventQueue.isDispatchThread()) {
            r.run();
            return;
        }
        try {
            EventQueue.invokeAndWait(r);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static <T> T onEdt(java.util.function.Supplier<T> read) {
        AtomicReference<T> out = new AtomicReference<>();
        onEdt(() -> out.set(read.get()));
        return out.get();
    }
}
