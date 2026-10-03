package dock.smb;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msdtyp.FileTime;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileBasicInformation;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.event.SMBEventBus;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import dock.core.fs.FileEntry;
import dock.core.fs.FileSystem;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * SMB filesystem over one authenticated smbj session. The pane root "/"
 * is the server itself: it lists the shares (srvsvc RPC over the session,
 * netapi32 fallback — see {@link Srvsvc} and {@link ShareEnumerator});
 * everything below "/Share/…" is a share's directory tree. Disk shares
 * are connected lazily and reused.
 *
 * <p>The line self-heals: servers and NATs drop idle SMB connections,
 * and smbj tears the session and its shares down when the transport
 * notices. Every wire operation runs through {@link #onWire}, which
 * redials the whole line and retries once when it died mid-op — the
 * caller never sees "DiskShare has already been closed".
 */
public final class SmbFs implements FileSystem {

    private static final long ATTR_DIRECTORY = 0x10;
    private static final long ATTR_HIDDEN = 0x02;
    private static final Set<NtStatus> MISSING = Set.of(
            NtStatus.STATUS_NO_SUCH_FILE,
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_BAD_NETWORK_NAME);

    /** One dialed connection: the client owns the TCP line, the session rides it. */
    record Line(SMBClient client, com.hierynomus.smbj.session.Session smbSession) {}

    private final SmbSessions.SmbSpec spec;
    private final SMBEventBus bus;
    private final String label;
    private final String homePath;
    private final ReentrantLock heal = new ReentrantLock();
    private volatile Line line;
    /** Share connections bound to {@link #line}; replaced wholesale on redial. */
    private volatile Map<String, DiskShare> shares = new ConcurrentHashMap<>();
    private volatile boolean closed;

    SmbFs(SmbSessions.SmbSpec spec, SMBEventBus bus, Line line) {
        this.spec = spec;
        this.bus = bus;
        this.line = line;
        this.label = "smb://" + spec.host();
        this.homePath = spec.share() == null || spec.share().isBlank()
                ? "/" : "/" + spec.share();
    }

    @Override public String label() { return label; }
    @Override public boolean remote() { return true; }
    @Override public String separator() { return "/"; }
    @Override public List<String> roots() { return List.of("/"); }
    @Override public String home() { return homePath; }

    @Override public String normalize(String path) { return SmbPaths.normalize(path); }
    @Override public String parent(String path) { return SmbPaths.parent(path); }
    @Override public String child(String dir, String name) { return SmbPaths.child(dir, name); }

    /** The server root is the share list — virtual rows that no new/rename/
     *  delete could touch and a directory that receives nothing. */
    @Override public boolean immutableListing(String path) {
        return SmbPaths.isRoot(SmbPaths.normalize(path));
    }

    // ---- self-healing ----

    interface WireOp<T> { T run() throws IOException; }

    interface HealAction { void run() throws IOException; }

    private <T> T onWire(WireOp<T> op) throws IOException {
        return selfHeal(op, this::connectionAlive, this::healLine);
    }

    /**
     * The heal-and-retry rule as a pure function (tested without a server):
     * a failure on a dead line redials and retries the operation once;
     * a failure on a live line is genuine and propagates as-is.
     */
    static <T> T selfHeal(WireOp<T> op, BooleanSupplier lineAlive, HealAction healLine)
            throws IOException {
        try {
            return op.run();
        } catch (IOException | RuntimeException e) {
            if (lineAlive.getAsBoolean()) throw e;
            healLine.run();
            return op.run();
        }
    }

    /** Dials a fresh line unconditionally (manual "Reconnect now"). */
    void redial() throws IOException {
        heal.lock();
        try {
            if (closed) throw new IOException("Session closed.");
            swapLine(SmbSessions.dialLine(spec, bus));
        } finally {
            heal.unlock();
        }
    }

    /** Heals the line only when it is dead; concurrent callers ride the winner's dial. */
    private void healLine() throws IOException {
        if (connectionAlive()) return;
        heal.lock();
        try {
            if (closed) throw new IOException("Session closed.");
            if (connectionAlive()) return;
            swapLine(SmbSessions.dialLine(spec, bus));
        } finally {
            heal.unlock();
        }
    }

    private void swapLine(Line fresh) {
        Line dead = line;
        line = fresh;
        shares = new ConcurrentHashMap<>();
        if (dead != null) closeQuietly(dead.client());
    }

    // ---- listing ----

    @Override public List<FileEntry> list(String path) throws IOException {
        return onWire(() -> listOnce(path));
    }

    private List<FileEntry> listOnce(String path) throws IOException {
        String p = SmbPaths.normalize(path);
        if (SmbPaths.isRoot(p)) return shareList();
        String share = SmbPaths.shareOf(p);
        String rest = SmbPaths.restOf(p);
        List<FileIdBothDirectoryInformation> rows;
        try {
            rows = shareFor(share).list(rest);
        } catch (SMBApiException e) {
            throw new IOException(e.getMessage(), e);
        }
        List<FileEntry> out = new ArrayList<>(rows.size());
        for (FileIdBothDirectoryInformation row : rows) {
            String name = row.getFileName();
            if (name.equals(".") || name.equals("..")) continue;
            out.add(entry(name, row.getFileAttributes(),
                    row.getEndOfFile(), row.getLastWriteTime().toEpochMillis()));
        }
        return out;
    }

    private List<FileEntry> shareList() throws IOException {
        // srvsvc over the live session first: one authentication, no
        // dependency on the OS redirector (which a stuck Windows session
        // can wedge machine-wide until reboot). netapi32 stays as the
        // fallback for servers that refuse the pipe to this session.
        com.hierynomus.smbj.session.Session session = line.smbSession();
        List<ShareEnumerator.Share> shares = null;
        IOException srvsvc = null;
        try {
            shares = Srvsvc.list(session);
        } catch (IOException e) {
            srvsvc = e;
        }
        if (shares == null) {
            if (com.sun.jna.Platform.isWindows()) {
                String user = spec.guest() ? null : spec.user();
                try {
                    shares = ShareEnumerator.list(
                            spec.host(), spec.domain(), user, spec.password());
                } catch (IOException fallback) {
                    if (srvsvc != null) fallback.addSuppressed(srvsvc);
                    throw fallback;
                }
            }
            if (shares == null) throw srvsvc;
        }
        List<FileEntry> out = new ArrayList<>();
        for (ShareEnumerator.Share s : shares) {
            if (!s.disk()) continue;
            // Shares sort and navigate like directories but are network
            // objects, not folders — the flag gives them their own icon.
            out.add(new FileEntry(s.name(), true, 0, 0, null, s.special(), true));
        }
        return out;
    }

    /** Maps one directory row to a FileEntry (pure; tested without a server). */
    public static FileEntry entry(String name, long attributes, long size, long mtimeMillis) {
        return new FileEntry(name,
                (attributes & ATTR_DIRECTORY) != 0,
                size,
                mtimeMillis,
                null,
                (attributes & ATTR_HIDDEN) != 0);
    }

    // ---- metadata ----

    @Override public FileEntry stat(String path) throws IOException {
        return onWire(() -> statOnce(path));
    }

    private FileEntry statOnce(String path) throws IOException {
        String p = SmbPaths.normalize(path);
        if (SmbPaths.isRoot(p)) {
            return new FileEntry(spec.host(), true, 0, 0, null, false);
        }
        String share = SmbPaths.shareOf(p);
        String rest = SmbPaths.restOf(p);
        if (rest.isEmpty()) {
            // The share root always exists once the tree connects.
            return new FileEntry(share, true, 0, 0, null, false, true);
        }
        try {
            var info = shareFor(share).getFileInformation(rest);
            return entry(nameOf(p), info.getBasicInformation().getFileAttributes(),
                    info.getStandardInformation().getEndOfFile(),
                    info.getBasicInformation().getLastWriteTime().toEpochMillis());
        } catch (SMBApiException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static String nameOf(String path) {
        int cut = path.lastIndexOf('/');
        return cut < 0 ? path : path.substring(cut + 1);
    }

    @Override public boolean exists(String path) throws IOException {
        return onWire(() -> existsOnce(path));
    }

    private boolean existsOnce(String path) throws IOException {
        String p = SmbPaths.normalize(path);
        if (SmbPaths.isRoot(p)) return true;
        String rest = SmbPaths.restOf(p);
        if (rest.isEmpty()) {
            try {
                shareFor(SmbPaths.shareOf(p));
                return true;
            } catch (IOException e) {
                return false;
            }
        }
        try {
            stat(p);
            return true;
        } catch (IOException e) {
            if (e.getCause() instanceof SMBApiException smb && MISSING.contains(smb.getStatus())) {
                return false;
            }
            throw e;
        }
    }

    // ---- mutations ----

    @Override public void mkdir(String path) throws IOException {
        onWire(() -> { mkdirOnce(path); return null; });
    }

    private void mkdirOnce(String path) throws IOException {
        String share = SmbPaths.shareOf(path);
        String rest = SmbPaths.restOf(path);
        if (share == null || rest == null || rest.isEmpty()) {
            throw new IOException("Cannot create directories at the server root; "
                    + "open a share first.");
        }
        try {
            shareFor(share).mkdir(rest);
        } catch (SMBApiException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    @Override public void delete(String path) throws IOException {
        onWire(() -> { deleteOnce(path); return null; });
    }

    private void deleteOnce(String path) throws IOException {
        String share = SmbPaths.shareOf(path);
        String rest = SmbPaths.restOf(path);
        if (share == null || rest == null || rest.isEmpty()) {
            throw new IOException("Cannot delete a share from here.");
        }
        try {
            if (stat(path).directory()) shareFor(share).rmdir(rest, false);
            else shareFor(share).rm(rest);
        } catch (SMBApiException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    @Override public void rename(String from, String to) throws IOException {
        onWire(() -> { renameOnce(from, to); return null; });
    }

    private void renameOnce(String from, String to) throws IOException {
        String share = SmbPaths.shareOf(from);
        String restFrom = SmbPaths.restOf(from);
        String restTo = SmbPaths.restOf(to);
        if (share == null || restFrom == null || restFrom.isEmpty()
                || !share.equals(SmbPaths.shareOf(to))) {
            throw new IOException("Renaming across shares is a transfer, not a rename.");
        }
        try {
            boolean dir = stat(from).directory();
            EnumSet<SMB2CreateOptions> opts = dir
                    ? EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE)
                    : EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE);
            try (var e = shareFor(share).open(restFrom,
                    EnumSet.of(AccessMask.DELETE), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, opts)) {
                e.rename(restTo, false);
            }
        } catch (SMBApiException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    // ---- streams ----

    @Override public InputStream read(String path) throws IOException {
        return onWire(() -> readOnce(path));
    }

    /**
     * A positioned stream: every read becomes an SMB2 READ at the current
     * offset — no skipped bytes cross the wire. Random access into big
     * files (archive central directories, transfer resume) needs this;
     * the default would reopen and scan from the top per seek.
     */
    @Override public InputStream read(String path, long offset) throws IOException {
        if (offset <= 0) return read(path);
        return onWire(() -> readAt(path, offset));
    }

    private InputStream readAt(String path, long offset) throws IOException {
        String p = SmbPaths.normalize(path);
        String share = SmbPaths.shareOf(p);
        String rest = SmbPaths.restOf(p);
        if (share == null || rest == null || rest.isEmpty()) {
            throw new IOException("Not a file: " + p);
        }
        try {
            File f = shareFor(share).openFile(rest,
                    EnumSet.of(AccessMask.FILE_READ_DATA),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
            return new InputStream() {
                private long at = offset;

                @Override public int read(byte[] b, int off, int len) throws IOException {
                    if (len == 0) return 0;
                    int n = f.read(b, at, off, len);
                    if (n < 0) return -1;
                    at += n;
                    return n;
                }

                @Override public int read() throws IOException {
                    byte[] one = new byte[1];
                    int n = read(one, 0, 1);
                    return n < 0 ? -1 : (one[0] & 0xFF);
                }

                @Override public void close() throws IOException {
                    f.close();
                }
            };
        } catch (SMBApiException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private InputStream readOnce(String path) throws IOException {
        String share = SmbPaths.shareOf(path);
        String rest = SmbPaths.restOf(path);
        if (share == null || rest == null || rest.isEmpty()) {
            throw new IOException("Not a file: " + SmbPaths.normalize(path));
        }
        try {
            File f = shareFor(share).openFile(rest, EnumSet.of(AccessMask.FILE_READ_DATA),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
            return f.getInputStream();
        } catch (SMBApiException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    @Override public OutputStream write(String path, boolean append) throws IOException {
        return onWire(() -> writeOnce(path, append));
    }

    private OutputStream writeOnce(String path, boolean append) throws IOException {
        String share = SmbPaths.shareOf(path);
        String rest = SmbPaths.restOf(path);
        if (share == null || rest == null || rest.isEmpty()) {
            throw new IOException("Not a file: " + SmbPaths.normalize(path));
        }
        try {
            File f = shareFor(share).openFile(rest, EnumSet.of(AccessMask.FILE_WRITE_DATA),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL,
                    append ? SMB2CreateDisposition.FILE_OPEN_IF
                           : SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
            // The stream positions itself at EOF when appending.
            return f.getOutputStream(append);
        } catch (SMBApiException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    @Override public void setTimes(String path, long mtimeMillis) throws IOException {
        onWire(() -> { setTimesOnce(path, mtimeMillis); return null; });
    }

    private void setTimesOnce(String path, long mtimeMillis) throws IOException {
        String share = SmbPaths.shareOf(path);
        String rest = SmbPaths.restOf(path);
        if (share == null || rest == null || rest.isEmpty()) return;
        try {
            // Reuse the current attributes so the SET_INFO call does not
            // alter read-only/hidden flags while touching the times.
            long attrs = shareFor(share).getFileInformation(rest)
                    .getBasicInformation().getFileAttributes();
            shareFor(share).setFileInformation(rest, new FileBasicInformation(
                    FileBasicInformation.DONT_SET, FileBasicInformation.DONT_SET,
                    FileTime.ofEpochMillis(mtimeMillis), FileBasicInformation.DONT_SET,
                    attrs));
        } catch (SMBApiException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** SMB has no POSIX mode bits; callers must treat this backend as fixed-mode. */
    @Override public void setPerms(String path, int posix) throws IOException {
        throw new UnsupportedOperationException("SMB has no POSIX permissions.");
    }

    // ---- lifecycle ----

    /** True while the underlying SMB connection is connected (loss filter, heal gate). */
    boolean connectionAlive() {
        Line l = line;
        try {
            var connection = l.smbSession().getConnection();
            return connection != null && connection.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    /** The underlying smbj session of the current line (diagnostics; the srvsvc pipe lives there). */
    com.hierynomus.smbj.session.Session smbSession() { return line.smbSession(); }

    /** One connected DiskShare per share name, shared by browse and transfer paths. */
    private DiskShare shareFor(String share) throws IOException {
        if (share == null || share.isBlank()) {
            throw new IOException("No share in path.");
        }
        Map<String, DiskShare> map = shares;
        try {
            return map.computeIfAbsent(share, name -> {
                try {
                    return (DiskShare) line.smbSession().connectShare(name);
                } catch (RuntimeException e) {
                    throw new ShareDialException(e);
                }
            });
        } catch (ShareDialException e) {
            Throwable cause = e.getCause();
            String msg = cause instanceof SMBApiException api
                    ? "Share \\\\" + spec.host() + "\\" + share + ": " + api.getMessage()
                    : String.valueOf(cause.getMessage());
            throw new IOException(msg, cause);
        }
    }

    private static final class ShareDialException extends RuntimeException {
        ShareDialException(Throwable t) { super(t); }
    }

    @Override public void close() {
        heal.lock();
        try {
            closed = true;
            closeQuietly(line.client());
        } finally {
            heal.unlock();
        }
    }

    private static void closeQuietly(SMBClient client) {
        try {
            client.close();
        } catch (Exception ignored) {
            // Best-effort; the OS reclaims the socket regardless.
        }
    }

    /** Browsing and streaming multiplex over the same session (SMB2 credits). */
    @Override public FileSystem streamView() { return this; }
}
