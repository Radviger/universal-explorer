package dock.webdav;

import dock.core.config.Protocol;
import dock.core.config.Site;
import dock.kit.connect.AuthMode;
import dock.kit.connect.ConnectionFragment;
import dock.kit.connect.ConnectForm;
import java.awt.Dimension;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JComponent;

/**
 * WebDAV's contribution to the connect dialog: the https/http scheme
 * picker in the host row (with its port-follow rule) and the anonymous
 * auth model.
 */
public final class WebDavFragment implements ConnectionFragment {

    private static final AuthMode ANONYMOUS =
            AuthMode.noCredentials("Anonymous (no credentials)");
    private static final AuthMode[] MODES = {AuthMode.PASSWORD, ANONYMOUS};

    private final JComboBox<String> schemeCombo = new JComboBox<>(new String[]{"https", "http"});

    public WebDavFragment() {
        schemeCombo.setPreferredSize(new Dimension(84, schemeCombo.getPreferredSize().height));
    }

    @Override
    public List<JComponent> hostPrefix() {
        return List.of(schemeCombo);
    }

    @Override public AuthMode[] authModes() { return MODES.clone(); }

    @Override
    public AuthMode authModeFor(Site site) {
        return site.guest() ? ANONYMOUS : AuthMode.PASSWORD;
    }

    @Override
    public String pathLabel() { return "Path"; }

    @Override
    public String pathPlaceholder() { return "/dav (optional)"; }

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
        boolean https = schemeCombo.getSelectedItem().equals("https");
        char[] password = anonymous || form.password().length == 0 ? null : form.password();
        return new WebDavSessions.WebDavSpec(
                form.host(), form.port(), https, form.path(),
                anonymous ? null : form.user(), password);
    }

    @Override
    public Site siteFromForm(String name, AuthMode mode, ConnectForm form) {
        boolean anonymous = mode.noCredentials();
        return new Site(name, Protocol.WEBDAV, form.host(), form.port(),
                anonymous ? "" : form.user(), null, false, null, form.path(),
                anonymous, isHttps(), 0, null, null);
    }

    @Override
    public void fill(Site site, ConnectForm form) {
        schemeCombo.setSelectedItem(site.secure() ? "https" : "http");
        form.setPath(site.initialPath() == null ? "" : site.initialPath());
    }

    @Override
    public void installed(ConnectForm form) {
        // A still-default port follows the scheme switch; an edited port
        // is never silently swapped.
        schemeCombo.addActionListener(e -> {
            if (schemeCombo.getSelectedItem() == null) return;
            boolean https = schemeCombo.getSelectedItem().equals("https");
            if (form.portIs(https ? 80 : 443)) {
                form.setPort(https ? 443 : 80);
            }
        });
    }

    private boolean isHttps() {
        return schemeCombo.getSelectedItem() != null
                && schemeCombo.getSelectedItem().equals("https");
    }

    // ---- test hooks ----

    public String schemeForTest() {
        return (String) schemeCombo.getSelectedItem();
    }

    public void setSchemeForTest(String scheme) {
        schemeCombo.setSelectedItem(scheme);
    }

    public boolean schemeVisibleForTest() {
        return schemeCombo.isVisible();
    }
}
