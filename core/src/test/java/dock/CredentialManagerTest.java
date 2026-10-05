package dock;

import static org.junit.jupiter.api.Assertions.*;

import dock.core.secrets.CredentialManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Proves the OS store binding (Credential Manager, Keychain) really round-trips. */
class CredentialManagerTest {

    @Test
    void savesLoadsDeletes() {
        Assumptions.assumeTrue(CredentialManager.available(), "no OS credential store");
        String target = "Dock/selftest";
        try {
            CredentialManager.save(target, "s3cret-passphrase");
            assertEquals("s3cret-passphrase", CredentialManager.load(target));

            CredentialManager.save(target, "rotated");
            assertEquals("rotated", CredentialManager.load(target), "save must overwrite");

            CredentialManager.delete(target);
            assertNull(CredentialManager.load(target));
        } finally {
            CredentialManager.delete(target);
        }
    }

    @Test
    void missingEntryLoadsNull() {
        Assumptions.assumeTrue(CredentialManager.available(), "no OS credential store");
        assertNull(CredentialManager.load("Dock/definitely-not-stored-xyz"));
    }

    @Test
    void legacyDockTargetsLoadAndCopyForward() {
        Assumptions.assumeTrue(CredentialManager.available(), "no OS credential store");
        String legacy = "Dock/renamed-selftest";
        String current = "Universal Explorer/renamed-selftest";
        try {
            CredentialManager.save(legacy, "pre-rename-secret");
            assertEquals("pre-rename-secret", CredentialManager.load(current),
                    "the legacy twin must still serve the secret");
            CredentialManager.delete(legacy);
            assertEquals("pre-rename-secret", CredentialManager.load(current),
                    "the fallback must have stored the copy before the original went");
        } finally {
            CredentialManager.delete(current);
        }
    }

    @Test
    void deletingATargetPurgesItsLegacyTwin() {
        Assumptions.assumeTrue(CredentialManager.available(), "no OS credential store");
        try {
            CredentialManager.save("Dock/twin-selftest", "stale");
            CredentialManager.delete("Universal Explorer/twin-selftest");
            assertNull(CredentialManager.load("Dock/twin-selftest"));
        } finally {
            CredentialManager.delete("Universal Explorer/twin-selftest");
        }
    }
}
