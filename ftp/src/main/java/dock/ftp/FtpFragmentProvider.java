package dock.ftp;

import dock.core.config.Protocol;
import dock.kit.connect.ConnectionFragment;
import dock.kit.connect.ConnectionFragmentProvider;

/** ServiceLoader registration for FTP's connect-form fragment. */
public final class FtpFragmentProvider implements ConnectionFragmentProvider {
    @Override public Protocol protocol() { return Protocol.FTP; }
    @Override public ConnectionFragment create() { return new FtpFragment(); }
}
