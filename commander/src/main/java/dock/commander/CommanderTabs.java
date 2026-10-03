package dock.commander;

import dock.core.session.Session;
import dock.kit.Glyphs;
import dock.kit.tabs.TabContent;
import dock.kit.tabs.TabContentProvider;
import javax.swing.JComponent;

/**
 * The dual-pane session browser — the default tab content for every
 * session. Registered through META-INF/services; the shell discovers it.
 */
public final class CommanderTabs implements TabContentProvider {

    @Override public String id() { return "commander"; }
    @Override public String title() { return "Commander"; }
    @Override public String glyph() { return Glyphs.FOLDER_OPEN; }
    @Override public boolean isDefault() { return true; }
    @Override public boolean supports(Session session) { return true; }

    @Override
    public TabContent create(Session session, String preferredName) {
        SessionView view = new SessionView(session, preferredName);
        return new TabContent(view.title(), session.fs().label(), view);
    }

    @Override
    public void close(java.awt.Component content) {
        if (content instanceof SessionView view) view.close();
    }
}
