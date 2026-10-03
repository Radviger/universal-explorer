package dock.core.session;

import dock.core.fs.FileSystem;
import java.io.IOException;

/**
 * One open remote connection, protocol-neutral. The UI layer (tabs,
 * panes, transfers) talks to this; dialing and protocol specifics stay
 * in the per-protocol implementations.
 */
public interface Session extends AutoCloseable {

    /** The live filesystem this session is browsing. */
    FileSystem fs();

    /**
     * Re-establishes the connection and returns the fresh filesystem.
     * The previous one is the caller's to close after swapping it in.
     */
    FileSystem reconnect() throws IOException;

    /**
     * Registers a loss callback (invoked off the EDT, possibly for the
     * session's own close — callers filter by their closing flag).
     * Implementations keep the hook live across {@link #reconnect()}.
     * Backends without loss notification may ignore it.
     */
    void onConnectionLost(Runnable callback);

    /** False when the session carries no reconnect recipe (ad-hoc sessions). */
    boolean reconnectable();

    @Override
    void close();
}
