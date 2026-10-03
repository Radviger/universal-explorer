package dock.core.spi;

import dock.core.config.Protocol;
import dock.core.config.Site;
import dock.core.fs.FileSystem;
import dock.core.session.Session;
import java.io.IOException;

/**
 * One protocol's leg of the connection machinery. Backends live in their
 * own Gradle modules and register an implementation of this interface via
 * {@code META-INF/services}; the shell looks them up through
 * {@link Backends} and never compiles against a protocol's classes.
 */
public interface ProtocolBackend {

    /** The protocol this backend serves (its enum constant exists for the
     *  persisted site shape even before the backend module ships). */
    Protocol protocol();

    /**
     * Connects. Loads exactly the secrets it needs through {@code secrets}
     * and throws {@link MissingSecretException} when a required one is
     * absent — the caller answers that by opening the edit dialog, exactly
     * like a failed dial is answered with its message. Every other
     * failure's message must be user-readable; backends compose it from
     * their own readable-error helpers.
     */
    Session dial(Site site, SecretSource secrets) throws IOException;

    /** The launcher/path-head mark: a Symbols Nerd Font codepoint. */
    String glyph();

    /** The launcher's second line for a saved site of this protocol. */
    String secondaryText(Site site);

    /** This backend's glyph when {@code fs} is one of its filesystems,
     *  null otherwise (the caller falls back to a generic server mark). */
    String glyphOf(FileSystem fs);
}
