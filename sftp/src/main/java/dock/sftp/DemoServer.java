package dock.sftp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

/**
 * An in-process SFTP server for tests and the demo/screenshot mode.
 * Credentials: demo/demo, serving a seeded temp directory.
 */
public final class DemoServer {

    public record Handle(SshServer server, Path root, int port) implements AutoCloseable {
        @Override public void close() throws IOException {
            server.stop(true);
        }
    }

    public static Handle start() throws IOException {
        return start(0);
    }

    /** Fixed-port variant for reconnect tests. */
    public static Handle start(int port) throws IOException {
        return start(port, false, null);
    }

    /** Fixed-port variant that also accepts the given public key (agent-auth tests). */
    public static Handle start(int port, java.security.PublicKey allowedKey) throws IOException {
        return start(port, false, allowedKey);
    }

    /**
     * Fixed-port variant; with {@code silent} the server accepts every
     * global request (keepalives included) but never answers them — the
     * SSH-level picture of a connection whose return path is gone (NAT
     * idle drop, sleep/wake). Everything else behaves normally.
     */
    public static Handle start(int port, boolean silent) throws IOException {
        return start(port, silent, null);
    }

    private static Handle start(int port, boolean silent,
                                java.security.PublicKey allowedKey) throws IOException {
        Path root = Files.createTempDirectory("dock-demo");
        seed(root);

        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setPort(port);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        sshd.setPasswordAuthenticator((user, password, session) ->
                "demo".equals(user) && "demo".equals(new String(password)));
        if (allowedKey != null) {
            // Compare OpenSSH encodings, not key objects: client and server
            // decode the same key into different provider classes.
            String expected = org.apache.sshd.common.config.keys.PublicKeyEntry
                    .toString(allowedKey);
            sshd.setPublickeyAuthenticator((user, key, session) ->
                    expected.equals(org.apache.sshd.common.config.keys.PublicKeyEntry
                            .toString(key)));
        }
        // Interactive shell so terminals work in demo mode and tests. The
        // screenshot harness gets a shell that starts in its fictional
        // home — the cwd is set at process spawn, so there is no cd line
        // to quote — so no real path rides along in the captured prompt.
        String shellCwd = System.getProperty("dock.demo.shell.cwd");
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            sshd.setShellFactory(shellCwd == null
                    ? new org.apache.sshd.server.shell.ProcessShellFactory("cmd.exe", "/Q")
                    : new FictionalHomeShellFactory(Path.of(shellCwd)));
        } else {
            sshd.setShellFactory(new org.apache.sshd.server.shell.ProcessShellFactory());
        }
        // Chroot the SFTP subsystem to the seeded temp dir.
        sshd.setFileSystemFactory(
                new org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory(root));
        sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        if (silent) {
            sshd.setGlobalRequestHandlers(List.of(new org.apache.sshd.common.session.helpers
                    .AbstractConnectionServiceRequestHandler() {
                @Override public org.apache.sshd.common.channel.RequestHandler.Result process(
                        org.apache.sshd.common.session.ConnectionService service,
                        String request, boolean wantReply,
                        org.apache.sshd.common.util.buffer.Buffer buffer) {
                    // Claim the reply was sent — without sending anything.
                    return org.apache.sshd.common.channel.RequestHandler.Result.Replied;
                }
            }));
        }
        sshd.start();
        return new Handle(sshd, root, sshd.getPort());
    }

    /** A believable little tree so screenshots look like real usage. */
    /**
     * A shell that starts in a given directory — the harness's fictional
     * home. The cwd is set at process spawn (no cd line to quote through
     * argument mangling) and an opening dir gives the shot a lived-in
     * look; ProcessShellFactory's own command re-resolution is bypassed
     * entirely, the process is ours.
     */
    private static final class FictionalHomeShellFactory
            implements org.apache.sshd.server.shell.ShellFactory {
        private final Path cwd;

        FictionalHomeShellFactory(Path cwd) { this.cwd = cwd; }

        @Override
        public org.apache.sshd.server.command.Command createShell(
                org.apache.sshd.server.channel.ChannelSession channel) {
            return new org.apache.sshd.server.shell.InvertedShellWrapper(
                    new org.apache.sshd.server.shell.InvertedShell() {
                        private org.apache.sshd.server.session.ServerSession session;
                        private Process process;

                        @Override public void setSession(
                                org.apache.sshd.server.session.ServerSession s) { session = s; }

                        @Override public org.apache.sshd.server.session.ServerSession
                                getServerSession() { return session; }

                        @Override public org.apache.sshd.server.channel.ChannelSession
                                getServerChannelSession() { return null; }

                        @Override public void start(
                                org.apache.sshd.server.channel.ChannelSession channel,
                                org.apache.sshd.server.Environment env) throws IOException {
                            process = new ProcessBuilder("cmd.exe", "/Q", "/K", "dir")
                                    .directory(cwd.toFile()).start();
                        }

                        @Override public java.io.OutputStream getInputStream() {
                            return process.getOutputStream();
                        }

                        @Override public java.io.InputStream getOutputStream() {
                            return process.getInputStream();
                        }

                        @Override public java.io.InputStream getErrorStream() {
                            return process.getErrorStream();
                        }

                        @Override public boolean isAlive() { return process.isAlive(); }

                        @Override public int exitValue() { return process.exitValue(); }

                        @Override public void destroy(
                                org.apache.sshd.server.channel.ChannelSession channel) {
                            if (process != null) process.destroy();
                        }
                    });
        }
    }

    private static void seed(Path root) throws IOException {
        file(root, "readme.md", 1584, "2026-09-18T10:04:00Z",
                """
                # demo host

                This tree exists so screenshots and tests have something
                realistic to show.
                """);
        Files.writeString(root.resolve(".hidden-config"), "secret=1\n");

        dir(root, "Documents");
        file(root.resolve("Documents"), "quarterly-report.pdf", 2_411_003, "2026-09-02T09:00:00Z", "pdf");
        file(root.resolve("Documents"), "invoice-2026-08.txt", 4_812, "2026-08-30T16:41:00Z", "inv");
        file(root.resolve("Documents"), "notes.md", 931, "2026-09-21T22:17:00Z", "notes");

        dir(root, "Projects");
        dir(root.resolve("Projects"), "dock");
        dir(root.resolve("Projects"), "website");
        file(root.resolve("Projects/dock"), "build.gradle.kts", 2_108, "2026-09-24T23:55:00Z", "kts");
        file(root.resolve("Projects/dock"), "README.md", 3_322, "2026-09-23T18:02:00Z", "rm");
        file(root.resolve("Projects/website"), "index.html", 18_411, "2026-09-10T11:36:00Z", "html");

        dir(root, "backups");
        file(root.resolve("backups"), "srv-web-2026-09-20.tar.gz", 148_774_144, "2026-09-20T03:00:00Z", "gz");

        dir(root, "logs");
        for (int i = 20; i <= 24; i++) {
            file(root.resolve("logs"), "app-2026-09-%d.log".formatted(i), 122_880 + i * 137L,
                    "2026-09-%dT23:59:00Z".formatted(i), "log");
        }
    }

    private static void dir(Path parent, String name) throws IOException {
        Files.createDirectories(parent.resolve(name));
    }

    private static void file(Path parent, String name, long size, String mtime, String content)
            throws IOException {
        Path p = parent.resolve(name);
        StringBuilder sb = new StringBuilder();
        String chunk = content.repeat(Math.max(1, (int) Math.min(2048, Math.max(1, size / 64))));
        while (sb.length() < Math.min(size, 4096)) sb.append(chunk).append('\n');
        Files.writeString(p, sb.length() > 0 ? sb.toString() : content);
        Files.setLastModifiedTime(p,
                java.nio.file.attribute.FileTime.from(Instant.parse(mtime)));
    }
}
