import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Session specs own their secrets. The connect flows wipe their password
 * arrays as soon as the dial returns, but the spec lives on for reconnects
 * and share enumeration — a shared array meant those later uses saw a
 * blanked-out secret, which is exactly why the SMB root listing failed
 * live while the freshly dialed connection kept working.
 */
class SmbSpecSecretsTest {

    @Test
    void smbSpecKeepsPasswordWhenCallerWipes() {
        char[] pw = "secret".toCharArray();
        var spec = new dock.smb.SmbSessions.SmbSpec(
                "nas", 445, null, "root", pw, false, null);
        java.util.Arrays.fill(pw, ' ');
        assertArrayEquals("secret".toCharArray(), spec.password(),
                "spec must own a private copy of the secret");
    }

    @Test
    void smbGuestSpecHasNoSecret() {
        char[] pw = "secret".toCharArray();
        var spec = new dock.smb.SmbSessions.SmbSpec(
                "nas", 445, null, "ignored", pw, true, null);
        assertNull(spec.user());
        assertNull(spec.password());
    }
}
