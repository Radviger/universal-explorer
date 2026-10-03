import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.JButton;
import javax.swing.UIManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the ghost-toolbar contract against FlatLaf with the app's own theme
 * defaults: a borderless button paints nothing at rest, paints the theme's
 * translucent hover fill when the model rolls over, and the
 * Button.toolbar.hoverBackground key loads as a translucent color (the
 * properties loader parses #RRGGBBAA — an alpha of 0 or 255 means the hex
 * was written in the wrong component order).
 */
class ToolbarGhostButtonTest {

    @Test
    void borderlessButtonIsGhostAtRestAndFillsOnHover() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());

        Color hoverKey = UIManager.getColor("Button.toolbar.hoverBackground");
        assertTrue(hoverKey != null, "Button.toolbar.hoverBackground must be defined");
        assertTrue(hoverKey.getAlpha() > 0 && hoverKey.getAlpha() < 255,
                "hover fill must be translucent, got alpha=" + hoverKey.getAlpha()
                        + " (hex component order likely wrong)");

        JButton b = new JButton();
        b.putClientProperty("JButton.buttonType", "borderless");
        b.setRolloverEnabled(true);
        b.setSize(30, 30);

        assertTrue(bandInk(b, false) == 0, "rest must paint no border ring or fill");
        assertTrue(bandInk(b, true) > 60, "hover must paint the state fill");
    }

    /** Non-transparent pixels in the 3px edge band of a standalone paint. */
    private static int bandInk(JButton b, boolean rollover) {
        BufferedImage img = new BufferedImage(30, 30, BufferedImage.TYPE_INT_ARGB);
        var model = b.getModel();
        model.setRollover(rollover);
        Graphics2D g = img.createGraphics();
        try {
            b.paint(g);
        } finally {
            g.dispose();
        }
        model.setRollover(false);
        int ink = 0;
        for (int y = 0; y < 30; y++) {
            for (int x = 0; x < 30; x++) {
                if (x >= 3 && y >= 3 && x < 27 && y < 27) continue;
                if ((img.getRGB(x, y) >>> 24) != 0) ink++;
            }
        }
        return ink;
    }
}
