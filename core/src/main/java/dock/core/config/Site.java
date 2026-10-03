package dock.core.config;

import java.util.Map;

/**
 * A saved connection definition. Secrets live in the OS credential store.
 * {@code useAgent} marks agent-based auth (Windows OpenSSH agent / YubiKey)
 * — those sites keep no secret at all. The per-protocol fields are nullable:
 * {@code domain} and {@code guest} carry SMB auth, {@code initialPath} is
 * the opening directory (an SMB share, a WebDAV base path), and
 * {@code secure} marks WebDAV over https. {@code lastUsed} (epoch millis,
 * 0 = never) drives the launcher's recency order. {@code lastLocalPath}
 * and {@code lastRemotePath} are the path memory — the directories the
 * panes last showed on this site, written by the app as navigation lands
 * (never by the connect form) and reopened on the next connect in place
 * of {@code initialPath}; sessions.json files written before a field
 * existed load as 0/false/null. {@code playback} is the player's
 * bookmark — where playback stood on this site's files, keyed by full
 * path with the file's mtime as the staleness guard. {@code region}
 * scopes S3's request signatures (null means the endpoint's own
 * default, us-east-1).
 */
public record Site(String name, Protocol protocol, String host, int port, String user,
                   String keyPath, boolean useAgent, String domain, String initialPath,
                   boolean guest, boolean secure, long lastUsed,
                   String lastLocalPath, String lastRemotePath,
                   Map<String, Playback> playback, String region) {

    /** Where playback stood on one file, and the file's mtime then — a
     *  mismatch means the bookmark belongs to a file that no longer
     *  exists. */
    public record Playback(long positionMs, long mtimeMs) {}

    public Site(String name, Protocol protocol, String host, int port, String user,
                String keyPath, boolean useAgent, String domain, String initialPath,
                boolean guest, boolean secure, long lastUsed,
                String lastLocalPath, String lastRemotePath) {
        this(name, protocol, host, port, user, keyPath, useAgent, domain, initialPath,
                guest, secure, lastUsed, lastLocalPath, lastRemotePath, null, null);
    }

    public Site(String name, Protocol protocol, String host, int port, String user,
                String keyPath, boolean useAgent, String domain, String initialPath,
                boolean guest, boolean secure, long lastUsed) {
        this(name, protocol, host, port, user, keyPath, useAgent, domain, initialPath,
                guest, secure, lastUsed, null, null, null, null);
    }

    public Site(String name, Protocol protocol, String host, int port, String user,
                String keyPath, boolean useAgent, String domain, String initialPath,
                boolean guest, long lastUsed) {
        this(name, protocol, host, port, user, keyPath, useAgent, domain, initialPath,
                guest, false, lastUsed);
    }

    public Site(String name, String host, int port, String user, String keyPath) {
        this(name, Protocol.SFTP, host, port, user, keyPath, false, null, null, false,
                false, 0);
    }

    public Site(String name, String host, int port, String user, String keyPath,
                boolean useAgent, long lastUsed) {
        this(name, Protocol.SFTP, host, port, user, keyPath, useAgent, null, null, false,
                false, lastUsed);
    }

    public Site {
        if (protocol == null) protocol = Protocol.SFTP;
        if (port <= 0) port = protocol.defaultPort();
        if (name == null || name.isBlank()) {
            name = (user == null || user.isBlank()) ? host : user + "@" + host;
        }
        if (lastUsed < 0) lastUsed = 0;
        // The agent holds the keys; a stale keyPath from a former mode
        // must not resurrect key-file auth.
        if (useAgent) keyPath = null;
        if (guest) user = "";
        if (lastLocalPath != null && lastLocalPath.isBlank()) lastLocalPath = null;
        if (lastRemotePath != null && lastRemotePath.isBlank()) lastRemotePath = null;
    }

    public Site withLastUsed(long epochMillis) {
        return new Site(name, protocol, host, port, user, keyPath, useAgent,
                domain, initialPath, guest, secure, epochMillis,
                lastLocalPath, lastRemotePath, playback, region);
    }

    /** The path memory: where the panes last stood on this site. */
    public Site withPaths(String localDir, String remoteDir) {
        return new Site(name, protocol, host, port, user, keyPath, useAgent,
                domain, initialPath, guest, secure, lastUsed, localDir, remoteDir,
                playback, region);
    }

    /** The player's bookmark shelf. */
    public Site withPlayback(Map<String, Playback> playback) {
        return new Site(name, protocol, host, port, user, keyPath, useAgent,
                domain, initialPath, guest, secure, lastUsed,
                lastLocalPath, lastRemotePath, playback, region);
    }

    /** Credential Manager target for this site's password/passphrase.
     *  Pre-rename "Dock/…" targets still load: CredentialManager falls
     *  back to the legacy twin and copies the secret forward. */
    public String secretTarget() {
        return "Universal Explorer/" + name;
    }

    /** Credential Manager target for the key passphrase. */
    public String keySecretTarget() {
        return "Universal Explorer/" + name + "/key";
    }
}
