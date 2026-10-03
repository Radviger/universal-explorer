package dock.sftp;

import dock.core.config.Protocol;
import dock.core.config.Site;
import dock.kit.connect.AuthMode;
import dock.kit.connect.ConnectionFragment;
import dock.kit.connect.ConnectForm;

/**
 * SFTP's contribution to the connect dialog: the password / key file /
 * agent auth models and the shape it dials. The key file + passphrase
 * widgets themselves are the shell's shared credential block; this
 * fragment only decides when they show and what they mean.
 */
public final class SftpFragment implements ConnectionFragment {

    private static final AuthMode[] MODES =
            {AuthMode.PASSWORD, AuthMode.KEY, AuthMode.AGENT};

    @Override public AuthMode[] authModes() {
        return MODES.clone();
    }

    @Override
    public AuthMode authModeFor(Site site) {
        if (site.useAgent()) return AuthMode.AGENT;
        if (site.keyPath() != null && !site.keyPath().isBlank()) return AuthMode.KEY;
        return AuthMode.PASSWORD;
    }

    @Override
    public String validate(AuthMode mode, ConnectForm form) {
        if (!mode.noCredentials() && form.user().isBlank()) {
            return "User is required.";
        }
        if (mode.key() && (form.keyPath() == null || form.keyPath().isBlank())) {
            return "Key file is required for key authentication.";
        }
        return null;
    }

    @Override
    public String defaultName(String user, String host) {
        return user + "@" + host;
    }

    @Override
    public Object buildSpec(ConnectForm form) {
        AuthMode mode = form.authMode();
        boolean viaKey = mode.key();
        String keyPath = viaKey ? form.keyPath() : null;
        // Mirrors the old dialog spec: the password field is ignored for
        // key/agent modes, the passphrase field for everything but key.
        char[] password = viaKey || mode.agent() ? null : form.password();
        char[] pass = viaKey ? form.passphrase() : null;
        return new SshSessions.ConnectionSpec(form.user(), form.host(), form.port(),
                password, keyPath, pass, mode.agent());
    }

    @Override
    public Site siteFromForm(String name, AuthMode mode, ConnectForm form) {
        return new Site(name, Protocol.SFTP, form.host(), form.port(), form.user(),
                mode.key() ? form.keyPath() : null, mode.agent(), null, null,
                false, false, 0, null, null);
    }

    @Override
    public void fill(Site site, ConnectForm form) {
        form.setKeyPath(site.keyPath() == null ? "" : site.keyPath());
    }
}
