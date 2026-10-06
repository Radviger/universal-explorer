package dock.s3;

import dock.core.config.Protocol;
import dock.core.config.Site;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The marks/labels the shell shows for S3 sites — most importantly, the
 * home row never carries credential material.
 */
class S3BackendTest {

    private final S3Backend backend = new S3Backend();

    @Test
    void theHomeRowShowsHowNotWithWhat() {
        Site site = new Site("unit-s3", Protocol.S3, "s3.example.com", 443,
                "AKIAIOSFODNN7EXAMPLE", null, false, null, "backups", false, true, 0,
                null, null, null, "eu-west-1");
        String line = backend.secondaryText(site);
        assertFalse(line.contains("AKIAIOSFODNN7EXAMPLE"),
                "the access key ID is half of a key pair — it never renders");
        assertEquals("s3.example.com  ·  eu-west-1  ·  access key", line,
                "endpoint, optional region, and the authorization type — SFTP's tail shape");
    }

    @Test
    void aCustomPortShowsAndADefaultRegionHides() {
        Site site = new Site("unit-minio", Protocol.S3, "minio.example.com", 9000,
                "docktest", null, false, null, null, false, false, 0);
        assertEquals("minio.example.com:9000  ·  access key", backend.secondaryText(site));
    }

    @Test
    void aProfileSiteNamesItsProfileNotAKey() {
        Site site = new Site("AWS work", Protocol.S3, "s3.eu-west-1.amazonaws.com", 443,
                "work", null, true, null, null, false, true, 0, null, null, null, "eu-west-1");
        assertEquals("s3.eu-west-1.amazonaws.com  ·  eu-west-1  ·  profile work",
                backend.secondaryText(site));
    }

    @Test
    void aMissingProfileFailsWithItsName() {
        Site site = new Site("AWS ghost", Protocol.S3, "s3.amazonaws.com", 443,
                "unit-no-such-profile-xyz", null, true, null, null, false, true, 0);
        var e = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> backend.dial(site, target -> {
                    throw new AssertionError("a profile site never asks for a stored secret");
                }));
        org.junit.jupiter.api.Assertions.assertTrue(
                e.getMessage().contains("unit-no-such-profile-xyz"), e.getMessage());
    }

    @Test
    void theCloudGlyphMarksS3Everywhere() {
        assertEquals(dock.kit.Glyphs.CLOUD, backend.glyph());
        assertEquals(dock.kit.Glyphs.CLOUD, backend.glyphOf(new S3Fs(null,
                new S3Sessions.S3Spec("nas", 0, false, null, "ak", "sk".toCharArray(), null))));
        assertEquals(Protocol.S3, backend.protocol());
    }
}
