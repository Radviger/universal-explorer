package dock.smb;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ImpersonationLevel;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.PipeShare;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Share enumeration spoken directly over the server's srvsvc named pipe
 * (DCE/RPC NetrShareEnum, level 1), riding the already-authenticated smbj
 * session. This is Explorer's own data source, minus the Windows
 * redirector: no netapi32, no second authentication, no dependence on
 * machine-global network state that a stuck session can wedge until
 * reboot — and it works on any OS.
 *
 * The wire formats are hand-rolled NDR: a bind to the SRVSVC interface
 * followed by one request. Both are small, fixed-shape PDUs; the parser
 * below is the only stateful part and is pure (unit-tested against
 * synthetic responses).
 */
final class Srvsvc {

    /** SRVSVC: 4B324FC8-1670-01D3-1278-5A47BF6EE188, version 3.0. */
    private static final byte[] SRVSVC_UUID = {
            (byte) 0xC8, 0x4F, 0x32, 0x4B, 0x70, 0x16, (byte) 0xD3, 0x01,
            0x12, 0x78, 0x5A, 0x47, (byte) 0xBF, 0x6E, (byte) 0xE1, (byte) 0x88};
    /** NDR transfer syntax: 8A885D04-1CEB-11C9-9FE8-08002B104860, v2. */
    private static final byte[] NDR_UUID = {
            0x04, 0x5D, (byte) 0x88, (byte) 0x8A, (byte) 0xEB, 0x1C, (byte) 0xC9, 0x11,
            (byte) 0x9F, (byte) 0xE8, 0x08, 0x00, 0x2B, 0x10, 0x48, 0x60};

    private static final int PTYPE_BIND = 11;
    private static final int PTYPE_BIND_ACK = 12;
    private static final int PTYPE_REQUEST = 0;
    private static final int PTYPE_RESPONSE = 2;
    private static final int PFC_FIRST = 0x01;
    private static final int PFC_LAST = 0x02;
    private static final int OPNUM_NETR_SHARE_ENUM = 15;

    private Srvsvc() {}

    /** Lists the server's shares over its srvsvc pipe; read-only by nature. */
    static List<ShareEnumerator.Share> list(Session session) throws IOException {
        return list(session, null);
    }

    /**
     * Full form: {@code wire}, when non-null, receives every response PDU
     * raw (manual diagnostics only — never wired in production paths).
     */
    static List<ShareEnumerator.Share> list(Session session,
            java.util.function.Consumer<byte[]> wire) throws IOException {
        try {
            return listOverPipe(session, wire);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            // smbj reports refusals and transport faults unchecked.
            throw new IOException("Share listing over srvsvc failed: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()), e);
        }
    }

    private static List<ShareEnumerator.Share> listOverPipe(Session session,
            java.util.function.Consumer<byte[]> wire) throws IOException {
        if (!(session.connectShare("IPC$") instanceof PipeShare pipe)) {
            throw new IOException("The server did not expose an IPC$ pipe share.");
        }
        try (var srvsvc = pipe.open("srvsvc",
                SMB2ImpersonationLevel.Impersonation,
                EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.noneOf(SMB2CreateOptions.class))) {
            byte[] ack = exchange(srvsvc, bindPdu(1));
            if (wire != null) wire.accept(ack);
            checkBindAck(ack);
            byte[] resp = exchange(srvsvc, requestPdu(2, requestStub()));
            if (wire != null) wire.accept(resp);
            return parseResponse(resp);
        } finally {
            try {
                pipe.close();
            } catch (Exception ignored) {
                // Best-effort tree disconnect.
            }
        }
    }

    // ---- wire format ----

    /** 72-byte bind PDU: one context item, SRVSVC 3.0 over NDR 2.0. */
    static byte[] bindPdu(int callId) {
        var out = new ByteArrayOutputStream();
        header(out, PTYPE_BIND, 72, 0, callId);
        le16(out, 1200);                  // max_xmit_frag
        le16(out, 1200);                  // max_recv_frag
        le32(out, 0);                     // assoc_group
        out.write(1);                     // num_ctx_items
        out.write(0); out.write(0); out.write(0);
        le16(out, 0);                     // context_id
        le16(out, 1);                     // num transfer syntaxes
        out.writeBytes(SRVSVC_UUID);
        le32(out, 3);                     // abstract version 3.0
        out.writeBytes(NDR_UUID);
        le32(out, 2);                     // transfer version 2.0
        return out.toByteArray();
    }

    /** Request PDU wrapping the NetrShareEnum stub. */
    static byte[] requestPdu(int callId, byte[] stub) {
        var out = new ByteArrayOutputStream();
        header(out, PTYPE_REQUEST, 16 + 8 + stub.length, 0, callId);
        le32(out, stub.length);           // alloc_hint
        le16(out, 0);                     // context_id
        le16(out, OPNUM_NETR_SHARE_ENUM);
        out.writeBytes(stub);
        return out.toByteArray();
    }

    /**
     * NetrShareEnum stub: null server name, level 1, null container (the
     * server allocates), PrefMaxLen -1, a zeroed resume handle. 28 bytes.
     */
    static byte[] requestStub() {
        var b = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0);          // servername: NULL unique pointer
        b.putInt(1);          // level
        b.putInt(1);          // union switch: level
        b.putInt(0);          // container: NULL unique pointer
        b.putInt(-1);         // PrefMaxLen
        b.putInt(0x00020000); // resume handle pointer (non-null)
        b.putInt(0);          // resume handle value
        return b.array();
    }

    private static void header(ByteArrayOutputStream out, int ptype,
                               int fragLength, int authLength, int callId) {
        out.write(5);                       // rpc version
        out.write(0);                       // minor
        out.write(ptype);
        out.write(PFC_FIRST | PFC_LAST);
        out.write(0x10);                    // drep: little-endian, ASCII, IEEE
        out.write(0); out.write(0); out.write(0);
        le16(out, fragLength);
        le16(out, authLength);
        le32(out, callId);
    }

    // ---- response parsing ----

    /**
     * Parses a RESPONSE PDU's stub into share rows. Layout verified
     * against a live Samba server (pidl's inline pointer style): union
     * pointer, switch, container count, array pointer, max count, then
     * {@code {netname id, type, remark id}} per element, then all strings
     * (netname and remark per element, in order), total entries, resume
     * handle, win32 status. Sanity anchors make a desync fail loudly
     * instead of returning garbage.
     */
    static List<ShareEnumerator.Share> parseResponse(byte[] pdu) throws IOException {
        ByteBuffer head = ByteBuffer.wrap(pdu).order(ByteOrder.LITTLE_ENDIAN);
        if (pdu.length < 16 || head.get(0) != 5) {
            throw new IOException("Malformed RPC response.");
        }
        int ptype = head.get(2) & 0xFF;
        if (ptype != PTYPE_RESPONSE) {
            throw new IOException("Unexpected RPC response type " + ptype + ".");
        }
        int flags = head.get(3) & 0xFF;
        if ((flags & PFC_FIRST) == 0) {
            throw new IOException("Fragmented RPC responses are not supported.");
        }
        ByteBuffer b = ByteBuffer.wrap(pdu).order(ByteOrder.LITTLE_ENDIAN);
        b.position(24); // header + alloc_hint + context_id + opnum

        if (b.getInt() == 0) {
            throw statusFromTail(b);
        }
        int level = b.getInt();
        if (level != 1) {
            throw new IOException("The server listed shares at level " + level
                    + "; only level 1 is supported.");
        }
        List<ShareEnumerator.Share> shares = new ArrayList<>();
        if (b.getInt() != 0) {                    // container pointer
            int count = b.getInt();
            boolean hasArray = b.getInt() != 0;   // array pointer
            int max = b.getInt();
            if (count < 0 || max < 0 || count > max || max > 100_000) {
                throw new IOException("Implausible share array bounds in RPC response.");
            }
            int[] netIds = new int[count];
            int[] remarkIds = new int[count];
            int[] types = new int[count];
            for (int i = 0; i < count; i++) {
                netIds[i] = b.getInt();
                types[i] = b.getInt();
                remarkIds[i] = b.getInt();
            }
            if (hasArray) {
                for (int i = 0; i < count; i++) {
                    String name = netIds[i] != 0 ? ndrString(b) : "";
                    String remark = remarkIds[i] != 0 ? ndrString(b) : "";
                    shares.add(new ShareEnumerator.Share(
                            name.isEmpty() ? null : name, types[i],
                            remark.isEmpty() ? null : remark));
                }
            }
        }
        b.getInt();                               // total entries (already counted)
        if (b.getInt() != 0) b.getInt();          // resume handle pointer + value
        int status = b.getInt();
        if (status != 0) {
            throw new IOException(winError(status));
        }
        return shares;
    }

    /** Reads one NDR conformant string (UTF-16LE, count includes the NUL). */
    private static String ndrString(ByteBuffer b) throws IOException {
        b.getInt();                        // max count
        b.getInt();                        // offset
        int count = b.getInt();
        if (count < 0 || count > 100_000) {
            throw new IOException("Implausible string length in RPC response.");
        }
        byte[] raw = new byte[count * 2];
        b.get(raw);
        pad4(b);
        String s = new String(raw, 0, raw.length, StandardCharsets.UTF_16LE);
        int nul = s.indexOf('\0');
        return nul >= 0 ? s.substring(0, nul) : s;
    }

    private static void checkBindAck(byte[] ack) throws IOException {
        if (ack.length < 16 || ack[0] != 5) {
            throw new IOException("Malformed RPC bind acknowledgement.");
        }
        int ptype = ack[2] & 0xFF;
        if (ptype == 13) {
            throw new IOException("The server rejected the RPC protocol.");
        }
        if (ptype != PTYPE_BIND_ACK) {
            throw new IOException("Unexpected RPC response type " + ptype + " to bind.");
        }
        // 16 header, 2+2 max frags, 4 assoc_group, then the counted
        // secondary address ("\pipe\srvsvc"), padded, then the results.
        int secLen = u16(ack, 24);
        int off = 26 + secLen;
        off = (off + 3) & ~3;
        off += 4;                          // n_results + 3 reserved
        if (off + 2 > ack.length) {
            throw new IOException("Truncated RPC bind acknowledgement.");
        }
        int result = u16(ack, off);
        if (result != 0) {
            throw new IOException("The server refused the srvsvc RPC interface "
                    + "(result " + result + ").");
        }
    }

    /** With no info union in the reply, only the trailing status remains. */
    private static IOException statusFromTail(ByteBuffer b) {
        int status = 0;
        byte[] pdu = null;
        try {
            b.position(b.limit() - 4);
            status = b.getInt();
        } catch (Exception e) {
            // fall through with status 0; the shape error below stands
        }
        return new IOException(status != 0
                ? winError(status)
                : "Unsupported srvsvc reply shape; cannot list shares.");
    }

    private static String winError(int rc) {
        if (rc == 5) {
            return "The server refused to list its shares for this account.";
        }
        if (rc == 124) {
            return "The server does not support share listing at level 1.";
        }
        if (rc == 234) {
            return "The server truncated the share list; retry.";
        }
        return "Share listing failed (Windows error " + (rc & 0xFFFFFFFFL) + ").";
    }

    // ---- pipe I/O ----

    /** Writes one PDU and reads back one (possibly multi-fragment) PDU. */
    private static byte[] exchange(com.hierynomus.smbj.share.NamedPipe pipe,
                                   byte[] pdu) throws IOException {
        int written = pipe.write(pdu, 0, pdu.length);
        if (written != pdu.length) {
            throw new IOException("Short write on the srvsvc pipe.");
        }
        byte[] headBytes = readFully(pipe, 16);
        int fragLength = u16(headBytes, 8);
        if (fragLength < 16) {
            throw new IOException("Malformed RPC fragment header.");
        }
        int flags = headBytes[3] & 0xFF;
        var out = new ByteArrayOutputStream();
        out.writeBytes(headBytes);
        out.writeBytes(readFully(pipe, fragLength - 16));
        // Continuation fragments: strip their headers, append bodies only,
        // until the fragment carrying the last-fragment flag arrives.
        while ((flags & PFC_LAST) == 0) {
            byte[] nextHead = readFully(pipe, 16);
            flags = nextHead[3] & 0xFF;
            out.writeBytes(readFully(pipe, u16(nextHead, 8) - 16));
        }
        return out.toByteArray();
    }

    private static byte[] readFully(com.hierynomus.smbj.share.NamedPipe pipe,
                                    int len) throws IOException {
        byte[] out = new byte[len];
        int done = 0;
        while (done < len) {
            int n = pipe.read(out, done, len - done);
            if (n < 0) break;
            done += n;
        }
        if (done != len) {
            throw new IOException("The srvsvc pipe closed mid-reply.");
        }
        return out;
    }

    private static int u16(byte[] a, int off) {
        return (a[off] & 0xFF) | ((a[off + 1] & 0xFF) << 8);
    }

    private static void pad4(ByteBuffer b) {
        b.position((b.position() + 3) & ~3);
    }

    private static void le16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }

    private static void le32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 24) & 0xFF);
    }
}
