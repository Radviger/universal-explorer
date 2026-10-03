package dock.core.transfer;

import dock.core.fs.FileSystem;
import java.util.UUID;

/** One unit of transfer work: one file copy or one directory scan. */
public final class TransferJob {

    public enum State { QUEUED, RUNNING, PAUSED, DONE, FAILED, SKIPPED, CANCELLED }

    public enum Kind { FILE, DIRECTORY }

    private final UUID id = UUID.randomUUID();
    private final UUID batchId;
    private final Kind kind;
    private final FileSystem src;
    private final String srcPath;
    private final FileSystem dst;
    private final String dstPath;
    private final boolean move;

    private volatile State state = State.QUEUED;
    private volatile long size;
    private volatile long transferred;
    private volatile long speedBytesPerSec;   // smoothed
    private volatile String error;
    /** Bytes already at the target from an interrupted attempt; retry resumes from here. */
    private volatile long resumeFrom;
    /** Epoch millis when the job reached a terminal state (0 while active). */
    private volatile long finishedAt;

    TransferJob(UUID batchId, Kind kind, FileSystem src, String srcPath,
                FileSystem dst, String dstPath, boolean move, long size) {
        this.batchId = batchId;
        this.kind = kind;
        this.src = src;
        this.srcPath = srcPath;
        this.dst = dst;
        this.dstPath = dstPath;
        this.move = move;
        this.size = size;
    }

    public UUID id() { return id; }
    public UUID batchId() { return batchId; }
    public Kind kind() { return kind; }
    public FileSystem src() { return src; }
    public String srcPath() { return srcPath; }
    public FileSystem dst() { return dst; }
    public String dstPath() { return dstPath; }
    public boolean move() { return move; }

    public State state() { return state; }
    public long size() { return size; }
    public long transferred() { return transferred; }
    public long speedBytesPerSec() { return speedBytesPerSec; }
    public String error() { return error; }

    public String name() {
        String p = srcPath;
        int cut = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return cut >= 0 ? p.substring(cut + 1) : p;
    }

    // package-private mutators for the engine
    void setState(State s) { state = s; }
    /** Terminal transition; records when the job settled (drives the footer's linger). */
    void settle(State s) {
        state = s;
        finishedAt = System.currentTimeMillis();
    }
    /** Fixed clock for tests. */
    void setFinishedAt(long millis) { finishedAt = millis; }
    void setSize(long s) { size = s; }
    void setTransferred(long t) { transferred = t; }
    void setSpeed(long bps) { speedBytesPerSec = bps; }
    void setError(String e) { error = e; }
    void setResumeFrom(long bytes) { resumeFrom = bytes; }

    long resumeFrom() { return resumeFrom; }
    public long finishedAtMillis() { return finishedAt; }

    public boolean isActive() {
        State s = state;
        return s == State.QUEUED || s == State.RUNNING || s == State.PAUSED;
    }

    public boolean isFinished() {
        State s = state;
        return s == State.DONE || s == State.FAILED || s == State.SKIPPED
                || s == State.CANCELLED;
    }
}
