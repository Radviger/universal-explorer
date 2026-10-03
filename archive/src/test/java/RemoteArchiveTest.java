import dock.core.fs.LocalFs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The remote mount path: the archive rides the host backend's positioned
 * reads through the block-cached channel. A central-directory walk and
 * entry reads collapse into aligned block fetches, and anything read once
 * never touches the backend again — the contract that makes peeking into
 * remote zips viable.
 */
class RemoteArchiveTest {

    private static final int BLOCK = 256 * 1024;

    /** A remote stand-in: LocalFs that records every positioned read. */
    static final class CountingFs implements dock.core.fs.FileSystem {
        final List<Long> positions = new CopyOnWriteArrayList<>();
        private final dock.core.fs.FileSystem inner = LocalFs.INSTANCE;

        @Override public java.io.InputStream read(String p, long offset) throws IOException {
            positions.add(offset);
            return inner.read(p, offset);
        }

        @Override public String label() { return inner.label(); }
        @Override public boolean remote() { return true; }
        @Override public String separator() { return inner.separator(); }
        @Override public String home() { return inner.home(); }
        @Override public List<String> roots() { return inner.roots(); }
        @Override public String normalize(String p) { return inner.normalize(p); }
        @Override public String parent(String p) { return inner.parent(p); }
        @Override public String child(String d, String n) { return inner.child(d, n); }
        @Override public boolean exists(String p) throws IOException { return inner.exists(p); }
        @Override public List<dock.core.fs.FileEntry> list(String p) throws IOException { return inner.list(p); }
        @Override public dock.core.fs.FileEntry stat(String p) throws IOException { return inner.stat(p); }
        @Override public void mkdir(String p) throws IOException { inner.mkdir(p); }
        @Override public void delete(String p) throws IOException { inner.delete(p); }
        @Override public void rename(String f, String t) throws IOException { inner.rename(f, t); }
        @Override public java.io.InputStream read(String p) throws IOException { return inner.read(p); }
        @Override public java.io.OutputStream write(String p, boolean a) throws IOException { return inner.write(p, a); }
        @Override public void setTimes(String p, long m) throws IOException { inner.setTimes(p, m); }
        @Override public void setPerms(String p, int x) throws IOException { inner.setPerms(p, x); }
        @Override public void close() {}
    }

    private Path dir;
    private byte[] payload;

    @BeforeEach
    void makeDir() throws Exception {
        dir = Files.createTempDirectory("dock-remote-archive");
        payload = new byte[3 * 1024 * 1024];
        new java.util.Random(7).nextBytes(payload);    // incompressible
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (IOException e) { throw new RuntimeException(e); }
            });
        }
    }

    @Test
    void positionedReadsAreAlignedBlockFetchesAndThenCached() throws Exception {
        Path zip = dir.resolve("remote.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("big.bin"));
            out.write(payload);
            out.closeEntry();
            out.putNextEntry(new ZipEntry("small.txt"));
            out.write("tiny".getBytes());
            out.closeEntry();
        }
        CountingFs host = new CountingFs();
        try (var fs = dock.archive.ArchiveFs.open(host, zip.toString())) {
            assertEquals(2, fs.list("/").size());
            assertArrayEquals("tiny".getBytes(), fs.read("/small.txt").readAllBytes());
            assertArrayEquals(payload, fs.read("/big.bin").readAllBytes(),
                    "the cached channel returns the right bytes");

            int afterFirst = host.positions.size();
            assertTrue(afterFirst > 0, "the backend was actually consulted");
            assertTrue(afterFirst <= 24,
                    "reads are block-bounded, not per-seek: " + afterFirst);
            for (long p : host.positions) {
                assertEquals(0, p % BLOCK, "fetches start on block boundaries");
            }

            List<Long> first = new ArrayList<>(host.positions);
            assertArrayEquals(payload, fs.read("/big.bin").readAllBytes());
            assertArrayEquals(java.util.Arrays.copyOfRange(payload, 1000, payload.length),
                    fs.read("/big.bin", 1000).readAllBytes(),
                    "offset reads resume from that byte");
            assertEquals(first, new ArrayList<>(host.positions),
                    "anything read once never touches the backend again");
        }
    }
}
