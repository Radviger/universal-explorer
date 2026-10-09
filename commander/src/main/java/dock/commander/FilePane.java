package dock.commander;

import dock.kit.ViewSettings;
import dock.kit.Toast;
import dock.kit.Fmt;
import dock.kit.FontRegistry;
import dock.kit.Glyphs;
import dock.kit.StatusLine;
import dock.kit.Tokens;
import dock.core.fs.FileEntry;
import dock.core.fs.FileSystem;
import dock.core.fs.LocalFs;
import dock.core.fs.SlashPaths;
import dock.archive.ArchiveFs;
import dock.editor.EditorPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.CellEditorListener;
import javax.swing.event.ChangeEvent;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;

/**
 * One file-browsing pane: navigation bar, file table, summary strip.
 * All filesystem work runs on virtual threads; results carry a generation
 * counter so stale listings (rapid navigation) are dropped.
 */
public final class FilePane extends JPanel {

    private FileSystem fs;
    private final FileTableModel model = new FileTableModel();
    private final SpeedSearch speed = new SpeedSearch();
    // Printable keystrokes have no InputMap entries; speed search must see
    // them in processKeyEvent before the table's default (no-op) handling.
    private final SpeedTable table = new SpeedTable(model);
    private final JScrollPane scroll = new JScrollPane(table);

    /**
     * The pane's table. Printable keys arrive here (key events dispatched
     * through the focus machinery reach processKeyEvent only when the
     * key-event mask is enabled — no KeyListener ever registers).
     */
    private final class SpeedTable extends JTable {
        SpeedTable(FileTableModel m) {
            super(m);
            enableEvents(java.awt.AWTEvent.KEY_EVENT_MASK);
        }

        @Override protected void processKeyEvent(java.awt.event.KeyEvent e) {
            if (speed.process(e)) return;
            super.processKeyEvent(e);
        }

        void processKeyForTest(java.awt.event.KeyEvent e) {
            processKeyEvent(e);
        }

        /** The exit row of a mounted archive — ".." at any depth — carries
         *  the view's amber tint. Painted after the cells, over the table's
         *  own surface: a renderer background would stick (cell renderers
         *  cache the colors set on them), and an overlay above the table —
         *  the scroll pane — goes stale on partial repaints. This rides
         *  every repaint the table itself makes. A selected exit row skips
         *  the tint: the way out must stay findable while picked. */
        @Override public void paint(Graphics g) {
            super.paint(g);
            FileTableModel m = (FileTableModel) getModel();
            if (m.parentKind != FileIcons.Kind.ARCHIVE || getRowCount() == 0
                    || isRowSelected(0) || !m.row(0).equals(FileEntry.PARENT)) return;
            Color tint = UIManager.getColor("Dock.archiveRowTint");
            if (tint == null) return;
            Rectangle band = getCellRect(0, 0, true);
            g.setColor(tint);
            ((java.awt.Graphics2D) g).fill(new Rectangle(0, band.y, getWidth(), band.height));
        }
    }
    private final PathBar pathBar = new PathBar(() -> fs, this::navigate, this::navigatePlace);
    private final JButton backButton = toolButton(Glyphs.ARROW_LEFT, "Back (Alt+Left)");
    private final JButton forwardButton = toolButton(Glyphs.ARROW_RIGHT, "Forward (Alt+Right)");
    private final JButton upButton = toolButton(Glyphs.ARROW_UP, "Parent directory (Backspace)");
    private final JButton refreshButton = toolButton(Glyphs.SYNC, "Refresh (Ctrl+R)");
    private final JLabel summary = new JLabel();
    /** The selection's size, its own label so the digits read in mono. */
    private final JLabel summaryBytes = new JLabel();

    private volatile int generation = 0;
    /** True once any listing has committed — a first listing that fails
     *  (a remembered directory that no longer exists) falls back to the
     *  filesystem's home instead of leaving an empty pane. */
    private boolean everListed;
    private String path = "";
    private List<FileEntry> current = List.of();
    private long totalDirs, totalFiles;
    private FileTableModel.Column sortColumn = FileTableModel.Column.NAME;
    private boolean sortAscending = true;

    /** One navigation target: a filesystem and a path in it. Mounting an
     *  archive swaps the filesystem, so history and the selection memory
     *  are kept per place, not per path string. */
    private record Place(FileSystem fs, String path) {}

    private final Deque<Place> backStack = new ArrayDeque<>();
    private final Deque<Place> forwardStack = new ArrayDeque<>();
    /** Directories left behind, mapped to the entry selected at the moment
     *  of leaving — restored when that directory is listed again. */
    private final Map<Place, String> selectionMemory = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Place, String> eldest) {
            return size() > 512;
        }
    };
    /** Runs once, on the EDT, after this pane's first listing lands. */
    private Runnable firstListingHook;
    /** Asks the session view to open the in-pane image viewer here; null in
     *  standalone panes (tests) where Enter on an image stays a no-op. */
    private java.util.function.Consumer<String> viewHook;
    /** Asks the session view to open the in-pane markdown viewer here; null
     *  in standalone panes, the {@link #viewHook} pattern. */
    private java.util.function.Consumer<String> mdHook;
    /** Asks the session view to open the in-pane text editor here; null in
     *  standalone panes, the {@link #viewHook} pattern. */
    private java.util.function.Consumer<String> editHook;
    /** Asks the session view to open the in-pane media player here; null
     *  in standalone panes, the {@link #viewHook} pattern. */
    private java.util.function.Consumer<String> playHook;
    /** Tells the session view where this pane stands (filesystem and
     *  path) every time a listing commits — the session's path memory;
     *  null in standalone panes. */
    private java.util.function.BiConsumer<FileSystem, String> dirHook;
    /** Where the cursor lands when the next listing arrives: delete parks
     *  it on the row that now holds the entry after the deleted one;
     *  create parks it on the new folder's name. */
    private int pendingCursorRow = -1;
    private String pendingCursorName;
    /** The draft row while a new folder is being named in place — never a
     *  filesystem entry until Enter commits it. */
    private FileEntry draftEntry;
    private DefaultCellEditor draftEditor;
    private boolean autoConfirmDelete;

    public FilePane(FileSystem fs) {
        this.fs = fs;
        speed.attach(table, this, this::openSelected);
        // The speed-search badge is painted over this scroll pane by the
        // pane itself. Blit scrolling would copy the badge's pixels along
        // with the table, and viewport scroll damage alone repaints only
        // the table — so disable the blit and put the damage on the pane
        // (badge included) whenever the view moves.
        scroll.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        scroll.getViewport().addChangeListener(e -> speed.changed());
        setLayout(new BorderLayout());
        add(buildToolbar(), BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(buildSummary(), BorderLayout.SOUTH);
        configureTable();
        bindKeys();
        var dnd = new DragOut();
        table.setDragEnabled(true);
        table.setTransferHandler(dnd);
        setTransferHandler(dnd);
        navigate(fs.home());
    }

    public FileSystem fs() { return fs; }
    public String path() { return path; }
    public JTable table() { return table; }
    public int rowCount() { return model.getRowCount(); }

    /** Reloads the current directory without touching history. */
    public void reload() { load(path); }

    /** Re-filters the listing after a View-menu settings change (no I/O). */
    public void applyViewSettings() { refreshDisplay(); }

    /** Dot-prefixed names also hide here: the Windows setting, local panes only. */
    private boolean dotPrefixedHidden() {
        return ViewSettings.hideDotPrefixedOnWindows() && !fs.remote();
    }

    /** Whether a listing of this pane is in flight (tests). */
    public boolean busyForTest() { return pathBar.busyForTest(); }

    /** The summary strip's full readout as one string (tests): the words
     *  plus the size segment when one exists. */
    public String summaryTextForTest() {
        String bytes = summaryBytes.getText();
        return summary.getText() + (bytes.isEmpty() ? "" : " " + bytes);
    }

    /** The size segment's font (tests) — mono so the digits line up. */
    public java.awt.Font summaryBytesFontForTest() { return summaryBytes.getFont(); }

    /** A pane leaving the screen takes its hover text with it. */
    @Override public void removeNotify() {
        super.removeNotify();
        StatusLine.clear();
    }

    /** Ctrl+L: switch this pane's path bar to manual editing. */
    public void beginPathEdit() { pathBar.beginEdit(); }

    /**
     * The keyboard landing point: focuses the table and touches nothing
     * else — the selection stays exactly as it was (usually empty).
     * Scanning starts on the first arrow-down, which skips ".." on its
     * own.
     */
    public void focusTable() {
        table.requestFocusInWindow();
    }

    /** Arms {@code hook} to run once, on the EDT, after this pane's first
     *  listing lands — a fresh session's keyboard entry point. */
    public void onFirstListing(Runnable hook) { firstListingHook = hook; }

    /** Arms the viewer request sink — the SessionView swaps this pane out
     *  for the image viewer when it fires. */
    public void onViewRequest(java.util.function.Consumer<String> hook) { viewHook = hook; }

    /** Arms the markdown request sink — the SessionView swaps this pane out
     *  for the rendered-document viewer when it fires. */
    public void onMarkdownRequest(java.util.function.Consumer<String> hook) { mdHook = hook; }

    /** Arms the editor request sink — the SessionView swaps this pane out
     *  for the text editor when it fires. */
    public void onEditRequest(java.util.function.Consumer<String> hook) { editHook = hook; }

    /** Arms the media request sink — the SessionView swaps this pane out
     *  for the streaming player when it fires. */
    public void onPlayRequest(java.util.function.Consumer<String> hook) { playHook = hook; }

    /** Arms the directory-landed sink — the SessionView records where its
     *  panes stand under the saved session (the path memory). */
    public void onDirLanded(java.util.function.BiConsumer<FileSystem, String> hook) {
        dirHook = hook;
    }

    /** Enter/F3 on an image: this pane becomes the viewer until closed. */
    public void viewFile(String name) {
        if (viewHook != null) viewHook.accept(name);
    }

    /** Enter/F3 on a markdown file: this pane becomes the rendered reader
     *  until closed. */
    public void viewMarkdown(String name) {
        if (mdHook != null) mdHook.accept(name);
    }

    /** F4 on any file, Enter on a text kind: this pane becomes the text
     *  editor for it until closed. */
    public void editFile(String name) {
        if (editHook != null) editHook.accept(name);
    }

    /** Enter/F3 on media: this pane becomes the streaming player until
     *  closed — the file itself is served, never downloaded. */
    public void playFile(String name) {
        if (playHook != null) playHook.accept(name);
    }

    private void viewSelected() {
        List<FileEntry> selected = selectedEntries();
        if (selected.size() != 1) return;
        String name = selected.get(0).name();
        // View dispatches by kind: markdown renders, media plays, everything
        // else is offered to the image viewer (which decode-refuses
        // non-images); names the map can't place sniff before that offer.
        if (FileIcons.kindOf(name) == FileIcons.Kind.MARKDOWN) viewMarkdown(name);
        else if (isMedia(name)) playFile(name);
        else if (FileIcons.kindOf(name) == FileIcons.Kind.FILE)
            sniffThen(name, sniffed -> {
                if (sniffed == FileIcons.Kind.VIDEO || sniffed == FileIcons.Kind.AUDIO)
                    playFile(name);
                else viewFile(name);
            });
        else viewFile(name);
    }

    /** The playable kinds — video and audio share the one player surface. */
    private static boolean isMedia(String name) {
        FileIcons.Kind k = FileIcons.kindOf(name);
        return k == FileIcons.Kind.VIDEO || k == FileIcons.Kind.AUDIO;
    }

    /** F4: any single file opens in the editor — markdown's source included
     *  (View renders it); binary files explain themselves on arrival. */
    private void editSelected() {
        List<FileEntry> selected = selectedEntries();
        if (selected.size() != 1 || selected.get(0).directory()) return;
        editFile(selected.get(0).name());
    }

    /** The pane's image files in display order — the viewer's walk order,
     *  re-read on every step so reloads behind it are picked up. */
    public List<String> imageSequence() {
        return filesOfKind(FileIcons.Kind.IMAGE);
    }

    /** The pane's media files in display order — the player's walk order,
     *  video and audio together in one queue. */
    public List<String> mediaSequence() {
        List<String> out = new java.util.ArrayList<>();
        for (int r = 0; r < model.getRowCount(); r++) {
            FileEntry e = model.row(r);
            if (e != FileEntry.PARENT && !e.directory() && isMedia(e.name()))
                out.add(e.name());
        }
        return out;
    }

    private List<String> filesOfKind(FileIcons.Kind kind) {
        List<String> out = new java.util.ArrayList<>();
        for (int r = 0; r < model.getRowCount(); r++) {
            FileEntry e = model.row(r);
            if (e != FileEntry.PARENT && !e.directory()
                    && FileIcons.kindOf(e.name()) == kind)
                out.add(e.name());
        }
        return out;
    }

    /** Moves the cursor onto a row by name — the viewer reports every file
     *  it shows so closing it lands where the walk left off. */
    public void selectEntry(String name) {
        for (int r = 0; r < model.getRowCount(); r++) {
            FileEntry e = model.row(r);
            if (e != FileEntry.PARENT && name.equals(e.name())) {
                table.setRowSelectionInterval(r, r);
                table.scrollRectToVisible(table.getCellRect(r, 0, true));
                return;
            }
        }
    }

    /**
     * Swaps the backend after a reconnect, keeping the current directory.
     * A mount cannot survive its host's death — the pane lands on the
     * folder that holds the archive, and history recorded on the old line
     * moves onto the new one (places inside mounts are dropped with them).
     */
    public void setFileSystem(FileSystem newFs) {
        String dir = fs instanceof ArchiveFs a ? a.hostDir() : path;
        Set<FileSystem> before = liveFilesystems();
        backStack.clear();
        forwardStack.clear();
        selectionMemory.keySet().removeIf(p -> p.fs() instanceof ArchiveFs);
        this.fs = newFs;
        load(newFs, dir);
        releaseIfOrphaned(before);
    }

    // ---- construction ----

    private JPanel buildToolbar() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBorder(BorderFactory.createEmptyBorder(Tokens.GAP_1, Tokens.GAP_2,
                Tokens.GAP_1, Tokens.GAP_2));
        bar.setOpaque(false);

        backButton.addActionListener(e -> goBack());
        forwardButton.addActionListener(e -> goForward());
        upButton.addActionListener(e -> goUp());
        refreshButton.addActionListener(e -> navigate(path));

        pathBar.setPreferredSize(new Dimension(120, 30));
        pathBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        bar.add(backButton);
        bar.add(Box.createHorizontalStrut(2));
        bar.add(forwardButton);
        bar.add(Box.createHorizontalStrut(2));
        bar.add(upButton);
        bar.add(Box.createHorizontalStrut(2));
        bar.add(refreshButton);
        bar.add(Box.createHorizontalStrut(Tokens.GAP_2));
        bar.add(pathBar);
        return bar;
    }

    private JPanel buildSummary() {
        JPanel strip = new JPanel(new BorderLayout(Tokens.GAP_2, 0));
        strip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0,
                        UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(2, Tokens.GAP_3, 2, Tokens.GAP_3)));
        summary.setFont(FontRegistry.ui(Tokens.ICON_SMALL));
        summary.setForeground(FileTableModel.muted());
        summaryBytes.setFont(FontRegistry.mono(Tokens.ICON_SMALL));
        summaryBytes.setForeground(FileTableModel.muted());
        JPanel readout = new JPanel();
        readout.setLayout(new BoxLayout(readout, BoxLayout.X_AXIS));
        readout.setOpaque(false);
        readout.add(summary);
        readout.add(Box.createHorizontalStrut(Tokens.GAP_1));
        readout.add(summaryBytes);
        strip.add(readout, BorderLayout.WEST);
        // The strip's height must never depend on which segment carries
        // text — an empty text-only label collapses to zero height, which
        // would resize the viewport under the table on every selection
        // change. Pin it to the size segment's mono line, the taller of
        // the two fonts.
        strip.setPreferredSize(new Dimension(0,
                summaryBytes.getFontMetrics(summaryBytes.getFont()).getHeight()
                        + strip.getInsets().top + strip.getInsets().bottom));
        JLabel tag = new JLabel(fs.label());
        tag.setFont(FontRegistry.uiMedium(Tokens.ICON_SMALL));
        tag.setForeground(FileTableModel.muted());
        strip.add(tag, BorderLayout.EAST);
        return strip;
    }

    private void configureTable() {
        table.setRowHeight(Tokens.TABLE_ROW_HEIGHT);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoCreateColumnsFromModel(false);
        // JTable(model) already auto-created a column set before this line
        // turned that off; drop it so exactly one configured set remains.
        while (table.getColumnCount() > 0) {
            table.removeColumn(table.getColumnModel().getColumn(0));
        }
        for (int i = 0; i < 4; i++) {
            TableColumn tc = new TableColumn(i);
            tc.setHeaderValue(model.getColumnName(i));
            table.addColumn(tc);
        }
        var cm = table.getColumnModel();
        cm.getColumn(0).setCellRenderer(new FileTableModel.NameRenderer());
        cm.getColumn(1).setCellRenderer(new FileTableModel.SizeRenderer());
        cm.getColumn(2).setCellRenderer(new FileTableModel.DateRenderer());
        cm.getColumn(3).setCellRenderer(new FileTableModel.AttrsRenderer());
        cm.getColumn(0).setPreferredWidth(420);
        cm.getColumn(1).setPreferredWidth(90);
        cm.getColumn(2).setPreferredWidth(150);
        cm.getColumn(3).setPreferredWidth(110);
        if (!fs.remote()) {
            cm.removeColumn(cm.getColumn(3));
        }

        // The draft row's name cell edits in place: Enter stops the edit
        // (commit), Escape cancels — DefaultCellEditor supplies both.
        draftEditor = new DefaultCellEditor(new DraftField());
        draftEditor.addCellEditorListener(new CellEditorListener() {
            @Override public void editingStopped(ChangeEvent e) { finishDraft(true); }
            @Override public void editingCanceled(ChangeEvent e) { finishDraft(false); }
        });
        cm.getColumn(0).setCellEditor(draftEditor);

        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) openSelected();
                else if (SwingUtilities.isRightMouseButton(e)) showContextMenu(e);
            }
        });

        // Commander-style hover: the row's facts go to the window footer's
        // status line, never a tooltip floating over the table. They are
        // file facts, so they read in the mono font like the columns.
        table.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (row < 0) {
                    StatusLine.clear();
                    return;
                }
                FileEntry entry = model.row(row);
                if (entry == draftEntry) {
                    StatusLine.clear();
                    return;
                }
                StatusLine.publish(entry == FileEntry.PARENT ? "" : hoverText(entry), true);
            }
            @Override public void mouseExited(MouseEvent e) { StatusLine.clear(); }
        });

        // The summary strip follows the selection: totals when nothing is
        // picked, selection stats while a selection exists.
        table.getSelectionModel().addListSelectionListener(e -> updateSummary());

        JTableHeader header = table.getTableHeader();
        header.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int col = header.columnAtPoint(e.getPoint());
                if (col < 0) return;
                FileTableModel.Column clicked = FileTableModel.Column.values()[col];
                if (clicked == sortColumn) sortAscending = !sortAscending;
                else { sortColumn = clicked; sortAscending = true; }
                refreshDisplay();
            }
        });
    }

    /** The draft row's editor — the name cell while a new folder is being
     *  typed. No text-field chrome: the row's selection runs under it and
     *  the folder glyph stays left of the text, so the row keeps the exact
     *  shape of the folders around it while it edits in place (a stock
     *  JTextField would paint the plain field background next to the
     *  selected cells — a hole where the row's facts should read). */
    private static final class DraftField extends JTextField {
        DraftField() {
            setOpaque(false);
            // The glyph's seat: it paints at GAP_2, the text clears it —
            // the same lead the name renderer gives icon and text.
            setBorder(BorderFactory.createEmptyBorder(0,
                    glyph().getIconWidth() + 2 * Tokens.GAP_2, 0, Tokens.GAP_2));
        }

        private static javax.swing.Icon glyph() {
            return FileIcons.icon(FileIcons.Kind.FOLDER);
        }

        @Override public void updateUI() {
            super.updateUI();
            setFont(FontRegistry.mono());
            java.awt.Color ink = UIManager.getColor("Table.selectionForeground");
            if (ink != null) setForeground(ink);
        }

        @Override protected void paintComponent(Graphics g) {
            // The row's own selection color, read from the table at paint
            // time — FlatLaf swaps it with focus, and the field must match
            // whatever the row's other cells paint this instant, not the
            // raw UIManager key. Opaque-false would otherwise let the
            // table's plain background through under the text.
            java.awt.Color sel = null;
            for (Container p = getParent(); p != null; p = p.getParent()) {
                if (p instanceof JTable t) {
                    sel = t.getSelectionBackground();
                    break;
                }
            }
            if (sel == null) sel = UIManager.getColor("Table.selectionBackground");
            g.setColor(sel != null ? sel : getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            super.paintComponent(g);
            javax.swing.Icon icon = glyph();
            icon.paintIcon(this, g, Tokens.GAP_2,
                    (getHeight() - icon.getIconHeight()) / 2);
        }
    }

    private void bindKeys() {
        var im = getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        var am = getActionMap();
        bind(im, am, "BACK_SPACE", "up", this::goUp);
        bind(im, am, "alt LEFT", "back", this::goBack);
        bind(im, am, "alt RIGHT", "forward", this::goForward);
        bind(im, am, "ctrl R", "refresh", () -> navigate(path));
        bind(im, am, "F2", "rename", this::renameSelected);
        bind(im, am, "ctrl D", "new-folder", this::newFolder);
        bind(im, am, "ctrl V", "paste", this::pasteFromClipboard);
        bind(im, am, "DELETE", "delete", this::deleteSelected);
        bind(im, am, "alt ENTER", "properties", this::showProperties);
        bind(im, am, "F3", "view", this::viewSelected);
        bind(im, am, "F4", "edit", this::editSelected);

        // JTable binds F2 to "start editing" in its focused map and consumes
        // the key before ancestor maps see it; the table is read-only, so that
        // action is a silent no-op. Override it where it lives.
        var tim = table.getInputMap(javax.swing.JComponent.WHEN_FOCUSED);
        var tam = table.getActionMap();
        bind(tim, tam, "F2", "dock-rename", this::renameSelected);
        bind(tim, tam, "ctrl D", "dock-new-folder", this::newFolder);
        bind(tim, tam, "ctrl V", "dock-paste", this::pasteFromClipboard);
        bind(tim, tam, "DELETE", "dock-delete", this::deleteSelected);
        bind(tim, tam, "alt ENTER", "dock-properties", this::showProperties);
        bind(tim, tam, "F3", "dock-view", this::viewSelected);
        bind(tim, tam, "F4", "dock-edit", this::editSelected);
        bind(tim, tam, "ctrl R", "dock-refresh", () -> navigate(path));
        bind(tim, tam, "BACK_SPACE", "dock-up",
                () -> speed.backspace(this::goUp));
        // Enter opens the selection; with an active speed search it commits
        // the match first. Escape only ever cancels a running search.
        bind(tim, tam, "ENTER", "dock-open", speed::commit);
        bind(tim, tam, "ESCAPE", "dock-speed-escape", speed::escape);
        // Typing alone searches; the hotkey is for those who reach for one.
        // Both spellings everywhere: Ctrl+F is the Windows/Linux habit,
        // Cmd+F the Mac one (and Cmd never reaches the table on the others).
        bind(tim, tam, "ctrl F", "dock-speed-start", speed::start);
        bind(tim, tam, "meta F", "dock-speed-start", speed::start);

        // Arrow-down from an empty selection must not start on the ".." row:
        // a scan walks down the listing, never up out of the directory. The
        // stock action picks row 0, so that first move drops to the first
        // real entry instead (the same row every keyboard landing picks).
        // ".." stays reachable with arrow-up, and a directory holding nothing
        // else still selects it.
        javax.swing.Action stockNextRow = tam.get("selectNextRow");
        tam.put("selectNextRow", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                int first = table.getSelectedRow() < 0 ? firstRealRow() : -1;
                if (first > 0) {
                    table.changeSelection(first, 0, false, false);
                    return;
                }
                stockNextRow.actionPerformed(e);
            }
        });
    }

    private static void bind(javax.swing.InputMap im, javax.swing.ActionMap am,
                             String key, String name, Runnable action) {
        im.put(KeyStroke.getKeyStroke(key), name);
        am.put(name, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { action.run(); }
        });
    }

    // ---- navigation ----

    /**
     * Navigate with history tracking. A "host-path!/inner" string mounts the
     * archive and continues inside it; a bare string stays on whatever the
     * pane is showing (inside a mount that means an inner path).
     */
    public void navigate(String target) {
        if (fs instanceof ArchiveFs) {
            loadNavigate(fs, fs.normalize(target));
            return;
        }
        String mount = archiveMountTarget(target);
        if (mount != null) {
            mountAt(mount, target.substring(mount.length() + 1));
            return;
        }
        loadNavigate(fs, fs.normalize(target));
    }

    /**
     * The archive-interior "foo.zip!/inner" address applies only when the
     * pre-bang segment names an archive. A directory whose own name simply
     * carries a '!' navigates like any other folder — those are common on
     * real servers. Returns the archive path to mount, or null. Pure, so
     * the rule is testable without I/O.
     */
    static String archiveMountTarget(String target) {
        int bang = target.indexOf('!');
        if (bang <= 0) return null;
        String outer = target.substring(0, bang);
        int cut = Math.max(outer.lastIndexOf('/'), outer.lastIndexOf('\\'));
        String name = cut < 0 ? outer : outer.substring(cut + 1);
        return FileIcons.kindOf(name) == FileIcons.Kind.ARCHIVE ? outer : null;
    }

    /** Navigates a specific filesystem — host crumbs, mount exits. */
    public void navigatePlace(FileSystem targetFs, String target) {
        loadNavigate(targetFs, targetFs.normalize(target));
    }

    private void loadNavigate(FileSystem targetFs, String t) {
        if (targetFs != fs || !t.equals(path)) {
            if (!path.isEmpty()) backStack.push(new Place(fs, path));
            forwardStack.clear();
        }
        load(targetFs, t);
    }

    private void goBack() {
        if (backStack.isEmpty()) return;
        forwardStack.push(new Place(fs, path));
        Place p = backStack.pop();
        load(p.fs(), p.path());
    }

    private void goForward() {
        if (forwardStack.isEmpty()) return;
        backStack.push(new Place(fs, path));
        Place p = forwardStack.pop();
        load(p.fs(), p.path());
    }

    /**
     * Loads a directory into the table without touching history. The place
     * is committed to the pane only when the listing succeeds: on failure
     * the pane keeps the directory it still shows (a dead link must not
     * advance the path bar click by click). Unchecked exceptions count as
     * failures too — SMB signals dead connections with them.
     */
    private void load(String target) { load(fs, target); }

    private void load(FileSystem targetFs, String target) {
        Set<FileSystem> before = liveFilesystems();
        String previous = path;
        FileSystem previousFs = fs;
        rememberSelection(previous);
        fs = targetFs;
        path = target;
        pathBar.setPath(target);
        setBusy(true);
        int gen = ++generation;
        Thread.ofVirtual().name("dock-list").start(() -> {
            try {
                List<FileEntry> entries = targetFs.list(target);
                List<FileEntry> display = FileTableModel.displayList(
                        withParentRow(entries, target), sortColumn, sortAscending,
                        ViewSettings.showHidden(), dotPrefixedHidden());
                SwingUtilities.invokeLater(() -> {
                    if (gen != generation) return;
                    current = entries;
                    everListed = true;
                    applyDisplay(display, target);
                    restoreSelection(target);
                    if (dirHook != null) dirHook.accept(targetFs, target);
                    if (firstListingHook != null) {
                        Runnable hook = firstListingHook;
                        firstListingHook = null;
                        hook.run();
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (gen != generation) return;
                    Toast.show(FilePane.this, "Could not list " + target + ": "
                            + shortError(ex), Glyphs.WARNING);
                    if (!everListed && !target.equals(targetFs.normalize(targetFs.home()))) {
                        // The opening directory — a remembered path may be
                        // gone — never landed, and a pane with nothing to
                        // revert to would sit empty: fall back to home.
                        load(targetFs, targetFs.normalize(targetFs.home()));
                        return;
                    }
                    if (!previous.isEmpty() && path.equals(target)) {
                        path = previous;
                        fs = previousFs;
                        pathBar.setPath(previous);
                    }
                    setBusy(false);
                });
            }
        });
        releaseIfOrphaned(before);
    }

    /** The pane's current filesystem plus everything history still holds. */
    private Set<FileSystem> liveFilesystems() {
        Set<FileSystem> live = new HashSet<>();
        live.add(fs);
        for (Place p : backStack) live.add(p.fs());
        for (Place p : forwardStack) live.add(p.fs());
        return live;
    }

    /** A mount that left the view and history is done: its selection memory
     *  goes, and so does it — unless a transfer is still extracting from it. */
    private void releaseIfOrphaned(Set<FileSystem> before) {
        Set<FileSystem> live = liveFilesystems();
        for (FileSystem f : before) {
            if (live.contains(f)) continue;
            selectionMemory.keySet().removeIf(p -> p.fs() == f);
            if (f instanceof ArchiveFs a
                    && !dock.core.transfer.TransferEngine.GLOBAL.hasActive(a)) {
                a.close();
            }
        }
    }

    /** The pane is going away: close every mount it still holds. */
    public void releaseMounts() {
        for (FileSystem f : liveFilesystems()) {
            if (f instanceof ArchiveFs a) a.close();
        }
        selectionMemory.keySet().removeIf(p -> p.fs() instanceof ArchiveFs);
    }

    /** Re-sorts/re-filters the already-loaded directory (no I/O). */
    private void refreshDisplay() {
        int gen = ++generation;
        Thread.ofVirtual().start(() -> {
            List<FileEntry> display = FileTableModel.displayList(
                    withParentRow(current, path), sortColumn, sortAscending,
                    ViewSettings.showHidden(), dotPrefixedHidden());
            SwingUtilities.invokeLater(() -> {
                if (gen != generation) return;
                applyDisplay(display, path);
            });
        });
    }

    /**
     * Remembers the selection of the directory being left, so scanning
     * folders runs on the keyboard alone: enter, check contents,
     * Backspace, arrow down, enter — and the cursor is where it was.
     */
    private void rememberSelection(String dir) {
        for (int r : table.getSelectedRows()) {
            FileEntry e = model.row(r);
            if (e == FileEntry.PARENT) continue;
            selectionMemory.put(new Place(fs, dir), e.name());
            return;
        }
        selectionMemory.remove(new Place(fs, dir));
    }

    /**
     * Puts the cursor back after a listing lands: the entry selected when
     * the directory was left, scrolled into view. The memory is consumed
     * on use, and a first visit or a vanished name selects nothing — a
     * chdir must never inherit the previous directory's selection.
     */
    private void restoreSelection(String dir) {
        // A destructive or creating op placed the cursor on purpose: the
        // row that now holds the entry after the deleted one, or the new
        // folder's name. It wins over the directory memory.
        if (pendingCursorName != null || pendingCursorRow >= 0) {
            String wantedName = pendingCursorName;
            int wantedRow = pendingCursorRow;
            pendingCursorName = null;
            pendingCursorRow = -1;
            int r = -1;
            if (wantedName != null) {
                for (int i = 0; i < model.getRowCount(); i++) {
                    if (wantedName.equals(model.row(i).name())) { r = i; break; }
                }
            } else {
                // Deleting shifts the entries below up by one row; a
                // directory emptied of everything else starts unselected.
                r = Math.min(wantedRow, model.getRowCount() - 1);
                if (r >= 0 && model.row(r) == FileEntry.PARENT) r = -1;
            }
            if (r >= 0) {
                table.setRowSelectionInterval(r, r);
                table.scrollRectToVisible(table.getCellRect(r, 0, true));
                return;
            }
            table.clearSelection();
            return;
        }
        String wanted = selectionMemory.remove(new Place(fs, dir));
        if (wanted != null) {
            for (int r = 0; r < model.getRowCount(); r++) {
                if (wanted.equals(model.row(r).name())) {
                    table.setRowSelectionInterval(r, r);
                    table.scrollRectToVisible(table.getCellRect(r, 0, true));
                    return;
                }
            }
        }
        table.clearSelection();
    }

    /** The row a keyboard landing picks: the first entry below "..", or
     *  -1 when the listing holds nothing else. */
    private int firstRealRow() {
        int r = model.getRowCount() > 0 && model.row(0) == FileEntry.PARENT ? 1 : 0;
        return r < model.getRowCount() ? r : -1;
    }

    private List<FileEntry> withParentRow(List<FileEntry> entries, String dir) {
        // A mount's root keeps "..": there it exits to the folder holding
        // the archive, even though the inner tree has nothing above "/".
        if (fs.parent(dir).equals(dir) && !(fs instanceof ArchiveFs)) return entries;
        List<FileEntry> out = new ArrayList<>(entries.size() + 1);
        out.add(FileEntry.PARENT);
        out.addAll(entries);
        return out;
    }

    private void applyDisplay(List<FileEntry> display, String dir) {
        // Inside an archive, ".." wears the archive mark at every depth —
        // at the root it is the way out of the mount, deeper it only climbs.
        model.setParentKind(fs instanceof ArchiveFs
                ? FileIcons.Kind.ARCHIVE : FileIcons.Kind.PARENT);
        model.setData(display);
        speed.reset();
        setBusy(false);
        totalDirs = display.stream().filter(e -> e.directory() && e != FileEntry.PARENT).count();
        totalFiles = display.stream().filter(e -> !e.directory()).count();
        updateSummary();
        upButton.setEnabled(!fs.parent(dir).equals(dir) || fs instanceof ArchiveFs);
        backButton.setEnabled(!backStack.isEmpty());
        forwardButton.setEnabled(!forwardStack.isEmpty());
    }

    /**
     * The strip under the table: selection stats while a selection exists
     * (the commander convention), the directory's totals otherwise. The
     * parent ("..") row never counts toward either.
     */
    private void updateSummary() {
        long selDirs = 0, selFiles = 0, selBytes = 0;
        for (int r : table.getSelectedRows()) {
            FileEntry e = model.row(r);
            if (e == FileEntry.PARENT) continue;
            if (e.directory()) selDirs++;
            else { selFiles++; selBytes += e.size(); }
        }
        if (selDirs + selFiles == 0) {
            summary.setText("%d directories, %d files".formatted(totalDirs, totalFiles));
            summaryBytes.setText("");
            return;
        }
        summary.setText("%d selected — %d directories, %d files".formatted(
                selDirs + selFiles, selDirs, selFiles));
        // The size rides its own mono label so its digits line up.
        summaryBytes.setText(selFiles > 0 ? "· " + Fmt.bytes(selBytes) : "");
    }

    /** The footer's hover line for a row — the table's own columns, joined. */
    private static String hoverText(FileEntry e) {
        StringBuilder sb = new StringBuilder(e.name());
        String size = FileTableModel.sizeString(e);
        if (!size.isEmpty()) sb.append(" · ").append(size);
        else if (e.share()) sb.append(" · share");
        else if (e.directory()) sb.append(" · directory");
        String date = FileTableModel.dateString(e);
        if (!date.isEmpty()) sb.append(" · modified ").append(date);
        String attrs = FileTableModel.attrsString(e);
        if (!attrs.isEmpty()) sb.append(" · ").append(attrs);
        return sb.toString();
    }

    // ---- file operations ----

    private void openSelected() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        FileEntry e = model.row(row);
        if (e == FileEntry.PARENT) goUp();
        else if (e.directory()) navigate(fs.child(path, e.name()));
        else openByKind(e.name(), FileIcons.kindOf(e.name()));
    }

    /**
     * Enter on a file: route it to its surface by kind. Kinds the extension
     * map can't place get one sniff of the head bytes first, so a camera
     * file with its extension stripped still plays — and what the sniff
     * can't identify either goes to the editor, which refuses binary with
     * a reason instead of leaving Enter a silent no-op.
     */
    private void openByKind(String name, FileIcons.Kind kind) {
        if (kind != FileIcons.Kind.FILE) {
            routeByKind(name, kind);
            return;
        }
        sniffThen(name, sniffed -> {
            if (sniffed == FileIcons.Kind.FILE) editFile(name);
            else routeByKind(name, sniffed);
        });
    }

    private void routeByKind(String name, FileIcons.Kind kind) {
        if (kind == FileIcons.Kind.ARCHIVE) mountAt(fs.child(path, name), "/");
        else if (kind == FileIcons.Kind.IMAGE) viewFile(name);
        else if (kind == FileIcons.Kind.MARKDOWN) viewMarkdown(name);
        else if (kind == FileIcons.Kind.VIDEO || kind == FileIcons.Kind.AUDIO) playFile(name);
        else if (EditorPanel.edits(name)) editFile(name);
        // What's left is binary with no surface of its own yet; F4 still
        // opens the editor on anything.
    }

    /**
     * One 512-byte peek for a name the extension map can't place. The read
     * runs off the EDT and the verdict is applied only if the pane hasn't
     * navigated meanwhile — the listing fast path (pure name math) never
     * pays for this; it runs exactly once, at the moment a file is opened.
     */
    private void sniffThen(String name, java.util.function.Consumer<FileIcons.Kind> route) {
        FileSystem files = fs;
        String target = fs.child(path, name);
        int gen = generation;
        Thread.ofVirtual().name("dock-sniff").start(() -> {
            byte[] head = new byte[0];
            try (java.io.InputStream in = files.read(target)) {
                head = in.readNBytes(512);
            } catch (Exception ex) {
                // Unreadable: let the next surface explain, with its own error.
            }
            byte[] verdict = head;
            SwingUtilities.invokeLater(() -> {
                if (gen != generation) return;
                route.accept(FileIcons.sniffKind(verdict));
            });
        });
    }

    /** Up one level: out of a mount when at its root, the parent otherwise. */
    private void goUp() {
        if (fs instanceof ArchiveFs a && path.equals("/")) {
            navigatePlace(a.host(), a.hostDir());
            return;
        }
        navigate(fs.parent(path));
    }

    /**
     * Mounts the archive at {@code archivePath} (on whatever the pane shows)
     * and continues at {@code inner} ("/" when blank). Opening and indexing
     * run off the EDT; a navigation that happened meanwhile discards the
     * mount.
     */
    private void mountAt(String archivePath, String inner) {
        String target = fs.normalize(archivePath);
        String into = inner.isBlank() ? "/" : SlashPaths.normalize(inner);
        int cut = Math.max(target.lastIndexOf('/'), target.lastIndexOf('\\'));
        String name = cut < 0 ? target : target.substring(cut + 1);
        setBusy(true);
        int gen = generation;
        FileSystem hostFs = fs;
        Thread.ofVirtual().start(() -> {
            try {
                ArchiveFs mount = ArchiveFs.open(hostFs, target);
                SwingUtilities.invokeLater(() -> {
                    if (gen != generation) {
                        mount.close();
                        return;
                    }
                    loadNavigate(mount, into);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (gen != generation) return;
                    setBusy(false);
                    Toast.show(FilePane.this, "Could not open " + name + ": "
                            + shortError(ex), Glyphs.WARNING);
                });
            }
        });
    }

    /** The context-menu escape hatch: peek inside anything, extension or not. */
    private void openSelectionAsArchive() {
        List<FileEntry> selected = selectedEntries();
        if (selected.size() != 1 || selected.get(0).directory()) return;
        mountAt(fs.child(path, selected.get(0).name()), "/");
    }

    private void showContextMenu(MouseEvent e) {
        int row = table.rowAtPoint(e.getPoint());
        if (row >= 0 && !table.getSelectionModel().isSelectedIndex(row)) {
            table.setRowSelectionInterval(row, row);
        }
        buildContextMenu().show(table, e.getX(), e.getY());
    }

    /** The right-click menu (tests assert its accelerators). */
    public JPopupMenu buildContextMenu() {
        JPopupMenu menu = new JPopupMenu();
        // A virtual listing (SMB's share selection) can't be reshaped: the
        // mutating actions stay out of the menu entirely rather than firing
        // and failing on the wire.
        if (!fs.immutableListing(path)) {
            menu.add(item("Paste", Glyphs.PASTE, "ctrl V", this::pasteFromClipboard));
            menu.add(item("New folder", Glyphs.FOLDER, "ctrl D", this::newFolder));
            menu.addSeparator();
            menu.add(item("Rename…", Glyphs.EDIT, "F2", this::renameSelected));
            menu.add(item("Delete", Glyphs.TRASH, "DELETE", this::deleteSelected));
        }
        JMenuItem props = item("Properties…", Glyphs.INFO, "alt ENTER", this::showProperties);
        props.setEnabled(selectedEntries().size() == 1);
        menu.add(props);
        // View attempts any single file — markdown renders, images open,
        // the rest decode-refuse.
        JMenuItem view = item("View", Glyphs.EYE, "F3", this::viewSelected);
        List<FileEntry> viewable = selectedEntries();
        view.setEnabled(viewable.size() == 1 && !viewable.get(0).directory());
        menu.add(view);
        // Play shows for media, where View and Enter mean the same thing —
        // the row opens in the streaming player, not the image viewer.
        JMenuItem play = item("Play", Glyphs.FILM, null, () -> {
            List<FileEntry> selected = selectedEntries();
            if (selected.size() == 1 && !selected.get(0).directory())
                playFile(selected.get(0).name());
        });
        List<FileEntry> playable = selectedEntries();
        play.setEnabled(playable.size() == 1 && !playable.get(0).directory()
                && isMedia(playable.get(0).name()));
        menu.add(play);
        JMenuItem edit = item("Edit", Glyphs.EDIT, "F4", this::editSelected);
        List<FileEntry> editable = selectedEntries();
        edit.setEnabled(editable.size() == 1 && !editable.get(0).directory());
        menu.add(edit);
        JMenuItem peek = item("Open as archive", Glyphs.ARCHIVE, null,
                this::openSelectionAsArchive);
        List<FileEntry> peekable = selectedEntries();
        peek.setEnabled(peekable.size() == 1 && !peekable.get(0).directory());
        menu.add(peek);
        menu.addSeparator();
        menu.add(item("Refresh", Glyphs.SYNC, "ctrl R", () -> navigate(path)));
        return menu;
    }

    /** Accelerator strings match bindKeys() specs so the shown shortcut can
     * never drift from the binding that actually fires. */
    private JMenuItem item(String text, String glyph, String accelerator, Runnable action) {
        JMenuItem mi = new JMenuItem(text,
                Glyphs.icon(glyph, 15, () -> UIManager.getColor("Label.disabledForeground")));
        if (accelerator != null) mi.setAccelerator(KeyStroke.getKeyStroke(accelerator));
        mi.addActionListener(ev -> action.run());
        return mi;
    }

    /**
     * Ctrl+D: the new folder is named in place — a draft row right below
     * ".." whose name cell is a text field. Nothing touches the filesystem
     * until Enter commits the name; Escape (or any refresh that replaces
     * the listing) discards the row.
     */
    private void newFolder() {
        if (fs.immutableListing(path)) return;    // a share list holds no drafts
        if (draftEntry != null) return;    // one draft at a time
        draftEntry = new FileEntry("", true, 0, 0, null, false);
        model.setDraft(draftEntry);
        int row = model.getRowCount() > 0 && model.row(0) == FileEntry.PARENT ? 1 : 0;
        model.insertRow(row, draftEntry);
        table.setRowSelectionInterval(row, row);
        // The draft sits at the top of the listing — bring it into view
        // before editing, or a pane scrolled deep hides its own input.
        table.scrollRectToVisible(table.getCellRect(row, 0, true));
        table.editCellAt(row, 0);
        JTextField field = (JTextField) table.getEditorComponent();
        field.setText("New folder");
        field.selectAll();
        field.requestFocusInWindow();
    }

    /** The draft's end: commit mkdirs and lands the cursor on the new
     *  folder; cancel just drops the row. */
    private void finishDraft(boolean commit) {
        FileEntry draft = draftEntry;
        if (draft == null) return;
        draftEntry = null;
        String name = commit
                ? String.valueOf(draftEditor.getCellEditorValue()).strip()
                : "";
        model.removeRow(draft);
        if (name.isEmpty()) return;
        pendingCursorName = name;
        runOp(() -> fs.mkdir(fs.child(path, name)), "create folder " + name);
    }

    private void renameSelected() {
        if (fs.immutableListing(path)) return;
        int row = table.getSelectedRow();
        if (row < 0) return;
        FileEntry e = model.row(row);
        if (e == FileEntry.PARENT) return;
        String name = (String) JOptionPane.showInputDialog(this, "New name:", "Rename",
                JOptionPane.PLAIN_MESSAGE, null, null, e.name());
        if (name == null || name.isBlank() || name.equals(e.name())) return;
        runOp(() -> fs.rename(fs.child(path, e.name()), fs.child(path, name)),
                "rename " + e.name());
    }

    private void deleteSelected() {
        if (fs.immutableListing(path)) return;
        List<FileEntry> selected = selectedEntries();
        if (selected.isEmpty()) return;
        if (!confirmDelete(selected)) return;
        // The cursor continues where the deleted block stood: after the
        // refresh that row holds the entry that followed it.
        int anchor = -1;
        for (int r : table.getSelectedRows()) {
            if (model.row(r) != FileEntry.PARENT) { anchor = r; break; }
        }
        pendingCursorRow = anchor;
        runOp(() -> {
            for (FileEntry e : selected) fs.deleteTree(fs.child(path, e.name()));
        }, "delete");
    }

    /** The delete confirmation dialog (tests answer programmatically). */
    private boolean confirmDelete(List<FileEntry> selected) {
        if (autoConfirmDelete) return true;
        int confirm = JOptionPane.showConfirmDialog(this,
                selected.size() == 1
                        ? "Delete \"" + selected.get(0).name() + "\"?"
                        : "Delete " + selected.size() + " items?",
                "Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return confirm == JOptionPane.YES_OPTION;
    }

    /** Tests skip the modal confirmation. */
    public void autoConfirmDeleteForTest() { autoConfirmDelete = true; }

    /** Properties (and chmod) for exactly one selected entry. */
    private void showProperties() {
        List<FileEntry> selected = selectedEntries();
        if (selected.size() != 1) return;
        new PropertiesDialog(this, fs, path, selected.get(0), this::reload).setVisible(true);
    }

    public List<FileEntry> selectedEntries() {
        int[] rows = table.getSelectedRows();
        List<FileEntry> out = new ArrayList<>(rows.length);
        for (int r : rows) {
            FileEntry e = model.row(r);
            if (e != FileEntry.PARENT) out.add(e);
        }
        return out;
    }

    /** Runs a blocking op off-EDT, then refreshes; errors become toasts. */
    private void runOp(IoOp op, String what) {
        setBusy(true);
        Thread.ofVirtual().start(() -> {
            try {
                op.run();
                SwingUtilities.invokeLater(() -> load(path));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setBusy(false);
                    pendingCursorRow = -1;
                    pendingCursorName = null;
                    Toast.show(FilePane.this, "Could not " + what + ": " + shortError(ex),
                            Glyphs.WARNING);
                });
            }
        });
    }

    private interface IoOp { void run() throws IOException; }

    private void setBusy(boolean b) {
        // The path bar's own background is the progress indicator: a
        // separate strip that appears for the listing would reflow the
        // toolbar and shift the table under it (3px flicker per rescan).
        pathBar.setBusy(b);
        refreshButton.setEnabled(!b);
    }

    // ---- clipboard paste ----

    /** The clipboard the pane pastes from; injectable so tests hand it an
     *  Explorer-style file list without touching the user's real one. */
    private static volatile java.util.function.Supplier<
            java.awt.datatransfer.Transferable> clipboard = FilePane::systemClipboard;

    private static java.awt.datatransfer.Transferable systemClipboard() {
        try {
            return java.awt.Toolkit.getDefaultToolkit()
                    .getSystemClipboard().getContents(null);
        } catch (IllegalStateException e) {
            return null;   // clipboard unavailable (locked by another app)
        }
    }

    /** Swaps the paste source (a fake file list); null restores the system
     *  clipboard. */
    public static void useClipboardForTest(
            java.util.function.Supplier<java.awt.datatransfer.Transferable> source) {
        clipboard = source == null ? FilePane::systemClipboard : source;
    }

    /** Ctrl+V: files copied in the OS file explorer paste straight into
     *  the pane's directory — the exact import the drag-and-drop path
     *  takes, so dropping and pasting behave identically (grouping by
     *  source directory, one transfer job per group). */
    private void pasteFromClipboard() {
        if (fs.immutableListing(path)) return;
        java.awt.datatransfer.Transferable t = clipboard.get();
        if (t == null || !(t.isDataFlavorSupported(DOCK_FLAVOR)
                || t.isDataFlavorSupported(
                        java.awt.datatransfer.DataFlavor.javaFileListFlavor))) {
            Toast.show(this, "The clipboard holds no files.", Glyphs.WARNING);
            return;
        }
        dropInto(t);
    }

    // ---- drag & drop ----

    /** Payload dragged between the app's panes (same JVM only). */
    public record DragPayload(FileSystem fs, String srcDir, List<FileEntry> entries) {}

    public static final java.awt.datatransfer.DataFlavor DOCK_FLAVOR =
            new java.awt.datatransfer.DataFlavor(DragPayload.class, "Universal Explorer files");

    private final class DragOut extends javax.swing.TransferHandler {
        @Override
        protected java.awt.datatransfer.Transferable createTransferable(javax.swing.JComponent c) {
            // Shares don't drag; archive entries do — dragging out extracts.
            if (fs.immutableListing(path) && !fs.extractable(path)) return null;
            List<FileEntry> selected = selectedEntries();
            if (selected.isEmpty()) return null;
            return new DockTransferable(new DragPayload(fs, path(), selected));
        }

        @Override
        public int getSourceActions(javax.swing.JComponent c) {
            return COPY;
        }

        @Override
        public boolean canImport(javax.swing.TransferHandler.TransferSupport support) {
            // A virtual listing is not a drop target — the drag cursor
            // shows the no-drop shape over the whole pane.
            if (fs.immutableListing(path)) return false;
            return support.isDataFlavorSupported(DOCK_FLAVOR)
                    || support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
        }

        @Override
        public boolean importData(javax.swing.TransferHandler.TransferSupport support) {
            return dropInto(support.getTransferable());
        }
    }

    /** Handles a drop onto this pane (dock payload or OS file list). */
    private boolean dropInto(java.awt.datatransfer.Transferable t) {
        if (fs.immutableListing(path)) return false;
        try {
            if (t.isDataFlavorSupported(DOCK_FLAVOR)) {
                DragPayload payload = (DragPayload) t.getTransferData(DOCK_FLAVOR);
                if (payload.fs() == fs && payload.srcDir().equals(path())) {
                    Toast.show(FilePane.this, "Source and target are the same directory.",
                            Glyphs.WARNING);
                    return false;
                }
                dock.core.transfer.TransferEngine.GLOBAL.enqueue(
                        payload.fs(), payload.srcDir(), payload.entries(), fs, path(), false);
                return true;
            }
            if (t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                @SuppressWarnings("unchecked")
                List<java.io.File> files = (List<java.io.File>)
                        t.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                // Group by parent directory: enqueue takes one source dir per call.
                java.util.Map<String, List<FileEntry>> groups = new java.util.LinkedHashMap<>();
                for (java.io.File f : files) {
                    java.nio.file.Path p = f.toPath();
                    java.nio.file.attribute.BasicFileAttributes a =
                            java.nio.file.Files.readAttributes(p,
                                    java.nio.file.attribute.BasicFileAttributes.class);
                    groups.computeIfAbsent(p.getParent().toString(), k -> new ArrayList<>())
                            .add(new FileEntry(p.getFileName().toString(), a.isDirectory(),
                                    a.size(), a.lastModifiedTime().toMillis(), null, false));
                }
                for (var e : groups.entrySet()) {
                    dock.core.transfer.TransferEngine.GLOBAL.enqueue(
                            LocalFs.INSTANCE, e.getKey(), e.getValue(), fs, path(), false);
                }
                return !files.isEmpty();
            }
        } catch (Exception e) {
            Toast.show(FilePane.this, "Drop failed: " + e.getMessage(), Glyphs.WARNING);
        }
        return false;
    }

    private final class DockTransferable implements java.awt.datatransfer.Transferable {
        private final DragPayload payload;

        DockTransferable(DragPayload payload) { this.payload = payload; }

        @Override
        public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
            return new java.awt.datatransfer.DataFlavor[]{
                    DOCK_FLAVOR,
                    java.awt.datatransfer.DataFlavor.javaFileListFlavor,
                    java.awt.datatransfer.DataFlavor.stringFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor) {
            if (flavor.equals(DOCK_FLAVOR)) return true;
            if (flavor.equals(java.awt.datatransfer.DataFlavor.stringFlavor)) return true;
            return flavor.equals(java.awt.datatransfer.DataFlavor.javaFileListFlavor)
                    && fs == LocalFs.INSTANCE;
        }

        @Override
        public Object getTransferData(java.awt.datatransfer.DataFlavor flavor)
                throws java.awt.datatransfer.UnsupportedFlavorException, IOException {
            if (flavor.equals(DOCK_FLAVOR)) return payload;
            if (flavor.equals(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                StringBuilder sb = new StringBuilder();
                for (FileEntry e : payload.entries()) {
                    sb.append(payload.fs().child(payload.srcDir(), e.name())).append('\n');
                }
                return sb.toString();
            }
            if (flavor.equals(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                List<java.io.File> files = new ArrayList<>();
                for (FileEntry e : payload.entries()) {
                    files.add(new java.io.File(
                            payload.fs().child(payload.srcDir(), e.name())));
                }
                return files;
            }
            throw new java.awt.datatransfer.UnsupportedFlavorException(flavor);
        }
    }

    // ---- helpers ----

    /** Overlays the speed-search badge on top of the file area. */
    @Override protected void paintChildren(java.awt.Graphics g) {
        super.paintChildren(g);
        speed.paintBadge((java.awt.Graphics2D) g, listArea());
    }

    /** The rows' viewport in pane coordinates — below the column headers. */
    private java.awt.Rectangle listArea() {
        return javax.swing.SwingUtilities.convertRectangle(scroll,
                scroll.getViewport().getBounds(), this);
    }

    // ---- test hooks ----

    /** Feeds a real KEY_TYPED event to the table (the typing path). */
    public void typeForTest(String chars) {
        for (char c : chars.toCharArray()) {
            table.processKeyForTest(new java.awt.event.KeyEvent(table,
                    java.awt.event.KeyEvent.KEY_TYPED,
                    System.currentTimeMillis(), 0,
                    java.awt.event.KeyEvent.VK_UNDEFINED, c,
                    java.awt.event.KeyEvent.KEY_LOCATION_UNKNOWN));
        }
    }

    /** Feeds a real KEY_PRESSED event to the table (Escape etc.). */
    public void pressForTest(int keyCode) {
        table.processKeyForTest(new java.awt.event.KeyEvent(table,
                java.awt.event.KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), 0, keyCode,
                java.awt.event.KeyEvent.CHAR_UNDEFINED,
                java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
    }

    /** Fires the table's binding for a keystroke (the real keyboard path):
     *  the focused map first, then the ancestor map where the table's own
     *  bindings (the arrows) live. */
    public void fireTableActionForTest(String spec) {
        KeyStroke ks = KeyStroke.getKeyStroke(spec);
        Object name = table.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).get(ks);
        if (name == null) {
            name = table.getInputMap(
                    javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(ks);
        }
        if (name == null) throw new IllegalStateException("no binding for " + spec);
        javax.swing.Action action = table.getActionMap().get(name);
        if (action == null) throw new IllegalStateException("no action for " + spec);
        action.actionPerformed(new java.awt.event.ActionEvent(table, 0, "test"));
    }

    /** Ends an active search without touching selection or scroll. */
    public void searchResetForTest() { speed.reset(); }

    /** {@code changed()} call count; the viewport scroll listener shows here. */
    public int searchChangedCountForTest() { return speed.changedCount; }

    public boolean searchActiveForTest() { return speed.active(); }
    public String searchQueryForTest() { return speed.query(); }
    public boolean searchNoMatchForTest() { return speed.noMatch(); }
    public int searchMatchCountForTest() { return speed.matchCount(); }
    /** Where the badge paints, in pane coordinates; null while hidden. */
    public java.awt.Rectangle searchBadgeBoundsForTest() { return speed.badgeBounds(listArea()); }
    /** The rows' viewport in pane coordinates. */
    public java.awt.Rectangle listAreaForTest() { return listArea(); }

    private static JButton toolButton(String glyph, String tooltip) {
        // Uniform ink scaling keeps thin chevrons and dense glyphs the same
        // optical size; 20px ink in a 30px hit area matches IntelliJ toolbars.
        JButton b = new JButton(Glyphs.iconUniform(glyph, Tokens.ICON_LARGE,
                FileTableModel::muted));
        b.setToolTipText(tooltip);
        // Correct FlatLaf key is "JButton.buttonType" — "FlatLaf.buttonType"
        // is silently ignored and leaves a bordered regular button.
        b.putClientProperty("JButton.buttonType", "borderless");
        // Ghost at rest; FlatLaf paints only hover/pressed state fills.
        // Rollover must be enabled for mouse hover to reach the model.
        b.setRolloverEnabled(true);
        b.setFocusable(false);
        b.setPreferredSize(new Dimension(30, 30));
        b.setMaximumSize(new Dimension(30, 30));
        return b;
    }

    private static String shortError(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m.split("\n")[0];
    }
}
