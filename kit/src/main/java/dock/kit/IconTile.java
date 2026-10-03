package dock.kit;

import dock.kit.Glyphs;
import dock.kit.Tokens;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JComponent;
import javax.swing.UIManager;

/** Rounded tinted square with a centered accent glyph — the "app mark" tile. */
public final class IconTile extends JComponent {

    private final int box;
    private final javax.swing.Icon icon;

    public IconTile(String glyph, float glyphSize, int box) {
        this(Glyphs.icon(glyph, glyphSize, () -> UIManager.getColor("Dock.accent")), box);
    }

    /** Tile from a prebuilt mark icon — e.g. the rotated app planet. */
    public IconTile(javax.swing.Icon mark, int box) {
        this.box = box;
        this.icon = mark;
        setPreferredSize(new Dimension(box, box));
        // Without a max size, BoxLayout stretches the tile to the column
        // width while the painted square stays left-aligned in it.
        setMaximumSize(new Dimension(box, box));
        setMinimumSize(new Dimension(box, box));
        setAlignmentX(CENTER_ALIGNMENT);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int arc = Tokens.ARC_CARD;
            g2.setColor(UIManager.getColor("Dock.tileBackground"));
            g2.fillRoundRect(0, 0, box - 1, box - 1, arc, arc);
            // Center by the icon's real rendered size, not the nominal
            // glyph size — glyph ink is narrower than the em square.
            icon.paintIcon(this, g2, (box - icon.getIconWidth()) / 2,
                    (box - icon.getIconHeight()) / 2);
        } finally {
            g2.dispose();
        }
    }
}
