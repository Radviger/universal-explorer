package dock.core.secrets;

import dock.core.platform.Platform;

/**
 * Password/passphrase storage in the OS credential store (see
 * {@link Platform#secrets()}): the Windows Credential Manager or the macOS
 * login Keychain. Secrets never touch a file of ours.
 */
public final class CredentialManager {

    /** The app was Project Dock once. Loads that miss a current target
     *  retry its legacy "Dock/…" twin and copy the secret forward; deletes
     *  take the twin down too, so no stale credential outlives the site. */
    private static final String TARGET_PREFIX = "Universal Explorer/";
    private static final String LEGACY_TARGET_PREFIX = "Dock/";

    private CredentialManager() {}

    public static boolean available() {
        return store().available();
    }

    /** The OS store's name for UI text ("Keychain"). */
    public static String storeName() {
        return store().displayName();
    }

    public static void save(String target, String secret) {
        if (!available() || secret == null) return;
        delete(target);
        store().write(target, secret);
    }

    /** Returns null when nothing is stored (or the OS has no store). A miss
     *  on a current target retries the legacy twin and copies the secret
     *  forward — the fallback never destroys the old entry. */
    public static String load(String target) {
        if (!available()) return null;
        String secret = store().read(target);
        if (secret == null && target.startsWith(TARGET_PREFIX)) {
            secret = store().read(LEGACY_TARGET_PREFIX + target.substring(TARGET_PREFIX.length()));
            if (secret != null) store().write(target, secret);
        }
        return secret;
    }

    public static void delete(String target) {
        if (!available()) return;
        store().remove(target);
        if (target.startsWith(TARGET_PREFIX)) {
            store().remove(LEGACY_TARGET_PREFIX + target.substring(TARGET_PREFIX.length()));
        }
    }

    private static SecretStore store() {
        return Platform.secrets();
    }
}
