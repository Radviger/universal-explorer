package dock.syntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The span contract: exact offsets, the multi-line carry between lines,
 * and the quiet fallbacks. Pure lexing — no look-and-feel, no EDT — so
 * every assertion here is byte-deterministic.
 */
final class SyntaxTest {

    private static final String JAVA = """
            // note
            public class Demo {
                int n = 42;
                String s = "hi";
            }""";

    @Test
    void javaTokensLandAtExactOffsets() {
        List<SyntaxSpan> spans = Syntax.highlight(JAVA, "java");
        List<String> keywords = slices(JAVA, spans, SyntaxKind.KEYWORD);
        assertTrue(keywords.contains("public class"),
                "adjacent keywords merge across the blank glyph: " + keywords);
        assertTrue(keywords.contains("int"),
                "a primitive type reads as a keyword: " + keywords);
        assertEquals(List.of("// note"), slices(JAVA, spans, SyntaxKind.COMMENT),
                "the line comment is one span");
        assertEquals(List.of("\"hi\""), slices(JAVA, spans, SyntaxKind.STRING),
                "the string includes its quotes");
        assertEquals(List.of("42"), slices(JAVA, spans, SyntaxKind.NUMBER),
                "the literal is exactly the digits");
    }

    @Test
    void blockCommentsCarryAcrossLines() {
        String code = """
                /* one
                   two */
                int x = 1;""";
        List<SyntaxSpan> spans = Syntax.highlight(code, "java");
        assertTrue(slices(code, spans, SyntaxKind.COMMENT).stream()
                        .anyMatch(s -> s.contains("one") && s.contains("two")),
                "the open comment continues onto the next line: " + spans);
        assertTrue(slices(code, spans, SyntaxKind.KEYWORD).contains("int"),
                "code after the closed comment lexes normally");
    }

    @Test
    void pythonDocstringsCarryAcrossLines() {
        String code = "s = \"\"\"\ntext\n\"\"\"\nx = 1";
        List<SyntaxSpan> spans = Syntax.highlight(code, "python");
        assertTrue(slices(code, spans, SyntaxKind.STRING).stream()
                        .anyMatch(s -> s.contains("text")),
                "the triple-quoted string spans its middle line: " + spans);
        assertTrue(slices(code, spans, SyntaxKind.NUMBER).contains("1"),
                "code after the string lexes normally");
    }

    @Test
    void adjacentSameKindRunsMergeAcrossWhitespace() {
        String code = "if (a) {} else if (b) {}";
        List<SyntaxSpan> spans = Syntax.highlight(code, "java");
        assertTrue(slices(code, spans, SyntaxKind.KEYWORD).contains("else if"),
                "two keywords split by a blank glyph read as one run: " + spans);
        String strings = "\"a\" + \"b\"";
        List<SyntaxSpan> two = Syntax.highlight(strings, "java");
        assertFalse(slices(strings, two, SyntaxKind.STRING).stream()
                        .anyMatch(s -> s.contains("+")),
                "runs separated by an operator never merge");
    }

    @Test
    void dataFormatsHighlight() {
        String json = "{\"k\": [1, 2]}";
        List<SyntaxSpan> spans = Syntax.highlight(json, "json");
        assertTrue(slices(json, spans, SyntaxKind.STRING).contains("\"k\""),
                "json strings color: " + spans);
        assertTrue(slices(json, spans, SyntaxKind.NUMBER).containsAll(List.of("1", "2")),
                "json numbers color: " + spans);
        String yaml = "name: dock\n# note\n";
        assertTrue(slices(yaml, Syntax.highlight(yaml, "yaml"), SyntaxKind.COMMENT)
                        .contains("# note"), "yaml comments color");
        String toml = "# note\nkey = \"v\"";
        assertTrue(slices(toml, Syntax.highlight(toml, "toml"), SyntaxKind.COMMENT)
                        .contains("# note"), "toml rides the properties lexer");
    }

    @Test
    void aliasesAndFileNamesResolve() {
        assertTrue(Syntax.supported("JS"));
        assertTrue(Syntax.supported("c++"));
        assertTrue(Syntax.supported("Main.java"));
        assertTrue(Syntax.supported("README.MD"));
        assertFalse(Syntax.supported("nosuchlang"));
        assertFalse(Syntax.supported(""));
        assertFalse(Syntax.supported(null));
        String py = "s = 'hi'";
        assertTrue(slices(py, Syntax.highlight(py, "py"), SyntaxKind.STRING)
                        .contains("'hi'"), "the py alias lexes python");
        String sh = "if [ -f x ]; then echo done; fi";
        assertTrue(Syntax.highlight(sh, "bash").stream()
                        .anyMatch(s -> s.kind() == SyntaxKind.KEYWORD),
                "the bash alias lexes shell");
    }

    @Test
    void unknownOrNullFallsBackToPlain() {
        assertTrue(Syntax.highlight("int x = 1;", "brainfuck").isEmpty());
        assertTrue(Syntax.highlight("int x = 1;", null).isEmpty());
        assertTrue(Syntax.highlight("", "java").isEmpty());
        assertTrue(Syntax.highlight(null, "java").isEmpty());
    }

    @Test
    void spansAreSortedAndDisjoint() {
        List<SyntaxSpan> spans = Syntax.highlight(JAVA, "java");
        assertFalse(spans.isEmpty());
        int lastEnd = -1;
        for (SyntaxSpan s : spans) {
            assertTrue(s.start() >= lastEnd,
                    "spans never overlap or backtrack: " + spans);
            lastEnd = s.end();
        }
    }

    private static List<String> slices(String code, List<SyntaxSpan> spans, SyntaxKind kind) {
        return spans.stream()
                .filter(s -> s.kind() == kind)
                .map(s -> code.substring(s.start(), s.end()))
                .toList();
    }
}
