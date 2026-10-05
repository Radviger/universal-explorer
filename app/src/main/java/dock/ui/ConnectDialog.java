package dock.ui;

import dock.kit.FontRegistry;
import dock.kit.Glyphs;
import dock.kit.Tokens;
import dock.kit.connect.AuthMode;
import dock.kit.connect.ConnectionFragment;
import dock.kit.connect.ConnectForm;
import dock.kit.connect.Fragments;
import dock.core.config.Protocol;
import dock.core.config.Site;
import dock.core.config.Sites;
import dock.core.secrets.CredentialManager;
import dock.core.spi.Backends;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * New-session dialog: common chrome only — name, protocol, host/port,
 * user, the shared credential block (password, key file, agent), and the
 * opening path row. Everything protocol-specific (scheme/TLS toggles,
 * domain, auth models, validation, the shape that gets dialed) is
 * contributed by the selected protocol's {@link ConnectionFragment},
 * shipped inside its backend module.
 */
public final class ConnectDialog extends JDialog implements ConnectForm {

    /** Receives a freshly connected session and the site that dialed it
     *  (the site carries the preferred name; an unsaved quick connect
     *  passes its form-built site — nothing was persisted for it, so the
     *  path memory self-neutralizes on it). */
    public interface Connected {
        void connected(dock.core.session.Session session, dock.core.config.Site site);
    }

    /** Receives the persisted site after an edit-form Save (nothing dials). */
    public interface Saved {
        void saved(dock.core.config.Site site);
    }

    private final Connected onConnected;
    private final Saved onSave;
    /** The entry an edit form modifies; null when the form creates one. */
    private final Site editSite;

    private final JTextField nameField = new JTextField();
    private final JComboBox<String> protocolCombo = new JComboBox<>(
            Backends.all().stream()
                    .sorted(Comparator.comparingInt(b -> b.protocol().ordinal()))
                    .map(b -> b.protocol().label())
                    .toArray(String[]::new));
    private final JTextField hostField = new JTextField();
    private JPanel hostRow;
    private final List<JComponent> hostPrefix = new ArrayList<>();
    private final JTextField portField = new JTextField(
            String.valueOf(Backends.all().isEmpty() ? 22
                    : Backends.all().get(0).protocol().defaultPort()));
    private final JTextField userField = new JTextField(System.getProperty("user.name", ""));
    private final JComboBox<String> authCombo = new JComboBox<>();
    private JLabel authLabel;
    private final JPasswordField passwordField = new JPasswordField();
    private final JCheckBox savePassword = savePasswordBox();
    private final JTextField keyField = new JTextField();
    private final JPasswordField passphraseField = new JPasswordField();
    private final JTextField shareField = new JTextField();
    private JLabel passwordLabel;
    private JLabel keyLabel;
    private JLabel passphraseLabel;
    private JLabel shareLabel;
    private JPanel keyRow;
    private JLabel agentHint;
    private final JCheckBox saveSession = new JCheckBox("Save session");
    private final JLabel errorLabel = new JLabel();
    private static final Color ERROR_RED = new Color(0xE5484D);
    private static final Color SUCCESS_GREEN = new Color(0x30A46C);
    private final JButton connectButton = new JButton("Connect");
    private final JButton testButton = new JButton("Test Connection");

    /** Names this OS's store; without one the option shows but cannot be had. */
    private static JCheckBox savePasswordBox() {
        if (CredentialManager.available()) {
            return new JCheckBox("Save password in " + CredentialManager.storeName());
        }
        JCheckBox box = new JCheckBox("Saving passwords is not supported on this system");
        box.setEnabled(false);
        return box;
    }
    private String idleText = "Connect";
    private String busyText = "Connecting…";

    /** The protocol leg currently installed; swapped by applyProtocol. */
    private ConnectionFragment fragment;
    private Protocol appliedProtocol;
    private JPanel formPanel;
    private final List<JComponent> fragmentRowInputs = new ArrayList<>();
    /** True once anything but the prefill heuristic has filled the user
     *  row — after that the text is the user's (or a saved site's). */
    private boolean userEdited;
    private boolean adjustingUser;

    public ConnectDialog(Frame owner, Connected onConnected) {
        this(owner, null, onConnected, null);
    }

    /** Edit mode for a connect: fields prefilled from the saved site, and
     *  the button dials — this is the missing-secret prompt path, not an
     *  edit. */
    public ConnectDialog(Frame owner, Site edit, Connected onConnected) {
        this(owner, edit, onConnected, null);
    }

    /** Edit mode, save-only: the button reads "Save", the entry is
     *  modified in place — a rename replaces the old name and moves its
     *  secret — and nothing dials. */
    public ConnectDialog(Frame owner, Site edit, Saved onSave) {
        this(owner, edit, null, onSave);
    }

    private ConnectDialog(Frame owner, Site edit, Connected onConnected, Saved onSave) {
        super(owner, edit == null ? "New session" : "Edit session", true);
        this.onConnected = onConnected;
        this.onSave = onSave;
        this.editSite = edit;
        setLayout(new BorderLayout());
        userField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { touched(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { touched(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { touched(); }
            private void touched() {
                if (!adjustingUser) userEdited = true;
            }
        });
        // Registered before fill(): setting the protocol in edit mode must
        // rebuild the form and auth model before the auth selection is
        // applied (the scheme/TLS pickers' own port-follow rules are wired
        // by their fragments in installed()).
        protocolCombo.addActionListener(e -> applyProtocol());
        authCombo.addActionListener(e -> applyAuthMode());
        appliedProtocol = protocol();
        fragment = Fragments.of(appliedProtocol).create();
        formPanel = buildForm();
        add(formPanel, BorderLayout.CENTER);
        add(buildButtons(), BorderLayout.SOUTH);
        if (onSave != null) {
            idleText = "Save";
            busyText = "Saving…";
            connectButton.setText(idleText);
            // An edit saves by definition — the checkbox is create-only.
            saveSession.setVisible(false);
        }
        fragment.installed(this);
        authCombo.setModel(authModel(fragment));
        if (edit != null) fill(edit);
        applyProtocol();
        pack();
        setMinimumSize(new Dimension(460, getHeight()));
        setLocationRelativeTo(owner);
        getRootPane().setDefaultButton(connectButton);
    }

    private JPanel buildForm() {
        fragmentRowInputs.clear();
        hostPrefix.clear();
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(Tokens.GAP_4, Tokens.GAP_4,
                Tokens.GAP_3, Tokens.GAP_4));
        var gc = new GridBagConstraints();
        gc.insets = new Insets(Tokens.GAP_1, Tokens.GAP_1, Tokens.GAP_1, Tokens.GAP_2);
        gc.anchor = GridBagConstraints.LINE_START;
        int row = 0;

        field(form, gc, row++, "Session name", nameField);
        field(form, gc, row++, "Protocol", combo(protocolCombo));

        hostField.putClientProperty("JTextField.placeholderText", "hostname or IP");
        hostRow = new JPanel();
        hostRow.setLayout(new BoxLayout(hostRow, BoxLayout.X_AXIS));
        hostRow.setOpaque(false);
        for (JComponent prefix : fragment.hostPrefix()) {
            hostPrefix.add(prefix);
            hostRow.add(prefix);
            hostRow.add(Box.createHorizontalStrut(Tokens.GAP_1));
        }
        hostRow.add(hostField);
        gc.gridx = 0; gc.gridy = row; form.add(label("Host"), gc);
        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1;
        form.add(hostRow, gc);
        portField.setPreferredSize(new Dimension(70, portField.getPreferredSize().height));
        gc.gridx = 2; gc.fill = GridBagConstraints.NONE; gc.weightx = 0;
        form.add(portField, gc);
        row++;

        for (ConnectionFragment.LabeledRow extra : fragment.rows()) {
            JLabel caption = field(form, gc, row++, extra.caption(), extra.input());
            fragmentRowInputs.add(extra.input());
        }

        field(form, gc, row++, fragment.userLabel(), userField);
        authLabel = field(form, gc, row++, "Authentication", combo(authCombo));

        agentHint = new JLabel("Keys are offered by the Windows OpenSSH agent — "
                + "plug in your YubiKey and add it with ssh-add.");
        agentHint.setFont(FontRegistry.ui());
        agentHint.setForeground(muted());
        gc.gridx = 1; gc.gridy = row; gc.gridwidth = 2;
        gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1;
        form.add(agentHint, gc);
        gc.gridwidth = 1; gc.weightx = 0;
        row++;

        passwordField.setPreferredSize(hostField.getPreferredSize());
        passwordLabel = field(form, gc, row++, fragment.passwordLabel(), passwordField);
        gc.gridx = 1; gc.gridy = row; gc.gridwidth = 2;
        form.add(savePassword, gc);
        gc.gridwidth = 1;
        row++;

        keyField.putClientProperty("JTextField.placeholderText", "C:\\Users\\you\\.ssh\\id_ed25519");
        keyLabel = label("Key file");
        gc.gridx = 0; gc.gridy = row; form.add(keyLabel, gc);
        keyRow = new JPanel();
        keyRow.setLayout(new BoxLayout(keyRow, BoxLayout.X_AXIS));
        keyRow.setOpaque(false);
        keyRow.add(keyField);
        JButton browse = new JButton(Glyphs.icon(Glyphs.SEARCH, Tokens.ICON_SMALL,
                ConnectDialog::muted));
        browse.setToolTipText("Browse…");
        browse.putClientProperty("JButton.buttonType", "borderless");
        browse.setRolloverEnabled(true);
        browse.addActionListener(e -> {
            var fc = new JFileChooser();
            fc.setFileHidingEnabled(false);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                keyField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        keyRow.add(Box.createHorizontalStrut(Tokens.GAP_1));
        keyRow.add(browse);
        gc.gridx = 1; gc.gridwidth = 2; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1;
        form.add(keyRow, gc);
        gc.gridwidth = 1;
        row++;
        passphraseLabel = field(form, gc, row++, "Key passphrase", passphraseField);

        String pathLabel = fragment.pathLabel();
        if (pathLabel != null) {
            shareField.putClientProperty("JTextField.placeholderText", fragment.pathPlaceholder());
            shareLabel = field(form, gc, row++, pathLabel, shareField);
        } else {
            // No path concept: an invisible row keeps shareField wired so
            // setPath from an edit-mode fill is harmless.
            shareLabel = label("");
            shareField.setVisible(false);
            shareLabel.setVisible(false);
        }

        errorLabel.setForeground(ERROR_RED);
        errorLabel.setFont(FontRegistry.ui());
        errorLabel.setVisible(false);
        gc.gridx = 0; gc.gridy = row; gc.gridwidth = 3; gc.fill = GridBagConstraints.HORIZONTAL;
        form.add(errorLabel, gc);

        saveSession.setSelected(true);
        return form;
    }

    private Protocol protocol() {
        return Protocol.of((String) protocolCombo.getSelectedItem());
    }

    /**
     * Switches the form between protocols: the fragment's rows replace
     * whatever the previous protocol contributed, the auth model changes,
     * and a still-default port follows the protocol's own default.
     */
    private void applyProtocol() {
        Protocol p = protocol();
        var provider = Fragments.of(p);
        if (provider == null) return;
        // Rebuilding the form for the protocol already on screen (the
        // ctor's final call after edit-mode fill) would clobber the
        // selected auth method; only a real switch rebuilds.
        if (p != appliedProtocol) {
            appliedProtocol = p;
            fragment = provider.create();
            // An untouched user row follows the protocol's login heuristic —
            // the OS account is a plausible SFTP/WebDAV login, never an
            // access key ID.
            if (!userEdited) setUserText(fragment.defaultUser());
            fragmentRowInputs.clear();
            remove(formPanel);
            formPanel = buildForm();
            add(formPanel, BorderLayout.CENTER);
            fragment.installed(this);
            authCombo.setModel(authModel(fragment));
            // A port that still holds some other protocol's default follows
            // the switch; an edited port is never silently swapped.
            for (Protocol other : Protocol.values()) {
                if (other != p && isDefaultPort(portField.getText().trim(), other.defaultPort())) {
                    portField.setText(String.valueOf(p.defaultPort()));
                    break;
                }
            }
        }
        applyAuthMode();
    }

    private static javax.swing.DefaultComboBoxModel<String> authModel(ConnectionFragment f) {
        String[] labels = new String[f.authModes().length];
        for (int i = 0; i < labels.length; i++) labels[i] = f.authModes()[i].label();
        return new javax.swing.DefaultComboBoxModel<>(labels);
    }

    /** The AuthMode whose label the picker currently shows. */
    private AuthMode selectedMode() {
        String selected = (String) authCombo.getSelectedItem();
        for (AuthMode m : fragment.authModes()) {
            if (m.label().equals(selected)) return m;
        }
        return fragment.authModes()[0];
    }

    /**
     * Shows only the inputs belonging to the selected auth method and
     * packs the dialog to the shorter form.
     */
    private void applyAuthMode() {
        AuthMode mode = selectedMode();
        // A single-model protocol (S3) has nothing to pick — the row
        // hides entirely instead of offering a one-item dropdown.
        boolean pickable = fragment.authModes().length > 1;
        authLabel.setVisible(pickable);
        authCombo.setVisible(pickable);
        passwordLabel.setVisible(mode.password());
        passwordField.setVisible(mode.password());
        savePassword.setVisible(mode.password());
        keyLabel.setVisible(mode.key());
        keyRow.setVisible(mode.key());
        passphraseLabel.setVisible(mode.key());
        passphraseField.setVisible(mode.key());
        agentHint.setVisible(mode.agent());
        fragment.applyAuthMode(mode);
        boolean pathRow = fragment.pathLabel() != null;
        shareLabel.setVisible(pathRow);
        shareField.setVisible(pathRow);
        userField.setEditable(!mode.noCredentials());
        userField.setEnabled(!mode.noCredentials());
        revalidate();
        pack();
        repaint();
    }

    private JComponent buildButtons() {
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0,
                        UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(Tokens.GAP_3, Tokens.GAP_4,
                        Tokens.GAP_3, Tokens.GAP_4)));
        buttons.add(saveSession);
        buttons.add(Box.createHorizontalGlue());

        connectButton.putClientProperty("FlatLaf.style", "arc: " + Tokens.ARC + ";");
        connectButton.addActionListener(e -> {
            if (onSave != null) save();
            else connect();
        });
        testButton.addActionListener(e -> testConnection());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        buttons.add(testButton);
        buttons.add(Box.createHorizontalStrut(Tokens.GAP_2));
        buttons.add(cancel);
        buttons.add(Box.createHorizontalStrut(Tokens.GAP_2));
        buttons.add(connectButton);
        return buttons;
    }

    private void fill(Site s) {
        protocolCombo.setSelectedItem(s.protocol().label());
        nameField.setText(s.name());
        hostField.setText(s.host());
        portField.setText(String.valueOf(s.port()));
        userField.setText(s.user());
        fragment.fill(s, this);
        authCombo.setSelectedItem(fragment.authModeFor(s).label());
        String pw = CredentialManager.load(s.secretTarget());
        if (pw != null) {
            passwordField.setText(pw);
            savePassword.setSelected(true);
        }
        String pass = CredentialManager.load(s.keySecretTarget());
        if (pass != null) passphraseField.setText(pass);
    }

    /** First blocking problem with the form, or null when it is ready —
     *  the shared gate in front of both verdicts. */
    private String formProblem() {
        if (hostField.getText().trim().isEmpty()) return "Host is required.";
        String problem = fragment.validate(selectedMode(), this);
        if (problem != null) return problem;
        try {
            Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            return "Port must be a number.";
        }
        return null;
    }

    /** The form as a dial: the site and the secrets it reads. The arrays
     *  are the caller's to wipe once the dial returns. */
    private record FormDial(Protocol protocol, AuthMode mode, Site site,
                            char[] password, char[] passphrase) {
        dock.core.session.Session dial() throws Exception {
            return Backends.of(protocol).dial(site, target ->
                    target.endsWith("/key") ? passphrase : password);
        }

        void wipe() {
            if (password != null) java.util.Arrays.fill(password, ' ');
            if (passphrase != null) java.util.Arrays.fill(passphrase, ' ');
        }
    }

    private FormDial formDial() {
        AuthMode mode = selectedMode();
        String host = hostField.getText().trim();
        // Only password mode reads the password field; the grabbed arrays
        // back the SecretSource the backend's dial reads through, and are
        // wiped the moment the dial returns.
        char[] password = mode.password() ? passwordField.getPassword() : null;
        char[] passphrase = passphraseField.getPassword();
        String user = userField.getText().trim();
        String name = nameField.getText().isBlank()
                ? fragment.defaultName(user, host)
                : nameField.getText().trim();
        return new FormDial(protocol(), mode, fragment.siteFromForm(name, mode, this),
                password, passphrase);
    }

    /** Dials with the form as it stands and hangs up at once: the verdict
     *  lands in the form, which stays open either way. Nothing is saved. */
    private void testConnection() {
        String problem = formProblem();
        if (problem != null) {
            error(problem);
            return;
        }
        FormDial dial = formDial();
        setBusy(true);
        connectButton.setText(idleText);
        testButton.setText("Testing…");
        Thread.ofVirtual().name("dock-test-connect").start(() -> {
            String failure = null;
            try {
                dial.dial().close();
            } catch (Exception ex) {
                failure = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            } finally {
                dial.wipe();
            }
            final String verdict = failure;
            SwingUtilities.invokeLater(() -> {
                setBusy(false);
                testButton.setText("Test Connection");
                if (verdict == null) success("Connection works.");
                else error(verdict);
            });
        });
    }

    private void connect() {
        String problem = formProblem();
        if (problem != null) {
            error(problem);
            return;
        }
        FormDial dial = formDial();
        AuthMode mode = dial.mode();
        Site site = dial.site();
        char[] password = dial.password();
        char[] passphrase = dial.passphrase();

        setBusy(true);
        Thread.ofVirtual().name("dock-connect").start(() -> {
            try {
                dock.core.session.Session session = dial.dial();
                Site saved = site;
                if (saveSession.isVisible() && saveSession.isSelected()) {
                    try {
                        saved = persist(site);
                        saveCredentials(saved, mode, password, passphrase);
                    } catch (Exception saveError) {
                        // Saving must never kill the session; surface as a toast later.
                        System.err.println("could not save session: " + saveError);
                    }
                }
                final dock.core.session.Session opened = session;
                final Site done = saved;
                SwingUtilities.invokeLater(() -> {
                    dispose();
                    onConnected.connected(opened, done);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setBusy(false);
                    error(ex.getMessage() == null ? ex.toString() : ex.getMessage());
                });
            } finally {
                dial.wipe();
            }
        });
    }

    /** The edit form's verdict: persist the modified entry and close —
     *  nothing dials (connecting stays on the row's double-click). */
    private void save() {
        String problem = formProblem();
        if (problem != null) {
            error(problem);
            return;
        }
        AuthMode mode = selectedMode();
        String host = hostField.getText().trim();
        char[] password = mode.password() ? passwordField.getPassword() : null;
        char[] passphrase = passphraseField.getPassword();
        String user = userField.getText().trim();
        String name = nameField.getText().isBlank()
                ? fragment.defaultName(user, host)
                : nameField.getText().trim();
        Site site = fragment.siteFromForm(name, mode, this);

        setBusy(true);
        Thread.ofVirtual().name("dock-save-session").start(() -> {
            try {
                Site saved = persist(site);
                saveCredentials(saved, mode, password, passphrase);
                final Site done = saved;
                SwingUtilities.invokeLater(() -> {
                    dispose();
                    onSave.saved(done);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setBusy(false);
                    error(ex.getMessage() == null ? ex.toString() : ex.getMessage());
                });
            } finally {
                if (password != null) java.util.Arrays.fill(password, ' ');
                if (passphrase != null) java.util.Arrays.fill(passphrase, ' ');
            }
        });
    }

    /**
     * Writes the form's site as the stored entry's replacement, carrying
     * the previous entry's memory (recency, path memory, playback shelf)
     * across the edit — and across a rename, which also moves the
     * Credential Manager secrets to their new targets.
     */
    private Site persist(Site form) throws java.io.IOException {
        String key = editSite != null ? editSite.name() : form.name();
        Site stored = null;
        for (Site s : Sites.load()) {
            if (s.name().equals(key)) {
                stored = s;
                break;
            }
        }
        Site merged = form
                .withLastUsed(stored == null ? 0 : stored.lastUsed())
                .withPaths(stored == null ? null : stored.lastLocalPath(),
                        stored == null ? null : stored.lastRemotePath())
                .withPlayback(stored == null ? null : stored.playback());
        Sites.replace(key, merged);
        if (editSite != null && !editSite.name().equals(merged.name())) {
            moveSecret(editSite.secretTarget(), merged.secretTarget());
            moveSecret(editSite.keySecretTarget(), merged.keySecretTarget());
        }
        return merged;
    }

    /** A rename re-targets the stored secrets; none existed, nothing moves. */
    private static void moveSecret(String from, String to) {
        try {
            String secret = CredentialManager.load(from);
            if (secret != null) {
                CredentialManager.save(to, secret);
                CredentialManager.delete(from);
            }
        } catch (Exception ignored) {
            // The secret stays under its old target; the entry itself is saved.
        }
    }

    /** The opted-in credential saves; a refusal must not kill an
     *  otherwise-complete connect or save. */
    private void saveCredentials(Site site, AuthMode mode, char[] password, char[] passphrase) {
        try {
            if (mode.password() && savePassword.isSelected()
                    && password != null && password.length > 0) {
                CredentialManager.save(site.secretTarget(), new String(password));
            }
            if (mode.key() && passphrase.length > 0) {
                CredentialManager.save(site.keySecretTarget(), new String(passphrase));
            }
        } catch (Exception saveError) {
            System.err.println("could not save session: " + saveError);
        }
    }

    // ---- ConnectForm (the fragment's view of this dialog) ----

    @Override public String host() { return hostField.getText().trim(); }
    @Override public int port() { return Integer.parseInt(portField.getText().trim()); }
    @Override public void setPort(int port) { portField.setText(String.valueOf(port)); }
    @Override public boolean portIs(int candidate) {
        return isDefaultPort(portField.getText().trim(), candidate);
    }
    @Override public String user() { return userField.getText().trim(); }
    @Override public char[] password() { return passwordField.getPassword(); }
    @Override public char[] passphrase() { return passphraseField.getPassword(); }
    @Override public String keyPath() { return keyField.getText().trim(); }
    @Override public String path() { return shareField.getText().trim(); }
    @Override public void setPath(String path) { shareField.setText(path); }
    @Override public void setKeyPath(String keyPath) { keyField.setText(keyPath); }
    @Override public AuthMode authMode() { return selectedMode(); }

    private static boolean isDefaultPort(String text, int port) {
        try {
            return Integer.parseInt(text) == port;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Programmatic user-row text — never marks the row user-edited. */
    private void setUserText(String text) {
        adjustingUser = true;
        try {
            userField.setText(text);
        } finally {
            adjustingUser = false;
        }
    }

    private void setBusy(boolean b) {
        connectButton.setEnabled(!b);
        connectButton.setText(b ? busyText : idleText);
        testButton.setEnabled(!b);
        hostField.setEnabled(!b);
        portField.setEnabled(!b);
        protocolCombo.setEnabled(!b);
        errorLabel.setVisible(!b);
        // Re-applies the guest lock on the user field and friends.
        applyAuthMode();
    }

    private void error(String message) {
        errorLabel.setForeground(ERROR_RED);
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        pack();
    }

    /** A good verdict in the same slot the errors use. */
    private void success(String message) {
        errorLabel.setForeground(SUCCESS_GREEN);
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        pack();
    }

    // ---- quick connect ----

    private static final java.util.regex.Pattern QUICK_SPEC = java.util.regex.Pattern.compile(
            "^(?:([\\w.\\-]+)@)?([A-Za-z0-9][A-Za-z0-9.\\-]*)(?::(\\d+))?$");

    /**
     * Parses {@code [user@]host[:port]}. Returns {@code {user|null, host,
     * port|null}}, or null when the text is not a bare host spec.
     */
    public static String[] parseQuickSpec(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty() || t.contains(" ") || t.contains("/")) return null;
        var m = QUICK_SPEC.matcher(t);
        if (!m.matches()) return null;
        // A bare word like "nas" is ambiguous with a filter query; require a
        // dot, a colon, an at-sign, or all-digits (an IP) to treat it as a host.
        if (!t.contains(".") && !t.contains(":") && !t.contains("@")) return null;
        return new String[]{m.group(1), m.group(2), m.group(3)};
    }

    /** Prefills the form from a quick-connect spec (null parts keep defaults). */
    void prefillQuick(String user, String host, String port) {
        if (host != null && !host.isBlank()) hostField.setText(host);
        if (user != null && !user.isBlank()) userField.setText(user);
        if (port != null && !port.isBlank()) portField.setText(port);
        nameField.setText("");
        hostField.requestFocusInWindow();
    }

    // ---- test hooks ----

    /** The protocol leg currently installed (scheme/TLS hooks live on it). */
    public ConnectionFragment fragmentForTest() {
        return fragment;
    }

    public javax.swing.JComboBox<String> protocolForTest() {
        return protocolCombo;
    }

    public javax.swing.JComboBox<String> authForTest() {
        return authCombo;
    }

    /** True when the auth picker row is on screen — single-model
     *  protocols (S3) hide it. */
    public boolean authPickerVisible() {
        return authLabel.isVisible() && authCombo.isVisible();
    }

    /** The shared user row's current text. */
    public String userForTest() {
        return userField.getText();
    }

    public boolean passwordInputsVisible() {
        return passwordLabel.isVisible() && passwordField.isVisible() && savePassword.isVisible();
    }

    public boolean keyInputsVisible() {
        return keyLabel.isVisible() && keyRow.isVisible()
                && passphraseLabel.isVisible() && passphraseField.isVisible();
    }

    public boolean agentHintVisible() {
        return agentHint.isVisible();
    }

    public boolean shareRowVisible() {
        return shareLabel.isVisible() && shareField.isVisible();
    }

    /** True when every row the fragment contributes is on screen. */
    public boolean fragmentRowsVisible() {
        for (JComponent c : fragmentRowInputs) {
            if (!c.isVisible()) return false;
        }
        return true;
    }

    /** SMB shape: fragment rows (domain) + share row visible. */
    public boolean smbInputsVisible() {
        return fragmentRowsVisible() && !fragmentRowInputs.isEmpty() && shareRowVisible();
    }

    public String pathLabelForTest() {
        return shareLabel.getText();
    }

    public void setPathForTest(String text) {
        shareField.setText(text);
    }

    /** The dialog's primary button — "Connect" to dial, "Save" to edit. */
    public JButton primaryButtonForTest() {
        return connectButton;
    }

    public JButton testButtonForTest() {
        return testButton;
    }

    public void setPasswordForTest(String text) {
        passwordField.setText(text);
    }

    /** The verdict line's text while it shows, else null (tests). */
    public String statusForTest() {
        return errorLabel.isVisible() ? errorLabel.getText() : null;
    }

    public void setNameForTest(String text) {
        nameField.setText(text);
    }

    public void setHostAndUserForTest(String host, String user) {
        hostField.setText(host);
        userField.setText(user);
    }

    public String portForTest() {
        return portField.getText();
    }

    public void setPortForTest(String text) {
        portField.setText(text);
    }

    /** Test hook: the spec the current form state would dial (no dialing). */
    public Object specForTest() {
        if (portField.getText().trim().isEmpty()) {
            portField.setText(String.valueOf(protocol().defaultPort()));
        }
        return fragment.buildSpec(this);
    }

    // ---- small form helpers ----

    /** Adds a caption/input row; the caption is returned so auth-mode
     *  switching can hide the pair together. */
    private JLabel field(JPanel form, GridBagConstraints gc, int row, String caption, JComponent input) {
        gc.gridx = 0; gc.gridy = row; gc.gridwidth = 1; gc.fill = GridBagConstraints.NONE;
        JLabel captionLabel = label(caption);
        form.add(captionLabel, gc);
        gc.gridx = 1; gc.gridwidth = 2; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1;
        form.add(input, gc);
        gc.gridwidth = 1; gc.weightx = 0;
        return captionLabel;
    }

    private JComponent combo(JComboBox<String> box) {
        box.setPreferredSize(hostField.getPreferredSize());
        return box;
    }

    private JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(FontRegistry.ui());
        return l;
    }

    static java.awt.Color muted() {
        java.awt.Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : UIManager.getColor("Label.foreground");
    }
}
