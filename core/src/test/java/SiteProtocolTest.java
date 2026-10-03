import dock.core.config.AppPaths;
import dock.core.config.Protocol;
import dock.core.config.Site;
import dock.core.config.Sites;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session store grows a protocol: new files carry it, old files read
 * back as SFTP, and the per-protocol SMB fields round-trip.
 */
class SiteProtocolTest {

    @TempDir
    static Path configDir;

    @BeforeAll
    static void isolate() {
        AppPaths.override(configDir);
    }

    @Test
    void smbFieldsRoundTripThroughSessionsJson() throws Exception {
        Sites.upsert(new Site("unit-nas", Protocol.SMB, "nas", 445, "root",
                null, false, "WORKGROUP", "Public", false, 0));
        Site s = Sites.load().stream()
                .filter(x -> x.name().equals("unit-nas")).findFirst().orElseThrow();
        assertEquals(Protocol.SMB, s.protocol());
        assertEquals("nas", s.host());
        assertEquals(445, s.port());
        assertEquals("root", s.user());
        assertEquals("WORKGROUP", s.domain());
        assertEquals("Public", s.initialPath());
        assertEquals("Universal Explorer/unit-nas", s.secretTarget(),
                "SMB sites share the SFTP secret target scheme");
    }

    @Test
    void webDavFieldsRoundTripThroughSessionsJson() throws Exception {
        Sites.upsert(new Site("unit-dav", Protocol.WEBDAV, "nas", 5006, "root",
                null, false, null, "/dav", false, true, 0));
        Site s = Sites.load().stream()
                .filter(x -> x.name().equals("unit-dav")).findFirst().orElseThrow();
        assertEquals(Protocol.WEBDAV, s.protocol());
        assertEquals(true, s.secure(), "https round-trips");
        assertEquals("/dav", s.initialPath(), "the base path rides initialPath");
        assertEquals(5006, s.port());
    }

    @Test
    void legacyFilesWithoutSecureReadAsHttp() throws Exception {
        Path legacy = Files.createTempDirectory("dock-legacy-dav");
        AppPaths.override(legacy);
        try {
            Files.writeString(legacy.resolve("sessions.json"), """
                    [{"name":"old-dav","protocol":"WEBDAV","host":"nas","port":5006,
                      "user":"root","initialPath":"/dav"}]
                    """);
            List<Site> loaded = Sites.load();
            assertEquals(Protocol.WEBDAV, loaded.get(0).protocol());
            assertEquals(false, loaded.get(0).secure(),
                    "sessions saved before https landed connect over http");
        } finally {
            AppPaths.override(configDir);
        }
    }

    @Test
    void ftpFieldsRoundTripThroughSessionsJson() throws Exception {
        Sites.upsert(new Site("unit-ftp", Protocol.FTP, "nas", 0, "root",
                null, false, null, "/downloads", false, true, 0));
        Site s = Sites.load().stream()
                .filter(x -> x.name().equals("unit-ftp")).findFirst().orElseThrow();
        assertEquals(Protocol.FTP, s.protocol());
        assertEquals(true, s.secure(), "the TLS flag round-trips");
        assertEquals(21, s.port(), "port 0 takes the protocol's own default");
        assertEquals("/downloads", s.initialPath());
    }

    @Test
    void s3FieldsRoundTripThroughSessionsJson() throws Exception {
        Sites.upsert(new Site("unit-s3", Protocol.S3, "s3.example.com", 443, "AKIDEXAMPLE",
                null, false, null, "backups", false, true, 0, null, null, null, "eu-west-1"));
        Site s = Sites.load().stream()
                .filter(x -> x.name().equals("unit-s3")).findFirst().orElseThrow();
        assertEquals(Protocol.S3, s.protocol());
        assertEquals("eu-west-1", s.region(), "the signature scope round-trips");
        assertEquals("backups", s.initialPath(), "the opening bucket rides initialPath");
        assertEquals("AKIDEXAMPLE", s.user(), "the access key ID rides user");
        assertNull(s.domain());
    }

    @Test
    void legacyFilesWithoutRegionReadAsNull() throws Exception {
        Path legacy = Files.createTempDirectory("dock-legacy-s3");
        AppPaths.override(legacy);
        try {
            Files.writeString(legacy.resolve("sessions.json"), """
                    [{"name":"old-s3","protocol":"S3","host":"s3.example.com","port":443,
                      "user":"AKID","initialPath":"backups","secure":true}]
                    """);
            Site s = Sites.load().get(0);
            assertEquals(Protocol.S3, s.protocol());
            assertNull(s.region(), "sessions saved before region default the scope");
        } finally {
            AppPaths.override(configDir);
        }
    }

    @Test
    void replaceSwapsEntriesEvenAcrossARename() throws Exception {
        Sites.upsert(new Site("unit-old", "nas", 22, "root", null));
        Sites.replace("unit-old", new Site("unit-new", "nas2", 2222, "root2", null));
        assertTrue(Sites.load().stream().noneMatch(s -> s.name().equals("unit-old")),
                "the old name does not survive a rename");
        Site s = Sites.load().stream()
                .filter(x -> x.name().equals("unit-new")).findFirst().orElseThrow();
        assertEquals("nas2", s.host());
        assertEquals(2222, s.port());
    }

    @Test
    void guestSiteDropsItsUser() {
        Site s = new Site("unit-guest", Protocol.SMB, "router", 0, "leftover",
                null, false, null, null, true, 0);
        assertEquals("", s.user(), "guest normalizes the user away");
        assertEquals(445, s.port(), "port 0 takes the protocol default");
    }

    @Test
    void legacyFilesWithoutProtocolReadAsSftp() throws Exception {
        Path legacy = Files.createTempDirectory("dock-legacy");
        AppPaths.override(legacy);
        try {
            Files.writeString(legacy.resolve("sessions.json"), """
                    [{"name":"old-box","host":"198.51.100.9","port":22,"user":"admin","keyPath":null}]
                    """);
            List<Site> loaded = Sites.load();
            assertEquals(Protocol.SFTP, loaded.get(0).protocol());
            assertNull(loaded.get(0).domain());
        } finally {
            AppPaths.override(configDir);
        }
    }

    @Test
    void unknownProtocolStringsFallBackToSftp() {
        assertEquals(Protocol.SFTP, Protocol.of("carrier-pigeon"));
        assertEquals(Protocol.SFTP, Protocol.of(null));
        assertEquals(Protocol.SFTP, Protocol.of(""));
        assertEquals(Protocol.SMB, Protocol.of("smb"));
        assertEquals(Protocol.SMB, Protocol.of(" SMB "),
                "stored names tolerate case and padding");
    }

    @Test
    void everyProtocolHasALabelAndPort() {
        for (Protocol p : Protocol.values()) {
            assertTrue(p.defaultPort() > 0, p + " needs a default port");
            assertTrue(p.label() != null && !p.label().isBlank(), p + " needs a label");
        }
    }

    @Test
    void pathMemoryRoundTripsAndIgnoresUnknownNames() throws Exception {
        Sites.upsert(new Site("unit-nas", Protocol.SMB, "nas", 445, "root",
                null, false, "WORKGROUP", "Public", false, 0));
        Sites.recordPaths("unit-nas", "C:\\work\\here", "/srv/data");
        Site s = Sites.load().stream()
                .filter(x -> x.name().equals("unit-nas")).findFirst().orElseThrow();
        assertEquals("C:\\work\\here", s.lastLocalPath(), "the local side remembers");
        assertEquals("/srv/data", s.lastRemotePath(), "the remote side remembers");
        assertEquals("Public", s.initialPath(), "the user's opening path stays");
        assertEquals(Protocol.SMB, s.protocol(), "the rest of the site is untouched");

        // An unsaved quick connect has nothing to key memory on.
        Sites.recordPaths("stranger", "C:\\x", "/y");
        assertTrue(Sites.load().stream().noneMatch(x -> x.name().equals("stranger")),
                "unknown names record nothing");
    }

    @Test
    void pathMemoryIsNotAUse() throws Exception {
        Sites.upsert(new Site("unit-rec", "nas", 22, "root", null));
        Sites.touch("unit-rec");
        long used = Sites.load().stream()
                .filter(x -> x.name().equals("unit-rec")).findFirst().orElseThrow().lastUsed();
        Sites.recordPaths("unit-rec", "C:\\a", "/b");
        Site s = Sites.load().stream()
                .filter(x -> x.name().equals("unit-rec")).findFirst().orElseThrow();
        assertEquals(used, s.lastUsed(), "recording where the panes stood is not a connect");
        assertEquals("/b", s.lastRemotePath());
    }

    @Test
    void legacyFilesWithoutPathMemoryReadAsUnremembered() throws Exception {
        Path legacy = Files.createTempDirectory("dock-legacy-paths");
        AppPaths.override(legacy);
        try {
            Files.writeString(legacy.resolve("sessions.json"), """
                    [{"name":"old-box","host":"198.51.100.9","port":22,"user":"admin"}]
                    """);
            Site s = Sites.load().get(0);
            assertNull(s.lastLocalPath(), "sessions saved before path memory open unplaced");
            assertNull(s.lastRemotePath());
        } finally {
            AppPaths.override(configDir);
        }
    }

    @Test
    void blankRememberedPathsNormalizeAway() {
        Site s = new Site("unit-blank", "nas", 22, "root", null).withPaths("   ", "");
        assertNull(s.lastLocalPath());
        assertNull(s.lastRemotePath());
    }
}
