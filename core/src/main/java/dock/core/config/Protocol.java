package dock.core.config;

/**
 * The wire protocols Universal Explorer speaks. New protocols join here first; the
 * connect dialog, session store and launcher all key off this enum, and
 * per-protocol code (fs backends, specs) hangs off the value.
 */
public enum Protocol {
    SFTP("SFTP", 22),
    SMB("SMB", 445),
    WEBDAV("WebDAV", 443),
    FTP("FTP", 21),
    S3("S3", 443);

    private final String label;
    private final int defaultPort;

    Protocol(String label, int defaultPort) {
        this.label = label;
        this.defaultPort = defaultPort;
    }

    public String label() { return label; }

    public int defaultPort() { return defaultPort; }

    /** Parses stored names; unknown or missing values read as SFTP. */
    public static Protocol of(String stored) {
        if (stored == null || stored.isBlank()) return SFTP;
        try {
            return valueOf(stored.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SFTP;
        }
    }
}
