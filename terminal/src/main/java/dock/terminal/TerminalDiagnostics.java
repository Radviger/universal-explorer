package dock.terminal;

import com.jediterm.terminal.ui.JediTermWidget;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;

/**
 * Buffer-level checks for the screenshot self-test: resize echoes and
 * console-codepage mojibake. Lives here because only the terminal
 * frontend may know JediTerm's widget type.
 */
public final class TerminalDiagnostics {

    private TerminalDiagnostics() { }

    /** The JediTerm widget shown in the window, or null. */
    public static Component widgetIn(Window w) {
        return find(w);
    }

    private static Component find(Component root) {
        if (root instanceof JediTermWidget widget) return widget;
        if (root instanceof Container c) {
            for (Component child : c.getComponents()) {
                Component hit = find(child);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /**
     * A visible line carrying a literal "stty rows/cols" — the resize bug
     * wrote the command into the shell, which echoed it as text — or null.
     */
    public static String resizeJunkLine(Window w) {
        JediTermWidget widget = castWidget(w);
        if (widget == null) return null;
        for (int i = 0; i < widget.getTerminalTextBuffer().getHeight(); i++) {
            String line = widget.getTerminalTextBuffer().getLine(i).getText();
            if (line.contains("stty rows") || line.contains("stty cols")) return line;
        }
        return null;
    }

    /**
     * True when the first buffer lines carry replacement characters or
     * runs of question marks — a banner decoded in the wrong codepage.
     */
    public static boolean bannerGarbled(Window w) {
        JediTermWidget widget = castWidget(w);
        if (widget == null) return false;
        for (int i = 0; i < 8; i++) {
            String line = widget.getTerminalTextBuffer().getLine(i).getText();
            // The literal U+FFFD replacement character (UTF-8 source).
            if (line.contains("￿") || line.matches(".*\\?{4,}.*")) {
                return true;
            }
        }
        return false;
    }

    private static JediTermWidget castWidget(Window w) {
        Component c = widgetIn(w);
        return c instanceof JediTermWidget widget ? widget : null;
    }
}
