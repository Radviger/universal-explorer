package dock.smb;

import dock.core.config.Protocol;
import dock.core.config.Site;
import dock.kit.connect.AuthMode;
import dock.kit.connect.ConnectionFragment;
import dock.kit.connect.ConnectForm;
import java.util.List;
import javax.swing.JTextField;

/**
 * SMB's contribution to the connect dialog: the domain row, the
 * guest/anonymous auth model, and the opening-share path row.
 */
public final class SmbFragment implements ConnectionFragment {

    private static final AuthMode GUEST = AuthMode.noCredentials("Guest (no credentials)");
    private static final AuthMode[] MODES = {AuthMode.PASSWORD, GUEST};

    private final JTextField domainField = new JTextField();

    @Override public AuthMode[] authModes() { return MODES.clone(); }

    @Override
    public AuthMode authModeFor(Site site) {
        return site.guest() ? GUEST : AuthMode.PASSWORD;
    }

    @Override
    public List<LabeledRow> rows() {
        return List.of(new LabeledRow("Domain", domainField));
    }

    @Override
    public String pathLabel() { return "Opening share"; }

    @Override
    public String pathPlaceholder() { return "e.g. Public — empty lists all shares"; }

    @Override
    public String validate(AuthMode mode, ConnectForm form) {
        if (mode.password() && form.user().isBlank()) {
            return "User is required for password authentication.";
        }
        return null;
    }

    @Override
    public Object buildSpec(ConnectForm form) {
        boolean guest = form.authMode().noCredentials();
        char[] password = guest || form.password().length == 0 ? null : form.password();
        return new SmbSessions.SmbSpec(
                form.host(), form.port(), domainField.getText().trim(),
                guest ? null : form.user(), password, guest, form.path());
    }

    @Override
    public Site siteFromForm(String name, AuthMode mode, ConnectForm form) {
        boolean guest = mode.noCredentials();
        return new Site(name, Protocol.SMB, form.host(), form.port(),
                guest ? "" : form.user(), null, false,
                domainField.getText().trim(), form.path(), guest, false, 0, null, null);
    }

    @Override
    public void fill(Site site, ConnectForm form) {
        domainField.setText(site.domain() == null ? "" : site.domain());
        form.setPath(site.initialPath() == null ? "" : site.initialPath());
    }

    // ---- test hooks ----

    public boolean domainRowVisibleForTest() {
        return domainField.isVisible();
    }

    public void setDomainForTest(String domain) {
        domainField.setText(domain);
    }
}
