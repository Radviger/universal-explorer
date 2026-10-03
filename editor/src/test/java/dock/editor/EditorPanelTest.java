package dock.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import dock.kit.FontRegistry;
import java.awt.Color;
import java.awt.EventQueue;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.JFrame;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The editor loads a file faithfully (EOL, BOM, language), tracks dirty
 *  edits by content — undoing back to the file is not dirty — lights the
 *  save button and the gutter's changed-line marks only for real changes,
 *  writes back through the pane's filesystem, refuses binary and
 *  oversized files, and asks before overwriting outside changes
 *  (including a file deleted or renamed away) or closing dirty work. */
class EditorPanelTest {

    private MemFs fs;
    private final List<JFrame> frames = new ArrayList<>();
    private JFrame frame;
    private EditorPanel panel;
    private int savedRuns;
    private boolean closed;
    /** Where same-folder markdown links from the render view land. */
    private final List<String> linked = new ArrayList<>();

    @BeforeAll
    static void theme() {
        FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @BeforeEach
    void build() throws Exception {
        fs = new MemFs();
        fs.add("/a.java", "public class A {\r\n    // hi\r\n}\r\n"
                .getBytes(StandardCharsets.UTF_8));
        mount("a.java");
        await(() -> panel.stateForTest() == EditorPanel.State.SHOWN, "a.java loaded");
    }

    /** A second editor over the same filesystem — the refuse paths. */
    private EditorPanel mount(String name) throws Exception {
        return mount(name, false);
    }

    private EditorPanel mount(String name, boolean renderFirst) throws Exception {
        EditorPanel[] held = new EditorPanel[1];
        EventQueue.invokeAndWait(() -> {
            savedRuns = 0;
            closed = false;
            held[0] = panel = new EditorPanel(fs, "/", name, renderFirst,
                    opened -> linked.add(opened), () -> savedRuns++, () -> closed = true);
            frame = new JFrame("ed-" + name);
            frames.add(frame);
            frame.add(panel);
            frame.setSize(600, 400);
            frame.setLocation(-2000, 0);
            frame.setVisible(true);
        });
        return held[0];
    }

    @AfterEach
    void tearDown() {
        List<JFrame> open = new ArrayList<>(frames);
        frames.clear();
        EventQueue.invokeLater(() -> open.forEach(JFrame::dispose));
    }

    static void await(Supplier<Boolean> until, String what) throws Exception {
        for (int i = 0; i < 250; i++) {
            if (until.get()) return;
            Thread.sleep(20);
        }
        assertTrue(until.get(), what + " (timed out)");
    }

    private void type(String text) throws Exception {
        EventQueue.invokeAndWait(() -> panel.setTextForTest(text));
    }

    private void save() throws Exception {
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("ctrl S"));
    }

    @Test
    void loadsTheFileNormalizedAndPinsItsLanguage() {
        assertEquals("public class A {\n    // hi\n}\n",
                panel.areaForTest().getText());
        assertEquals("text/java", panel.areaForTest().getSyntaxEditingStyle());
        assertFalse(panel.dirtyForTest());
        assertTrue(panel.scrollForTest().getLineNumbersEnabled(),
                "the gutter shows line numbers");
        assertEquals(0, panel.areaForTest().getCaretPosition());
    }

    @Test
    void typingMarksDirtyAndCtrlSWritesTheRememberedEolBack() throws Exception {
        type("public class A {\n    int x = 1;\n}\n");
        assertTrue(panel.dirtyForTest(), "an edit marks the file dirty");
        assertTrue(panel.saveEnabledForTest(), "the save button lights for real edits");
        panel.pumpMarksForTest();
        assertTrue(panel.changedLineCount() > 0,
                "changed lines wear gutter marks");
        save();
        await(() -> fs.lastWritten("/a.java") != null && !panel.savingForTest(),
                "the save landed and settled");
        assertEquals("public class A {\r\n    int x = 1;\r\n}\r\n",
                new String(fs.lastWritten("/a.java"), StandardCharsets.UTF_8),
                "the CRLF file keeps its CRLF");
        assertFalse(panel.dirtyForTest());
        assertFalse(panel.saveEnabledForTest(), "a landed save puts the button out");
        assertEquals(0, panel.changedLineCount(), "saving clears the marks");
        assertEquals(1, savedRuns, "the pane hears the save");
    }

    @Test
    void theByteOrderMarkSurvivesTheRoundTrip() throws Exception {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "x = 1\n".getBytes(StandardCharsets.UTF_8);
        byte[] file = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, file, 0, bom.length);
        System.arraycopy(body, 0, file, bom.length, body.length);
        fs.add("/bom.java", file);
        mount("bom.java");
        await(() -> panel.stateForTest() == EditorPanel.State.SHOWN, "bom.java loaded");
        type("x = 2\n");
        save();
        await(() -> fs.lastWritten("/bom.java") != null, "the save landed");
        byte[] written = fs.lastWritten("/bom.java");
        assertEquals(0xEF, written[0] & 0xFF, "the BOM byte 1");
        assertEquals(0xBB, written[1] & 0xFF, "the BOM byte 2");
        assertEquals(0xBF, written[2] & 0xFF, "the BOM byte 3");
        assertEquals("x = 2\n", new String(written, 3, written.length - 3,
                StandardCharsets.UTF_8));
    }

    @Test
    void aCleanFileSavesNothing() throws Exception {
        save();
        Thread.sleep(250);
        assertNull(fs.lastWritten("/a.java"), "no bytes leave a clean editor");
        assertFalse(panel.savingForTest());
    }

    @Test
    void binaryFilesAreRefused() throws Exception {
        fs.add("/x.png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 1, 0, 2, 0});
        mount("x.png");
        await(() -> panel.stateForTest() == EditorPanel.State.REFUSED, "binary refused");
        assertTrue(panel.refuseReasonForTest().contains("text"));
    }

    @Test
    void oversizedFilesAreRefused() throws Exception {
        fs.add("/big.log", new byte[(int) (8L * 1024 * 1024 + 1)]);
        mount("big.log");
        await(() -> panel.stateForTest() == EditorPanel.State.REFUSED, "cap refused");
        assertTrue(panel.refuseReasonForTest().contains("large"));
    }

    @Test
    void anOutsideChangeAsksBeforeItIsOverwritten() throws Exception {
        fs.mutate("/a.java", "// changed elsewhere\n".getBytes(StandardCharsets.UTF_8));
        type("// mine\n");
        panel.confirmOverwriteForTest(Boolean.FALSE);
        save();
        await(() -> !panel.savingForTest(), "the skipped save settled");
        assertNull(fs.lastWritten("/a.java"), "answering no keeps the outside version");
        assertTrue(panel.dirtyForTest(), "the edits survive");
        panel.confirmOverwriteForTest(Boolean.TRUE);
        save();
        await(() -> fs.lastWritten("/a.java") != null, "the overwriting save landed");
        assertTrue(new String(fs.lastWritten("/a.java"), StandardCharsets.UTF_8)
                .startsWith("// mine"), "answering yes overwrites");
    }

    @Test
    void aFileDeletedOutsideAsksBeforeItIsRecreated() throws Exception {
        type("// mine\n");
        fs.delete("/a.java");
        panel.confirmOverwriteForTest(Boolean.FALSE);
        save();
        await(() -> !panel.savingForTest(), "the skipped save settled");
        assertNull(fs.lastWritten("/a.java"), "answering no does not resurrect the file");
        assertFalse(fs.exists("/a.java"));
        assertTrue(panel.dirtyForTest(), "the edits survive a refused save");
        panel.confirmOverwriteForTest(Boolean.TRUE);
        save();
        await(() -> fs.lastWritten("/a.java") != null, "the recreating save landed");
        assertTrue(fs.exists("/a.java"), "answering yes writes the file back");
        assertTrue(new String(fs.lastWritten("/a.java"), StandardCharsets.UTF_8)
                .startsWith("// mine"), "the recreation carries the edits");
    }

    @Test
    void undoingBackToTheFileLeavesNothingToSave() throws Exception {
        String file = "public class A {\n    // hi\n}\n";
        type(file + "// draft\n");
        assertTrue(panel.dirtyForTest());
        assertTrue(panel.saveEnabledForTest());
        panel.pumpMarksForTest();
        assertTrue(panel.changedLineCount() > 0, "the draft line wears a mark");
        // Undo the typing away — however many undo steps the replace took.
        for (int i = 0; panel.dirtyForTest() && i < 10; i++)
            EventQueue.invokeAndWait(() -> panel.areaForTest().undoLastAction());
        assertFalse(panel.dirtyForTest(),
                "text equal to the file is not dirty, whatever the keys did");
        assertFalse(panel.saveEnabledForTest(), "nothing to save, no lit button");
        panel.pumpMarksForTest();
        assertEquals(0, panel.changedLineCount(), "the marks clear with the text");
        // Clean work closes without a dialog — a dialog here would hang
        // the EDT and time the test out.
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("ESCAPE"));
        assertTrue(closed, "undo-back closes without asking to save");
        assertNull(fs.lastWritten("/a.java"), "no bytes ever left");
    }

    @Test
    void changedLinesWearGutterMarksUntilTheSaveClearsThem() throws Exception {
        fs.add("/lines.txt", "one\ntwo\nthree\n".getBytes(StandardCharsets.UTF_8));
        mount("lines.txt");
        await(() -> panel.stateForTest() == EditorPanel.State.SHOWN, "lines.txt loaded");
        assertEquals(0, panel.changedLineCount(), "a clean file has no marks");
        assertTrue(panel.scrollForTest().getGutter().isIconRowHeaderEnabled(),
                "the mark column stands open even while clean — no width jump");
        type("one\nTWO\nthree\nfour\n");
        panel.pumpMarksForTest();
        // "TWO" changed and "four" was added; "one" and "three" survive the
        // common subsequence, so exactly two marks.
        assertEquals(2, panel.changedLineCount(),
                "one changed line and one added line carry marks");
        assertTrue(panel.scrollForTest().getGutter().isIconRowHeaderEnabled(),
                "the icon column stands the marks in");
        save();
        await(() -> fs.lastWritten("/lines.txt") != null && !panel.savingForTest(),
                "the save landed");
        assertEquals(0, panel.changedLineCount(), "saving clears the marks");
        assertTrue(panel.scrollForTest().getGutter().isIconRowHeaderEnabled(),
                "the mark column keeps its width after the save");
    }

    @Test
    void closingDirtyWorkAsksAndDiscardsOnNo() throws Exception {
        type("// draft\n");
        panel.closeAnswerForTest(javax.swing.JOptionPane.NO_OPTION);
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("ESCAPE"));
        assertTrue(closed, "discard closes the editor");
        assertNull(fs.lastWritten("/a.java"), "discard writes nothing");
    }

    @Test
    void closingDirtyWorkSavesBeforeItGoes() throws Exception {
        type("// draft\n");
        panel.closeAnswerForTest(javax.swing.JOptionPane.YES_OPTION);
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("ESCAPE"));
        await(() -> closed && fs.lastWritten("/a.java") != null,
                "save-then-close landed both");
    }

    @Test
    void cancelingTheCloseKeepsTheEditorOpen() throws Exception {
        type("// draft\n");
        panel.closeAnswerForTest(javax.swing.JOptionPane.CANCEL_OPTION);
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("ESCAPE"));
        assertFalse(closed);
        assertTrue(panel.dirtyForTest());
    }

    @Test
    void ctrlFFindsMarksAndCycles() throws Exception {
        String text = "int answer = 42; // answer\nString answer;\n";
        type(text);
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("ctrl F"));
        assertTrue(panel.findRowVisibleForTest(), "ctrl+F opens the find row");
        EventQueue.invokeAndWait(() -> panel.typeFindForTest("answer"));
        assertEquals(3, panel.markedMatches(), "every match is marked live");
        assertEquals("3 matches", panel.findStatus());

        EventQueue.invokeAndWait(() -> panel.fireFindForTest(true));
        int first = text.indexOf("answer");
        assertEquals(first + "answer".length(),
                panel.areaForTest().getCaretPosition(),
                "Enter lands the selection on the first match");
        EventQueue.invokeAndWait(() -> panel.fireFindForTest(true));
        int second = text.indexOf("answer", first + 1);
        assertEquals(second + "answer".length(),
                panel.areaForTest().getCaretPosition(), "next steps forward");
        assertEquals("2/3", panel.findStatus());
        EventQueue.invokeAndWait(() -> panel.fireFindForTest(false));
        assertEquals(first + "answer".length(),
                panel.areaForTest().getCaretPosition(), "previous steps back");
        assertEquals("1/3", panel.findStatus());
    }

    @Test
    void escapeFoldsTheFindBarBeforeTheEditor() throws Exception {
        type("// draft\n");
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("ctrl F"));
        assertTrue(panel.findRowVisibleForTest());
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("ESCAPE"));
        assertFalse(panel.findRowVisibleForTest(), "the first escape folds the bar");
        assertFalse(panel.closedForTest(), "the editor itself stays");
        assertEquals(0, panel.markedMatches(), "the marks clear with the bar");
        // The second escape is the editor's: dirty work asks, discard.
        panel.closeAnswerForTest(javax.swing.JOptionPane.NO_OPTION);
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("ESCAPE"));
        assertTrue(panel.closedForTest());
    }

    @Test
    void theSchemeFollowsTheTheme() throws Exception {
        Color darkKeyword = panel.areaForTest().getSyntaxScheme()
                .getStyle(TokenTypes.RESERVED_WORD).foreground;
        assertNotNull(darkKeyword, "the dark keyword hue resolves");
        Color darkString = panel.areaForTest().getSyntaxScheme()
                .getStyle(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE).foreground;
        Color darkPlain = panel.areaForTest().getSyntaxScheme()
                .getStyle(TokenTypes.IDENTIFIER).foreground;
        try {
            EventQueue.invokeAndWait(() -> {
                FlatLaf.setup(new FlatLightLaf());
                panel.updateUI();
            });
            Color lightKeyword = panel.areaForTest().getSyntaxScheme()
                    .getStyle(TokenTypes.RESERVED_WORD).foreground;
            Color lightString = panel.areaForTest().getSyntaxScheme()
                    .getStyle(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE).foreground;
            Color lightPlain = panel.areaForTest().getSyntaxScheme()
                    .getStyle(TokenTypes.IDENTIFIER).foreground;
            assertNotEquals(darkKeyword, lightKeyword, "keywords re-hue");
            assertNotEquals(darkString, lightString, "strings re-hue");
            // Identifier ink is the laf's text foreground, never a code hue.
            assertNotNull(lightPlain);
            assertNotEquals(lightKeyword, lightPlain, "identifiers stay plain ink");
        } finally {
            EventQueue.invokeAndWait(() -> {
                FlatLaf.setup(new FlatDarkLaf());
                panel.updateUI();
            });
        }
    }

    // ---- the markdown render view ----

    private static String docText(javax.swing.text.StyledDocument d) {
        try {
            return d.getText(0, d.getLength());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void markdownOpensOnTheRenderedPage() throws Exception {
        fs.add("/readme.md", "# Title\n\nrendered body\n"
                .getBytes(StandardCharsets.UTF_8));
        mount("readme.md", true);
        await(() -> panel.stateForTest() == EditorPanel.State.SHOWN, "loads");
        assertTrue(panel.showingRender(), "the rendered page stands in");
        assertNotNull(panel.renderPage(), "the flip exists for markdown");
        await(() -> panel.renderPage().ready(), "the page renders");
        String t = docText(panel.renderPage().document());
        assertTrue(t.contains("rendered body"), "the body rendered: " + t);
        assertEquals("# Title\n\nrendered body\n", panel.areaForTest().getText(),
                "the code document is in hand behind the page");
    }

    @Test
    void markdownOpenedForEditStartsOnTheCode() throws Exception {
        fs.add("/readme.md", "# Title\n\nbody\n".getBytes(StandardCharsets.UTF_8));
        mount("readme.md", false);
        await(() -> panel.stateForTest() == EditorPanel.State.SHOWN, "loads");
        assertFalse(panel.showingRender(), "F4 lands straight on the source");
        assertNotNull(panel.renderPage(), "but the flip is there");
    }

    @Test
    void nonMarkdownFilesHaveNoFlip() throws Exception {
        assertFalse(panel.showingRender());
        assertNull(panel.renderPage(), "nothing to render for a.java");
    }

    @Test
    void theViewToggleFlipsInPlace() throws Exception {
        fs.add("/readme.md", "# Title\n\nbody\n".getBytes(StandardCharsets.UTF_8));
        mount("readme.md", true);
        await(() -> panel.stateForTest() == EditorPanel.State.SHOWN
                && panel.renderPage().ready(), "rendered");
        EventQueue.invokeAndWait(() -> panel.toggleViewForTest());
        assertFalse(panel.showingRender(), "the code shows");
        assertTrue(panel.areaForTest().getText().contains("body"));
        EventQueue.invokeAndWait(() -> panel.toggleViewForTest());
        assertTrue(panel.showingRender(), "the page shows again");
        assertTrue(docText(panel.renderPage().document()).contains("body"),
                "the same document stands — the flip built nothing");
    }

    @Test
    void editsMadeInTheCodeReRenderOnTheFlip() throws Exception {
        fs.add("/readme.md", "# Title\n\nbody\n".getBytes(StandardCharsets.UTF_8));
        mount("readme.md", true);
        await(() -> panel.stateForTest() == EditorPanel.State.SHOWN
                && panel.renderPage().ready(), "rendered");
        EventQueue.invokeAndWait(() -> panel.toggleViewForTest());
        EventQueue.invokeAndWait(() -> panel.setTextForTest("# Title\n\nbody extended\n"));
        assertTrue(panel.dirtyForTest(), "the edit dirtied the code");
        EventQueue.invokeAndWait(() -> panel.toggleViewForTest());
        await(() -> docText(panel.renderPage().document()).contains("extended"),
                "the re-render picks the edit up");
    }

    /** One button, two views: the code folds its own lines (off by
     *  default, the editor norm), the page folds everything (on by
     *  default, the reader norm) — each side keeps its state across
     *  flips. */
    @Test
    void wordWrapIsUniversal() throws Exception {
        fs.add("/readme.md", ("# Title\n\n" + "word ".repeat(100) + "\n")
                .getBytes(StandardCharsets.UTF_8));
        mount("readme.md", true);
        await(() -> panel.stateForTest() == EditorPanel.State.SHOWN
                && panel.renderPage().ready(), "rendered");
        assertTrue(panel.renderPage().wraps(), "the page wraps by default");
        EventQueue.invokeAndWait(() -> panel.toggleWrapForTest());
        assertFalse(panel.renderPage().wraps(), "the button unwraps the page");
        EventQueue.invokeAndWait(() -> panel.toggleViewForTest());
        assertFalse(panel.areaForTest().getLineWrap(),
                "the code stays unwrapped by default");
        EventQueue.invokeAndWait(() -> panel.toggleWrapForTest());
        assertTrue(panel.areaForTest().getLineWrap(), "the same button wraps the code");
        EventQueue.invokeAndWait(() -> panel.toggleViewForTest());
        assertFalse(panel.renderPage().wraps(),
                "the page kept its own state across the flips");
    }

    @Test
    void renderLinksOpenFilesWhenCleanAndRefuseWhenDirty() throws Exception {
        fs.add("/a.md", "[b](b.md)\n".getBytes(StandardCharsets.UTF_8));
        fs.add("/b.md", "# B\n".getBytes(StandardCharsets.UTF_8));
        mount("a.md", true);
        await(() -> panel.stateForTest() == EditorPanel.State.SHOWN
                && panel.renderPage().ready(), "rendered");
        var doc = panel.renderPage().document();
        // Clean: the link opens through the host.
        int link = docText(doc).indexOf("b");
        EventQueue.invokeAndWait(() -> panel.followLinkForTest(link));
        assertEquals(List.of("b.md"), linked, "clean links open the file");
        // Dirty: following would drop the edits — the editor refuses.
        EventQueue.invokeAndWait(() -> panel.setTextForTest("[b](b.md) changed\n"));
        assertTrue(panel.dirtyForTest());
        EventQueue.invokeAndWait(() -> panel.followLinkForTest(link));
        assertEquals(List.of("b.md"), linked, "dirty links refuse to open");
    }

    @Test
    void escapeClosesFromTheRenderView() throws Exception {
        fs.add("/readme.md", "# Title\n".getBytes(StandardCharsets.UTF_8));
        mount("readme.md", true);
        await(() -> panel.stateForTest() == EditorPanel.State.SHOWN
                && panel.renderPage().ready(), "rendered");
        assertFalse(panel.dirtyForTest());
        EventQueue.invokeAndWait(() -> panel.fireKeyForTest("ESCAPE"));
        assertTrue(panel.closedForTest(), "escape on the page closes the editor");
    }

    @Test
    void aBinaryMarkdownFileIsRefused() throws Exception {
        byte[] blob = new byte[64];
        blob[10] = 0;
        fs.add("/blob.md", blob);
        mount("blob.md", true);
        await(() -> panel.stateForTest() == EditorPanel.State.REFUSED, "refused");
        assertFalse(panel.showingRender(), "no page ever stood in");
        assertTrue(panel.refuseReasonForTest().contains("text"),
                "the refusal says why: " + panel.refuseReasonForTest());
    }
}
