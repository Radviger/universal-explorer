package dock.commander;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import dock.kit.Glyphs;
import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The Windows shell folders worth a shortcut — the places Explorer pins to
 * its sidebar. A local breadcrumb segment that lands on one collapses to an
 * icon-only pathlet (the same slot remote breadcrumbs give the server
 * mark); the folder's name rides the tooltip. Remote sessions never
 * consult this table.
 *
 * Real placement comes from the registry's expanded {@code Shell Folders}
 * values, so OneDrive-redirected Documents resolves to its actual path; a
 * registry-less environment falls back to the conventional slots under
 * {@code user.home}. Resolution runs once per process — the same one-shot
 * registry access ThemeManager already makes.
 */
public final class KnownFolders {

    /** GUID of the Downloads shell folder — the one folder without a name. */
    private static final String DOWNLOADS_GUID = "{374DE290-123F-4565-9164-39C4925E467B}";

    public enum Folder {
        HOME(Glyphs.USER, "Dock.accent", "Home"),
        DESKTOP(Glyphs.MONITOR, "Dock.accent", "Desktop"),
        DOCUMENTS(Glyphs.FILE_TEXT, "Dock.fileDoc", "Documents"),
        DOWNLOADS(Glyphs.DOWNLOAD, "Dock.accent", "Downloads"),
        PICTURES(Glyphs.IMAGE, "Dock.fileImage", "Pictures"),
        MUSIC(Glyphs.MUSIC, "Dock.fileAudio", "Music"),
        VIDEOS(Glyphs.FILM, "Dock.fileVideo", "Videos");

        final String glyph;
        final String colorKey;
        final String label;

        Folder(String glyph, String colorKey, String label) {
            this.glyph = glyph;
            this.colorKey = colorKey;
            this.label = label;
        }

        public String glyph() { return glyph; }
        String colorKey() { return colorKey; }
        String label() { return label; }
    }

    /** Tests pin the table (empty = no collapsing); takes precedence. */
    private static volatile Map<String, Folder> override;
    private static volatile Map<String, Folder> resolved;

    private KnownFolders() {}

    /** Normalized absolute path -> folder, for the folders this account has. */
    public static Map<String, Folder> snapshot() {
        Map<String, Folder> pinned = override;
        if (pinned != null) return pinned;
        Map<String, Folder> m = resolved;
        if (m == null) {
            m = mapFor(System.getProperty("user.home"), shellFolders());
            resolved = m;
        }
        return m;
    }

    public static void installForTest(Map<String, Folder> table) { override = table; }

    public static void clearForTest() { override = null; resolved = null; }

    /** Reads the expanded per-user shell folder paths; empty off-Windows. */
    private static Map<String, String> shellFolders() {
        try {
            Map<String, Object> values = Advapi32Util.registryGetValues(
                    WinReg.HKEY_CURRENT_USER,
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Shell Folders");
            Map<String, String> out = new HashMap<>();
            for (Map.Entry<String, Object> e : values.entrySet()) {
                if (e.getValue() instanceof String s && !s.isBlank()) out.put(e.getKey(), s);
            }
            return out;
        } catch (Throwable t) {
            return Map.of();
        }
    }

    /**
     * Pure resolution (tests drive it with synthetic registry values):
     * shell-folder entries override the conventional {@code home\Name}
     * placement, so a redirected Documents maps at its real path and the
     * stale in-home slot stays unmapped. Keys are lowercase and carry no
     * trailing separator; lookups match Windows path case-insensitively.
     */
    public static Map<String, Folder> mapFor(String home, Map<String, String> shell) {
        Map<String, Folder> out = new HashMap<>();
        put(out, Folder.HOME, home);
        put(out, Folder.DESKTOP, shell.getOrDefault("Desktop", under(home, "Desktop")));
        put(out, Folder.DOCUMENTS, shell.getOrDefault("Personal", under(home, "Documents")));
        put(out, Folder.DOWNLOADS, shell.getOrDefault(DOWNLOADS_GUID, under(home, "Downloads")));
        put(out, Folder.PICTURES, shell.getOrDefault("My Pictures", under(home, "Pictures")));
        put(out, Folder.MUSIC, shell.getOrDefault("My Music", under(home, "Music")));
        put(out, Folder.VIDEOS, shell.getOrDefault("My Video", under(home, "Videos")));
        return Map.copyOf(out);
    }

    private static String under(String home, String name) {
        String sep = home.matches(".*[/\\\\]$") ? "" : File.separator;
        return home + sep + name;
    }

    private static void put(Map<String, Folder> out, Folder f, String path) {
        if (path == null || path.isBlank()) return;
        String key = path.trim();
        while (key.length() > 1 && (key.endsWith("\\") || key.endsWith("/"))) {
            key = key.substring(0, key.length() - 1);
        }
        out.put(key.toLowerCase(Locale.ROOT), f);
    }
}
