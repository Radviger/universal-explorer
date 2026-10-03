import dock.core.fs.LocalFs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The archive mount as a filesystem: the zip's central directory becomes a
 * virtual tree (explicit and synthesized directories), entries carry real
 * size/mtime/unix-mode metadata, reads (and offset reads) round-trip the
 * bytes, and the whole listing is immutable yet extractable.
 */
class ArchiveFsTest {

    private static final long T = 1_700_000_122_000L;

    private Path dir;

    @BeforeEach
    void makeDir() throws Exception {
        dir = Files.createTempDirectory("dock-archive");
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
    void theTreeListsExplicitAndImplicitDirectories() throws Exception {
        Path p = zip("b.zip", out -> {
            dirEntry(out, "docs/");
            fileEntry(out, "docs/report.txt", 0100644, "hello".getBytes());
            // No directory entries at all — the tree synthesizes them.
            fileEntry(out, "deep/nested/data.bin", 0100600, new byte[10]);
        });
        try (var fs = dock.archive.ArchiveFs.open(LocalFs.INSTANCE, p.toString())) {
            assertEquals(List.of("deep", "docs"), names(fs.list("/")));
            assertEquals(List.of("report.txt"), names(fs.list("/docs")));
            assertEquals(List.of("nested"), names(fs.list("/deep")));
            assertEquals(List.of("data.bin"), names(fs.list("/deep/nested")));
            assertTrue(fs.stat("/deep").directory(), "a synthesized directory navigates");
            assertTrue(fs.stat("/").directory());
        }
    }

    @Test
    void entriesCarrySizeMtimeAndUnixMode() throws Exception {
        Path p = zip("b.zip", out -> {
            dirEntry(out, "docs/");
            fileEntry(out, "docs/report.txt", 0100644, "hello".getBytes());
            fileEntry(out, "deep/nested/data.bin", 0100600, new byte[10]);
        });
        try (var fs = dock.archive.ArchiveFs.open(LocalFs.INSTANCE, p.toString())) {
            var report = fs.stat("/docs/report.txt");
            assertEquals(5, report.size());
            assertEquals(T, report.mtimeMillis());
            assertEquals(0644, report.posixPerms());
            assertEquals(0755, fs.stat("/docs").posixPerms(), "explicit dir entries keep mode");
            assertNull(fs.stat("/deep/nested").posixPerms(), "synthesized dirs have none");
        }
    }

    @Test
    void readsRoundTripAndOffsetReadsResume() throws Exception {
        byte[] pattern = new byte[4096];
        for (int i = 0; i < pattern.length; i++) pattern[i] = (byte) (i * 31 + 7);
        Path p = zip("b.zip", out ->
                fileEntry(out, "big.bin", 0100644, pattern));
        try (var fs = dock.archive.ArchiveFs.open(LocalFs.INSTANCE, p.toString())) {
            assertArrayEquals(pattern, fs.read("/big.bin").readAllBytes());
            assertArrayEquals(Arrays.copyOfRange(pattern, 1000, pattern.length),
                    fs.read("/big.bin", 1000).readAllBytes(),
                    "an offset read resumes from that byte");
        }
    }

    @Test
    void storedEntriesReadToo() throws Exception {
        byte[] data = "store me verbatim".getBytes();
        Path p = zip("b.zip", out -> {
            var e = new ZipArchiveEntry("stored.bin");
            e.setMethod(ZipArchiveEntry.STORED);
            e.setSize(data.length);
            CRC32 crc = new CRC32();
            crc.update(data);
            e.setCrc(crc.getValue());
            e.setTime(T);
            out.putArchiveEntry(e);
            out.write(data);
            out.closeArchiveEntry();
        });
        try (var fs = dock.archive.ArchiveFs.open(LocalFs.INSTANCE, p.toString())) {
            assertArrayEquals(data, fs.read("/stored.bin").readAllBytes());
            assertNull(fs.stat("/stored.bin").posixPerms(), "no unix mode was written");
        }
    }

    @Test
    void theArchiveIsImmutableButExtractable() throws Exception {
        Path p = zip("b.zip", out ->
                fileEntry(out, "a.txt", 0100644, "x".getBytes()));
        try (var fs = dock.archive.ArchiveFs.open(LocalFs.INSTANCE, p.toString())) {
            assertTrue(fs.immutableListing("/"));
            assertTrue(fs.immutableListing("/whatever"));
            assertTrue(fs.extractable("/"), "copying out is extraction");
            assertThrows(IOException.class, () -> fs.mkdir("/x"));
            assertThrows(IOException.class, () -> fs.delete("/a.txt"));
            assertThrows(IOException.class, () -> fs.rename("/a.txt", "/b.txt"));
            assertThrows(IOException.class, () -> fs.write("/x", false));
        }
    }

    @Test
    void magicBytesDecideTheFormatNotTheExtension() throws Exception {
        Path zip = zip("real.zip", out ->
                fileEntry(out, "a.txt", 0100644, "x".getBytes()));
        Path renamed = dir.resolve("blob.dat");
        Files.copy(zip, renamed);
        try (var fs = dock.archive.ArchiveFs.open(LocalFs.INSTANCE, renamed.toString())) {
            assertEquals(1, fs.list("/").size());
        }

        Path fake = dir.resolve("fake.zip");
        Files.writeString(fake, "this is not a zip at all");
        assertThrows(IOException.class, () ->
                dock.archive.ArchiveFs.open(LocalFs.INSTANCE, fake.toString()));
    }

    @Test
    void innerPathGeometryFollowsSlashRules() throws Exception {
        Path p = zip("b.zip", out ->
                fileEntry(out, "a.txt", 0100644, "x".getBytes()));
        try (var fs = dock.archive.ArchiveFs.open(LocalFs.INSTANCE, p.toString())) {
            assertEquals("/a/b", fs.normalize("/a//b/"));
            assertEquals("/a", fs.parent("/a/b"));
            assertEquals("/", fs.parent("/a"));
            assertEquals("/a", fs.child("/", "a"));
            assertEquals("/a/b", fs.child("/a", "b"));
            assertEquals(List.of("/"), fs.roots());
        }
    }

    @Test
    void anEmptyZipListsNothingButStats() throws Exception {
        Path p = zip("empty.zip", out -> {});
        try (var fs = dock.archive.ArchiveFs.open(LocalFs.INSTANCE, p.toString())) {
            assertEquals(List.of(), fs.list("/"));
            assertTrue(fs.stat("/").directory());
            assertTrue(fs.exists("/"));
        }
    }

    @Test
    void readingAMissingEntryOrADirectoryThrows() throws Exception {
        Path p = zip("b.zip", out -> {
            dirEntry(out, "docs/");
            fileEntry(out, "docs/a.txt", 0100644, "x".getBytes());
        });
        try (var fs = dock.archive.ArchiveFs.open(LocalFs.INSTANCE, p.toString())) {
            assertThrows(IOException.class, () -> fs.read("/nope.txt"));
            assertThrows(IOException.class, () -> fs.read("/docs"));
            assertThrows(IOException.class, () -> fs.stat("/nope.txt"));
            assertThrows(IOException.class, () -> fs.list("/docs/a.txt"));
        }
    }

    @Test
    void aComicCbzMountsLikeAZipAndARarCbrIsRefused() throws Exception {
        // A cbz is a plain zip; the sniff mounts it without asking the name.
        Path cbz = zip("album.cbz", out -> {
            fileEntry(out, "001.jpg", 0100644, new byte[8]);
            fileEntry(out, "002.jpg", 0100644, new byte[8]);
        });
        try (var fs = dock.archive.ArchiveFs.open(LocalFs.INSTANCE, cbz.toString())) {
            assertEquals(List.of("001.jpg", "002.jpg"), names(fs.list("/")));
        }
        // A cbr is a RAR and nothing reads RAR: the mount fails on the magic,
        // naming the file — the pane shows that text as its toast.
        Path cbr = dir.resolve("album.cbr");
        Files.write(cbr, new byte[] {'R', 'a', 'r', '!', 0x1A, 0x07, 0x01, 0x00, 0, 0});
        IOException ex = assertThrows(IOException.class,
                () -> dock.archive.ArchiveFs.open(LocalFs.INSTANCE, cbr.toString()));
        assertTrue(ex.getMessage().contains("Not a recognizable archive"), ex.getMessage());
    }

    // ---- fixtures ----

    @FunctionalInterface
    private interface Fill {
        void accept(ZipArchiveOutputStream out) throws IOException;
    }

    private Path zip(String name, Fill fill) throws Exception {
        Path p = dir.resolve(name);
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(p.toFile())) {
            fill.accept(out);
        }
        return p;
    }

    private static void dirEntry(ZipArchiveOutputStream out, String name) throws IOException {
        var e = new ZipArchiveEntry(name);
        e.setUnixMode(040755);
        e.setTime(T);
        out.putArchiveEntry(e);
        out.closeArchiveEntry();
    }

    private static void fileEntry(ZipArchiveOutputStream out, String name, int mode, byte[] data)
            throws IOException {
        var e = new ZipArchiveEntry(name);
        e.setUnixMode(mode);
        e.setTime(T);
        out.putArchiveEntry(e);
        out.write(data);
        out.closeArchiveEntry();
    }

    private static List<String> names(List<dock.core.fs.FileEntry> entries) {
        List<String> out = new ArrayList<>();
        for (var e : entries) out.add(e.name());
        out.sort(Comparator.naturalOrder());
        return out;
    }
}
