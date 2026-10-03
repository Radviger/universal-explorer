import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Session specs own their secrets: the connect flow wipes its password
 * array as soon as the dial returns, but the spec lives on for
 * reconnects and per-transfer clients.
 */
class FtpSpecSecretsTest {

    @Test
    void ftpSpecKeepsPasswordWhenCallerWipes() {
        char[] pw = "secret".toCharArray();
        var spec = new dock.ftp.FtpSessions.FtpSpec(
                "nas", 0, true, "downloads/", "user", pw);
        java.util.Arrays.fill(pw, ' ');
        assertArrayEquals("secret".toCharArray(), spec.password());
        assertEquals(990, spec.port(), "TLS with port 0 takes the implicit FTPS default");
        assertEquals("/downloads", spec.basePath());
    }

    @Test
    void ftpAnonymousSpecDropsUserAndSecret() {
        char[] pw = "secret".toCharArray();
        var spec = new dock.ftp.FtpSessions.FtpSpec(
                "nas", 0, false, null, "  ", pw);
        assertNull(spec.user());
        assertNull(spec.password());
        assertEquals(21, spec.port(), "plain FTP defaults to 21");
        assertEquals("", spec.basePath());
    }
}
