package dock.ftp;

import dock.core.fs.FileEntry;
import dock.core.fs.FileSystem;
import dock.core.fs.SlashPaths;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPConnectionClosedException;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

/**
 * FTP filesystem over one commons-net control connection. Paths are
 * absolute "/"-style; every op is guarded by one lock because FTP allows
 * a single command at a time per control line. Transfers never share the
 * browsing client: {@link #streamView()} dials a dedicated logged-in
 * client per transfer (FTP's one-data-stream-per-control rule).
 */
public final class FtpFs implements FileSystem {

    /** MFMT's 14-digit timeval (YYYYMMDDhhmmss). */
    private static final DateTimeFormatter MFMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final FTPClient client;
    private final FtpSessions.FtpSpec spec;
    /** Serializes commands on the shared browsing control line. */
    private final Object lock = new Object();
    private final String label;

    FtpFs(FTPClient client, FtpSessions.FtpSpec spec) {
        this.client = client;
        this.spec = spec;
        this.label = FtpSessions.label(spec);
    }

    @Override public String label() { return label; }
    @Override public boolean remote() { return true; }
    @Override public String separator() { return "/"; }
    @Override public List<String> roots() { return List.of("/"); }
    @Override public String home() { return spec.basePath().isEmpty() ? "/" : spec.basePath(); }

    @Override public String normalize(String path) { return SlashPaths.normalize(path); }
    @Override public String parent(String path) { return SlashPaths.parent(path); }
    @Override public String child(String dir, String name) { return SlashPaths.child(dir, name); }

    // ---- listing ----

    @Override public List<FileEntry> list(String path) throws IOException {
        String p = normalize(path);
        synchronized (lock) {
            FTPFile[] rows = io(() -> client.listFiles(p));
            ensureListOk(p, rows);
            List<FileEntry> out = new ArrayList<>(rows.length);
            for (FTPFile row : rows) {
                if (row == null) continue;
                String name = row.getName();
                if (name.equals(".") || name.equals("..")) continue;
                out.add(new FileEntry(name, row.isDirectory(),
                        Math.max(0, row.getSize()), millis(row.getTimestamp()),
                        null, name.startsWith(".")));
            }
            return out;
        }
    }

    private void ensureListOk(String p, FTPFile[] rows) throws IOException {
        // listFiles swallows protocol errors into an empty array; the
        // pending reply code still tells whether the directory exists.
        if (rows.length > 0 || FTPReply.isPositiveCompletion(client.getReplyCode())) return;
        throw wrapped(p, client.getReplyCode(), client.getReplyString());
    }

    private static long millis(Calendar ts) {
        return ts == null ? 0 : ts.getTimeInMillis();
    }

    // ---- metadata ----

    @Override public FileEntry stat(String path) throws IOException {
        String p = normalize(path);
        if (SlashPaths.isRoot(p)) return new FileEntry(spec.host(), true, 0, 0, null, false);
        String dir = SlashPaths.parent(p);
        String name = p.substring(p.lastIndexOf('/') + 1);
        for (FileEntry e : list(dir)) {
            if (e.name().equals(name)) return e;
        }
        throw notFound(p);
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
        return e.getMessage() != null && e.getMessage().startsWith("Not found");
    }

    // ---- mutations ----

    @Override public void mkdir(String path) throws IOException {
        String p = normalize(path);
        synchronized (lock) {
            if (!io(() -> client.makeDirectory(p))) {
                throw wrapped(p, client.getReplyCode(), client.getReplyString());
            }
        }
    }

    @Override public void delete(String path) throws IOException {
        String p = normalize(path);
        if (stat(p).directory() && !list(p).isEmpty()) {
            throw new IOException("Directory not empty: " + p);
        }
        synchronized (lock) {
            boolean ok = stat(p).directory() ? client.removeDirectory(p) : client.deleteFile(p);
            if (!ok) throw wrapped(p, client.getReplyCode(), client.getReplyString());
        }
    }

    @Override public void rename(String from, String to) throws IOException {
        String f = normalize(from);
        String t = normalize(to);
        synchronized (lock) {
            if (!io(() -> client.rename(f, t))) {
                throw wrapped(f, client.getReplyCode(), client.getReplyString());
            }
        }
    }

    // ---- streams ----

    @Override public InputStream read(String path) throws IOException {
        String p = normalize(path);
        InputStream in;
        synchronized (lock) {
            in = io(() -> client.retrieveFileStream(p));
            if (in == null) throw wrapped(p, client.getReplyCode(), client.getReplyString());
        }
        return new FinishingInputStream(in);
    }

    /** Resume primitive: REST offset + RETR — no skipped bytes cross the wire. */
    @Override public InputStream read(String path, long offset) throws IOException {
        if (offset <= 0) return read(path);
        String p = normalize(path);
        InputStream in;
        synchronized (lock) {
            client.setRestartOffset(offset);
            in = io(() -> client.retrieveFileStream(p));
            if (in == null) throw wrapped(p, client.getReplyCode(), client.getReplyString());
        }
        return new FinishingInputStream(in);
    }

    /** FTP really can append (APPE) — unlike WebDAV. */
    @Override public OutputStream write(String path, boolean append) throws IOException {
        String p = normalize(path);
        OutputStream out;
        synchronized (lock) {
            out = append
                    ? io(() -> client.appendFileStream(p))
                    : io(() -> client.storeFileStream(p));
            if (out == null) throw wrapped(p, client.getReplyCode(), client.getReplyString());
        }
        return new FinishingOutputStream(out);
    }

    @Override public void setTimes(String path, long mtimeMillis) throws IOException {
        String p = normalize(path);
        synchronized (lock) {
            if (!io(() -> client.setModificationTime(p, MFMT.format(
                    Instant.ofEpochMilli(mtimeMillis))))) {
                throw wrapped(p, client.getReplyCode(), client.getReplyString());
            }
        }
    }

    /** FTP has no POSIX mode bits in the standard command set. */
    @Override public void setPerms(String path, int posix) throws IOException {
        throw new UnsupportedOperationException("FTP has no POSIX permissions.");
    }

    // ---- lifecycle ----

    @Override public void close() {
        try {
            client.logout();
        } catch (Exception ignored) {
            // The control line may already be gone; disconnect below suffices.
        }
        try {
            client.disconnect();
        } catch (Exception ignored) {
            // Best-effort; the OS reclaims the socket regardless.
        }
    }

    /**
     * A fresh logged-in client per transfer: FTP allows one data stream per
     * control connection, so browsing and transfers must never share one.
     */
    @Override public FileSystem streamView() throws IOException {
        return new FtpFs(FtpSessions.dialClient(spec), spec);
    }

    // ---- plumbing ----

    @FunctionalInterface
    private interface FtpOp<T> { T run() throws IOException; }

    private <T> T io(FtpOp<T> op) throws IOException {
        try {
            return op.run();
        } catch (IOException e) {
            throw FtpSessions.readable(label, spec.host(), e);
        }
    }

    /**
     * The 226 that must follow every transfer byte stream; skipping it
     * desyncs the control line for every later command.
     */
    private void finishPending() throws IOException {
        synchronized (lock) {
            if (!client.completePendingCommand()) {
                throw wrapped("", client.getReplyCode(), client.getReplyString());
            }
        }
    }

    private final class FinishingInputStream extends FilterInputStream {
        FinishingInputStream(InputStream in) { super(in); }

        @Override public void close() throws IOException {
            try {
                super.close();
            } finally {
                finishPending();
            }
        }
    }

    private final class FinishingOutputStream extends FilterOutputStream {
        FinishingOutputStream(OutputStream out) { super(out); }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override public void close() throws IOException {
            try {
                super.close();
            } finally {
                finishPending();
            }
        }
    }

    private static IOException notFound(String path) {
        return new IOException("Not found: " + path);
    }

    private static IOException wrapped(String path, int code, String reply) {
        String what = switch (code) {
            case 421 -> "The server closed the connection";
            case 530 -> "Not logged in — the session expired; reconnect";
            case 550 -> "Not found or not accessible";
            case 553 -> "The server rejected the name";
            default -> "The server refused the operation (" + code + ")";
        };
        return new IOException(what + ": " + path
                + (reply == null || reply.isBlank() ? "" : " (" + reply.trim() + ")"), null);
    }
}
