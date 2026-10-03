package dock.smb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-format contracts for the srvsvc share listing: the bind and
 * NetrShareEnum PDUs are fixed-shape bytes, and the reply parser must
 * follow the layout verified against a live Samba server (pidl's inline
 * pointer style: element ids first, strings after, then total/resume/
 * status).
 */
class SrvsvcTest {

    @Test
    void bindPduIsTheFixedShape() {
        byte[] pdu = Srvsvc.bindPdu(1);
        assertEquals(72, pdu.length);
        assertEquals("05 00 0b 03 10 00 00 00 48 00 00 00 01 00 00 00",
                HexFormat.ofDelimiter(" ").formatHex(java.util.Arrays.copyOf(pdu, 16)));
        // Abstract syntax (SRVSVC 3.0) and transfer syntax (NDR 2.0).
        assertEquals("c8 4f 32 4b 70 16 d3 01 12 78 5a 47 bf 6e e1 88",
                HexFormat.ofDelimiter(" ").formatHex(
                        java.util.Arrays.copyOfRange(pdu, 32, 48)));
        assertEquals(3, i32(pdu, 48));
        assertEquals("04 5d 88 8a eb 1c c9 11 9f e8 08 00 2b 10 48 60",
                HexFormat.ofDelimiter(" ").formatHex(
                        java.util.Arrays.copyOfRange(pdu, 52, 68)));
        assertEquals(2, i32(pdu, 68));
    }

    @Test
    void requestStubIsNullsAndLevelOne() {
        byte[] stub = Srvsvc.requestStub();
        assertEquals(28, stub.length);
        assertEquals("00 00 00 00 01 00 00 00 01 00 00 00 00 00 00 00 "
                + "ff ff ff ff 00 00 02 00 00 00 00 00",
                HexFormat.ofDelimiter(" ").formatHex(stub));
    }

    @Test
    void requestPduWrapsStubWithOpnum15() {
        byte[] pdu = Srvsvc.requestPdu(2, Srvsvc.requestStub());
        assertEquals(52, pdu.length);
        assertEquals(0, pdu[2]);                      // ptype REQUEST
        assertEquals(52, i32(pdu, 8) & 0xFFFF);       // frag length
        assertEquals(2, i32(pdu, 12));                // call id
        assertEquals(28, i32(pdu, 16));               // alloc hint
        assertEquals(15, i32(pdu, 22) & 0xFFFF);      // opnum
    }

    @Test
    void parsesRealisticReply() throws Exception {
        var shares = Srvsvc.parseResponse(reply(0, share("print$", 0, null),
                share("IPC$", 0x80000003, "IPC")));
        assertEquals(2, shares.size());
        assertEquals("print$", shares.get(0).name());
        assertEquals(0, shares.get(0).type());
        assertTrue(shares.get(0).disk());
        assertTrue(shares.get(0).special(), "names ending in $ are special");
        assertTrue(shares.get(0).remark() == null, "null remark pointer has no string");
        assertEquals("IPC$", shares.get(1).name());
        assertEquals(0x80000003, shares.get(1).type());
        assertTrue(!shares.get(1).disk());
        assertEquals("IPC", shares.get(1).remark());
    }

    @Test
    void parsesEmptyReply() throws Exception {
        var shares = Srvsvc.parseResponse(reply(0));
        assertTrue(shares.isEmpty());
    }

    @Test
    void reportsWin32Failure() {
        var e = assertThrows(java.io.IOException.class,
                () -> Srvsvc.parseResponse(reply(5)));
        assertTrue(e.getMessage().contains("refused"),
                "access denied must read as a refusal: " + e.getMessage());
    }

    @Test
    void rejectsOtherShareLevels() {
        var e = assertThrows(java.io.IOException.class,
                () -> Srvsvc.parseResponse(reply(0, 2)));
        assertTrue(e.getMessage().contains("level"));
    }

    // ---- synthetic replies in the live-verified layout ----

    private record Row(String name, int type, String remark) {}

    private static Row share(String name, int type, String remark) {
        return new Row(name, type, remark);
    }

    private static byte[] reply(int status, Row... rows) {
        return reply(status, 1, rows);
    }

    private static byte[] reply(int status, int level, Row... rows) {
        var body = new java.io.ByteArrayOutputStream();
        le32(body, 0x00000001);              // info union pointer
        le32(body, level);                   // union switch
        if (level == 1 && rows.length >= 0) {
            le32(body, 0x00020004);          // container pointer
            le32(body, rows.length);         // container count
            le32(body, rows.length == 0 ? 0 : 0x00020008);  // array pointer
            le32(body, rows.length);         // array max count
            for (int i = 0; i < rows.length; i++) {
                le32(body, rows[i].name() == null ? 0 : 0x00020010 + i * 2);
                le32(body, rows[i].type());
                le32(body, rows[i].remark() == null ? 0 : 0x00020011 + i * 2);
            }
            for (Row row : rows) {
                if (row.name() != null) ndrString(body, row.name());
                if (row.remark() != null) ndrString(body, row.remark());
            }
        }
        le32(body, rows.length);             // total entries
        le32(body, 0);                       // resume handle: NULL pointer
        le32(body, status);
        byte[] stub = body.toByteArray();

        var pdu = new java.io.ByteArrayOutputStream();
        pdu.write(5); pdu.write(0); pdu.write(2); pdu.write(3);
        pdu.write(0x10); pdu.write(0); pdu.write(0); pdu.write(0);
        le16(pdu, 24 + stub.length);
        le16(pdu, 0);
        le32(pdu, 2);                        // call id
        le32(pdu, stub.length);              // alloc hint
        le16(pdu, 0);                        // context id
        le16(pdu, 15);                       // opnum
        pdu.writeBytes(stub);
        return pdu.toByteArray();
    }

    private static void ndrString(java.io.ByteArrayOutputStream o, String s) {
        int chars = s.length() + 1;          // the NUL terminator counts
        le32(o, chars);                      // max count
        le32(o, 0);                          // offset
        le32(o, chars);                      // actual count
        byte[] raw = (s + "\0").getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        o.writeBytes(raw);
        for (int p = raw.length % 4; p != 0 && p < 4; p++) o.write(0);
    }

    private static int i32(byte[] a, int off) {
        return ByteBuffer.wrap(a, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static void le16(java.io.ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }

    private static void le32(java.io.ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 24) & 0xFF);
    }
}
