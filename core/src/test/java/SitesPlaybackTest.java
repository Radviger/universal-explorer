import dock.core.config.AppPaths;
import dock.core.config.Site;
import dock.core.config.Sites;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The player's bookmark on the session store: round-trip through
 * sessions.json, the mtime staleness guard, watched-through forgetting,
 * the bounded shelf, and the silence of unsaved sessions.
 */
class SitesPlaybackTest {

    @TempDir
    static Path configDir;

    @BeforeAll
    static void isolate() {
        AppPaths.override(configDir);
    }

    @BeforeEach
    void freshStore() throws Exception {
        Files.deleteIfExists(configDir.resolve("sessions.json"));
        Sites.save(List.of(new Site("nas", "192.0.2.10", 22, "user", null)));
    }

    @Test
    void remembersWherePlaybackStood() throws Exception {
        Sites.recordPlayback("nas", "/media/film.mkv", 1_000L, 240_000L);
        assertEquals(240_000L,
                Sites.playbackPositionOf("nas", "/media/film.mkv", 1_000L));
        assertEquals(240_000L,
                Sites.playbackPositionOf("nas", "/media/film.mkv", 0L),
                "an unknown current mtime trusts the bookmark");
    }

    @Test
    void aChangedFileVoidsItsBookmark() throws Exception {
        Sites.recordPlayback("nas", "/f.mkv", 1_000L, 240_000L);
        assertEquals(-1, Sites.playbackPositionOf("nas", "/f.mkv", 2_000L));
    }

    @Test
    void watchedThroughForgets() throws Exception {
        Sites.recordPlayback("nas", "/f.mkv", 1_000L, 240_000L);
        Sites.recordPlayback("nas", "/f.mkv", 1_000L, 0L);
        assertEquals(-1, Sites.playbackPositionOf("nas", "/f.mkv", 1_000L));
    }

    @Test
    void theShelfIsBounded() throws Exception {
        for (int i = 0; i < 40; i++)
            Sites.recordPlayback("nas", "/f" + i + ".mkv", 1_000L, 60_000L);
        Site site = Sites.load().get(0);
        assertEquals(32, site.playback().size(), "a site keeps 32 bookmarks");
        assertEquals(-1, Sites.playbackPositionOf("nas", "/f0.mkv", 1_000L),
                "the oldest bookmark fell off the shelf");
        assertEquals(60_000L, Sites.playbackPositionOf("nas", "/f39.mkv", 1_000L));
    }

    @Test
    void unsavedSessionsKeyNothing() throws Exception {
        Sites.recordPlayback("quick", "/f.mkv", 1_000L, 240_000L);
        assertEquals(-1, Sites.playbackPositionOf("quick", "/f.mkv", 1_000L));
        assertEquals(1, Sites.load().size(), "no phantom site appeared");
    }
}
