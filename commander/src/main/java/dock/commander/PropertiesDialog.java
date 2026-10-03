package dock.commander;

import dock.kit.Fmt;
import dock.kit.FontRegistry;
import dock.kit.Tokens;
import dock.core.fs.FileEntry;
import dock.core.fs.FileSystem;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.io.IOException;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Properties (and chmod) for one entry. Facts are read from the listing's
 * snapshot; the permission grid edits the classic rwx octal and applies it
 * with {@link FileSystem#setPerms}. Backends without POSIX modes (local
 * Windows) get the facts section only.
 */
public final class PropertiesDialog extends JDialog {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault());

    private static final int[] BIT = {0400, 0200, 0100, 0040, 0020, 0010, 0004, 0002, 0001};

    private final FileSystem fs;
    private final String fullPath;
    private final FileEntry entry;
    private final Runnable onChanged;
    /** Assigned in buildForm(), which only the constructor runs. */
    private JLabel nameValue;

    private final JLabel octalLabel = new JLabel("", JLabel.CENTER);
    private final JCheckBox[] permBoxes = new JCheckBox[9];
    private final JLabel errorLabel = new JLabel();
    private final JButton applyButton = new JButton("Apply");
    private final JButton okButton = new JButton("OK");
    private final boolean permsEditable;
    private int initialPerms;
    private int appliedPerms;

    /**
     * @param onChanged invoked (EDT) after a permission change was written;
     *                  the owning pane refreshes its listing.
     */
    public PropertiesDialog(Component owner, FileSystem fs, String dir, FileEntry entry,
                            Runnable onChanged) {
        super(owner == null ? null : SwingUtilities.windowForComponent(owner),
                "Properties — " + entry.name(), ModalityType.DOCUMENT_MODAL);
        this.fs = fs;
        this.fullPath = fs.child(dir, entry.name());
        this.entry = entry;
        this.onChanged = onChanged;
        // Modes show, but on an immutable listing (an archive mount, a share
        // list) there is nothing to write back — the grid stays out.
        this.permsEditable = entry.posixPerms() != null && !fs.immutableListing(dir);
        this.initialPerms = entry.posixPerms() == null ? 0 : entry.posixPerms();
        this.appliedPerms = initialPerms;

        setLayout(new BorderLayout());
        add(buildForm(), BorderLayout.CENTER);
        add(buildButtons(), BorderLayout.SOUTH);
        pack();
        setMinimumSize(new Dimension(420, getHeight()));
        setResizable(false);
        setLocationRelativeTo(getOwner());
        getRootPane().setDefaultButton(okButton);
    }

    private JComponent buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(Tokens.GAP_4, Tokens.GAP_4,
                Tokens.GAP_3, Tokens.GAP_4));
        var gc = new GridBagConstraints();
        gc.insets = new Insets(Tokens.GAP_1, Tokens.GAP_1, Tokens.GAP_1, Tokens.GAP_2);
        gc.anchor = GridBagConstraints.LINE_START;
        int row = 0;

        nameValue = row(form, gc, row++, "Name", entry.name(),
                FileIcons.icon(FileIcons.kindOf(entry), Tokens.ICON_LARGE));
        row(form, gc, row++, "Type", entry.directory() ? "Folder" : "File");
        row(form, gc, row++, "Location", fs.parent(fullPath));
        if (!entry.directory()) {
            row(form, gc, row++, "Size",
                    entry.size() + " bytes  (" + Fmt.bytes(entry.size()) + ")");
        } else {
            row(form, gc, row++, "Size", "—");
        }
        row(form, gc, row++, "Modified", entry.mtimeMillis() <= 0 ? "—"
                : STAMP.format(Instant.ofEpochMilli(entry.mtimeMillis())));

        if (permsEditable) form.add(permSection(gc, row), gc);
        return form;
    }

    /** Caption + read-only value row; values render in the mono face (paths/dates). */
    private void row(JPanel form, GridBagConstraints gc, int row, String caption, String value) {
        row(form, gc, row, caption, value, null);
    }

    /** Same row with a leading icon (the file-type icon on the Name row). */
    private JLabel row(JPanel form, GridBagConstraints gc, int row, String caption, String value,
                       Icon icon) {
        JLabel c = new JLabel(caption);
        c.setFont(FontRegistry.ui());
        gc.gridx = 0; gc.gridy = row; gc.fill = GridBagConstraints.NONE; gc.weightx = 0;
        form.add(c, gc);
        JLabel v = new JLabel(value, icon, JLabel.LEFT);
        v.setFont(FontRegistry.mono());
        if (icon != null) v.setIconTextGap(Tokens.GAP_2);
        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1;
        form.add(v, gc);
        gc.weightx = 0;
        return v;
    }

    /** The chmod grid: Owner/Group/Others columns × Read/Write/Execute rows. */
    private JComponent permSection(GridBagConstraints gc, int row) {
        JPanel section = new JPanel(new GridBagLayout());
        section.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0,
                        UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(Tokens.GAP_3, 0, 0, 0)));
        var pgc = new GridBagConstraints();
        pgc.insets = new Insets(Tokens.GAP_1, Tokens.GAP_2, Tokens.GAP_1, Tokens.GAP_2);
        pgc.anchor = GridBagConstraints.CENTER;

        // Column captions in the grid's own coordinate space (gridx 1..3).
        String[] who = {"Owner", "Group", "Others"};
        for (int i = 0; i < 3; i++) {
            JLabel h = new JLabel(who[i]);
            h.setFont(FontRegistry.ui());
            pgc.gridx = 1 + i; pgc.gridy = 0;
            section.add(h, pgc);
        }
        String[] what = {"Read", "Write", "Execute"};
        for (int b = 0; b < 9; b++) {
            final int bit = BIT[b];
            if (b % 3 == 0) {
                JLabel l = new JLabel(what[b / 3]);
                l.setFont(FontRegistry.ui());
                l.setForeground(FileTableModel.muted());
                pgc.gridx = 0; pgc.gridy = 1 + b / 3;
                pgc.anchor = GridBagConstraints.LINE_END;
                section.add(l, pgc);
                pgc.anchor = GridBagConstraints.CENTER;
            }
            JCheckBox box = new JCheckBox();
            box.setSelected((initialPerms & bit) != 0);
            box.addItemListener(e -> permsChanged());
            permBoxes[b] = box;
            pgc.gridx = 1 + b % 3; pgc.gridy = 1 + b / 3;
            section.add(box, pgc);
        }

        octalLabel.setFont(FontRegistry.monoMedium(FontRegistry.BASE_SIZE + 1));
        octalLabel.setText("%04o".formatted(initialPerms));
        var octalRow = new JPanel();
        octalRow.setLayout(new BoxLayout(octalRow, BoxLayout.X_AXIS));
        octalRow.add(new JLabel("Octal:"));
        octalRow.add(Box.createHorizontalStrut(Tokens.GAP_2));
        octalRow.add(octalLabel);
        pgc.gridx = 0; pgc.gridy = 4; pgc.gridwidth = 4;
        pgc.anchor = GridBagConstraints.LINE_START;
        section.add(octalRow, pgc);

        errorLabel.setForeground(new Color(0xE5484D));
        errorLabel.setFont(FontRegistry.ui());
        errorLabel.setVisible(false);
        pgc.gridy = 5;
        section.add(errorLabel, pgc);

        gc.gridx = 0; gc.gridy = row; gc.gridwidth = 2; gc.fill = GridBagConstraints.HORIZONTAL;
        return section;
    }

    private JComponent buildButtons() {
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0,
                        UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(Tokens.GAP_3, Tokens.GAP_4,
                        Tokens.GAP_3, Tokens.GAP_4)));
        applyButton.setEnabled(false);
        applyButton.addActionListener(e -> apply(false));
        buttons.add(applyButton);
        buttons.add(Box.createHorizontalGlue());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        okButton.putClientProperty("FlatLaf.style", "arc: " + Tokens.ARC + ";");
        okButton.addActionListener(e -> ok());
        buttons.add(cancel);
        buttons.add(Box.createHorizontalStrut(Tokens.GAP_2));
        buttons.add(okButton);
        return buttons;
    }

    // ---- chmod logic ----

    private int currentPerms() {
        int perms = 0;
        for (int b = 0; b < 9; b++) {
            if (permBoxes[b].isSelected()) perms |= BIT[b];
        }
        return perms;
    }

    /** Recomputed octal display + dirty state after any checkbox flips. */
    private void permsChanged() {
        errorLabel.setVisible(false);
        int perms = currentPerms();
        octalLabel.setText("%04o".formatted(perms));
        boolean dirty = perms != appliedPerms;
        applyButton.setEnabled(dirty);
        okButton.setText(dirty ? "Apply and close" : "OK");
    }

    private void ok() {
        if (currentPerms() != appliedPerms) apply(true);
        else dispose();
    }

    /** Writes the current octal off-EDT; {@code closeWhenDone} closes on success. */
    private void apply(boolean closeWhenDone) {
        int perms = currentPerms();
        applyButton.setEnabled(false);
        okButton.setEnabled(false);
        Thread.ofVirtual().name("dock-chmod").start(() -> {
            try {
                fs.setPerms(fullPath, perms);
                SwingUtilities.invokeLater(() -> {
                    appliedPerms = perms;
                    okButton.setEnabled(true);
                    permsChanged();
                    onChanged.run();
                    if (closeWhenDone) dispose();
                });
            } catch (IOException | RuntimeException ex) {
                SwingUtilities.invokeLater(() -> {
                    okButton.setEnabled(true);
                    permsChanged();
                    errorLabel.setText(ex.getMessage() == null ? ex.toString() : ex.getMessage());
                    errorLabel.setVisible(true);
                    pack();
                });
            }
        });
    }

    // ---- test hooks ----

    public Icon nameIconForTest() { return nameValue.getIcon(); }

    public String octalForTest() { return octalLabel.getText(); }

    public JCheckBox permBoxForTest(int index) { return permBoxes[index]; }

    public JButton applyButtonForTest() { return applyButton; }
    public JButton okButtonForTest() { return okButton; }

    /** Non-modal peek so a test can read section visibility without pumping. */
    public boolean permsSectionVisibleForTest() { return permsEditable; }

    public boolean errorVisibleForTest() { return errorLabel.isVisible(); }
}
