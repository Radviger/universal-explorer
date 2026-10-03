package dock.s3;

import dock.core.fs.SlashPaths;

/**
 * Pure string geometry for S3 paths: "/" is the endpoint root (the
 * bucket list), "/Bucket" a bucket root, "/Bucket/prefix/file" a key
 * inside. The generic trio lives in {@link SlashPaths}; only the
 * bucket-aware split is S3's own. No I/O — everything here is testable
 * without a server.
 */
public final class S3Paths {

    private S3Paths() {}

    public static String normalize(String path) { return SlashPaths.normalize(path); }

    public static boolean isRoot(String path) { return SlashPaths.isRoot(path); }

    public static String parent(String path) { return SlashPaths.parent(path); }

    public static String child(String dir, String name) { return SlashPaths.child(dir, name); }

    /** The bucket component, or null at the endpoint root. */
    public static String bucketOf(String path) {
        String p = normalize(path);
        if (p.equals("/")) return null;
        int cut = p.indexOf('/', 1);
        return cut < 0 ? p.substring(1) : p.substring(1, cut);
    }

    /** The key inside the bucket ("" at a bucket root), or null at the
     *  endpoint root. A key's own slashes are part of the name — this is
     *  a prefix, not a path. */
    public static String restOf(String path) {
        String p = normalize(path);
        if (p.equals("/")) return null;
        int cut = p.indexOf('/', 1);
        return cut < 0 ? "" : p.substring(cut + 1);
    }
}
