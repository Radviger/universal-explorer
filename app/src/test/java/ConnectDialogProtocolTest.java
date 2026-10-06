import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import dock.core.config.Protocol;
import dock.core.config.Site;
import dock.ftp.FtpFragment;
import dock.s3.S3Fragment;
import dock.ui.ConnectDialog;
import dock.webdav.WebDavFragment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The connect dialog's protocol switcher: SFTP keeps its auth machinery,
 * SMB swaps in domain/share/guest, ports follow their protocol's default
 * unless customized, and each protocol's fragment builds the right spec
 * shape. The scheme/TLS hooks live on the fragments themselves.
 */
class ConnectDialogProtocolTest {

    @BeforeAll
    static void boot() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    private static WebDavFragment webDav(ConnectDialog d) {
        return assertInstanceOf(WebDavFragment.class, d.fragmentForTest());
    }

    private static FtpFragment ftp(ConnectDialog d) {
        return assertInstanceOf(FtpFragment.class, d.fragmentForTest());
    }

    @Test
    void sftpIsTheDefaultShape() {
        ConnectDialog d = new ConnectDialog(null, (session, name) -> {});
        assertEquals("SFTP", d.protocolForTest().getSelectedItem());
        assertEquals("22", d.portForTest());
        assertFalse(d.smbInputsVisible(), "domain/share belong to SMB only");
        assertEquals("Connect", d.primaryButtonForTest().getText(),
                "the new-session form dials");
        assertTrue(d.authPickerVisible(), "SFTP offers three auth models");
        assertTrue(d.passwordInputsVisible());
        assertFalse(d.agentHintVisible());
        d.dispose();
    }

    @Test
    void smbSwapsPortsFieldsAndAuthModel() {
        ConnectDialog d = new ConnectDialog(null, (session, name) -> {});
        d.protocolForTest().setSelectedItem("SMB");
        assertEquals("445", d.portForTest(), "default SFTP port follows the protocol");
        assertTrue(d.smbInputsVisible());
        assertTrue(d.passwordInputsVisible(), "password stays the default SMB auth");
        assertFalse(d.keyInputsVisible(), "no SSH key rows on SMB");
        assertEquals(java.util.List.of("Password", "Guest (no credentials)"),
                comboItems(d));
        assertEquals("Guest (no credentials)", d.authForTest().getItemAt(1));

        d.protocolForTest().setSelectedItem("SFTP");
        assertEquals("22", d.portForTest(), "switching back restores the SFTP port");
        assertFalse(d.smbInputsVisible());
        assertEquals(3, d.authForTest().getItemCount(), "password/key/agent return");
        d.dispose();
    }

    @Test
    void customPortsSurviveTheProtocolSwitch() {
        ConnectDialog d = new ConnectDialog(null, (session, name) -> {});
        d.setPortForTest("2222");
        d.protocolForTest().setSelectedItem("SMB");
        assertEquals("2222", d.portForTest(), "an edited port is never silently swapped");
        d.dispose();
    }

    @Test
    void guestAuthHidesTheSecretInputs() {
        ConnectDialog d = new ConnectDialog(null, (session, name) -> {});
        d.protocolForTest().setSelectedItem("SMB");
        d.authForTest().setSelectedItem("Guest (no credentials)");
        assertFalse(d.passwordInputsVisible());
        assertFalse(d.keyInputsVisible());
        d.dispose();
    }

    @Test
    void buildsAnSmbSpecFromTheForm() {
        ConnectDialog d = new ConnectDialog(null, (session, name) -> {});
        d.protocolForTest().setSelectedItem("SMB");
        var spec = assertInstanceOf(dock.smb.SmbSessions.SmbSpec.class,
                d.specForTest());
        assertEquals(445, spec.port());
        assertFalse(spec.guest());
        d.dispose();
    }

    @Test
    void buildsAnSftpSpecFromTheForm() {
        ConnectDialog d = new ConnectDialog(null, (session, name) -> {});
        Object spec = d.specForTest();
        var sftp = assertInstanceOf(dock.sftp.SshSessions.ConnectionSpec.class, spec);
        assertEquals(22, sftp.port());
        d.dispose();
    }

    @Test
    void editingAnSmbSitePrefillsItsShape() {
        Site site = new Site("unit-nas", Protocol.SMB, "nas", 445, "root",
                null, false, "WORKGROUP", "Public", false, 0);
        ConnectDialog d = new ConnectDialog(null, site, (session, name) -> {});
        assertEquals("SMB", d.protocolForTest().getSelectedItem());
        assertTrue(d.smbInputsVisible());
        assertEquals("445", d.portForTest());
        assertEquals("Password", d.authForTest().getSelectedItem());
        d.dispose();
    }

    @Test
    void editingAGuestSiteSelectsGuest() {
        Site site = new Site("unit-box", Protocol.SMB, "router", 445, "",
                null, false, null, null, true, 0);
        ConnectDialog d = new ConnectDialog(null, site, (session, name) -> {});
        assertEquals("Guest (no credentials)", d.authForTest().getSelectedItem());
        assertFalse(d.passwordInputsVisible());
        d.dispose();
    }

    @Test
    void webDavSwapsInSchemePathAndAuthModel() {
        ConnectDialog d = new ConnectDialog(null, (session, name) -> {});
        d.protocolForTest().setSelectedItem("WebDAV");
        assertEquals("443", d.portForTest(), "https is the WebDAV default");
        WebDavFragment dav = webDav(d);
        assertTrue(dav.schemeVisibleForTest());
        assertTrue(d.shareRowVisible());
        assertFalse(d.smbInputsVisible(), "no domain row on WebDAV");
        assertFalse(d.agentHintVisible());
        assertTrue(d.passwordInputsVisible(), "password stays the default WebDAV auth");
        assertEquals("Path", d.pathLabelForTest(), "the share row is reused as the base path");
        assertEquals(java.util.List.of("Password", "Anonymous (no credentials)"),
                comboItems(d));

        dav.setSchemeForTest("http");
        assertEquals("80", d.portForTest(), "the scheme swap carries the port default");
        dav.setSchemeForTest("https");
        assertEquals("443", d.portForTest());

        d.protocolForTest().setSelectedItem("SFTP");
        assertFalse(d.shareRowVisible(), "SFTP has no path row");
        assertEquals("22", d.portForTest());
        d.dispose();
    }

    @Test
    void buildsAWebDavSpecFromTheForm() {
        ConnectDialog d = new ConnectDialog(null, (session, name) -> {});
        d.protocolForTest().setSelectedItem("WebDAV");
        d.setHostAndUserForTest("nas.example", "root");
        d.setPathForTest("/dav");
        var spec = assertInstanceOf(dock.webdav.WebDavSessions.WebDavSpec.class,
                d.specForTest());
        assertEquals("nas.example", spec.host());
        assertEquals(443, spec.port());
        assertTrue(spec.secure(), "the scheme picker defaults to https");
        assertEquals("root", spec.user());
        assertEquals("/dav", spec.basePath());
        d.dispose();
    }

    @Test
    void editingAWebDavSitePrefillsItsShape() {
        Site site = new Site("unit-dav", Protocol.WEBDAV, "nas", 5006, "root",
                null, false, null, "/dav", false, true, 0);
        ConnectDialog d = new ConnectDialog(null, site, (session, name) -> {});
        assertEquals("WebDAV", d.protocolForTest().getSelectedItem());
        assertEquals("https", webDav(d).schemeForTest());
        assertEquals("5006", d.portForTest());
        assertEquals("Path", d.pathLabelForTest());
        assertTrue(d.shareRowVisible());
        assertEquals("Password", d.authForTest().getSelectedItem());
        d.dispose();
    }

    @Test
    void ftpSwapsInTlsPathAndAuthModel() {
        ConnectDialog d = new ConnectDialog(null, (session, name) -> {});
        d.protocolForTest().setSelectedItem("FTP");
        assertEquals("21", d.portForTest(), "plain FTP is the default");
        FtpFragment f = ftp(d);
        assertTrue(f.tlsVisibleForTest());
        assertTrue(d.shareRowVisible());
        assertFalse(d.smbInputsVisible(), "no domain row on FTP");
        assertTrue(d.passwordInputsVisible());
        assertEquals("Path", d.pathLabelForTest());
        assertEquals(java.util.List.of("Password", "Anonymous (no credentials)"),
                comboItems(d));

        f.setTlsForTest(true);
        assertEquals("990", d.portForTest(), "TLS on carries the implicit FTPS port");
        f.setTlsForTest(false);
        assertEquals("21", d.portForTest());
        d.dispose();
    }

    @Test
    void buildsAnFtpSpecFromTheForm() {
        ConnectDialog d = new ConnectDialog(null, (session, name) -> {});
        d.protocolForTest().setSelectedItem("FTP");
        d.setHostAndUserForTest("nas.example", "root");
        d.setPathForTest("/downloads");
        var spec = assertInstanceOf(dock.ftp.FtpSessions.FtpSpec.class, d.specForTest());
        assertEquals("nas.example", spec.host());
        assertEquals(21, spec.port());
        assertFalse(spec.secure(), "TLS is opt-in");
        assertEquals("root", spec.user());
        assertEquals("/downloads", spec.basePath());
        d.dispose();
    }

    @Test
    void editingAnFtpSitePrefillsItsShape() {
        Site site = new Site("unit-ftp", Protocol.FTP, "nas", 2121, "root",
                null, false, null, "/downloads", false, true, 0);
        ConnectDialog d = new ConnectDialog(null, site, (session, name) -> {});
        assertEquals("FTP", d.protocolForTest().getSelectedItem());
        assertTrue(ftp(d).tlsForTest(), "the saved TLS flag prefills the toggle");
        assertEquals("2121", d.portForTest());
        assertEquals("Path", d.pathLabelForTest());
        assertTrue(d.shareRowVisible());
        assertEquals("Password", d.authForTest().getSelectedItem());
        d.dispose();
    }

    @Test
    void s3SwapsInSchemeRegionBucketAndRelabeledCredentials() {
        ConnectDialog d = new ConnectDialog(null, (session, name) -> {});
        d.protocolForTest().setSelectedItem("S3");
        assertEquals("443", d.portForTest(), "https is the S3 default");
        S3Fragment s3 = assertInstanceOf(S3Fragment.class, d.fragmentForTest());
        assertTrue(s3.schemeVisibleForTest());
        assertTrue(d.fragmentRowsVisible(), "the region row is on screen");
        assertTrue(d.shareRowVisible());
        assertFalse(d.agentHintVisible());
        assertEquals("Opening bucket", d.pathLabelForTest());
        assertEquals("Access key ID", s3.userLabel(), "the shared user row speaks S3");
        assertEquals("Secret access key", s3.passwordLabel(), "so does the shared password row");
        assertTrue(d.authPickerVisible(), "access key or AWS profile");
        assertEquals(java.util.List.of("Access key", "AWS profile"), comboItems(d));
        assertEquals("Access key ID", d.userCaptionForTest());
        assertTrue(d.userForTest().isEmpty(), "the OS login is not an access key prefill");
        assertTrue(d.passwordInputsVisible());

        s3.setSchemeForTest("http");
        assertEquals("80", d.portForTest(), "the scheme swap carries the port default");
        s3.setSchemeForTest("https");
        assertEquals("443", d.portForTest());

        d.protocolForTest().setSelectedItem("SFTP");
        assertEquals("22", d.portForTest());
        assertEquals(System.getProperty("user.name", ""), d.userForTest(),
                "the untouched prefill returns with the protocol's own heuristic");
        d.dispose();
    }

    @Test
    void aTypedLoginSurvivesTheProtocolSwitch() {
        ConnectDialog d = new ConnectDialog(null, (session, name) -> {});
        d.protocolForTest().setSelectedItem("S3");
        assertTrue(d.userForTest().isEmpty(), "S3 starts the row empty");
        d.setHostAndUserForTest("minio.example.com", "AKID123");
        d.protocolForTest().setSelectedItem("SFTP");
        assertEquals("AKID123", d.userForTest(),
                "a typed login is never clobbered by the prefill heuristic");
        d.dispose();
    }

    @Test
    void buildsAnS3SpecFromTheForm() {
        ConnectDialog d = new ConnectDialog(null, (session, name) -> {});
        d.protocolForTest().setSelectedItem("S3");
        S3Fragment s3 = assertInstanceOf(S3Fragment.class, d.fragmentForTest());
        d.setHostAndUserForTest("minio.example.com", "docktest");
        d.setPathForTest("backups");
        s3.setRegionForTest("auto");
        var spec = assertInstanceOf(dock.s3.S3Sessions.S3Spec.class, d.specForTest());
        assertEquals("minio.example.com", spec.host());
        assertEquals(443, spec.port());
        assertTrue(spec.secure(), "the scheme picker defaults to https");
        assertEquals("auto", spec.region());
        assertEquals("docktest", spec.accessKey());
        assertEquals("backups", spec.bucket());

        s3.setSchemeForTest("http");
        spec = assertInstanceOf(dock.s3.S3Sessions.S3Spec.class, d.specForTest());
        assertEquals(80, spec.port());
        assertFalse(spec.secure());
        d.dispose();
    }

    @Test
    void editingAnS3SitePrefillsItsShape() {
        Site site = new Site("unit-s3", Protocol.S3, "s3.example.com", 443, "AKID",
                null, false, null, "backups", false, true, 0, null, null, null, "eu-west-1");
        ConnectDialog d = new ConnectDialog(null, site, (session, name) -> {});
        assertEquals("S3", d.protocolForTest().getSelectedItem());
        S3Fragment s3 = assertInstanceOf(S3Fragment.class, d.fragmentForTest());
        assertEquals("https", s3.schemeForTest());
        assertEquals("eu-west-1", s3.regionForTest(), "the saved region prefills");
        assertEquals("AKID", d.userForTest(), "a saved site's access key prefills after the clear");
        assertEquals("443", d.portForTest());
        assertEquals("Opening bucket", d.pathLabelForTest());
        assertTrue(d.shareRowVisible());
        assertEquals("Access key", d.authForTest().getSelectedItem());
        d.dispose();
    }

    @Test
    void s3ProfileModeNamesAProfileAndKeepsNoSecret() {
        ConnectDialog d = new ConnectDialog(null, (session, name) -> {});
        d.protocolForTest().setSelectedItem("S3");
        d.authForTest().setSelectedItem("AWS profile");
        assertEquals("AWS profile", d.userCaptionForTest(), "the user row names the profile");
        assertFalse(d.passwordInputsVisible(), "no secret is typed or stored");
        assertTrue(d.agentHintVisible());
        d.setHostAndUserForTest("s3.eu-west-1.amazonaws.com", "work");
        Site site = d.fragmentForTest().siteFromForm("AWS work",
                d.fragmentForTest().authModes()[1], d);
        assertTrue(site.useAgent(), "a profile site's keys live outside the app");
        assertEquals("work", site.user());

        d.authForTest().setSelectedItem("Access key");
        assertEquals("Access key ID", d.userCaptionForTest());
        assertTrue(d.passwordInputsVisible());
        d.dispose();
    }

    @Test
    void editingAProfileSiteRestoresProfileMode() {
        Site site = new Site("AWS work", Protocol.S3, "s3.amazonaws.com", 443, "work",
                null, true, null, null, false, true, 0);
        ConnectDialog d = new ConnectDialog(null, site, (session, name) -> {});
        assertEquals("AWS profile", d.authForTest().getSelectedItem());
        assertEquals("work", d.userForTest());
        assertEquals("AWS profile", d.userCaptionForTest());
        d.dispose();
    }

    private static java.util.List<String> comboItems(ConnectDialog d) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < d.authForTest().getItemCount(); i++) {
            out.add(d.authForTest().getItemAt(i));
        }
        return out;
    }
}
