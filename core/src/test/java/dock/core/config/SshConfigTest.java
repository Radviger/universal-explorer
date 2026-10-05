package dock.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SshConfigTest {

    @TempDir Path home;

    private List<SshConfig.Host> parse(String text) {
        return SshConfig.parse(text, home, "me");
    }

    private SshConfig.Host only(String text) {
        List<SshConfig.Host> hosts = parse(text);
        assertEquals(1, hosts.size(), "hosts: " + hosts);
        return hosts.getFirst();
    }

    @Test
    void aHostBlockCarriesItsOptions() {
        var h = only("""
                Host prod
                    HostName 10.0.0.5
                    User deploy
                    Port 2222
                    IdentityFile ~/.ssh/prod_key
                """);
        assertEquals("prod", h.alias());
        assertEquals("10.0.0.5", h.hostName());
        assertEquals("deploy", h.user());
        assertEquals(2222, h.port());
        assertEquals(List.of(home + "/.ssh/prod_key"), h.identityFiles());
    }

    @Test
    void wildcardBlocksFillInWhatTheHostLeavesOut() {
        var h = only("""
                Host web
                    HostName web.example.com
                Host *
                    User admin
                    Port 2200
                """);
        assertEquals("admin", h.user());
        assertEquals(2200, h.port());
    }

    @Test
    void theFirstValueWinsLikeSsh() {
        var h = only("""
                Host *
                    User early
                Host db
                    User late
                """);
        assertEquals("early", h.user(), "ssh keeps the first obtained value");
    }

    @Test
    void wildcardAndNegatedPatternsAreNotHosts() {
        List<SshConfig.Host> hosts = parse("""
                Host *.internal !bastion.internal
                    User ops
                Host a b
                    HostName shared.example.com
                """);
        assertEquals(List.of("a", "b"), hosts.stream().map(SshConfig.Host::alias).toList());
    }

    @Test
    void aNegatedPatternVetoesItsBlock() {
        assertFalse(SshConfig.matches(List.of("*", "!secret"), "secret"));
        assertTrue(SshConfig.matches(List.of("*", "!secret"), "public"));
        assertTrue(SshConfig.matches(List.of("web?"), "WEB1"), "host matching ignores case");
    }

    @Test
    void matchBlocksNeverApply() {
        var h = only("""
                Host box
                Match host box
                    User nobody
                """);
        assertNull(h.user());
    }

    @Test
    void equalsSignsAndQuotesParse() {
        var h = only("""
                Host=quoted
                  IdentityFile="/keys/with space"
                  User = someone
                """);
        assertEquals("someone", h.user());
        assertEquals(List.of("/keys/with space"), h.identityFiles());
    }

    @Test
    void commentsAndKeywordCaseAreIgnored() {
        var h = only("""
                # a comment
                HOST lab
                    hostname lab.local
                """);
        assertEquals("lab.local", h.hostName());
    }

    @Test
    void includesAreFollowedRelativeToTheSshDirectory() throws Exception {
        Path conf = Files.createDirectories(home.resolve(".ssh/conf.d"));
        Files.writeString(conf.resolve("work.conf"), "Host work\n  HostName work.example.com\n");
        Files.writeString(conf.resolve("home.conf"), "Host nas\n  HostName nas.local\n");
        List<SshConfig.Host> hosts = parse("Include conf.d/*.conf\nHost own\n");
        assertEquals(Set.of("work", "nas", "own"),
                Set.copyOf(hosts.stream().map(SshConfig.Host::alias).toList()));
    }

    @Test
    void identityTokensExpand() {
        var h = only("""
                Host t
                    HostName t.example.com
                    User root
                    IdentityFile %d/.ssh/%h-%r_%u
                """);
        assertEquals(List.of(home + "/.ssh/t.example.com-root_me"), h.identityFiles());
    }

    @Test
    void aMissingConfigFileReadsAsNoHosts() {
        assertEquals(List.of(), SshConfig.load(home.resolve(".ssh/config"), home, "me"));
    }

    // ---- as a site ----

    @Test
    void theSiteIsNamedAfterTheAliasAndDialsTheHostName() {
        var site = only("Host prod\n  HostName 10.0.0.5\n  User deploy\n")
                .toSite(home, "me", p -> false);
        assertEquals("prod", site.name());
        assertEquals(Protocol.SFTP, site.protocol());
        assertEquals("10.0.0.5", site.host());
        assertEquals("deploy", site.user());
        assertEquals(22, site.port());
    }

    @Test
    void withoutHostNameOrUserTheAliasAndLocalAccountStandIn() {
        var site = only("Host plain\n").toSite(home, "me", p -> false);
        assertEquals("plain", site.host());
        assertEquals("me", site.user());
    }

    @Test
    void theFirstExistingIdentityFileBecomesTheKey() {
        var host = only("""
                Host k
                    IdentityFile /missing/key
                    IdentityFile /present/key
                """);
        var site = host.toSite(home, "me", p -> p.equals(Path.of("/present/key")));
        assertEquals("/present/key", site.keyPath());
    }

    @Test
    void withoutAnIdentityFileOpenSshDefaultKeysAreTried() {
        Path rsa = home.resolve(".ssh/id_rsa");
        var site = only("Host d\n").toSite(home, "me", rsa::equals);
        assertEquals(rsa.toString(), site.keyPath());
    }

    @Test
    void noKeyAnywhereMeansPasswordAuth() {
        assertNull(only("Host pw\n").toSite(home, "me", p -> false).keyPath());
    }
}
