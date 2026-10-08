package dock.ftp;

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
 * The FTP/FTPS leg of the connection machinery. Registered through
 * META-INF/services; the shell never imports this class.
 */
public final class FtpBackend implements ProtocolBackend {

    @Override public Protocol protocol() { return Protocol.FTP; }

    @Override
    public Session dial(Site site, SecretSource secrets) throws IOException {
        char[] password = null;
        if (!site.guest()) {
            password = secrets.load(site.secretTarget());
            if (password == null) throw new MissingSecretException(site.secretTarget());
        }
        try {
            var spec = new FtpSessions.FtpSpec(
                    site.host(), site.port(), site.secure(), site.initialPath(),
                    site.guest() ? null : site.user(), password);
            return FtpSessions.connect(spec);
        } finally {
            if (password != null) Arrays.fill(password, ' ');
        }
    }

    @Override public String glyph() { return Glyphs.CLOUD; }

    @Override
    public String secondaryText(Site site) {
        int defaultPort = site.secure() ? 990 : 21;
        String url = (site.secure() ? "ftps://" : "ftp://") + site.host()
                + (site.port() == defaultPort ? "" : ":" + site.port())
                + (site.initialPath() == null || site.initialPath().isEmpty()
                        ? "" : site.initialPath());
        return url;
    }

    @Override
    public String glyphOf(FileSystem fs) {
        return fs instanceof FtpFs ? Glyphs.CLOUD : null;
    }
}
