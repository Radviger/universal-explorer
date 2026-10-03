package dock.media;

/**
 * Where the player keeps its bookmarks between runs — the session file's
 * playback memory, wired by the host view. Implementations do their own
 * I/O and are called from the player's housekeeping threads, never the
 * EDT; a bookmark is best-effort and must never fail a close.
 */
public interface PlaybackMemory {

    /** The remembered position (ms) for a file, or null when nothing
     *  sane is on file — unknown file, nothing worth resuming, or a
     *  changed one (the staleness guard rides in the implementation,
     *  keyed on the mtime handed in). */
    Long positionOf(String path, long mtimeMs);

    /** Records where a file stands; implementations typically treat
     *  near-zero as "forget". */
    void record(String path, long mtimeMs, long positionMs);
}
