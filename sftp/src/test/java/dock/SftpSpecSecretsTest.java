package dock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * The SFTP spec owns its secrets: connect flows wipe their arrays as
 * soon as the dial returns, but the spec lives on for keepalive
 * reconnects — a shared array meant the reconnect saw a blanked-out
 * secret.
 */
class SftpSpecSecretsTest {

    @Test
    void sftpSpecKeepsSecretsWhenCallerWipes() {
        char[] pw = "secret".toCharArray();
        char[] pp = "phrase".toCharArray();
        var spec = new dock.sftp.SshSessions.ConnectionSpec(
                "root", "nas", 22, pw, null, pp, false);
        java.util.Arrays.fill(pw, ' ');
        java.util.Arrays.fill(pp, ' ');
        assertArrayEquals("secret".toCharArray(), spec.password());
        assertArrayEquals("phrase".toCharArray(), spec.passphrase());
    }
}
