import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.Icon;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Back/Forward paint plain arrows — quieter than the curvy undo/redo pair
 * they briefly wore. Guards against font drift: the codepoints must exist
 * in the exact TTF we ship, paint a real shape, and the toolbar's whole
 * nav group (back, forward, up, refresh — all adjacent) stays pairwise
 * distinct.
 */
class ToolbarGlyphsTest {

    @BeforeAll
    static void fonts() {
        dock.kit.FontRegistry.install();
    }

    @Test
    void plainNavigationGlyphsExistInTheBundledSymbolFont() {
        Font symbol = dock.kit.FontRegistry.symbol(16);
        assertTrue(symbol.canDisplay(dock.kit.Glyphs.ARROW_LEFT.codePointAt(0)),
                "back glyph missing from Symbols Nerd Font");
        assertTrue(symbol.canDisplay(dock.kit.Glyphs.ARROW_RIGHT.codePointAt(0)),
                "forward glyph missing from Symbols Nerd Font");
    }

    @Test
    void toolbarNavigationPaintsDistinctPlainShapes() {
        String back = raster(dock.kit.Glyphs.ARROW_LEFT);
        String forward = raster(dock.kit.Glyphs.ARROW_RIGHT);
        String up = raster(dock.kit.Glyphs.ARROW_UP);
        String refresh = raster(dock.kit.Glyphs.SYNC);

        assertTrue(paintsPixels(back), "back paints a shape");
        assertTrue(paintsPixels(forward), "forward paints a shape");
        // The four adjacent nav buttons must never collide.
        assertFalse(back.equals(forward), "back and forward must differ");
        assertFalse(back.equals(up), "back distinct from up");
        assertFalse(back.equals(refresh), "back distinct from refresh");
        assertFalse(forward.equals(up), "forward distinct from up");
        assertFalse(forward.equals(refresh), "forward distinct from refresh");
        assertFalse(up.equals(refresh), "up distinct from refresh");
        // And they are no longer the attention-grabbing curvy arrows.
        assertFalse(back.equals(raster(dock.kit.Glyphs.UNDO)),
                "back is no longer the curvy arrow");
        assertFalse(forward.equals(raster(dock.kit.Glyphs.REDO)),
                "forward is no longer the curvy arrow");
    }

    private static String raster(String glyph) {
        Icon icon = dock.kit.Glyphs.icon(glyph, 16, () -> Color.WHITE);
        BufferedImage img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        icon.paintIcon(null, g, 0, 0);
        g.dispose();
        StringBuilder px = new StringBuilder();
        for (int argb : img.getRGB(0, 0, 20, 20, null, 0, 20)) {
            if ((argb >>> 24) != 0) px.append(Integer.toHexString(argb)).append(',');
        }
        return px.toString();
    }

    private static boolean paintsPixels(String raster) {
        return !raster.isEmpty();
    }
}
