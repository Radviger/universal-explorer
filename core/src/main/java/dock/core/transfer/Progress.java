package dock.core.transfer;

/**
 * The queue seen as one number: the aggregate of the current burst — the
 * batches still in flight or just settled. The footer progress bar renders
 * this.
 */
public record Progress(boolean active, boolean recent, int running, int queued,
                       long speedBytesPerSec, long doneBytes, long totalBytes,
                       int downloads, int uploads) {

    /**
     * Whether the burst's byte total is known. A burst of directory scans
     * only (children not yet enqueued) shows as indeterminate.
     */
    public boolean determinate() { return totalBytes > 0; }

    /** 0–100, clamped; meaningless while {@link #determinate()} is false. */
    public int percent() {
        return totalBytes <= 0 ? 0 : (int) Math.min(100, doneBytes * 100 / totalBytes);
    }
}
