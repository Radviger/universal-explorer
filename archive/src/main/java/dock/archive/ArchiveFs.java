package dock.archive;

import dock.core.fs.FileEntry;
import dock.core.fs.FileSystem;
import dock.core.fs.LocalFs;
import dock.core.fs.SlashPaths;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

/**
 * A read-only virtual filesystem over one archive file living on any host
 * backend: Enter on an archive mounts it, the entry table lists it like a
 * directory, copying out extracts. Inner paths are plain "/" geometry
 * ({@link SlashPaths}); the mount's way out — the directory holding the
 * archive — is exposed to the pane via {@link #hostDir()}.
 *
 * <p>Formats: the zip family and 7z are indexed once and held open over a
 * seekable channel; the tar family (plain, gz, bz2, xz) is indexed by one
 * streaming pass and re-streamed per read; a compressed single file (plain
 * gz/bz2/xz) becomes one virtual entry named after the archive. The format
 * is decided by magic bytes, never the extension — renamed archives still
 * open, mislabeled files fail loudly.
 */
public final class ArchiveFs implements FileSystem {

    private final FileSystem host;
    private final String archivePath;
    private final String label;
    private final Backend backend;
    /** Every known node, files and directories (explicit and synthesized). */
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private boolean closed;

    private ArchiveFs(FileSystem host, String archivePath, Backend backend) throws IOException {
        this.host = host;
        this.archivePath = archivePath;
        this.label = baseName(host, archivePath);
        this.backend = backend;
        backend.index(nodes);
    }

    /** Mounts the archive at {@code archivePath}, sniffing the format first. */
    public static ArchiveFs open(FileSystem host, String archivePath) throws IOException {
        SeekableByteChannel channel = openChannel(host, archivePath);
        Format format = sniff(channel);
        try {
            return switch (format) {
                case ZIP -> new ArchiveFs(host, archivePath, new ZipBackend(channel));
                case SEVEN_Z -> new ArchiveFs(host, archivePath, new SevenZBackend(channel));
                case TAR -> streaming(host, archivePath, null, channel);
                case GZIP, BZIP2, XZ -> compressed(host, archivePath, format, channel);
                case UNKNOWN -> throw new IOException(
                        "Not a recognizable archive: " + baseName(host, archivePath));
            };
        } catch (IOException | RuntimeException e) {
            // Backends that took the channel closed it themselves; closing
            // again is harmless and covers every failure ordering.
            try { channel.close(); } catch (IOException ignored) {}
            throw e;
        }
    }

    /** Tar and friends stream instead of seeking — the channel retires here. */
    private static ArchiveFs streaming(FileSystem host, String archivePath, Format wrap,
                                       SeekableByteChannel channel) throws IOException {
        channel.close();
        return new ArchiveFs(host, archivePath, new TarBackend(host, archivePath, wrap));
    }

    /**
     * A compressed file: either a tar wearing a codec (tgz, tar.xz, …) or a
     * single compressed member shown as one entry named after the archive.
     */
    private static ArchiveFs compressed(FileSystem host, String archivePath, Format outer,
                                        SeekableByteChannel channel) throws IOException {
        Format inner;
        try (InputStream raw = host.read(archivePath)) {
            inner = sniffStream(decompressed(raw, outer));
        }
        if (inner == Format.TAR) return streaming(host, archivePath, outer, channel);

        // The gzip trailer carries the uncompressed size (mod 4 GiB); the
        // other codecs have no footer, so the size comes from a counting
        // scan at mount.
        long size = outer == Format.GZIP
                ? gzipIsize(channel) : countUncompressed(host, archivePath, outer);
        long mtime = host.stat(archivePath).mtimeMillis();
        channel.close();
        return new ArchiveFs(host, archivePath,
                new SingleFileBackend(host, archivePath, outer, size, mtime));
    }

    private static long countUncompressed(FileSystem host, String path, Format format)
            throws IOException {
        long n = 0;
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = decompressed(host.read(path), format)) {
            int r;
            while ((r = in.read(buf)) > 0) n += r;
        }
        return n;
    }

    /** The ISIZE trailer word; 0xFFFFFFFF means "4 GiB multiples, unknown". */
    private static long gzipIsize(SeekableByteChannel channel) throws IOException {
        if (channel.size() < 18) return 0;    // not even header + trailer
        ByteBuffer bb = ByteBuffer.allocate(4);
        channel.position(channel.size() - 4);
        while (bb.hasRemaining()) {
            if (channel.read(bb) < 0) return 0;
        }
        long isize = bb.order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0) & 0xFFFFFFFFL;
        return isize == 0xFFFFFFFFL ? 0 : isize;
    }

    private static InputStream decompressed(InputStream raw, Format format) throws IOException {
        return switch (format) {
            case GZIP -> new GZIPInputStream(raw);
            case BZIP2 -> new BZip2CompressorInputStream(raw);
            case XZ -> new XZCompressorInputStream(raw);
            default -> raw;
        };
    }

    private static SeekableByteChannel openChannel(FileSystem host, String path)
            throws IOException {
        if (host == LocalFs.INSTANCE) {
            return FileChannel.open(Path.of(path), StandardOpenOption.READ);
        }
        return new PositionedChannel(host, path, host.stat(path).size());
    }

    // ---- format sniffing ----

    enum Format { ZIP, SEVEN_Z, GZIP, BZIP2, XZ, TAR, UNKNOWN }

    /** Reads the header and resets; magic bytes, extensions never consulted. */
    static Format sniff(SeekableByteChannel channel) throws IOException {
        ByteBuffer head = ByteBuffer.allocate(265);    // tar's "ustar" sits at 257
        while (head.hasRemaining()) {
            if (channel.read(head) < 0) break;
        }
        channel.position(0);
        return match(head.array(), head.position());
    }

    private static Format sniffStream(InputStream in) throws IOException {
        if (!in.markSupported()) in = new BufferedInputStream(in, 512);
        byte[] b = new byte[265];
        in.mark(b.length);
        int len = 0;
        while (len < b.length) {
            int r = in.read(b, len, b.length - len);
            if (r < 0) break;
            len += r;
        }
        in.reset();
        return match(b, len);
    }

    private static Format match(byte[] b, int len) {
        if (at(b, len, 0, 'P', 'K', 0x03, 0x04)
                || at(b, len, 0, 'P', 'K', 0x05, 0x06)
                || at(b, len, 0, 'P', 'K', 0x07, 0x08)) return Format.ZIP;
        if (at(b, len, 0, '7', 'z', 0xBC, 0xAF, 0x27, 0x1C)) return Format.SEVEN_Z;
        if (at(b, len, 0, 0x1F, 0x8B)) return Format.GZIP;
        if (at(b, len, 0, 'B', 'Z', 'h')) return Format.BZIP2;
        if (at(b, len, 0, 0xFD, '7', 'z', 'X', 'Z', 0x00)) return Format.XZ;
        if (at(b, len, 257, 'u', 's', 't', 'a', 'r')) return Format.TAR;
        return Format.UNKNOWN;
    }

    private static boolean at(byte[] b, int len, int off, int... pattern) {
        if (len < off + pattern.length) return false;
        for (int i = 0; i < pattern.length; i++) {
            if ((b[off + i] & 0xFF) != pattern[i]) return false;
        }
        return true;
    }

    // ---- the tree ----

    /**
     * One node of the virtual tree. {@code handle} is backend-private: the
     * zip entry, the 7z entry, or the inner path for streaming tars.
     */
    private record Node(String name, boolean directory, long size, long mtime,
                        Integer perms, Object handle) {
        static Node synthetic(String name) {
            return new Node(name, true, 0, 0, null, null);
        }
    }

    /** Adds one entry and synthesizes any missing ancestor directories. */
    private static void indexOne(Map<String, Node> nodes, String inner, Node n) {
        nodes.put(inner, n);
        for (String dir = SlashPaths.parent(inner); !dir.equals("/");
                dir = SlashPaths.parent(dir)) {
            nodes.putIfAbsent(dir, Node.synthetic(lastOf(dir)));
        }
    }

    /** Fills the tree and streams file entries; format-private knowledge lives here. */
    private interface Backend extends AutoCloseable {
        void index(Map<String, Node> nodes) throws IOException;
        InputStream open(Object handle) throws IOException;

        @Override void close();
    }

    private static final class ZipBackend implements Backend {
        private final ZipFile zip;

        ZipBackend(SeekableByteChannel channel) throws IOException {
            this.zip = ZipFile.builder().setSeekableByteChannel(channel).get();
        }

        @Override public void index(Map<String, Node> nodes) throws IOException {
            for (ZipArchiveEntry e : Collections.list(zip.getEntries())) {
                String inner = innerOf(e.getName());
                if (inner.equals("/")) continue;
                // The unix mode rides in the zip's external attributes; 0
                // means the writer had none (Windows zips) and reads null.
                int mode = e.getUnixMode();
                Date d = e.getLastModifiedDate();
                indexOne(nodes, inner, new Node(lastOf(inner),
                        e.isDirectory() || e.getName().endsWith("/"),
                        Math.max(0, e.getSize()),
                        d == null ? 0 : d.getTime(),
                        mode != 0 ? mode & 0777 : null,
                        e));
            }
        }

        @Override public InputStream open(Object handle) throws IOException {
            return zip.getInputStream((ZipArchiveEntry) handle);
        }

        @Override public void close() {
            try { zip.close(); } catch (IOException ignored) {}
        }
    }

    private static final class SevenZBackend implements Backend {
        private final SevenZFile sevenZ;

        SevenZBackend(SeekableByteChannel channel) throws IOException {
            this.sevenZ = new SevenZFile(channel);
        }

        @Override public void index(Map<String, Node> nodes) throws IOException {
            for (SevenZArchiveEntry e : sevenZ.getEntries()) {
                String inner = innerOf(e.getName());
                if (inner.equals("/")) continue;
                var t = timeOf(e);
                indexOne(nodes, inner, new Node(lastOf(inner), e.isDirectory(),
                        Math.max(0, e.getSize()),
                        t < 0 ? 0 : t,
                        null, e));
            }
        }

        /** 7z entries without any stored time throw instead of returning null. */
        private static long timeOf(SevenZArchiveEntry e) {
            try {
                var t = e.getLastModifiedTime();
                return t == null ? -1 : t.toMillis();
            } catch (UnsupportedOperationException noTimestamp) {
                return -1;
            }
        }

        @Override public InputStream open(Object handle) throws IOException {
            SevenZArchiveEntry e = (SevenZArchiveEntry) handle;
            if (!e.hasStream()) return InputStream.nullInputStream();
            return sevenZ.getInputStream(e);
        }

        @Override public void close() {
            try { sevenZ.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * The tar family: no index exists on disk, so one full pass builds the
     * tree and every read re-streams from the top. Local tars pay a disk
     * pass; remote ones pay a download — the price of the format.
     */
    private static final class TarBackend implements Backend {
        private final FileSystem host;
        private final String archivePath;
        private final Format wrap;    // null = plain tar

        TarBackend(FileSystem host, String archivePath, Format wrap) {
            this.host = host;
            this.archivePath = archivePath;
            this.wrap = wrap;
        }

        private TarArchiveInputStream open() throws IOException {
            InputStream raw = host.read(archivePath);
            return new TarArchiveInputStream(wrap == null ? raw : decompressed(raw, wrap));
        }

        @Override public void index(Map<String, Node> nodes) throws IOException {
            try (TarArchiveInputStream in = open()) {
                TarArchiveEntry e;
                while ((e = in.getNextEntry()) != null) {
                    String inner = innerOf(e.getName());
                    if (inner.equals("/")) continue;
                    Date d = e.getModTime();
                    int mode = e.getMode() & 0777;
                    indexOne(nodes, inner, new Node(lastOf(inner), e.isDirectory(),
                            e.getSize(), d == null ? 0 : d.getTime(),
                            mode != 0 ? mode : null, inner));
                }
            }
        }

        @Override public InputStream open(Object handle) throws IOException {
            String want = (String) handle;
            TarArchiveInputStream in = open();
            TarArchiveEntry e;
            while ((e = in.getNextEntry()) != null) {
                // The stream returned sits inside the matched entry: reads
                // stop at its end, skip stays within it.
                if (innerOf(e.getName()).equals(want)) return in;
            }
            in.close();
            throw new IOException("Entry vanished since indexing: " + want);
        }

        @Override public void close() {}
    }

    /** A gz/bz2/xz holding anything but a tar: one member, one entry. */
    private static final class SingleFileBackend implements Backend {
        private final FileSystem host;
        private final String archivePath;
        private final Format format;
        private final long size;
        private final long mtime;

        SingleFileBackend(FileSystem host, String archivePath, Format format,
                          long size, long mtime) {
            this.host = host;
            this.archivePath = archivePath;
            this.format = format;
            this.size = size;
            this.mtime = mtime;
        }

        @Override public void index(Map<String, Node> nodes) throws IOException {
            String base = baseName(host, archivePath);
            int dot = base.lastIndexOf('.');
            String name = dot > 0 ? base.substring(0, dot) : base;
            indexOne(nodes, "/" + name, new Node(name, false, size, mtime, null, null));
        }

        @Override public InputStream open(Object handle) throws IOException {
            return decompressed(host.read(archivePath), format);
        }

        @Override public void close() {}
    }

    // ---- inner-path geometry ----

    /** An entry name to an inner path: slashes normalized, dirs unslashed. */
    private static String innerOf(String rawName) {
        String n = rawName.replace('\\', '/');
        while (n.startsWith("/")) n = n.substring(1);
        if (n.endsWith("/")) n = n.substring(0, n.length() - 1);
        if (n.isEmpty()) return "/";
        return "/" + n;
    }

    private static String lastOf(String innerPath) {
        return innerPath.substring(innerPath.lastIndexOf('/') + 1);
    }

    private static String baseName(FileSystem host, String path) {
        int cut = Math.max(path.lastIndexOf(host.separator()), path.lastIndexOf('/'));
        return cut < 0 ? path : path.substring(cut + 1);
    }

    // ---- FileSystem ----

    private static FileEntry toEntry(Node n) {
        return new FileEntry(n.name(), n.directory(), n.size(), n.mtime(),
                n.perms(), false, false);
    }

    @Override public String label() { return label; }
    @Override public boolean remote() { return host.remote(); }
    @Override public String separator() { return "/"; }
    @Override public List<String> roots() { return List.of("/"); }
    @Override public String home() { return "/"; }

    @Override public String normalize(String path) { return SlashPaths.normalize(path); }
    @Override public String parent(String path) { return SlashPaths.parent(path); }
    @Override public String child(String dir, String name) { return SlashPaths.child(dir, name); }

    @Override public boolean immutableListing(String path) { return true; }
    @Override public boolean extractable(String path) { return true; }

    @Override public boolean exists(String path) throws IOException {
        String p = normalize(path);
        return p.equals("/") || nodes.containsKey(p);
    }

    @Override public List<FileEntry> list(String path) throws IOException {
        String p = normalize(path);
        Node dir = nodes.get(p);
        if (!p.equals("/") && (dir == null || !dir.directory())) {
            throw new IOException("Not a directory: " + p);
        }
        String prefix = p.equals("/") ? "/" : p + "/";
        List<FileEntry> out = new ArrayList<>();
        for (Map.Entry<String, Node> e : nodes.entrySet()) {
            String key = e.getKey();
            if (key.length() > prefix.length() && key.startsWith(prefix)
                    && key.indexOf('/', prefix.length()) < 0) {
                out.add(toEntry(e.getValue()));
            }
        }
        return out;
    }

    @Override public FileEntry stat(String path) throws IOException {
        String p = normalize(path);
        if (p.equals("/")) return new FileEntry(label, true, 0, 0, null, false);
        Node n = nodes.get(p);
        if (n == null) throw new IOException("No such entry: " + p);
        return toEntry(n);
    }

    @Override public InputStream read(String path) throws IOException {
        String p = normalize(path);
        Node n = nodes.get(p);
        if (n == null) throw new IOException("No such entry: " + p);
        if (n.directory()) throw new IOException("Not a file: " + p);
        return backend.open(n.handle());
    }

    @Override public void mkdir(String path) throws IOException {
        throw new IOException("The archive is read-only.");
    }

    @Override public void delete(String path) throws IOException {
        throw new IOException("The archive is read-only.");
    }

    @Override public void rename(String from, String to) throws IOException {
        throw new IOException("The archive is read-only.");
    }

    @Override public OutputStream write(String path, boolean append) throws IOException {
        throw new IOException("The archive is read-only.");
    }

    @Override public void setTimes(String path, long mtimeMillis) throws IOException {
        throw new IOException("The archive is read-only.");
    }

    @Override public void setPerms(String path, int posix) throws IOException {
        throw new IOException("The archive is read-only.");
    }

    /** The index is shared by design; transfers extract through it. */
    @Override public FileSystem streamView() { return this; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        backend.close();
    }

    // ---- mount facts (the pane's way out) ----

    public FileSystem host() { return host; }
    public String archivePath() { return archivePath; }
    /** The host directory holding the archive — where ".." at the root goes. */
    public String hostDir() { return host.parent(archivePath); }

    @Override public String toString() { return "ArchiveFs[" + archivePath + "]"; }
}
