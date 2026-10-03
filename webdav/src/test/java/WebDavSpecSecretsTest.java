import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Session specs own their secrets: the connect flow wipes its password
 * array as soon as the dial returns, but the spec lives on for
 * reconnects and per-transfer clients.
 */
class WebDavSpecSecretsTest {

    @Test
    void webDavSpecKeepsPasswordWhenCallerWipes() {
        char[] pw = "secret".toCharArray();
        var spec = new dock.webdav.WebDavSessions.WebDavSpec(
                "nas", 0, true, "dav/", "user", pw);
        java.util.Arrays.fill(pw, ' ');
        assertArrayEquals("secret".toCharArray(), spec.password());
        assertEquals(443, spec.port(), "port 0 takes the scheme default");
        assertEquals("/dav", spec.basePath(), "base path normalizes to lead-without-trail");
    }

    @Test
    void webDavAnonymousSpecDropsUserAndSecret() {
        char[] pw = "secret".toCharArray();
        var spec = new dock.webdav.WebDavSessions.WebDavSpec(
                "nas", 0, false, null, "  ", pw);
        assertNull(spec.user(), "a blank user means anonymous");
        assertNull(spec.password());
        assertEquals(80, spec.port(), "plain http defaults to 80");
        assertEquals("", spec.basePath());
    }
}
