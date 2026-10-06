package dock.kit.connect;

import dock.core.config.Site;
import java.util.List;
import javax.swing.JComponent;

/**
 * A backend's contribution to the connect dialog. Everything
 * protocol-specific about the form — the auth models it offers, its
 * extra widgets, its path row, its validation, and the shape it dials —
 * lives behind this interface inside the backend's own module; the
 * shell's dialog keeps only the common chrome (name, host, port, user,
 * the shared credential block, buttons).
 */
public interface ConnectionFragment {

    /** Components spliced into the host row before the host field (a
     *  scheme picker, a TLS toggle); empty for most protocols. */
    default List<JComponent> hostPrefix() { return List.of(); }

    /** Caption for the shared user row — S3 calls it the access key ID. */
    default String userLabel() { return "User"; }

    /** The user row's caption under one auth model — S3's profile mode
     *  names the profile there. */
    default String userLabel(AuthMode mode) { return userLabel(); }

    /** The line shown in place of the secret inputs while an agent-style
     *  model (keys held outside the app) is picked. */
    default String agentHint() {
        return "Keys are offered by the SSH agent — add them with ssh-add.";
    }

    /** Prefill for the shared user row while the user hasn't typed one
     *  (protocols whose login is rarely the OS account — S3's access
     *  keys — return an empty string). */
    default String defaultUser() { return System.getProperty("user.name", ""); }

    /** Caption for the shared password row — S3 calls it the secret key. */
    default String passwordLabel() { return "Password"; }

    /** The auth models the protocol offers, in picker order. */
    AuthMode[] authModes();

    /** The model a saved site should preselect in the picker. */
    AuthMode authModeFor(Site site);

    /** Labeled rows appended below the auth picker (SMB's domain); shown
     *  while the protocol is active unless {@link #applyAuthMode} hides
     *  them. */
    default List<LabeledRow> rows() { return List.of(); }

    /** Caption for the opening-path row, or null when the protocol has no
     *  such concept and the row hides entirely. */
    default String pathLabel() { return null; }

    /** Placeholder for the path field (used only when there is a row). */
    default String pathPlaceholder() { return ""; }

    /** React to a mode switch: show/hide the fragment's own rows. */
    default void applyAuthMode(AuthMode mode) { }

    /** The default session name when the name field is left blank. */
    default String defaultName(String user, String host) { return host; }

    /** First blocking problem with the form for this mode, or null when
     *  the form is ready to dial. */
    default String validate(AuthMode mode, ConnectForm form) { return null; }

    /** The spec the current form state would dial (never connects). The
     *  shell only passes it through — it is the tests' surface. */
    Object buildSpec(ConnectForm form);

    /** The saved-site shape of the current form state. */
    Site siteFromForm(String name, AuthMode mode, ConnectForm form);

    /** Prefill the fragment's own widgets (and the form hooks it owns:
     *  path, key path) from a saved site. */
    void fill(Site site, ConnectForm form);

    /** Called once when the dialog installs the fragment — the place to
     *  wire port-follow listeners against the form. */
    default void installed(ConnectForm form) { }

    record LabeledRow(String caption, JComponent input) { }
}
