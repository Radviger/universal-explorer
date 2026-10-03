package dock.syntax;

/**
 * One colored run over the code text: {@code [start, end)} offsets into
 * the exact string that was highlighted. Offsets stay meaningful only
 * against that same string — surfaces paint the text they lexed, never a
 * re-derived copy.
 *
 * @param start inclusive offset
 * @param end   exclusive offset
 * @param kind  the category to color this run
 */
public record SyntaxSpan(int start, int end, SyntaxKind kind) {

    public SyntaxSpan {
        if (start < 0 || end <= start)
            throw new IllegalArgumentException("empty or negative span: " + start + ".." + end);
    }
}
