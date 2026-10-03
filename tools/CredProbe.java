import com.sun.jna.*;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import dock.core.secrets.CredentialManager;
import java.nio.charset.StandardCharsets;

public class CredProbe {
    public interface Raw extends StdCallLibrary {
        boolean CredWriteW(CredentialManager.CREDENTIAL cred, int flags) throws LastErrorException;
        boolean CredReadW(String t, int type, int f, PointerByReference out) throws LastErrorException;
        void CredFree(Pointer p);
    }
    public static void main(String[] args) {
        Raw adv = Native.load("Advapi32", Raw.class, W32APIOptions.DEFAULT_OPTIONS);
        byte[] blob = "hello".getBytes(StandardCharsets.UTF_8);
        Memory mem = new Memory(blob.length);
        mem.write(0, blob, 0, blob.length);
        CredentialManager.CREDENTIAL cred = new CredentialManager.CREDENTIAL();
        cred.Type = 1;
        cred.TargetName = "Dock/probe2";
        cred.UserName = "";
        cred.CredentialBlobSize = blob.length;
        cred.CredentialBlob = mem;
        cred.Persist = 2;
        try {
            boolean ok = adv.CredWriteW(cred, 0);
            System.out.println("CredWriteW returned " + ok + " (no exception)");
        } catch (LastErrorException e) {
            System.out.println("CredWriteW error " + e.getErrorCode());
            return;
        }
        PointerByReference out = new PointerByReference();
        try {
            boolean ok2 = adv.CredReadW("Dock/probe2", 1, 0, out);
            System.out.println("CredReadW returned " + ok2);
            if (ok2) {
                CredentialManager.CREDENTIAL back =
                        Structure.newInstance(CredentialManager.CREDENTIAL.class, out.getValue());
                back.read();
                System.out.println("target=" + back.TargetName + " size=" + back.CredentialBlobSize);
                adv.CredFree(out.getValue());
            }
        } catch (LastErrorException e) {
            System.out.println("CredReadW error " + e.getErrorCode());
        }
    }
}
