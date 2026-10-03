package dock.kit;

import dock.kit.Tokens;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import javax.swing.JPanel;
import javax.swing.UIManager;

/** Rounded, slightly-elevated surface card (DJ-Raven style). */
public class CardPanel extends JPanel {

    public CardPanel(LayoutManager layout) {
        super(layout);
        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            int arc = Tokens.ARC_CARD;
            int w = getWidth() - 1;
            int h = getHeight() - 1;
            g2.setColor(UIManager.getColor("Dock.cardBackground"));
            g2.fillRoundRect(0, 0, w, h, arc, arc);
            g2.setColor(UIManager.getColor("Dock.cardBorder"));
            g2.drawRoundRect(0, 0, w, h, arc, arc);
        } finally {
            g2.dispose();
        }
    }
}
