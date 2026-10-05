package dock.core.secrets;

import com.sun.jna.LastErrorException;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The Windows Credential Manager (DPAPI protected, tied to the Windows
 * account). JNA 5.x ships no Cred* mapping, so the small native surface is
 * declared here directly. String fields are marshaled manually as UTF-16
 * pointers: JNA applies library type mappers only to structures constructed
 * inside a library context, so plain String fields silently marshal as null.
 */
public final class WindowsCredentialStore implements SecretStore {

    private static final int CRED_TYPE_GENERIC = 1;
    private static final int CRED_PERSIST_LOCAL_MACHINE = 2;
    private static final int ERROR_NOT_FOUND = 1168;

    private interface RawAdvapi32 extends StdCallLibrary {
        boolean CredWriteW(CREDENTIAL cred, int flags) throws LastErrorException;
        boolean CredReadW(Pointer target, int type, int flags, PointerByReference out)
                throws LastErrorException;
        boolean CredDeleteW(Pointer target, int type, int flags) throws LastErrorException;
        void CredFree(Pointer buffer);
    }

    @Structure.FieldOrder({"Flags", "Type", "TargetName", "Comment", "LastWritten",
            "CredentialBlobSize", "CredentialBlob", "Persist", "AttributeCount",
            "Attributes", "TargetAlias", "UserName"})
    public static class CREDENTIAL extends Structure {
        public int Flags;
        public int Type;
        public Pointer TargetName;
        public Pointer Comment;
        public WinBaseFileTime LastWritten;
        public int CredentialBlobSize;
        public Pointer CredentialBlob;
        public int Persist;
        public int AttributeCount;
        public Pointer Attributes;
        public Pointer TargetAlias;
        public Pointer UserName;
    }

    /** Embedded FILETIME (8 raw bytes). */
    @Structure.FieldOrder({"dwLowDateTime", "dwHighDateTime"})
    public static class WinBaseFileTime extends Structure {
        public int dwLowDateTime;
        public int dwHighDateTime;
    }

    /** Loaded with the first instance — only {@code Platform} on Windows makes one. */
    private final RawAdvapi32 adv =
            Native.load("Advapi32", RawAdvapi32.class, W32APIOptions.DEFAULT_OPTIONS);

    @Override public boolean available() {
        return true;
    }

    @Override public String displayName() {
        return "Windows Credential Manager";
    }

    @Override public void write(String target, String secret) {
        List<Memory> keepAlive = new ArrayList<>();
        byte[] blob = secret.getBytes(StandardCharsets.UTF_8);
        Memory blobMem = new Memory(Math.max(1, blob.length));
        blobMem.write(0, blob, 0, blob.length);
        keepAlive.add(blobMem);

        CREDENTIAL cred = new CREDENTIAL();
        cred.Type = CRED_TYPE_GENERIC;
        cred.TargetName = wide(target, keepAlive);
        cred.UserName = wide("", keepAlive);
        cred.CredentialBlobSize = blob.length;
        cred.CredentialBlob = blobMem;
        cred.Persist = CRED_PERSIST_LOCAL_MACHINE;
        try {
            adv.CredWriteW(cred, 0);
        } catch (LastErrorException e) {
            throw new IllegalStateException("CredWrite failed: " + e.getMessage(), e);
        }
    }

    @Override public String read(String target) {
        List<Memory> keepAlive = new ArrayList<>();
        PointerByReference out = new PointerByReference();
        try {
            adv.CredReadW(wide(target, keepAlive), CRED_TYPE_GENERIC, 0, out);
        } catch (LastErrorException e) {
            if (e.getErrorCode() == ERROR_NOT_FOUND) return null;
            throw new IllegalStateException("CredRead failed: " + e.getMessage(), e);
        }
        try {
            CREDENTIAL cred = Structure.newInstance(CREDENTIAL.class, out.getValue());
            cred.read();
            if (cred.CredentialBlob == null || cred.CredentialBlobSize == 0) return "";
            byte[] blob = cred.CredentialBlob.getByteArray(0, cred.CredentialBlobSize);
            return new String(blob, StandardCharsets.UTF_8);
        } finally {
            adv.CredFree(out.getValue());
        }
    }

    @Override public void remove(String target) {
        List<Memory> keepAlive = new ArrayList<>();
        try {
            adv.CredDeleteW(wide(target, keepAlive), CRED_TYPE_GENERIC, 0);
        } catch (LastErrorException e) {
            if (e.getErrorCode() != ERROR_NOT_FOUND) {
                throw new IllegalStateException("CredDelete failed: " + e.getMessage(), e);
            }
        }
    }

    private static Pointer wide(String s, List<Memory> keepAlive) {
        byte[] utf16 = (s + "\0").getBytes(StandardCharsets.UTF_16LE);
        Memory m = new Memory(utf16.length);
        m.write(0, utf16, 0, utf16.length);
        keepAlive.add(m);
        return m;
    }
}
