import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Color;
import java.awt.Font;
import javax.swing.UIManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The archive view's presentation contract: both themes ship a translucent
 * amber tint for the mount's exit row in the same hue as the archive icons
 * (the properties loader parses #RRGGBBAA — pinned here against format
 * drift), and the archive mark exists in the bundled symbol font as a real
 * shape distinct from the zipped-page glyph it sits beside.
 */
class ArchivePresentationTest {

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
    }

    @Test
    void bothThemesTintTheExitRowInTranslucentArchiveAmber() {
        for (FlatLaf laf : new FlatLaf[] {new FlatDarkLaf(), new FlatLightLaf()}) {
            FlatLaf.setup(laf);
            Color tint = UIManager.getColor("Dock.archiveRowTint");
            Color amber = UIManager.getColor("Dock.fileArchive");
            assertNotNull(tint, laf.getDescription() + " ships Dock.archiveRowTint");
            assertNotNull(amber, laf.getDescription() + " ships Dock.fileArchive");
            assertTrue(tint.getAlpha() > 0 && tint.getAlpha() < 255,
                    laf.getDescription() + ": the tint must stay translucent");
            assertEquals(amber.getRed(), tint.getRed(), laf.getDescription() + " tint hue");
            assertEquals(amber.getGreen(), tint.getGreen(), laf.getDescription() + " tint hue");
            assertEquals(amber.getBlue(), tint.getBlue(), laf.getDescription() + " tint hue");
        }
        FlatLaf.setup(new FlatDarkLaf());    // leave the suite in its usual skin
    }

    @Test
    void theArchiveMarkShipsInTheFontAndLeadsTheArchiveKind() {
        Font symbol = dock.kit.FontRegistry.symbol(16);
        assertTrue(symbol.canDisplay(dock.kit.Glyphs.ARCHIVE.codePointAt(0)),
                "archive glyph missing from the bundled Symbols Nerd Font");
        assertSame(dock.kit.Glyphs.ARCHIVE, dock.commander.FileIcons.Kind.ARCHIVE.glyph(),
                "the archive kind wears the box");
        String box = raster(dock.kit.Glyphs.ARCHIVE);
        assertTrue(paintsPixels(box), "the box paints a shape");
        assertNotEquals(raster(dock.kit.Glyphs.FILE_ARCHIVE), box,
                "the box differs from the zipped page beside it");
    }

    private static String raster(String glyph) {
        javax.swing.Icon icon = dock.kit.Glyphs.icon(glyph, 16, () -> Color.WHITE);
        var img = new java.awt.image.BufferedImage(20, 20,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        icon.paintIcon(null, g, 2, 2);
        g.dispose();
        var sb = new StringBuilder();
        for (int y = 0; y < 20; y += 2) {
            for (int x = 0; x < 20; x += 2) {
                sb.append((img.getRGB(x, y) & 0xFF000000) != 0 ? '#' : '.');
            }
        }
        return sb.toString();
    }

    private static boolean paintsPixels(String raster) { return raster.indexOf('#') >= 0; }
}
