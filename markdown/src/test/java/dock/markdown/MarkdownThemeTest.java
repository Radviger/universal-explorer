package dock.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import dock.kit.FontRegistry;
import dock.syntax.SyntaxKind;
import dock.syntax.SyntaxPalette;
import java.awt.Color;
import javax.swing.UIManager;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The theme keys exist in both themes and the rendered document's colors
 *  come from the snapshot the render ran with. */
class MarkdownThemeTest {

    @AfterEach
    void reset() {
        FlatLaf.setup(new FlatDarkLaf());
    }

    private static void setup(boolean dark) {
        FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(dark ? new FlatDarkLaf() : new FlatLightLaf());
    }

    @Test
    void themeKeysExistInBothThemes() {
        for (boolean dark : new boolean[] {true, false}) {
            setup(dark);
            String which = dark ? "dark" : "light";
            assertNotNull(UIManager.getColor("Dock.mdCodeBackground"), which + " code chip");
            assertNotNull(UIManager.getColor("Dock.accent"), which + " accent");
            assertNotNull(MdTheme.page(), which + " reading surface");
        }
    }

    /** The six token colors exist in both themes, ride the snapshot, and
     *  actually differ between themes — a palette stuck on one theme's
     *  hues would read wrong in the other. */
    @Test
    void codePaletteExistsInBothThemesAndFollowsThem() {
        for (boolean dark : new boolean[] {true, false}) {
            setup(dark);
            String which = dark ? "dark" : "light";
            SyntaxPalette p = MdTheme.current().code();
            for (SyntaxKind kind : SyntaxKind.values())
                assertNotNull(p.of(kind), which + " " + kind);
            assertEquals(UIManager.getColor("Dock.codeKeyword"), p.of(SyntaxKind.KEYWORD),
                    which + " keyword comes from the theme key");
        }
        setup(true);
        Color darkString = MdTheme.current().code().of(SyntaxKind.STRING);
        setup(false);
        assertNotEquals(darkString, MdTheme.current().code().of(SyntaxKind.STRING),
                "token colors follow the theme");
    }

    @Test
    void snapshotTracksTheActiveTheme() {
        setup(true);
        MdTheme dark = MdTheme.current();
        assertEquals(UIManager.getColor("TextPane.background"), dark.surface());
        assertEquals(UIManager.getColor("Dock.mdCodeBackground"), dark.codeBackground());
        assertEquals("JetBrains Mono Bold", dark.headings()[0].getFontName());
        setup(false);
        MdTheme light = MdTheme.current();
        assertEquals(UIManager.getColor("TextPane.background"), light.surface());
        assertTrue(!dark.surface().equals(light.surface()),
                "the reading surface differs between themes");
    }

    /** The reading surface is the Laf's own text-surface color — the same
     *  family as the chrome around it, never a near-black page unrelated
     *  to the app palette (the look this pins down). */
    @Test
    void surfaceSitsInTheAppPalette() {
        for (boolean dark : new boolean[] {true, false}) {
            setup(dark);
            assertEquals(UIManager.getColor("TextPane.background"), MdTheme.page());
            assertTrue(brightness(MdTheme.page()) + 8
                            >= brightness(UIManager.getColor("Panel.background")),
                    (dark ? "dark" : "light") + " page no darker than the chrome");
        }
    }

    /** Body runs carry the theme's text color explicitly — an unset
     *  foreground falls back to Color.black at paint time, which read as
     *  black-on-dark in the dark theme (the bug this pins down). */
    @Test
    void bodyTextCarriesTheThemeText() {
        setup(true);
        StyledDocument d = MarkdownRenderer.render("plain body\n", MdTheme.current());
        assertEquals(UIManager.getColor("Label.foreground"),
                StyleConstants.getForeground(d.getCharacterElement(3).getAttributes()));
        assertTrue(brightness(StyleConstants.getForeground(
                d.getCharacterElement(3).getAttributes())) > 150,
                "dark theme text is light");
        setup(false);
        d = MarkdownRenderer.render("plain body\n", MdTheme.current());
        assertEquals(UIManager.getColor("Label.foreground"),
                StyleConstants.getForeground(d.getCharacterElement(3).getAttributes()));
        assertTrue(brightness(StyleConstants.getForeground(
                d.getCharacterElement(3).getAttributes())) < 80,
                "light theme text is dark");
    }

    @Test
    void linkColorFollowsTheSnapshot() {
        for (boolean dark : new boolean[] {true, false}) {
            setup(dark);
            StyledDocument d = MarkdownRenderer.render("[dock](https://dock.dev)\n",
                    MdTheme.current());
            String t;
            try {
                t = d.getText(0, d.getLength());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            assertEquals(UIManager.getColor("Dock.accent"),
                    StyleConstants.getForeground(d.getCharacterElement(t.indexOf("dock")).getAttributes()),
                    (dark ? "dark" : "light") + " link color");
        }
    }

    private static int brightness(Color c) {
        return (c.getRed() + c.getGreen() + c.getBlue()) / 3;
    }
}
