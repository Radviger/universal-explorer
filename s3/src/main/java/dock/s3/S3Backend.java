package dock.s3;

import dock.core.config.AwsProfiles;
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
 * The S3 leg of the connection machinery. Registered through
 * META-INF/services; the shell never imports this class.
 */
public final class S3Backend implements ProtocolBackend {

    @Override public Protocol protocol() { return Protocol.S3; }

    @Override
    public Session dial(Site site, SecretSource secrets) throws IOException {
        if (site.useAgent()) return dialProfile(site);
        char[] secret = secrets.load(site.secretTarget());
        if (secret == null) throw new MissingSecretException(site.secretTarget());
        try {
            var spec = new S3Sessions.S3Spec(site.host(), site.port(), site.secure(),
                    site.region(), site.user(), secret, site.initialPath());
            return S3Sessions.connect(spec);
        } finally {
            Arrays.fill(secret, ' ');
        }
    }

    /** Keys from the AWS profile the site's user names, read fresh now. */
    private static Session dialProfile(Site site) throws IOException {
        String name = site.user();
        AwsProfiles.Profile profile = AwsProfiles.profile(name);
        if (profile == null) {
            throw new IOException("AWS profile '" + name + "' is not in ~/.aws/credentials or ~/.aws/config.");
        }
        if (!profile.hasKeys()) {
            throw new IOException("AWS profile '" + name + "' has no access keys"
                    + " (SSO, role and credential_process profiles are not supported yet).");
        }
        char[] secret = profile.secretAccessKey().toCharArray();
        try {
            String region = site.region() != null ? site.region() : profile.region();
            var spec = new S3Sessions.S3Spec(site.host(), site.port(), site.secure(), region,
                    profile.accessKeyId(), secret, site.initialPath(), profile.sessionToken());
            return S3Sessions.connect(spec);
        } finally {
            Arrays.fill(secret, ' ');
        }
    }

    @Override public String glyph() { return Glyphs.CLOUD; }

    @Override
    public String secondaryText(Site site) {
        // The access key ID stays off the home row — half of a key pair is
        // still credential material; like SFTP's tail, the line says how
        // the site authenticates, not with what.
        String text = site.host() + (site.port() == 443 ? "" : ":" + site.port());
        if (site.region() != null && !site.region().isBlank()) {
            text += "  ·  " + site.region();
        }
        return text + (site.useAgent() ? "  ·  profile " + site.user() : "  ·  access key");
    }

    @Override
    public String glyphOf(FileSystem fs) {
        return fs instanceof S3Fs ? Glyphs.CLOUD : null;
    }
}
