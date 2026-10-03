package dock.terminal;

import com.jediterm.terminal.TtyConnector;
import java.awt.Dimension;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;

/**
 * Bridges a MINA shell channel into JediTerm's TtyConnector. One terminal
 * tab = one shell channel on the session's connection.
 */
public final class ShellChannel implements TtyConnector {

    /** kernel32 piece jna-platform doesn't map. */
    private interface RawKernel32 extends com.sun.jna.win32.StdCallLibrary {
        RawKernel32 INSTANCE = com.sun.jna.Native.load("kernel32", RawKernel32.class,
                com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS);
        int GetOEMCP();
    }

    private final ChannelShell shell;
    private final OutputStream in;          // remote stdin
    private final InputStreamReader out;   // remote stdout
    private final Charset charset;
    private final String name;

    public ShellChannel(ClientSession session, int cols, int rows) throws IOException {
        this.name = "ssh: " + session.getUsername() + "@" + session.getConnectAddress();
        this.shell = session.createShellChannel();
        shell.setupSensibleDefaultPty();
        shell.setPtyType("xterm-256color");
        shell.setPtyColumns(cols);
        shell.setPtyLines(rows);
        shell.open().verify(Duration.ofSeconds(10));
        this.charset = shellCharset(session.getConnectAddress());
        this.in = shell.getInvertedIn();
        this.out = new InputStreamReader(shell.getInvertedOut(), charset);
    }

    /**
     * Decides the wire charset for a shell. Remote hosts send nothing to
     * negotiate — UTF-8 is the norm there. A shell on this Windows machine
     * (loopback, demo) speaks its console's OEM codepage (CP866 for Cyrillic
     * locales), which the system API reports; decoding it as UTF-8 turns
     * every localized character into a question mark.
     */
    public static Charset shellCharset(java.net.SocketAddress target) {
        boolean loopback = !(target instanceof java.net.InetSocketAddress isa)
                || isa.getAddress() == null
                || isa.getAddress().isLoopbackAddress()
                || isa.getAddress().isAnyLocalAddress();
        return loopback ? localConsoleCharset() : StandardCharsets.UTF_8;
    }

    /**
     * Wire charset for a shell running on THIS machine: the console's OEM
     * codepage on Windows (via the system API), UTF-8 elsewhere.
     */
    public static Charset localConsoleCharset() {
        if (!com.sun.jna.Platform.isWindows()) return StandardCharsets.UTF_8;
        try {
            int cp = RawKernel32.INSTANCE.GetOEMCP();
            if (cp > 0) return Charset.forName("CP" + cp);
        } catch (Exception ignored) {
            // Unknown codepage number for this JVM: fall through to UTF-8.
        }
        return StandardCharsets.UTF_8;
    }

    /** The local console OEM codepage, or 0 (tests / locale-adaptive checks). */
    public static int oemCodePage() {
        try {
            return RawKernel32.INSTANCE.GetOEMCP();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public int read(char[] buf, int off, int len) throws IOException {
        return out.read(buf, off, len);
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        in.write(bytes);
        in.flush();
    }

    @Override
    public void write(String string) throws IOException {
        write(string.getBytes(charset));
    }

    @Override
    public boolean isConnected() {
        return shell.isOpen();
    }

    @Override
    public boolean ready() throws IOException {
        return out.ready();
    }

    @Override
    public int waitFor() throws InterruptedException {
        shell.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), Duration.ofSeconds(5));
        Integer status = shell.getExitStatus();
        return status == null ? 0 : status;
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * PTY window change via the proper SSH window-change request. (The old
     * fallback wrote a literal "stty rows R cols C" into the shell's stdin —
     * the shell echoed it into the terminal and ran it, so every fresh
     * terminal showed the resize as visible text.)
     */
    @Override
    public void resize(Dimension termSize) {
        try {
            shell.sendWindowChange(termSize.width, termSize.height);
        } catch (IOException ignored) {
        }
    }

    @Override
    public void resize(Dimension termSize, Dimension pixelSize) {
        resize(termSize);
    }

    @Override
    public void close() {
        shell.close(true);
    }
}
