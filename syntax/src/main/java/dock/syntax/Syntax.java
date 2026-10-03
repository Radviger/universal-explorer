package dock.syntax;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

import static org.fife.ui.rsyntaxtextarea.SyntaxConstants.*;

/**
 * Turns code text into {@link SyntaxSpan}s — the one entry point every
 * highlighting surface calls. The language id is a markdown fence word or
 * a file name ("java", "Main.java"); anything unrecognized answers an
 * empty list and the caller paints plain, so an unknown fence is a look,
 * never an error.
 *
 * <p>Runs anywhere, including off the EDT: the lexers are pure CPU and
 * never touch the look-and-feel. The one hazard is that RSyntaxTextArea's
 * default factory hands every caller the <em>same stateful lexer</em> per
 * language, so concurrent highlights would corrupt each other's scan —
 * the drive is therefore synchronized. A fenced snippet lexes in
 * microseconds; documents render one worker at a time; the lock is free
 * in practice.
 */
public final class Syntax {

    private Syntax() {}

    private static final TokenMakerFactory FACTORY = TokenMakerFactory.getDefaultInstance();

    /** True when the language (or a file named for it) has a lexer. */
    public static boolean supported(String language) {
        return style(language) != null;
    }

    /**
     * The underlying lexer engine's style id for a language or file name
     * ("Main.java" → {@code "text/java"}), or null when unrecognized.
     * Engine-specific by design and consumed only by the editor, which
     * runs the engine's own editing component; span consumers never need
     * this — they call {@link #highlight}.
     */
    public static String engineStyle(String language) {
        return style(language);
    }

    /**
     * Maps an engine token type to the span kind, or null when the token
     * paints as plain ink. Engine-specific like {@link #engineStyle}: the
     * editor colors the engine's own component through this same
     * bucketing, so a keyword on the editor surface and a keyword in a
     * markdown code card are decided by one table.
     */
    public static SyntaxKind kindOf(int engineTokenType) {
        return kind(engineTokenType);
    }

    /**
     * Highlights code in the language named. Spans are sorted by offset,
     * never overlap, and same-kind runs separated only by whitespace are
     * merged — a blank glyph carries no color, so the merge changes
     * nothing a painter can see.
     *
     * @param code     the exact text the caller will paint
     * @param language a fence word or a file name; null/blank answers empty
     */
    public static List<SyntaxSpan> highlight(String code, String language) {
        if (code == null || code.isEmpty()) return List.of();
        String style = style(language);
        if (style == null) return List.of();
        return drive(code, style);
    }

    private static synchronized List<SyntaxSpan> drive(String code, String style) {
        TokenMaker maker = FACTORY.getTokenMaker(style);
        List<SyntaxSpan> spans = new ArrayList<>();
        int carry = TokenTypes.NULL;
        int from = 0;
        while (from <= code.length()) {
            int nl = code.indexOf('\n', from);
            String line = nl < 0 ? code.substring(from) : code.substring(from, nl);
            Token last = null;
            for (Token t = maker.getTokenList(
                    new Segment(line.toCharArray(), 0, line.length()), carry, from);
                    t != null; t = t.getNextToken()) {
                last = t;
                if (t.getEndOffset() > t.getOffset()) {
                    SyntaxKind kind = kind(t.getType());
                    if (kind != null) add(spans, code, t.getOffset(), t.getEndOffset(), kind);
                }
            }
            // The chain's final token is the line's state handoff: NULL
            // when nothing is open, else the type of the construct that
            // continues — exactly what the next line's first call expects.
            carry = last != null ? last.getType() : TokenTypes.NULL;
            if (nl < 0) break;
            from = nl + 1;
        }
        return List.copyOf(spans);
    }

    private static void add(List<SyntaxSpan> spans, String code,
                            int start, int end, SyntaxKind kind) {
        if (!spans.isEmpty()) {
            SyntaxSpan last = spans.getLast();
            if (last.kind() == kind && blank(code, last.end(), start)) {
                spans.set(spans.size() - 1, new SyntaxSpan(last.start(), end, kind));
                return;
            }
        }
        spans.add(new SyntaxSpan(start, end, kind));
    }

    private static boolean blank(String code, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = code.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') return false;
        }
        return true;
    }

    private static SyntaxKind kind(int type) {
        return switch (type) {
            case TokenTypes.COMMENT_EOL, TokenTypes.COMMENT_MULTILINE,
                 TokenTypes.COMMENT_DOCUMENTATION, TokenTypes.COMMENT_KEYWORD,
                 TokenTypes.COMMENT_MARKUP, TokenTypes.MARKUP_COMMENT ->
                    SyntaxKind.COMMENT;
            // Primitives ride DATA_TYPE (java's int, rust's u32, c#'s
            // string) and read as keywords in every mainstream palette —
            // VS Code, IntelliJ — so the keyword blue takes them; the
            // teal type hue keeps annotations and markup attributes.
            case TokenTypes.RESERVED_WORD, TokenTypes.RESERVED_WORD_2,
                 TokenTypes.DATA_TYPE, TokenTypes.LITERAL_BOOLEAN,
                 TokenTypes.PREPROCESSOR, TokenTypes.MARKUP_TAG_NAME ->
                    SyntaxKind.KEYWORD;
            case TokenTypes.ANNOTATION, TokenTypes.MARKUP_TAG_ATTRIBUTE ->
                    SyntaxKind.TYPE;
            // VARIABLE is string-valued in practice: the json lexer types
            // its strings that way (the shell lexers leave $vars untyped).
            case TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, TokenTypes.LITERAL_CHAR,
                 TokenTypes.LITERAL_BACKQUOTE, TokenTypes.REGEX,
                 TokenTypes.ERROR_STRING_DOUBLE, TokenTypes.ERROR_CHAR,
                 TokenTypes.VARIABLE, TokenTypes.MARKUP_TAG_ATTRIBUTE_VALUE,
                 TokenTypes.MARKUP_ENTITY_REFERENCE, TokenTypes.MARKUP_CDATA ->
                    SyntaxKind.STRING;
            case TokenTypes.LITERAL_NUMBER_DECIMAL_INT, TokenTypes.LITERAL_NUMBER_FLOAT,
                 TokenTypes.LITERAL_NUMBER_HEXADECIMAL, TokenTypes.ERROR_NUMBER_FORMAT ->
                    SyntaxKind.NUMBER;
            case TokenTypes.FUNCTION -> SyntaxKind.FUNCTION;
            // Identifiers, operators, separators, whitespace, and any
            // engine-internal continuation type stay unspanned: plain ink.
            default -> null;
        };
    }

    // ---- languages ----

    private static final Map<String, String> STYLES = styles();

    private static Map<String, String> styles() {
        Map<String, String> m = new LinkedHashMap<>();
        // Canonical language names and their aliases, all lowercase; a
        // fence word ("js"), a shell name ("zsh"), and a file's extension
        // all reach the same style through one table.
        link(m, SYNTAX_STYLE_ACTIONSCRIPT, "actionscript");
        link(m, SYNTAX_STYLE_BBCODE, "bbcode");
        link(m, SYNTAX_STYLE_C, "c");
        link(m, SYNTAX_STYLE_CLOJURE, "clojure");
        link(m, SYNTAX_STYLE_CPLUSPLUS, "cpp c++ cc h hpp");
        link(m, SYNTAX_STYLE_CSHARP, "cs c# csharp");
        link(m, SYNTAX_STYLE_CSS, "css");
        link(m, SYNTAX_STYLE_CSV, "csv");
        link(m, SYNTAX_STYLE_D, "d");
        link(m, SYNTAX_STYLE_DART, "dart");
        link(m, SYNTAX_STYLE_DELPHI, "delphi pascal");
        link(m, SYNTAX_STYLE_DTD, "dtd");
        link(m, SYNTAX_STYLE_DOCKERFILE, "dockerfile docker");
        link(m, SYNTAX_STYLE_ENV, "env dotenv");
        link(m, SYNTAX_STYLE_FORTRAN, "fortran");
        link(m, SYNTAX_STYLE_GO, "golang go");
        link(m, SYNTAX_STYLE_GROOVY, "groovy");
        link(m, SYNTAX_STYLE_HANDLEBARS, "handlebars hbs");
        link(m, SYNTAX_STYLE_HOSTS, "hosts");
        link(m, SYNTAX_STYLE_HTACCESS, "htaccess");
        link(m, SYNTAX_STYLE_HTML, "html htm xhtml");
        link(m, SYNTAX_STYLE_INI, "ini conf cfg");
        link(m, SYNTAX_STYLE_JAVA, "java");
        link(m, SYNTAX_STYLE_JAVASCRIPT, "javascript js mjs cjs jsx");
        link(m, SYNTAX_STYLE_JSON, "json");
        link(m, SYNTAX_STYLE_JSP, "jsp");
        link(m, SYNTAX_STYLE_KOTLIN, "kotlin kt kts");
        link(m, SYNTAX_STYLE_LATEX, "latex tex");
        link(m, SYNTAX_STYLE_LESS, "less");
        link(m, SYNTAX_STYLE_LISP, "lisp");
        link(m, SYNTAX_STYLE_LUA, "lua");
        link(m, SYNTAX_STYLE_MAKEFILE, "makefile make gnumake");
        link(m, SYNTAX_STYLE_MARKDOWN, "markdown md");
        link(m, SYNTAX_STYLE_MXML, "mxml");
        link(m, SYNTAX_STYLE_NSIS, "nsis");
        link(m, SYNTAX_STYLE_PERL, "perl pl");
        link(m, SYNTAX_STYLE_PHP, "php");
        link(m, SYNTAX_STYLE_POWERSHELL, "powershell ps1 psm1");
        link(m, SYNTAX_STYLE_PROPERTIES_FILE, "properties props toml");
        link(m, SYNTAX_STYLE_PROTO, "proto");
        link(m, SYNTAX_STYLE_PYTHON, "python py");
        link(m, SYNTAX_STYLE_RUBY, "ruby rb");
        link(m, SYNTAX_STYLE_RUST, "rust rs");
        link(m, SYNTAX_STYLE_SAS, "sas");
        link(m, SYNTAX_STYLE_SCALA, "scala");
        link(m, SYNTAX_STYLE_SQL, "sql");
        link(m, SYNTAX_STYLE_TCL, "tcl");
        link(m, SYNTAX_STYLE_TYPESCRIPT, "typescript ts tsx");
        link(m, SYNTAX_STYLE_UNIX_SHELL, "unix sh bash zsh shell");
        link(m, SYNTAX_STYLE_VHDL, "vhdl");
        link(m, SYNTAX_STYLE_WINDOWS_BATCH, "bat cmd batch");
        link(m, SYNTAX_STYLE_XML, "xml svg xsl xsd xslt");
        link(m, SYNTAX_STYLE_YAML, "yaml yml");
        return Collections.unmodifiableMap(m);
    }

    private static void link(Map<String, String> m, String style, String keys) {
        for (String k : keys.split(" ")) m.put(k, style);
    }

    private static String style(String language) {
        if (language == null) return null;
        String key = language.strip().toLowerCase(Locale.ROOT);
        String style = STYLES.get(key);
        // A file name ("Main.java") declares its language in the
        // extension — the future code viewer passes names, not fence words.
        if (style == null) {
            int dot = key.lastIndexOf('.');
            if (dot >= 0 && key.length() > dot + 1) style = STYLES.get(key.substring(dot + 1));
        }
        return style;
    }
}
