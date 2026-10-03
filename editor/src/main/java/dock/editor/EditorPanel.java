package dock.editor;

import dock.core.fs.FileEntry;
import dock.core.fs.FileSystem;
import dock.kit.FontRegistry;
import dock.kit.Glyphs;
import dock.kit.Tokens;
import dock.kit.Toast;
import dock.markdown.MarkdownDocs;
import dock.markdown.MarkdownPage;
import dock.syntax.Syntax;
import dock.syntax.SyntaxKind;
import dock.syntax.SyntaxPalette;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import javax.swing.event.CaretEvent;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Style;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.fife.ui.rtextarea.DocumentRange;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.fife.ui.rtextarea.SearchResult;

/**
 * The in-pane text editor: a toolbar, a syntax-highlighted
 * {@link RSyntaxTextArea} with a line-number gutter, an info strip. It is
 * swapped into a commander pane in place of the file table and edits one
 * local or remote file until closed. Reading and writing run on virtual
 * threads through the pane's filesystem — a remote file downloads into
 * memory on open and every save uploads the whole text back through a
 * private channel, so the editor works uniformly over every backend.
 *
 * <p>Files that render as markdown (".md", the extensionless repo texts
 * like README and LICENSE) open on the rendered {@link dock.markdown.MarkdownPage}
 * instead — the reader's whole surface: prose, code cards, tables,
 * inline images with their remote fetches, links. A toolbar button
 * flips between that render and this source in place; the flip costs
 * nothing unless the text moved, and edits, save, and the close guard
 * work the same from either side. Word wrap is one button for both
 * views: the code folds its lines, the page folds everything.
 *
 * <p>Faithfulness to the file: the load refuses anything binary or over
 * 8 MB, remembers the byte-order mark and the line endings, and save
 * writes both back unchanged. Dirty means the text actually differs from
 * the saved file — typing and undoing back leaves nothing to save, and
 * every line that is not in the saved file wears a green mark in the
 * gutter until the next save. Before writing, save re-checks the file's
 * size and modification time and asks before stepping on outside
 * changes, including a file that was deleted or renamed away.

 * <p>Escape is the only way out — backspace deletes text here; it must
 * not back out of the surface the way it backs out of the read-only
 * viewers. Closing with unsaved edits asks Save/Discard/Cancel, and the
 * Save path closes only after the upload landed.
 */
public final class EditorPanel extends JPanel {

    /** Refuse before reading: no document should be able to blow the heap. */
    private static final long MAX_EDIT_BYTES = 8L * 1024 * 1024;
    /** A byte we never expect in the first pages of honest text. */
    private static final int SNIFF_BYTES = 8192;
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    /** Keystroke bursts coalesce into one gutter diff. */
    private static final int MARK_DEBOUNCE_MS = 150;
    /** An LCS middle bigger than this marks as a block instead — no
     *  keystroke ever waits on a pathological diff. */
    private static final int MAX_DIFF_CELLS = 1_000_000;

    /** Package-visible so tests can assert the load state machine. */
    enum State { LOADING, SHOWN, REFUSED }

    /** The plain-text family the syntax table has no lexer for: notes,
     *  logs, TSV beside CSV, and the repo dotfiles — a leading-dot name
     *  reaches this set through its extension, so ".gitignore" carries
     *  "gitignore". These edit painted plain. */
    private static final Set<String> PLAIN_TEXT = Set.of(
            "txt", "text", "log", "tsv", "gitignore", "gitattributes",
            "dockerignore", "editorconfig");

    /** Extensionless repo texts that neither render as markdown nor have
     *  a lexer — "CHANGELOG", "NOTICE". */
    private static final Set<String> PLAIN_NAMES = Set.of(
            "changelog", "notice", "authors", "todo");

    /**
     * Whether Enter or double-click opens {@code name} in the editor: the
     * syntax table's languages (code, config, data — "Main.java",
     * "app.ini", "hosts", "Makefile"), the plain-text family around them,
     * and the well-known repo texts. Binary kinds answer false and keep
     * the open fall-through; F4 still edits anything.
     */
    public static boolean edits(String name) {
        if (name == null) return false;
        if (Syntax.engineStyle(name) != null) return true;
        String key = name.strip().toLowerCase(Locale.ROOT);
        int dot = key.lastIndexOf('.');
        if (dot >= 0 && key.length() > dot + 1
                && PLAIN_TEXT.contains(key.substring(dot + 1))) return true;
        return PLAIN_NAMES.contains(key);
    }

    private final FileSystem fs;
    private final String dir;
    private final String name;
    private final Runnable onSaved;
    private final Runnable onClose;
    /** Same-folder markdown links from the render view land here; null
     *  (nothing wired) explains itself with a toast. */
    private final java.util.function.Consumer<String> onOpenFile;
    /** The reader's convention on the render open: the shown file is
     *  reported so the pane's cursor can follow. Null on the edit path. */
    private java.util.function.Consumer<String> onNavigate;

    private final Editor area = new Editor();
    private final RTextScrollPane scroll = new RTextScrollPane(area, true);
    /** The rendered half for markdown files; null for everything else. */
    private final MarkdownPage page;
    private final JLabel title = new JLabel();
    private final JLabel strip = new JLabel();
    private final JTextField findField = new JTextField();
    private final JLabel findCount = new JLabel();
    private JPanel stripBar;
    private JPanel findRow;
    private JComponent center;
    private JButton wrapButton;
    /** The render ↔ source flip; only exists for files that render. */
    private JButton viewButton;
    private JButton saveButton;

    private State state = State.LOADING;
    private volatile boolean dirty;
    private volatile boolean saving;
    private volatile boolean closed;
    /** Guards the document churn of installing a loaded file. */
    private boolean loadingText;
    /** The open path's ask: markdown lands on the rendered page, the
     *  edit path (F4) on the source — honored when the load succeeds. */
    private final boolean renderFirst;
    /** Whether the rendered page (not the code surface) is showing. */
    private boolean renderView;
    /** Word wrap for the code view; the page keeps its own. */
    private boolean codeWrap;
    /** The text moved since the page was last built — the next entry into
     *  the render view re-renders it. */
    private boolean renderStale = true;
    /** A Laf flip happened while the page was detached (code view showing);
     *  the re-style cascade never reached it, so the next entry catches up. */
    private boolean pageThemeDirty;
    private boolean bom;
    private boolean crlf;
    private FileEntry loaded;
    private String refuseReason = "";

    /** The file as last loaded or saved, in the document's \n dialect —
     *  the text dirty and the gutter diff measure against. */
    private String baseline = "";
    /** Zero-based lines of the current text not present in the baseline,
     *  in gutter-mark order; membership is the only meaning. */
    private int[] markedLines = {};
    /** The gutter bar, rebuilt with the theme. */
    private Icon changedMark;
    private final Timer markTimer;

    /** Overrides the modal confirms in tests; null asks the real dialog. */
    private Boolean confirmOverwriteOverride;
    private Integer closeAnswerOverride;

    /** {@code renderFirst} opens markdown files on the rendered page —
     *  the open path (Enter/F3) passes true, the edit path (F4) false;
     *  files that don't render always open on the code. */
    public EditorPanel(FileSystem fs, String dir, String fileName, boolean renderFirst,
                       java.util.function.Consumer<String> onOpenFile,
                       Runnable onSaved, Runnable onClose) {
        super(new BorderLayout());
        this.fs = fs;
        this.dir = dir;
        this.name = fileName;
        this.onOpenFile = onOpenFile;
        this.onSaved = onSaved;
        this.onClose = onClose;
        this.renderFirst = renderFirst;
        if (MarkdownDocs.renders(fileName)) {
            page = new MarkdownPage(fs, dir);
            page.onMarkdownLink(this::openLinked);
            installPageKeys();
        } else {
            page = null;
        }
        area.setTabSize(4);
        area.setTabsEmulated(true);          // Tab indents with spaces
        area.setAutoIndentEnabled(true);
        area.setBracketMatchingEnabled(true);
        area.setCloseCurlyBraces(true);
        area.setCloseMarkupTags(true);
        area.setCodeFoldingEnabled(true);
        area.setAntiAliasingEnabled(true);
        scroll.setFoldIndicatorEnabled(true);
        area.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { edited(); }
            @Override public void removeUpdate(DocumentEvent e) { edited(); }
            @Override public void changedUpdate(DocumentEvent e) {}
        });
        area.addCaretListener((CaretEvent e) -> updateStrip());
        markTimer = new Timer(MARK_DEBOUNCE_MS, e -> recomputeMarks());
        markTimer.setRepeats(false);
        findRow = buildFindRow();
        findRow.setVisible(false);
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(buildBar(), BorderLayout.NORTH);
        top.add(findRow, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(buildStrip(), BorderLayout.SOUTH);
        showCenter(new Notice(Glyphs.SYNC, () -> "Loading " + name + "…"));
        applyTheme();
        updateSaveButton();
        updateBar();
        load();
    }

    /** Same-folder markdown links from the render view: while there are
     *  unsaved edits, following one would drop them — the editor refuses
     *  and says so; clean, the link opens through the host. */
    private void openLinked(String other) {
        if (dirty || saving) {
            Toast.show(this, "Save or discard your edits before following links.",
                    Glyphs.WARNING);
            return;
        }
        if (onOpenFile != null) onOpenFile.accept(other);
    }

    /** The render view's keys on the page's text surface: the editor's
     *  save and close convention, the viewer's backspace — find stays a
     *  code-view tool. */
    private void installPageKeys() {
        InputMap im = page.text().getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = page.text().getActionMap();
        bindKey(im, am, "ctrl S", "save", this::save);
        bindKey(im, am, "ESCAPE", "close", this::escape);
        bindKey(im, am, "BACK_SPACE", "close", this::escape);
    }

    private static void bindKey(InputMap im, ActionMap am,
                                String key, String name, Runnable action) {
        KeyStroke ks = KeyStroke.getKeyStroke(key);
        if (ks == null) throw new IllegalStateException("bad keystroke " + key);
        im.put(ks, name);
        am.put(name, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                action.run();
            }
        });
    }

    // ---- chrome ----

    private JComponent buildBar() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(
                Tokens.GAP_1, Tokens.GAP_2, Tokens.GAP_1, Tokens.GAP_2));
        bar.add(button(Glyphs.UNDO, "Undo (Ctrl+Z)", area::undoLastAction));
        bar.add(button(Glyphs.REDO, "Redo (Ctrl+Y)", area::redoLastAction));
        bar.add(groupGap());
        bar.add(saveButton = button(Glyphs.SAVE, "Save (Ctrl+S)", this::save));
        bar.add(Box.createHorizontalStrut(Tokens.GAP_3));
        bar.add(button(Glyphs.SEARCH, "Find (Ctrl+F)", this::toggleFind));
        bar.add(Box.createHorizontalStrut(Tokens.GAP_3));
        wrapButton = button(Glyphs.TEXT_WIDTH, "Disable word wrap", this::toggleWrap);
        bar.add(wrapButton);
        if (page != null) {
            viewButton = button(Glyphs.PICTURE, "View rendered", this::toggleView);
            bar.add(viewButton);
        }
        bar.add(title);
        bar.add(Box.createHorizontalGlue());
        bar.add(button(Glyphs.CLOSE, "Close (Esc)", this::requestClose));
        return bar;
    }

    private static Component groupGap() {
        javax.swing.JSeparator sep =
                new javax.swing.JSeparator(javax.swing.SwingConstants.VERTICAL);
        sep.setPreferredSize(new Dimension(1, 18));
        sep.setMaximumSize(new Dimension(1, 18));
        Box wrap = Box.createHorizontalBox();
        wrap.add(Box.createHorizontalStrut(Tokens.GAP_3));
        wrap.add(sep);
        wrap.add(Box.createHorizontalStrut(Tokens.GAP_3));
        // A Box with no alignment of its own answers through its layout
        // (Container defers to BoxLayout), and this one's aggregate comes
        // out 0.0 — top-aligned. The bar's BoxLayout sizes an X row as
        // the max space above plus the max space below across the row's
        // children at their alignments, so a top-aligned 18px child asks
        // 15+18px where the 30px buttons ask 30 — a bar 3px taller than
        // the pane's own, and the toolbar row jumped on every open.
        // Centered, the separator asks no more than the buttons do.
        wrap.setAlignmentY(0.5f);
        return wrap;
    }

    private static JButton button(String glyph, String tooltip, Runnable action) {
        // Uniform ink scaling keeps thin chevrons and dense glyphs the same
        // optical size; 20px ink in a 30px hit area matches the pane toolbar.
        JButton b = new JButton(Glyphs.iconUniform(glyph, Tokens.ICON_LARGE,
                EditorPanel::muted));
        b.setToolTipText(tooltip);
        b.putClientProperty("JButton.buttonType", "borderless");
        b.setRolloverEnabled(true);
        b.setFocusable(false);
        b.setPreferredSize(new Dimension(30, 30));
        b.setMaximumSize(new Dimension(30, 30));
        b.addActionListener(e -> action.run());
        return b;
    }

    private JComponent buildStrip() {
        stripBar = new JPanel(new BorderLayout());
        stripBar.setOpaque(false);
        stripBar.setBorder(stripBorder());
        strip.setFont(FontRegistry.mono());
        strip.setForeground(muted());
        stripBar.add(strip, BorderLayout.CENTER);
        return stripBar;
    }

    private static javax.swing.border.Border stripBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0,
                        UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(
                        Tokens.GAP_1, Tokens.GAP_3, Tokens.GAP_1, Tokens.GAP_3));
    }

    private static Color muted() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : UIManager.getColor("Label.foreground");
    }

    private static Color ui(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }

    private void showCenter(JComponent c) {
        if (center != null) remove(center);
        center = c;
        add(c, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    // ---- loading pipeline ----

    private record Loaded(String text, boolean bom, boolean crlf, FileEntry stat) {}
    private record Refused(String reason) {}

    private void load() {
        state = State.LOADING;
        updateTitle();
        updateStrip();
        Thread.ofVirtual().name("dock-editor").start(() -> {
            Object result = read();
            SwingUtilities.invokeLater(() -> {
                if (closed) return;
                switch (result) {
                    case Loaded l -> show(l);
                    case Refused r -> refuse(r.reason());
                    default -> throw new IllegalStateException("unexpected " + result);
                }
            });
        });
    }

    /** The worker's whole life — every blocking call lives here. */
    private Object read() {
        String path = fs.child(dir, name);
        try {
            FileEntry st = fs.stat(path);
            if (st.size() > MAX_EDIT_BYTES)
                return new Refused("too large to edit (over 8 MB)");
            FileSystem view = fs.streamView();
            byte[] bytes;
            try (InputStream in = view.read(path)) {
                bytes = in.readAllBytes();
            } finally {
                if (view != fs) try { view.close(); } catch (Exception ignored) {}
            }
            int sniff = (int) Math.min(bytes.length, SNIFF_BYTES);
            for (int i = 0; i < sniff; i++) {
                if (bytes[i] == 0) return new Refused("not a text file");
            }
            boolean hasBom = sniff >= 3 && (bytes[0] & 0xFF) == 0xEF
                    && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF;
            byte[] body = hasBom
                    ? java.util.Arrays.copyOfRange(bytes, 3, bytes.length) : bytes;
            String text = new String(body, StandardCharsets.UTF_8);
            // The document speaks \n; the file's own ending is remembered
            // and written back on save. A stray lone \r normalizes too.
            boolean hasCrlf = text.contains("\r\n");
            text = text.replace("\r\n", "\n").replace('\r', '\n');
            return new Loaded(text, hasBom, hasCrlf, st);
        } catch (Exception e) {
            return new Refused(shortError(e));
        }
    }

    private void show(Loaded l) {
        bom = l.bom();
        crlf = l.crlf();
        loaded = l.stat();
        baseline = l.text();
        markedLines = new int[0];
        loadingText = true;
        try {
            area.setText(l.text());
            area.discardAllEdits();   // opening a file is not an undo step
            area.setCaretPosition(0);
        } finally {
            loadingText = false;
        }
        dirty = false;
        String style = Syntax.engineStyle(name);
        area.setSyntaxEditingStyle(
                style != null ? style : RSyntaxTextArea.SYNTAX_STYLE_NONE);
        // The view lands only now, with the load actually in hand — a
        // refusal (or a still-pending load) never claims the page shows.
        renderView = renderFirst && page != null;
        if (renderView) {
            // The markdown default: the rendered page stands in; the code
            // document is already in hand behind it for the flip.
            renderStale = false;
            page.render(l.text(), null);
            showCenter(page);
            page.text().requestFocusInWindow();
        } else {
            showCenter(scroll);
            area.requestFocusInWindow();
        }
        state = State.SHOWN;
        updateTitle();
        updateStrip();
        updateBar();
        // The reader's report: the pane's cursor follows the file shown —
        // only the render open wires it, the edit path keeps the cursor.
        if (renderView && onNavigate != null) onNavigate.accept(name);
    }

    private void refuse(String reason) {
        state = State.REFUSED;
        refuseReason = reason;
        showCenter(new Notice(Glyphs.WARNING, () -> reason));
        Toast.show(this, "Can't edit " + name + ": " + reason, Glyphs.WARNING);
        updateTitle();
        updateStrip();
    }

    // ---- saving ----

    /** A document edit landed: dirty is recomputed from the text itself,
     *  so undoing back to the file leaves nothing to save, and the gutter
     *  marks refresh on a short debounce so a burst of keystrokes diffs
     *  once. */
    private void edited() {
        if (loadingText) return;
        refreshDirty();
        markTimer.restart();
        renderStale = true;
    }

    /** Dirty means the text differs from the saved file — never merely
     *  that a key went down. */
    private void refreshDirty() {
        boolean now = state == State.SHOWN && textDiffers();
        if (now == dirty) return;
        dirty = now;
        updateTitle();
        updateStrip();
        updateSaveButton();
    }

    private boolean textDiffers() {
        // Document length is O(1) and settles nearly every keystroke; the
        // full compare only runs when the lengths happen to match.
        if (area.getDocument().getLength() != baseline.length()) return true;
        return !normalizedText().equals(baseline);
    }

    private String normalizedText() {
        return area.getText().replace("\r\n", "\n").replace('\r', '\n');
    }

    /** Save lights only for work that would leave the editor. */
    private void updateSaveButton() {
        saveButton.setEnabled(state == State.SHOWN && dirty && !saving);
    }

    void save() {
        if (state != State.SHOWN || saving) return;
        if (!dirty) return;
        saveThen(null);
    }

    /** Saves the text; {@code after} runs only when the save landed. */
    private void saveThen(Runnable after) {
        if (state != State.SHOWN || saving) return;
        if (!dirty) {
            if (after != null) after.run();
            return;
        }
        String saved = normalizedText();
        byte[] bytes = serialize(saved);
        saving = true;
        updateSaveButton();
        updateStrip();
        String path = fs.child(dir, name);
        Thread.ofVirtual().name("dock-editor-save").start(() -> {
            Saved result = persist(path, bytes);
            SwingUtilities.invokeLater(() -> {
                saving = false;
                if (result.skipped()) {
                    // The outside version stood: the editor stays open and
                    // dirty, and nothing claims a save happened.
                    updateSaveButton();
                    updateStrip();
                } else if (result.error() == null) {
                    // The file is now exactly what this save wrote. Typing
                    // that landed while it uploaded stays dirty against the
                    // new baseline instead of being wiped by the settle.
                    baseline = saved;
                    refreshDirty();
                    updateTitle();
                    updateStrip();
                    recomputeMarks();
                    Toast.show(this, (fs.remote() ? "Uploaded " : "Saved ") + name,
                            Glyphs.CHECK);
                    onSaved.run();
                    if (after != null) after.run();
                } else {
                    Toast.show(this, "Can't save " + name + ": " + result.error(),
                            Glyphs.WARNING);
                    updateSaveButton();
                    updateStrip();
                }
            });
        });
    }

    private record Saved(String error, boolean skipped) {}

    /** The worker's whole life — every blocking call lives here. A
     *  skipped save (the user answered no to the conflict) is not an
     *  error: the editor stays open and dirty. */
    private Saved persist(String path, byte[] bytes) {
        try {
            FileEntry now;
            try {
                now = fs.stat(path);
            } catch (Exception gone) {
                now = null;   // deleted or renamed out from under us
            }
            if (loaded != null && now == null
                    && !confirmOverwrite(name + " was deleted from the "
                            + (fs.remote() ? "server" : "disk")
                            + " since it was loaded. Save it anyway?")) {
                return new Saved(null, true);
            }
            if (loaded != null && now != null
                    && (now.size() != loaded.size()
                            || now.mtimeMillis() != loaded.mtimeMillis())
                    && !confirmOverwrite(name + " was changed by another program"
                            + " since it was loaded. Overwrite it?")) {
                return new Saved(null, true);
            }
            FileSystem view = fs.streamView();
            try (OutputStream out = view.write(path, false)) {
                out.write(bytes);
            } finally {
                if (view != fs) try { view.close(); } catch (Exception ignored) {}
            }
            loaded = fs.stat(path);   // what the next save compares against
            return new Saved(null, false);
        } catch (Exception e) {
            return new Saved(shortError(e), false);
        }
    }

    /** The text as file bytes: the remembered BOM and line endings back. */
    private byte[] serialize(String text) {
        if (crlf) text = text.replace("\n", "\r\n");
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        if (!bom) return body;
        byte[] out = new byte[body.length + UTF8_BOM.length];
        System.arraycopy(UTF8_BOM, 0, out, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, out, UTF8_BOM.length, body.length);
        return out;
    }

    /** Asks on the EDT from the save worker — the ConflictDialog pattern. */
    private boolean confirmOverwrite(String message) {
        Boolean override = confirmOverwriteOverride;
        if (override != null) return override;
        boolean[] yes = {false};
        try {
            SwingUtilities.invokeAndWait(() -> yes[0] = JOptionPane.showConfirmDialog(
                    this, message, "Save", JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION);
        } catch (Exception e) {
            return false;
        }
        return yes[0];
    }

    // ---- changed lines ----

    /** Debounced from keystrokes (and fired straight after a save): diffs
     *  the text against the saved file and repaints the gutter marks. */
    private void recomputeMarks() {
        if (state != State.SHOWN) return;
        int[] lines = changedLines(baseline, normalizedText());
        if (Arrays.equals(lines, markedLines)) return;
        markedLines = lines;
        paintMarks();
    }

    /** The VS Code treatment: a slim green bar on every gutter line that
     *  is not in the saved file. The mark column is never toggled — its
     *  width is fixed whether or not anything stands in it, so marks
     *  appear in place instead of shoving the numbers aside. */
    private void paintMarks() {
        Gutter gutter = scroll.getGutter();
        gutter.removeAllTrackingIcons();
        for (int line : markedLines) {
            if (line >= area.getLineCount()) continue;
            try {
                gutter.addLineTrackingIcon(line, changedMark);
            } catch (BadLocationException ignored) {}
        }
    }

    /** Zero-based lines of {@code text} that are not in {@code saved}.
     *  One scan peels the common head and tail — the shape of nearly
     *  every real edit — and only the squeezed middle pays for the LCS. */
    private static int[] changedLines(String saved, String text) {
        if (saved.equals(text)) return new int[0];
        List<String> a = lines(saved), b = lines(text);
        int n = a.size(), m = b.size();
        int pre = 0;
        while (pre < n && pre < m && a.get(pre).equals(b.get(pre))) pre++;
        int suf = 0;
        while (suf < n - pre && suf < m - pre
                && a.get(n - 1 - suf).equals(b.get(m - 1 - suf))) suf++;
        int an = n - pre - suf, bm = m - pre - suf;
        if (an == 0) return run(pre, bm);   // pure insertion
        if (bm == 0) return new int[0];     // pure deletion: no line to mark
        if ((long) an * bm > MAX_DIFF_CELLS) return run(pre, bm);
        return lcsMarks(a, b, pre, an, bm);
    }

    /** Lines {@code from} … {@code from + count - 1} marked as a block. */
    private static int[] run(int from, int count) {
        int[] out = new int[count];
        for (int i = 0; i < count; i++) out[i] = from + i;
        return out;
    }

    /** The lines of {@code b} outside the longest common subsequence of
     *  the two middles carry the mark; the walk emits them descending. */
    private static int[] lcsMarks(List<String> a, List<String> b,
                                  int pre, int an, int bm) {
        int[][] dp = new int[an + 1][bm + 1];
        for (int i = 1; i <= an; i++)
            for (int j = 1; j <= bm; j++)
                dp[i][j] = a.get(pre + i - 1).equals(b.get(pre + j - 1))
                        ? dp[i - 1][j - 1] + 1
                        : Math.max(dp[i - 1][j], dp[i][j - 1]);
        ArrayList<Integer> out = new ArrayList<>();
        int i = an, j = bm;
        while (i > 0 && j > 0) {
            if (a.get(pre + i - 1).equals(b.get(pre + j - 1))) { i--; j--; }
            else if (dp[i - 1][j] >= dp[i][j - 1]) i--;
            else { out.add(pre + j - 1); j--; }
        }
        while (j > 0) { out.add(pre + j - 1); j--; }
        int[] lines = new int[out.size()];
        for (int k = 0; k < lines.length; k++) lines[k] = out.get(k);
        return lines;
    }

    private static List<String> lines(String text) {
        return Arrays.asList(text.split("\n", -1));
    }

    // ---- views: code ↔ rendered, word wrap ----

    /** Flips rendered ↔ source in place. Both sides cost nothing to
     *  enter: the code document is always current, and the page
     *  re-renders only when the text moved since it was last built — or
     *  when a Laf flip happened while it sat detached and the re-style
     *  cascade never reached it. */
    private void toggleView() {
        if (page == null) return;
        renderView = !renderView;
        if (renderView) {
            if (renderStale) page.render(normalizedText(), null);
            else if (pageThemeDirty) page.updateUI();
            renderStale = false;
            pageThemeDirty = false;
            showCenter(page);
            page.text().requestFocusInWindow();
        } else {
            showCenter(scroll);
            area.requestFocusInWindow();
        }
        updateBar();
        updateStrip();
    }

    /** Word wrap for whichever view is showing: the code folds its lines
     *  into the pane, the page folds everything — prose, cards, tables.
     *  Each side keeps its own state; the button always speaks for the
     *  view in front of you. */
    private void toggleWrap() {
        if (renderView && page != null) page.setWrap(!page.wraps());
        else {
            codeWrap = !codeWrap;
            area.setLineWrap(codeWrap);
        }
        updateBar();
    }

    /** The view and wrap controls speak for what is showing. */
    private void updateBar() {
        if (viewButton != null) {
            reicon(viewButton, renderView ? Glyphs.CODE : Glyphs.PICTURE);
            viewButton.setToolTipText(renderView ? "View source" : "View rendered");
        }
        boolean wrapped = renderView && page != null ? page.wraps() : codeWrap;
        wrapButton.setToolTipText(wrapped ? "Disable word wrap" : "Enable word wrap");
    }

    private static void reicon(JButton b, String glyph) {
        b.setIcon(Glyphs.iconUniform(glyph, Tokens.ICON_LARGE, EditorPanel::muted));
    }

    // ---- finding ----

    /** The find row: a field that marks matches live, Enter steps through
     *  them (Shift+Enter steps back), Escape folds it away. */
    private JPanel buildFindRow() {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(stripBorder());
        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.X_AXIS));
        inner.setOpaque(false);
        inner.setBorder(BorderFactory.createEmptyBorder(
                Tokens.GAP_1, Tokens.GAP_2, Tokens.GAP_1, Tokens.GAP_2));
        findField.setColumns(20);
        findField.setFont(FontRegistry.mono());
        findField.putClientProperty("JTextField.placeholderText", "Find");
        findField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { markAllLive(); }
            @Override public void removeUpdate(DocumentEvent e) { markAllLive(); }
            @Override public void changedUpdate(DocumentEvent e) {}
        });
        // Enter steps forward; Shift+Enter steps back.
        findField.addActionListener(e -> findNext(
                (e.getModifiers() & java.awt.event.ActionEvent.SHIFT_MASK) == 0));
        var fim = findField.getInputMap(WHEN_FOCUSED);
        var fam = findField.getActionMap();
        fim.put(KeyStroke.getKeyStroke("ESCAPE"), "dock-find-close");
        fam.put("dock-find-close", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                closeFind();
            }
        });
        findCount.setFont(FontRegistry.mono());
        findCount.setBorder(BorderFactory.createEmptyBorder(0, Tokens.GAP_2, 0, 0));
        inner.add(findField);
        inner.add(findCount);
        inner.add(Box.createHorizontalStrut(Tokens.GAP_2));
        inner.add(button(Glyphs.ARROW_UP, "Previous (Shift+Enter)", () -> findNext(false)));
        inner.add(button(Glyphs.ARROW_DOWN, "Next (Enter)", () -> findNext(true)));
        inner.add(Box.createHorizontalGlue());
        inner.add(button(Glyphs.CLOSE, "Close find (Esc)", this::closeFind));
        row.add(inner, BorderLayout.CENTER);
        return row;
    }

    /** Shows (or folds) the find row; opening lands the keyboard in it. */
    void toggleFind() {
        if (findRow.isVisible()) {
            closeFind();
            return;
        }
        findRow.setVisible(true);
        revalidate();
        repaint();
        if (!findField.getText().isEmpty()) markAllLive();
        findField.requestFocusInWindow();
        findField.selectAll();
    }

    /** Puts the keyboard back on the text and clears the marks. */
    private void closeFind() {
        findRow.setVisible(false);
        area.clearMarkAllHighlights();
        findCount.setText("");
        revalidate();
        repaint();
        area.requestFocusInWindow();
    }

    /** Marks every match as the query is typed — the count, not a jump. */
    private void markAllLive() {
        if (findField.getText().isEmpty()) {
            area.clearMarkAllHighlights();
            findCount.setText("");
            return;
        }
        SearchResult r = SearchEngine.markAll(area, context(true));
        int n = r.getMarkedCount();
        findCount.setText(n == 0 ? "no matches" : n + (n == 1 ? " match" : " matches"));
    }

    /** Steps to the next (or previous) match, wrapping, and reports the
     *  position among all matches. */
    private void findNext(boolean forward) {
        if (findField.getText().isEmpty()) return;
        SearchResult r = SearchEngine.find(area, context(forward));
        if (r.getMatchRange() == null) {
            findCount.setText("no matches");
            return;
        }
        int at = 0;
        for (DocumentRange range : area.getMarkAllHighlightRanges())
            if (range.getStartOffset() < r.getMatchRange().getStartOffset()) at++;
        findCount.setText((at + 1) + "/" + r.getMarkedCount());
    }

    /** The shared search setup: case-insensitive, wrapping, mark-all on. */
    private SearchContext context(boolean forward) {
        // The two-arg constructor's boolean is matchCase — direction is a
        // setter, easy to cross without looking.
        SearchContext c = new SearchContext(findField.getText());
        c.setSearchForward(forward);
        c.setMatchCase(false);
        c.setSearchWrap(true);
        c.setMarkAll(true);
        return c;
    }

    // ---- closing ----

    /** Escape retreats one level: the find bar folds before the editor
     *  ever offers to close. */
    private void escape() {
        if (findRow.isVisible()) {
            closeFind();
            return;
        }
        requestClose();
    }

    /** Escape lands here: dirty work asks, clean work just goes. */
    public void requestClose() {
        if (saving) return;   // a save is landing; close after it, not under it
        if (state != State.SHOWN || !dirty) {
            close();
            return;
        }
        int answer = closeAnswer();
        if (answer == JOptionPane.CANCEL_OPTION) return;
        if (answer == JOptionPane.YES_OPTION) {
            saveThen(this::close);   // closes only after the save landed
            return;
        }
        close();
    }

    private int closeAnswer() {
        Integer override = closeAnswerOverride;
        if (override != null) return override;
        return JOptionPane.showConfirmDialog(this,
                "Save changes to " + name + " before closing?", "Close",
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
    }

    private void close() {
        markTimer.stop();
        closed = true;
        onClose.run();
    }

    /** The keyboard landing point after the pane swap. */
    public void focusCanvas() {
        if (renderView && page != null) page.text().requestFocusInWindow();
        else area.requestFocusInWindow();
    }

    // ---- theming ----

    /** The look-and-feel can flip under an open editor (Dark follows
     *  Windows); re-apply so every surface follows it instead of freezing. */
    @Override public void updateUI() {
        super.updateUI();
        // The JPanel constructor fires updateUI before field initializers
        // run — nothing to restyle yet.
        if (area == null) return;
        applyTheme();
    }

    private void applyTheme() {
        Font font = FontRegistry.mono();
        Color fg = ui("TextArea.foreground", area.getForeground());
        Color bg = ui("TextArea.background", area.getBackground());
        Color muted = muted();
        Color accent = UIManager.getColor("Dock.accent");
        area.setFont(font);
        area.setBackground(bg);
        area.setForeground(fg);
        area.setCaretColor(accent != null ? accent : fg);
        area.setSelectionColor(ui("TextArea.selectionBackground", area.getSelectionColor()));
        area.setSelectedTextColor(ui("TextArea.selectionForeground", area.getSelectedTextColor()));
        Color line = UIManager.getColor("Dock.tileBackground");
        if (line != null) area.setCurrentLineHighlightColor(line);
        // Match marks: the accent, translucent, so plain text stays legible
        // under them in both themes.
        if (accent != null) area.setMarkAllHighlightColor(new Color(
                accent.getRed(), accent.getGreen(), accent.getBlue(), 72));
        // The six code hues the markdown code cards read, painted onto
        // the engine's own tokens through the shared bucketing.
        area.setSyntaxScheme(scheme(fg, new SyntaxPalette(
                UIManager.getColor("Dock.codeKeyword"),
                UIManager.getColor("Dock.codeType"),
                UIManager.getColor("Dock.codeString"),
                UIManager.getColor("Dock.codeComment"),
                UIManager.getColor("Dock.codeNumber"),
                UIManager.getColor("Dock.codeFunction"))));
        Gutter gutter = scroll.getGutter();
        gutter.setLineNumberColor(muted);
        gutter.setLineNumberFont(FontRegistry.monoMedium(font.getSize2D() - 1f));
        Color border = UIManager.getColor("Component.borderColor");
        if (border != null) gutter.setBorderColor(border);
        // The changed-line bar: the theme's diff green, sized to the line
        // so it reads as a mark on the line, not a dot beside it.
        Color changed = UIManager.getColor("Dock.editChanged");
        changedMark = barIcon(changed != null ? changed : new Color(0x3FB950),
                area.getFontMetrics(font).getHeight());
        // The mark column stands open from the start and never folds —
        // its width never changes, so nothing jumps when a mark appears.
        gutter.setIconRowHeaderEnabled(true);
        gutter.setIconRowHeaderInheritsGutterBackground(true);
        paintMarks();
        gutter.setBackground(bg);
        scroll.setBackground(bg);
        scroll.setBorder(null);
        title.setFont(FontRegistry.monoMedium(font.getSize2D() - 1f));
        title.setForeground(muted);
        strip.setFont(FontRegistry.mono());
        strip.setForeground(muted);
        stripBar.setBorder(stripBorder());
        findField.setFont(FontRegistry.mono());
        findCount.setFont(FontRegistry.mono());
        findCount.setForeground(muted);
        findRow.setBorder(stripBorder());
        // A Laf flip while the code view shows leaves the detached page
        // unstyled — the flip itself never reaches it through the tree.
        if (page != null) pageThemeDirty = true;
    }

    private static SyntaxScheme scheme(Color plain, SyntaxPalette colors) {
        SyntaxScheme s = new SyntaxScheme(FontRegistry.mono());
        for (int t = 0; t < TokenTypes.DEFAULT_NUM_TOKEN_TYPES; t++) {
            SyntaxKind kind = Syntax.kindOf(t);
            Color c = kind == null ? plain : colors.of(kind);
            s.setStyle(t, new Style(c != null ? c : plain, null));
        }
        return s;
    }

    /** The gutter mark: a slim rounded bar of the changed-line green. */
    private static Icon barIcon(Color c, int lineHeight) {
        int h = Math.max(6, lineHeight - 8);
        return new Icon() {
            @Override public int getIconWidth() { return 2; }
            @Override public int getIconHeight() { return h; }
            @Override public void paintIcon(Component comp, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(c);
                g2.fillRoundRect(x, y, 2, h, 2, 2);
            }
        };
    }

    // ---- strip ----

    private void updateTitle() {
        title.setText((dirty ? name + " •" : name) + "  ");
        title.setToolTipText(fs.child(dir, name));
    }

    private void updateStrip() {
        String s = switch (state) {
            case LOADING -> name + " · loading…";
            case REFUSED -> name + " · " + refuseReason;
            case SHOWN -> {
                StringBuilder b = new StringBuilder(name)
                        .append(" · ").append(area.getLineCount()).append(" lines")
                        .append(" · UTF-8").append(crlf ? " · CRLF" : " · LF");
                if (saving) b.append(" · saving…");
                else if (dirty) b.append(" · edited");
                if (renderView && page != null) {
                    b.append(" · rendered");
                } else {
                    int caret = Math.min(area.getCaretPosition(),
                            area.getDocument().getLength());
                    try {
                        int line = area.getLineOfOffset(caret);
                        b.append(" · Ln ").append(line + 1)
                                .append(", Col ")
                                .append(caret - area.getLineStartOffset(line) + 1);
                    } catch (Exception ignored) {}
                }
                b.append(" · Esc closes");
                yield b.toString();
            }
        };
        strip.setText(s);
    }

    // ---- the text surface ----

    /** The editing surface carrying the editor keys. */
    final class Editor extends RSyntaxTextArea {
        Editor() {
            bindKeys();
        }

        /** Editor keys live on the focused pane so they beat the ancestor
         *  maps — including the pane-hop arrows of the session. */
        private void bindKeys() {
            InputMap im = getInputMap(WHEN_FOCUSED);
            ActionMap am = getActionMap();
            bind(im, am, "ctrl S", "save", EditorPanel.this::save);
            bind(im, am, "ctrl F", "find", EditorPanel.this::toggleFind);
            // Escape is the only way out: backspace deletes text here, and
            // the find bar folds first.
            bind(im, am, "ESCAPE", "close", EditorPanel.this::escape);
        }

        private void bind(InputMap im, ActionMap am, String key, String name, Runnable action) {
            KeyStroke ks = KeyStroke.getKeyStroke(key);
            if (ks == null) throw new IllegalStateException("bad keystroke " + key);
            im.put(ks, name);
            am.put(name, new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    action.run();
                }
            });
        }
    }

    /** The loading / refusal surface: the pane's centered glyph-plus-word
     *  treatment, painted on the editing color. */
    private final class Notice extends JComponent {
        private final String glyph;
        private final Supplier<String> message;

        Notice(String glyph, Supplier<String> message) {
            this.glyph = glyph;
            this.message = message;
        }

        @Override protected void paintComponent(Graphics g) {
            int cw = getWidth(), ch = getHeight();
            if (cw == 0 || ch == 0) return;
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            Color c = muted();
            g2.setColor(ui("TextArea.background", getBackground()));
            g2.fillRect(0, 0, cw, ch);
            String textMsg = message.get();
            FontMetrics fm = getFontMetrics(FontRegistry.ui());
            int textW = fm.stringWidth(textMsg);
            var icon = Glyphs.icon(glyph, Tokens.ICON_HERO, () -> c);
            int total = icon.getIconWidth() + Tokens.GAP_3 + textW;
            int x = (cw - total) / 2, y = ch / 2;
            icon.paintIcon(this, g2, x, y - icon.getIconHeight() / 2);
            g2.setColor(c);
            g2.setFont(FontRegistry.ui());
            g2.drawString(textMsg, x + icon.getIconWidth() + Tokens.GAP_3,
                    y + fm.getAscent() / 2 - 1);
        }
    }

    private static String shortError(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m.split("\n")[0];
    }

    /** True when the text holds unsaved edits — the structural paths in
     *  the session view ask before discarding them. */
    public boolean isDirty() { return dirty; }

    /** The language the engine highlights the file as ("text/java"), or
     *  the plain style — an opaque id outside this module. */
    public String language() { return area.getSyntaxEditingStyle(); }

    /** True when the gutter shows line numbers — the harness pins it. */
    public boolean lineNumbersShown() { return scroll.getLineNumbersEnabled(); }

    /** How many lines wear a change mark — the harness and tests pin the
     *  gutter diff against the saved file. */
    public int changedLineCount() { return markedLines.length; }

    /** How many matches the find query currently marks — the harness. */
    public int markedMatches() { return area.getMarkAllHighlightRanges().size(); }

    /** True when the rendered page (not the code surface) is showing —
     *  the markdown open default, pinned by tests and the harness. */
    public boolean showingRender() { return renderView && page != null; }

    /** The embedded render surface for markdown files — null for files
     *  that don't render. */
    public MarkdownPage renderPage() { return page; }

    /** The code document's text — the source behind whichever view is
     *  showing; tests and the harness pin the two sides together
     *  through it. */
    public String codeText() { return area.getText(); }

    /** Fires the page's link resolution at a document offset — tests and
     *  the harness drive the click path. */
    public void followLinkForTest(int offset) {
        if (page != null) page.followAt(offset);
    }

    /** The reader's convention on the render open: every shown file is
     *  reported so the pane's cursor can follow. */
    public void onNavigate(java.util.function.Consumer<String> report) {
        onNavigate = report;
    }

    /** The find row's count text as shown — the harness. */
    public String findStatus() { return findCount.getText(); }

    // ---- for tests (same package) ----

    State stateForTest() { return state; }
    boolean dirtyForTest() { return dirty; }
    boolean savingForTest() { return saving; }
    boolean closedForTest() { return closed; }
    boolean saveEnabledForTest() { return saveButton.isEnabled(); }
    String refuseReasonForTest() { return refuseReason; }
    Editor areaForTest() { return area; }
    RTextScrollPane scrollForTest() { return scroll; }
    void setTextForTest(String text) { area.setText(text); }
    void confirmOverwriteForTest(Boolean answer) { confirmOverwriteOverride = answer; }
    void closeAnswerForTest(Integer answer) { closeAnswerOverride = answer; }
    boolean findRowVisibleForTest() { return findRow.isVisible(); }
    void typeFindForTest(String query) { findField.setText(query); }
    void fireFindForTest(boolean forward) { findNext(forward); }
    /** Runs the debounced gutter diff immediately. */
    void pumpMarksForTest() { markTimer.stop(); recomputeMarks(); }
    boolean codeWrapForTest() { return codeWrap; }

    /** Fires the view and wrap toggles — public because the screenshot
     *  harness drives them through the live editor. */
    public void toggleViewForTest() { toggleView(); }
    public void toggleWrapForTest() { toggleWrap(); }

    /** Fires the showing surface's binding for a keystroke — the real
     *  keyboard path, on the code area or the rendered page whichever
     *  is up. */
    public void fireKeyForTest(String spec) {
        KeyStroke ks = KeyStroke.getKeyStroke(spec);
        JComponent surface = renderView && page != null
                ? (JComponent) page.text() : area;
        Object name = surface.getInputMap(WHEN_FOCUSED).get(ks);
        if (name == null) throw new IllegalStateException("no binding for " + spec);
        Action action = surface.getActionMap().get(name);
        if (action == null) throw new IllegalStateException("no action for " + spec);
        action.actionPerformed(new java.awt.event.ActionEvent(surface, 0, "test"));
    }
}
