package dock.core.spi;

/**
 * Where a backend's dial gets its secrets. The production shell binds
 * this to the OS credential store; the connect dialog binds it to the
 * form's password fields; tests bind it to a plain map. Implementations
 * return a fresh array per call — the caller wipes it after use, and a
 * spec that outlives the dial takes its own clone.
 */
@FunctionalInterface
public interface SecretSource {

    /**
     * The secret stored under a credential target ({@code
     * Universal Explorer/<site>} for the password, {@code
     * Universal Explorer/<site>/key} for a key passphrase; targets saved
     * before the rename still load via CredentialManager's legacy
     * fallback), or null when nothing is stored there.
     */
    char[] load(String credentialTarget);
}
