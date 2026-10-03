package dock.core.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dock.core.fs.FileSystem;
import dock.core.fs.FileEntry;
import dock.core.fs.LocalFs;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link TransferEngine#overall(List, long, long)} — the footer bar's data.
 * Jobs are constructed directly (their filesystems are never touched; the
 * aggregate only asks which side is remote) and the clock is passed in, so
 * every case here is pure math.
 */
class OverallProgressTest {

    private static final long NOW = 1_000_000L;
    private static final long RECENCY = 2_000L;

    @Test
    void emptyQueueIsIdle() {
        Progress p = TransferEngine.overall(List.of(), NOW, RECENCY);
        assertFalse(p.active());
        assertFalse(p.recent());
        assertFalse(p.determinate());
        assertEquals(0, p.percent());
    }

    @Test
    void byteTotalsRollUpAcrossTheWholeBatch() {
        UUID batch = UUID.randomUUID();
        TransferJob running = file(batch, 100, 40, TransferJob.State.RUNNING);
        running.setSpeed(1_000_000);
        TransferJob done = file(batch, 300, 300, TransferJob.State.DONE);
        TransferJob queued = file(batch, 50, 0, TransferJob.State.QUEUED);
        queued.setSize(0); // size unknown until the worker stats it

        Progress p = TransferEngine.overall(List.of(running, done, queued), NOW, RECENCY);
        assertTrue(p.active());
        assertTrue(p.determinate());
        assertEquals(340, p.doneBytes());
        assertEquals(400, p.totalBytes());
        assertEquals(85, p.percent());
        assertEquals(1, p.running());
        assertEquals(1, p.queued());
        assertEquals(1_000_000, p.speedBytesPerSec());
    }

    @Test
    void directoryJobsCarryChildCountsNotBytes() {
        UUID batch = UUID.randomUUID();
        TransferJob scan = new TransferJob(batch, TransferJob.Kind.DIRECTORY,
                LocalFs.INSTANCE, "C:\\in", LocalFs.INSTANCE, "C:\\out", false, 5);
        scan.setState(TransferJob.State.RUNNING);
        scan.setSize(5); // five children discovered

        Progress onlyScan = TransferEngine.overall(List.of(scan), NOW, RECENCY);
        assertTrue(onlyScan.active());
        assertFalse(onlyScan.determinate(), "a bare directory scan has no byte total");

        // Once the scan's children enqueue, the same batch becomes measurable.
        TransferJob child = file(batch, 500, 100, TransferJob.State.RUNNING);
        Progress withChild = TransferEngine.overall(List.of(scan, child), NOW, RECENCY);
        assertTrue(withChild.determinate());
        assertEquals(500, withChild.totalBytes());
        assertEquals(20, withChild.percent());
    }

    @Test
    void settledBurstLingersThenDropsOut() {
        UUID batch = UUID.randomUUID();
        TransferJob done = file(batch, 200, 200, TransferJob.State.DONE);

        done.setFinishedAt(NOW - 100); // settled moments ago
        Progress lingering = TransferEngine.overall(List.of(done), NOW, RECENCY);
        assertFalse(lingering.active());
        assertTrue(lingering.recent(), "a just-settled burst holds its final numbers");
        assertEquals(100, lingering.percent());

        done.setFinishedAt(NOW - RECENCY - 1); // a breath past the window
        Progress expired = TransferEngine.overall(List.of(done), NOW, RECENCY);
        assertFalse(expired.recent());
        assertEquals(0, expired.totalBytes(), "old batches must not pollute the bar");
    }

    @Test
    void finishedBatchesDropOutOfTheBurst() {
        UUID earlier = UUID.randomUUID();
        TransferJob old = file(earlier, 1_000_000, 1_000_000, TransferJob.State.DONE);
        old.setFinishedAt(NOW - 60_000);
        assertFalse(TransferEngine.overall(List.of(old), NOW, RECENCY).active(),
                "a queue of only finished jobs is idle");

        UUID current = UUID.randomUUID();
        TransferJob running = file(current, 100, 50, TransferJob.State.RUNNING);
        Progress p = TransferEngine.overall(List.of(old, running), NOW, RECENCY);
        assertTrue(p.active());
        assertEquals(100, p.totalBytes(), "earlier bursts must not pollute the bar");
        assertEquals(50, p.percent());
    }

    @Test
    void directionSplitCountsWireSidesOnly() {
        UUID batch = UUID.randomUUID();
        TransferJob down = new TransferJob(batch, TransferJob.Kind.FILE,
                new RemoteFs(), "/srv.bin", LocalFs.INSTANCE, "C:\\srv.bin", false, 10);
        down.setState(TransferJob.State.RUNNING);
        TransferJob up = new TransferJob(batch, TransferJob.Kind.FILE,
                LocalFs.INSTANCE, "C:\\up.bin", new RemoteFs(), "/up.bin", false, 10);
        up.setState(TransferJob.State.QUEUED);
        TransferJob localCopy = file(batch, 10, 0, TransferJob.State.QUEUED);

        Progress p = TransferEngine.overall(List.of(down, up, localCopy), NOW, RECENCY);
        assertEquals(1, p.downloads());
        assertEquals(1, p.uploads(), "a local-to-local copy is neither");
    }

    @Test
    void percentClampsAtOneHundred() {
        UUID batch = UUID.randomUUID();
        TransferJob oversized = file(batch, 100, 250, TransferJob.State.RUNNING);
        assertEquals(100, TransferEngine.overall(List.of(oversized), NOW, RECENCY).percent());
    }

    // ---- helpers ----

    private static TransferJob file(UUID batch, long size, long transferred,
                                    TransferJob.State state) {
        TransferJob j = new TransferJob(batch, TransferJob.Kind.FILE,
                LocalFs.INSTANCE, "C:\\a.bin", LocalFs.INSTANCE, "C:\\b.bin", false, size);
        j.setState(state);
        j.setTransferred(transferred);
        if (j.isFinished()) j.setFinishedAt(NOW);
        return j;
    }

    /** Stands in for a wire backend; the aggregate only ever asks remote(). */
    private static final class RemoteFs implements FileSystem {
        @Override public String label() { return "remote"; }
        @Override public boolean remote() { return true; }
        @Override public String separator() { return "/"; }
        @Override public String home() { throw new UnsupportedOperationException(); }
        @Override public List<String> roots() { throw new UnsupportedOperationException(); }
        @Override public String normalize(String path) { throw new UnsupportedOperationException(); }
        @Override public String parent(String path) { throw new UnsupportedOperationException(); }
        @Override public String child(String dir, String name) { throw new UnsupportedOperationException(); }
        @Override public boolean exists(String path) { throw new UnsupportedOperationException(); }
        @Override public List<FileEntry> list(String path) { throw new UnsupportedOperationException(); }
        @Override public FileEntry stat(String path) { throw new UnsupportedOperationException(); }
        @Override public void mkdir(String path) { throw new UnsupportedOperationException(); }
        @Override public void delete(String path) { throw new UnsupportedOperationException(); }
        @Override public void rename(String from, String to) { throw new UnsupportedOperationException(); }
        @Override public InputStream read(String path) { throw new UnsupportedOperationException(); }
        @Override public OutputStream write(String path, boolean append) { throw new UnsupportedOperationException(); }
        @Override public void setTimes(String path, long mtimeMillis) { throw new UnsupportedOperationException(); }
        @Override public void setPerms(String path, int posix) { throw new UnsupportedOperationException(); }
        @Override public void close() { throw new UnsupportedOperationException(); }
    }
}
