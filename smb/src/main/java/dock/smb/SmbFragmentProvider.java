package dock.smb;

import dock.core.config.Protocol;
import dock.kit.connect.ConnectionFragment;
import dock.kit.connect.ConnectionFragmentProvider;

/** ServiceLoader registration for SMB's connect-form fragment. */
public final class SmbFragmentProvider implements ConnectionFragmentProvider {
    @Override public Protocol protocol() { return Protocol.SMB; }
    @Override public ConnectionFragment create() { return new SmbFragment(); }
}
