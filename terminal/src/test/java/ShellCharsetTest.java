import dock.sftp.SftpFs;
import dock.sftp.SshSessions;
import dock.terminal.ShellChannel;
import dock.sftp.DemoServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shell wire-charset rules: remote hosts stay UTF-8, a loopback shell on
 * Windows speaks the console's OEM codepage (auto-detected via the system
 * API), and the local cmd banner must decode without question marks.
 */
class ShellCharsetTest {

    @Test
    void remoteHostsAlwaysUseUtf8() throws Exception {
        var remote = new InetSocketAddress(InetAddress.getByName("192.0.2.10"), 22);
        assertEquals(StandardCharsets.UTF_8, ShellChannel.shellCharset(remote));
    }

    @Test
    void loopbackOnWindowsUsesTheConsoleOemCodepage() throws Exception {
        var loopback = new InetSocketAddress(InetAddress.getLoopbackAddress(), 22);
        var cs = ShellChannel.shellCharset(loopback);
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            int cp = ShellChannel.oemCodePage();
            if (cp > 0) {
                assertEquals(java.nio.charset.Charset.forName("CP" + cp), cs,
                        "local shells decode with the OEM codepage the system reports");
            }
        } else {
            assertEquals(StandardCharsets.UTF_8, cs);
        }
    }

    /**
     * End-to-end: the demo server's cmd.exe banner contains the localized
     * copyright line. Under the old UTF-8-only decode it came back as runs of
     * question marks; with the OEM codepage it must decode as real text.
     */
    @Test
    void localShellBannerDecodesWithoutQuestionMarks() throws Exception {
        try (DemoServer.Handle demo = DemoServer.start()) {
            SftpFs fs = SshSessions.connect("demo", "127.0.0.1", demo.port(),
                    "demo".toCharArray(), null, null, AcceptAllServerKeyVerifier.INSTANCE);
            try {
                ShellChannel ch = new ShellChannel(fs.session(), 100, 30);
                try {
                    StringBuilder sb = new StringBuilder();
                    char[] buf = new char[1024];
                    long deadline = System.currentTimeMillis() + 10_000;
                    while (System.currentTimeMillis() < deadline
                            && !sb.toString().contains("Microsoft Corporation")) {
                        if (ch.ready()) {
                            int n = ch.read(buf, 0, buf.length);
                            if (n > 0) sb.append(buf, 0, n);
                        } else {
                            Thread.sleep(30);
                        }
                    }
                    String text = sb.toString();
                    assertTrue(text.contains("Microsoft Corporation"),
                            "banner was not read, got: " + text);
                    assertFalse(text.contains("\uFFFD"), "replacement chars leaked in");
                    assertFalse(text.matches("(?s).*\\?{4,}.*"),
                            "question-mark runs leaked in: " + text);
                    // On a Cyrillic console the copyright line is localized:
                    // prove the OEM decode produces real letters, not noise.
                    if (ShellChannel.oemCodePage() == 866) {
                        assertTrue(text.chars().anyMatch(c -> c >= 0x0410 && c <= 0x044F),
                                "expected Cyrillic letters in the banner: " + text);
                    }
                } finally {
                    ch.close();
                }
            } finally {
                fs.close();
            }
        }
    }
}
