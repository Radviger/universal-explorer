package dock.core.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Well-known locations: config under %APPDATA%\Universal Explorer. */
public final class AppPaths {

    private static final Path CONFIG = Path.of(
            System.getenv().getOrDefault("APPDATA",
                    Path.of(System.getProperty("user.home"), "AppData", "Roaming").toString()),
            "Universal Explorer");

    /** Test/harness isolation; must be set before the first {@link #config()}. */
    private static volatile Path override;

    private AppPaths() {}

    public static Path config() {
        Path dir = override != null ? override : CONFIG;
        try {
            if (!Files.exists(dir)) adoptLegacyFolder(dir);
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create config dir " + dir, e);
        }
        return dir;
    }

    /** The app was Project Dock once: the first launch under the new name
     *  moves the old %APPDATA%\Dock tree over wholesale. Best effort — a
     *  locked file merely stays behind, and a total failure just starts
     *  fresh, exactly as a new user would. */
    private static void adoptLegacyFolder(Path dir) {
        Path abs = dir.toAbsolutePath().normalize();
        Path legacy = abs.getParent() == null ? null : abs.getParent().resolve("Dock");
        if (legacy == null || legacy.equals(abs) || !Files.isDirectory(legacy)) return;
        try {
            Files.move(legacy, abs);
            return;
        } catch (IOException moveFailed) {
            // something inside is locked — copy what we can instead
        }
        try (var stream = Files.walk(legacy)) {
            stream.forEach(from -> {
                Path to = abs.resolve(legacy.relativize(from));
                try {
                    if (Files.isDirectory(from)) Files.createDirectories(to);
                    else {
                        Files.createDirectories(to.getParent());
                        Files.copy(from, to);
                    }
                } catch (IOException ignored) {
                    // locked or unreadable: it stays in the legacy tree
                }
            });
        } catch (IOException ignored) {
            // never let a migration failure block startup
        }
    }

    /** Ephemeral scratch inside the config tree (the player's fetched
     *  subtitle sidecars) — occupants delete their own files; nothing
     *  here is a store. */
    public static Path cache() {
        Path dir = config().resolve("cache");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create cache dir " + dir, e);
        }
        return dir;
    }

    /** Redirects the config dir (tests and the screenshot harness). */
    public static void override(Path dir) {
        override = dir;
    }
}
