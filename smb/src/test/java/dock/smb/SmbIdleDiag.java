package dock.smb;

/**
 * Manual, READ-ONLY idle check against a live server — never part of the
 * suite. Lists, idles past the old 60s soTimeout that used to tear idle
 * lines down, then lists again and reports whether the line survived and
 * whether the listing rode a healed line. It must not create, delete,
 * rename, write, or touch timestamps anywhere.
 *
 * Usage: java dock.smb.SmbIdleDiag &lt;host&gt; [idleMillis]
 */
public final class SmbIdleDiag {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "nas";
        long idleMs = args.length > 1 ? Long.parseLong(args[1]) : 75_000;
        var spec = new SmbSessions.SmbSpec(host, 445, null, null, null, true, null);
        try (var session = SmbSessions.connect(spec)) {
            SmbFs fs = (SmbFs) session.fs();
            System.out.println("== connected " + fs.label());
            String share = listRootAndOneShare(fs, null);

            System.out.println("== idling " + idleMs + " ms"
                    + " (the old soTimeout killed the line at 60s)");
            Thread.sleep(idleMs);

            boolean alive = fs.connectionAlive();
            System.out.println("== line alive after idle: " + alive);
            listRootAndOneShare(fs, share);
            System.out.println("== alive after listing: " + fs.connectionAlive()
                    + (alive ? "" : " (the listing rode a healed line)"));
            System.out.println("== done (read-only; nothing was modified)");
        }
    }

    /**
     * Lists the server root and, when {@code knownShare} is set or a share
     * this guest may read exists, that share's root too. Share opens are
     * read-only attempts; guest may be denied some shares — the first
     * readable one wins.
     */
    private static String listRootAndOneShare(SmbFs fs, String knownShare) throws Exception {
        var shares = fs.list("/");
        System.out.println("== list / -> " + shares.size() + " shares");
        String pick = knownShare;
        if (pick == null) {
            for (var e : shares) {
                if (!e.directory() || e.hidden()) continue;
                try {
                    System.out.println("== list /" + e.name() + " -> "
                            + fs.list("/" + e.name()).size() + " entries");
                    return e.name();
                } catch (Exception denied) {
                    System.out.println("== /" + e.name() + " not readable by guest, skipping");
                }
            }
        } else {
            System.out.println("== list /" + pick + " -> "
                    + fs.list("/" + pick).size() + " entries");
        }
        return pick;
    }

    private SmbIdleDiag() {}
}
