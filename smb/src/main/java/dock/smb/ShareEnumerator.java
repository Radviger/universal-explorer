package dock.smb;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Netapi32;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.Winnetwk;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.W32APIOptions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Share enumeration, Windows Explorer's way: NetShareEnum over netapi32,
 * riding an authenticated IPC$ session (WNetAddConnection2) whenever
 * credentials are available — a null session is used only for guest dials.
 * Pure-Java SMB clients cannot list shares (the DCE-RPC srvsvc dialect
 * needed is not implemented by smbj), so this rides the OS — Windows-only
 * by design, like the rest of the app's platform integration.
 */
public final class ShareEnumerator {

    /** One row of the server's share list. */
    public record Share(String name, int type, String remark) {
        public boolean disk() { return (type & 0x03) == 0; }
        /** Special/administrative shares: IPC$, C$, ADMIN$ … */
        public boolean special() { return (type & 0x8000_0000) != 0 || name.endsWith("$"); }
    }

    private ShareEnumerator() {}

    /**
     * Lists the server's shares. With {@code user}/{@code password} an
     * authenticated IPC$ session is established first; without them a null
     * session is attempted. Throws with a readable message when the server
     * refuses enumeration entirely. Bounded: a server that stalls the RPC
     * (observed on anonymous enumeration against Samba boxes that silently
     * drop it) must not hang the pane — the native call itself cannot be
     * interrupted, so it waits on a daemon thread and is abandoned on
     * timeout.
     */
    public static List<Share> list(String host, String domain, String user,
                                   char[] password) throws IOException {
        if (!com.sun.jna.Platform.isWindows()) {
            throw new UnsupportedOperationException(
                    "Listing shares requires Windows (netapi32).");
        }
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "dock-share-enum");
            t.setDaemon(true);
            return t;
        });
        try {
            var future = executor.submit(
                    () -> enumerate(host, domain, user, password));
            return future.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("Listing the shares of \\\\" + host
                    + " timed out — the server did not answer. Connect directly "
                    + "to a share by filling the Opening share field.");
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new IOException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Share enumeration interrupted.", e);
        } finally {
            executor.shutdownNow();
        }
    }

    private static List<Share> enumerate(String host, String domain, String user,
                                         char[] password) throws IOException {
        String server = "\\\\" + host.replace('\\', '/').replace("/", "");
        PointerByReference buf = new PointerByReference();
        IntByReference read = new IntByReference();
        IntByReference total = new IntByReference();
        int rc;
        if (user != null && !user.isBlank()) {
            // Credentials in hand: session-first, always. The null-session
            // attempt that some servers silently drop would burn the whole
            // timeout budget before a retry could ever run (observed against
            // Samba). An existing Windows session to the server (mapped
            // drive) is ridden rather than fought.
            String ipc = server + "\\IPC$";
            int added = addSession(ipc, domain, user, password);
            if (added != 0 && added != WinError.ERROR_SESSION_CREDENTIAL_CONFLICT) {
                throw new IOException(message(host, added));
            }
            try {
                rc = Netapi32Dock.INSTANCE.NetShareEnum(server, 1, buf,
                        -1, read, total, null);
            } finally {
                if (added == 0) {
                    MprDock.INSTANCE.WNetCancelConnection2W(ipc, 0, true);
                }
            }
        } else {
            // Null session — all a guest dial has. Some servers refuse it
            // outright or silently drop the RPC; the outer timeout bounds
            // the drop case.
            rc = Netapi32Dock.INSTANCE.NetShareEnum(server, 1, buf,
                    -1, read, total, null);
        }
        if (rc != 0) {
            throw new IOException(message(host, rc));
        }
        try {
            List<Share> out = new ArrayList<>(read.getValue());
            if (buf.getValue() != null && read.getValue() > 0) {
                Netapi32Dock.ShareInfo1[] rows =
                        (Netapi32Dock.ShareInfo1[]) new Netapi32Dock.ShareInfo1(
                                buf.getValue()).toArray(read.getValue());
                for (Netapi32Dock.ShareInfo1 row : rows) {
                    out.add(new Share(row.shi1_netname, row.shi1_type, row.shi1_remark));
                }
            }
            return out;
        } finally {
            Netapi32Dock.INSTANCE.NetApiBufferFree(buf.getValue());
        }
    }

    private static final int RESOURCETYPE_DISK = 1;

    private static int addSession(String ipc, String domain, String user, char[] password) {
        Winnetwk.NETRESOURCE.ByReference res = new Winnetwk.NETRESOURCE.ByReference();
        res.dwType = RESOURCETYPE_DISK;
        res.lpRemoteName = ipc;
        String login = domain == null || domain.isBlank()
                ? user : domain + "\\" + user;
        return MprDock.INSTANCE.WNetAddConnection2W(res,
                password == null ? null : new String(password), login, 0);
    }

    private static String message(String host, int rc) {
        if (rc == WinError.ERROR_ACCESS_DENIED) {
            return "The server \\\\" + host + " refused to list its shares for this account.";
        }
        if (rc == WinError.ERROR_SESSION_CREDENTIAL_CONFLICT) {
            return "Windows already has a different session to \\\\" + host
                    + " — disconnect mapped drives to it (or reboot) and retry.";
        }
        if (rc == ERROR_NO_NET_OR_BAD_PATHNAME) {
            // Observed with a wedged redirector: Windows itself could not
            // reach the server either, and only a reboot cleared it.
            return "Windows' network stack refused \\\\" + host + " (error 1203) — it "
                    + "may have stuck sessions to this server; Windows itself may have "
                    + "trouble opening \\\\" + host + " until it is rebooted. You can "
                    + "still open a share directly by filling the Opening share field.";
        }
        if (rc == WinError.ERROR_LOGON_FAILURE || rc == WinError.ERROR_BAD_USERNAME) {
            return "The server rejected the user or password.";
        }
        if (rc == WinError.ERROR_BAD_NETPATH || rc == WinError.ERROR_NETWORK_UNREACHABLE
                || rc == 0x00000046) {
            return "\\\\" + host + " did not answer the share-list request — it may "
                    + "refuse anonymous enumeration (provide a user and password) "
                    + "or be unreachable.";
        }
        return "Cannot list shares on \\\\" + host + " (Windows error " + rc + ").";
    }

    private static final int ERROR_NO_NET_OR_BAD_PATHNAME = 1203;

    /** netapi32 with the NetShareEnum mapping jna-platform lacks. */
    public interface Netapi32Dock extends Netapi32 {
        Netapi32Dock INSTANCE = Native.load("netapi32", Netapi32Dock.class,
                W32APIOptions.DEFAULT_OPTIONS);

        int NetShareEnum(String servername, int level, PointerByReference bufptr,
                         int prefMaxLength, IntByReference entriesRead,
                         IntByReference totalEntries, IntByReference resumeHandle);

        int NetApiBufferFree(Pointer buffer);

        /** SHARE_INFO_1: netname, type, remark — wide strings. */
        @Structure.FieldOrder({"shi1_netname", "shi1_type", "shi1_remark"})
        class ShareInfo1 extends Structure {
            public String shi1_netname;
            public int shi1_type;
            public String shi1_remark;

            public ShareInfo1() {}
            public ShareInfo1(Pointer p) { super(p); read(); }
        }
    }

    /** Mpr with the WNetAddConnection2W/WNetCancelConnection2W mapping. */
    public interface MprDock extends com.sun.jna.platform.win32.Mpr {
        MprDock INSTANCE = Native.load("Mpr", MprDock.class, W32APIOptions.DEFAULT_OPTIONS);

        int WNetAddConnection2W(Winnetwk.NETRESOURCE.ByReference resource,
                                String password, String userName, int flags);

        int WNetCancelConnection2W(String name, int flags, boolean force);
    }
}
