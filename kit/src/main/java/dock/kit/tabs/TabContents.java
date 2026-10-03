package dock.kit.tabs;

import dock.core.session.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * The tab-content frontends present at runtime, discovered through
 * ServiceLoader. The shell asks for the default content of a fresh
 * session, or for another provider that supports it (the terminal).
 */
public final class TabContents {

    private static final List<TabContentProvider> ALL;

    static {
        List<TabContentProvider> found = new ArrayList<>();
        for (TabContentProvider p : ServiceLoader.load(TabContentProvider.class)) {
            found.add(p);
        }
        ALL = List.copyOf(found);
    }

    private TabContents() { }

    public static List<TabContentProvider> all() { return ALL; }

    /** The default content provider for a session, or null when none
     *  supports it. */
    public static TabContentProvider defaultFor(Session session) {
        TabContentProvider fallback = null;
        for (TabContentProvider p : ALL) {
            if (!p.supports(session)) continue;
            if (p.isDefault()) return p;
            if (fallback == null) fallback = p;
        }
        return fallback;
    }

    /** Another provider that supports the session (the terminal overlay),
     *  or null. */
    public static TabContentProvider overlayFor(Session session) {
        for (TabContentProvider p : ALL) {
            if (!p.isDefault() && p.supports(session)) return p;
        }
        return null;
    }
}
