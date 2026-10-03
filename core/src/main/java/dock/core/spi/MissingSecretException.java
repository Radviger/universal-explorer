package dock.core.spi;

import java.io.IOException;

/**
 * A required secret is absent from the {@link SecretSource}. Not an error
 * the user should read as text: the shell answers it by opening the
 * site's edit dialog instead of showing a failure.
 */
public final class MissingSecretException extends IOException {

    /** The credential target that came back empty (never the secret). */
    public MissingSecretException(String credentialTarget) {
        super("no secret stored under " + credentialTarget);
    }
}
