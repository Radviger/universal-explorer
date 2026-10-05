package dock.core.secrets;

/**
 * Raw access to one OS credential store, keyed by a target string. Only
 * the storage primitives live here; the target naming and the legacy
 * "Dock/…" fallback stay in {@link CredentialManager}, the same on every OS.
 */
public interface SecretStore {

    /** False when this OS has no supported store; every other call is then a no-op. */
    boolean available();

    /** Stores a secret under a target that holds nothing yet. */
    void write(String target, String secret);

    /** The stored secret, or null when the target holds nothing. */
    String read(String target);

    /** Removes the target; a missing target is not an error. */
    void remove(String target);

    /** The store of an OS without one: nothing is ever kept. */
    SecretStore NONE = new SecretStore() {
        @Override public boolean available() { return false; }
        @Override public void write(String target, String secret) {}
        @Override public String read(String target) { return null; }
        @Override public void remove(String target) {}
    };
}
