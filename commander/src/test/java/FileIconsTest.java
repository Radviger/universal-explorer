import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.Icon;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * File-type iconography: the extension map resolves the expected kind
 * (case-insensitively, with compound archive suffixes and dotfile names),
 * every kind's glyph really exists in the bundled Symbols Nerd Font and
 * paints a shape distinct from every other kind, and both themes carry
 * eight legible, pairwise-distinct hues.
 */
class FileIconsTest {

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @Test
    void everyKindMapsToTheExpectedGroupByExtension() {
        assertKind(dock.commander.FileIcons.Kind.IMAGE, "photo.PNG", "cam.jpeg", "icon.svg", "shot.webp", "art.xcf");
        assertKind(dock.commander.FileIcons.Kind.VIDEO, "movie.mkv", "clip.mp4", "demo.mov", "video.webm",
                "dvd.vob", "camera.m2ts", "capture.ogv", "old.divx", "ep.rmvb", "stream.asf");
        assertKind(dock.commander.FileIcons.Kind.AUDIO, "song.flac", "track.mp3", "voice.wav", "sound.opus",
                "memo.amr", "callrec.awb", "cd.ape", "pack.wv", "mix.ac3", "track.dts",
                "import.aifc", "dsd.dsf");
        assertKind(dock.commander.FileIcons.Kind.PDF, "report.pdf");
        assertKind(dock.commander.FileIcons.Kind.WORD, "thesis.docx", "letter.doc", "doc.rtf", "memo.odt");
        assertKind(dock.commander.FileIcons.Kind.EXCEL, "budget.xlsx", "data.csv", "sheet.ods");
        assertKind(dock.commander.FileIcons.Kind.SLIDES, "deck.pptx", "slides.ppt");
        assertKind(dock.commander.FileIcons.Kind.MARKDOWN, "README.md", "notes.markdown",
                "LICENSE", "readme", "CHANGELOG", "Contributing", "code_of_conduct");
        assertKind(dock.commander.FileIcons.Kind.TEXT, "plain.txt", "README.txt");
        assertKind(dock.commander.FileIcons.Kind.JS, "app.js", "lib.mjs", "cjs.cjs");
        assertKind(dock.commander.FileIcons.Kind.TS, "index.ts", "widget.tsx", "mod.mts", "stream.ts");
        assertKind(dock.commander.FileIcons.Kind.JSX, "button.jsx");
        assertKind(dock.commander.FileIcons.Kind.JSON, "package.json", "tsconfig.json");
        assertKind(dock.commander.FileIcons.Kind.HTML, "page.html", "old.htm");
        assertKind(dock.commander.FileIcons.Kind.CSS, "style.css", "theme.less");
        assertKind(dock.commander.FileIcons.Kind.SASS, "site.scss", "old.sass");
        assertKind(dock.commander.FileIcons.Kind.PYTHON, "main.py", "tool.pyw");
        assertKind(dock.commander.FileIcons.Kind.JAVA, "App.java");
        assertKind(dock.commander.FileIcons.Kind.C, "lib.c", "header.h");
        assertKind(dock.commander.FileIcons.Kind.CPP, "app.cpp", "h.hpp", "cxx.cc");
        assertKind(dock.commander.FileIcons.Kind.CSHARP, "Program.cs");
        assertKind(dock.commander.FileIcons.Kind.GO, "tool.go");
        assertKind(dock.commander.FileIcons.Kind.RUST, "lib.rs");
        assertKind(dock.commander.FileIcons.Kind.PHP, "index.php");
        assertKind(dock.commander.FileIcons.Kind.RUBY, "app.rb");
        assertKind(dock.commander.FileIcons.Kind.SWIFT, "View.swift");
        assertKind(dock.commander.FileIcons.Kind.KOTLIN, "Main.kt");
        assertKind(dock.commander.FileIcons.Kind.SHELL, "run.sh", "zsh.zsh", "rc.bash", ".bashrc");
        assertKind(dock.commander.FileIcons.Kind.POWERSHELL, "setup.ps1", "m.psm1");
        assertKind(dock.commander.FileIcons.Kind.BAT, "build.bat", "x.cmd");
        assertKind(dock.commander.FileIcons.Kind.XML, "pom.xml", "doc.xsd", "s.xsl");
        assertKind(dock.commander.FileIcons.Kind.YAML, "ci.yaml", "cfg.yml");
        assertKind(dock.commander.FileIcons.Kind.CONFIG, "app.toml", "x.ini", "y.cfg", "z.properties", ".env");
        assertKind(dock.commander.FileIcons.Kind.SQL, "query.sql");
        assertKind(dock.commander.FileIcons.Kind.DOCKER, "Dockerfile");
        assertKind(dock.commander.FileIcons.Kind.CODE, "Makefile");
        assertKind(dock.commander.FileIcons.Kind.GIT, ".gitignore", ".gitattributes");
        assertKind(dock.commander.FileIcons.Kind.ARCHIVE, "x.zip", "backup.tar.gz", "src.tar.bz2",
                "src.tar.xz", "y.7z", "z.rar", "app.jar", "db.tgz",
                "comic.cbz", "comic.cbr", "Album.CBZ");
        assertKind(dock.commander.FileIcons.Kind.DISK, "ubuntu.iso", "disk.img", "win.vhd", "mac.dmg");
        assertKind(dock.commander.FileIcons.Kind.EXECUTABLE, "setup.exe", "lib.dll", "lib.so", "f.msi",
                "u.deb", "p.rpm", "a.appimage");
        assertKind(dock.commander.FileIcons.Kind.LOG, "server.log", "debug.LOG");
        assertKind(dock.commander.FileIcons.Kind.FILE, "unknown.zzz", "noext", "trailing.", ".dotfile");
    }

    private static void assertKind(dock.commander.FileIcons.Kind expected, String... names) {
        for (String n : names) assertEquals(expected, dock.commander.FileIcons.kindOf(n), n);
    }

    @Test
    void sniffedHeadBytesIdentifyTheContainer() {
        var V = dock.commander.FileIcons.Kind.VIDEO;
        var A = dock.commander.FileIcons.Kind.AUDIO;
        var I = dock.commander.FileIcons.Kind.IMAGE;
        // Video containers: Matroska's EBML, the ISO-BMFF brand box, and
        // RIFF-tagged AVI.
        assertEquals(V, dock.commander.FileIcons.sniffKind(
                new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0}));
        assertEquals(V, dock.commander.FileIcons.sniffKind(
                new byte[] {0, 0, 0, 32, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'}));
        assertEquals(V, dock.commander.FileIcons.sniffKind(
                ascii("RIFF____AVI _____")));
        // Audio: RIFF/WAVE, Ogg, FLAC, tagged and bare MP3 frames.
        assertEquals(A, dock.commander.FileIcons.sniffKind(ascii("RIFF____WAVE")));
        assertEquals(A, dock.commander.FileIcons.sniffKind(ascii("OggS____")));
        assertEquals(A, dock.commander.FileIcons.sniffKind(ascii("fLaC____")));
        assertEquals(A, dock.commander.FileIcons.sniffKind(ascii("ID3ichel")));
        assertEquals(A, dock.commander.FileIcons.sniffKind(
                new byte[] {(byte) 0xFF, (byte) 0xFB, 0x64, 0x00}));
        // Images: PNG, JPEG, GIF, BMP, and a RIFF/WebP.
        assertEquals(I, dock.commander.FileIcons.sniffKind(
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}));
        assertEquals(I, dock.commander.FileIcons.sniffKind(
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}));
        assertEquals(I, dock.commander.FileIcons.sniffKind(ascii("GIF89a__")));
        assertEquals(I, dock.commander.FileIcons.sniffKind(ascii("BM______")));
        assertEquals(I, dock.commander.FileIcons.sniffKind(ascii("RIFF____WEBP")));
        // Documents and archives: PDF, the zip family, RAR, 7z, gzip.
        assertEquals(dock.commander.FileIcons.Kind.PDF,
                dock.commander.FileIcons.sniffKind(ascii("%PDF-1.7")));
        assertEquals(dock.commander.FileIcons.Kind.ARCHIVE,
                dock.commander.FileIcons.sniffKind(ascii("PK__more")));
        assertEquals(dock.commander.FileIcons.Kind.ARCHIVE,
                dock.commander.FileIcons.sniffKind(ascii("Rar!____")));
        assertEquals(dock.commander.FileIcons.Kind.ARCHIVE,
                dock.commander.FileIcons.sniffKind(new byte[] {'7', 'z', (byte) 0xBC,
                        (byte) 0xAF, 0x27, 0x1C, 0, 0}));
        assertEquals(dock.commander.FileIcons.Kind.ARCHIVE,
                dock.commander.FileIcons.sniffKind(new byte[] {0x1F, (byte) 0x8B, 8, 0}));
        // An MPEG transport stream: the 0x47 sync every 188 bytes — three
        // packets' worth, exactly what a 512-byte peek reads.
        byte[] ts = new byte[512];
        java.util.Arrays.fill(ts, (byte) 0x10);
        ts[0] = ts[188] = ts[376] = 0x47;
        assertEquals(V, dock.commander.FileIcons.sniffKind(ts));
        // Nothing known: plain text, a too-short head, one lone 0x47 (a
        // single sync byte is not a transport stream), and a byte pair that
        // trips the MP3 sync but dies on the reserved bitrate nibble.
        assertEquals(dock.commander.FileIcons.Kind.FILE,
                dock.commander.FileIcons.sniffKind(ascii("hello, plain text file")));
        assertEquals(dock.commander.FileIcons.Kind.FILE,
                dock.commander.FileIcons.sniffKind(new byte[] {1, 2, 3}));
        byte[] loneSync = new byte[512];
        java.util.Arrays.fill(loneSync, (byte) 0x10);
        loneSync[0] = 0x47;
        assertEquals(dock.commander.FileIcons.Kind.FILE,
                dock.commander.FileIcons.sniffKind(loneSync));
        assertEquals(dock.commander.FileIcons.Kind.FILE,
                dock.commander.FileIcons.sniffKind(
                        new byte[] {(byte) 0xFF, (byte) 0xE3, (byte) 0xFF, (byte) 0xFF}));
        assertEquals(dock.commander.FileIcons.Kind.FILE,
                dock.commander.FileIcons.sniffKind(null));
    }

    private static byte[] ascii(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    @Test
    void entriesAndTheParentSentinelResolveThroughKindOf() {
        assertEquals(dock.commander.FileIcons.Kind.PARENT,
                dock.commander.FileIcons.kindOf(dock.core.fs.FileEntry.PARENT));
        assertEquals(dock.commander.FileIcons.Kind.FOLDER, dock.commander.FileIcons.kindOf(
                new dock.core.fs.FileEntry("src", true, 0, 0, 0755, false)));
        assertEquals(dock.commander.FileIcons.Kind.PYTHON, dock.commander.FileIcons.kindOf(
                new dock.core.fs.FileEntry("main.py", false, 10, 0, 0644, false)));
        // A directory named like an archive stays a folder.
        assertEquals(dock.commander.FileIcons.Kind.FOLDER, dock.commander.FileIcons.kindOf(
                new dock.core.fs.FileEntry("backup.zip", true, 0, 0, null, false)));
        // A share (SMB server root listing) outranks its directory flag —
        // it is a network object, not a folder.
        assertEquals(dock.commander.FileIcons.Kind.SHARE, dock.commander.FileIcons.kindOf(
                new dock.core.fs.FileEntry("media", true, 0, 0, null, false, true)));
    }

    /**
     * Guards against font-version drift: every kind's codepoint must exist in
     * the exact TTF we ship (canDisplay is false for the .notdef box).
     */
    @Test
    void everyKindGlyphExistsInTheBundledSymbolFont() {
        Font symbol = dock.kit.FontRegistry.symbol(16);
        for (dock.commander.FileIcons.Kind k : dock.commander.FileIcons.Kind.values()) {
            assertTrue(symbol.canDisplay(k.glyph().codePointAt(0)),
                    k + " glyph U+" + Integer.toHexString(k.glyph().codePointAt(0))
                            + " missing from Symbols Nerd Font");
        }
    }

    /**
     * No two kinds may paint the same pixels — catches duplicate codepoints
     * and glyphs that silently resolve to the same icon.
     */
    @Test
    void everyKindPaintsADistinctShape() {
        Map<String, List<dock.commander.FileIcons.Kind>> shapes = new HashMap<>();
        for (dock.commander.FileIcons.Kind k : dock.commander.FileIcons.Kind.values()) {
            shapes.computeIfAbsent(raster(k), s -> new ArrayList<>()).add(k);
        }
        List<String> collisions = new ArrayList<>();
        shapes.forEach((shape, kinds) -> {
            if (kinds.size() > 1) collisions.add(kinds.toString());
        });
        assertTrue(collisions.isEmpty(), "kinds painting identical shapes: " + collisions);
    }

    private static String raster(dock.commander.FileIcons.Kind kind) {
        Icon icon = dock.kit.Glyphs.icon(kind.glyph(), 16, () -> Color.WHITE);
        BufferedImage img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        icon.paintIcon(null, g, 0, 0);
        g.dispose();
        return Arrays.toString(img.getRGB(0, 0, 20, 20, null, 0, 20));
    }

    /** Both themes define the group hues, all distinct and readable. */
    @Test
    void bothThemesDefineDistinctLegibleHues() throws Exception {
        String[] keys = {"Dock.share", "Dock.fileImage", "Dock.fileVideo", "Dock.fileAudio",
                "Dock.fileDoc", "Dock.fileCode", "Dock.fileArchive", "Dock.fileBinary",
                "Dock.fileLog"};
        for (FlatLaf laf : List.of(new FlatDarkLaf(), new FlatLightLaf())) {
            String theme = laf.getClass().getSimpleName();
            SwingUtilities.invokeAndWait(() -> FlatLaf.setup(laf));
            Color accent = UIManager.getColor("Dock.accent");
            Color bg = UIManager.getColor("Table.background");
            if (bg == null) bg = UIManager.getColor("Panel.background");
            assertNotNull(bg, theme + " has a table/panel background");
            Set<Integer> seen = new HashSet<>();
            for (String key : keys) {
                Color c = UIManager.getColor(key);
                assertNotNull(c, theme + " defines " + key);
                assertTrue(c.getRGB() != accent.getRGB(), key + " must differ from the folder accent");
                assertTrue(seen.add(c.getRGB()), key + " duplicates another hue");
                assertTrue(contrast(c, bg) >= 3.0,
                        key + " too quiet on the table background: " + contrast(c, bg));
            }
        }
        SwingUtilities.invokeAndWait(() -> FlatLaf.setup(new FlatDarkLaf()));
    }

    // ---- WCAG relative luminance, used only for the legibility floor ----

    private static double contrast(Color a, Color b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color c) {
        double[] rgb = {c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0};
        double[] weight = {0.2126, 0.7152, 0.0722};
        double l = 0;
        for (int i = 0; i < 3; i++) {
            double v = rgb[i] <= 0.03928 ? rgb[i] / 12.92
                    : Math.pow((rgb[i] + 0.055) / 1.055, 2.4);
            l += weight[i] * v;
        }
        return l;
    }
}
