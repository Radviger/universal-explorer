import dock.commander.FilePane;
import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The right-click menu shows shortcuts through JMenuItem accelerators, so
 * the LaF paints them in its own right-aligned column the way the menu bar
 * does — never glued onto the label text. The shown key must also be a key
 * the pane actually binds, or the menu would advertise shortcuts that do
 * nothing.
 */
class ContextMenuTest {

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        com.formdev.flatlaf.FlatLaf.registerCustomDefaultsSource("dock.themes");
        com.formdev.flatlaf.FlatLaf.setup(new com.formdev.flatlaf.FlatDarkLaf());
    }

    @Test
    void shortcutsAreAcceleratorsNotLabelText() {
        Map<String, JMenuItem> items = menuItems();
        assertEquals(KeyStroke.getKeyStroke("F2"), items.get("Rename…").getAccelerator());
        assertEquals(KeyStroke.getKeyStroke("DELETE"), items.get("Delete").getAccelerator());
        assertEquals(KeyStroke.getKeyStroke("alt ENTER"),
                items.get("Properties…").getAccelerator());
        assertEquals(KeyStroke.getKeyStroke("ctrl R"), items.get("Refresh").getAccelerator());
        assertEquals(KeyStroke.getKeyStroke("ctrl D"),
                items.get("New folder").getAccelerator());
        assertEquals(KeyStroke.getKeyStroke("ctrl V"),
                items.get("Paste").getAccelerator());
        for (JMenuItem mi : items.values()) {
            String t = mi.getText();
            assertTrue(!t.contains("  "),
                    "shortcut leaked into the label text: \"" + t + "\"");
        }
    }

    @Test
    void shownShortcutsAreLivePaneBindings() {
        FilePane pane = paneReady();
        List<KeyStroke> shown = new ArrayList<>();
        List<KeyStroke> bound = new ArrayList<>();
        onEdt(() -> {
            for (java.awt.Component c : pane.buildContextMenu().getComponents()) {
                if (c instanceof JMenuItem mi && mi.getAccelerator() != null) {
                    shown.add(mi.getAccelerator());
                }
            }
            collect(pane.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT), bound);
            collect(pane.table().getInputMap(JComponent.WHEN_FOCUSED), bound);
        });
        for (KeyStroke acc : shown) {
            assertTrue(bound.contains(acc),
                    "menu shows " + acc + " but the pane binds no such key");
        }
    }

    // ---- helpers ----

    private static FilePane paneReady() {
        FilePane pane = new FilePane(dock.core.fs.LocalFs.INSTANCE);
        await(() -> onEdt(pane::rowCount) > 0);
        return pane;
    }

    private static Map<String, JMenuItem> menuItems() {
        FilePane pane = paneReady();
        return onEdt(() -> {
            Map<String, JMenuItem> byText = new LinkedHashMap<>();
            JPopupMenu menu = pane.buildContextMenu();
            for (java.awt.Component c : menu.getComponents()) {
                if (c instanceof JMenuItem mi) byText.put(mi.getText(), mi);
            }
            return byText;
        });
    }

    private static void collect(InputMap im, List<KeyStroke> into) {
        KeyStroke[] keys = im.keys();
        if (keys != null) into.addAll(List.of(keys));
    }

    /** Runs the read on the EDT (establishes happens-before with Swing). */
    private static <T> T onEdt(java.util.function.Supplier<T> read) {
        AtomicReference<T> out = new AtomicReference<>();
        try {
            EventQueue.invokeAndWait(() -> out.set(read.get()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out.get();
    }

    private static void onEdt(Runnable r) {
        try {
            EventQueue.invokeAndWait(r);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void await(java.util.function.BooleanSupplier cond) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!cond.getAsBoolean()) {
            try { Thread.sleep(10); } catch (InterruptedException e) { return; }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within 5s");
            }
        }
    }
}
