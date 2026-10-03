package dock.kit;

import dock.kit.FontRegistry;
import dock.kit.Glyphs;
import dock.kit.Tokens;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.geom.RoundRectangle2D;
import java.util.Map;
import java.util.WeakHashMap;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JWindow;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

/**
 * Non-blocking toast notification anchored to the bottom of a window.
 * Multiple toasts stack upwards. Pattern popularized by DJ-Raven's
 * swing-toast-notifications (MIT), implemented fresh for Dock.
 */
public final class Toast {

    private static final Map<Window, Integer> STACKED = new WeakHashMap<>();

    private final JWindow window;
    private final Window owner;
    private final int height;
    private final int holdMs;

    private Toast(Window owner, String message, String glyph) {
        this.owner = owner;
        // Long messages (listing errors and the like) wrap instead of
        // stretching past the window, and stay long enough to actually
        // be read — a fixed 2s flash forced users to re-trigger errors.
        String text = message.length() > 72
                ? "<html><body style='width:" + textWidth(owner) + "px'>"
                        + escape(message).replace("\n", "<br>") + "</body></html>"
                : message;
        this.holdMs = holdMs(message);
        JLabel label = new JLabel(text,
                Glyphs.icon(glyph, Tokens.ICON_SMALL, () -> UIManager.getColor("Dock.accent")),
                JLabel.LEFT);
        label.setFont(FontRegistry.uiMedium());
        label.setIconTextGap(Tokens.GAP_2);
        label.setForeground(UIManager.getColor("Label.foreground"));

        JPanel panel = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(UIManager.getColor("Dock.cardBackground"));
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Tokens.ARC, Tokens.ARC);
                g2.setColor(UIManager.getColor("Dock.cardBorder"));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Tokens.ARC, Tokens.ARC);
                g2.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(9, Tokens.GAP_4, 9, Tokens.GAP_4));
        panel.add(label, BorderLayout.CENTER);

        window = new JWindow(owner);
        window.setContentPane(panel);
        window.setFocusableWindowState(false);
        window.pack();
        height = window.getHeight();
    }

    /** Shows a toast at the bottom-center of {@code anchor}'s window. Safe from any thread. */
    public static void show(java.awt.Component anchor, String message, String glyph) {
        if (anchor == null) return;
        SwingUtilities.invokeLater(() -> {
            Window owner = SwingUtilities.getWindowAncestor(anchor);
            if (owner == null) return;
            new Toast(owner, message, glyph).start();
        });
    }

    private void start() {
        int index = STACKED.merge(owner, 1, Integer::sum);
        int width = window.getWidth();
        int x = owner.getX() + Math.max(0, (owner.getWidth() - width) / 2);
        int y = owner.getY() + owner.getHeight() - height - 36 - (index - 1) * (height + Tokens.GAP_1);
        window.setLocation(x, y);
        window.setShape(new RoundRectangle2D.Double(0, 0, width, height, Tokens.ARC, Tokens.ARC));
        window.setOpacity(0f);
        window.setVisible(true);
        animate(0f, 1f, 140, () -> hold(holdMs, () -> animate(1f, 0f, 140, this::dismiss)));
    }

    /** Reading time: a base flash plus per-character time for long messages, capped. */
    public static int holdMs(String message) {
        long extra = Math.max(0, message.length() - 40) * 55L;
        return (int) Math.min(10_000, 2_200 + extra);
    }

    private static int textWidth(Window owner) {
        int room = (owner == null ? 0 : owner.getWidth()) - 120;
        return Math.max(240, Math.min(560, room));
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void animate(float from, float to, int duration, Runnable done) {
        Timer[] holder = new Timer[1];
        int frames = Math.max(1, duration / 16);
        int[] frame = {0};
        holder[0] = new Timer(16, e -> {
            float t = ++frame[0] / (float) frames;
            if (t >= 1f) {
                window.setOpacity(to);
                holder[0].stop();
                done.run();
                return;
            }
            float eased = 1f - (1f - t) * (1f - t); // ease-out
            window.setOpacity(from + (to - from) * eased);
        });
        holder[0].start();
    }

    private void hold(int ms, Runnable done) {
        Timer t = new Timer(ms, e -> done.run());
        t.setRepeats(false);
        t.start();
    }

    private void dismiss() {
        window.dispose();
        STACKED.merge(owner, -1, Integer::sum);
        if (STACKED.getOrDefault(owner, 0) <= 0) STACKED.remove(owner);
    }
}
