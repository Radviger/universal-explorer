import dock.core.fs.LocalFs;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.tukaani.xz.XZOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every format the mount reads beyond the zip family: tar plain and wearing
 * each codec (tgz, tar.bz2, tar.xz), single-file gz/bz2/xz as one virtual
 * entry named after the archive, and 7z. Fixtures are real archives built
 * with Commons Compress itself.
 */
class ArchiveFormatsTest {

    private static final long T = 1_700_000_122_000L;

    private Path dir;

    @BeforeEach
    void makeDir() throws Exception {
        dir = Files.createTempDirectory("dock-formats");
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
    void tarListsAndReadsExactly() throws Exception {
        Path p = dir.resolve("a.tar");
        try (OutputStream out = Files.newOutputStream(p)) {
            tar(tar -> {
                dirEntry(tar, "docs/");
                fileEntry(tar, "docs/report.txt", 0100644, "hello".getBytes());
                fileEntry(tar, "deep/nested/data.bin", 0100600, new byte[10]);
            }, out);
        }
        try (var fs = dock.archive.ArchiveFs.open(LocalFs.INSTANCE, p.toString())) {
            assertEquals(List.of("deep", "docs"), names(fs.list("/")));
            assertEquals(List.of("report.txt"), names(fs.list("/docs")));
            assertEquals(5, fs.stat("/docs/report.txt").size());
            assertEquals(0644, fs.stat("/docs/report.txt").posixPerms());
            assertEquals(0755, fs.stat("/docs").posixPerms());
            assertEquals("hello", new String(fs.read("/docs/report.txt").readAllBytes()));
            assertTrue(fs.stat("/deep").directory(), "implicit tar directories navigate");
        }
    }

    @Test
    void tarWearingEachCodecStillMounts() throws Exception {
        byte[] bytes = tarBytes(tar -> {
            dirEntry(tar, "docs/");
            fileEntry(tar, "docs/a.txt", 0100644, "inside".getBytes());
        });
        for (String name : List.of("a.tgz", "a.tar.bz2", "a.tar.xz")) {
            Path p = dir.resolve(name);
            try (OutputStream raw = Files.newOutputStream(p)) {
                OutputStream wrapped = switch (name.substring(name.lastIndexOf('.') + 1)) {
                    case "tgz" -> new GZIPOutputStream(raw);
                    case "bz2" -> new BZip2CompressorOutputStream(raw);
                    default -> new XZOutputStream(raw, new org.tukaani.xz.LZMA2Options());
                };
                wrapped.write(bytes);
                wrapped.close();
            }
            try (var fs = dock.archive.ArchiveFs.open(LocalFs.INSTANCE, p.toString())) {
                assertEquals(List.of("docs"), names(fs.list("/")), name);
                assertEquals("inside", new String(fs.read("/docs/a.txt").readAllBytes()), name);
            }
        }
    }

    @Test
    void aCompressedSingleFileBecomesOneEntryNamedAfterTheArchive() throws Exception {
        byte[] payload = new byte[100_000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 7 + 1);

        Path gz = dir.resolve("report.gz");
        try (var out = new GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write(payload);
        }
        try (var fs = dock.archive.ArchiveFs.open(LocalFs.INSTANCE, gz.toString())) {
            assertEquals(List.of("report"), names(fs.list("/")));
            assertEquals(payload.length, fs.stat("/report").size(),
                    "the gzip trailer carries the uncompressed size");
            assertArrayEquals(payload, fs.read("/report").readAllBytes());
        }

        Path xz = dir.resolve("report.xz");
        try (var out = new XZOutputStream(Files.newOutputStream(xz), new org.tukaani.xz.LZMA2Options())) {
            out.write(payload);
        }
        try (var fs = dock.archive.ArchiveFs.open(LocalFs.INSTANCE, xz.toString())) {
            assertEquals(payload.length, fs.stat("/report").size(),
                    "xz has no trailer — the mount counts the size");
            assertArrayEquals(payload, fs.read("/report").readAllBytes());
        }

        Path bz2 = dir.resolve("report.bz2");
        try (var out = new BZip2CompressorOutputStream(Files.newOutputStream(bz2))) {
            out.write(payload);
        }
        try (var fs = dock.archive.ArchiveFs.open(LocalFs.INSTANCE, bz2.toString())) {
            assertEquals(List.of("report"), names(fs.list("/")));
            assertArrayEquals(payload, fs.read("/report").readAllBytes());
        }
    }

    @Test
    void sevenZListsAndReads() throws Exception {
        byte[] data = "seven zed payload".getBytes();
        Path p = dir.resolve("a.7z");
        try (var out = new SevenZOutputFile(p.toFile())) {
            var d = new SevenZArchiveEntry();
            d.setName("docs/");
            d.setDirectory(true);
            out.putArchiveEntry(d);
            out.closeArchiveEntry();
            var f = new SevenZArchiveEntry();
            f.setName("docs/a.txt");
            out.putArchiveEntry(f);
            out.write(data);
            out.closeArchiveEntry();
            var empty = new SevenZArchiveEntry();
            empty.setName("empty.bin");
            out.putArchiveEntry(empty);
            out.closeArchiveEntry();
        }
        try (var fs = dock.archive.ArchiveFs.open(LocalFs.INSTANCE, p.toString())) {
            assertEquals(List.of("docs", "empty.bin"), names(fs.list("/")));
            assertEquals(data.length, fs.stat("/docs/a.txt").size());
            assertArrayEquals(data, fs.read("/docs/a.txt").readAllBytes());
            assertEquals(0, fs.read("/empty.bin").readAllBytes().length,
                    "entries without a stream read as empty");
        }
    }

    @Test
    void aGzipHoldingAZipIsASingleFileNotAMount() throws Exception {
        byte[] zipBytes;
        try (var buf = new ByteArrayOutputStream();
             var out = new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(buf)) {
            var e = new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("a.txt");
            out.putArchiveEntry(e);
            out.write("zipped".getBytes());
            out.closeArchiveEntry();
            zipBytes = buf.toByteArray();
        }
        Path p = dir.resolve("wrapped.gz");
        try (var out = new GZIPOutputStream(Files.newOutputStream(p))) {
            out.write(zipBytes);
        }
        try (var fs = dock.archive.ArchiveFs.open(LocalFs.INSTANCE, p.toString())) {
            assertEquals(List.of("wrapped"), names(fs.list("/")),
                    "the member name is not stored — the archive name stands in");
            assertArrayEquals(zipBytes, fs.read("/wrapped").readAllBytes(),
                    "reading gives the decompressed member, not a nested mount");
        }
    }

    // ---- fixtures ----

    @FunctionalInterface
    private interface TarFill {
        void accept(TarArchiveOutputStream out) throws IOException;
    }

    private static byte[] tarBytes(TarFill fill) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        tar(fill, buf);
        return buf.toByteArray();
    }

    private static void tar(TarFill fill, OutputStream target) throws IOException {
        TarArchiveOutputStream out = new TarArchiveOutputStream(target);
        fill.accept(out);
        out.finish();
        out.close();
    }

    private static void dirEntry(TarArchiveOutputStream out, String name) throws IOException {
        var e = new TarArchiveEntry(name);
        e.setMode(040755);
        e.setModTime(T);
        out.putArchiveEntry(e);
        out.closeArchiveEntry();
    }

    private static void fileEntry(TarArchiveOutputStream out, String name, int mode, byte[] data)
            throws IOException {
        var e = new TarArchiveEntry(name);
        e.setMode(mode);
        e.setModTime(T);
        e.setSize(data.length);
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
