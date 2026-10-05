package dock.core.secrets;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import java.nio.charset.StandardCharsets;

/**
 * The macOS login Keychain, as generic-password items whose service name is
 * the credential target (Keychain Access lists them under that name). Spoken
 * through Security.framework's SecKeychain*GenericPassword calls: plain C
 * signatures, no CoreFoundation dictionaries — and unlike the {@code security}
 * CLI, the secret never appears on a command line.
 */
public final class MacKeychainStore implements SecretStore {

    private static final int ERR_SEC_ITEM_NOT_FOUND = -25300;
    /** Items carry no account; the service name alone identifies them. */
    private static final byte[] ACCOUNT = new byte[0];

    private interface Security extends Library {
        int SecKeychainAddGenericPassword(Pointer keychain,
                int serviceNameLength, byte[] serviceName,
                int accountNameLength, byte[] accountName,
                int passwordLength, byte[] passwordData,
                PointerByReference itemRef);

        int SecKeychainFindGenericPassword(Pointer keychainOrArray,
                int serviceNameLength, byte[] serviceName,
                int accountNameLength, byte[] accountName,
                IntByReference passwordLength, PointerByReference passwordData,
                PointerByReference itemRef);

        int SecKeychainItemDelete(Pointer itemRef);

        int SecKeychainItemFreeContent(Pointer attrList, Pointer data);
    }

    private interface CoreFoundation extends Library {
        void CFRelease(Pointer cf);
    }

    /** Loaded with the first instance — only {@code Platform} on macOS makes one. */
    private final Security sec = Native.load("Security", Security.class);
    private final CoreFoundation cf = Native.load("CoreFoundation", CoreFoundation.class);

    @Override public boolean available() {
        return true;
    }

    @Override public void write(String target, String secret) {
        byte[] service = utf8(target);
        byte[] data = utf8(secret);
        int rc = sec.SecKeychainAddGenericPassword(null, service.length, service,
                ACCOUNT.length, ACCOUNT, data.length, data, null);
        check(rc, "SecKeychainAddGenericPassword");
    }

    @Override public String read(String target) {
        byte[] service = utf8(target);
        IntByReference length = new IntByReference();
        PointerByReference data = new PointerByReference();
        int rc = sec.SecKeychainFindGenericPassword(null, service.length, service,
                ACCOUNT.length, ACCOUNT, length, data, null);
        if (rc == ERR_SEC_ITEM_NOT_FOUND) return null;
        check(rc, "SecKeychainFindGenericPassword");
        try {
            int n = length.getValue();
            if (n == 0 || data.getValue() == null) return "";
            return new String(data.getValue().getByteArray(0, n), StandardCharsets.UTF_8);
        } finally {
            sec.SecKeychainItemFreeContent(null, data.getValue());
        }
    }

    @Override public void remove(String target) {
        byte[] service = utf8(target);
        PointerByReference item = new PointerByReference();
        int rc = sec.SecKeychainFindGenericPassword(null, service.length, service,
                ACCOUNT.length, ACCOUNT, null, null, item);
        if (rc == ERR_SEC_ITEM_NOT_FOUND) return;
        check(rc, "SecKeychainFindGenericPassword");
        try {
            check(sec.SecKeychainItemDelete(item.getValue()), "SecKeychainItemDelete");
        } finally {
            cf.CFRelease(item.getValue());
        }
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void check(int rc, String call) {
        if (rc != 0) throw new IllegalStateException(call + " failed: OSStatus " + rc);
    }
}
