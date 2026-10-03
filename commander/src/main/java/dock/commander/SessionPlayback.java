package dock.commander;

import dock.core.config.Sites;
import dock.media.PlaybackMemory;

/**
 * The player's bookmarks, kept in the session file: positions ride the
 * site's record exactly like the path memory, under the same rules — an
 * unsaved quick connect has nothing to key memory on and stays silent.
 * Best-effort throughout; a bookmark must never fail a close.
 */
final class SessionPlayback implements PlaybackMemory {

    private final String site;

    SessionPlayback(String site) {
        this.site = site;
    }

    @Override public Long positionOf(String path, long mtimeMs) {
        if (site == null) return null;
        long at = Sites.playbackPositionOf(site, path, mtimeMs);
        return at < 0 ? null : at;
    }

    @Override public void record(String path, long mtimeMs, long positionMs) {
        if (site == null) return;
        try {
            Sites.recordPlayback(site, path, mtimeMs, positionMs);
        } catch (Exception ignored) {
            // Best-effort memory.
        }
    }
}
