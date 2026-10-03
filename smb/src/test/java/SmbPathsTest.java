import dock.kit.FontRegistry;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.Icon;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SMB session geometry and entry mapping — the pure parts of the SMB
 * stack, testable without a server (the wire paths are exercised live,
 * read-only, against the LAN boxes).
 */
class SmbPathsTest {

    @BeforeAll
    static void fonts() {
        dock.kit.FontRegistry.install();
    }

    @Test
    void pathsNormalizeToServerRootedSlashes() {
        assertEquals("/", dock.smb.SmbPaths.normalize(null));
        assertEquals("/", dock.smb.SmbPaths.normalize(""));
        assertEquals("/Public", dock.smb.SmbPaths.normalize("Public"));
        assertEquals("/Public", dock.smb.SmbPaths.normalize("/Public/"));
        assertEquals("/Public/docs", dock.smb.SmbPaths.normalize("\\Public\\docs\\"));
        assertEquals("/Public/docs", dock.smb.SmbPaths.normalize("/Public//docs"),
                "inner duplicate separators collapse");
    }

    @Test
    void shareAndRestSplitOffTheHead() {
        assertNull(dock.smb.SmbPaths.shareOf("/"));
        assertNull(dock.smb.SmbPaths.restOf("/"));
        assertEquals("Public", dock.smb.SmbPaths.shareOf("/Public"));
        assertEquals("", dock.smb.SmbPaths.restOf("/Public"));
        assertEquals("Public", dock.smb.SmbPaths.shareOf("/Public/docs/a.txt"));
        assertEquals("docs/a.txt", dock.smb.SmbPaths.restOf("/Public/docs/a.txt"));
    }

    @Test
    void parentStopsAtTheServerRoot() {
        assertEquals("/", dock.smb.SmbPaths.parent("/"));
        assertEquals("/", dock.smb.SmbPaths.parent("/Public"));
        assertEquals("/Public", dock.smb.SmbPaths.parent("/Public/docs"));
        assertEquals("/Public", dock.smb.SmbPaths.child("/", "Public"));
        assertEquals("/Public/docs", dock.smb.SmbPaths.child("/Public", "docs"));
    }

    @Test
    void entryMappingReadsDosAttributeBits() {
        var dir = dock.smb.SmbFs.entry("Docs", 0x10, 0, 1_000);
        assertTrue(dir.directory());
        assertFalse(dir.hidden());
        var hidden = dock.smb.SmbFs.entry("secrets", 0x12, 7, 2_000);
        assertTrue(hidden.directory(), "0x12 = directory | hidden");
        assertTrue(hidden.hidden());
        var file = dock.smb.SmbFs.entry("a.txt", 0x80, 123, 3_000);
        assertFalse(file.directory());
        assertFalse(file.hidden());
        assertEquals(123, file.size());
        assertEquals(3_000, file.mtimeMillis());
        assertNull(file.posixPerms(), "SMB has no POSIX bits");
    }

    @Test
    void specNormalizesItself() {
        var spec = new dock.smb.SmbSessions.SmbSpec(
                "nas", 0, " ", "root", "pw".toCharArray(), false, "\\Public\\");
        assertEquals(445, spec.port(), "port 0 becomes the SMB default");
        assertNull(spec.domain(), "blank domain reads as null");
        assertEquals("Public", spec.share(), "share sheds slashes both ways");

        var guest = new dock.smb.SmbSessions.SmbSpec(
                "nas", 445, null, "root", "pw".toCharArray(), true, "");
        assertNull(guest.user(), "guest carries no identity");
        assertNull(guest.password());
        assertNull(guest.share());
    }

    @Test
    void shareRowsClassifyDiskAndSpecial() {
        var normal = new dock.smb.ShareEnumerator.Share("Public", 0, "");
        var printer = new dock.smb.ShareEnumerator.Share("HPLaser", 1, "");
        var admin = new dock.smb.ShareEnumerator.Share("C$", 0x8000_0000, "");
        var dollar = new dock.smb.ShareEnumerator.Share("data$", 0, "");
        assertTrue(normal.disk());
        assertFalse(normal.special());
        assertFalse(printer.disk(), "printer queues are not browsable trees");
        assertTrue(admin.special(), "administrative shares stay hidden until asked");
        assertTrue(dollar.special(), "a trailing $ hides a share, Explorer-style");
    }

    /** The SMB head mark must exist in the shipped font and not be tofu. */
    @Test
    void windowsGlyphExistsAndDiffersFromTheServerMark() {
        var symbol = FontRegistry.symbol(16);
        assertTrue(symbol.canDisplay(dock.kit.Glyphs.WINDOWS.codePointAt(0)),
                "Windows glyph missing from Symbols Nerd Font");
        assertNotEquals(raster(dock.kit.Glyphs.WINDOWS),
                raster(dock.kit.Glyphs.SERVER),
                "SMB and SFTP head marks must paint different shapes");
    }

    private static String raster(String glyph) {
        Icon icon = dock.kit.Glyphs.icon(glyph, 16, () -> Color.WHITE);
        BufferedImage img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        icon.paintIcon(null, g, 0, 0);
        g.dispose();
        return java.util.Arrays.toString(img.getRGB(0, 0, 20, 20, null, 0, 20));
    }
}
