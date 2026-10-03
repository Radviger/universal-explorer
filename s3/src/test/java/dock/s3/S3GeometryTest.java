package dock.s3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The endpoint's path geometry and virtual-root contract, testable
 * without a server — the constructor never touches the wire (the
 * HttpClient argument is null and stays that way).
 */
class S3GeometryTest {

    private final S3Fs fs = new S3Fs(null, new S3Sessions.S3Spec(
            "Nas.Example", 0, true, null, "ak", "secret".toCharArray(), null));
    private final S3Fs pinned = new S3Fs(null, new S3Sessions.S3Spec(
            "nas", 0, false, null, "ak", "secret".toCharArray(), "media"));

    @Test
    void theRootIsTheBucketList() {
        assertTrue(fs.immutableListing("/"), "bucket rows accept no mutations");
        assertFalse(fs.immutableListing("/media"));
        assertFalse(fs.extractable("/"), "a bucket is not copyable out, like an SMB share");
        assertEquals("/", fs.home());
        assertEquals("s3://Nas.Example", fs.label());
        assertTrue(fs.remote());
        assertEquals("/", fs.separator());
        assertEquals(java.util.List.of("/"), fs.roots());
    }

    @Test
    void anOpeningBucketPinsHome() {
        assertEquals("/media", pinned.home());
    }

    @Test
    void bucketGeometrySplitsAtTheFirstSlash() {
        assertNull(S3Paths.bucketOf("/"));
        assertNull(S3Paths.restOf("/"));
        assertEquals("media", S3Paths.bucketOf("/media/logs/2024"));
        assertEquals("logs/2024", S3Paths.restOf("/media/logs/2024"),
                "a key's own slashes are part of the name");
        assertEquals("", S3Paths.restOf("/media"));
        assertEquals("/media", S3Paths.parent("/media/readme.md"));
        assertEquals("/media/a", S3Paths.child("/media", "a"));
        assertEquals("/media/a", S3Paths.normalize("\\media\\a\\"));
    }
}
