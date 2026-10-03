package dock;

import static org.junit.jupiter.api.Assertions.*;

import dock.core.fs.FileEntry;
import dock.core.fs.LocalFs;
import dock.sftp.SftpFs;
import dock.sftp.SshSessions;
import dock.core.transfer.TransferEngine;
import dock.core.transfer.TransferJob;
import dock.sftp.DemoServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.junit.jupiter.api.Test;

/** M3: connection-loss notification and transfer resume. */
class M3Test {

    @Test
    void connectionLossIsNotified() throws Exception {
        DemoServer.Handle demo = DemoServer.start();
        int port = demo.port();
        CountDownLatch lost = new CountDownLatch(1);
        SftpFs fs = SshSessions.connect(
                new SshSessions.ConnectionSpec("demo", "127.0.0.1", port,
                        "demo".toCharArray(), null, null),
                AcceptAllServerKeyVerifier.INSTANCE, lost::countDown);
        assertTrue(fs.exists("/readme.md"));
        demo.close(); // server dies underneath the client
        assertTrue(lost.await(10, TimeUnit.SECONDS),
                "connection-lost listener must fire when the server goes away");
        fs.close();

        // And a reconnect with the same spec works against a fresh server.
        try (DemoServer.Handle revived = DemoServer.start(port)) {
            SftpFs again = SshSessions.connect(
                    new SshSessions.ConnectionSpec("demo", "127.0.0.1", port,
                            "demo".toCharArray(), null, null),
                    AcceptAllServerKeyVerifier.INSTANCE, null);
            try {
                assertTrue(again.exists("/readme.md"));
            } finally {
                again.close();
            }
        }
    }

    @Test
    void cancelledTransferResumesOnRetry() throws Exception {
        try (DemoServer.Handle demo = DemoServer.start()) {
            SftpFs fs = SshSessions.connect("demo", "127.0.0.1", demo.port(),
                    "demo".toCharArray(), null, null, AcceptAllServerKeyVerifier.INSTANCE);
            try {
                Path big = Files.createTempFile("dock-resume-", ".bin");
                byte[] pattern = new byte[1024 * 1024];
                new java.util.Random(42).nextBytes(pattern);
                try (var out = Files.newOutputStream(big)) {
                    for (int i = 0; i < 24; i++) out.write(pattern); // 24 MB
                }

                FileEntry e = new FileEntry(big.getFileName().toString(), false, 0, 0, null, false);
                TransferEngine engine = TransferEngine.GLOBAL;
                engine.setResolver(null);
                engine.clearFinished();
                engine.enqueue(LocalFs.INSTANCE, big.getParent().toString(),
                        List.of(e), fs, "/", false);

                AtomicReference<TransferJob> job = new AtomicReference<>();
                long deadline = System.currentTimeMillis() + 10_000;
                while (System.currentTimeMillis() < deadline) {
                    engine.snapshot().stream()
                            .filter(j -> j.name().equals(big.getFileName().toString()))
                            .findFirst().ifPresent(job::set);
                    if (job.get() != null && job.get().transferred() > 0) break;
                    Thread.sleep(10);
                }
                TransferJob j = job.get();
                assertNotNull(j, "job never started");
                engine.cancel(j.id());
                awaitState(engine, j, TransferJob.State.CANCELLED);
                long partial = fs.stat("/" + big.getFileName()).size();
                assertTrue(partial > 0 && partial < 24L * pattern.length,
                        "expected a partial target, got " + partial);

                engine.retry(j.id());
                awaitState(engine, j, TransferJob.State.DONE);
                assertEquals(24L * pattern.length, fs.stat("/" + big.getFileName()).size(),
                        "resumed target must be complete");
                // Content integrity: the resumed upload equals the source.
                try (var in = fs.read("/" + big.getFileName())) {
                    byte[] verify = new byte[pattern.length];
                    for (int i = 0; i < 24; i++) {
                        assertEquals(pattern.length, in.readNBytes(verify, 0, verify.length));
                        assertArrayEquals(pattern, verify, "block " + i + " corrupted");
                    }
                }
            } finally {
                fs.close();
            }
        }
    }

    private static void awaitState(TransferEngine engine, TransferJob job,
                                   TransferJob.State state) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            TransferJob now = engine.byId(job.id());
            if (now != null && now.state() == state) return;
            try { Thread.sleep(10); } catch (InterruptedException e) { return; }
        }
        fail("job never reached " + state + ", now="
                + (engine.byId(job.id()) == null ? "gone" : engine.byId(job.id()).state()));
    }
}
