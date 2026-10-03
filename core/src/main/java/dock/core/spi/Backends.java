package dock.core.spi;

import dock.core.config.Protocol;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * The protocol backends present at runtime, discovered through
 * ServiceLoader. A protocol whose module is not on the classpath (S3
 * until its backend exists) simply has no entry here — the connect
 * dialog's protocol picker is built from this registry, not from
 * {@link Protocol#values()}.
 */
public final class Backends {

    private static final Map<Protocol, ProtocolBackend> BY_PROTOCOL;
    private static final List<ProtocolBackend> ALL;

    static {
        Map<Protocol, ProtocolBackend> m = new EnumMap<>(Protocol.class);
        for (ProtocolBackend b : ServiceLoader.load(ProtocolBackend.class)) {
            m.put(b.protocol(), b);
        }
        BY_PROTOCOL = Map.copyOf(m);
        ALL = List.copyOf(new ArrayList<>(m.values()));
    }

    private Backends() { }

    /** The backend for a protocol, or null when this build has none. */
    public static ProtocolBackend of(Protocol protocol) {
        return BY_PROTOCOL.get(protocol);
    }

    /** Every registered backend (order follows the service files). */
    public static List<ProtocolBackend> all() {
        return ALL;
    }
}
