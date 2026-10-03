package dock.editor;

import dock.core.fs.FileEntry;
import dock.core.fs.FileSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An in-memory filesystem for editor tests: flat files under "/"
 *  directories, everything in RAM. Writes land in {@link #lastWritten}
 *  with a bumped mtime — the outside hand that makes saves conflict. */
final class MemFs implements FileSystem {
    private final Map<String, byte[]> files = new LinkedHashMap<>();
    private final Map<String, Long> mtimes = new LinkedHashMap<>();
    private final Map<String, byte[]> written = new LinkedHashMap<>();
    private long clock;

    void add(String path, byte[] data) {
        files.put(path, data);
        mtimes.put(path, 0L);
    }

    /** Replaces a file's content and bumps its mtime — a change made
     *  elsewhere after the editor loaded its copy. */
    void mutate(String path, byte[] data) {
        files.put(path, data);
        mtimes.put(path, ++clock);
    }

    byte[] file(String path) { return files.get(path); }
    byte[] lastWritten(String path) { return written.get(path); }

    @Override public String label() { return "mem"; }
    @Override public boolean remote() { return false; }
    @Override public String separator() { return "/"; }
    @Override public String home() { return "/"; }
    @Override public List<String> roots() { return List.of("/"); }
    @Override public String normalize(String path) { return path; }
    @Override public String parent(String path) {
        int cut = path.lastIndexOf('/');
        return cut <= 0 ? "/" : path.substring(0, cut);
    }
    @Override public String child(String dir, String name) {
        return dir.equals("/") ? "/" + name : dir + "/" + name;
    }
    @Override public boolean exists(String path) { return files.containsKey(path); }

    @Override public List<FileEntry> list(String dir) throws IOException {
        List<FileEntry> out = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : files.entrySet())
            if (parent(e.getKey()).equals(dir))
                out.add(stat(e.getKey()));
        return out;
    }

    @Override public FileEntry stat(String path) throws IOException {
        byte[] b = files.get(path);
        if (b == null) throw new IOException("no such file: " + path);
        return new FileEntry(nameOf(path), false, b.length,
                mtimes.getOrDefault(path, 0L), null, false, false);
    }

    @Override public InputStream read(String path) throws IOException {
        byte[] b = files.get(path);
        if (b == null) throw new IOException("no such file: " + path);
        return new ByteArrayInputStream(b);
    }

    @Override public OutputStream write(String path, boolean append) {
        return new ByteArrayOutputStream() {
            @Override public void close() throws IOException {
                super.close();
                byte[] body = toByteArray();
                if (append && files.containsKey(path)) {
                    byte[] old = files.get(path);
                    byte[] joined = new byte[old.length + body.length];
                    System.arraycopy(old, 0, joined, 0, old.length);
                    System.arraycopy(body, 0, joined, old.length, body.length);
                    body = joined;
                }
                files.put(path, body);
                mtimes.put(path, ++clock);
                written.put(path, body);
            }
        };
    }
    @Override public void mkdir(String path) {}
    @Override public void delete(String path) { files.remove(path); }
    @Override public void rename(String from, String to) {
        byte[] b = files.remove(from);
        if (b != null) files.put(to, b);
    }
    @Override public void setTimes(String path, long mtimeMillis) {
        mtimes.put(path, mtimeMillis);
    }
    @Override public void setPerms(String path, int posix) {}
    @Override public void close() {}

    private static String nameOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
