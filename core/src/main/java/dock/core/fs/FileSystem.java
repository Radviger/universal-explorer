package dock.core.fs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Minimal file-browsing/transfer surface implemented by the local disk and
 * SFTP. Path strings are absolute and use the backend's own separator
 * ('\' locally, '/' remotely). All methods may block; the UI layer is
 * responsible for keeping them off the EDT.
 */
public interface FileSystem extends AutoCloseable {

    String label();

    boolean remote();

    String separator();

    String home();

    /** Local: available drives. Remote: ["/"]. */
    List<String> roots();

    String normalize(String path);

    String parent(String path);

    String child(String dir, String name);

    boolean exists(String path) throws IOException;

    List<FileEntry> list(String path) throws IOException;

    FileEntry stat(String path) throws IOException;

    /**
     * True when this path's listing is virtual: its rows are not writable
     * filesystem objects (an SMB server root lists shares) and the directory
     * itself can neither receive files nor be reshaped. The UI neither
     * offers nor accepts mutating actions there.
     */
    default boolean immutableListing(String path) { return false; }

    /**
     * True when the rows of an immutable listing can still be copied out —
     * archive entries extract, share rows are whole shares and stay put.
     * Writable listings are always copyable, hence the default.
     */
    default boolean extractable(String path) { return !immutableListing(path); }

    void mkdir(String path) throws IOException;

    /** Deletes a file or an empty directory. */
    void delete(String path) throws IOException;

    default void deleteTree(String path) throws IOException {
        FileEntry st = stat(path);
        if (st.directory()) {
            for (FileEntry e : list(path)) deleteTree(child(path, e.name()));
        }
        delete(path);
    }

    void rename(String from, String to) throws IOException;

    InputStream read(String path) throws IOException;

    /**
     * Opens a read stream positioned at {@code offset} — the primitive
     * transfer resume is built on. The default skips through a plain stream;
     * SFTP overrides it with a seek.
     */
    default InputStream read(String path, long offset) throws IOException {
        InputStream in = read(path);
        long skipped = 0;
        while (skipped < offset) {
            long n = in.skip(offset - skipped);
            if (n <= 0) break;
            skipped += n;
        }
        return in;
    }

    OutputStream write(String path, boolean append) throws IOException;

    void setTimes(String path, long mtimeMillis) throws IOException;

    /** May throw UnsupportedOperationException on backends without POSIX modes. */
    void setPerms(String path, int posix) throws IOException;

    /**
     * A view of this filesystem with a private channel for stream I/O.
     * Transfers must not share the browsing channel: the SFTP protocol
     * client is not safe under concurrent use. Local filesystems return
     * themselves.
     */
    default FileSystem streamView() throws IOException {
        return this;
    }

    @Override
    void close();
}
