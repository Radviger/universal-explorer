package dock.kit.tabs;

/**
 * What the shell needs from a session tab beyond showing it: the View
 * menu and transfer-finish refresh drive through these, so the shell
 * stays ignorant of the concrete frontend.
 */
public interface SessionTab {

    /** The tab's title (the preferred name or the endpoint). */
    String title();

    /** Releases the session's resources when the tab closes. */
    void close();

    /** Re-reads pane listings after transfers touched them. */
    void refreshAfterTransfers();

    /** Whether the local pane is folded away (the Explorer layout). */
    boolean localPaneHidden();

    /** Folds the local pane away — or seats it back in the split. */
    void setLocalPaneHidden(boolean hidden);

    /** Whether the remote pane is folded away. */
    boolean remotePaneHidden();

    /** Folds the remote pane away — or seats it back in the split. */
    void setRemotePaneHidden(boolean hidden);

    /** Pushes View-menu settings (hidden files, dot-prefix rule) in. */
    void applyViewSettings();
}
