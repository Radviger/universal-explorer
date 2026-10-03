package dock.s3;

import dock.core.fs.FileSystem;
import dock.core.session.Session;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;

/**
 * Connects S3 sessions and hands back a protocol-neutral
 * {@link Session}. One connect = one virtual thread's blocking
 * workload; never call from the EDT.
 */
public final class S3Sessions {

    private S3Sessions() {}

    /**
     * Everything needed to (re)establish an S3 session. {@code secure}
     * picks https over http, {@code region} scopes the signature
     * ("us-east-1" works on every S3-compatible store; R2 wants "auto"),
     * {@code bucket} is the opening bucket (null lists all of them).
     * The spec outlives the dial (reconnects), so it must own a private
     * copy of the secret.
     */
    public record S3Spec(String host, int port, boolean secure, String region,
                         String accessKey, char[] secretKey, String bucket) {
        public S3Spec {
            if (port <= 0) port = secure ? 443 : 80;
            region = region == null || region.isBlank()
                    ? "us-east-1" : region.trim().toLowerCase();
            if (accessKey != null && accessKey.isBlank()) accessKey = null;
            String b = bucket == null ? "" : bucket.trim().replace('\\', '/');
            while (b.startsWith("/")) b = b.substring(1);
            while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
            bucket = b.isEmpty() ? null : b;
            secretKey = secretKey == null ? null : secretKey.clone();
        }
    }

    /** Opens a session; throws IOException with a readable message on failure. */
    public static S3Session connect(S3Spec spec) throws IOException {
        return new S3Session(spec, dial(spec));
    }

    static S3Fs dial(S3Spec spec) throws IOException {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        S3Fs fs = new S3Fs(http, spec);
        try {
            fs.list("/");    // the probe: proves endpoint, credentials, and region scope
            return fs;
        } catch (IOException | RuntimeException e) {
            fs.close();
            throw readable(fs.label(), spec.host(), e);
        }
    }

    /** Turns transport failures into one actionable sentence; protocol
     *  failures already carry their own readable verdicts. */
    static IOException readable(String label, String host, Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException) {
                return new IOException("Unknown host " + host + ".", e);
            }
            if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException) {
                return new IOException("Could not connect to " + label + ".", e);
            }
        }
        if (e instanceof IOException io && io.getMessage() != null
                && !io.getMessage().isBlank()) {
            return io;
        }
        String msg = e.getMessage();
        return new IOException(label + (msg == null || msg.isBlank()
                ? " failed." : ": " + msg), e);
    }

    /**
     * S3-backed session. HTTP carries no persistent line, so there is no
     * spontaneous loss notification: failed operations surface as
     * IOExceptions, and the next request simply works once the network
     * is back.
     */
    public static final class S3Session implements Session {

        private final S3Spec spec;
        private volatile S3Fs fs;

        S3Session(S3Spec spec, S3Fs fs) {
            this.spec = spec;
            this.fs = fs;
        }

        @Override public FileSystem fs() { return fs; }

        @Override public FileSystem reconnect() throws IOException {
            S3Fs fresh = dial(spec);
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
