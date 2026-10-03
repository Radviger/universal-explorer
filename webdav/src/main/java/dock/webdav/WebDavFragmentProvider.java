package dock.webdav;

import dock.core.config.Protocol;
import dock.kit.connect.ConnectionFragment;
import dock.kit.connect.ConnectionFragmentProvider;

/** ServiceLoader registration for WebDAV's connect-form fragment. */
public final class WebDavFragmentProvider implements ConnectionFragmentProvider {
    @Override public Protocol protocol() { return Protocol.WEBDAV; }
    @Override public ConnectionFragment create() { return new WebDavFragment(); }
}
