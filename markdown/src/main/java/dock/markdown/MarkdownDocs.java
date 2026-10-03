package dock.markdown;

import java.util.Locale;
import java.util.Set;

/**
 * The names that open in the rendered reader even without a markdown
 * extension: the repo convention files GitHub also renders — README,
 * LICENSE, CHANGELOG and friends. Matches are exact (case-insensitive),
 * so {@code README} renders while {@code README.txt} stays plain text;
 * one shared list keeps the file table's dispatch and the reader's own
 * link walking from drifting apart.
 */
public final class MarkdownDocs {

    private MarkdownDocs() {}

    /** The extensionless repo convention names, lowercase. */
    public static final Set<String> NAMES = Set.of(
            "readme", "license", "licence", "changelog", "changes",
            "contributing", "authors", "contributors", "acknowledgments",
            "acknowledgements", "code_of_conduct", "security", "notice",
            "support", "todo", "history", "news", "install", "copying",
            "citation");

    /** True when a file of this name renders in the markdown reader. */
    public static boolean renders(String name) {
        if (name == null || name.isBlank()) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return NAMES.contains(n) || n.endsWith(".md") || n.endsWith(".markdown");
    }
}
