package dock.kit;

import dock.kit.FontRegistry;
import dock.kit.Glyphs;
import dock.kit.Tokens;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;

/** The centered welcome card shown when no session is open. */
public final class EmptyState extends JPanel {

    public EmptyState(Runnable newSessionAction) {
        setLayout(new GridBagLayout());

        CardPanel card = new CardPanel(new GridBagLayout());
        card.setBorder(BorderFactory.createEmptyBorder(Tokens.GAP_8, 56, Tokens.GAP_8, 56));

        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        col.setAlignmentX(CENTER_ALIGNMENT);

        col.add(new IconTile(Glyphs.iconRotated(Glyphs.PLANET, Tokens.ICON_HERO * 1.5f,
                () -> UIManager.getColor("Dock.accent"), Glyphs.PLANET_TILT_DEG), 88));
        col.add(Box.createVerticalStrut(Tokens.GAP_5));
        col.add(heading("No connections"));
        col.add(Box.createVerticalStrut(Tokens.GAP_2));
        col.add(body("Open a session to browse and transfer"));
        col.add(body("files over SFTP."));
        col.add(Box.createVerticalStrut(Tokens.GAP_6));
        col.add(primaryButton("New session", Glyphs.PLUS, newSessionAction));
        col.add(Box.createVerticalStrut(Tokens.GAP_3));
        col.add(hint("or press Ctrl+N"));

        card.add(col);
        add(card);
    }
    private static JLabel heading(String text) {
        JLabel l = new JLabel(text, JLabel.CENTER);
        l.setAlignmentX(CENTER_ALIGNMENT);
        l.setFont(FontRegistry.uiSemiBold(18));
        l.setForeground(UIManager.getColor("Label.foreground"));
        return l;
    }

    private static JLabel body(String text) {
        JLabel l = new JLabel(text, JLabel.CENTER);
        l.setAlignmentX(CENTER_ALIGNMENT);
        l.setFont(FontRegistry.ui());
        l.setForeground(muted());
        return l;
    }

    private static JLabel hint(String text) {
        JLabel l = new JLabel(text, JLabel.CENTER);
        l.setAlignmentX(CENTER_ALIGNMENT);
        l.setFont(FontRegistry.uiMedium(11));
        l.setForeground(muted());
        return l;
    }

    private static JButton primaryButton(String text, String glyph, Runnable action) {
        class AccentButton extends JButton {
            private boolean hover;
            AccentButton() {
                super(text, Glyphs.icon(glyph, Tokens.ICON_SMALL,
                        () -> UIManager.getColor("Dock.accentText")));
                setFont(FontRegistry.uiMedium());
                setForeground(UIManager.getColor("Dock.accentText"));
                setBackground(UIManager.getColor("Dock.accent"));
                setFocusPainted(false);
                setBorderPainted(false);
                setContentAreaFilled(false);
                setOpaque(false);
                setBorder(BorderFactory.createEmptyBorder(8, 22, 8, 22));
                setAlignmentX(CENTER_ALIGNMENT);
                MouseListener ml = new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hover = true;  repaint(); }
                    @Override public void mouseExited(MouseEvent e)  { hover = false; repaint(); }
                };
                addMouseListener(ml);
                addActionListener(e -> action.run());
            }
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                Color base = UIManager.getColor("Dock.accent");
                g2.setColor(hover ? base.darker() : base);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Tokens.ARC, Tokens.ARC);
                g2.dispose();
                super.paintComponent(g);
            }
        }
        return new AccentButton();
    }

    private static Color muted() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : UIManager.getColor("Label.foreground");
    }
}
