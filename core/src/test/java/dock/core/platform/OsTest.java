package dock.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OsTest {

    @Test
    void windowsNamesClassifyAsWindows() {
        assertEquals(Os.WINDOWS, Os.of("Windows 11"));
        assertEquals(Os.WINDOWS, Os.of("Windows Server 2022"));
    }

    @Test
    void macNamesClassifyAsMac() {
        assertEquals(Os.MAC, Os.of("Mac OS X"));
        assertEquals(Os.MAC, Os.of("Darwin"));
    }

    @Test
    void linuxNamesClassifyAsLinux() {
        assertEquals(Os.LINUX, Os.of("Linux"));
    }

    @Test
    void everythingElseIsOther() {
        assertEquals(Os.OTHER, Os.of("FreeBSD"));
        assertEquals(Os.OTHER, Os.of(""));
    }
}
