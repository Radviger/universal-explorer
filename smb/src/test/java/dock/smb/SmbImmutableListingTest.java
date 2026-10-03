package dock.smb;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The server root — the share selection screen — is an immutable listing:
 * its rows are shares the server exported, not folders this client could
 * rename, delete or add to. The predicate is pure path geometry, so it is
 * testable without a connection (the constructor never touches the wire).
 */
class SmbImmutableListingTest {

    private static SmbFs fs() {
        return new SmbFs(new SmbSessions.SmbSpec(
                "nas", 445, null, "root", "pw".toCharArray(), false, ""), null, null);
    }

    @Test
    void theServerRootIsImmutableAndNothingBelowIt() {
        var fs = fs();
        assertTrue(fs.immutableListing("/"), "the share list itself");
        assertTrue(fs.immutableListing(""), "unnormalized roots count too");
        assertTrue(fs.immutableListing("//"));
        assertFalse(fs.immutableListing("/Public"), "a share root is a real folder");
        assertFalse(fs.immutableListing("/Public/docs"));
    }
}
