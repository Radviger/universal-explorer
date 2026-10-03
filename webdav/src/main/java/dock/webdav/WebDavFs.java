package dock.webdav;

import com.github.sardine.DavResource;
import com.github.sardine.Sardine;
import com.github.sardine.impl.SardineException;
import dock.core.fs.FileEntry;
import dock.core.fs.FileSystem;
import dock.core.fs.SlashPaths;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import javax.xml.namespace.QName;

/**
 * WebDAV filesystem over one Sardine (Apache HttpClient) client. The
 * pane's "/" is the connect-form base path on the server; everything
 * below is PROPFIND-listed URL geometry. Paths are kept decoded
 * ("/фото/a b.txt") and percent-encoded per segment only when a request
 * URL is built.
 */
public final class WebDavFs implements FileSystem {

    /** The PROPPATCH property setTimes rewrites (RFC 4918, §15.1). */
    private static final QName LAST_MODIFIED = new QName("DAV:", "getlastmodified", "D");

    private final Sardine sardine;
    private final WebDavSessions.WebDavSpec spec;
    /** Request and label URL in one: scheme://host[:port]/base (no trailing slash). */
    private final String baseUrl;

    WebDavFs(Sardine sardine, WebDavSessions.WebDavSpec spec) {
        this.sardine = sardine;
        this.spec = spec;
        boolean defaultPort = spec.secure() ? spec.port() == 443 : spec.port() == 80;
        this.baseUrl = (spec.secure() ? "https://" : "http://") + spec.host()
                + (defaultPort ? "" : ":" + spec.port()) + spec.basePath();
    }

    @Override public String label() { return baseUrl; }
    @Override public boolean remote() { return true; }
    @Override public String separator() { return "/"; }
    @Override public List<String> roots() { return List.of("/"); }
    @Override public String home() { return "/"; }

    @Override public String normalize(String path) { return SlashPaths.normalize(path); }
    @Override public String parent(String path) { return SlashPaths.parent(path); }
    @Override public String child(String dir, String name) { return SlashPaths.child(dir, name); }

    // ---- listing ----

    @Override public List<FileEntry> list(String path) throws IOException {
        String p = normalize(path);
        List<DavResource> all;
        try {
            all = sardine.list(url(p) + "/", 1);
        } catch (SardineException e) {
            throw wrapped(p, e);
        }
        String self = stripSlash(spec.basePath() + p);
        List<FileEntry> out = new ArrayList<>(all.size());
        for (DavResource r : all) {
            if (stripSlash(decoded(r)).equals(self)) continue;
            out.add(entryOf(r));
        }
        return out;
    }

    private static FileEntry entryOf(DavResource r) {
        Long len = r.getContentLength();
        Date mod = r.getModified();
        return new FileEntry(r.getName(), r.isDirectory(),
                len == null ? 0 : len,
                mod == null ? 0 : mod.getTime(),
                null, false);
    }

    // ---- metadata ----

    @Override public FileEntry stat(String path) throws IOException {
        String p = normalize(path);
        List<DavResource> rs;
        try {
            rs = sardine.list(url(p), 0);
        } catch (SardineException e) {
            throw wrapped(p, e);
        }
        if (rs.isEmpty()) throw notFound(p);
        return entryOf(rs.get(0));
    }

    @Override public boolean exists(String path) throws IOException {
        try {
            stat(path);
            return true;
        } catch (IOException e) {
            if (missing(e)) return false;
            throw e;
        }
    }

    private static boolean missing(IOException e) {
        if (e.getCause() instanceof SardineException se) return se.getStatusCode() == 404;
        return e.getMessage() != null && e.getMessage().startsWith("Not found");
    }

    // ---- mutations ----

    @Override public void mkdir(String path) throws IOException {
        String p = normalize(path);
        try {
            sardine.createDirectory(url(p) + "/");
        } catch (SardineException e) {
            throw wrapped(p, e);
        }
    }

    /**
     * Deletes a file or an empty directory. HTTP DELETE would happily
     * remove a whole subtree, so directories are checked first — the
     * same contract the SMB backend enforces.
     */
    @Override public void delete(String path) throws IOException {
        String p = normalize(path);
        if (stat(p).directory() && !list(p).isEmpty()) {
            throw new IOException("Directory not empty: " + p);
        }
        try {
            sardine.delete(url(p));
        } catch (SardineException e) {
            throw wrapped(p, e);
        }
    }

    @Override public void rename(String from, String to) throws IOException {
        String f = normalize(from);
        String t = normalize(to);
        try {
            sardine.move(url(f), url(t));
        } catch (SardineException e) {
            throw wrapped(f, e);
        }
    }

    // ---- streams ----

    @Override public InputStream read(String path) throws IOException {
        String p = normalize(path);
        try {
            return sardine.get(url(p));
        } catch (SardineException e) {
            throw wrapped(p, e);
        }
    }

    /** Resume primitive: a single-byte-range GET instead of a skip-through. */
    @Override public InputStream read(String path, long offset) throws IOException {
        if (offset <= 0) return read(path);
        String p = normalize(path);
        try {
            return sardine.get(url(p), Map.of("Range", "bytes=" + offset + "-"));
        } catch (SardineException e) {
            throw wrapped(p, e);
        }
    }

    /**
     * PUT is a whole-resource operation: the stream rides a pipe into a
     * virtual-thread request (chunked encoding), and close() waits for
     * the server's verdict so failures surface to the writer.
     */
    @Override public OutputStream write(String path, boolean append) throws IOException {
        if (append) throw new IOException("WebDAV replaces whole files — it cannot append.");
        String p = normalize(path);
        PipedInputStream pipeIn = new PipedInputStream(1 << 16);
        PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);
        CountDownLatch done = new CountDownLatch(1);
        IOException[] failure = new IOException[1];
        Thread.ofVirtual().name("dock-webdav-put").start(() -> {
            try (pipeIn) {
                sardine.put(url(p), pipeIn, null, false);
            } catch (IOException e) {
                failure[0] = e instanceof SardineException se ? wrapped(p, se) : e;
            } finally {
                done.countDown();
            }
        });
        return new OutputStream() {
            @Override public void write(int b) throws IOException { pipeOut.write(b); }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                pipeOut.write(b, off, len);
            }
            @Override public void flush() throws IOException { pipeOut.flush(); }
            @Override public void close() throws IOException {
                pipeOut.close();
                try {
                    done.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while finishing the upload.", ie);
                }
                if (failure[0] != null) throw failure[0];
            }
        };
    }

    @Override public void setTimes(String path, long mtimeMillis) throws IOException {
        String p = normalize(path);
        String stamp = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(Instant.ofEpochMilli(mtimeMillis).atZone(ZoneOffset.UTC));
        try {
            sardine.patch(url(p), Map.of(LAST_MODIFIED, stamp));
        } catch (SardineException e) {
            throw wrapped(p, e);
        }
    }

    /** WebDAV has no POSIX mode bits; callers must treat this backend as fixed-mode. */
    @Override public void setPerms(String path, int posix) throws IOException {
        throw new UnsupportedOperationException("WebDAV has no POSIX permissions.");
    }

    // ---- lifecycle ----

    @Override public void close() {
        try {
            sardine.shutdown();
        } catch (Exception ignored) {
            // Best-effort; the OS reclaims the sockets regardless.
        }
    }

    /** One client serves browsing and transfers (HttpClient's pool is thread-safe). */
    @Override public FileSystem streamView() { return this; }

    // ---- URL geometry ----

    String rootUrl() { return baseUrl + "/"; }

    private String url(String p) {
        StringBuilder sb = new StringBuilder(baseUrl);
        for (String seg : p.split("/")) {
            if (!seg.isEmpty()) sb.append('/').append(encode(seg));
        }
        return sb.toString();
    }

    private static String encode(String seg) {
        return java.net.URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String decoded(DavResource r) {
        try {
            return URLDecoder.decode(r.getHref().getRawPath(), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return r.getHref().getPath();
        }
    }

    private static String stripSlash(String p) {
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static IOException notFound(String path) {
        return new IOException("Not found: " + path);
    }

    private static IOException wrapped(String path, SardineException e) {
        String phrase = e.getResponsePhrase() == null ? "" : " " + e.getResponsePhrase().trim();
        String what = switch (e.getStatusCode()) {
            case 401 -> "Authentication failed";
            case 403 -> "Access was refused (403)";
            case 404 -> "Not found";
            case 405 -> "The server does not allow this operation (405)";
            case 409 -> "A conflict blocked the operation (409)";
            case 507 -> "The server is out of space";
            default -> "Server error " + e.getStatusCode() + phrase;
        };
        return new IOException(what + ": " + path, e);
    }
}
