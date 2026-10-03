package dock.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persists the saved-session list as sessions.json in the config dir. */
public final class Sites {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static Path file;
    private static Path fileDir;

    private Sites() {}

    /** Resolved per config dir so an AppPaths override (tests) takes effect. */
    private static synchronized Path file() {
        Path dir = AppPaths.config();
        if (file == null || !dir.equals(fileDir)) {
            fileDir = dir;
            file = dir.resolve("sessions.json");
        }
        return file;
    }

    public static synchronized List<Site> load() {
        if (!Files.exists(file())) return new ArrayList<>();
        try {
            return new ArrayList<>(List.of(JSON.readValue(file().toFile(), Site[].class)));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    public static synchronized void save(List<Site> sites) throws IOException {
        JSON.writerWithDefaultPrettyPrinter().writeValue(file().toFile(), sites.toArray(new Site[0]));
    }

    public static synchronized void upsert(Site site) throws IOException {
        List<Site> all = load();
        all.removeIf(s -> s.name().equals(site.name()));
        all.add(site);
        save(all);
    }

    /** Replaces the entry saved under {@code originalName} with {@code
     *  site} — an edit, which may have renamed it; inserts when no such
     *  entry exists. A renamed entry's secret moves with it (the
     *  caller's job). */
    public static synchronized void replace(String originalName, Site site) throws IOException {
        List<Site> all = load();
        all.removeIf(s -> s.name().equals(originalName) || s.name().equals(site.name()));
        all.add(site);
        save(all);
    }

    /** Deletes by name. The associated secret is removed by the caller. */
    public static synchronized void remove(String name) throws IOException {
        List<Site> all = load();
        all.removeIf(s -> s.name().equals(name));
        save(all);
    }

    /** Records a use (successful connect); ignored for unknown names. */
    public static synchronized void touch(String name) throws IOException {
        List<Site> all = load();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).name().equals(name)) {
                all.set(i, all.get(i).withLastUsed(System.currentTimeMillis()));
                save(all);
                return;
            }
        }
    }

    /** Records the directories the panes last showed on a site (the path
     *  memory); ignored for unknown names — an unsaved quick connect has
     *  nothing to key memory on. */
    public static synchronized void recordPaths(String name, String localDir,
                                                String remoteDir) throws IOException {
        List<Site> all = load();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).name().equals(name)) {
                all.set(i, all.get(i).withPaths(localDir, remoteDir));
                save(all);
                return;
            }
        }
    }

    /** The player's bookmark: where playback stood on one of a site's
     *  files, or -1 — unknown site, no bookmark, or a bookmark for an
     *  older file on disk (the mtime moved). */
    public static synchronized long playbackPositionOf(String name, String path,
                                                       long mtimeMs) {
        for (Site s : load()) {
            if (!s.name().equals(name)) continue;
            Site.Playback mark = s.playback() == null ? null : s.playback().get(path);
            if (mark == null) return -1;
            if (mark.mtimeMs() > 0 && mtimeMs > 0 && mark.mtimeMs() != mtimeMs)
                return -1;   // the file changed since — the bookmark is stale
            return mark.positionMs();
        }
        return -1;
    }

    /** Records (or, near zero, forgets) where playback stands on a file;
     *  ignored for unknown names. The shelf is bounded — a site keeps at
     *  most 32 bookmarks, oldest out. */
    public static synchronized void recordPlayback(String name, String path,
            long mtimeMs, long positionMs) throws IOException {
        List<Site> all = load();
        for (int i = 0; i < all.size(); i++) {
            Site s = all.get(i);
            if (!s.name().equals(name)) continue;
            Map<String, Site.Playback> map = new LinkedHashMap<>(
                    s.playback() == null ? Map.of() : s.playback());
            if (positionMs < 5_000) {
                map.remove(path);   // nothing worth resuming — watched through
            } else {
                map.put(path, new Site.Playback(positionMs, mtimeMs));
                while (map.size() > 32)
                    map.remove(map.keySet().iterator().next());
            }
            all.set(i, s.withPlayback(map));
            save(all);
            return;
        }
    }

    /** Launcher order: most recently used first, never-used alphabetically last. */
    public static List<Site> byRecency(List<Site> sites) {
        List<Site> sorted = new ArrayList<>(sites);
        sorted.sort(Comparator
                .comparingLong((Site s) -> s.lastUsed()).reversed()
                .thenComparing(Comparator.comparing(Site::name)));
        return sorted;
    }
}
