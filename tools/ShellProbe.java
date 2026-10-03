import dock.sftp.SftpFs;
import dock.sftp.SshSessions;
import dock.sftp.DemoServer;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;

public class ShellProbe {
    public static void main(String[] args) throws Exception {
        try (DemoServer.Handle demo = DemoServer.start()) {
            SftpFs fs = SshSessions.connect("demo", "127.0.0.1", demo.port(),
                    "demo".toCharArray(), null, null, AcceptAllServerKeyVerifier.INSTANCE);
            var shell = fs.session().createShellChannel();
            shell.setupSensibleDefaultPty();
            shell.setPtyType("xterm-256color");
            shell.setPtyColumns(80);
            shell.setPtyLines(24);
            shell.open().verify(java.time.Duration.ofSeconds(10));
            Thread.sleep(500);
            System.out.println("open=" + shell.isOpen());
            shell.getInvertedIn().write("echo dock-marker\r\n".getBytes());
            shell.getInvertedIn().flush();
            var out = shell.getInvertedOut();
            var err = shell.getInvertedErr();
            StringBuilder sb = new StringBuilder();
            long deadline = System.currentTimeMillis() + 8000;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < deadline) {
                int n = out.available() > 0 ? out.read(buf) : -0;
                if (n > 0) sb.append("[out] ").append(new String(buf, 0, n)).append('\n');
                int m = err.available() > 0 ? err.read(buf) : 0;
                if (m > 0) sb.append("[err] ").append(new String(buf, 0, m)).append('\n');
                if (sb.toString().contains("dock-marker")) break;
                Thread.sleep(100);
            }
            System.out.println("captured:\n" + sb);
            shell.close(true);
            fs.close();
        }
    }
}
