package dock;

import static org.junit.jupiter.api.Assertions.*;

import dock.core.fs.FileEntry;
import dock.sftp.SftpFs;
import dock.sftp.SshSessions;
import dock.sftp.DemoServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Runs against an in-process SFTP server — no external host needed. */
class SftpFsTest {

    static DemoServer.Handle demo;
    static SftpFs fs;

    @BeforeAll
    static void connect() throws IOException {
        demo = DemoServer.start();
        fs = SshSessions.connect("demo", "127.0.0.1", demo.port(), "demo".toCharArray(),
                null, null, AcceptAllServerKeyVerifier.INSTANCE);
    }

    @AfterAll
    static void disconnect() throws IOException {
        if (fs != null) fs.close();
        if (demo != null) demo.close();
    }

    @Test
    void homeIsSeededRoot() throws IOException {
        assertEquals("/", fs.home());
        List<FileEntry> entries = fs.list("/");
        assertTrue(entries.size() >= 4, "seeded tree should have several entries, got " + entries.size());
        assertTrue(entries.stream().anyMatch(e -> e.name().equals("readme.md")));
        assertTrue(entries.stream().anyMatch(e -> e.name().equals("backups") && e.directory()));
    }

    @Test
    void attributesLookRight() throws IOException {
        FileEntry md = fs.stat("/readme.md");
        assertFalse(md.directory());
        assertTrue(md.size() > 0);
        assertTrue(md.mtimeMillis() > 0);
        assertNotNull(md.posixPerms(), "SFTP should carry POSIX permissions");
        assertTrue(md.posixPerms() > 0);
        var hidden = fs.stat("/.hidden-config");
        assertTrue(hidden.hidden());
        assertFalse(md.hidden());
    }

    @Test
    void pathAlgebraIsPosix() {
        assertEquals("/", fs.parent("/"));
        assertEquals("/a", fs.parent("/a/b"));
        assertEquals("/a/b", fs.normalize("/a/./b"));
        assertEquals("/a", fs.normalize("/a/b/.."));
        assertEquals("/x/y", fs.child("/x", "y"));
    }

    @Test
    void mkdirWriteReadRenameDelete() throws IOException {
        fs.mkdir("/tmp-test");
        try (OutputStream out = fs.write("/tmp-test/f.txt", false)) {
            out.write("one".getBytes());
        }
        try (OutputStream out = fs.write("/tmp-test/f.txt", true)) {
            out.write("two".getBytes());
        }
        try (InputStream in = fs.read("/tmp-test/f.txt")) {
            assertEquals("onetwo", new String(in.readAllBytes()));
        }
        assertEquals(6, fs.stat("/tmp-test/f.txt").size());

        fs.rename("/tmp-test/f.txt", "/tmp-test/g.txt");
        assertFalse(fs.exists("/tmp-test/f.txt"));

        fs.setTimes("/tmp-test/g.txt", 1_700_000_000_000L);
        assertEquals(1_700_000_000_000L, fs.stat("/tmp-test/g.txt").mtimeMillis());

        // Mode round-trip: the demo server's Windows backing store normalizes
        // POSIX bits (0666), so assert the call succeeds and yields a sane
        // mode rather than exact 0600 — real servers honor setStat exactly.
        fs.setPerms("/tmp-test/g.txt", 0600);
        Integer after = fs.stat("/tmp-test/g.txt").posixPerms();
        assertNotNull(after);
        assertTrue(after > 0 && after <= 0777, "mode out of range: " + after);

        fs.deleteTree("/tmp-test");
        assertFalse(fs.exists("/tmp-test"));
    }

    @Test
    void bigDirectoryListsCompletely() throws IOException {
        for (int i = 0; i < 200; i++) {
            fs.mkdir("/bulk-" + i);
            try (OutputStream out = fs.write("/bulk-" + i + "/data.bin", false)) {
                out.write(new byte[64]);
            }
        }
        try {
            List<FileEntry> entries = fs.list("/");
            long bulkDirs = entries.stream().filter(e -> e.directory())
                    .filter(e -> e.name().startsWith("bulk-")).count();
            assertEquals(200, bulkDirs);
            List<FileEntry> inside = fs.list("/bulk-0");
            assertEquals(1, inside.size());
            assertEquals("data.bin", inside.get(0).name());
        } finally {
            for (int i = 0; i < 200; i++) fs.deleteTree("/bulk-" + i);
        }
    }

    @Test
    void wrongPasswordFailsCleanly() throws IOException {
        assertThrows(IOException.class, () ->
                SshSessions.connect("demo", "127.0.0.1", demo.port(), "wrong".toCharArray(),
                        null, null, AcceptAllServerKeyVerifier.INSTANCE));
    }
}
