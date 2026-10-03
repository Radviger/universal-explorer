package dock.markdown;

import java.awt.Color;
import java.awt.Font;
import javax.swing.UIManager;

import dock.kit.FontRegistry;
import dock.syntax.SyntaxPalette;

/**
 * The colors and fonts a rendered document is built from, snapshotted
 * before the parse runs. The renderer executes on a worker thread; reading
 * {@code UIManager} there would be both a race and a thread-safety hazard,
 * so the panel resolves the current theme on the EDT and hands the worker
 * this immutable record.
 */
record MdTheme(Font base, Font mono, Font[] headings, Color foreground, Color muted,
               Color accent, Color surface, Color codeBackground, Color border,
               SyntaxPalette code) {

    /** Heading sizes over the 13px base, h1 through h6. */
    private static final float[] HEADING_SIZES = {20f, 17f, 15f, 13f, 12f, 12f};

    /** Snapshot of the current look-and-feel's markdown palette. The
     *  document is set in the app's mono face — the reader is a
     *  terminal-native surface, one voice with the file lists and the
     *  terminal beside it; headings take the mono weights over it. */
    static MdTheme current() {
        Font[] headings = new Font[6];
        for (int i = 0; i < 6; i++) {
            headings[i] = i < 3
                    ? FontRegistry.monoBold(HEADING_SIZES[i])
                    : FontRegistry.monoMedium(HEADING_SIZES[i]);
        }
        Color foreground = UIManager.getColor("Label.foreground");
        Color muted = UIManager.getColor("Label.disabledForeground");
        if (muted == null) muted = foreground;
        return new MdTheme(FontRegistry.mono(), FontRegistry.mono(), headings,
                foreground, muted,
                UIManager.getColor("Dock.accent"),
                page(),
                UIManager.getColor("Dock.mdCodeBackground"),
                UIManager.getColor("Component.borderColor"),
                // Missing keys stay null on purpose: the palette answers
                // null and the code area falls back to plain ink, so a
                // half-themed look beats a half-rendered document.
                new SyntaxPalette(
                        UIManager.getColor("Dock.codeKeyword"),
                        UIManager.getColor("Dock.codeType"),
                        UIManager.getColor("Dock.codeString"),
                        UIManager.getColor("Dock.codeComment"),
                        UIManager.getColor("Dock.codeNumber"),
                        UIManager.getColor("Dock.codeFunction")));
    }

    /** The reading page: the look-and-feel's own text-surface color —
     *  the same family as the panels around it, a step off the chrome —
     *  so the reader belongs to the app in both themes instead of
     *  painting its own near-black sheet. */
    static Color page() {
        Color c = UIManager.getColor("TextPane.background");
        return c != null ? c : UIManager.getColor("Panel.background");
    }
}
