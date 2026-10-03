package dock.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The open-dispatch predicate: which kinds Enter and double-click send to
 * the editor — the syntax table's languages plus the plain-text family
 * around them, never the binary kinds.
 */
class EditsTest {

    @Test
    void codeConfigAndLogFilesEdit() {
        for (String name : new String[]{
                "Main.java", "script.py", "app.js", "main.rs", "index.ts",
                "app.ini", "settings.conf", "service.cfg", "app.properties",
                "pyproject.toml", ".env", "docker-compose.yml", "data.json",
                "pom.xml", "sheet.csv", "notes.txt", "README.txt", "boot.log",
                "table.tsv", "hosts", "Makefile", "Dockerfile", ".gitignore",
                ".editorconfig", "CHANGELOG"}) {
            assertTrue(EditorPanel.edits(name), name + " opens the editor");
        }
    }

    @Test
    void binariesAndUnknownsDoNot() {
        for (String name : new String[]{
                "photo.png", "shot.jpg", "bundle.zip", "backup.tar.gz",
                "program.exe", "library.so", "font.ttf", "clip.mp4",
                "data.bin", "noextension"}) {
            assertFalse(EditorPanel.edits(name), name + " stays a fall-through");
        }
    }
}
