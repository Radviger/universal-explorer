package dock.terminal;

import com.jediterm.terminal.TtyConnector;
import java.awt.Dimension;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * A terminal tab over a shell spawned on THIS machine (%ComSpec% on Windows,
 * $SHELL elsewhere), piped into JediTerm. Shares the console-codepage
 * detection with SSH shells on loopback, so a localized cmd banner decodes
 * correctly. There is no PTY — a piped shell wraps output at its default
 * width and full-screen TUIs are not supported.
 */
public final class LocalShell implements TtyConnector {

    private final Process process;
    private final InputStreamReader out;
    private final OutputStream in;
    private final Charset charset;
    private final String name;
    private final boolean windows;
    /** True right after a translated CR — lets a following LF be swallowed. */
    private boolean afterCr;

    private LocalShell(Process process, String name, boolean windows) {
        this.process = process;
        this.name = name;
        this.windows = windows;
        this.charset = ShellChannel.localConsoleCharset();
        this.out = new InputStreamReader(process.getInputStream(), charset);
        this.in = process.getOutputStream();
    }

    /** Spawns the machine's shell with merged stderr. */
    public static LocalShell spawn() throws IOException {
        boolean windows = com.sun.jna.Platform.isWindows();
        String shell = windows
                ? System.getenv().getOrDefault("ComSpec", "cmd.exe")
                : System.getenv().getOrDefault("SHELL", "/bin/sh");
        Process p = new ProcessBuilder(shell)
                .redirectErrorStream(true)
                .start();
        return new LocalShell(p, "local: " + shell, windows);
    }

    @Override
    public int read(char[] buf, int off, int len) throws IOException {
        return out.read(buf, off, len);
    }

    /**
     * Terminals send a bare CR on Enter and let the PTY's line discipline
     * sort it out; a piped shell has no such layer — cmd.exe silently
     * ignores lone CRs, so no input ever executed. Translate here: CR
     * becomes CRLF (LF on Unix), with an existing LF swallowed.
     */
    @Override
    public void write(byte[] bytes) throws IOException {
        for (byte b : bytes) {
            if (b == '\r') {
                in.write(windows ? new byte[]{'\r', '\n'} : new byte[]{'\n'});
                afterCr = true;
            } else if (b == '\n' && afterCr) {
                afterCr = false;
            } else {
                in.write(b);
                afterCr = false;
            }
        }
        in.flush();
    }

    @Override
    public void write(String string) throws IOException {
        write(string.getBytes(charset));
    }

    @Override
    public boolean isConnected() {
        return process.isAlive();
    }

    @Override
    public boolean ready() throws IOException {
        return out.ready();
    }

    @Override
    public int waitFor() throws InterruptedException {
        process.waitFor();
        return process.exitValue();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void resize(Dimension termSize) {
        // No PTY behind pipes; the shell keeps its default width.
    }

    @Override
    public void resize(Dimension termSize, Dimension pixelSize) {
        resize(termSize);
    }

    @Override
    public void close() {
        process.destroy();
    }
}
