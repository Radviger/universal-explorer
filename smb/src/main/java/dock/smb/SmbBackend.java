package dock.smb;

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
 * The SMB leg of the connection machinery. Registered through
 * META-INF/services; the shell never imports this class.
 */
public final class SmbBackend implements ProtocolBackend {

    @Override public Protocol protocol() { return Protocol.SMB; }

    @Override
    public Session dial(Site site, SecretSource secrets) throws IOException {
        char[] password = null;
        if (!site.guest()) {
            password = secrets.load(site.secretTarget());
            if (password == null) throw new MissingSecretException(site.secretTarget());
        }
        try {
            var spec = new SmbSessions.SmbSpec(
                    site.host(), site.port(), site.domain(), site.user(),
                    password, site.guest(), site.initialPath());
            return SmbSessions.connect(spec);
        } finally {
            if (password != null) Arrays.fill(password, ' ');
        }
    }

    @Override public String glyph() { return Glyphs.WINDOWS; }

    @Override
    public String secondaryText(Site site) {
        String login = site.guest() ? "guest"
                : (site.domain() == null || site.domain().isEmpty() ? ""
                        : site.domain() + "\\") + site.user();
        return "\\\\" + site.host() + (site.port() == 445 ? "" : ":" + site.port())
                + "  ·  " + login;
    }

    @Override
    public String glyphOf(FileSystem fs) {
        return fs instanceof SmbFs ? Glyphs.WINDOWS : null;
    }
}
