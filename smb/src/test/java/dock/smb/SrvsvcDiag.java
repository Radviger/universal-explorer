package dock.smb;

import dock.core.config.Protocol;
import dock.core.config.Site;
import dock.core.config.Sites;
import java.util.HexFormat;
import java.util.List;

/**
 * Manual, READ-ONLY diagnostic for the srvsvc share listing — never part
 * of the automated suite. Dials the saved site with its stored
 * secret (the secret never prints anywhere) over pure smbj — no WinAPI,
 * no Windows redirector — lists the shares, and dumps raw RPC PDUs if
 * parsing fails. Nothing is ever written to the server.
 *
 * Usage: SrvsvcDiag [siteNameOrHost]
 */
public final class SrvsvcDiag {

    public static void main(String[] args) throws Exception {
        String want = args.length > 0 ? args[0] : "nas";
        Site site = Sites.load().stream()
                .filter(s -> s.protocol() == Protocol.SMB)
                .filter(s -> s.name().toLowerCase().contains(want.toLowerCase())
                        || s.host().toLowerCase().contains(want.toLowerCase()))
                .findFirst().orElse(null);
        if (site == null) {
            System.out.println("no SMB site matching '" + want + "'");
            return;
        }
        System.out.println("site " + site.name() + " -> " + site.host() + ":"
                + site.port() + " user=" + site.user() + " guest=" + site.guest());
        String secret = site.guest() ? null
                : dock.core.secrets.CredentialManager.load(site.secretTarget());
        if (!site.guest() && secret == null) {
            System.out.println("no stored secret under " + site.secretTarget());
            return;
        }
        char[] pw = secret == null ? null : secret.toCharArray();
        try {
            SmbSessions.SmbSpec spec = new SmbSessions.SmbSpec(
                    site.host(), site.port(), site.domain(), site.user(),
                    pw, site.guest(), null);
            SmbSessions.SmbSession session = SmbSessions.connect(spec);
            try {
                SmbFs fs = (SmbFs) session.fs();
                System.out.println("\n== raw srvsvc (bind + NetrShareEnum level 1)");
                List<ShareEnumerator.Share> shares;
                try {
                    shares = Srvsvc.list(fs.smbSession(), pdu -> {
                        System.out.println("  pdu(" + pdu.length + "B):");
                        for (int i = 0; i < pdu.length; i += 32) {
                            System.out.println("    " + HexFormat.ofDelimiter(" ").formatHex(
                                    java.util.Arrays.copyOfRange(pdu, i,
                                            Math.min(pdu.length, i + 32))));
                        }
                    });
                } catch (Exception e) {
                    System.out.println("  !! srvsvc failed: " + e);
                    System.out.println("\n== production list(/) (srvsvc + fallback)");
                    for (var entry : fs.list("/")) {
                        System.out.println("  " + entry.name() + (entry.directory() ? "/" : ""));
                    }
                    return;
                }
                for (ShareEnumerator.Share s : shares) {
                    System.out.println("  " + s.name() + "  type=0x"
                            + Integer.toHexString(s.type()) + "  disk=" + s.disk()
                            + "  special=" + s.special() + "  remark=" + s.remark());
                }
                System.out.println("\n== production list(/) (srvsvc + fallback)");
                for (var entry : fs.list("/")) {
                    System.out.println("  " + entry.name() + (entry.directory() ? "/" : ""));
                }
                System.out.println("== ok (read-only; nothing modified)");
            } finally {
                session.close();
            }
        } finally {
            if (pw != null) java.util.Arrays.fill(pw, ' ');
        }
    }

    private SrvsvcDiag() {}
}
