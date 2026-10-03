package dock.smb;

import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import dock.core.config.Protocol;
import dock.core.config.Site;
import dock.core.config.Sites;
import dock.smb.ShareEnumerator;
import java.util.List;

/**
 * Manual, READ-ONLY diagnostic for the root share listing against a saved
 * site — never part of the automated suite. Pulls the site's secret
 * from Credential Manager (the secret itself never prints anywhere), runs
 * the production enumeration, and on failure re-runs the steps
 * individually printing the raw Win32 codes. Nothing is ever written to
 * the server; the IPC$ session is cancelled again immediately.
 *
 * Usage: SmbEnumDiag [siteNameOrHost]
 */
public final class SmbEnumDiag {

    public static void main(String[] args) throws Exception {
        String want = args.length > 0 ? args[0] : "nas";
        List<Site> sites = Sites.load();
        for (Site s : sites) {
            System.out.println("site: " + s.name() + "  protocol=" + s.protocol()
                    + "  host=" + s.host() + "  port=" + s.port()
                    + "  user=" + s.user() + "  domain=" + s.domain()
                    + "  guest=" + s.guest() + "  initialPath=" + s.initialPath());
        }
        Site site = sites.stream()
                .filter(s -> s.protocol() == Protocol.SMB)
                .filter(s -> s.name().toLowerCase().contains(want.toLowerCase())
                        || s.host().toLowerCase().contains(want.toLowerCase()))
                .findFirst().orElse(null);
        if (site == null) {
            System.out.println("no SMB site matching '" + want + "'");
            return;
        }
        String secret = site.guest() ? null
                : dock.core.secrets.CredentialManager.load(site.secretTarget());
        if (!site.guest() && secret == null) {
            System.out.println("no stored secret under " + site.secretTarget());
            return;
        }
        char[] password = secret == null ? null : secret.toCharArray();
        try {
            System.out.println("\n== production ShareEnumerator.list(" + site.host()
                    + ", " + site.domain() + ", " + site.user() + ", <"
                    + (password == null ? 0 : password.length) + " chars>)");
            try {
                for (ShareEnumerator.Share sh : ShareEnumerator.list(
                        site.host(), site.domain(), site.user(), password)) {
                    System.out.println("  " + sh.name() + "  type=0x"
                            + Integer.toHexString(sh.type()) + "  disk=" + sh.disk()
                            + "  special=" + sh.special() + "  remark=" + sh.remark());
                }
                System.out.println("== ok (read-only; nothing modified)");
            } catch (Exception e) {
                System.out.println("!! failed: " + e);
                variants(site, password);
            }
        } finally {
            if (password != null) java.util.Arrays.fill(password, ' ');
        }
    }

    /**
     * Tries the authenticated IPC$ session against the saved host in a few
     * shapes — IP vs hostname, DISK vs ANY resource type, explicit provider
     * — printing each raw code, then enumerates through whichever session
     * took. Read-only; every added session is cancelled again.
     */
    private static void variants(Site site, char[] password) {
        String login = site.domain() == null || site.domain().isBlank()
                ? site.user() : site.domain() + "\\" + site.user();
        String pass = password == null ? null : new String(password);
        String[] hosts = {site.host(), "nas"};
        int[] types = {1, 0};
        for (String host : hosts) {
            if (host == null || host.isBlank()) continue;
            for (int type : types) {
                String ipc = "\\\\" + host + "\\IPC$";
                var res = new com.sun.jna.platform.win32.Winnetwk.NETRESOURCE.ByReference();
                res.dwType = type;
                res.lpRemoteName = ipc;
                int added = ShareEnumerator.MprDock.INSTANCE.WNetAddConnection2W(res,
                        pass, login, 0);
                System.out.println("add " + ipc + " dwType=" + type + " => " + added
                        + " (0x" + Integer.toHexString(added) + ")");
                if (added != 0) continue;
                try {
                    PointerByReference buf = new PointerByReference();
                    IntByReference read = new IntByReference();
                    IntByReference total = new IntByReference();
                    int rc = ShareEnumerator.Netapi32Dock.INSTANCE.NetShareEnum(
                            "\\\\" + host, 1, buf, -1, read, total, null);
                    System.out.println("  NetShareEnum(\\\\" + host + ") => " + rc
                            + "  read=" + read.getValue() + " total=" + total.getValue());
                    if (rc == 0 && buf.getValue() != null && read.getValue() > 0) {
                        ShareEnumerator.Netapi32Dock.ShareInfo1[] rows =
                                (ShareEnumerator.Netapi32Dock.ShareInfo1[])
                                        new ShareEnumerator.Netapi32Dock.ShareInfo1(
                                                buf.getValue()).toArray(read.getValue());
                        for (var row : rows) {
                            System.out.println("    raw: " + row.shi1_netname
                                    + "  type=0x" + Integer.toHexString(row.shi1_type)
                                    + "  remark=" + row.shi1_remark);
                        }
                    }
                    if (buf.getValue() != null) {
                        ShareEnumerator.Netapi32Dock.INSTANCE.NetApiBufferFree(buf.getValue());
                    }
                } finally {
                    int cancelled = ShareEnumerator.MprDock.INSTANCE
                            .WNetCancelConnection2W(ipc, 0, true);
                    System.out.println("  cancel => " + cancelled);
                }
            }
        }
    }

    private SmbEnumDiag() {}
}
