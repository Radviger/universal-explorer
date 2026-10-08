package dock.webdav;

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
 * The WebDAV leg of the connection machinery. Registered through
 * META-INF/services; the shell never imports this class.
 */
public final class WebDavBackend implements ProtocolBackend {

    @Override public Protocol protocol() { return Protocol.WEBDAV; }

    @Override
    public Session dial(Site site, SecretSource secrets) throws IOException {
        char[] password = null;
        if (!site.guest()) {
            password = secrets.load(site.secretTarget());
            if (password == null) throw new MissingSecretException(site.secretTarget());
        }
        try {
            var spec = new WebDavSessions.WebDavSpec(
                    site.host(), site.port(), site.secure(), site.initialPath(),
                    site.guest() ? null : site.user(), password);
            return WebDavSessions.connect(spec);
        } finally {
            if (password != null) Arrays.fill(password, ' ');
        }
    }

    @Override public String glyph() { return Glyphs.GLOBE; }

    @Override
    public String secondaryText(Site site) {
        int defaultPort = site.secure() ? 443 : 80;
        String url = (site.secure() ? "https://" : "http://") + site.host()
                + (site.port() == defaultPort ? "" : ":" + site.port())
                + (site.initialPath() == null ? "" : site.initialPath());
        return url;
    }

    @Override
    public String glyphOf(FileSystem fs) {
        return fs instanceof WebDavFs ? Glyphs.GLOBE : null;
    }
}
