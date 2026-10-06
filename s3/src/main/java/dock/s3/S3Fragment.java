package dock.s3;

import dock.core.config.Protocol;
import dock.core.config.Site;
import dock.kit.connect.AuthMode;
import dock.kit.connect.ConnectionFragment;
import dock.kit.connect.ConnectForm;
import java.awt.Dimension;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JTextField;

/**
 * S3's contribution to the connect dialog: the https/http scheme picker
 * in the host row (with its port-follow rule), the region row that
 * scopes the signature, and the opening-bucket path row. The shared
 * credential rows carry S3's own captions — "Access key ID" and
 * "Secret access key". The "AWS profile" model keeps no secret at all:
 * the user row names a profile of ~/.aws, whose keys load at every dial.
 */
public final class S3Fragment implements ConnectionFragment {

    static final AuthMode ACCESS_KEY = new AuthMode("Access key", true, false, false, false);
    /** Keys held outside the app, like SSH's agent: the agent-shaped model. */
    static final AuthMode PROFILE = new AuthMode("AWS profile", false, false, true, false);
    private static final AuthMode[] MODES = {ACCESS_KEY, PROFILE};

    private final JComboBox<String> schemeCombo = new JComboBox<>(new String[]{"https", "http"});
    private final JTextField regionField = new JTextField();

    public S3Fragment() {
        schemeCombo.setPreferredSize(new Dimension(84, schemeCombo.getPreferredSize().height));
        regionField.putClientProperty("JTextField.placeholderText", "us-east-1");
    }

    @Override
    public List<JComponent> hostPrefix() {
        return List.of(schemeCombo);
    }

    @Override public AuthMode[] authModes() { return MODES.clone(); }

    @Override public AuthMode authModeFor(Site site) {
        return site.useAgent() ? PROFILE : ACCESS_KEY;
    }

    @Override public String userLabel(AuthMode mode) {
        return mode.agent() ? "AWS profile" : userLabel();
    }

    @Override public String agentHint() {
        return "Keys are read from ~/.aws at every connect — nothing is stored here.";
    }

    @Override public void applyAuthMode(AuthMode mode) {
        regionField.putClientProperty("JTextField.placeholderText",
                mode.agent() ? "from the profile, else us-east-1" : "us-east-1");
        regionField.repaint();
    }

    @Override
    public List<LabeledRow> rows() {
        return List.of(new LabeledRow("Region", regionField));
    }

    @Override public String pathLabel() { return "Opening bucket"; }

    @Override public String pathPlaceholder() { return "e.g. backups — empty lists all buckets"; }

    @Override public String userLabel() { return "Access key ID"; }

    /** An access key ID is never the OS account — the row starts empty. */
    @Override public String defaultUser() { return ""; }

    @Override public String passwordLabel() { return "Secret access key"; }

    @Override
    public String validate(AuthMode mode, ConnectForm form) {
        // The secret prompts at dial like every other protocol's password —
        // an edit form must save without re-entering credentials.
        if (form.user().isBlank()) {
            return mode.agent() ? "The AWS profile name is required."
                    : "The access key ID is required.";
        }
        return null;
    }

    @Override
    public Object buildSpec(ConnectForm form) {
        return new S3Sessions.S3Spec(form.host(), form.port(), isHttps(),
                regionField.getText().trim(), form.user(), form.password(), form.path());
    }

    @Override
    public Site siteFromForm(String name, AuthMode mode, ConnectForm form) {
        return new Site(name, Protocol.S3, form.host(), form.port(), form.user(), null,
                mode.agent(), null, form.path(), false, isHttps(), 0, null, null, null,
                regionOrNull());
    }

    @Override
    public void fill(Site site, ConnectForm form) {
        schemeCombo.setSelectedItem(site.secure() ? "https" : "http");
        regionField.setText(site.region() == null ? "" : site.region());
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

    private String regionOrNull() {
        String r = regionField.getText().trim();
        return r.isEmpty() ? null : r;
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

    public String regionForTest() {
        return regionField.getText();
    }

    public void setRegionForTest(String region) {
        regionField.setText(region);
    }
}
