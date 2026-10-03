package dock.ftp;

import dock.core.config.Protocol;
import dock.core.config.Site;
import dock.kit.connect.AuthMode;
import dock.kit.connect.ConnectionFragment;
import dock.kit.connect.ConnectForm;
import java.util.List;
import javax.swing.JCheckBox;
import javax.swing.JComponent;

/**
 * FTP's contribution to the connect dialog: the TLS toggle in the host
 * row (implicit FTPS on 990 when on, explicit TLS keeps 21) and the
 * anonymous auth model.
 */
public final class FtpFragment implements ConnectionFragment {

    private static final AuthMode ANONYMOUS =
            AuthMode.noCredentials("Anonymous (no credentials)");
    private static final AuthMode[] MODES = {AuthMode.PASSWORD, ANONYMOUS};

    private final JCheckBox tlsCheck = new JCheckBox("TLS");

    @Override
    public List<JComponent> hostPrefix() {
        return List.of(tlsCheck);
    }

    @Override public AuthMode[] authModes() { return MODES.clone(); }

    @Override
    public AuthMode authModeFor(Site site) {
        return site.guest() ? ANONYMOUS : AuthMode.PASSWORD;
    }

    @Override
    public String pathLabel() { return "Path"; }

    @Override
    public String pathPlaceholder() { return "/downloads (optional)"; }

    @Override
    public String validate(AuthMode mode, ConnectForm form) {
        if (mode.password() && form.user().isBlank()) {
            return "User is required for password authentication.";
        }
        return null;
    }

    @Override
    public Object buildSpec(ConnectForm form) {
        boolean anonymous = form.authMode().noCredentials();
        char[] password = anonymous || form.password().length == 0 ? null : form.password();
        return new FtpSessions.FtpSpec(
                form.host(), form.port(), tlsCheck.isSelected(), form.path(),
                anonymous ? null : form.user(), password);
    }

    @Override
    public Site siteFromForm(String name, AuthMode mode, ConnectForm form) {
        boolean anonymous = mode.noCredentials();
        return new Site(name, Protocol.FTP, form.host(), form.port(),
                anonymous ? "" : form.user(), null, false, null, form.path(),
                anonymous, tlsCheck.isSelected(), 0, null, null);
    }

    @Override
    public void fill(Site site, ConnectForm form) {
        tlsCheck.setSelected(site.secure());
        form.setPath(site.initialPath() == null ? "" : site.initialPath());
    }

    @Override
    public void installed(ConnectForm form) {
        // ItemListener (not Action): programmatic setSelected from the
        // edit-mode prefill must move the port default too. TLS on:
        // implicit FTPS lives on 990; explicit TLS keeps port 21.
        tlsCheck.addItemListener(e -> {
            if (tlsCheck.isSelected()) {
                if (form.portIs(21)) form.setPort(990);
            } else if (form.portIs(990)) {
                form.setPort(21);
            }
        });
    }

    // ---- test hooks ----

    public boolean tlsForTest() {
        return tlsCheck.isSelected();
    }

    public void setTlsForTest(boolean on) {
        tlsCheck.setSelected(on);
    }

    public boolean tlsVisibleForTest() {
        return tlsCheck.isVisible();
    }
}
