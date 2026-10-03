package dock.commander;

import dock.kit.Glyphs;
import dock.kit.Tokens;
import dock.core.fs.FileEntry;
import java.awt.Color;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.Icon;
import javax.swing.UIManager;

/**
 * File-type iconography: one glyph plus one hue per kind of file, keyed by
 * extension (and a handful of exact dotfile names such as {@code .gitignore}).
 * Glyphs come from the Font Awesome family for generic kinds and the Seti
 * family for source files; hues resolve from {@code Dock.file*} theme keys so
 * both themes stay legible without per-icon hardcoding.
 *
 * <p>Icons are cached per kind and size, and their color is resolved through
 * a supplier at paint time — a theme switch recolors every row without
 * rebuilding the cache or invalidating renderers.
 */
public final class FileIcons {

    private FileIcons() {}

    /**
     * Every icon shape Dock knows. The color key is a Dock.* UIManager entry
     * shared by all kinds of one group ({@code null} paints muted like the
     * generic file); folders and the parent shortcut keep the accent.
     */
    public enum Kind {
        PARENT(Glyphs.FOLDER_OPEN, "Dock.accent"),
        FOLDER(Glyphs.FOLDER, "Dock.accent"),

        // An exported share (SMB server root): navigable like a directory,
        // but a network object — its own glyph and hue say so.
        SHARE(Glyphs.SHARE_ALT, "Dock.share"),

        IMAGE(Glyphs.FILE_IMAGE, "Dock.fileImage"),
        VIDEO(Glyphs.FILE_VIDEO, "Dock.fileVideo"),
        AUDIO(Glyphs.FILE_AUDIO, "Dock.fileAudio"),

        PDF(Glyphs.FILE_PDF, "Dock.fileDoc"),
        WORD(Glyphs.FILE_WORD, "Dock.fileDoc"),
        EXCEL(Glyphs.FILE_EXCEL, "Dock.fileDoc"),
        SLIDES(Glyphs.FILE_SLIDES, "Dock.fileDoc"),
        MARKDOWN(Glyphs.SETI_MARKDOWN, "Dock.fileDoc"),
        TEXT(Glyphs.FILE_TEXT, "Dock.fileDoc"),

        JS(Glyphs.SETI_JS, "Dock.fileCode"),
        TS(Glyphs.SETI_TS, "Dock.fileCode"),
        JSX(Glyphs.SETI_REACT, "Dock.fileCode"),
        JSON(Glyphs.SETI_JSON, "Dock.fileCode"),
        HTML(Glyphs.HTML5, "Dock.fileCode"),
        CSS(Glyphs.SETI_CSS, "Dock.fileCode"),
        SASS(Glyphs.SETI_SASS, "Dock.fileCode"),
        PYTHON(Glyphs.SETI_PYTHON, "Dock.fileCode"),
        JAVA(Glyphs.SETI_JAVA, "Dock.fileCode"),
        C(Glyphs.SETI_C, "Dock.fileCode"),
        CPP(Glyphs.SETI_CPP, "Dock.fileCode"),
        CSHARP(Glyphs.SETI_CSHARP, "Dock.fileCode"),
        GO(Glyphs.SETI_GO, "Dock.fileCode"),
        RUST(Glyphs.SETI_RUST, "Dock.fileCode"),
        PHP(Glyphs.SETI_PHP, "Dock.fileCode"),
        RUBY(Glyphs.SETI_RUBY, "Dock.fileCode"),
        SWIFT(Glyphs.SETI_SWIFT, "Dock.fileCode"),
        KOTLIN(Glyphs.SETI_KOTLIN, "Dock.fileCode"),
        SHELL(Glyphs.SETI_SHELL, "Dock.fileCode"),
        POWERSHELL(Glyphs.SETI_POWERSHELL, "Dock.fileCode"),
        BAT(Glyphs.TERMINAL, "Dock.fileCode"),
        XML(Glyphs.SETI_XML, "Dock.fileCode"),
        YAML(Glyphs.SLIDERS, "Dock.fileCode"),
        CONFIG(Glyphs.SETI_CONFIG, "Dock.fileCode"),
        SQL(Glyphs.DATABASE, "Dock.fileCode"),
        CODE(Glyphs.FILE_CODE, "Dock.fileCode"),
        DOCKER(Glyphs.SETI_DOCKER, "Dock.fileCode"),
        GIT(Glyphs.SETI_GIT, "Dock.fileCode"),

        ARCHIVE(Glyphs.ARCHIVE, "Dock.fileArchive"),
        DISK(Glyphs.HDD, "Dock.fileArchive"),
        EXECUTABLE(Glyphs.CUBE, "Dock.fileBinary"),
        LOG(Glyphs.SCROLL, "Dock.fileLog"),

        FILE(Glyphs.FILE, null);

        final String glyph;
        final String colorKey;

        Kind(String glyph, String colorKey) {
            this.glyph = glyph;
            this.colorKey = colorKey;
        }

        public String glyph() { return glyph; }
    }

    /** Compound suffixes the plain last-extension rule would misread. */
    private static final String[] COMPOUND_ARCHIVES = {
            ".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst", ".tar.lz", ".tar.7z", ".tar.lzma"
    };

    /** Extensionless (or dot-) names with a meaning of their own. */
    private static final Map<String, Kind> FILENAMES = buildFileNames();

    private static Map<String, Kind> buildFileNames() {
        Map<String, Kind> m = new HashMap<>();
        m.put("dockerfile", Kind.DOCKER);
        m.put("makefile", Kind.CODE);
        m.put("cmakelists.txt", Kind.CONFIG);
        m.put(".gitignore", Kind.GIT);
        m.put(".gitattributes", Kind.GIT);
        m.put(".gitmodules", Kind.GIT);
        m.put(".gitconfig", Kind.GIT);
        m.put(".env", Kind.CONFIG);
        m.put(".editorconfig", Kind.CONFIG);
        m.put(".bashrc", Kind.SHELL);
        m.put(".zshrc", Kind.SHELL);
        m.put(".profile", Kind.SHELL);
        // README, LICENSE and the rest of the repo convention set open in
        // the markdown reader — one shared list with the reader itself.
        for (String n : dock.markdown.MarkdownDocs.NAMES) m.put(n, Kind.MARKDOWN);
        return Map.copyOf(m);
    }

    private static final Map<String, Kind> EXTENSIONS = buildExtensions();

    private static Map<String, Kind> buildExtensions() {
        Map<String, Kind> m = new HashMap<>();
        put(m, Kind.IMAGE, "png jpg jpeg gif webp svg ico bmp tif tiff heic heif avif xcf psd");
        // .ts/.mts deliberately stay with TypeScript (Kind.TS below): code
        // routing wins, a camera file landing there gets the editor's clean
        // binary refusal, and m2ts covers the streamable transport streams.
        put(m, Kind.VIDEO, "mp4 mkv mov avi webm wmv flv m4v mpg mpeg 3gp"
                + " m2ts vob ogv asf divx rm rmvb");
        put(m, Kind.AUDIO, "mp3 flac wav ogg oga m4a aac wma opus mid midi aiff"
                + " amr awb ape wv ac3 dts aifc dsf");
        put(m, Kind.PDF, "pdf");
        put(m, Kind.WORD, "doc docx odt rtf");
        put(m, Kind.EXCEL, "xls xlsx csv ods");
        put(m, Kind.SLIDES, "ppt pptx odp");
        put(m, Kind.MARKDOWN, "md markdown");
        put(m, Kind.TEXT, "txt text");
        put(m, Kind.JS, "js mjs cjs");
        put(m, Kind.TS, "ts mts cts tsx");
        put(m, Kind.JSX, "jsx");
        put(m, Kind.JSON, "json jsonc geojson");
        put(m, Kind.HTML, "html htm");
        // less shares CSS's shape: Nerd Fonts maps seti-json and seti-less to
        // the same glyph, and json already claims it.
        put(m, Kind.CSS, "css less");
        put(m, Kind.SASS, "scss sass");
        put(m, Kind.PYTHON, "py pyw pyi");
        put(m, Kind.JAVA, "java");
        put(m, Kind.C, "c h");
        put(m, Kind.CPP, "cpp cc cxx hpp hh");
        put(m, Kind.CSHARP, "cs");
        put(m, Kind.GO, "go");
        put(m, Kind.RUST, "rs");
        put(m, Kind.PHP, "php");
        put(m, Kind.RUBY, "rb ruby");
        put(m, Kind.SWIFT, "swift");
        put(m, Kind.KOTLIN, "kt kts");
        put(m, Kind.SHELL, "sh bash zsh fish");
        put(m, Kind.POWERSHELL, "ps1 psm1");
        put(m, Kind.BAT, "bat cmd");
        put(m, Kind.XML, "xml xsd xsl xslt plist");
        put(m, Kind.YAML, "yaml yml");
        put(m, Kind.CONFIG, "ini cfg conf toml properties");
        put(m, Kind.SQL, "sql");
        put(m, Kind.ARCHIVE,
                "zip zipx 7z rar cbz cbr gz bz2 xz zst tgz tbz tbz2 txz lz4 jar war ear");
        put(m, Kind.DISK, "iso img vhd vhdx dmg");
        put(m, Kind.EXECUTABLE, "exe msi dll so dylib bin sys com scr app appimage deb rpm apk");
        put(m, Kind.LOG, "log");
        return Map.copyOf(m);
    }

    private static void put(Map<String, Kind> m, Kind kind, String exts) {
        for (String ext : exts.split(" ")) m.put(ext, kind);
    }

    /** Resolves a listing entry: the parent shortcut, shares, directories, then names. */
    public static Kind kindOf(FileEntry e) {
        if (e.equals(FileEntry.PARENT)) return Kind.PARENT;
        if (e.share()) return Kind.SHARE;
        if (e.directory()) return Kind.FOLDER;
        return kindOf(e.name());
    }

    /** Case-insensitive name → kind; unknown names fall back to the plain page. */
    public static Kind kindOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        Kind exact = FILENAMES.get(n);
        if (exact != null) return exact;
        for (String suffix : COMPOUND_ARCHIVES) {
            if (n.endsWith(suffix)) return Kind.ARCHIVE;
        }
        int dot = n.lastIndexOf('.');
        if (dot > 0 && dot < n.length() - 1) {
            Kind kind = EXTENSIONS.get(n.substring(dot + 1));
            if (kind != null) return kind;
        }
        return Kind.FILE;
    }

    // ---- content sniffing ----

    private static final byte[] EBML    = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};
    private static final byte[] PNG     = {(byte) 0x89, 0x50, 0x4E, 0x47};
    private static final byte[] JPEG    = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GZIP    = {0x1F, (byte) 0x8B};
    private static final byte[] SEVEN_Z = {0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C};
    private static final byte[] RAR     = ascii("Rar!");
    private static final byte[] RIFF    = ascii("RIFF");
    private static final byte[] WAVE    = ascii("WAVE");
    private static final byte[] AVI     = ascii("AVI ");
    private static final byte[] WEBP    = ascii("WEBP");
    private static final byte[] OGGS    = ascii("OggS");
    private static final byte[] FLAC    = ascii("fLaC");
    private static final byte[] ID3     = ascii("ID3");
    private static final byte[] FTYP    = ascii("ftyp");
    private static final byte[] GIF8    = ascii("GIF8");
    private static final byte[] PDF     = ascii("%PDF");
    private static final byte[] ZIP     = ascii("PK");

    private static byte[] ascii(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    /**
     * Identifies a file from its first bytes — the fallback for names the
     * extension map can't place (camera files with the extension stripped,
     * extension-less blobs). Consulted at open time only, on one short
     * read; listings never pay for it. {@link Kind#FILE} means "nothing
     * known" and leaves the decision to the next surface.
     */
    public static Kind sniffKind(byte[] head) {
        if (head == null || head.length < 4) return Kind.FILE;
        if (sig(head, 0, EBML)) return Kind.VIDEO;              // Matroska: mkv/webm
        if (head.length >= 12 && sig(head, 4, FTYP)) return Kind.VIDEO;  // ISO-BMFF: mp4/mov/m4v
        if (head.length >= 12 && sig(head, 0, RIFF)) {          // RIFF family, by payload tag
            if (sig(head, 8, WAVE)) return Kind.AUDIO;
            if (sig(head, 8, AVI)) return Kind.VIDEO;
            if (sig(head, 8, WEBP)) return Kind.IMAGE;
        }
        if (sig(head, 0, OGGS)) return Kind.AUDIO;
        if (sig(head, 0, FLAC)) return Kind.AUDIO;
        if (sig(head, 0, ID3)) return Kind.AUDIO;               // tagged mp3
        if (mp3Frame(head)) return Kind.AUDIO;                  // bare frame sync
        if (sig(head, 0, PNG)) return Kind.IMAGE;
        if (sig(head, 0, JPEG)) return Kind.IMAGE;
        if (sig(head, 0, GIF8)) return Kind.IMAGE;
        if (head[0] == 'B' && head[1] == 'M') return Kind.IMAGE;  // BMP
        if (sig(head, 0, PDF)) return Kind.PDF;
        if (sig(head, 0, ZIP)) return Kind.ARCHIVE;              // zip family
        if (sig(head, 0, RAR)) return Kind.ARCHIVE;
        if (sig(head, 0, SEVEN_Z)) return Kind.ARCHIVE;
        if (sig(head, 0, GZIP)) return Kind.ARCHIVE;
        if (transportStream(head)) return Kind.VIDEO;            // 0x47 every 188 bytes
        return Kind.FILE;
    }

    private static boolean sig(byte[] head, int off, byte[] magic) {
        if (head.length < off + magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (head[off + i] != magic[i]) return false;
        }
        return true;
    }

    /** An MPEG audio frame header: the 11-bit sync plus reserved-bit holes. */
    private static boolean mp3Frame(byte[] h) {
        if (h.length < 4 || (h[0] & 0xFF) != 0xFF || (h[1] & 0xE0) != 0xE0) return false;
        int version = (h[1] >> 3) & 0x03, layer = (h[1] >> 1) & 0x03;
        int bitrate = (h[2] >> 4) & 0x0F, rate = (h[2] >> 2) & 0x03;
        return version != 0x01 && layer != 0x00
                && bitrate != 0x00 && bitrate != 0x0F && rate != 0x03;
    }

    /** An MPEG transport stream: the 0x47 sync byte in three 188-byte packets. */
    private static boolean transportStream(byte[] h) {
        return h.length >= 377 && (h[0] & 0xFF) == 0x47
                && (h[188] & 0xFF) == 0x47 && (h[376] & 0xFF) == 0x47;
    }

    private record CacheKey(Kind kind, float size) {}

    private static final ConcurrentHashMap<CacheKey, Icon> CACHE = new ConcurrentHashMap<>();

    /** The table-row icon (16px) for a kind. */
    public static Icon icon(Kind kind) {
        return icon(kind, Tokens.ICON);
    }

    /** A themed icon for a kind at an arbitrary size (dialog headers, etc.). */
    public static Icon icon(Kind kind, float size) {
        return CACHE.computeIfAbsent(new CacheKey(kind, size), key -> {
            Kind k = key.kind();
            return Glyphs.icon(k.glyph, key.size(),
                    k.colorKey == null ? FileIcons::muted
                            : () -> UIManager.getColor(k.colorKey));
        });
    }

    private static Color muted() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : UIManager.getColor("Label.foreground");
    }
}
