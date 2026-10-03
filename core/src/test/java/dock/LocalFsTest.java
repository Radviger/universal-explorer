package dock;

import static org.junit.jupiter.api.Assertions.*;

import dock.core.fs.FileEntry;
import dock.core.fs.LocalFs;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFsTest {

    @Test
    void listsDirectoryWithAttributes(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("a.txt"), "hello");
        Files.createDirectory(tmp.resolve("sub"));

        var entries = LocalFs.INSTANCE.list(tmp.toString());
        assertEquals(2, entries.size());
        var a = entries.stream().filter(e -> e.name().equals("a.txt")).findFirst().orElseThrow();
        var sub = entries.stream().filter(e -> e.name().equals("sub")).findFirst().orElseThrow();
        assertEquals(5, a.size());
        assertFalse(a.directory());
        assertTrue(sub.directory());
        assertTrue(a.mtimeMillis() > 0);
    }

    @Test
    void pathAlgebra() {
        var fs = LocalFs.INSTANCE;
        var base = fs.normalize(tmpPath("x", "y"));
        assertEquals(tmpPath("x"), fs.parent(base));
        assertEquals(tmpPath("x", "z"), fs.child(base.substring(0, base.length() - 2), "z"));
        assertTrue(!fs.roots().isEmpty());
    }

    @Test
    void writeReadRenameDelete(@TempDir Path tmp) throws IOException {
        var fs = LocalFs.INSTANCE;
        String file = tmp.resolve("f.txt").toString();
        try (OutputStream out = fs.write(file, false)) {
            out.write("one".getBytes());
        }
        try (OutputStream out = fs.write(file, true)) {
            out.write("two".getBytes());
        }
        try (InputStream in = fs.read(file)) {
            assertEquals("onetwo", new String(in.readAllBytes()));
        }
        String moved = tmp.resolve("g.txt").toString();
        fs.rename(file, moved);
        assertFalse(fs.exists(file));
        assertTrue(fs.exists(moved));

        fs.setTimes(moved, 1_700_000_000_000L);
        assertEquals(1_700_000_000_000L, fs.stat(moved).mtimeMillis());

        fs.deleteTree(moved);
        assertFalse(fs.exists(moved));
    }

    @Test
    void deleteTreeRemovesNestedContent(@TempDir Path tmp) throws IOException {
        var fs = LocalFs.INSTANCE;
        Files.createDirectories(tmp.resolve("a/b"));
        Files.writeString(tmp.resolve("a/b/c.txt"), "x");
        fs.deleteTree(tmp.resolve("a").toString());
        assertFalse(Files.exists(tmp.resolve("a")));
    }

    @Test
    void positionedReadStartsAtTheOffset(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("p.bin"), "0123456789");
        try (InputStream in = LocalFs.INSTANCE.read(tmp.resolve("p.bin").toString(), 7)) {
            assertEquals("789", new String(in.readAllBytes()),
                    "the positioned open serves from byte 7, not from the top");
        }
        // A fresh stream per call: two overlapping windows must not share
        // channel state.
        try (InputStream a = LocalFs.INSTANCE.read(tmp.resolve("p.bin").toString(), 2);
             InputStream b = LocalFs.INSTANCE.read(tmp.resolve("p.bin").toString(), 5)) {
            assertEquals("234", new String(a.readNBytes(3)));
            assertEquals("567", new String(b.readNBytes(3)));
        }
    }

    private static String tmpPath(String... parts) {
        return Path.of("C:\\", parts).toString();
    }
}
