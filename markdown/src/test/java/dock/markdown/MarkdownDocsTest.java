package dock.markdown;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The one predicate behind every "opens in the reader" decision: the
 *  markdown extensions plus the repo convention names, exact — README
 *  renders, README.txt stays plain text. */
class MarkdownDocsTest {

    @Test
    void repoConventionNamesRenderWithoutAnExtension() {
        assertTrue(MarkdownDocs.renders("README"));
        assertTrue(MarkdownDocs.renders("license"));
        assertTrue(MarkdownDocs.renders("LICENCE"));
        assertTrue(MarkdownDocs.renders("ChangeLog"));
        assertTrue(MarkdownDocs.renders("CONTRIBUTING"));
        assertTrue(MarkdownDocs.renders("code_of_conduct"));
        assertTrue(MarkdownDocs.renders("TODO"));
    }

    @Test
    void markdownExtensionsRender() {
        assertTrue(MarkdownDocs.renders("notes.md"));
        assertTrue(MarkdownDocs.renders("README.MD"));
        assertTrue(MarkdownDocs.renders("notes.markdown"));
    }

    @Test
    void everythingElseStaysOut() {
        assertFalse(MarkdownDocs.renders("README.txt"));
        assertFalse(MarkdownDocs.renders("license.java"));
        assertFalse(MarkdownDocs.renders("Dockerfile"));
        assertFalse(MarkdownDocs.renders("Makefile"));
        assertFalse(MarkdownDocs.renders(""));
        assertFalse(MarkdownDocs.renders(null));
    }

    @Test
    void theSharedListIsWhatTheTableDispatchesOn() {
        // The file table maps every name in the shared list to its
        // markdown kind — keep the two from drifting apart.
        for (String n : MarkdownDocs.NAMES)
            assertTrue(MarkdownDocs.renders(n), n + " renders");
    }
}
