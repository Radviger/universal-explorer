package dock.kit.connect;

import dock.core.config.Protocol;
import java.util.EnumMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * The connect-form fragments present at runtime, discovered through
 * ServiceLoader alongside the wire-side backends. A protocol shows up in
 * the connect dialog only when both registrations exist.
 */
public final class Fragments {

    private static final Map<Protocol, ConnectionFragmentProvider> BY_PROTOCOL;

    static {
        Map<Protocol, ConnectionFragmentProvider> m = new EnumMap<>(Protocol.class);
        for (ConnectionFragmentProvider p : ServiceLoader.load(ConnectionFragmentProvider.class)) {
            m.put(p.protocol(), p);
        }
        BY_PROTOCOL = Map.copyOf(m);
    }

    private Fragments() { }

    /** The fragment factory for a protocol, or null when this build has none. */
    public static ConnectionFragmentProvider of(Protocol protocol) {
        return BY_PROTOCOL.get(protocol);
    }
}
