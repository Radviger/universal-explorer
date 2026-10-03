import dock.kit.FontRegistry;
import dock.kit.Glyphs;
import dock.commander.KnownFolders;
import dock.commander.KnownFolders.Folder;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shell-folder resolution for the local breadcrumb: registry values
 * override the conventional home placement (OneDrive redirects), keys are
 * normalized for Windows' case-insensitive paths, and every folder's glyph
 * exists in the bundled font and paints a distinct shape.
 */
class KnownFoldersTest {

    private static final String DOWNLOADS_GUID = "{374DE290-123F-4565-9164-39C4925E467B}";

    @BeforeAll
    static void fonts() {
        FontRegistry.install();
        // Another test class may have pinned the table in this JVM; resolve
        // the real one for the snapshot test below.
        KnownFolders.clearForTest();
    }

    @Test
    void shellFolderValuesOverrideTheDefaultHomePlacement() {
        Map<String, Folder> map = KnownFolders.mapFor("C:\\Users\\ada", Map.of(
                "Personal", "C:\\Users\\ada\\OneDrive\\Documents",
                DOWNLOADS_GUID, "D:\\Downloads"));

        assertEquals(Folder.HOME, map.get("c:\\users\\ada"));
        assertEquals(Folder.DOCUMENTS, map.get("c:\\users\\ada\\onedrive\\documents"),
                "registry Documents wins");
        assertEquals(Folder.DOWNLOADS, map.get("d:\\downloads"));
        assertFalse(map.containsKey("c:\\users\\ada\\documents"),
                "the stale in-home slot stays unmapped after a redirect");
        // Folders the registry didn't mention keep the conventional slots.
        assertEquals(Folder.DESKTOP, map.get("c:\\users\\ada\\desktop"));
        assertEquals(Folder.PICTURES, map.get("c:\\users\\ada\\pictures"));
        assertEquals(Folder.MUSIC, map.get("c:\\users\\ada\\music"));
        assertEquals(Folder.VIDEOS, map.get("c:\\users\\ada\\videos"));
    }

    @Test
    void keysAreLowercasedAndShedTrailingSeparators() {
        Map<String, Folder> map = KnownFolders.mapFor("C:\\Users\\ada", Map.of(
                "My Music", "E:\\Media\\Music\\",   // registry values may keep one
                "Desktop", " "));                   // blank entries are skipped

        assertEquals(Folder.MUSIC, map.get("e:\\media\\music"),
                "trailing separator tolerated at registration");
        assertFalse(map.containsKey("c:\\users\\ada\\desktop"),
                "blank shell values map nothing");
        assertEquals(Folder.HOME, map.get("c:\\users\\ada"));
    }

    @Test
    void everyFolderGlyphExistsAndPaintsADistinctShape() {
        List<String> rasters = new java.util.ArrayList<>();
        for (Folder f : Folder.values()) {
            int cp = f.glyph().codePointAt(0);
            assertTrue(FontRegistry.symbol(16).canDisplay(cp),
                    "glyph missing from the bundled font: " + f);
            var icon = Glyphs.icon(f.glyph(), 16, () -> Color.WHITE);
            var img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
            var g = img.createGraphics();
            try {
                icon.paintIcon(null, g, 0, 0);
            } finally {
                g.dispose();
            }
            String raster = Arrays.toString(img.getRGB(0, 0, 20, 20, null, 0, 20));
            assertFalse(rasters.contains(raster),
                    "two folders paint identically: " + f);
            rasters.add(raster);
        }
        assertEquals(Folder.values().length, rasters.size());
    }

    @Test
    void snapshotResolvesThisMachinesShellFolders() {
        Map<String, Folder> map = KnownFolders.snapshot();
        assertEquals(Folder.HOME,
                map.get(System.getProperty("user.home").toLowerCase(Locale.ROOT)),
                "the account home always resolves");
        assertTrue(map.size() >= 5, "conventional fallbacks fill the rest, got " + map.size());
    }
}
