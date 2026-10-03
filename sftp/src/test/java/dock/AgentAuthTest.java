package dock;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import org.apache.sshd.agent.SshAgent;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent auth end-to-end: a real named pipe (created by the test) speaking
 * the ssh-agent wire protocol stands in for the Windows OpenSSH agent and a
 * YubiKey — identities, signatures, and full connects that authenticate
 * with nothing but an agent-held key. Both key families a real YubiKey
 * offers (ed25519, RSA) are exercised.
 */
class AgentAuthTest {

    private static final int MSG_IDENTITIES = 11;
    private static final int MSG_IDENTITIES_ANSWER = 12;
    private static final int MSG_SIGN = 13;
    private static final int MSG_SIGN_RESPONSE = 14;
    private static final int MSG_FAILURE = 5;
    private static final int ERROR_PIPE_CONNECTED = 535;

    @Test
    void ed25519IdentitiesAndSignaturesFlowThroughThePipe() throws Exception {
        KeyPair key = i2pEd25519();
        try (FakeAgent agent = FakeAgent.start(key, key.getPublic())) {
            SshAgent proxy = new dock.sftp.WindowsAgent.Factory(agent.pipe())
                    .createClient(null, null);
            try {
                var it = proxy.getIdentities().iterator();
                assertTrue(it.hasNext(), "agent lists the YubiKey stand-in key");
                var id = it.next();
                assertEquals("dock-test-key", id.getValue(), "comment survives the wire");
                assertEquals(org.apache.sshd.common.config.keys.PublicKeyEntry
                                .toString(key.getPublic()),
                        org.apache.sshd.common.config.keys.PublicKeyEntry
                                .toString(id.getKey()),
                        "identity parses back to the offered key");
                assertFalse(it.hasNext(), "exactly one identity");

                byte[] data = "dock-agent-sign-me".getBytes();
                var sig = proxy.sign(null, id.getKey(), "ssh-ed25519", data);
                assertEquals("ssh-ed25519", sig.getKey(), "algorithm echoed");
                byte[] raw = sig.getValue();
                assertEquals(64, raw.length, "ed25519 raw signature");
                var verify = new net.i2p.crypto.eddsa.EdDSAEngine(
                        java.security.MessageDigest.getInstance("SHA-512"));
                verify.initVerify(key.getPublic());
                verify.update(data);
                assertTrue(verify.verify(raw), "signature verifies with the public key");
            } finally {
                proxy.close();
            }
        }
    }

    @Test
    void ed25519AgentAuthConnectsWithoutAnyLocalSecret() throws Exception {
        agentAuthConnects(i2pEd25519());
    }

    @Test
    void rsaAgentAuthConnectsHonouringTheRequestedHash() throws Exception {
        agentAuthConnects(rsa());
    }

    private static void agentAuthConnects(KeyPair key) throws Exception {
        try (FakeAgent agent = FakeAgent.start(key, key.getPublic());
             dock.sftp.DemoServer.Handle demo =
                     dock.sftp.DemoServer.start(0, key.getPublic())) {
            var spec = new dock.sftp.SshSessions.ConnectionSpec(
                    "demo", "127.0.0.1", demo.port(), null, null, null, true);
            var fs = dock.sftp.SshSessions.connect(
                    spec,
                    org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier.INSTANCE,
                    null, new dock.sftp.WindowsAgent.Factory(agent.pipe()));
            try {
                assertTrue(fs.exists("/readme.md"),
                        "authenticated session must browse the server");
            } finally {
                fs.close();
            }
        }
    }

    @Test
    void missingPipeFailsWithServiceHint() {
        var factory = new dock.sftp.WindowsAgent.Factory(
                "\\\\.\\pipe\\dock-no-such-agent-" + System.nanoTime());
        try {
            factory.probe();
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("not running"),
                    "message names the service: " + e.getMessage());
            return;
        }
        throw new AssertionError("probe must fail when the pipe is absent");
    }

    @Test
    void agentSitesPersistAndLegacyFilesLoadAsPassword() throws Exception {
        var dir = java.nio.file.Files.createTempDirectory("dock-sites");
        var previous = dock.core.config.AppPaths.config();
        dock.core.config.AppPaths.override(dir);
        try {
            // sessions.json written before the agent existed: no useAgent key.
            java.nio.file.Files.writeString(dir.resolve("sessions.json"),
                    "[{\"name\":\"legacy\",\"host\":\"h\",\"port\":22,\"user\":\"u\","
                            + "\"keyPath\":null,\"lastUsed\":5}]");
            var legacy = dock.core.config.Sites.load().get(0);
            assertFalse(legacy.useAgent(), "legacy files default to non-agent");

            dock.core.config.Sites.upsert(new dock.core.config.Site(
                    "yubi", "h", 22, "u", null, true, 0));
            var saved = dock.core.config.Sites.load().stream()
                    .filter(s -> s.name().equals("yubi")).findFirst().orElseThrow();
            assertTrue(saved.useAgent(), "agent mode round-trips");
            assertTrue(saved.keyPath() == null, "agent owns the auth; no key path");
        } finally {
            dock.core.config.AppPaths.override(previous);
        }
    }

    /**
     * An ed25519 keypair in net.i2p classes — the exact shape a real agent
     * hands to MINA. Built from a random seed so no JCA provider selection
     * is involved: after MINA registers the i2p provider, JDK "Ed25519"
     * lookups return i2p engines that reject JDK key objects (and vice
     * versa), and which one wins depends on registration order.
     */
    private static KeyPair i2pEd25519() {
        var ed = net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable.getByName(
                net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable.ED_25519);
        byte[] seed = new byte[32];
        new java.security.SecureRandom().nextBytes(seed);
        var priv = new net.i2p.crypto.eddsa.EdDSAPrivateKey(
                new net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec(seed, ed));
        var pub = new net.i2p.crypto.eddsa.EdDSAPublicKey(
                new net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec(priv.getA(), ed));
        return new KeyPair(pub, priv);
    }

    private static KeyPair rsa() throws Exception {
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    // ---- a stand-in Windows OpenSSH agent on a named pipe ----

    /** Kernel32 with the named-pipe server entry points jna-platform omits. */
    private interface PipeApi extends Kernel32, StdCallLibrary {
        PipeApi INSTANCE = Native.load("kernel32", PipeApi.class, W32APIOptions.DEFAULT_OPTIONS);
        WinNT.HANDLE CreateNamedPipeW(com.sun.jna.WString name, int openMode, int pipeMode,
                                      int maxInstances, int outBuf, int inBuf,
                                      int defaultTimeout, WinBase.SECURITY_ATTRIBUTES attrs);
        boolean ConnectNamedPipe(WinNT.HANDLE handle, WinBase.OVERLAPPED overlapped);
        boolean DisconnectNamedPipe(WinNT.HANDLE handle);
    }

    /**
     * One key served over the agent protocol: lists it and signs whatever is
     * asked. The first pipe instance is created synchronously by start() so
     * clients can connect the instant start() returns.
     */
    private static final class FakeAgent implements AutoCloseable {
        private final String pipe;
        private final KeyPair key;
        private final java.security.PublicKey offered;
        private final Thread acceptor;
        private volatile boolean closed;
        private volatile WinNT.HANDLE pending;

        private FakeAgent(String pipe, KeyPair key, java.security.PublicKey offered) {
            this.pipe = pipe;
            this.key = key;
            this.offered = offered;
            this.acceptor = Thread.ofVirtual().name("dock-fake-agent").unstarted(this::acceptLoop);
        }

        static FakeAgent start(KeyPair key, java.security.PublicKey offered) throws IOException {
            String pipe = "\\\\.\\pipe\\dock-agent-test-"
                    + Long.toHexString(System.nanoTime());
            FakeAgent agent = new FakeAgent(pipe, key, offered);
            agent.pending = agent.createInstance();
            if (WinBase.INVALID_HANDLE_VALUE.equals(agent.pending)) {
                throw new IOException("CreateNamedPipeW failed: "
                        + Kernel32.INSTANCE.GetLastError());
            }
            agent.acceptor.start();
            return agent;
        }

        String pipe() { return pipe; }

        /** PIPE_ACCESS_DUPLEX | byte mode | wait | unlimited instances. */
        private WinNT.HANDLE createInstance() {
            return PipeApi.INSTANCE.CreateNamedPipeW(
                    new com.sun.jna.WString(pipe), 3, 0, 255, 8192, 8192, 0, null);
        }

        private void acceptLoop() {
            while (!closed) {
                WinNT.HANDLE h = pending;
                pending = null;
                if (h == null) h = createInstance();
                if (WinBase.INVALID_HANDLE_VALUE.equals(h)) return;
                boolean connected = PipeApi.INSTANCE.ConnectNamedPipe(h, null);
                if (!connected && PipeApi.INSTANCE.GetLastError() != ERROR_PIPE_CONNECTED) {
                    Kernel32.INSTANCE.CloseHandle(h);
                    return;
                }
                if (closed) {
                    Kernel32.INSTANCE.CloseHandle(h);
                    return;
                }
                serve(h);
            }
        }

        private void serve(WinNT.HANDLE h) {
            try {
                while (true) {
                    int len = (int) readUInt(h);
                    if (len <= 0 || len > 1_000_000) break;
                    var in = new ByteArrayBuffer(readFully(h, len));
                    var out = new ByteArrayBuffer();
                    switch (in.getUByte()) {
                        case MSG_IDENTITIES -> {
                            // MINA's agent protocol writes keys raw (type
                            // string + fields), not as a blob string.
                            out.putByte((byte) MSG_IDENTITIES_ANSWER);
                            out.putInt(1);
                            out.putPublicKey(offered);
                            out.putString("dock-test-key");
                        }
                        case MSG_SIGN -> {
                            in.getPublicKey(); // the key: we hold exactly one
                            byte[] data = in.getBytes();
                            int flags = (int) in.getInt();
                            Signed signed = sign(data, flags);
                            var inner = new ByteArrayBuffer();
                            inner.putString(signed.algorithm());
                            inner.putInt(signed.signature().length);
                            inner.putRawBytes(signed.signature());
                            out.putByte((byte) MSG_SIGN_RESPONSE);
                            // putBytes writes a u32-prefixed string; the
                            // payload must go out raw (putInt already wrote
                            // the string prefix).
                            out.putInt(inner.getCompactData().length);
                            out.putRawBytes(inner.getCompactData());
                        }
                        default -> out.putByte((byte) MSG_FAILURE);
                    }
                    writeFrame(h, out.getCompactData());
                }
            } catch (Exception e) {
                // Client hung up; the handle is cleaned up below. Unexpected
                // protocol errors surface on the client side anyway (the
                // reply never arrives), so the test still fails loudly.
                if (System.getenv("DOCK_AGENT_DEBUG") != null) e.printStackTrace();
            } finally {
                PipeApi.INSTANCE.DisconnectNamedPipe(h);
                Kernel32.INSTANCE.CloseHandle(h);
            }
        }

        /** Signed({@code algorithm, raw signature}) honouring the agent flags MINA sets. */
        private record Signed(String algorithm, byte[] signature) {}

        private Signed sign(byte[] data, int flags) throws Exception {
            if (key.getPublic() instanceof net.i2p.crypto.eddsa.EdDSAPublicKey) {
                var s = new net.i2p.crypto.eddsa.EdDSAEngine(
                        java.security.MessageDigest.getInstance("SHA-512"));
                s.initSign(key.getPrivate());
                s.update(data);
                return new Signed("ssh-ed25519", s.sign());
            }
            String alg;
            String jca;
            if (flags == 2) { // SSH_AGENT_RSA_SHA2_256
                alg = "rsa-sha2-256";
                jca = "SHA256withRSA";
            } else if (flags == 4) { // SSH_AGENT_RSA_SHA2_512
                alg = "rsa-sha2-512";
                jca = "SHA512withRSA";
            } else {
                alg = "ssh-rsa";
                jca = "SHA1withRSA";
            }
            var s = Signature.getInstance(jca);
            s.initSign(key.getPrivate());
            s.update(data);
            return new Signed(alg, s.sign());
        }

        @Override public void close() {
            closed = true;
            // Unblock the acceptor if it is parked in ConnectNamedPipe.
            WinNT.HANDLE nudge = Kernel32.INSTANCE.CreateFile(pipe,
                    WinNT.GENERIC_READ | WinNT.GENERIC_WRITE, 0, null,
                    WinNT.OPEN_EXISTING, 0, null);
            if (!WinBase.INVALID_HANDLE_VALUE.equals(nudge)) {
                Kernel32.INSTANCE.CloseHandle(nudge);
            }
            try {
                acceptor.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // ---- pipe framing (identical shape to the client side) ----

        private static void writeFrame(WinNT.HANDLE h, byte[] payload) throws IOException {
            var out = new ByteArrayOutputStream(payload.length + 4);
            out.write(payload.length >>> 24);
            out.write(payload.length >>> 16);
            out.write(payload.length >>> 8);
            out.write(payload.length);
            out.writeBytes(payload);
            byte[] frame = out.toByteArray();
            IntByReference written = new IntByReference();
            if (!Kernel32.INSTANCE.WriteFile(h, frame, frame.length, written, null)) {
                throw new IOException("fake agent write failed");
            }
        }

        private static long readUInt(WinNT.HANDLE h) throws IOException {
            byte[] b = readFully(h, 4);
            return (b[0] & 0xffL) << 24 | (b[1] & 0xffL) << 16
                    | (b[2] & 0xffL) << 8 | (b[3] & 0xffL);
        }

        private static byte[] readFully(WinNT.HANDLE h, int n) throws IOException {
            byte[] out = new byte[n];
            byte[] chunk = new byte[Math.max(4096, n)];
            int done = 0;
            IntByReference got = new IntByReference();
            while (done < n) {
                int want = Math.min(chunk.length, n - done);
                if (!Kernel32.INSTANCE.ReadFile(h, chunk, want, got, null)
                        || got.getValue() <= 0) {
                    throw new IOException("fake agent pipe closed");
                }
                System.arraycopy(chunk, 0, out, done, got.getValue());
                done += got.getValue();
            }
            return out;
        }
    }
}
