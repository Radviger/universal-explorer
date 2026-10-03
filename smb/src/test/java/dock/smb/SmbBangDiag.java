package dock.smb;

import dock.core.config.Protocol;
import dock.core.config.Site;
import dock.core.config.Sites;
import dock.core.fs.FileEntry;
import dock.core.secrets.CredentialManager;
import java.util.List;

/**
 * READ-ONLY diagnostic for the live report "SMB fails to open folders
 * with '!' in their name": connects to a saved SMB site, walks the
 * shares until it finds such a folder, and tries to list it — printing
 * the full failure chain when it breaks. Never writes anything and
 * never prints secrets.
 *
 * Usage: java dock.smb.SmbBangDiag [siteNameOrHost]
 */
public final class SmbBangDiag {

    public static void main(String[] args) throws Exception {
        String want = args.length > 0 ? args[0] : "";
        List<Site> sites = Sites.load().stream()
                .filter(s -> s.protocol() == Protocol.SMB)
                .filter(s -> want.isBlank() || s.name().toLowerCase().contains(want.toLowerCase())
                        || s.host().toLowerCase().contains(want.toLowerCase()))
                .toList();
        if (sites.isEmpty()) {
            System.out.println("no SMB site matching '" + want + "'");
            return;
        }
        for (Site site : sites) {
            System.out.println("site: " + site.name() + "  host=" + site.host());
            String pw = CredentialManager.load(site.secretTarget());
            var spec = new SmbSessions.SmbSpec(site.host(), site.port(), site.domain(),
                    site.guest() ? null : site.user(),
                    pw == null ? null : pw.toCharArray(), site.guest(), null);
            try (var session = SmbSessions.connect(spec)) {
                SmbFs fs = (SmbFs) session.fs();
                List<FileEntry> shares = fs.list("/");
                System.out.println("shares: "
                        + shares.stream().map(FileEntry::name).toList());
                int[] probed = {0};
                for (FileEntry share : shares)
                    scan(fs, "/" + share.name(), 0, probed);
                System.out.println("probed " + probed[0] + " '!' folders on " + site.name());
            } catch (Exception e) {
                System.out.println("SITE FAILED: " + describe(e));
            }
        }
    }

    /** Depth-first walk; every directory with '!' in the name is listed
     *  and stat'd. Read-only. */
    private static void scan(SmbFs fs, String path, int depth, int[] probed) throws Exception {
        if (depth > 4) return;
        List<FileEntry> rows;
        try {
            rows = fs.list(path);
        } catch (Exception e) {
            System.out.println("LIST FAILED at " + path + ": " + describe(e));
            return;
        }
        for (FileEntry e : rows) {
            if (!e.directory()) continue;
            String child = fs.child(path, e.name());
            if (e.name().contains("!")) {
                probed[0]++;
                System.out.println("found: " + child);
                try {
                    List<FileEntry> inside = fs.list(child);
                    System.out.println("  list ok: " + inside.size() + " rows");
                } catch (Exception ex) {
                    System.out.println("  LIST FAILED: " + describe(ex));
                }
                try {
                    FileEntry st = fs.stat(child);
                    System.out.println("  stat ok: dir=" + st.directory());
                } catch (Exception ex) {
                    System.out.println("  STAT FAILED: " + describe(ex));
                }
            }
            scan(fs, child, depth + 1, probed);
        }
    }

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder(t.toString());
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
            sb.append("\n  caused by: ").append(cur);
        }
        return sb.toString();
    }
}
