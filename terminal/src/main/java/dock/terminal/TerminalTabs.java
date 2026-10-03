package dock.terminal;

import dock.core.session.Session;
import dock.kit.Glyphs;
import dock.kit.tabs.TabContent;
import dock.kit.tabs.TabContentProvider;
import dock.sftp.SftpFs;
import java.io.IOException;
import javax.swing.JComponent;

/**
 * The embedded terminal — an overlay tab for SFTP sessions (it rides the
 * SSH connection's shell channel) and a local shell without a session.
 * Registered through META-INF/services; the shell discovers it.
 */
public final class TerminalTabs implements TabContentProvider {

    @Override public String id() { return "terminal"; }
    @Override public String title() { return "Terminal"; }
    @Override public String glyph() { return Glyphs.TERMINAL; }

    @Override
    public boolean supports(Session session) {
        return session.fs() instanceof SftpFs;
    }

    @Override
    public TabContent create(Session session, String preferredName) throws IOException {
        TerminalView view = new TerminalView((SftpFs) session.fs());
        return new TabContent(preferredName == null || preferredName.isBlank()
                ? "terminal" : preferredName, null, view);
    }

    @Override
    public void close(java.awt.Component content) {
        if (content instanceof TerminalView view) view.close();
    }

    @Override
    public JComponent localShell() throws IOException {
        return new TerminalView(LocalShell.spawn());
    }
}
