package dock.s3;

import dock.core.config.Protocol;
import dock.kit.connect.ConnectionFragment;
import dock.kit.connect.ConnectionFragmentProvider;

/** ServiceLoader registration for S3's connect-form fragment. */
public final class S3FragmentProvider implements ConnectionFragmentProvider {
    @Override public Protocol protocol() { return Protocol.S3; }
    @Override public ConnectionFragment create() { return new S3Fragment(); }
}
