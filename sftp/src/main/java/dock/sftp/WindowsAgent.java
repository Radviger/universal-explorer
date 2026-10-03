package dock.sftp;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.apache.sshd.agent.SshAgent;
import org.apache.sshd.agent.SshAgentFactory;
import org.apache.sshd.agent.SshAgentServer;
import org.apache.sshd.agent.common.AbstractAgentProxy;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.common.session.ConnectionService;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.common.util.threads.CloseableExecutorService;
import org.apache.sshd.common.util.threads.ThreadUtils;

/**
 * Client-side bridge to the Windows OpenSSH authentication agent over its
 * named pipe (the thing {@code ssh-add} talks to; a YubiKey behind it looks
 * like any other agent key). MINA implements the agent wire protocol and
 * wires agent keys into public-key auth; this class supplies the missing
 * piece — the named-pipe transport — as an {@link SshAgentFactory}.
 */
public final class WindowsAgent {

    /** The pipe published by Windows' "OpenSSH Authentication Agent" service. */
    public static final String DEFAULT_PIPE = "\\\\.\\pipe\\openssh-ssh-agent";

    private static final int ERROR_FILE_NOT_FOUND = 2;
    private static final int ERROR_PIPE_BUSY = 231;

    private WindowsAgent() {}

    /**
     * Verifies the agent pipe is reachable, failing with actionable text
     * before any dialing starts (auth-time failures get wrapped into a
     * generic "authentication failed").
     */
    public static void probe(String pipe) throws IOException {
        WinNT.HANDLE h = open(pipe);
        Kernel32.INSTANCE.CloseHandle(h);
    }

    /** Opens the pipe; the message names the two realistic causes. */
    private static WinNT.HANDLE open(String pipe) throws IOException {
        WinNT.HANDLE h = Kernel32.INSTANCE.CreateFile(pipe,
                WinNT.GENERIC_READ | WinNT.GENERIC_WRITE, 0, null,
                WinNT.OPEN_EXISTING, 0, null);
        if (WinBase.INVALID_HANDLE_VALUE.equals(h)) {
            int rc = Kernel32.INSTANCE.GetLastError();
            String hint = rc == ERROR_FILE_NOT_FOUND
                    ? "the 'OpenSSH Authentication Agent' Windows service is not running"
                    : rc == ERROR_PIPE_BUSY
                    ? "the agent is busy — retry in a moment"
                    : "OS error " + rc + " opening " + pipe;
            throw new IOException("SSH agent unavailable: " + hint);
        }
        return h;
    }

    /**
     * One synchronous agent: every call writes a length-prefixed request
     * frame and reads one reply frame. The Windows agent pipe may be message
     * or byte mode on either side; looped reads behave correctly under both.
     */
    public static final class Proxy extends AbstractAgentProxy {
        private final WinNT.HANDLE pipe;
        private volatile boolean open = true;

        Proxy(WinNT.HANDLE pipe, CloseableExecutorService executor) {
            super(executor);
            this.pipe = pipe;
        }

        @Override public boolean isOpen() { return open; }

        @Override protected synchronized Buffer request(Buffer buffer) throws IOException {
            // prepare() already wrote the frame length into the first four
            // bytes; ship the buffer's remaining bytes as one write.
            byte[] out = new byte[buffer.available()];
            System.arraycopy(buffer.array(), buffer.rpos(), out, 0, out.length);
            writeFully(out);

            byte[] len = readFully(4);
            long n = (len[0] & 0xffL) << 24 | (len[1] & 0xffL) << 16
                    | (len[2] & 0xffL) << 8 | (len[3] & 0xffL);
            if (n < 1 || n > 10 * 1024 * 1024) {
                throw new IOException("Unreasonable agent reply length: " + n);
            }
            return new ByteArrayBuffer(readFully((int) n));
        }

        @Override public void close() throws IOException {
            open = false;
            try {
                Kernel32.INSTANCE.CloseHandle(pipe);
            } finally {
                super.close();
            }
        }

        private void writeFully(byte[] data) throws IOException {
            IntByReference written = new IntByReference();
            if (!Kernel32.INSTANCE.WriteFile(pipe, data, data.length, written, null)
                    || written.getValue() != data.length) {
                throw new IOException("Short write to SSH agent pipe ("
                        + written.getValue() + "/" + data.length + ")");
            }
        }

        private byte[] readFully(int n) throws IOException {
            byte[] out = new byte[n];
            byte[] chunk = new byte[Math.max(4096, n)];
            int done = 0;
            while (done < n) {
                IntByReference got = new IntByReference();
                int want = Math.min(chunk.length, n - done);
                // Kernel32.ReadFile has no buffer-offset argument; stage
                // through a chunk and copy (replies are small, so one go).
                if (!Kernel32.INSTANCE.ReadFile(pipe, chunk, want, got, null)
                        || got.getValue() <= 0) {
                    throw new IOException("SSH agent pipe closed mid-reply");
                }
                System.arraycopy(chunk, 0, out, done, got.getValue());
                done += got.getValue();
            }
            return out;
        }
    }

    /**
     * Plugs {@link Proxy} into MINA's public-key auth: with a factory set,
     * {@code UserAuthPublicKey} offers every key the agent lists and asks it
     * to sign each challenge — the private keys never leave the agent.
     */
    public static final class Factory implements SshAgentFactory {
        private final String pipe;

        public Factory() { this(DEFAULT_PIPE); }

        /** Test seam: any pipe speaking the agent protocol. */
        public Factory(String pipe) { this.pipe = pipe; }

        /** Reachability check with an actionable message (call before dialing). */
        public void probe() throws IOException {
            WindowsAgent.probe(pipe);
        }

        @Override public List<ChannelFactory> getChannelForwardingFactories(
                FactoryManager manager) {
            // Agent *forwarding* (channelling the remote side back here) is
            // deliberately not offered.
            return Collections.emptyList();
        }

        @Override public SshAgent createClient(Session session, FactoryManager manager)
                throws IOException {
            return new Proxy(open(pipe),
                    ThreadUtils.newSingleThreadExecutor("dock-agent"));
        }

        @Override public SshAgentServer createServer(ConnectionService service)
                throws IOException {
            throw new UnsupportedOperationException("agent forwarding is not supported");
        }
    }
}
