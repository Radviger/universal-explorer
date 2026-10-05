package dock.media;

import com.sun.jna.NativeLibrary;
import dock.core.platform.Os;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import uk.co.caprica.vlcj.binding.lib.LibC;
import uk.co.caprica.vlcj.factory.discovery.strategy.NativeDiscoveryStrategy;

/**
 * Where the libVLC runtime lives on this OS and how it loads — the only
 * per-OS knowledge the player has. Handed to vlcj's {@code NativeDiscovery},
 * whose first success is process-wide: every player built afterwards reuses
 * this runtime instead of running vlcj's own search.
 *
 * <p>A runtime "home" mirrors the official build of each OS:
 * <ul>
 * <li>Windows: {@code libvlc.dll} with {@code plugins\} beside it (the
 *     portable zip; libVLC finds the plugins on its own).
 * <li>macOS: {@code lib/libvlc.dylib} and {@code plugins/} (the
 *     {@code VLC.app/Contents/MacOS} layout). Its libvlc names libvlccore
 *     through an {@code @rpath} it never sets, so libvlccore is loaded
 *     first, and {@code VLC_PLUGIN_PATH} points at the plugins.
 * <li>Linux: a distro's {@code libvlc.so.N}; the plugin path is compiled in.
 * </ul>
 * Search order: the {@code dock.vlc} property, the {@code DOCK_VLC}
 * environment variable, the bundled {@code vlc/} directory (from the working
 * directory up, then beside the classpath), and a system install last.
 */
final class VlcRuntime implements NativeDiscoveryStrategy {

    private static final Pattern SONAME = Pattern.compile("libvlc\\.so(\\.\\d+)*");

    /** Keeps the preloaded libvlccore referenced, so it never unloads. */
    private static volatile NativeLibrary core;

    private final Os os;
    private final List<Path> candidates;
    private Path home;

    VlcRuntime(Os os, List<Path> candidates) {
        this.os = os;
        this.candidates = List.copyOf(candidates);
    }

    static VlcRuntime forThisMachine() {
        return new VlcRuntime(Os.current(), candidates(Os.current()));
    }

    /** The home that was found, or null before (or without) a discovery. */
    Path home() {
        return home;
    }

    /** The directory holding the libvlc library itself. */
    Path libDir(Path home) {
        return os == Os.MAC ? home.resolve("lib") : home;
    }

    /** True when {@code home} holds this OS's libvlc. */
    boolean holdsLibVlc(Path home) {
        Path dir = libDir(home);
        return switch (os) {
            case WINDOWS -> Files.isRegularFile(dir.resolve("libvlc.dll"));
            case MAC -> Files.isRegularFile(dir.resolve("libvlc.dylib"));
            case LINUX, OTHER -> {
                if (!Files.isDirectory(dir)) yield false;
                try (Stream<Path> files = Files.list(dir)) {
                    yield files.anyMatch(f ->
                            SONAME.matcher(f.getFileName().toString()).matches());
                } catch (Exception e) {
                    yield false;
                }
            }
        };
    }

    // ---- vlcj's discovery contract ----

    @Override public boolean supported() {
        return true;
    }

    /** The first candidate that holds a runtime; vlcj adds its lib dir to JNA's search path. */
    @Override public String discover() {
        for (Path candidate : candidates) {
            if (holdsLibVlc(candidate)) {
                home = candidate;
                return libDir(candidate).toString();
            }
        }
        return null;
    }

    @Override public boolean onFound(String libDir) {
        if (os == Os.MAC) {
            NativeLibrary.addSearchPath("vlccore", libDir);
            core = NativeLibrary.getInstance("vlccore");
        }
        return true;
    }

    @Override public boolean onSetPluginPath(String libDir) {
        if (os == Os.MAC && home != null) {
            LibC.INSTANCE.setenv("VLC_PLUGIN_PATH", home.resolve("plugins").toString(), 1);
        }
        return true;
    }

    // ---- where to look ----

    static List<Path> candidates(Os os) {
        List<Path> out = new ArrayList<>();
        String explicit = System.getProperty("dock.vlc");
        if (explicit != null && !explicit.isBlank()) out.add(Path.of(explicit));
        String env = System.getenv("DOCK_VLC");
        if (env != null && !env.isBlank()) out.add(Path.of(env));
        // The bundled copy: the working directory (Gradle run lands in
        // app/, the packaged app in its install dir) and its parents cover
        // every launch shape, and the classpath location covers IDE runs.
        int walked = 0;
        for (Path p = Path.of("").toAbsolutePath(); p != null && walked < 8; p = p.getParent()) {
            out.add(p.resolve("vlc"));
            walked++;
        }
        out.add(codeLocation().resolve("vlc"));
        switch (os) {
            case WINDOWS -> {
                String pf = System.getenv("ProgramFiles");
                if (pf != null) out.add(Path.of(pf, "VideoLAN", "VLC"));
                String pf86 = System.getenv("ProgramFiles(x86)");
                if (pf86 != null) out.add(Path.of(pf86, "VideoLAN", "VLC"));
            }
            case MAC -> {
                out.add(Path.of("/Applications/VLC.app/Contents/MacOS"));
                out.add(Path.of(System.getProperty("user.home"),
                        "Applications", "VLC.app", "Contents", "MacOS"));
            }
            case LINUX, OTHER -> {
                for (String dir : new String[] {"/usr/lib/x86_64-linux-gnu",
                        "/usr/lib/aarch64-linux-gnu", "/usr/lib64", "/usr/lib",
                        "/usr/local/lib"}) {
                    out.add(Path.of(dir));
                }
            }
        }
        return out;
    }

    private static Path codeLocation() {
        try {
            var loc = MethodHandles.lookup().lookupClass()
                    .getProtectionDomain().getCodeSource().getLocation();
            return Path.of(loc.toURI()).getParent();
        } catch (Exception e) {
            return Path.of(".");
        }
    }
}
