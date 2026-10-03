package dock.ui;

import dock.kit.FontRegistry;
import dock.kit.Tokens;
import dock.core.transfer.ConflictDecision;
import dock.core.transfer.ConflictInfo;
import dock.core.transfer.ConflictResolver;
import dock.core.transfer.ConflictRule;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * WinSCP-style overwrite confirmation: source vs target with sizes and
 * times, per-rule buttons, and an "apply to all remaining" checkbox.
 */
public final class ConflictDialog {

    private ConflictDialog() {}

    /** A resolver that shows the dialog (blocking the worker thread). */
    public static ConflictResolver resolver(Window fallbackOwner) {
        return info -> {
            AtomicReference<ConflictDecision> out = new AtomicReference<>(
                    ConflictDecision.once(ConflictRule.SKIP));
            try {
                SwingUtilities.invokeAndWait(() -> {
                    Window owner = KeyboardFocusManagerHelper.activeWindow(fallbackOwner);
                    out.set(show(owner, info));
                });
            } catch (Exception e) {
                return ConflictDecision.once(ConflictRule.SKIP);
            }
            return out.get();
        };
    }

    private static ConflictDecision show(Window owner, ConflictInfo info) {
        class Dlg extends JDialog {
            final AtomicReference<ConflictDecision> decision =
                    new AtomicReference<>(ConflictDecision.once(ConflictRule.SKIP));
            JCheckBox all;

            Dlg() {
                super(owner, "File exists", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
                setLayout(new BorderLayout());
                add(buildBody(), BorderLayout.CENTER);
                add(buildButtons(), BorderLayout.SOUTH);
                pack();
                setMinimumSize(new Dimension(520, getHeight()));
                setLocationRelativeTo(owner);
            }

            private JPanel buildBody() {
                JPanel body = new JPanel(new GridBagLayout());
                body.setBorder(BorderFactory.createEmptyBorder(Tokens.GAP_4, Tokens.GAP_4,
                        Tokens.GAP_2, Tokens.GAP_4));
                var gc = new GridBagConstraints();
                gc.insets = new Insets(Tokens.GAP_1, Tokens.GAP_2, Tokens.GAP_1, Tokens.GAP_2);
                gc.anchor = GridBagConstraints.LINE_START;

                JLabel q = new JLabel("Target file already exists. What should happen?");
                q.setFont(FontRegistry.uiMedium());
                gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 3; gc.fill = GridBagConstraints.HORIZONTAL;
                body.add(q, gc);
                gc.gridwidth = 1;

                int row = 1;
                gc.gridx = 0; gc.gridy = row; gc.gridwidth = 3;
                JLabel file = new JLabel(fileName(info.targetPath()));
                file.setFont(FontRegistry.monoMedium(FontRegistry.BASE_SIZE));
                body.add(file, gc);
                gc.gridwidth = 1;
                row++;

                body.add(caption("Source"), at(gc, 0, row));
                body.add(value(dock.kit.Fmt.sizeOf(info.source())
                        + "  ·  " + dock.kit.Fmt.dateOf(info.source())), at(gc, 1, row));
                row++;
                body.add(caption("Target"), at(gc, 0, row));
                body.add(value(dock.kit.Fmt.sizeOf(info.target())
                        + "  ·  " + dock.kit.Fmt.dateOf(info.target())), at(gc, 1, row));
                row++;
                body.add(caption("From"), at(gc, 0, row));
                body.add(value(pathOf(info.sourcePath())), at(gc, 1, row));

                if (info.remainingConflicts() > 1) {
                    all = new JCheckBox("Do this for the remaining "
                            + (info.remainingConflicts() - 1) + " conflicts");
                } else {
                    all = new JCheckBox("Do this for all conflicts");
                    all.setVisible(false);
                }
                gc.gridx = 0; gc.gridy = row + 1; gc.gridwidth = 3;
                body.add(all, gc);
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

                JButton overwrite = new JButton("Overwrite");
                overwrite.addActionListener(e -> pick(ConflictRule.OVERWRITE));
                JButton newer = new JButton("Overwrite if newer");
                newer.addActionListener(e -> pick(ConflictRule.OVERWRITE_IF_NEWER));
                JButton skip = new JButton("Skip");
                skip.addActionListener(e -> pick(ConflictRule.SKIP));
                JButton rename = new JButton("Keep both (rename)");
                rename.addActionListener(e -> pick(ConflictRule.RENAME));
                JButton cancel = new JButton("Cancel transfer");
                cancel.addActionListener(e -> pick(ConflictRule.SKIP));

                getRootPane().setDefaultButton(skip);
                buttons.add(Box.createHorizontalGlue());
                buttons.add(overwrite);
                buttons.add(Box.createHorizontalStrut(Tokens.GAP_2));
                buttons.add(newer);
                buttons.add(Box.createHorizontalStrut(Tokens.GAP_2));
                buttons.add(skip);
                buttons.add(Box.createHorizontalStrut(Tokens.GAP_2));
                buttons.add(rename);
                buttons.add(Box.createHorizontalStrut(Tokens.GAP_2));
                buttons.add(cancel);
                return buttons;
            }

            private void pick(ConflictRule rule) {
                decision.set(new ConflictDecision(rule, all != null && all.isSelected()));
                dispose();
            }
        }

        Dlg dlg = new Dlg();
        dlg.setVisible(true);
        return dlg.decision.get();
    }

    private static GridBagConstraints at(GridBagConstraints proto, int x, int y) {
        var gc = (GridBagConstraints) proto.clone();
        gc.gridx = x;
        gc.gridy = y;
        gc.fill = GridBagConstraints.NONE;
        gc.gridwidth = 1;
        return gc;
    }

    private static JLabel caption(String text) {
        JLabel l = new JLabel(text);
        l.setFont(FontRegistry.ui());
        l.setForeground(dock.kit.Tokens.muted());
        return l;
    }

    private static JLabel value(String text) {
        JLabel l = new JLabel(text);
        l.setFont(FontRegistry.mono());
        return l;
    }

    private static String fileName(String path) {
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return cut >= 0 ? path.substring(cut + 1) : path;
    }

    private static String pathOf(String path) {
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return cut > 0 ? path.substring(0, cut) : path;
    }

    private static final class KeyboardFocusManagerHelper {
        static Window activeWindow(Window fallback) {
            Window w = java.awt.KeyboardFocusManager
                    .getCurrentKeyboardFocusManager().getActiveWindow();
            return w != null ? w : fallback;
        }
    }
}
