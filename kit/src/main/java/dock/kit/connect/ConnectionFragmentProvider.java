package dock.kit.connect;

import dock.core.config.Protocol;

/**
 * Supplies a protocol's {@link ConnectionFragment}. Backends register an
 * implementation via {@code META-INF/services}; the connect dialog asks
 * {@link Fragments} for the fragment of the protocol currently selected.
 * Kept apart from the wire-side SPI in dock.core so the headless core
 * never sees a swing type.
 */
public interface ConnectionFragmentProvider {

    Protocol protocol();

    ConnectionFragment create();
}
