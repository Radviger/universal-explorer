package dock.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AwsProfilesTest {

    @TempDir Path home;
    private final Map<String, String> env = new HashMap<>();

    private void write(String name, String text) throws Exception {
        Files.createDirectories(home.resolve(".aws"));
        Files.writeString(home.resolve(".aws").resolve(name), text);
    }

    private List<AwsProfiles.Profile> list() {
        return AwsProfiles.list(env::get, home);
    }

    @Test
    void keysComeFromCredentialsAndRegionFromConfig() throws Exception {
        write("credentials", """
                [work]
                aws_access_key_id = AKIAWORK
                aws_secret_access_key = workSecret
                """);
        write("config", """
                [profile work]
                region = eu-west-1
                output = json
                """);
        var p = AwsProfiles.profile("work", env::get, home);
        assertEquals("AKIAWORK", p.accessKeyId());
        assertEquals("workSecret", p.secretAccessKey());
        assertEquals("eu-west-1", p.region());
        assertTrue(p.hasKeys());
    }

    @Test
    void theDefaultProfileListsFirst() throws Exception {
        write("credentials", """
                [zeta]
                aws_access_key_id = A
                aws_secret_access_key = B
                [default]
                aws_access_key_id = C
                aws_secret_access_key = D
                """);
        assertEquals(List.of("default", "zeta"),
                list().stream().map(AwsProfiles.Profile::name).toList());
    }

    @Test
    void configSectionsOtherThanProfilesAreIgnored() throws Exception {
        write("config", """
                [default]
                region = us-east-2
                [sso-session corp]
                sso_region = us-east-1
                [services local]
                s3 =
                  endpoint_url = http://localhost:9000
                [plain-name]
                region = nowhere
                """);
        assertEquals(List.of("default"), list().stream().map(AwsProfiles.Profile::name).toList(),
                "a bare [name] in config is not a profile");
    }

    @Test
    void credentialsWinOverConfigForTheSameKey() throws Exception {
        write("credentials", "[default]\naws_access_key_id = FROM_CREDS\naws_secret_access_key = s\n");
        write("config", "[default]\naws_access_key_id = FROM_CONFIG\nregion = ap-south-1\n");
        var p = list().getFirst();
        assertEquals("FROM_CREDS", p.accessKeyId());
        assertEquals("ap-south-1", p.region());
    }

    @Test
    void nestedBlocksDoNotLeakIntoTheProfile() throws Exception {
        write("config", """
                [default]
                s3 =
                  region = wrong
                region = right
                """);
        assertEquals("right", list().getFirst().region());
    }

    @Test
    void sessionTokensAndEndpointsAreRead() throws Exception {
        write("credentials", """
                [temp]
                aws_access_key_id = ASIA
                aws_secret_access_key = s
                aws_session_token = tok
                """);
        write("config", "[profile temp]\nendpoint_url = http://minio.local:9000\n");
        var p = list().getFirst();
        assertEquals("tok", p.sessionToken());
        assertEquals("http://minio.local:9000", p.endpointUrl());
    }

    @Test
    void environmentOverridesTheFileLocations() throws Exception {
        Path custom = Files.writeString(home.resolve("elsewhere"),
                "[moved]\naws_access_key_id = A\naws_secret_access_key = B\n");
        env.put("AWS_SHARED_CREDENTIALS_FILE", custom.toString());
        assertEquals("moved", list().getFirst().name());
    }

    @Test
    void noFilesMeansNoProfiles() {
        assertEquals(List.of(), list());
        assertNull(AwsProfiles.profile("default", env::get, home));
    }

    // ---- as a site ----

    @Test
    void anAwsProfileDialsTheRegionalEndpointByProfile() {
        var site = new AwsProfiles.Profile("work", "A", "B", null, "eu-west-1", null).toSite();
        assertEquals("AWS work", site.name());
        assertEquals(Protocol.S3, site.protocol());
        assertEquals("s3.eu-west-1.amazonaws.com", site.host());
        assertEquals(443, site.port());
        assertTrue(site.secure());
        assertEquals("work", site.user(), "the profile name rides in the user slot");
        assertTrue(site.useAgent(), "keys come from outside the app");
        assertEquals("eu-west-1", site.region());
    }

    @Test
    void withoutARegionTheGlobalEndpointIsUsed() {
        var site = new AwsProfiles.Profile("d", "A", "B", null, null, null).toSite();
        assertEquals("s3.amazonaws.com", site.host());
    }

    @Test
    void anEndpointUrlPointsTheSiteAtAnS3CompatibleStore() {
        var site = new AwsProfiles.Profile("m", "A", "B", null, null,
                "http://minio.local:9000").toSite();
        assertEquals("minio.local", site.host());
        assertEquals(9000, site.port());
        assertFalse(site.secure());
    }

    @Test
    void profilesWithoutKeysAreNotListedAsSites() throws Exception {
        write("config", "[profile sso]\nsso_session = corp\nregion = us-east-1\n");
        write("credentials", "[keys]\naws_access_key_id = A\naws_secret_access_key = B\n");
        List<AwsProfiles.Profile> all = list();
        assertEquals(2, all.size());
        assertEquals(1, all.stream().filter(AwsProfiles.Profile::hasKeys).count());
    }
}
