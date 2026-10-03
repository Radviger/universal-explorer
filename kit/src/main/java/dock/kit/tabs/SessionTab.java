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

    boolean explorerMode();

    void setExplorerMode(boolean on);

    /** Pushes View-menu settings (hidden files, dot-prefix rule) in. */
    void applyViewSettings();
}
