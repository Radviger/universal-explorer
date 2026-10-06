package dock.ui;

import dock.core.config.Site;
import dock.core.config.Sites;
import dock.kit.FontRegistry;
import dock.kit.Glyphs;
import dock.kit.Toast;
import dock.kit.Tokens;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.UIManager;

/**
 * The manual mode of an external source (~/.ssh/config, ~/.aws): every
 * entry as a checkbox, the checked ones saved as ordinary sessions —
 * editable, deletable, with path memory, independent of the source from
 * then on. Entries a saved session already names are listed but locked.
 */
final class SourceImportDialog extends JDialog {

    private final List<Site> hosts;
    private final List<JCheckBox> boxes = new ArrayList<>();
    private int imported;

    SourceImportDialog(Window owner, String title, List<Site> hosts) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        this.hosts = hosts;
        setLayout(new BorderLayout());
        add(buildBody(), BorderLayout.CENTER);
        add(buildButtons(), BorderLayout.SOUTH);
        pack();
        setMinimumSize(new Dimension(480, Math.min(getHeight(), 560)));
        setSize(getMinimumSize());
        setLocationRelativeTo(owner);
    }

    /** Shows the dialog; the number of sessions saved (0 on cancel). */
    int showAndImport() {
        setVisible(true);
        return imported;
    }

    private JPanel buildBody() {
        Set<String> saved = new HashSet<>();
        for (Site s : Sites.load()) saved.add(s.name());

        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        for (Site h : hosts) {
            JCheckBox box = new JCheckBox("<html><b>" + escape(h.name()) + "</b>&nbsp;&nbsp;"
                    + "<span style='color:gray'>" + escape(endpoint(h)) + "</span></html>");
            box.setFont(FontRegistry.ui());
            if (saved.contains(h.name())) {
                box.setEnabled(false);
                box.setToolTipText("Already a saved session");
            } else {
                box.setSelected(true);
            }
            boxes.add(box);
            list.add(box);
        }

        JLabel intro = new JLabel("Checked entries become saved sessions.");
        intro.setFont(FontRegistry.ui());
        intro.setBorder(BorderFactory.createEmptyBorder(0, 0, Tokens.GAP_2, 0));

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")));
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel body = new JPanel(new BorderLayout());
        body.setBorder(BorderFactory.createEmptyBorder(Tokens.GAP_4, Tokens.GAP_4,
                Tokens.GAP_2, Tokens.GAP_4));
        body.add(intro, BorderLayout.NORTH);
        body.add(scroll, BorderLayout.CENTER);
        return body;
    }

    private JPanel buildButtons() {
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0,
                        UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(Tokens.GAP_3, Tokens.GAP_4,
                        Tokens.GAP_3, Tokens.GAP_4)));

        JButton all = new JButton("Select All");
        all.addActionListener(e -> setAll(true));
        JButton none = new JButton("Select None");
        none.addActionListener(e -> setAll(false));
        JButton ok = new JButton("Import");
        ok.addActionListener(e -> importChecked());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());

        getRootPane().setDefaultButton(ok);
        buttons.add(all);
        buttons.add(Box.createHorizontalStrut(Tokens.GAP_2));
        buttons.add(none);
        buttons.add(Box.createHorizontalGlue());
        buttons.add(cancel);
        buttons.add(Box.createHorizontalStrut(Tokens.GAP_2));
        buttons.add(ok);
        return buttons;
    }

    private void setAll(boolean on) {
        for (JCheckBox b : boxes) if (b.isEnabled()) b.setSelected(on);
    }

    private void importChecked() {
        int count = 0;
        try {
            for (int i = 0; i < hosts.size(); i++) {
                JCheckBox b = boxes.get(i);
                if (b.isEnabled() && b.isSelected()) {
                    Sites.upsert(hosts.get(i));
                    count++;
                }
            }
        } catch (IOException e) {
            Toast.show(this, "Could not save: " + e.getMessage(), Glyphs.WARNING);
        }
        imported = count;
        dispose();
    }

    private static String endpoint(Site s) {
        var backend = dock.core.spi.Backends.of(s.protocol());
        return backend != null ? backend.secondaryText(s) : s.host();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
