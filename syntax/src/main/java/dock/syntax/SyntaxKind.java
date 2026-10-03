package dock.syntax;

/**
 * The semantic categories a surface colors, deliberately far coarser than
 * any lexer's token types: an engine that distinguishes sixty token
 * varieties still communicates through these six, and a palette keys on
 * them. Anything unlisted — identifiers, operators, whitespace — is
 * simply not spanned and paints in the surface's foreground ink.
 */
public enum SyntaxKind {
    KEYWORD, TYPE, STRING, COMMENT, NUMBER, FUNCTION
}
