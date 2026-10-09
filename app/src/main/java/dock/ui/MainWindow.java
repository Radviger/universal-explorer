package dock.ui;

import dock.kit.ViewSettings;
import dock.kit.Toast;
import dock.kit.FontRegistry;
import dock.kit.Glyphs;
import dock.kit.ThemeManager;
import dock.kit.Tokens;
import dock.kit.tabs.SessionTab;
import dock.kit.tabs.TabContent;
import dock.kit.tabs.TabContentProvider;
import dock.kit.tabs.TabContents;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JLabel;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** The application main window: menu bar, session tabs, status bar. */
public final class MainWindow extends JFrame {

    /**
     * One open tab: its content, the provider that made it (for close),
     * and — for session tabs — the session it browses (for the terminal
     * overlay).
     */
    private record TabEntry(TabContentProvider provider, dock.core.session.Session session,
                            Component content) { }

    private final JTabbedPane tabs = new JTabbedPane();
    private final List<TabEntry> tabEntries = new ArrayList<>();
    private final JPanel center = new JPanel(new java.awt.BorderLayout());
    private final StatusBar statusBar;
    private final TransferPopup transfers;
    private SessionsHome homeView;
    /** The View-menu Explorer checkbox — synced with the footer's pane
     *  buttons by {@link #syncPaneToggles()}. */
    private JCheckBoxMenuItem explorerItem;

    public MainWindow() {
        super("Universal Explorer");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setIconImages(dock.kit.AppIcon.windowImages());
        setJMenuBar(menuBar());

        dock.core.transfer.TransferEngine.GLOBAL.setResolver(ConflictDialog.resolver(this));
        dock.core.transfer.TransferEngine.GLOBAL.addListener(this::onTransfersChanged);

        tabs.putClientProperty("JTabbedPane.tabsClosable", true);
        tabs.putClientProperty("JTabbedPane.tabCloseCallback",
                (java.util.function.BiConsumer<JTabbedPane, Integer>) this::closeSession);
        tabs.putClientProperty("JTabbedPane.tabCloseToolTipText", "Close Tab");
        tabs.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                if (SwingUtilities.isMiddleMouseButton(e)) closeTabAt(e);
                else if (e.isPopupTrigger()) showTabMenu(e);
            }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) showTabMenu(e);
            }
        });
        tabs.setVisible(false);

        var closeKey = KeyStroke.getKeyStroke("ctrl W");
        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(closeKey, "dock.closeTab");
        getRootPane().getActionMap().put("dock.closeTab", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (tabs.getSelectedIndex() >= 0) closeSession(tabs, tabs.getSelectedIndex());
            }
        });
        for (String acc : new String[] {"ctrl K", "ctrl F"}) {
            getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .put(KeyStroke.getKeyStroke(acc), "dock.searchSessions");
        }
        getRootPane().getActionMap().put("dock.searchSessions", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (homeView != null && homeView.isShowing()) homeView.focusSearch();
            }
        });

        center.add(home(), java.awt.BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);
        statusBar = new StatusBar(dock.core.transfer.TransferEngine.GLOBAL, this::toggleTransfers);
        statusBar.setPaneActions(this::toggleLocalPane, this::toggleRemotePane);
        add(statusBar, BorderLayout.SOUTH);
        transfers = new TransferPopup(this, dock.core.transfer.TransferEngine.GLOBAL, statusBar);
        syncPaneToggles();
        setSize(1280, 800);
        setMinimumSize(new Dimension(960, 600));
        // Centered every launch: setLocationByPlatform let the OS place
        // the window, and Windows cascades new windows, so the app opened
        // at a slightly different spot each run. After setSize, so the
        // centering accounts for the real size.
        setLocationRelativeTo(null);
    }

    /** The saved-sessions landing screen, rebuilt fresh each time it returns. */
    private SessionsHome home() {
        homeView = new SessionsHome(new SessionsHome.Host() {
            @Override public void connect(dock.core.config.Site site,
                    java.util.function.BiConsumer<Exception, dock.core.fs.FileSystem> outcome) {
                MainWindow.this.connectSite(site, outcome);
            }
            @Override public void newSession() { MainWindow.this.newSession(); }
            @Override public void localShell() { MainWindow.this.openLocalShell(); }
            @Override public void quickConnect(String typed) { MainWindow.this.quickConnect(typed); }
            @Override public void editSite(dock.core.config.Site site) { MainWindow.this.editSite(site); }
            @Override public boolean hasSourceEntries() {
                for (dock.core.config.SiteSource src : dock.core.config.SiteSource.all()) {
                    if (!src.sites().isEmpty()) return true;
                }
                return false;
            }
            @Override public boolean sourcesShown() {
                for (dock.core.config.SiteSource src : dock.core.config.SiteSource.all()) {
                    if (src.shown()) return true;
                }
                return false;
            }
            @Override public void setSourcesShown(boolean shown) {
                try {
                    for (dock.core.config.SiteSource src : dock.core.config.SiteSource.all()) {
                        dock.core.config.AppSettings.setFlag(src.settingKey(), shown);
                    }
                } catch (java.io.IOException ex) {
                    Toast.show(MainWindow.this, "Could not save the setting: " + ex.getMessage(),
                            Glyphs.WARNING);
                }
            }
            @Override public java.util.List<SessionsHome.Section> sourceSections() {
                java.util.List<SessionsHome.Section> out = new java.util.ArrayList<>();
                for (dock.core.config.SiteSource src : dock.core.config.SiteSource.all()) {
                    if (src.shown()) {
                        out.add(new SessionsHome.Section(src.title(), src.tag(), src.sites()));
                    }
                }
                return out;
            }
        });
        return homeView;
    }

    private void onTransfersChanged() {
        // Called from worker threads; coalesce onto the EDT.
        javax.swing.SwingUtilities.invokeLater(this::handleTransfersChanged);
    }

    private void handleTransfersChanged() {
        // Bursts surface in the footer bar; the transfers popup opens only
        // on demand (footer click / Ctrl+Alt+Q) — IntelliJ-style, never on
        // its own.
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof SessionTab view) view.refreshAfterTransfers();
        }
    }

    /** Toggles the transfers popup anchored above the footer bar. */
    public void toggleTransfers() {
        transfers.toggle();
    }

    /** The footer's edge buttons: fold one pane of the selected session
     *  away — or bring it back. */
    private void toggleLocalPane() {
        if (tabs.getSelectedComponent() instanceof SessionTab view) {
            view.setLocalPaneHidden(!view.localPaneHidden());
            syncPaneToggles();
        }
    }

    private void toggleRemotePane() {
        if (tabs.getSelectedComponent() instanceof SessionTab view) {
            view.setRemotePaneHidden(!view.remotePaneHidden());
            syncPaneToggles();
        }
    }

    /** The transfers popup (screenshot/selftest hook). */
    public TransferPopup transfersPopup() {
        return transfers;
    }

    private void newSession() {
        new ConnectDialog(this, this::connectedFromDialog).setVisible(true);
    }

    /** Quick connect: opens the dialog prefilled when the text parses as [user@]host[:port]. */
    private void quickConnect(String typed) {
        ConnectDialog d = new ConnectDialog(this, this::connectedFromDialog);
        String[] q = ConnectDialog.parseQuickSpec(typed);
        if (q != null) d.prefillQuick(q[0], q[1], q[2]);
        d.setVisible(true);
    }

    private void editSite(dock.core.config.Site site) {
        // The row menu modifies the saved entry; connecting stays on the
        // row's double-click.
        new ConnectDialog(this, site, saved -> {
            if (homeView != null) homeView.refresh();
        }).setVisible(true);
    }

    /** The missing-secret prompt: the prefilled form collects the secret
     *  and connects — a double-click that paused, not an edit. */
    private void editSiteToConnect(dock.core.config.Site site) {
        new ConnectDialog(this, site, this::connectedFromDialog).setVisible(true);
    }

    /** Adapter for the connect dialog: the site carries both the tab's
     *  preferred title and its remembered directories. */
    private void connectedFromDialog(dock.core.session.Session session,
                                     dock.core.config.Site site) {
        openSession(session, site.name(), site);
    }

    /**
     * Connects with a saved site's settings and stored secret. The outcome
     * runs on the EDT exactly once: non-null fs = connected (the tab is open),
     * non-null error = failed, both null = aborted (missing password opens
     * the edit dialog instead).
     */
    public void connectSite(dock.core.config.Site site,
                            java.util.function.BiConsumer<Exception, dock.core.fs.FileSystem> outcome) {
        Thread.ofVirtual().name("dock-connect").start(() -> {
            try {
                var backend = dock.core.spi.Backends.of(site.protocol());
                if (backend == null) {
                    throw new java.io.IOException(site.protocol().label()
                            + " support is not present in this build.");
                }
                dock.core.session.Session session;
                try {
                    session = backend.dial(site, MainWindow::loadSecret);
                } catch (dock.core.spi.MissingSecretException e) {
                    // Aborted (missing secret): the edit dialog is up.
                    SwingUtilities.invokeLater(() -> {
                        editSiteToConnect(site);
                        outcome.accept(null, null);
                    });
                    return;
                }
                try {
                    dock.core.config.Sites.touch(site.name());
                } catch (Exception ignored) {
                    // Recency bookkeeping must never kill the connection.
                }
                dock.core.fs.FileSystem fs = session.fs();
                SwingUtilities.invokeLater(() -> {
                    outcome.accept(null, fs);
                    openSession(session, site.name(), site);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> outcome.accept(ex, null));
            }
        });
    }

    /** The credential-store leg of the dial: secrets enter as char[]
     *  arrays so the backends can wipe them; the String is gone by then. */
    private static char[] loadSecret(String credentialTarget) {
        String stored = dock.core.secrets.CredentialManager.load(credentialTarget);
        return stored == null ? null : stored.toCharArray();
    }

    /** Opens a connected session as a new closable tab. */
    /**
     * Opens a connected session titled by its preferred name (the saved
     * session's name); blank names fall back to the endpoint, which then
     * also hides the tooltip carrying the endpoint.
     */
    public void openSession(dock.core.session.Session session, String preferredName) {
        openSession(session, preferredName, null);
    }

    /**
     * Opens a connected session as a new closable tab; a saved site also
     * hands the commander view its remembered directories, so both panes
     * reopen where this session was last left.
     */
    public void openSession(dock.core.session.Session session, String preferredName,
                            dock.core.config.Site site) {
        TabContentProvider provider = TabContents.defaultFor(session);
        if (provider == null) {
            Toast.show(this, "No tab content supports this session.", Glyphs.WARNING);
            return;
        }
        TabContent content;
        try {
            content = provider.create(session, preferredName);
        } catch (java.io.IOException e) {
            Toast.show(this, "Could not open the session: " + e.getMessage(), Glyphs.WARNING);
            return;
        }
        // Right after construction, before the first listings land: the
        // restores supersede the default opening directories cleanly.
        if (site != null && content.component() instanceof dock.commander.SessionView view)
            view.applySite(site);
        ensureTabsVisible();
        tabs.addTab(content.title(), content.component());
        tabEntries.add(new TabEntry(provider, session, content.component()));
        int index = tabs.getTabCount() - 1;
        if (content.tooltip() != null && !content.title().equals(content.tooltip())) {
            tabs.setToolTipTextAt(index, content.tooltip());
        }
        tabs.setSelectedIndex(index);
        tabs.requestFocusInWindow();
    }

    /** Opens an embedded terminal for the selected session tab. */
    private void openTerminal() {
        TabEntry entry = selectedEntry();
        if (entry == null || !(entry.content() instanceof SessionTab)) {
            Toast.show(this, "Open a session first.", Glyphs.INFO);
            return;
        }
        openTerminalFor(entry);
    }

    /** Spawns this machine's shell as a terminal tab — no session needed. */
    private void openLocalShell() {
        for (TabContentProvider provider : TabContents.all()) {
            try {
                Component shell = provider.localShell();
                if (shell != null) {
                    addContentTab(provider, "Local shell", shell);
                    return;
                }
            } catch (java.io.IOException e) {
                Toast.show(this, "Could not start the local shell: " + e.getMessage(),
                        Glyphs.WARNING);
                return;
            }
        }
    }

    /** Terminal overlay for a session tab (menu, Ctrl+Shift+T). */
    private void openTerminalFor(TabEntry entry) {
        TabContentProvider overlay = TabContents.overlayFor(entry.session());
        if (overlay == null) {
            Toast.show(this, "The embedded terminal is available for SFTP sessions.", Glyphs.INFO);
            return;
        }
        String title = entry.content() instanceof SessionTab tab
                ? tab.title() + " — terminal" : "terminal";
        try {
            TabContent content = overlay.create(entry.session(), title);
            addContentTab(overlay, content.title(), content.component());
        } catch (java.io.IOException e) {
            Toast.show(this, "Could not open shell: " + e.getMessage(), Glyphs.WARNING);
        }
    }

    /** Adds a tab of provider-made content and focuses it. */
    private void addContentTab(TabContentProvider provider, String title, Component content) {
        ensureTabsVisible();
        tabs.addTab(title, content);
        tabEntries.add(new TabEntry(provider, null, content));
        tabs.setSelectedIndex(tabs.getTabCount() - 1);
        if (content instanceof java.awt.Container c) c.requestFocusInWindow();
    }

    private void ensureTabsVisible() {
        if (tabs.getTabCount() == 0) {
            center.removeAll();
            tabs.setVisible(true);
            center.add(tabs, java.awt.BorderLayout.CENTER);
            center.revalidate();
            center.repaint();
        }
    }

    private TabEntry selectedEntry() {
        Component selected = tabs.getSelectedComponent();
        for (TabEntry e : tabEntries) {
            if (e.content() == selected) return e;
        }
        return null;
    }

    /** Screenshot-harness hook: terminal tab for the first session. */
    public void openTerminalForScreenshots() {
        for (TabEntry entry : tabEntries) {
            if (entry.session() != null && entry.content() instanceof SessionTab) {
                openTerminalFor(entry);
                return;
            }
        }
    }

    private void closeSession(JTabbedPane pane, Integer index) {
        java.awt.Component view = pane.getComponentAt(index);
        pane.remove(index);
        TabEntry owner = null;
        for (TabEntry e : tabEntries) {
            if (e.content() == view) {
                owner = e;
                break;
            }
        }
        if (owner != null) {
            tabEntries.remove(owner);
            owner.provider().close(view);
        }
        if (pane.getTabCount() == 0) {
            center.removeAll();
            pane.setVisible(false);
            center.add(home(), java.awt.BorderLayout.CENTER);
            center.revalidate();
            center.repaint();
        }
    }

    /** Middle-click close, resolved from the event position. */
    private void closeTabAt(java.awt.event.MouseEvent e) {
        int index = tabs.indexAtLocation(e.getX(), e.getY());
        if (index >= 0) closeSession(tabs, index);
    }

    /** IntelliJ-style tab context menu: Close / Close Others / Close All. */
    private void showTabMenu(java.awt.event.MouseEvent e) {
        int index = tabs.indexAtLocation(e.getX(), e.getY());
        if (index < 0) return;
        tabs.setSelectedIndex(index);

        JPopupMenu menu = new JPopupMenu();
        JMenuItem close = new JMenuItem("Close Tab");
        close.setAccelerator(KeyStroke.getKeyStroke("ctrl W"));
        close.addActionListener(ev -> closeSession(tabs, index));
        JMenuItem others = new JMenuItem("Close Other Tabs");
        others.setEnabled(tabs.getTabCount() > 1);
        others.addActionListener(ev -> {
            for (int i = tabs.getTabCount() - 1; i >= 0; i--) {
                if (i != index) closeSession(tabs, i);
            }
        });
        JMenuItem all = new JMenuItem("Close All Tabs");
        all.addActionListener(ev -> {
            for (int i = tabs.getTabCount() - 1; i >= 0; i--) closeSession(tabs, i);
        });
        menu.add(close);
        menu.add(others);
        menu.add(all);
        menu.show(tabs, e.getX(), e.getY());
    }

    public int sessionCount() {
        return tabs.getTabCount();
    }

    private JMenuBar menuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu session = new JMenu("Session");
        session.setMnemonic(KeyEvent.VK_S);
        session.add(item("New Session…", Glyphs.PLUS, "ctrl N", this::newSession));
        session.add(savedSessionsMenu());
        session.addSeparator();
        java.util.Map<JCheckBoxMenuItem, dock.core.config.SiteSource> shownItems =
                new java.util.LinkedHashMap<>();
        for (dock.core.config.SiteSource src : dock.core.config.SiteSource.all()) {
            session.add(item("Import " + src.menuName() + "…", Glyphs.SERVER, null,
                    () -> importFrom(src)));
            JCheckBoxMenuItem shown = sourceShownItem(src);
            shownItems.put(shown, src);
            session.add(shown);
        }
        // The launcher's Configs toggle flips the same flags: re-read on open.
        session.addMenuListener(new javax.swing.event.MenuListener() {
            @Override public void menuSelected(javax.swing.event.MenuEvent e) {
                shownItems.forEach((mi, src) -> mi.setState(src.shown()));
            }
            @Override public void menuDeselected(javax.swing.event.MenuEvent e) {}
            @Override public void menuCanceled(javax.swing.event.MenuEvent e) {}
        });
        session.addSeparator();
        session.add(item("Open Terminal…", Glyphs.TERMINAL, "ctrl shift T", this::openTerminal));
        session.add(item("Local Shell", Glyphs.TERMINAL, "ctrl shift L", this::openLocalShell));
        session.addSeparator();
        session.add(item("Quit", Glyphs.CLOSE, "ctrl Q", this::dispose));
        bar.add(session);

        JMenu view = new JMenu("View");
        view.setMnemonic(KeyEvent.VK_V);
        view.add(item("Transfers", Glyphs.EXCHANGE, "ctrl alt Q", this::toggleTransfers));
        view.addSeparator();
        explorerItem = explorerModeItem();
        view.add(explorerItem);
        view.addSeparator();
        view.add(hiddenFilesItem());
        view.add(dotFilesItem());
        view.addSeparator();
        view.add(item("Switch Theme", ThemeManager.mode() == ThemeManager.Mode.DARK
                ? Glyphs.SUN : Glyphs.MOON, "ctrl alt T", ThemeManager::toggle));
        JCheckBoxMenuItem follow = new JCheckBoxMenuItem("Follow System Theme",
                ThemeManager.isFollowSystem());
        follow.addActionListener(e -> ThemeManager.setFollowSystem(follow.getState()));
        view.add(follow);
        bar.add(view);

        JMenu help = new JMenu("Help");
        help.setMnemonic(KeyEvent.VK_H);
        help.add(item("About", Glyphs.INFO, null, this::showAbout));
        bar.add(help);

        return bar;
    }

    private JMenuItem item(String text, String glyph, String accelerator, Runnable action) {
        JMenuItem mi = new JMenuItem(text,
                Glyphs.icon(glyph, 15, () -> UIManager.getColor("Label.disabledForeground")));
        if (accelerator != null) {
            mi.setAccelerator(KeyStroke.getKeyStroke(accelerator));
        }
        mi.addActionListener(e -> action.run());
        return mi;
    }

    /**
     * Commander/Explorer toggle for the selected session tab — the local
     * pane's visibility. The checkbox mirrors the tab's layout and
     * follows tab selection; terminal tabs and the home screen disable
     * it, and so does a folded remote pane (one pane must stay visible).
     */
    private JCheckBoxMenuItem explorerModeItem() {
        JCheckBoxMenuItem explorerMode = new JCheckBoxMenuItem("Explorer Mode",
                Glyphs.icon(Glyphs.MONITOR, 15,
                        () -> UIManager.getColor("Label.disabledForeground")));
        explorerMode.setAccelerator(KeyStroke.getKeyStroke("ctrl alt E"));
        explorerMode.addActionListener(e -> {
            if (tabs.getSelectedComponent() instanceof SessionTab view) {
                view.setLocalPaneHidden(explorerMode.getState());
                syncPaneToggles();
            }
        });
        // Selection drives everything: connect/close/select all move it.
        tabs.addChangeListener(e -> syncPaneToggles());
        return explorerMode;
    }

    /** One truth for pane visibility: the View-menu checkbox and the
     *  footer's two edge buttons mirror the selected session together. */
    private void syncPaneToggles() {
        SessionTab tab = tabs.getSelectedComponent() instanceof SessionTab t ? t : null;
        boolean isSession = tab != null;
        boolean localHidden = isSession && tab.localPaneHidden();
        boolean remoteHidden = isSession && tab.remotePaneHidden();
        if (explorerItem != null) {
            explorerItem.setEnabled(isSession && !remoteHidden);
            explorerItem.setState(localHidden);
        }
        if (statusBar != null) {
            statusBar.syncPaneToggles(isSession, localHidden, remoteHidden);
        }
    }

    /** Auto mode: list a source's entries on the launcher, live. */
    private JCheckBoxMenuItem sourceShownItem(dock.core.config.SiteSource src) {
        JCheckBoxMenuItem mi = checkItem("Show " + src.menuName(), Glyphs.EYE, null);
        mi.setState(src.shown());
        mi.addActionListener(e -> {
            try {
                dock.core.config.AppSettings.setFlag(src.settingKey(), mi.getState());
            } catch (java.io.IOException ex) {
                Toast.show(this, "Could not save the setting: " + ex.getMessage(),
                        Glyphs.WARNING);
            }
            if (homeView != null) homeView.refresh();
        });
        return mi;
    }

    /** Manual mode: pick a source's entries to save as sessions. */
    private void importFrom(dock.core.config.SiteSource src) {
        java.util.List<dock.core.config.Site> hosts = src.sites();
        if (hosts.isEmpty()) {
            Toast.show(this, "Nothing to import: " + src.title().replace("From ", "")
                    + " lists no usable entries.", Glyphs.INFO);
            return;
        }
        int imported = new SourceImportDialog(this, "Import " + src.menuName(), hosts)
                .showAndImport();
        if (imported > 0) {
            if (homeView != null) homeView.refresh();
            Toast.show(this, "Imported " + imported + (imported == 1 ? " session." : " sessions."),
                    Glyphs.INFO);
        }
    }

    /** Show-hidden toggle for every open pane (View menu, Ctrl+H). */
    private JCheckBoxMenuItem hiddenFilesItem() {
        JCheckBoxMenuItem showHidden = checkItem("Show Hidden Files", Glyphs.EYE, "ctrl H");
        showHidden.addActionListener(e -> {
            ViewSettings.setShowHidden(showHidden.getState());
            applyViewSettings();
        });
        return showHidden;
    }

    /** Windows extra: also hide dot-prefixed names in local panes (off by default). */
    private JCheckBoxMenuItem dotFilesItem() {
        JCheckBoxMenuItem hideDot =
                checkItem("Hide Dot-Prefixed Files on Windows", Glyphs.FOLDER, null);
        hideDot.addActionListener(e -> {
            ViewSettings.setHideDotPrefixedOnWindows(hideDot.getState());
            applyViewSettings();
        });
        return hideDot;
    }

    private JCheckBoxMenuItem checkItem(String text, String glyph, String accelerator) {
        JCheckBoxMenuItem mi = new JCheckBoxMenuItem(text,
                Glyphs.icon(glyph, 15, () -> UIManager.getColor("Label.disabledForeground")));
        if (accelerator != null) mi.setAccelerator(KeyStroke.getKeyStroke(accelerator));
        return mi;
    }

    /** Pushes View-menu settings into every open session's panes. */
    private void applyViewSettings() {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof SessionTab view) view.applyViewSettings();
        }
    }

    /**
     * Saved-sites submenu, repopulated on every open so it never goes stale
     * after a connect (recency order) or a delete on the landing screen.
     */
    private JMenu savedSessionsMenu() {
        JMenu menu = new JMenu("Saved Sessions");
        populateSavedSessions(menu);
        menu.getPopupMenu().addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                populateSavedSessions(menu);
            }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });
        return menu;
    }

    private void populateSavedSessions(JMenu menu) {
        java.util.List<dock.core.config.Site> sites =
                dock.core.config.Sites.byRecency(dock.core.config.Sites.load());
        menu.removeAll();
        if (sites.isEmpty()) {
            JMenuItem none = new JMenuItem("No saved sessions yet");
            none.setEnabled(false);
            menu.add(none);
            return;
        }
        for (dock.core.config.Site s : sites) {
            JMenuItem mi = new JMenuItem(s.name(), Glyphs.icon(Glyphs.SERVER, 15,
                    () -> UIManager.getColor("Label.disabledForeground")));
            mi.setToolTipText(s.user() + "@" + s.host()
                    + (s.port() == 22 ? "" : ":" + s.port()));
            mi.addActionListener(ev -> connectSite(s, (err, fs) -> {
                if (err != null) {
                    Toast.show(MainWindow.this, "Could not connect to " + s.name() + ": "
                            + err.getMessage(), Glyphs.WARNING);
                }
            }));
            menu.add(mi);
        }
    }

    private void showAbout() {
        // Null when running straight from classes (IDE); the packaged jar
        // carries the Gradle version in its manifest.
        String version = getClass().getPackage().getImplementationVersion();
        JLabel title = new JLabel(version == null ? "Universal Explorer"
                : "Universal Explorer " + version);
        title.setFont(FontRegistry.uiSemiBold(16));
        JLabel body = new JLabel("A fast multi-protocol file client.");
        body.setFont(FontRegistry.ui());
        body.setForeground(UIManager.getColor("Label.disabledForeground"));
        JLabel stack = new JLabel("Java " + Runtime.version().feature()
                + "  ·  FlatLaf  ·  Nerd Fonts");
        stack.setFont(FontRegistry.ui(12));
        stack.setForeground(UIManager.getColor("Label.disabledForeground"));
        JLabel legal = new JLabel("Copyright (c) 2026 Universal Explorer contributors"
                + "  ·  GPL-3.0");
        legal.setFont(FontRegistry.ui(12));
        legal.setForeground(UIManager.getColor("Label.disabledForeground"));

        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(Tokens.GAP_3, Tokens.GAP_4,
                Tokens.GAP_2, Tokens.GAP_4));
        title.setAlignmentX(0f);
        body.setAlignmentX(0f);
        stack.setAlignmentX(0f);
        legal.setAlignmentX(0f);
        p.add(title);
        p.add(Box.createVerticalStrut(Tokens.GAP_1));
        p.add(body);
        p.add(Box.createVerticalStrut(Tokens.GAP_1));
        p.add(stack);
        p.add(Box.createVerticalStrut(Tokens.GAP_1));
        p.add(legal);

        JOptionPane.showMessageDialog(this, p, "About", JOptionPane.PLAIN_MESSAGE,
                Glyphs.iconRotated(Glyphs.PLANET, 32,
                        () -> UIManager.getColor("Dock.accent"), Glyphs.PLANET_TILT_DEG));
    }
}
