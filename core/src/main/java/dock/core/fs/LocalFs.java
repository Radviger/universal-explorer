package dock.core.fs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

/** The local Windows filesystem. Stateless singleton; close is a no-op. */
public final class LocalFs implements FileSystem {

    public static final LocalFs INSTANCE = new LocalFs();

    private LocalFs() {}

    @Override public String label() { return "Local"; }
    @Override public boolean remote() { return false; }
    @Override public String separator() { return "\\"; }

    @Override public String home() {
        return System.getProperty("user.home");
    }

    @Override public List<String> roots() {
        List<String> out = new ArrayList<>();
        for (Path root : FileSystems.getDefault().getRootDirectories()) out.add(root.toString());
        return out;
    }

    @Override public String normalize(String path) {
        return Path.of(path).normalize().toString();
    }

    @Override public String parent(String path) {
        Path parent = Path.of(path).getParent();
        return parent != null ? parent.toString() : path;
    }

    @Override public String child(String dir, String name) {
        return Path.of(dir, name).toString();
    }

    @Override public boolean exists(String path) {
        return Files.exists(Path.of(path));
    }

    @Override public List<FileEntry> list(String path) throws IOException {
        List<FileEntry> out = new ArrayList<>();
        try (var ds = Files.newDirectoryStream(Path.of(path))) {
            for (Path p : ds) {
                BasicFileAttributes a;
                try {
                    a = Files.readAttributes(p, BasicFileAttributes.class);
                } catch (IOException e) {
                    continue; // unreadable entry: show the rest
                }
                String name = p.getFileName().toString();
                out.add(new FileEntry(name, a.isDirectory(), a.size(),
                        a.lastModifiedTime().toMillis(), null, Files.isHidden(p)));
            }
        }
        return out;
    }

    @Override public FileEntry stat(String path) throws IOException {
        BasicFileAttributes a = Files.readAttributes(Path.of(path), BasicFileAttributes.class);
        Path p = Path.of(path);
        String name = p.getFileName() != null ? p.getFileName().toString() : path;
        return new FileEntry(name, a.isDirectory(), a.size(),
                a.lastModifiedTime().toMillis(), null, Files.isHidden(p));
    }

    @Override public void mkdir(String path) throws IOException {
        Files.createDirectory(Path.of(path));
    }

    @Override public void delete(String path) throws IOException {
        Files.delete(Path.of(path));
    }

    @Override public void rename(String from, String to) throws IOException {
        Files.move(Path.of(from), Path.of(to));
    }

    @Override public InputStream read(String path) throws IOException {
        return Files.newInputStream(Path.of(path));
    }

    /**
     * A real positioned open, not the interface's skip-loop: local files
     * underpin ranged consumers too (the media player's bridge, transfer
     * resume), and skipping to a mid-file offset would read every byte
     * before it.
     */
    @Override public InputStream read(String path, long offset) throws IOException {
        java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
                Path.of(path), java.nio.file.StandardOpenOption.READ);
        ch.position(offset);
        return new java.io.InputStream() {
            @Override public int read() throws IOException {
                byte[] b = new byte[1];
                int n = ch.read(java.nio.ByteBuffer.wrap(b));
                return n < 0 ? -1 : (b[0] & 0xFF);
            }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                return ch.read(java.nio.ByteBuffer.wrap(b, off, len));
            }
            @Override public long skip(long n) throws IOException {
                long before = ch.position();
                ch.position(Math.min(before + n, ch.size()));
                return ch.position() - before;
            }
            @Override public int available() throws IOException {
                long left = ch.size() - ch.position();
                return (int) Math.min(left, Integer.MAX_VALUE);
            }
            @Override public void close() throws IOException { ch.close(); }
        };
    }

    @Override public OutputStream write(String path, boolean append) throws IOException {
        var opts = new ArrayList<java.nio.file.StandardOpenOption>();
        opts.add(java.nio.file.StandardOpenOption.CREATE);
        opts.add(java.nio.file.StandardOpenOption.WRITE);
        if (append) opts.add(java.nio.file.StandardOpenOption.APPEND);
        else opts.add(java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        return Files.newOutputStream(Path.of(path), opts.toArray(new java.nio.file.OpenOption[0]));
    }

    @Override public void setTimes(String path, long mtimeMillis) throws IOException {
        Files.setLastModifiedTime(Path.of(path), FileTime.fromMillis(mtimeMillis));
    }

    @Override public void setPerms(String path, int posix) {
        throw new UnsupportedOperationException("POSIX permissions are not a local-Windows concept");
    }

    @Override public void close() {}
}
