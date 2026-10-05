package dock.ui;

import dock.kit.Fmt;
import dock.kit.Toast;
import dock.kit.IconTile;
import dock.kit.EmptyState;
import dock.kit.CardPanel;
import dock.kit.FontRegistry;
import dock.kit.Glyphs;
import dock.kit.StatusLine;
import dock.kit.Tokens;
import dock.core.config.Site;
import dock.core.config.Sites;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * The landing screen: every saved session, one click away. A filter field on
 * top, recency-sorted rows (single click or Enter connects with the stored
 * secret), and a row menu for edit/delete. Arrows walk the visible rows —
 * UP past the first returns to the filter — and typing a plain character
 * anywhere on the screen jumps to the filter and lands there — the
 * launcher's counterpart of the file table's speed search. Shows the
 * first-run hero card while no site is saved yet.
 */
public final class SessionsHome extends JPanel {

    /** Window-level actions the launcher needs. All methods run on the EDT. */
    public interface Host {
        /**
         * Connects using the saved site's settings/secret. The outcome lands
         * on the EDT exactly once: non-null fs = connected; non-null error =
         * failed; both null = aborted (a dialog took over, e.g. the stored
         * password is missing and the edit form opened instead).
         */
        void connect(Site site, BiConsumer<Exception, dock.core.fs.FileSystem> outcome);

        void newSession();

        /** Opens a shell on this machine as a terminal tab — no session needed. */
        void localShell();

        /** Opens the new-session dialog, prefilled when text parses as [user@]host[:port]. */
        void quickConnect(String typed);

        void editSite(Site site);

        /** The hosts of ~/.ssh/config to list beside the saved sessions,
         *  read fresh on every refresh; empty when that mode is off. */
        default List<Site> sshConfigSites() { return List.of(); }
    }

    private static final int CARD_WIDTH = 560;
    private static final int ROW_HEIGHT = 52;
    private static final int BUSY_STRIP_HEIGHT = 4;
    private static final int MAX_VISIBLE_ROWS = 8;
    private static final int SECTION_HEIGHT = 28;

    /** The launcher's per-protocol mark, from the backend registry. */
    private static String protocolGlyph(dock.core.config.Protocol protocol) {
        var backend = dock.core.spi.Backends.of(protocol);
        return backend != null ? backend.glyph() : Glyphs.SERVER;
    }

    private final Host host;
    private final List<Row> rows = new ArrayList<>();
    private JTextField searchField;
    private JLabel noMatchLabel;
    private JPanel rowsPanel;
    /** The ~/.ssh/config section's caption; null when that section is empty. */
    private JLabel sshHeader;

    public SessionsHome(Host host) {
        this.host = host;
        setLayout(new GridBagLayout());
        refresh();
    }

    /** Rebuilds from sessions.json (recency order) plus the ~/.ssh/config
     *  hosts no saved session already names; also swaps hero/launcher. */
    public void refresh() {
        removeAll();
        rows.clear();
        List<Site> sites = Sites.byRecency(Sites.load());
        java.util.Set<String> saved = new java.util.HashSet<>();
        for (Site s : sites) saved.add(s.name());
        List<Site> sshSites = host.sshConfigSites().stream()
                .filter(s -> !saved.contains(s.name())).toList();
        if (sites.isEmpty() && sshSites.isEmpty()) {
            add(new EmptyState(host::newSession));
        } else {
            add(buildLauncher(sites, sshSites));
        }
        revalidate();
        repaint();
    }

    public void focusSearch() {
        if (searchField != null) searchField.requestFocusInWindow();
    }

    /** A control's explanation goes to the footer, not a floating popup —
     *  the same convention as the file table's rows. */
    private static void statusHint(javax.swing.JComponent c, String text) {
        c.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { StatusLine.publish(text); }
            @Override public void mouseExited(MouseEvent e)  { StatusLine.clear(); }
        });
    }

    /**
     * A plain character typed anywhere on this screen — rows, buttons,
     * anything but the field itself — jumps to the search field and lands
     * there. Key events reach only their focus owner, so the interception
     * rides a dispatcher instead of an input map (plain characters have no
     * binding anywhere, the same reason the file table's speed search
     * lives in processKeyEvent).
     */
    private final java.awt.KeyEventDispatcher searchTypeAhead = e -> {
        // Runs on the EDT before the focus owner sees the event.
        if (e.getID() != java.awt.event.KeyEvent.KEY_TYPED || searchField == null) return false;
        java.awt.Component owner = e.getComponent();
        if (owner == null || owner == searchField) return false;
        // An open menu (the row popup) keeps its own keys.
        if (javax.swing.MenuSelectionManager.defaultManager().getSelectedPath().length > 0) {
            return false;
        }
        Window win = SwingUtilities.windowForComponent(this);
        // Only while the launcher is the screen in front: a session tab or
        // another window (dialog) holding focus must type undisturbed.
        if (win == null || !isShowing()
                || SwingUtilities.windowForComponent(owner) != win) return false;
        return typeAhead(e);
    };

    @Override public void addNotify() {
        super.addNotify();
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(searchTypeAhead);
    }

    /** A screen leaving the window takes its hover hint and its type-ahead
     *  interception with it. */
    @Override public void removeNotify() {
        super.removeNotify();
        StatusLine.clear();
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removeKeyEventDispatcher(searchTypeAhead);
    }

    /**
     * The interception step: Ctrl/Alt/Meta combos and control characters
     * are shortcuts, plain characters go to the field — the same gate the
     * file table's speed search uses (Shift stays legal for uppercase).
     */
    private boolean typeAhead(java.awt.event.KeyEvent e) {
        char c = e.getKeyChar();
        if (c < ' ' || c == 0x7F || c == java.awt.event.KeyEvent.CHAR_UNDEFINED) return false;
        int mask = java.awt.event.KeyEvent.CTRL_DOWN_MASK
                | java.awt.event.KeyEvent.ALT_DOWN_MASK
                | java.awt.event.KeyEvent.META_DOWN_MASK;
        if ((e.getModifiersEx() & mask) != 0) return false;
        searchField.requestFocusInWindow();
        String text = searchField.getText();
        searchField.setText(text + c);
        searchField.setCaretPosition(text.length() + 1);
        return true;
    }

    // ---- construction ----

    private JComponent buildLauncher(List<Site> sites, List<Site> sshSites) {
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        column.setAlignmentX(CENTER_ALIGNMENT);

        column.add(new IconTile(Glyphs.iconRotated(Glyphs.PLANET, Tokens.ICON_HERO * 1.5f,
                () -> UIManager.getColor("Dock.accent"), Glyphs.PLANET_TILT_DEG), 80));
        column.add(Box.createVerticalStrut(Tokens.GAP_4));
        column.add(heading("Sessions"));
        column.add(Box.createVerticalStrut(Tokens.GAP_1));
        column.add(body("Click a session to connect"));
        column.add(Box.createVerticalStrut(Tokens.GAP_6));
        column.add(new LauncherCard(sites, sshSites));
        return column;
    }

    private final class LauncherCard extends CardPanel {

        private LauncherCard(List<Site> sites, List<Site> sshSites) {
            super(new BorderLayout());
            setMaximumSize(new Dimension(CARD_WIDTH, Integer.MAX_VALUE));

            add(buildSearchArea(), BorderLayout.NORTH);
            add(buildRowsArea(sites, sshSites), BorderLayout.CENTER);
            add(buildFooter(), BorderLayout.SOUTH);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            d.width = CARD_WIDTH;
            return d;
        }
    }

    /** Search chrome: the tinted field well, filling the card edge to edge. */
    private JComponent buildSearchArea() {
        JPanel chrome = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    // Full-bleed well: the top corners follow the card's own
                    // arc one pixel inside its border stroke, the bottom meets
                    // the rows divider flush (the shape is extended past the
                    // clip so its lower rounding never shows). The tile tint
                    // stays distinct from the card in both themes.
                    g2.setColor(UIManager.getColor("Dock.tileBackground"));
                    g2.clipRect(1, 1, getWidth() - 2, getHeight() - 1);
                    g2.fillRoundRect(1, 1, getWidth() - 2,
                            getHeight() + Tokens.ARC_CARD, Tokens.ARC_CARD,
                            Tokens.ARC_CARD);
                } finally {
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        chrome.setOpaque(false);
        chrome.setBorder(BorderFactory.createEmptyBorder(0, Tokens.GAP_2, 0, Tokens.GAP_2));

        JLabel glyph = new JLabel(Glyphs.iconUniform(Glyphs.SEARCH, Tokens.ICON_SMALL,
                SessionsHome::muted));
        glyph.setBorder(BorderFactory.createEmptyBorder(0, Tokens.GAP_1, 0, 0));

        searchField = new JTextField();
        searchField.setOpaque(false);
        // Tall bar (~1.65x the old height): the extra room lives in the
        // field's own padding so the tint well grows with it.
        searchField.setBorder(BorderFactory.createEmptyBorder(18, Tokens.GAP_2, 18, Tokens.GAP_2));
        searchField.setFont(FontRegistry.ui());
        searchField.putClientProperty("JTextField.placeholderText", "Search sessions…");
        statusHint(searchField, "Filter by name, host or user — Enter connects the first match");
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
        });
        // Enter: connect the first visible match, or hand a quick-connect
        // spec ([user@]host[:port]) to the dialog when nothing matches.
        searchField.addActionListener(e -> {
            Row first = firstVisibleRow();
            if (first != null) first.connect();
            else if (!searchField.getText().isBlank()) host.quickConnect(searchField.getText().trim());
        });
        // Arrows move into the rows; Esc clears the filter.
        var im = searchField.getInputMap(JComponent.WHEN_FOCUSED);
        var am = searchField.getActionMap();
        im.put(KeyStroke.getKeyStroke("DOWN"), "dock.firstRow");
        am.put("dock.firstRow", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                Row first = firstVisibleRow();
                if (first != null) walkTo(first);
            }
        });
        im.put(KeyStroke.getKeyStroke("ESCAPED"), "dock.clearFilter");
        am.put("dock.clearFilter", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (!searchField.getText().isEmpty()) searchField.setText("");
                else searchField.transferFocus();
            }
        });

        chrome.add(glyph, BorderLayout.WEST);
        chrome.add(searchField, BorderLayout.CENTER);
        return chrome;
    }

    private JComponent buildRowsArea(List<Site> sites, List<Site> sshSites) {
        // Preferred width must stay at zero: a wide preferred (e.g. from a
        // row's text) makes the scroll view report more than the viewport
        // width, and with the horizontal scrollbar off the excess clips off
        // the right edge — taking the row's ⋯ button with it. The vertical
        // BoxLayout stretches rows to the real viewport width at layout time.
        rowsPanel = new JPanel() {
            @Override public Dimension getPreferredSize() {
                return new Dimension(0, super.getPreferredSize().height);
            }
        };
        rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.Y_AXIS));
        rowsPanel.setOpaque(false);

        noMatchLabel = new JLabel("No matching sessions");
        noMatchLabel.setFont(FontRegistry.ui());
        noMatchLabel.setForeground(muted());
        noMatchLabel.setBorder(BorderFactory.createEmptyBorder(Tokens.GAP_3, Tokens.GAP_3,
                Tokens.GAP_3, Tokens.GAP_3));
        noMatchLabel.setVisible(false);
        rowsPanel.add(noMatchLabel);

        for (Site s : sites) {
            Row row = new Row(s, false);
            rows.add(row);
            rowsPanel.add(row);
        }
        sshHeader = null;
        if (!sshSites.isEmpty()) {
            sshHeader = new JLabel("From ~/.ssh/config");
            sshHeader.setFont(FontRegistry.uiMedium(11));
            sshHeader.setForeground(muted());
            sshHeader.setAlignmentX(LEFT_ALIGNMENT);
            sshHeader.setBorder(BorderFactory.createEmptyBorder(Tokens.GAP_2, Tokens.GAP_3,
                    Tokens.GAP_1, Tokens.GAP_3));
            sshHeader.setMaximumSize(new Dimension(Integer.MAX_VALUE, SECTION_HEIGHT));
            sshHeader.setPreferredSize(new Dimension(0, SECTION_HEIGHT));
            rowsPanel.add(sshHeader);
            for (Site s : sshSites) {
                Row row = new Row(s, true);
                rows.add(row);
                rowsPanel.add(row);
            }
        }

        JScrollPane scroll = new JScrollPane(rowsPanel);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        int total = sites.size() + sshSites.size();
        scroll.setPreferredSize(new Dimension(CARD_WIDTH,
                Math.min(total, MAX_VISIBLE_ROWS) * ROW_HEIGHT
                        + (sshSites.isEmpty() ? 0 : SECTION_HEIGHT)));
        scroll.getVerticalScrollBar().setUnitIncrement(ROW_HEIGHT / 2);

        // The rows run edge to edge inside the card: no insets of any side,
        // only the divider under the search well. The row tint therefore
        // reads as the card's own surface changing, not a pill on it.
        JPanel area = new JPanel(new BorderLayout());
        area.setOpaque(false);
        area.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                UIManager.getColor("Component.borderColor")));
        area.add(scroll, BorderLayout.CENTER);
        return area;
    }

    private JComponent buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0,
                        UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(Tokens.GAP_2, Tokens.GAP_3, Tokens.GAP_2, Tokens.GAP_3)));

        JLabel hint = new JLabel("Ctrl+K to search  ·  ↑ ↓ to browse  ·  Enter to connect");
        hint.setFont(FontRegistry.uiMedium(11));
        hint.setForeground(muted());

        JButton fresh = new JButton("New Session…", Glyphs.icon(Glyphs.PLUS, Tokens.ICON_SMALL,
                () -> UIManager.getColor("Label.foreground")));
        fresh.putClientProperty("FlatLaf.style", "arc: " + Tokens.ARC + ";");
        fresh.addActionListener(e -> host.newSession());

        JButton local = new JButton("Local Shell", Glyphs.icon(Glyphs.TERMINAL,
                Tokens.ICON_SMALL, () -> UIManager.getColor("Label.foreground")));
        statusHint(local, "Open this machine's shell in a tab (Ctrl+Shift+L)");
        local.putClientProperty("FlatLaf.style", "arc: " + Tokens.ARC + ";");
        local.addActionListener(e -> host.localShell());

        JPanel actions = new JPanel();
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        actions.setOpaque(false);
        actions.add(local);
        actions.add(Box.createHorizontalStrut(Tokens.GAP_2));
        actions.add(fresh);

        footer.add(hint, BorderLayout.WEST);
        footer.add(actions, BorderLayout.EAST);
        return footer;
    }

    // ---- rows ----

    private final class Row extends JPanel {

        private final Site site;
        /** Listed live from ~/.ssh/config, not saved in sessions.json. */
        private final boolean fromSshConfig;
        private final JLabel glyphLabel;
        private final JLabel nameLabel;
        private final JLabel secondaryLabel;
        private final JLabel ageLabel;
        private final JButton moreButton;
        private final JProgressBar busyBar;
        private boolean hover;
        private boolean busy;

        private Row(Site site, boolean fromSshConfig) {
            this.site = site;
            this.fromSshConfig = fromSshConfig;
            // BorderLayout on purpose: EAST (age + ⋯) always gets its
            // preferred width and CENTER clips long text — a BoxLayout row
            // lets long names push the ⋯ button out of the viewport. SOUTH
            // is the busy strip, permanently reserved (see below).
            setLayout(new BorderLayout(Tokens.GAP_3, 0));
            setOpaque(false);
            setFocusable(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            // 4px (not 6) top/bottom padding funds the busy strip below the
            // content: the strip's slot is always reserved so that starting
            // or ending a connect can never reflow anything above it. The
            // side padding is content-only breathing room — the tint fills
            // past it to the card's edges.
            setBorder(BorderFactory.createEmptyBorder(4, Tokens.GAP_3, 4, Tokens.GAP_3));
            setPreferredSize(new Dimension(0, ROW_HEIGHT));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));

            glyphLabel = new JLabel(Glyphs.iconUniform(protocolGlyph(site.protocol()),
                    Tokens.ICON_LARGE, () -> UIManager.getColor("Label.foreground")));
            nameLabel = new JLabel(site.name());
            nameLabel.setFont(FontRegistry.uiSemiBold(14));
            nameLabel.setAlignmentX(LEFT_ALIGNMENT);
            secondaryLabel = new JLabel(secondaryText());
            secondaryLabel.setFont(FontRegistry.mono(12));
            secondaryLabel.setForeground(muted());
            secondaryLabel.setAlignmentX(LEFT_ALIGNMENT);
            ageLabel = new JLabel(fromSshConfig ? "ssh config" : Fmt.age(site.lastUsed()));
            ageLabel.setFont(FontRegistry.ui(11));
            ageLabel.setForeground(muted());

            moreButton = new JButton(Glyphs.iconUniform(Glyphs.ELLIPSIS, Tokens.ICON_SMALL,
                    SessionsHome::muted));
            moreButton.putClientProperty("JButton.buttonType", "borderless");
            moreButton.setRolloverEnabled(true);
            moreButton.setFocusable(false);
            moreButton.setPreferredSize(new Dimension(28, 28));
            moreButton.setMaximumSize(new Dimension(28, 28));
            statusHint(moreButton, "Session actions");
            moreButton.addActionListener(e -> showMenu(moreButton, 0, moreButton.getHeight()));

            // The row must not flinch while connecting: no glyph swap, no
            // retitled secondary line — only this strip starts painting.
            // Idle paints nothing, but the slot (SOUTH of the border layout)
            // is laid out at all times, so appearing can't move the content.
            busyBar = new JProgressBar() {
                @Override protected void paintComponent(Graphics g) {
                    if (!isIndeterminate()) return;
                    super.paintComponent(g);
                }
            };
            busyBar.setOpaque(false);
            busyBar.setPreferredSize(new Dimension(10, BUSY_STRIP_HEIGHT));
            busyBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, BUSY_STRIP_HEIGHT));
            busyBar.setMinimumSize(new Dimension(0, BUSY_STRIP_HEIGHT));

            JPanel text = new JPanel();
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            text.setOpaque(false);
            // LEFT_ALIGNMENT on both lines: BoxLayout would otherwise center
            // each against the widest sibling, so a long user@host line
            // would swing the session name sideways.
            text.add(nameLabel);
            text.add(Box.createVerticalStrut(1));
            text.add(secondaryLabel);
            // BorderLayout stretches CENTER to the full row height; GridBag
            // keeps the two-line block vertically centered like the glyph.
            // weightx=1 + LINE_START pins it left: with all weights zero
            // GridBag would center the whole grid, letting each row's text
            // drift with its own content — exactly the ragged look removed
            // here.
            JPanel textHolder = new JPanel(new GridBagLayout());
            textHolder.setOpaque(false);
            GridBagConstraints textGbc = new GridBagConstraints();
            textGbc.weightx = 1;
            textGbc.anchor = GridBagConstraints.LINE_START;
            textHolder.add(text, textGbc);

            JPanel right = new JPanel();
            right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
            right.setOpaque(false);
            right.add(ageLabel);
            right.add(Box.createHorizontalStrut(Tokens.GAP_2));
            right.add(moreButton);

            add(glyphLabel, BorderLayout.WEST);
            add(textHolder, BorderLayout.CENTER);
            add(right, BorderLayout.EAST);
            add(busyBar, BorderLayout.SOUTH);

            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) {
                    hover = true;  repaint();
                    StatusLine.publish("Connect to " + site.name());
                }
                @Override public void mouseExited(MouseEvent e) {
                    hover = false; repaint();
                    StatusLine.clear();
                }
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1) connect();
                }
                @Override public void mousePressed(MouseEvent e) {
                    if (e.isPopupTrigger()) showMenu(Row.this, e.getX(), e.getY());
                }
                @Override public void mouseReleased(MouseEvent e) {
                    if (e.isPopupTrigger()) showMenu(Row.this, e.getX(), e.getY());
                }
            });
            addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) {
                    repaint();
                    StatusLine.publish("Enter connects " + site.name());
                }
                @Override public void focusLost(FocusEvent e) {
                    // A hovering mouse keeps its own hint on screen.
                    if (!hover) StatusLine.clear();
                    repaint();
                }
            });
            // Enter connects the focused row; arrows walk the visible
            // rows, and UP past the first returns to the search field
            // (numpad arrows ride along).
            var im = getInputMap(WHEN_FOCUSED);
            var am = getActionMap();
            im.put(KeyStroke.getKeyStroke("ENTER"), "dock.rowConnect");
            am.put("dock.rowConnect", new javax.swing.AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) { connect(); }
            });
            for (String down : new String[] {"DOWN", "KP_DOWN"}) {
                im.put(KeyStroke.getKeyStroke(down), "dock.rowDown");
            }
            am.put("dock.rowDown", new javax.swing.AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    Row next = nextVisibleRow(Row.this);
                    if (next != null) walkTo(next);
                }
            });
            for (String up : new String[] {"UP", "KP_UP"}) {
                im.put(KeyStroke.getKeyStroke(up), "dock.rowUp");
            }
            am.put("dock.rowUp", new javax.swing.AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    Row prev = prevVisibleRow(Row.this);
                    if (prev != null) walkTo(prev);
                    else if (searchField != null) searchField.requestFocusInWindow();
                }
            });
        }

        private String secondaryText() {
            var backend = dock.core.spi.Backends.of(site.protocol());
            if (backend != null) return backend.secondaryText(site);
            // A site whose backend module is absent from this build still
            // renders, with the plain endpoint shape.
            String endpoint = site.user() + "@" + site.host()
                    + (site.port() == 22 ? "" : ":" + site.port());
            return endpoint + "  ·  " + (site.useAgent() ? "agent"
                    : site.keyPath() == null ? "password" : "key file");
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (hover || isFocusOwner()) {
                Color tint = UIManager.getColor("Button.toolbar.hoverBackground");
                if (tint != null) {
                    // Edge-to-edge: the tint is the row's background across
                    // the whole card width; only the outermost pixel column
                    // stays clear so the card's border stroke survives.
                    g.setColor(tint);
                    g.fillRect(1, 0, getWidth() - 2, getHeight());
                }
            }
            super.paintComponent(g);
        }

        private void connect() {
            if (busy) return;
            busy = true;
            busyBar.setIndeterminate(true);
            host.connect(site, (error, fs) -> {
                // EDT. Release in every outcome — on success the screen is
                // about to swap to the session tab (nobody sees the strip
                // stop), and if the user comes back to this screen the row
                // must be usable again rather than stuck busy.
                releaseBusy();
                if (error != null) {
                    Toast.show(SessionsHome.this, "Could not connect to " + site.name()
                            + ": " + error.getMessage(), Glyphs.WARNING);
                }
            });
        }

        /** Stops the busy strip — the only piece of the row connect changes. */
        private void releaseBusy() {
            busy = false;
            busyBar.setIndeterminate(false);
            busyBar.repaint();
        }

        private void edit() {
            host.editSite(site);
        }

        private void delete() {
            int ans = JOptionPane.showConfirmDialog(SwingUtilities.getWindowAncestor(this),
                    "Delete saved session \u201C" + site.name()
                            + "\u201D? Its stored password is removed too.",
                    "Delete Session", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (ans != JOptionPane.YES_OPTION) return;
            deleteNow();
        }

        /** Deletes without asking (also the test/harness path). */
        private void deleteNow() {
            try {
                Sites.remove(site.name());
            } catch (Exception e) {
                Toast.show(SessionsHome.this, "Could not delete: " + e.getMessage(),
                        Glyphs.WARNING);
                return;
            }
            try {
                dock.core.secrets.CredentialManager.delete(site.secretTarget());
                dock.core.secrets.CredentialManager.delete(site.keySecretTarget());
            } catch (Exception ignored) {
                // An absent secret is fine; anything else leaves a harmless orphan.
            }
            refresh();
        }

        private void showMenu(Component at, int x, int y) {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem connectItem = new JMenuItem("Connect", Glyphs.icon(Glyphs.PLUG,
                    Tokens.ICON_SMALL, SessionsHome::muted));
            connectItem.addActionListener(e -> connect());
            JMenuItem editItem = new JMenuItem("Edit…", Glyphs.icon(Glyphs.EDIT,
                    Tokens.ICON_SMALL, SessionsHome::muted));
            editItem.addActionListener(e -> edit());
            JMenuItem deleteItem = new JMenuItem("Delete…", Glyphs.icon(Glyphs.TRASH,
                    Tokens.ICON_SMALL, SessionsHome::muted));
            deleteItem.addActionListener(e -> delete());
            menu.add(connectItem);
            menu.addSeparator();
            if (fromSshConfig) {
                // Not ours to edit or delete: saving makes it a session.
                JMenuItem saveItem = new JMenuItem("Save as Session…", Glyphs.icon(Glyphs.PLUS,
                        Tokens.ICON_SMALL, SessionsHome::muted));
                saveItem.addActionListener(e -> edit());
                menu.add(saveItem);
                menu.show(at, x, y);
                return;
            }
            menu.add(editItem);
            menu.add(deleteItem);
            menu.show(at, x, y);
        }
    }

    // ---- keyboard walking ----

    /** The next visible row after {@code from}, or null at the end. */
    private Row nextVisibleRow(Row from) {
        for (int k = rows.indexOf(from) + 1; k < rows.size(); k++) {
            if (rows.get(k).isVisible()) return rows.get(k);
        }
        return null;
    }

    /** The visible row before {@code from}, or null — the search field
     *  sits above the first. */
    private Row prevVisibleRow(Row from) {
        for (int k = rows.indexOf(from) - 1; k >= 0; k--) {
            if (rows.get(k).isVisible()) return rows.get(k);
        }
        return null;
    }

    /** Focus that scrolls into view — walking must reach rows past the
     *  eight-row viewport. */
    private void walkTo(Row target) {
        target.requestFocusInWindow();
        target.scrollRectToVisible(
                new Rectangle(0, 0, target.getWidth(), target.getHeight()));
    }

    // ---- filtering ----

    private void applyFilter() {
        String q = searchField.getText().trim().toLowerCase();
        int visible = 0;
        for (Row row : rows) {
            Site s = row.site;
            boolean match = q.isEmpty()
                    || s.name().toLowerCase().contains(q)
                    || s.host().toLowerCase().contains(q)
                    || s.user().toLowerCase().contains(q);
            row.setVisible(match);
            if (match) visible++;
        }
        if (sshHeader != null) {
            boolean anySsh = false;
            for (Row row : rows) anySsh |= row.fromSshConfig && row.isVisible();
            sshHeader.setVisible(anySsh);
        }
        noMatchLabel.setVisible(visible == 0);
        rowsPanel.revalidate();
        rowsPanel.repaint();
    }

    private Row firstVisibleRow() {
        for (Row row : rows) if (row.isVisible()) return row;
        return null;
    }

    // ---- helpers ----

    private static JLabel heading(String text) {
        JLabel l = new JLabel(text, JLabel.CENTER);
        l.setAlignmentX(CENTER_ALIGNMENT);
        l.setFont(FontRegistry.uiSemiBold(18));
        l.setForeground(UIManager.getColor("Label.foreground"));
        return l;
    }

    private static JLabel body(String text) {
        JLabel l = new JLabel(text, JLabel.CENTER);
        l.setAlignmentX(CENTER_ALIGNMENT);
        l.setFont(FontRegistry.ui());
        l.setForeground(muted());
        return l;
    }

    private static Color muted() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : UIManager.getColor("Label.foreground");
    }

    // ---- test/harness hooks ----

    public int rowCount() {
        return rows.size();
    }

    public int visibleRowCount() {
        int n = 0;
        for (Row row : rows) if (row.isVisible()) n++;
        return n;
    }

    public Site siteForTest(int i) {
        return rows.get(i).site;
    }

    /** The row component itself — hover tests dispatch real mouse events. */
    public java.awt.Component rowForTest(int i) {
        return rows.get(i);
    }

    public JTextField searchFieldForTest() {
        return searchField;
    }

    /** The type-ahead step (the dispatcher body after its focus gates). */
    public boolean typeAheadForTest(java.awt.event.KeyEvent e) {
        return typeAhead(e);
    }

    /** The tinted panel behind the search field (pixel checks sample this). */
    public JComponent searchChromeForTest() {
        return searchField != null ? (JComponent) searchField.getParent() : null;
    }

    /** True while the ~/.ssh/config caption shows (tests). */
    public boolean sshSectionShownForTest() {
        return sshHeader != null && sshHeader.isVisible();
    }

    /** True when row {@code i} is listed live from ~/.ssh/config (tests). */
    public boolean fromSshConfigForTest(int i) {
        return rows.get(i).fromSshConfig;
    }

    public void setSearchTextForTest(String text) {
        searchField.setText(text);
        applyFilter();
    }

    /** Fires a row's key binding — the real keyboard path (tests). */
    public void fireRowActionForTest(int i, String spec) {
        Row row = rows.get(i);
        Object key = row.getInputMap(WHEN_FOCUSED).get(KeyStroke.getKeyStroke(spec));
        javax.swing.Action action = key == null ? null : row.getActionMap().get(key);
        if (action == null) throw new IllegalStateException("no row binding for " + spec);
        action.actionPerformed(new java.awt.event.ActionEvent(row, 0, String.valueOf(key)));
    }

    public void connectForTest(int i) {
        rows.get(i).connect();
    }

    public void editForTest(int i) {
        rows.get(i).edit();
    }

    /** Delete without the confirmation dialog (tests/harness). */
    public void deleteForTest(int i) {
        rows.get(i).deleteNow();
    }

    public JButton moreButtonForTest(int i) {
        return rows.get(i).moreButton;
    }

    /** Row width as laid out (regression: rows must not exceed the viewport). */
    public int rowWidthForTest(int i) {
        return rows.get(i).getWidth();
    }

    /** Scroll viewport width the row is stretched into. */
    public int viewportWidthForTest(int i) {
        java.awt.Container rowsParent = rows.get(i).getParent();       // rowsPanel
        return rowsParent != null && rowsParent.getParent() != null
                ? rowsParent.getParent().getWidth() : -1;              // viewport
    }

    /** True while the row's connect attempt is in flight. */
    public boolean busyForTest(int i) {
        return rows.get(i).busy;
    }

    /** x of the row's title line, in row coordinates (alignment checks). */
    public int rowNameXForTest(int i) {
        Row row = rows.get(i);
        return SwingUtilities.convertPoint(row.nameLabel, 0, 0, row).x;
    }

    /** x of the row's user@host line, in row coordinates. */
    public int rowSecondaryXForTest(int i) {
        Row row = rows.get(i);
        return SwingUtilities.convertPoint(row.secondaryLabel, 0, 0, row).x;
    }

    /** Texts, glyph identity and every child's bounds (row coordinates,
     *  busy strip included) — a connect attempt must not alter this. */
    public String rowSnapshotForTest(int i) {
        Row row = rows.get(i);
        StringBuilder sb = new StringBuilder("name=").append(row.nameLabel.getText())
                .append("|secondary=").append(row.secondaryLabel.getText())
                .append("|glyph=").append(System.identityHashCode(row.glyphLabel.getIcon()))
                .append("|age=").append(row.ageLabel.getText());
        for (Component c : new Component[]{row.glyphLabel, row.nameLabel,
                row.secondaryLabel, row.ageLabel, row.moreButton, row.busyBar}) {
            sb.append('|').append(SwingUtilities.convertRectangle(c,
                    new Rectangle(0, 0, c.getWidth(), c.getHeight()), row));
        }
        return sb.toString();
    }

    /** True when the busy strip actually paints pixels right now. */
    public boolean busyStripPaintsForTest(int i) {
        JProgressBar bar = rows.get(i).busyBar;
        BufferedImage img = new BufferedImage(Math.max(1, bar.getWidth()),
                Math.max(1, bar.getHeight()), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            bar.paint(g);
        } finally {
            g.dispose();
        }
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) != 0) return true;
            }
        }
        return false;
    }

    /** Standalone paint of row {@code i} with a forced hover state (tests). */
    public BufferedImage paintRowForTest(int i, boolean hoverState) {
        Row row = rows.get(i);
        row.hover = hoverState;
        BufferedImage img = new BufferedImage(Math.max(1, row.getWidth()),
                Math.max(1, row.getHeight()), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            row.paint(g);
        } finally {
            g.dispose();
        }
        row.hover = false;
        return img;
    }

    /** True when the ⋯ button lies fully inside its row's visible width. */
    public boolean moreButtonFitsForTest(int i) {
        Row row = rows.get(i);
        Rectangle b = SwingUtilities.convertRectangle(row.moreButton,
                new Rectangle(0, 0, row.moreButton.getWidth(), row.moreButton.getHeight()), row);
        return row.getWidth() > 0 && b.x + b.width <= row.getWidth();
    }
}
