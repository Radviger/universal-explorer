package dock.ui;

import dock.core.transfer.Progress;
import dock.core.transfer.TransferEngine;
import dock.kit.Fmt;
import dock.kit.FontRegistry;
import dock.kit.Glyphs;
import dock.kit.StatusLine;
import dock.kit.ThemeManager;
import dock.kit.Tokens;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.EventQueue;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.Timer;
import javax.swing.UIManager;

/**
 * Slim bar at the bottom of the main window: the status line's hover text
 * (file rows publish their facts here instead of floating tooltips over
 * the table), transfer activity with an overall progress bar, theme
 * control. The bar aggregates every batch still in flight and lingers at
 * its final value for a moment when the burst completes — without that it
 * would vanish at ~97% the instant the last file lands.
 */
public final class StatusBar extends JPanel {

    /** How long a completed burst holds its bar before the footer goes quiet. */
    private static final long LINGER_MS = 2_000;

    private final TransferEngine engine;
    private final JLabel activity = new JLabel();
    /** The burst's speed, its own label so the ticking digits read in
     *  mono and never jitter the sentence next to them. */
    private final JLabel activitySpeed = new JLabel();
    private final JProgressBar progress = new JProgressBar();
    private final JLabel hoverInfo = new JLabel();
    /** The shell's pane toggles, attached once the window is assembled. */
    private Runnable toggleLocalPane;
    private Runnable toggleRemotePane;
    /** The footer's west edge: folds the local pane away — or back. */
    private final JButton localPaneButton = paneButton(() -> toggleLocalPane);
    /** The footer's east edge: the same fold for the remote pane. */
    private final JButton remotePaneButton = paneButton(() -> toggleRemotePane);

    /** Shows the status line's hover text while any is published; file
     *  facts read in the mono font, sentences in the UI font. */
    private final java.util.function.Consumer<String> hoverListener = text -> {
        boolean hovering = text != null && !text.isBlank();
        hoverInfo.setFont(StatusLine.mono() ? FontRegistry.mono() : FontRegistry.ui());
        hoverInfo.setText(hovering ? text : "");
        hoverInfo.setVisible(hovering);
    };

    public StatusBar(TransferEngine engine, Runnable toggleTransfers) {
        this.engine = engine;
        super.setLayout(new BorderLayout(Tokens.GAP_2, 0));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0,
                        UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(4, Tokens.GAP_3, 4, Tokens.GAP_3)));

        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
        left.setOpaque(false);

        // The commander convention: hovering a file row publishes its facts
        // here instead of floating a tooltip over the table. The footer's
        // left side stays empty while nothing is hovered.
        hoverInfo.setFont(FontRegistry.ui());
        hoverInfo.setForeground(UIManager.getColor("Label.foreground"));
        hoverInfo.setVisible(false);
        // The pane toggle sits at the very west edge, before the hover
        // line: the button owns the corner, the reading comes after.
        left.add(localPaneButton);
        left.add(Box.createHorizontalStrut(Tokens.GAP_1));
        left.add(hoverInfo);
        StatusLine.addListener(hoverListener);

        JPanel right = new JPanel();
        right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
        right.setOpaque(false);

        activity.setFont(FontRegistry.uiMedium(Tokens.ICON_SMALL));
        activity.setForeground(muted());
        activity.setBorder(BorderFactory.createEmptyBorder(0, Tokens.GAP_1, 0, Tokens.GAP_1));
        activity.setToolTipText("Show transfers (Ctrl+Alt+Q)");
        activity.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        activity.setVisible(false);
        activitySpeed.setFont(FontRegistry.mono(Tokens.ICON_SMALL));
        activitySpeed.setForeground(muted());
        activitySpeed.setBorder(BorderFactory.createEmptyBorder(0, Tokens.GAP_1, 0, Tokens.GAP_1));
        activitySpeed.setToolTipText("Show transfers (Ctrl+Alt+Q)");
        activitySpeed.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        activitySpeed.setVisible(false);
        java.awt.event.MouseAdapter openPopup = new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                toggleTransfers.run();
            }
        };
        activity.addMouseListener(openPopup);
        activitySpeed.addMouseListener(openPopup);

        // FlatLaf's preferred progress size (146×4) is exactly the slim
        // status-bar shape; the fill color comes from the theme's accent.
        progress.setFocusable(false);
        progress.setVisible(false);
        progress.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        progress.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                toggleTransfers.run();
            }
        });

        // 150ms keeps the bar moving at the same cadence the queue window polls.
        new Timer(150, e -> updateActivity()).start();

        right.add(activity);
        right.add(activitySpeed);
        right.add(Box.createHorizontalStrut(Tokens.GAP_2));
        right.add(progress);
        right.add(Box.createHorizontalStrut(Tokens.GAP_2));
        right.add(verticalSeparator());
        right.add(Box.createHorizontalStrut(Tokens.GAP_2));
        right.add(themeButton());
        right.add(Box.createHorizontalStrut(Tokens.GAP_2));
        right.add(remotePaneButton);

        add(left, BorderLayout.WEST);
        add(right, BorderLayout.EAST);
    }

    private void updateActivity() {
        // The recency window doubles as the linger: a burst that just settled
        // keeps its final numbers (and its bar) for a moment instead of
        // vanishing at ~97% the instant the last file lands.
        Progress p = TransferEngine.overall(engine.snapshot(),
                System.currentTimeMillis(), LINGER_MS);

        boolean active = p.active();
        activity.setVisible(active);
        long bps = p.speedBytesPerSec();
        activitySpeed.setVisible(active && bps > 0);
        if (active) {
            activity.setText("↑↓ " + p.running() + (p.queued() > 0 ? " (+" + p.queued() + ")" : ""));
            if (bps > 0) activitySpeed.setText("· " + Fmt.speed(bps));
        }

        // An indeterminate burst (directory scans only) has nothing worth
        // lingering on once it settles — hide immediately.
        boolean showBar = p.recent() && (active || p.determinate());
        progress.setVisible(showBar);
        if (showBar) {
            progress.setIndeterminate(!p.determinate());
            progress.setValue(p.percent());
            progress.setToolTipText(tooltip(p));
        }
    }

    private static String tooltip(Progress p) {
        StringBuilder sb = new StringBuilder();
        if (p.determinate()) {
            sb.append(Fmt.bytes(p.doneBytes())).append(" of ")
                    .append(Fmt.bytes(p.totalBytes()))
                    .append(" (").append(p.percent()).append("%)");
        } else {
            sb.append("Scanning…");
        }
        if (p.downloads() > 0 || p.uploads() > 0) {
            sb.append(" · ");
            if (p.downloads() > 0) {
                sb.append(p.downloads()).append(p.downloads() == 1 ? " download" : " downloads");
            }
            if (p.downloads() > 0 && p.uploads() > 0) sb.append(", ");
            if (p.uploads() > 0) {
                sb.append(p.uploads()).append(p.uploads() == 1 ? " upload" : " uploads");
            }
        }
        sb.append(" · Show transfers (Ctrl+Alt+Q)");
        return sb.toString();
    }

    // ---- test access (tests live in the default package) ----

    /** One footer poll on the EDT — the same work the swing timer does. */
    public void pollForTest() {
        onEdt(this::updateActivity);
    }

    public boolean hoverVisibleForTest() { return onEdt(hoverInfo::isVisible); }
    public String hoverTextForTest() { return onEdt(hoverInfo::getText); }
    /** The hover label's font (tests): mono for file facts, UI for sentences. */
    public java.awt.Font hoverFontForTest() { return onEdt(hoverInfo::getFont); }
    /** The burst speed segment (tests): its text and its mono font. */
    public String activitySpeedTextForTest() { return onEdt(activitySpeed::getText); }
    public java.awt.Font activitySpeedFontForTest() { return onEdt(activitySpeed::getFont); }

    @Override public void removeNotify() {
        super.removeNotify();
        StatusLine.removeListener(hoverListener);
    }

    public boolean progressVisibleForTest() { return onEdt(progress::isVisible); }
    public int progressValueForTest() { return onEdt(progress::getValue); }
    public boolean progressIndeterminateForTest() { return onEdt(progress::isIndeterminate); }

    /** The footer's west-edge pane toggle (tests). */
    public JButton localPaneButtonForTest() { return localPaneButton; }

    /** The footer's east-edge pane toggle (tests). */
    public JButton remotePaneButtonForTest() { return remotePaneButton; }

    private static void onEdt(Runnable r) {
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

    /** Wires the two edge buttons to the shell's toggles; they stay
     *  disabled until {@link #syncPaneToggles} reports a session under
     *  them. */
    public void setPaneActions(Runnable toggleLocalPane, Runnable toggleRemotePane) {
        this.toggleLocalPane = toggleLocalPane;
        this.toggleRemotePane = toggleRemotePane;
    }

    /** Mirrors the selected session's pane visibility: accented ink while
     *  a pane is folded away, and a button rests while the other pane is
     *  hidden — one pane must stay on screen. */
    public void syncPaneToggles(boolean sessionActive, boolean localHidden,
                                boolean remoteHidden) {
        localPaneButton.setEnabled(sessionActive && !remoteHidden);
        remotePaneButton.setEnabled(sessionActive && !localHidden);
        localPaneButton.setIcon(Glyphs.icon(Glyphs.COLUMNS, Tokens.ICON_SMALL,
                localHidden ? accent() : StatusBar::muted));
        localPaneButton.setToolTipText(
                localHidden ? "Show the local pane" : "Hide the local pane");
        remotePaneButton.setIcon(Glyphs.icon(Glyphs.COLUMNS, Tokens.ICON_SMALL,
                remoteHidden ? accent() : StatusBar::muted));
        remotePaneButton.setToolTipText(
                remoteHidden ? "Show the remote pane" : "Hide the remote pane");
    }

    /** One of the footer's edge buttons: the columns glyph, muted while
     *  the pane shows and accented while it is folded away. Inert until
     *  the shell wires and syncs it. */
    private static JButton paneButton(java.util.function.Supplier<Runnable> action) {
        JButton b = new JButton(
                Glyphs.icon(Glyphs.COLUMNS, Tokens.ICON_SMALL, StatusBar::muted));
        b.setToolTipText("Hide the pane");
        b.putClientProperty("JButton.buttonType", "borderless");
        b.setRolloverEnabled(true);
        b.setEnabled(false);
        b.addActionListener(e -> {
            Runnable r = action.get();
            if (r != null) r.run();
        });
        return b;
    }

    private static java.util.function.Supplier<Color> accent() {
        return () -> UIManager.getColor("Dock.accent");
    }

    private JButton themeButton() {
        JButton b = new JButton(Glyphs.icon(
                ThemeManager.mode() == ThemeManager.Mode.DARK ? Glyphs.SUN : Glyphs.MOON,
                Tokens.ICON_SMALL, () -> muted()));
        b.setToolTipText(ThemeManager.mode() == ThemeManager.Mode.DARK
                ? "Switch to light theme" : "Switch to dark theme");
        b.putClientProperty("JButton.buttonType", "borderless");
        b.setRolloverEnabled(true);
        b.addActionListener(e -> ThemeManager.toggle());
        ThemeManager.addListener(() -> {
            b.setIcon(Glyphs.icon(
                    ThemeManager.mode() == ThemeManager.Mode.DARK ? Glyphs.SUN : Glyphs.MOON,
                    Tokens.ICON_SMALL, () -> muted()));
            b.setToolTipText(ThemeManager.mode() == ThemeManager.Mode.DARK
                    ? "Switch to light theme" : "Switch to dark theme");
        });
        return b;
    }

    private javax.swing.JSeparator verticalSeparator() {
        javax.swing.JSeparator s = new javax.swing.JSeparator(javax.swing.SwingConstants.VERTICAL);
        s.setPreferredSize(new java.awt.Dimension(1, Tokens.ICON_SMALL));
        s.setForeground(UIManager.getColor("Component.borderColor"));
        return s;
    }

    private static Color muted() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : UIManager.getColor("Label.foreground");
    }
}
