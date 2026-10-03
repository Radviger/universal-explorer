package dock.kit;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The window footer's shared info line — the commander convention. Content
 * deeper in the tree (a file pane) publishes transient text (the hovered
 * row's facts) and the shell's status bar shows it instead of floating a
 * tooltip over the file. All calls happen on the EDT.
 */
public final class StatusLine {

    private static final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private static String text = "";
    private static boolean mono;

    /** Shows {@code text} in the footer's reading font (blank or null
     *  reverts it to idle). */
    public static void publish(String text) { publish(text, false); }

    /** File facts — name, size, date — publish mono: they mirror the
     *  table's columns and their digits must line up. Sentences (hints,
     *  landing-screen lines) stay in the UI font. */
    public static void publish(String text, boolean mono) {
        StatusLine.text = text == null ? "" : text;
        StatusLine.mono = !StatusLine.text.isBlank() && mono;
        for (Consumer<String> l : listeners) l.accept(StatusLine.text);
    }

    /** Reverts the footer to its idle content. */
    public static void clear() { publish(""); }

    /** The line's current text (the last publish, "" when idle). */
    public static String text() { return text; }

    /** Whether the current text is file facts that read in the mono font. */
    public static boolean mono() { return mono; }

    /** Subscribes the consumer; it immediately receives the current text. */
    public static void addListener(Consumer<String> listener) {
        listeners.add(listener);
        listener.accept(text);
    }

    public static void removeListener(Consumer<String> listener) {
        listeners.remove(listener);
    }

    private StatusLine() {}
}
