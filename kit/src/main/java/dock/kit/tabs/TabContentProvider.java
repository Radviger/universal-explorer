package dock.kit.tabs;

import dock.core.session.Session;
import java.awt.Component;
import java.io.IOException;
import javax.swing.JComponent;

/**
 * One tab-content type — the frontends register implementations (a session
 * browser, a terminal) via META-INF/services and the shell discovers them
 * through {@link TabContents}. The shell never imports a frontend class.
 */
public interface TabContentProvider {

    /** Stable id ("commander", "terminal"). */
    String id();

    /** Display name for menus/diagnostics. */
    String title();

    /** The provider's mark: a Symbols Nerd Font codepoint. */
    String glyph();

    /**
     * The default content for a session (what a fresh connection opens
     * as). At most one provider per session should claim this; the shell
     * picks the default first, any other supporting provider on request
     * (the terminal for an open session).
     */
    default boolean isDefault() { return false; }

    boolean supports(Session session);

    /** Creates the tab content; {@code preferredName} is the saved site's
     *  name (blank falls back to the provider's own titling). */
    TabContent create(Session session, String preferredName) throws IOException;

    /** Releases the content's resources when its tab closes. */
    default void close(java.awt.Component content) { }

    /** The provider's shell-without-a-session (the local shell), or null. */
    default JComponent localShell() throws IOException { return null; }
}
