package dock.core.platform;

import dock.core.secrets.MacKeychainStore;
import dock.core.secrets.SecretStore;
import dock.core.secrets.WindowsCredentialStore;

/**
 * The one place that picks an OS-specific implementation. Callers ask for
 * a capability, never for the OS; each implementation loads its natives
 * only when chosen, so a Windows binding never initializes on a Mac and
 * vice versa.
 */
public final class Platform {

    private static volatile SecretStore secrets;

    private Platform() {}

    /** The OS credential store: Credential Manager on Windows, the login
     *  Keychain on macOS, an unavailable store elsewhere (Linux awaits a
     *  Secret Service binding). */
    public static SecretStore secrets() {
        SecretStore s = secrets;
        if (s == null) {
            synchronized (Platform.class) {
                s = secrets;
                if (s == null) {
                    s = switch (Os.current()) {
                        case WINDOWS -> new WindowsCredentialStore();
                        case MAC -> new MacKeychainStore();
                        case LINUX, OTHER -> SecretStore.NONE;
                    };
                    secrets = s;
                }
            }
        }
        return s;
    }
}
