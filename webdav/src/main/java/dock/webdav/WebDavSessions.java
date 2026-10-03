package dock.webdav;

import com.github.sardine.impl.SardineException;
import com.github.sardine.impl.SardineImpl;
import dock.core.fs.FileSystem;
import dock.core.session.Session;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClientBuilder;

/**
 * Connects WebDAV sessions and hands back a protocol-neutral
 * {@link Session}. One connect = one virtual thread's blocking workload;
 * never call from the EDT.
 */
public final class WebDavSessions {

    private WebDavSessions() {}

    /**
     * Everything needed to (re)establish a WebDAV session. {@code secure}
     * picks https over http; {@code basePath} is the collection the session
     * is rooted at (e.g. "/dav" — the pane's "/" is that path on the
     * server). A blank user means anonymous. The spec outlives the dial
     * (reconnects), so it must own a private copy of the password.
     */
    public record WebDavSpec(String host, int port, boolean secure, String basePath,
                             String user, char[] password) {
        public WebDavSpec {
            if (port <= 0) port = secure ? 443 : 80;
            if (user != null && user.isBlank()) user = null;
            // Anonymous carries nothing worth keeping, like SMB's guest.
            if (user == null) password = null;
            String b = basePath == null ? "" : basePath.trim().replace('\\', '/');
            if (!b.isEmpty()) {
                if (!b.startsWith("/")) b = "/" + b;
                while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
            }
            basePath = b;
            password = password == null ? null : password.clone();
        }
    }

    /** Opens a session; throws IOException with a readable message on failure. */
    public static WebDavSession connect(WebDavSpec spec) throws IOException {
        return new WebDavSession(spec, dial(spec));
    }

    static WebDavFs dial(WebDavSpec spec) throws IOException {
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(15_000)
                .setSocketTimeout(60_000)
                .setConnectionRequestTimeout(15_000)
                .build();
        HttpClientBuilder builder = HttpClientBuilder.create().setDefaultRequestConfig(config);
        // Sardine's credentials are Strings; the copy is unavoidable and
        // dies with the client (nothing ever prints it).
        SardineImpl sardine = spec.user() == null
                ? new SardineImpl(builder)
                : new SardineImpl(builder, spec.user(), new String(spec.password()));
        WebDavFs fs = new WebDavFs(sardine, spec);
        try {
            // The URL overload parses host/port; the String overload treats
            // its argument as a bare hostname and would never match.
            sardine.enablePreemptiveAuthentication(java.net.URI.create(fs.rootUrl()).toURL());
            if (!fs.exists("/")) {
                throw new IOException("Nothing is served at " + fs.label()
                        + " — check the path.");
            }
            return fs;
        } catch (IOException e) {
            fs.close();
            throw readable(fs.label(), spec.host(), e);
        } catch (RuntimeException e) {
            fs.close();
            throw readable(fs.label(), spec.host(),
                    new IOException(String.valueOf(e.getMessage()), e));
        }
    }

    /** Turns Sardine/HttpClient failures into one actionable sentence. */
    static IOException readable(String label, String host, IOException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException) {
                return new IOException("Unknown host " + host + ".", e);
            }
            if (t instanceof ConnectException || t instanceof SocketTimeoutException) {
                return new IOException("Could not connect to " + label + ".", e);
            }
            // The fs layer wraps protocol failures; the verdict is in the cause.
            if (t instanceof SardineException se) {
                return switch (se.getStatusCode()) {
                    case 401 -> new IOException("Authentication failed for " + label
                            + " — check the user and password.", e);
                    case 403 -> new IOException("The server refused access to " + label
                            + " (403).", e);
                    case 404 -> new IOException("Nothing is served at " + label
                            + " — check the path.", e);
                    default -> new IOException(label + ": " + se.getStatusCode()
                            + (se.getResponsePhrase() == null || se.getResponsePhrase().isBlank()
                                    ? "" : " " + se.getResponsePhrase()), e);
                };
            }
        }
        String msg = e.getMessage();
        return new IOException(label + (msg == null || msg.isBlank()
                ? " failed." : ": " + msg), e);
    }

    /**
     * WebDAV-backed session. HTTP carries no persistent line, so there is
     * no spontaneous loss notification: failed operations surface as
     * IOExceptions, and the next request simply works once the network is
     * back.
     */
    public static final class WebDavSession implements Session {

        private final WebDavSpec spec;
        private volatile WebDavFs fs;

        WebDavSession(WebDavSpec spec, WebDavFs fs) {
            this.spec = spec;
            this.fs = fs;
        }

        @Override public FileSystem fs() { return fs; }

        @Override public FileSystem reconnect() throws IOException {
            WebDavFs fresh = dial(spec);
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
