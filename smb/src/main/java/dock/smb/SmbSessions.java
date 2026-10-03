package dock.smb;

import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.event.ConnectionClosed;
import com.hierynomus.smbj.event.SMBEventBus;
import dock.core.fs.FileSystem;
import dock.core.session.Session;
import java.io.IOException;
import net.engio.mbassy.listener.Handler;

/**
 * Connects SMB sessions and hands back a protocol-neutral {@link Session}.
 * One connect = one virtual thread's blocking workload; never call from
 * the EDT.
 */
public final class SmbSessions {

    private SmbSessions() {}

    /**
     * Everything needed to (re)establish an SMB session. Passwords live
     * only in memory. {@code guest} dials a guest/anonymous context;
     * {@code share} optionally pins the opening share (empty = browse
     * the server's share list first).
     */
    public record SmbSpec(String host, int port, String domain, String user,
                          char[] password, boolean guest, String share) {
        public SmbSpec {
            if (port <= 0) port = 445;
            if (guest) {
                user = null;
                password = null;
            }
            if (domain != null && domain.isBlank()) domain = null;
            if (share != null) {
                share = share.replace('\\', '/').replace("/", "");
                if (share.isEmpty()) share = null;
            }
            // The spec outlives the dial (reconnects, share enumeration),
            // while callers wipe their password arrays as soon as connect
            // returns — it must own a private copy or those later uses see
            // a blanked-out secret.
            password = password == null ? null : password.clone();
        }
    }

    /** Opens a session; throws IOException with a readable message on failure. */
    public static SmbSession connect(SmbSpec spec) throws IOException {
        SMBEventBus bus = new SMBEventBus();
        return new SmbSession(spec, new SmbFs(spec, bus, dialLine(spec, bus)), bus);
    }

    /**
     * The client config. No soTimeout, on purpose: smbj's async transport
     * bounds <em>every</em> socket read by it — including the idle wait for
     * the next packet — so any finite value tears the connection down once
     * it has been idle that long ("DiskShare has already been closed" on
     * the next browse). Response timing is still bounded per transaction
     * by the 15s timeout, so a dead peer is caught on use, not by idling.
     */
    static com.hierynomus.smbj.SmbConfig config() {
        return com.hierynomus.smbj.SmbConfig.builder()
                .withTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .withReadTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .withWriteTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    static SmbFs.Line dialLine(SmbSpec spec, SMBEventBus bus) throws IOException {
        SMBClient client = new SMBClient(config(), bus);
        try {
            Connection connection = client.connect(spec.host(), spec.port());
            AuthenticationContext auth = spec.guest()
                    ? AuthenticationContext.guest()
                    : new AuthenticationContext(spec.user(), spec.password(),
                            spec.domain() == null ? "" : spec.domain());
            return new SmbFs.Line(client, connection.authenticate(auth));
        } catch (IOException e) {
            closeQuietly(client);
            throw new IOException(smbFailure(spec.host(), e.getMessage()), e);
        } catch (RuntimeException e) {
            // Authentication failures surface as SMBAuthException (unchecked).
            closeQuietly(client);
            throw new IOException(smbFailure(spec.host(), e.getMessage()), e);
        }
    }

    private static void closeQuietly(SMBClient client) {
        try {
            client.close();
        } catch (Exception ignored) {
            // Best-effort; the OS reclaims the socket regardless.
        }
    }

    private static String smbFailure(String host, String detail) {
        if (detail == null || detail.isBlank()) {
            return "Connecting to \\\\" + host + " failed.";
        }
        if (detail.toLowerCase().contains("auth")) {
            return "Authentication failed for \\\\" + host
                    + " — check the user, password, and domain.";
        }
        return "\\\\" + host + ": " + detail;
    }

    /**
     * SMB-backed session. Loss notification rides smbj's event bus: the
     * bus is created here and handed to the client before dialing, and
     * survives reconnects (each redial publishes to the same bus).
     */
    public static final class SmbSession implements Session {

        private final SmbSpec spec;
        private final SMBEventBus bus;
        private final LossListener listener = new LossListener();
        private volatile SmbFs fs;

        private SmbSession(SmbSpec spec, SmbFs fs, SMBEventBus bus) {
            this.spec = spec;
            this.fs = fs;
            this.bus = bus;
        }

        @Override public FileSystem fs() { return fs; }

        @Override public FileSystem reconnect() throws IOException {
            // Heal in place: a fresh instance would strand panes and queued
            // transfer jobs on the dead one — every holder of this fs keeps
            // a working backend.
            fs.redial();
            return fs;
        }

        @Override public void onConnectionLost(Runnable callback) {
            listener.callback = callback;
            bus.subscribe(listener);
        }

        @Override public boolean reconnectable() { return true; }

        @Override public void close() { fs.close(); }

        /**
         * ConnectionClosed events carry only host+port and one bus serves
         * every SMB session in the process, so an event alone proves
         * nothing. It reports the loss of <em>this</em> session only when
         * our own line is down — which also covers the previous connection
         * closing after a reconnect and other sessions to the same server
         * going away. Public/static so the filter is testable without a
         * server.
         */
        public static boolean isLoss(com.hierynomus.smbj.event.ConnectionClosed e,
                                     String host, int port, boolean ownLineAlive) {
            return !ownLineAlive && e.getPort() == port
                    && e.getHostname().equalsIgnoreCase(host);
        }

        private final class LossListener {
            volatile Runnable callback;

            @Handler
            public void on(ConnectionClosed e) {
                Runnable r = callback;
                if (r == null || !isLoss(e, spec.host(), spec.port(), lineAlive())) return;
                r.run();
            }

            private boolean lineAlive() {
                try {
                    SmbFs f = fs;
                    return f != null && f.connectionAlive();
                } catch (Exception e) {
                    return false;
                }
            }
        }
    }
}
