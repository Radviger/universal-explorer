package dock.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dock.core.platform.Os;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Each OS finds the runtime in the layout of its official VLC build. */
class VlcRuntimeTest {

    @TempDir Path tmp;

    private Path touch(String relative) throws Exception {
        Path f = tmp.resolve(relative);
        Files.createDirectories(f.getParent());
        return Files.createFile(f);
    }

    @Test
    void windowsRuntimeSitsBesideTheDll() throws Exception {
        touch("vlc/libvlc.dll");
        var rt = new VlcRuntime(Os.WINDOWS, List.of(tmp.resolve("vlc")));
        assertEquals(tmp.resolve("vlc").toString(), rt.discover());
        assertEquals(tmp.resolve("vlc"), rt.home());
    }

    @Test
    void macRuntimeKeepsItsLibrariesUnderLib() throws Exception {
        touch("vlc/lib/libvlc.dylib");
        var rt = new VlcRuntime(Os.MAC, List.of(tmp.resolve("vlc")));
        assertEquals(tmp.resolve("vlc/lib").toString(), rt.discover(),
                "JNA searches the lib dir, the home stays the VLC.app MacOS level");
        assertEquals(tmp.resolve("vlc"), rt.home());
    }

    @Test
    void macIgnoresADylibLeftAtTheHomeRoot() throws Exception {
        touch("vlc/libvlc.dylib");
        assertFalse(new VlcRuntime(Os.MAC, List.of()).holdsLibVlc(tmp.resolve("vlc")));
    }

    @Test
    void linuxAcceptsADistroVersionedSoname() throws Exception {
        touch("usr-lib/libvlc.so.5");
        var rt = new VlcRuntime(Os.LINUX, List.of(tmp.resolve("usr-lib")));
        assertEquals(tmp.resolve("usr-lib").toString(), rt.discover());
    }

    @Test
    void linuxDoesNotMistakeLibvlccoreForLibvlc() throws Exception {
        touch("usr-lib/libvlccore.so.9");
        assertFalse(new VlcRuntime(Os.LINUX, List.of()).holdsLibVlc(tmp.resolve("usr-lib")));
    }

    @Test
    void theFirstCandidateHoldingARuntimeWins() throws Exception {
        touch("bundled/lib/libvlc.dylib");
        touch("system/lib/libvlc.dylib");
        var rt = new VlcRuntime(Os.MAC, List.of(tmp.resolve("missing"),
                tmp.resolve("bundled"), tmp.resolve("system")));
        rt.discover();
        assertEquals(tmp.resolve("bundled"), rt.home());
    }

    @Test
    void noRuntimeAnywhereDiscoversNothing() {
        var rt = new VlcRuntime(Os.WINDOWS, List.of(tmp.resolve("nothing-here")));
        assertNull(rt.discover());
        assertNull(rt.home());
    }

    @Test
    void macLooksInTheInstalledVlcAppAfterTheBundle() {
        List<Path> c = VlcRuntime.candidates(Os.MAC);
        assertTrue(c.contains(Path.of("/Applications/VLC.app/Contents/MacOS")));
        assertTrue(c.indexOf(Path.of("/Applications/VLC.app/Contents/MacOS"))
                > c.indexOf(Path.of("").toAbsolutePath().resolve("vlc")));
    }
}
