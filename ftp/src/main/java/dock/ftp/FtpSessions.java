package dock.ftp;

import dock.core.fs.FileSystem;
import dock.core.session.Session;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPConnectionClosedException;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;

/**
 * Connects FTP sessions and hands back a protocol-neutral {@link Session}.
 * One connect = one virtual thread's blocking workload; never call from
 * the EDT.
 */
public final class FtpSessions {

    private FtpSessions() {}

    /**
     * Everything needed to (re)establish an FTP session. {@code secure}
     * upgrades control and data channels to TLS — port 990 dials implicit
     * FTPS, anything else negotiates explicit TLS (AUTH TLS) after the
     * plain connect. {@code basePath} is the opening directory ("/" when
     * blank; unlike WebDAV it does not re-root the tree). A blank user
     * means anonymous. The spec outlives the dial (reconnects, per-transfer
     * clients), so it owns a private copy of the password.
     */
    public record FtpSpec(String host, int port, boolean secure, String basePath,
                          String user, char[] password) {
        public FtpSpec {
            if (port <= 0) port = secure ? 990 : 21;
            if (user != null && user.isBlank()) user = null;
            // Anonymous carries nothing worth keeping, like SMB's guest.
            if (user == null) password = null;
            String b = basePath == null ? "" : basePath.trim().replace('\\', '/');
            if (!b.isEmpty()) {
                if (!b.startsWith("/")) b = "/" + b;
                while (b.endsWith("/") && b.length() > 1) b = b.substring(0, b.length() - 1);
            }
            basePath = b;
            password = password == null ? null : password.clone();
        }
    }

    /** Opens a session; throws IOException with a readable message on failure. */
    public static FtpSession connect(FtpSpec spec) throws IOException {
        return new FtpSession(spec, dial(spec));
    }

    static FtpFs dial(FtpSpec spec) throws IOException {
        FTPClient client = dialClient(spec);
        return new FtpFs(client, spec);
    }

    /** One connected, logged-in, binary, passive client. */
    static FTPClient dialClient(FtpSpec spec) throws IOException {
        FTPClient client = spec.secure()
                ? new FTPSClient(spec.port() == 990)
                : new FTPClient();
        client.setConnectTimeout(15_000);
        client.setDataTimeout(Duration.ofSeconds(60));
        // Modern servers speak UTF-8 on the control line (RFC 2640).
        client.setControlEncoding(StandardCharsets.UTF_8);
        try {
            client.connect(spec.host(), spec.port());
            // setSoTimeout touches the live control socket — only valid
            // once connected.
            client.setSoTimeout(60_000);
            if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
                throw new IOException("The server refused the connection ("
                        + trim(client.getReplyString()) + ").");
            }
            // commons-net's credentials are Strings; the copy dies with the
            // client (nothing ever prints it).
            boolean ok = client.login(
                    spec.user() == null ? "anonymous" : spec.user(),
                    spec.user() == null ? "dock@" + spec.host() : new String(spec.password()));
            if (!ok) {
                throw new IOException("Authentication failed for " + label(spec)
                        + " — check the user and password.");
            }
            if (spec.secure()) {
                FTPSClient tls = (FTPSClient) client;
                tls.execPBSZ(0);
                tls.execPROT("P");
            }
            client.setFileType(FTPClient.BINARY_FILE_TYPE);
            client.enterLocalPassiveMode();
            // Keeps NAT/firewall state warm while long transfers stream.
            client.setControlKeepAliveTimeout(Duration.ofSeconds(60));
            return client;
        } catch (IOException e) {
            try {
                client.disconnect();
            } catch (Exception ignored) {
                // Best-effort cleanup of the half-open control line.
            }
            throw readable(label(spec), spec.host(), e);
        }
    }

    static String label(FtpSpec spec) {
        int defaultPort = spec.secure() ? 990 : 21;
        return (spec.secure() ? "ftps://" : "ftp://") + spec.host()
                + (spec.port() == defaultPort ? "" : ":" + spec.port());
    }

    private static String trim(String reply) {
        return reply == null ? "" : reply.trim();
    }

    /** Turns commons-net failures into one actionable sentence. */
    static IOException readable(String label, String host, IOException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException) {
                return new IOException("Unknown host " + host + ".", e);
            }
            if (t instanceof ConnectException || t instanceof SocketTimeoutException) {
                return new IOException("Could not connect to " + label + ".", e);
            }
            if (t instanceof FTPConnectionClosedException) {
                return new IOException("The server closed the connection to " + label
                        + " before answering.", e);
            }
        }
        String msg = e.getMessage();
        return new IOException(label + (msg == null || msg.isBlank()
                ? " failed." : ": " + msg), e);
    }

    /**
     * FTP-backed session. The control channel carries no loss notification:
     * a dropped line surfaces as an IOException on the next operation, and
     * reconnect is a fresh stateless dial.
     */
    public static final class FtpSession implements Session {

        private final FtpSpec spec;
        private volatile FtpFs fs;

        FtpSession(FtpSpec spec, FtpFs fs) {
            this.spec = spec;
            this.fs = fs;
        }

        @Override public FileSystem fs() { return fs; }

        @Override public FileSystem reconnect() throws IOException {
            FtpFs fresh = dial(spec);
            this.fs = fresh;
            return fresh;
        }

        @Override public void onConnectionLost(Runnable callback) {
            // Nothing to subscribe to — see the class javadoc.
        }

        @Override public boolean reconnectable() { return true; }

        @Override public void close() { fs.close(); }
    }
}
