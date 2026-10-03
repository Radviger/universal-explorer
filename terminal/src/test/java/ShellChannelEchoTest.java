import dock.sftp.DemoServer;
import dock.sftp.SftpFs;
import dock.sftp.SshSessions;
import dock.terminal.ShellChannel;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shell channel's data flow: what goes in comes back out. Lives with
 * the app until the terminal frontend module lands (ShellChannel is its
 * class); the demo server and SFTP client come from the sftp backend.
 */
class ShellChannelEchoTest {

    @Test
    void shellChannelCarriesCommandOutput() throws Exception {
        try (DemoServer.Handle demo = DemoServer.start()) {
            SftpFs fs = SshSessions.connect("demo", "127.0.0.1", demo.port(),
                    "demo".toCharArray(), null, null, AcceptAllServerKeyVerifier.INSTANCE);
            try {
                ShellChannel shell = new ShellChannel(fs.session(), 80, 24);
                try {
                    shell.write("echo dock-shell-marker\r\n");
                    StringBuilder seen = new StringBuilder();
                    long deadline = System.currentTimeMillis() + 10_000;
                    char[] buf = new char[512];
                    while (System.currentTimeMillis() < deadline) {
                        if (shell.ready()) {
                            int n = shell.read(buf, 0, buf.length);
                            if (n > 0) {
                                seen.append(buf, 0, n);
                                if (seen.toString().contains("dock-shell-marker")) break;
                            }
                        } else {
                            Thread.sleep(50);
                        }
                    }
                    assertTrue(seen.toString().contains("dock-shell-marker"),
                            "shell output must echo the marker, got: " + seen);
                    assertTrue(shell.isConnected());
                } finally {
                    shell.close();
                }
            } finally {
                fs.close();
            }
        }
    }
}
