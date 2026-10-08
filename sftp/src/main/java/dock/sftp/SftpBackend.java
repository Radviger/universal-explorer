package dock.sftp;

import dock.core.config.Protocol;
import dock.core.config.Site;
import dock.core.fs.FileSystem;
import dock.core.session.Session;
import dock.core.spi.MissingSecretException;
import dock.core.spi.ProtocolBackend;
import dock.core.spi.SecretSource;
import dock.kit.Glyphs;
import java.io.IOException;
import java.util.Arrays;

/**
 * The SFTP leg of the connection machinery: dialing from a saved site,
 * and the marks/labels the shell shows for it. Registered through
 * META-INF/services; the shell never imports this class.
 */
public final class SftpBackend implements ProtocolBackend {

    @Override public Protocol protocol() { return Protocol.SFTP; }

    @Override
    public Session dial(Site site, SecretSource secrets) throws IOException {
        char[] password = null;
        char[] passphrase = null;
        try {
            SshSessions.ConnectionSpec spec;
            if (site.useAgent()) {
                spec = new SshSessions.ConnectionSpec(
                        site.user(), site.host(), site.port(), null, null, null, true);
            } else {
                if (site.keyPath() == null) {
                    password = secrets.load(site.secretTarget());
                    if (password == null) throw new MissingSecretException(site.secretTarget());
                } else {
                    // A key without a passphrase is legitimate; a null here
                    // is not a missing secret.
                    passphrase = secrets.load(site.keySecretTarget());
                }
                spec = new SshSessions.ConnectionSpec(
                        site.user(), site.host(), site.port(), password,
                        site.keyPath(), passphrase, false);
            }
            SftpFs fs = SshSessions.connect(spec, HostKeys.verifier(), null);
            return new SftpSession(fs, spec);
        } finally {
            if (password != null) Arrays.fill(password, ' ');
            if (passphrase != null) Arrays.fill(passphrase, ' ');
        }
    }

    @Override public String glyph() { return Glyphs.SERVER; }

    @Override
    public String secondaryText(Site site) {
        return site.host() + (site.port() == 22 ? "" : ":" + site.port());
    }

    @Override
    public String glyphOf(FileSystem fs) {
        return fs instanceof SftpFs ? Glyphs.SERVER : null;
    }
}
