package dock.core.fs;

/**
 * Pure string geometry for "/"-separated remote paths (SMB trees, WebDAV).
 * "/" is the browsing root; no I/O — everything here is testable without
 * a server.
 */
public final class SlashPaths {

    private SlashPaths() {}

    public static String normalize(String path) {
        if (path == null || path.isBlank()) return "/";
        String p = path.replace('\\', '/');
        if (!p.startsWith("/")) p = "/" + p;
        while (p.contains("//")) p = p.replace("//", "/");
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.intern();
    }

    public static boolean isRoot(String path) {
        return "/".equals(normalize(path));
    }

    public static String parent(String path) {
        String p = normalize(path);
        int cut = p.lastIndexOf('/');
        return cut <= 0 ? "/" : p.substring(0, cut);
    }

    public static String child(String dir, String name) {
        String d = normalize(dir);
        return d.equals("/") ? "/" + name : d + "/" + name;
    }
}
