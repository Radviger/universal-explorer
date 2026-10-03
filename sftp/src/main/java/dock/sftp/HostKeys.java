package dock.sftp;

import java.io.IOException;
import java.math.BigInteger;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECPoint;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.swing.SwingUtilities;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.common.util.security.SecurityUtils;

/**
 * Trust-on-first-use host key verification, compatible with OpenSSH's
 * ~/.ssh/known_hosts format. Unknown hosts prompt the user with the key
 * fingerprint and, on acceptance, are appended to the file; key mismatches
 * are rejected outright (a changed host key is a security event).
 */
public final class HostKeys {

    private static final Path FILE = Path.of(
            System.getProperty("user.home"), ".ssh", "known_hosts");

    private HostKeys() {}

    public static ServerKeyVerifier verifier() {
        return new TouVerifier();
    }

    private static final class TouVerifier implements ServerKeyVerifier {
        @Override
        public boolean verifyServerKey(ClientSession session, SocketAddress remote, PublicKey serverKey) {
            String pattern = pattern(remote);
            try {
                List<String> lines = Files.exists(FILE)
                        ? Files.readAllLines(FILE, StandardCharsets.UTF_8) : List.of();
                String algo = sshAlgoName(serverKey);
                byte[] wire = wireFormat(serverKey);
                for (String line : lines) {
                    String[] t = line.trim().split("\\s+");
                    if (t.length < 3 || !t[1].equals(algo)) continue;
                    byte[] stored = Base64.getDecoder().decode(t[2]);
                    if (Arrays.equals(stored, wire)
                            && Arrays.asList(t[0].split(",")).contains(pattern)) {
                        return true;
                    }
                }
                return unknown(pattern, remote, serverKey, algo, wire);
            } catch (Exception e) {
                return prompt(pattern, remote, serverKey,
                        "known_hosts could not be read (" + e.getMessage() + ")");
            }
        }
    }

    private static boolean unknown(String pattern, SocketAddress remote, PublicKey key,
                                   String algo, byte[] wire) throws IOException {
        if (!prompt(pattern, remote, key, "The host is not in known_hosts yet.")) return false;
        synchronized (HostKeys.class) {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, pattern + " " + algo + " "
                            + Base64.getEncoder().encodeToString(wire) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        return true;
    }

    private static boolean prompt(String pattern, SocketAddress remote, PublicKey key, String reason) {
        boolean[] ok = new boolean[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                String fp = org.apache.sshd.common.config.keys.KeyUtils.getFingerPrint(key);
                int ans = javax.swing.JOptionPane.showConfirmDialog(null,
                        """
                        %s

                        Host:  %s
                        Key:   %s

                        Trust this host and continue connecting?
                        Accepted keys are saved to ~/.ssh/known_hosts."""
                                .formatted(reason, pattern, fp),
                        "Unknown host key", javax.swing.JOptionPane.YES_NO_OPTION,
                        javax.swing.JOptionPane.QUESTION_MESSAGE);
                ok[0] = ans == javax.swing.JOptionPane.YES_OPTION;
            });
        } catch (Exception e) {
            return false;
        }
        return ok[0];
    }

    static String pattern(SocketAddress remote) {
        if (remote instanceof SshdSocketAddress a) {
            return a.getPort() == 22 ? a.getHostName()
                    : "[" + a.getHostName() + "]:" + a.getPort();
        }
        if (remote instanceof java.net.InetSocketAddress isa) {
            String host = isa.getHostString();
            return isa.getPort() == 22 ? host : "[" + host + "]:" + isa.getPort();
        }
        return remote.toString();
    }

    // --- key encoding to SSH wire format (what known_hosts stores) ---

    static String sshAlgoName(PublicKey key) {
        if (key instanceof RSAPublicKey) return "ssh-rsa";
        if (key instanceof ECPublicKey ec) {
            return "ecdsa-sha2-nistp" + ec.getParams().getCurve().getField().getFieldSize();
        }
        if (key instanceof EdECPublicKey) return "ssh-ed25519";
        throw new IllegalArgumentException("unsupported key type " + key.getAlgorithm());
    }

    static byte[] wireFormat(PublicKey key) throws IOException {
        if (key instanceof RSAPublicKey rsa) {
            return concat(string("ssh-rsa"), mpint(rsa.getPublicExponent()),
                    mpint(rsa.getModulus()));
        }
        if (key instanceof ECPublicKey ec) {
            int bits = ec.getParams().getCurve().getField().getFieldSize();
            int coordLen = (bits + 7) / 8;
            String curve = "nistp" + bits;
            ECPoint p = ec.getW();
            byte[] point = new byte[1 + 2 * coordLen];
            point[0] = 4;
            writeUnsigned(p.getAffineX(), point, 1, coordLen);
            writeUnsigned(p.getAffineY(), point, 1 + coordLen, coordLen);
            return concat(string("ecdsa-sha2-" + curve), string(curve), string(point));
        }
        if (key instanceof EdECPublicKey ed) {
            // Encoded as the y coordinate, little-endian, with the sign of x
            // in the topmost bit — always fits 32 bytes for Ed25519.
            byte[] le = twosComplementLittleEndian(ed.getPoint().getY(), 32);
            if (ed.getPoint().isXOdd()) le[31] |= (byte) 0x80;
            return concat(string("ssh-ed25519"), string(le));
        }
        throw new IOException("cannot encode key of type " + key.getAlgorithm());
    }

    private static void writeUnsigned(BigInteger v, byte[] into, int off, int len) {
        byte[] b = v.toByteArray(); // may carry a leading zero or be short
        int src = Math.max(0, b.length - len);
        int copy = b.length - src;
        System.arraycopy(b, src, into, off + (len - copy), copy);
    }

    private static byte[] twosComplementLittleEndian(BigInteger v, int len) {
        byte[] big = v.toByteArray();
        byte[] out = new byte[len];
        for (int i = 0; i < len && i < big.length; i++) {
            out[i] = big[big.length - 1 - i];
        }
        return out;
    }

    private static byte[] string(String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[4 + b.length];
        putU32(out, 0, b.length);
        System.arraycopy(b, 0, out, 4, b.length);
        return out;
    }

    private static byte[] string(byte[] b) {
        byte[] out = new byte[4 + b.length];
        putU32(out, 0, b.length);
        System.arraycopy(b, 0, out, 4, b.length);
        return out;
    }

    private static byte[] mpint(BigInteger v) {
        return string(v.toByteArray());
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    private static void putU32(byte[] into, int off, int v) {
        into[off] = (byte) (v >>> 24);
        into[off + 1] = (byte) (v >>> 16);
        into[off + 2] = (byte) (v >>> 8);
        into[off + 3] = (byte) v;
    }
}
