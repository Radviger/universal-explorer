package dock.smb;

import dock.core.fs.SlashPaths;

/**
 * Pure string geometry for SMB paths: "/" is the server root (the share
 * list), "/Share" a share root, "/Share/dir/file" a path inside the
 * share. The generic trio lives in {@link SlashPaths}; only the
 * share-aware split is SMB's own. No I/O — everything here is testable
 * without a server.
 */
public final class SmbPaths {

    private SmbPaths() {}

    public static String normalize(String path) { return SlashPaths.normalize(path); }

    public static boolean isRoot(String path) { return SlashPaths.isRoot(path); }

    public static String parent(String path) { return SlashPaths.parent(path); }

    public static String child(String dir, String name) { return SlashPaths.child(dir, name); }

    /** The share component, or null at the server root. */
    public static String shareOf(String path) {
        String p = normalize(path);
        if (p.equals("/")) return null;
        int cut = p.indexOf('/', 1);
        return cut < 0 ? p.substring(1) : p.substring(1, cut);
    }

    /** The path inside the share ("" at a share root), or null at the server root. */
    public static String restOf(String path) {
        String p = normalize(path);
        if (p.equals("/")) return null;
        int cut = p.indexOf('/', 1);
        return cut < 0 ? "" : p.substring(cut + 1);
    }

    /** "/Public", "Docs" -> "/Public/Docs" (a share name pins the head). */
    public static String insideShare(String share, String rest) {
        String r = rest == null ? "" : rest.replace('\\', '/');
        while (r.startsWith("/")) r = r.substring(1);
        return r.isEmpty() ? "/" + share : "/" + share + "/" + r;
    }
}
