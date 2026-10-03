package dock.sftp;

import dock.core.config.Protocol;
import dock.kit.connect.ConnectionFragment;
import dock.kit.connect.ConnectionFragmentProvider;

/** ServiceLoader registration for SFTP's connect-form fragment. */
public final class SftpFragmentProvider implements ConnectionFragmentProvider {
    @Override public Protocol protocol() { return Protocol.SFTP; }
    @Override public ConnectionFragment create() { return new SftpFragment(); }
}
