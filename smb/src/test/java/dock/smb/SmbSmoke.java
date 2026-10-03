package dock.smb;

import dock.smb.SmbSessions;
import dock.smb.ShareEnumerator;

/**
 * Manual, READ-ONLY smoke run against a live server — never part of the
 * suite. Enumerates shares, dials a guest session, lists the server root
 * and one share, stats and streams a few bytes of one small file. It must
 * not create, delete, rename, write, or touch timestamps anywhere.
 *
 * Usage: java dock.smb.SmbSmoke &lt;host&gt; [user password domain]
 */
public final class SmbSmoke {

    public static void main(String[] args) throws Exception {
        String host = args[0];
        String user = args.length > 1 ? args[1] : null;
        char[] password = args.length > 2 ? args[2].toCharArray() : null;
        String domain = args.length > 3 ? args[3] : null;

        System.out.println("== share enumeration (netapi32) for \\\\" + host);
        for (ShareEnumerator.Share s : ShareEnumerator.list(host, domain, user, password)) {
            System.out.printf("   %-24s type=0x%08x %-12s %s%n",
                    s.name(), s.type(), s.disk() ? "disk" : "-", s.remark());
        }

        System.out.println("== guest dial (smbj)");
        var spec = new SmbSessions.SmbSpec(host, 445, domain, user, password,
                user == null, null);
        try (var session = SmbSessions.connect(spec)) {
            var fs = session.fs();
            System.out.println("   connected, label=" + fs.label()
                    + ", home=" + fs.home());

            System.out.println("== list /");
            var shares = fs.list("/");
            for (var e : shares) {
                System.out.printf("   %-24s dir=%-5s hidden=%-5s%n",
                        e.name(), e.directory(), e.hidden());
            }

            var firstDisk = shares.stream()
                    .filter(e -> e.directory() && !e.hidden())
                    .findFirst().orElse(null);
            if (firstDisk == null) {
                System.out.println("   (no visible disk share to browse)");
                return;
            }
            String shareRoot = "/" + firstDisk.name();
            System.out.println("== list " + shareRoot);
            var rows = fs.list(shareRoot);
            for (var e : rows) {
                System.out.printf("   %-40s dir=%-5s hidden=%-5s size=%d mtime=%d%n",
                        e.name(), e.directory(), e.hidden(), e.size(), e.mtimeMillis());
            }

            var probe = rows.stream()
                    .filter(e -> !e.directory() && e.size() > 0 && e.size() <= 64 * 1024)
                    .findFirst().orElse(null);
            if (probe != null) {
                String p = shareRoot + "/" + probe.name();
                System.out.println("== stat " + p);
                var st = fs.stat(p);
                System.out.printf("   size=%d mtime=%d dir=%s%n",
                        st.size(), st.mtimeMillis(), st.directory());
                System.out.println("== read " + p + " (first 16 bytes)");
                try (var in = fs.read(p)) {
                    byte[] head = in.readNBytes(16);
                    System.out.println("   " + java.util.HexFormat.of().formatHex(head));
                }
            }
            System.out.println("== done (read-only; nothing was modified)");
        }
    }

    private SmbSmoke() {}
}
