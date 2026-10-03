package dock;

import static org.junit.jupiter.api.Assertions.*;

import dock.core.fs.FileEntry;
import dock.core.fs.LocalFs;
import dock.sftp.SftpFs;
import dock.sftp.SshSessions;
import dock.core.transfer.ConflictDecision;
import dock.core.transfer.ConflictRule;
import dock.core.transfer.TransferEngine;
import dock.core.transfer.TransferJob;
import dock.sftp.DemoServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransferEngineTest {

    static DemoServer.Handle demo;
    static SftpFs remote;
    static final LocalFs local = LocalFs.INSTANCE;
    static final TransferEngine engine = TransferEngine.GLOBAL;
    static Path tmp;

    @BeforeAll
    static void connect() throws IOException {
        demo = DemoServer.start();
        remote = SshSessions.connect("demo", "127.0.0.1", demo.port(), "demo".toCharArray(),
                null, null, AcceptAllServerKeyVerifier.INSTANCE);
        tmp = Files.createTempDirectory("dock-transfer-test");
    }

    @AfterAll
    static void disconnect() throws IOException {
        if (remote != null) remote.close();
        if (demo != null) demo.close();
    }

    @BeforeEach
    void reset() {
        engine.setResolver(null); // PROMPT default = overwrite path for fresh files
        engine.clearFinished();
    }

    private static Path localFile(String name, String content, String mtime) throws IOException {
        Path p = tmp.resolve(name);
        Files.writeString(p, content);
        Files.setLastModifiedTime(p,
                java.nio.file.attribute.FileTime.from(Instant.parse(mtime)));
        return p;
    }

    private static void await(String what, Predicate<List<TransferJob>> done) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (done.test(engine.snapshot())) return;
            try { Thread.sleep(25); } catch (InterruptedException e) { return; }
        }
        fail("timed out waiting for: " + what);
    }

    private static void awaitIdle() {
        await("queue idle", js -> js.stream().noneMatch(TransferJob::isActive));
    }

    private static void upload(Path file, String remoteDir) {
        FileEntry e = new FileEntry(file.getFileName().toString(), false,
                0, 0, null, false);
        engine.enqueue(local, tmp.toString(), List.of(e), remote, remoteDir, false);
    }

    @Test
    void uploadsContentAndPreservesMtime() throws IOException {
        localFile("upload.txt", "payload-123", "2026-09-01T10:00:00Z");
        upload(tmp.resolve("upload.txt"), "/");
        awaitIdle();
        assertTrue(remote.exists("/upload.txt"));
        assertEquals("payload-123", new String(remote.read("/upload.txt").readAllBytes()));
        assertEquals(Instant.parse("2026-09-01T10:00:00Z").toEpochMilli(),
                remote.stat("/upload.txt").mtimeMillis());
    }

    @Test
    void downloadsContent() throws IOException {
        try (var out = remote.write("/down-src.bin", false)) {
            out.write("remote-bytes".getBytes());
        }
        FileEntry e = new FileEntry("down-src.bin", false, 0, 0, null, false);
        engine.enqueue(remote, "/", List.of(e), local, tmp.toString(), false);
        awaitIdle();
        assertEquals("remote-bytes", Files.readString(tmp.resolve("down-src.bin")));
    }

    @Test
    void uploadsDirectoryRecursively() throws IOException {
        Files.createDirectories(tmp.resolve("tree/a/b"));
        Files.writeString(tmp.resolve("tree/one.txt"), "1");
        Files.writeString(tmp.resolve("tree/a/two.txt"), "2");
        Files.writeString(tmp.resolve("tree/a/b/three.txt"), "3");
        FileEntry e = new FileEntry("tree", true, 0, 0, null, false);
        engine.enqueue(local, tmp.toString(), List.of(e), remote, "/", false);
        await("tree uploaded", js -> remote.exists("/tree/a/b/three.txt"));
        awaitIdle();
        assertEquals("2", new String(remote.read("/tree/a/two.txt").readAllBytes()));
    }

    @Test
    void conflictOverwriteReplacesTarget() throws IOException {
        localFile("conf.txt", "new-content", "2026-09-10T00:00:00Z");
        try (var out = remote.write("/conf.txt", false)) {
            out.write("old-content".getBytes());
        }
        engine.setResolver(info -> ConflictDecision.once(ConflictRule.OVERWRITE));
        upload(tmp.resolve("conf.txt"), "/");
        awaitIdle();
        assertEquals("new-content", new String(remote.read("/conf.txt").readAllBytes()));
    }

    @Test
    void conflictSkipLeavesTarget() throws IOException {
        localFile("skip.txt", "new", "2026-09-10T00:00:00Z");
        try (var out = remote.write("/skip.txt", false)) {
            out.write("old".getBytes());
        }
        engine.setResolver(info -> ConflictDecision.once(ConflictRule.SKIP));
        upload(tmp.resolve("skip.txt"), "/");
        awaitIdle();
        assertEquals("old", new String(remote.read("/skip.txt").readAllBytes()));
    }

    @Test
    void conflictNewerOnlyOverwritesStaleTarget() throws IOException {
        localFile("newer.txt", "fresh", "2026-09-20T00:00:00Z");
        try (var out = remote.write("/newer.txt", false)) {
            out.write("stale".getBytes());
        }
        remote.setTimes("/newer.txt", Instant.parse("2026-09-01T00:00:00Z").toEpochMilli());
        engine.setResolver(info -> ConflictDecision.once(ConflictRule.OVERWRITE_IF_NEWER));
        upload(tmp.resolve("newer.txt"), "/");
        awaitIdle();
        assertEquals("fresh", new String(remote.read("/newer.txt").readAllBytes()));
    }

    @Test
    void conflictNewerOnlySkipsFreshTarget() throws IOException {
        localFile("older.txt", "stale", "2026-09-01T00:00:00Z");
        try (var out = remote.write("/older.txt", false)) {
            out.write("fresh".getBytes());
        }
        remote.setTimes("/older.txt", Instant.parse("2026-09-20T00:00:00Z").toEpochMilli());
        engine.setResolver(info -> ConflictDecision.once(ConflictRule.OVERWRITE_IF_NEWER));
        upload(tmp.resolve("older.txt"), "/");
        awaitIdle();
        assertEquals("fresh", new String(remote.read("/older.txt").readAllBytes()));
    }

    @Test
    void conflictRenameKeepsBoth() throws IOException {
        localFile("dup.txt", "second", "2026-09-10T00:00:00Z");
        try (var out = remote.write("/dup.txt", false)) {
            out.write("first".getBytes());
        }
        engine.setResolver(info -> ConflictDecision.once(ConflictRule.RENAME));
        upload(tmp.resolve("dup.txt"), "/");
        awaitIdle();
        assertEquals("first", new String(remote.read("/dup.txt").readAllBytes()));
        assertEquals("second", new String(remote.read("/dup (1).txt").readAllBytes()));
    }

    @Test
    void applyToAllSticksToBatch() throws IOException {
        localFile("b1.txt", "x1", "2026-09-10T00:00:00Z");
        localFile("b2.txt", "x2", "2026-09-10T00:00:00Z");
        try (var out = remote.write("/b1.txt", false)) { out.write("old1".getBytes()); }
        try (var out = remote.write("/b2.txt", false)) { out.write("old2".getBytes()); }
        var calls = new int[]{0};
        engine.setResolver(info -> {
            calls[0]++;
            return new ConflictDecision(ConflictRule.SKIP, true);
        });
        engine.enqueue(local, tmp.toString(),
                List.of(entry("b1.txt"), entry("b2.txt")), remote, "/", false);
        awaitIdle();
        assertEquals("old1", new String(remote.read("/b1.txt").readAllBytes()));
        assertEquals("old2", new String(remote.read("/b2.txt").readAllBytes()));
        assertEquals(1, calls[0], "apply-to-all must resolve the batch after one prompt");
    }

    @Test
    void moveDeletesSourceAfterCopy() throws IOException {
        localFile("mv.txt", "moved", "2026-09-10T00:00:00Z");
        FileEntry e = entry("mv.txt");
        engine.enqueue(local, tmp.toString(), List.of(e), remote, "/", true);
        awaitIdle();
        assertEquals("moved", new String(remote.read("/mv.txt").readAllBytes()));
        assertFalse(Files.exists(tmp.resolve("mv.txt")));
    }

    @Test
    void pauseAndResumeCompletes() throws IOException {
        // Multi-chunk file so the worker can observe the pause mid-copy.
        String payload = "abcdefghij".repeat(200_000); // 2 MB
        localFile("pause.txt", payload, "2026-09-10T00:00:00Z");
        FileEntry e = entry("pause.txt");
        engine.enqueue(local, tmp.toString(), List.of(e), remote, "/", false);
        TransferJob job = engine.snapshot().stream()
                .filter(j -> j.name().equals("pause.txt")).findFirst().orElseThrow();
        engine.pause(job.id());
        await("job paused or done", js -> js.stream().anyMatch(j ->
                j.id().equals(job.id())
                        && (j.state() == TransferJob.State.PAUSED
                            || j.state() == TransferJob.State.DONE)));
        engine.resume(job.id());
        awaitIdle();
        assertEquals(payload, new String(remote.read("/pause.txt").readAllBytes()));
    }

    @Test
    void failedJobCarriesErrorAndCanBeRetried() throws IOException {
        localFile("retry.txt", "content", "2026-09-10T00:00:00Z");
        engine.setResolver(info -> {
            throw new IllegalStateException("resolver blew up");
        });
        upload(tmp.resolve("retry.txt"), "/"); // target missing → no conflict path taken
        awaitIdle();
        assertTrue(remote.exists("/retry.txt"));

        // Now force a failure: delete the local source and retry after failure.
        Files.delete(tmp.resolve("retry.txt"));
        try (var out = remote.write("/gone.txt", false)) { out.write("x".getBytes()); }
        FileEntry e = new FileEntry("gone.txt", false, 0, 0, null, false);
        engine.setResolver(null);
        engine.enqueue(remote, "/", List.of(e), local, tmp.resolve("no-such-dir").toString(), false);
        await("failed job present", js -> js.stream()
                .anyMatch(j -> j.state() == TransferJob.State.FAILED && j.error() != null));
        TransferJob failed = engine.snapshot().stream()
                .filter(j -> j.state() == TransferJob.State.FAILED).findFirst().orElseThrow();
        assertNotNull(failed.error());
    }

    private static FileEntry entry(String name) {
        return new FileEntry(name, false, 0, 0, null, false);
    }
}
