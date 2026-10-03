package dock.kit.connect;

/**
 * One auth-model row of the connect dialog's picker. The flags drive the
 * shell-owned credential widgets (password field, key file + passphrase,
 * agent hint) so no protocol knowledge leaks into the shell; the label is
 * what the combo shows and what saved dialogs restore.
 */
public record AuthMode(String label, boolean password, boolean key, boolean agent,
                       boolean noCredentials) {

    public static final AuthMode PASSWORD =
            new AuthMode("Password", true, false, false, false);
    public static final AuthMode KEY =
            new AuthMode("Private key file", false, true, false, false);
    public static final AuthMode AGENT =
            new AuthMode("SSH agent (YubiKey)", false, false, true, false);

    /** SMB's "Guest" and WebDAV/FTP's "Anonymous" share the shape. */
    public static AuthMode noCredentials(String label) {
        return new AuthMode(label, false, false, false, true);
    }
}
