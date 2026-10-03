package dock;

import static org.junit.jupiter.api.Assertions.*;

import dock.core.secrets.CredentialManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Proves the hand-rolled JNA CREDENTIAL mapping really round-trips. */
class CredentialManagerTest {

    @Test
    void savesLoadsDeletes() {
        Assumptions.assumeTrue(CredentialManager.available(), "Windows only");
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
        Assumptions.assumeTrue(CredentialManager.available(), "Windows only");
        assertNull(CredentialManager.load("Dock/definitely-not-stored-xyz"));
    }

    @Test
    void legacyDockTargetsLoadAndCopyForward() {
        Assumptions.assumeTrue(CredentialManager.available(), "Windows only");
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
        Assumptions.assumeTrue(CredentialManager.available(), "Windows only");
        try {
            CredentialManager.save("Dock/twin-selftest", "stale");
            CredentialManager.delete("Universal Explorer/twin-selftest");
            assertNull(CredentialManager.load("Dock/twin-selftest"));
        } finally {
            CredentialManager.delete("Universal Explorer/twin-selftest");
        }
    }
}
