package dock.syntax;

import java.awt.Color;

/**
 * The six token colors a surface paints spans with. A color may be null —
 * {@link #of} then answers null and the caller falls back to its
 * foreground ink, so a partially-themed palette degrades quietly instead
 * of throwing mid-paint. Resolved on the EDT with the rest of a theme
 * snapshot; this module itself never reads the look-and-feel.
 */
public record SyntaxPalette(Color keyword, Color type, Color string,
                            Color comment, Color number, Color function) {

    /** The color for a kind, or null when the theme didn't provide one. */
    public Color of(SyntaxKind kind) {
        return switch (kind) {
            case KEYWORD -> keyword;
            case TYPE -> type;
            case STRING -> string;
            case COMMENT -> comment;
            case NUMBER -> number;
            case FUNCTION -> function;
        };
    }
}
