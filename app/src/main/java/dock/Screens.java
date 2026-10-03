package dock;

import dock.kit.FontRegistry;
import dock.kit.Glyphs;
import dock.kit.ThemeManager;
import dock.core.fs.FileEntry;
import dock.sftp.SftpFs;
import dock.sftp.SshSessions;
import dock.sftp.DemoServer;
import dock.kit.CardPanel;
import dock.commander.FilePane;
import dock.ui.MainWindow;
import dock.commander.SessionView;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;

/**
 * Screenshot harness + programmatic verifier.
 *
 * Two modes: the empty-state window, and a live session connected to an
 * in-process demo SFTP server (no external host needed). Verification is
 * deterministic: runtime component-tree facts and pixel sampling against
 * the theme's own expected colors — never a vision model.
 */
public final class Screens {

    private static final List<Check> CHECKS = new ArrayList<>();
    private static Rectangle tileRect;
    private static Rectangle remoteTableRect;
    private static Rectangle terminalRect;
    private static Rectangle toolButtonRect;
    private static Rectangle firstTabRect;
    private static Rectangle pathFieldRect;
    private static Rectangle cardRect;
    private static Rectangle searchChromeRect;
    private static Rectangle rowRect;
    /** The fictional home the shots live in — see {@link #seedFakeHome()}. */
    private static Path fakeHome;
    private static String realHome;
    /** Everything a rendered screen must never say: the real home path,
     *  account name, and working directory, captured before the override.
     *  Never printed — a failing check names the surface, not the string. */
    private static final List<String> identityStrings = new ArrayList<>();

    private record Check(String name, boolean pass, String detail) {
        @Override public String toString() { return (pass ? "PASS " : "FAIL ") + name + " - " + detail; }
    }

    private Screens() {}

    public static void captureAll(Path dir, boolean withSession) throws Exception {
        captureAll(dir, withSession, false);
    }

    public static void captureAll(Path dir, boolean withSession, boolean terminalShot)
            throws Exception {
        captureAll(dir, withSession, terminalShot, false);
    }

    public static void captureAll(Path dir, boolean withSession, boolean terminalShot,
                                  boolean viewerShot) throws Exception {
        captureAll(dir, withSession, terminalShot, viewerShot, false);
    }

    public static void captureAll(Path dir, boolean withSession, boolean terminalShot,
                                  boolean viewerShot, boolean mdShot) throws Exception {
        captureAll(dir, withSession, terminalShot, viewerShot, mdShot, false);
    }

    public static void captureAll(Path dir, boolean withSession, boolean terminalShot,
                                  boolean viewerShot, boolean mdShot, boolean edShot)
            throws Exception {
        Files.createDirectories(dir);
        // Isolate + seed the config so the landing launcher shows a fixed set
        // of saved sessions (never the developer's real sessions.json, which
        // connectSite/touch would also mutate).
        Path config = Files.createTempDirectory("dock-screens-config");
        dock.core.config.AppPaths.override(config);
        long day = 86_400_000L;
        long now = System.currentTimeMillis();
        Files.writeString(config.resolve("sessions.json"), """
                [
                  {"name":"shot-nas","host":"nas.local","port":22,"user":"admin","keyPath":null,"lastUsed":%d},
                  {"name":"shot-hetzner","host":"hetzner.example.com","port":22,"user":"root","keyPath":null,"lastUsed":%d},
                  {"name":"shot-media","host":"media.example","port":2222,"user":"demo","keyPath":null,"lastUsed":0}
                ]""".formatted(now - 2 * 3_600_000L, now - 9 * day));
        // Shots must carry no trace of the machine they were taken on:
        // the local pane, the terminal prompt, and the transfer rows all
        // point into a seeded fictional home instead of the developer's.
        identityStrings.add(System.getProperty("user.home"));
        identityStrings.add(System.getProperty("user.name"));
        identityStrings.add(Path.of("").toAbsolutePath().toString());
        fakeHome = seedFakeHome();
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());
        System.setProperty("dock.demo.shell.cwd", fakeHome.toString());
        int failures = 0;
        try (DemoServer.Handle demo = withSession ? DemoServer.start() : null) {
            if (viewerShot && demo != null) seedViewerImage(demo.root());
            if (mdShot && demo != null) seedMarkdownDoc(demo.root());
            if (edShot && demo != null) seedEditorDoc(demo.root());
            AtomicReference<SftpFs> sessionFs = new AtomicReference<>();
            if (demo != null) {
                sessionFs.set(SshSessions.connect("demo", "127.0.0.1", demo.port(),
                        "demo".toCharArray(), null, null, AcceptAllServerKeyVerifier.INSTANCE));
            }
            for (ThemeManager.Mode m : ThemeManager.Mode.values()) {
                CHECKS.clear();
                tileRect = null;
                remoteTableRect = null;
                terminalRect = null;
                cardRect = null;
                searchChromeRect = null;
                rowRect = null;
                Path demoUpload = withSession && !viewerShot && !mdShot && !edShot
                        ? createDemoUploadFile() : null;
                CountDownLatch done = new CountDownLatch(1);
                BufferedImage[] out = new BufferedImage[1];
                // The viewer-state capture (viewerShot/mdShot only); the
                // written PNG shows the stand-in, the verified pixels show
                // the restored session.
                BufferedImage[] viewerCapture = new BufferedImage[1];
                SwingUtilities.invokeAndWait(() -> {
                    ThemeManager.setMode(m);
                    MainWindow w = new MainWindow();
                    w.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                    if (sessionFs.get() != null) w.openSession(new dock.sftp.SftpSession(sessionFs.get(), null), null);
                    if (terminalShot) w.openTerminalForScreenshots();
                    w.setLocation(24, 24);
                    w.setVisible(true);
                    // Let the background listings land before capturing.
                    Timer settle = new Timer(withSession ? 1500 : 500, e -> {
                        if (viewerShot) {
                            runViewerFlow(w, done, out, viewerCapture);
                            return;
                        }
                        if (mdShot) {
                            runMarkdownFlow(w, done, out, viewerCapture);
                            return;
                        }
                        if (edShot) {
                            runEditorFlow(w, done, out, viewerCapture, demo.root());
                            return;
                        }
                        collectStructuralChecks(w, withSession, terminalShot);
                        collectIdentityChecks(w, terminalShot);
                        try {
                            var img = new BufferedImage(w.getWidth(), w.getHeight(),
                                    BufferedImage.TYPE_INT_RGB);
                            var g2 = img.createGraphics();
                            w.paint(g2);
                            g2.dispose();
                            out[0] = img;
                        } catch (Exception ex) {
                            ex.printStackTrace(System.err);
                        }
                        if (withSession) {
                            // Popup capture must happen while the owning window
                            // is still alive and showing.
                            enqueueDemoUpload(demoUpload, sessionFs.get());
                            w.toggleTransfers();
                            Timer queueShot = new Timer(900, e2 -> {
                                captureTransfers(w, dir, m);
                                w.dispose();
                                done.countDown();
                            });
                            queueShot.setRepeats(false);
                            queueShot.start();
                        } else {
                            w.dispose();
                            done.countDown();
                        }
                    });
                    settle.setRepeats(false);
                    settle.start();
                });
                done.await();
                if (out[0] == null) throw new IllegalStateException("capture failed for " + m);

                verifyPixels(out[0], withSession, terminalShot);
                verifyGlyphCoverage();

                String suffix = (viewerShot ? "viewer-" : mdShot ? "md-"
                        : edShot ? "ed-" : withSession ? "session-" : "")
                        + m.name().toLowerCase(Locale.ROOT);
                Path file = dir.resolve("main-" + suffix + ".png");
                ImageIO.write((viewerShot || edShot) && viewerCapture[0] != null
                        ? viewerCapture[0] : out[0], "png", file.toFile());
                System.out.println("wrote " + file.toAbsolutePath());

                System.out.println("--- checks for " + m + (withSession ? " (session)" : "") + " ---");
                for (Check c : CHECKS) {
                    System.out.println("  " + c);
                    if (!c.pass()) failures++;
                }
            }
            if (sessionFs.get() != null) sessionFs.get().close();
        } finally {
            restoreRealHome();
        }
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * A believable, entirely fictional home for the shots: real rows for
     * the local pane, a clean path for the terminal prompt and transfer
     * rows. It sits in the machine's public profile so no username rides
     * along in any rendered path; where that is missing on Windows the
     * run fails rather than leak. (Off-Windows a temp dir carries none.)
     */
    private static Path seedFakeHome() throws Exception {
        Path parent = Path.of(System.getProperty("user.home")).getParent();
        Path base = parent == null ? null : parent.resolve("Public");
        if (base == null || !Files.isDirectory(base)) {
            if (System.getProperty("os.name", "").toLowerCase().contains("win"))
                throw new java.io.IOException("no public profile for a clean fake home");
            base = Files.createTempDirectory("ue-shot-home");
        }
        Path home = Files.createDirectories(base.resolve("demo"));
        long now = System.currentTimeMillis();
        for (String d : new String[] {"Desktop", "Documents", "Downloads", "Music",
                "Pictures", "Projects", "Videos"})
            Files.createDirectories(home.resolve(d));
        seed(home, "notes.txt", 512, now - 2 * 3_600_000L);
        seed(home, "budget-2026.xlsx", 48_233, now - 26 * 3_600_000L);
        seed(home, "homelab.md", 3_140, now - 5 * 86_400_000L);
        return home;
    }

    private static void seed(Path home, String name, int size, long mtime) throws Exception {
        Path p = home.resolve(name);
        byte[] body = new byte[size];
        new java.util.Random(size).nextBytes(body);
        Files.write(p, body);
        Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(mtime));
    }

    /** Back to the real home; the fictional one is swept away. */
    private static void restoreRealHome() {
        if (realHome != null) System.setProperty("user.home", realHome);
        System.clearProperty("dock.demo.shell.cwd");
        if (fakeHome == null) return;
        try (var walk = Files.walk(fakeHome)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {
            // a locked leftover under the public profile is harmless
        }
    }

    /**
     * The anti-leak gate: every text the window actually renders — label,
     * button, tab title, table cell, and the terminal's own text buffer —
     * is collected and scanned for the real account strings. A failure
     * names the kind of surface that leaked, never the string itself.
     */
    private static void collectIdentityChecks(MainWindow w, boolean terminalShot) {
        StringBuilder visible = new StringBuilder();
        collectText(w, visible);
        if (terminalShot) {
            var term = (dock.terminal.TerminalView) findComponent(w,
                    c -> c instanceof dock.terminal.TerminalView);
            if (term != null) visible.append(term.screenTextForTest());
        }
        String text = visible.toString().toLowerCase(java.util.Locale.ROOT);
        for (String banned : identityStrings) {
            if (banned == null || banned.isBlank()) continue;
            add("no-deanonymized-text",
                    !text.contains(banned.toLowerCase(java.util.Locale.ROOT)),
                    "a rendered " + (terminalShot && text.length() > 0
                            ? "surface (labels, cells, or terminal buffer)"
                            : "surface (labels or cells)")
                            + " carried a real account path or name");
        }
    }

    /** Gathers the text of every painted text component in the tree. */
    private static void collectText(java.awt.Component c, StringBuilder into) {
        if (c instanceof JLabel l) into.append(l.getText()).append('\n');
        else if (c instanceof javax.swing.AbstractButton b)
            into.append(b.getText()).append('\n');
        else if (c instanceof javax.swing.text.JTextComponent t)
            into.append(t.getText()).append('\n');
        else if (c instanceof JTabbedPane tabs)
            for (int i = 0; i < tabs.getTabCount(); i++)
                into.append(tabs.getTitleAt(i)).append('\n');
        else if (c instanceof javax.swing.JTable table) {
            for (int row = 0; row < table.getRowCount(); row++)
                for (int col = 0; col < table.getColumnCount(); col++) {
                    Object v = table.getValueAt(row, col);
                    if (v != null) into.append(v).append('\n');
                }
        }
        if (c instanceof java.awt.Container container)
            for (java.awt.Component child : container.getComponents())
                collectText(child, into);
    }

    /** A unique, non-trivial file so progress is visible and no conflict
     *  resolver fires. Lives in the fictional home so the transfer rows
     *  render a clean path. */
    private static Path createDemoUploadFile() throws Exception {
        Path src = fakeHome.resolve("site-backup.zip");
        byte[] chunk = new byte[1024 * 1024];
        java.util.Random rnd = new java.util.Random();
        try (var out = Files.newOutputStream(src)) {
            for (int i = 0; i < 12; i++) {
                rnd.nextBytes(chunk);
                out.write(chunk);
            }
        }
        return src;
    }

    /** The image the viewer variant opens remotely: solid archive-amber,
     *  a hue no UI chrome uses, so pixel checks can find it. */
    private static void seedViewerImage(Path demoRoot) throws Exception {
        var img = new BufferedImage(400, 280, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(new Color(0xE0AF68));
        g.fillRect(0, 0, 400, 280);
        g.dispose();
        ImageIO.write(img, "png", demoRoot.resolve("shot.png").toFile());
    }

    /**
     * Viewer variant: opens the seeded image in the remote pane, checks the
     * standing-in viewer, captures it, then escapes and verifies the pane
     * returned — {@code out[0]} carries the restored session for the
     * standard pixel checks, {@code viewerCapture[0]} the PNG.
     */
    private static void runViewerFlow(MainWindow w, CountDownLatch done,
                                      BufferedImage[] out, BufferedImage[] viewerCapture) {
        SessionView view = (SessionView) findComponent(w, c -> c instanceof SessionView);
        if (view == null) {
            add("viewer-open", false, "no session view found");
            w.dispose();
            done.countDown();
            return;
        }
        FilePane remote = view.remotePane();
        remote.selectEntry("shot.png");
        remote.fireTableActionForTest("ENTER");
        Timer decoded = new Timer(1200, e2 -> {
            try {
                dock.viewer.ImageViewerPanel viewer = (dock.viewer.ImageViewerPanel)
                        findComponent(w, c -> c instanceof dock.viewer.ImageViewerPanel);
                add("viewer-open", viewer != null,
                        viewer != null ? "the viewer stands in for the pane" : "no viewer");
                var shot = new BufferedImage(w.getWidth(), w.getHeight(),
                        BufferedImage.TYPE_INT_RGB);
                var g2 = shot.createGraphics();
                w.paint(g2);
                g2.dispose();
                viewerCapture[0] = shot;
                if (viewer != null) {
                    // Canvas is the middle child of the panel's border layout.
                    Component canvas = viewer.getComponent(1);
                    Rectangle r = SwingUtilities.convertRectangle(canvas,
                            new Rectangle(0, 0, canvas.getWidth(), canvas.getHeight()), w);
                    Color amber = new Color(0xE0AF68);
                    Color atImage = new Color(shot.getRGB(
                            r.x + r.width / 2, r.y + r.height / 2), true);
                    add("viewer-painted", close(atImage, amber, 22),
                            "canvas center " + hex(atImage) + " vs image " + hex(amber));
                    Color well = UIManager.getColor("Dock.viewerBackground");
                    Color atWell = new Color(shot.getRGB(r.x + r.width / 2, r.y + 6), true);
                    add("viewer-well-themed", well != null && close(atWell, well, 8),
                            "canvas top " + hex(atWell) + " vs well " + hex(well));
                    // The strip is a JPanel wrapping the mono label.
                    JLabel strip = (JLabel) findComponent(
                            (Container) viewer.getComponent(2), c -> c instanceof JLabel);
                    add("viewer-strip-painted",
                            strip.getText().contains("shot.png") && strip.getText().contains("png"),
                            "strip: " + strip.getText());
                    int buttons = countComponents(
                            (Container) viewer.getComponent(0), c -> c instanceof JButton);
                    add("viewer-controls", buttons == 8, "toolbar buttons=" + buttons);
                }
                // The escape hatch: the pane must come back whole.
                view.fireViewerKeyForTest(remote, "ESCAPE");
                add("viewer-restores-pane",
                        !view.showingViewer(remote) && remote.isDisplayable(),
                        "pane returned to the hierarchy");
                Timer back = new Timer(400, e3 -> {
                    try {
                        collectStructuralChecks(w, true, false);
                        var restored = new BufferedImage(w.getWidth(), w.getHeight(),
                                BufferedImage.TYPE_INT_RGB);
                        var g3 = restored.createGraphics();
                        w.paint(g3);
                        g3.dispose();
                        out[0] = restored;
                    } catch (Exception ex) {
                        ex.printStackTrace(System.err);
                    }
                    w.dispose();
                    done.countDown();
                });
                back.setRepeats(false);
                back.start();
            } catch (Exception ex) {
                ex.printStackTrace(System.err);
                w.dispose();
                done.countDown();
            }
        });
        decoded.setRepeats(false);
        decoded.start();
    }

    /** The markdown the reader variant opens remotely — every construct the
     *  renderer pins, over the demo SFTP server. */
    private static final String MD_DOC = """
            # Universal Explorer markdown shot

            A rendered paragraph with **bold**, *italics*, ~~gone~~ and `inline code`,
            plus a [link](https://universal-explorer.dev) that reads as one.

            A long line that measures the reader's word-wrap toggle: with wrap off it keeps
            its full natural width instead of folding, and the page grows the horizontal
            scrollbar that shift+wheel rolls the editor's way.

            - [x] seeded checkbox
            - [ ] unchecked sibling

            | column | value |
            | ------ | ----: |
            | one    | 1     |
            | two    | 2     |

            ```java
            int answer = 42;
            int more = 7;
            ```

            ![shot](mdshot.png)
            """;

    /** The extensionless repo doc the pane opens in the reader too. */
    private static final String LICENSE_DOC = """
            MIT License

            Copyright (c) 2026 Universal Explorer contributors

            Permission is hereby granted, free of charge, to any person
            obtaining a copy of this software.
            """;

    private static void seedMarkdownDoc(Path demoRoot) throws Exception {
        Files.writeString(demoRoot.resolve("mdshot.md"), MD_DOC);
        Files.writeString(demoRoot.resolve("LICENSE"), LICENSE_DOC);
        var img = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(new Color(0x7AA2F7));
        g.fillRect(0, 0, 320, 200);
        g.dispose();
        ImageIO.write(img, "png", demoRoot.resolve("mdshot.png").toFile());
    }

    /** The java source the editor variant opens and rewrites over the
     *  demo SFTP server. */
    private static final String EDIT_DOC = """
            package demo;

            public class Main {
                // the editor shot seeds this comment
                public static void main(String[] args) {
                    int answer = 42;
                    System.out.println("answer = " + answer);
                }
            }
            """;

    private static void seedEditorDoc(Path demoRoot) throws Exception {
        Files.writeString(demoRoot.resolve("Main.java"), EDIT_DOC);
    }

    /**
     * Editor variant: F4 the seeded source in the remote pane, check the
     * standing-in editor (source, language, gutter), type an edit, save it
     * through the real SFTP write path, then escape and verify the pane
     * returned — {@code out[0]} carries the restored session,
     * {@code viewerCapture[0]} the open editor PNG.
     */
    private static void runEditorFlow(MainWindow w, CountDownLatch done,
                                      BufferedImage[] out, BufferedImage[] viewerCapture,
                                      Path demoRoot) {
        // Re-seed every pass: the previous theme's save must not become
        // this pass's baseline, or an honest dirty sees nothing to edit.
        try {
            seedEditorDoc(demoRoot);
        } catch (Exception e) {
            add("ed-open", false, "could not seed the demo file");
            w.dispose();
            done.countDown();
            return;
        }
        SessionView view = (SessionView) findComponent(w, c -> c instanceof SessionView);
        if (view == null) {
            add("ed-open", false, "no session view found");
            w.dispose();
            done.countDown();
            return;
        }
        FilePane remote = view.remotePane();
        remote.selectEntry("Main.java");
        remote.fireTableActionForTest("F4");
        Timer loaded = new Timer(1500, e2 -> {
            try {
                dock.editor.EditorPanel editor = (dock.editor.EditorPanel)
                        findComponent(w, c -> c instanceof dock.editor.EditorPanel);
                add("ed-open", editor != null && view.showingEditor(remote),
                        editor != null ? "the editor stands in for the pane" : "no editor");
                javax.swing.text.JTextComponent area = editor == null ? null
                        : (javax.swing.text.JTextComponent) findComponent(editor,
                                c -> c instanceof javax.swing.text.JTextComponent
                                        && !(c instanceof javax.swing.JTextField));
                String text = area == null ? "" : area.getText();
                add("ed-loaded",
                        text.contains("public class Main")
                                && text.contains("System.out.println"),
                        "the seeded source arrived: " + text.length() + " chars");
                add("ed-language", editor != null && "text/java".equals(editor.language()),
                        "language: " + (editor == null ? "none" : editor.language()));
                add("ed-gutter", editor != null && editor.lineNumbersShown(),
                        "line numbers on");
                if (editor == null || area == null) {
                    w.dispose();
                    done.countDown();
                    return;
                }
                // Type the way a keystroke would, then save.
                area.setText(text.replace("answer = 42", "answer = 43"));
                add("ed-dirty", editor.isDirty(), "typing marks the file dirty");
                final Timer[] saved = new Timer[1];
                boolean[] fired = {false};
                int[] tries = {0};
                saved[0] = new Timer(100, e3 -> {
                    if (!fired[0]) {
                        // The gutter marks land on a short debounce after
                        // the keystroke; save only once they are on.
                        if (editor.changedLineCount() == 0 && ++tries[0] < 25) return;
                        add("ed-marks", editor.changedLineCount() > 0,
                                "changed lines wear gutter marks");
                        // The written PNG is this moment: the dirty editor,
                        // green bars standing in the gutter.
                        var shot = new BufferedImage(w.getWidth(), w.getHeight(),
                                BufferedImage.TYPE_INT_RGB);
                        var g2 = shot.createGraphics();
                        w.paint(g2);
                        g2.dispose();
                        viewerCapture[0] = shot;
                        fired[0] = true;
                        tries[0] = 0;
                        view.fireEditorKeyForTest(remote, "ctrl S");
                        return;
                    }
                    // The upload rides SFTP; escape only once it settles (a
                    // dirty escape would open the save dialog).
                    tries[0]++;
                    if (editor.isDirty() && tries[0] < 50) return;
                    saved[0].stop();
                    if (editor.isDirty()) {
                        add("ed-uploads", false, "the save never settled");
                        w.dispose();
                        done.countDown();
                        return;
                    }
                    String onDisk;
                    try {
                        onDisk = Files.readString(demoRoot.resolve("Main.java"));
                    } catch (Exception ex) {
                        onDisk = "";
                    }
                    add("ed-uploads", onDisk.contains("answer = 43"),
                            "Ctrl+S wrote through the SFTP write path");
                    add("ed-marks-clear", editor.changedLineCount() == 0,
                            "saving clears the gutter marks");
                    // The find bar: query, live marks, escape folds it first.
                    view.fireEditorKeyForTest(remote, "ctrl F");
                    javax.swing.JTextField field = (javax.swing.JTextField)
                            findComponent(editor, c -> c instanceof javax.swing.JTextField);
                    if (field != null) field.setText("answer");
                    add("ed-find-marks", field != null
                                    && editor.markedMatches() == 3
                                    && editor.findStatus().contains("3"),
                            "the query marks every match live");
                    view.fireEditorKeyForTest(remote, "ESCAPE");
                    add("ed-find-folds", view.showingEditor(remote)
                                    && editor.markedMatches() == 0,
                            "escape folds the bar before the editor");
                    view.fireEditorKeyForTest(remote, "ESCAPE");
                    add("ed-restores-pane",
                            !view.showingEditor(remote) && remote.isDisplayable(),
                            "pane returned to the hierarchy");
                    Timer back = new Timer(400, e4 -> {
                        try {
                            collectStructuralChecks(w, true, false);
                            var restored = new BufferedImage(w.getWidth(), w.getHeight(),
                                    BufferedImage.TYPE_INT_RGB);
                            var g3 = restored.createGraphics();
                            w.paint(g3);
                            g3.dispose();
                            out[0] = restored;
                        } catch (Exception ex) {
                            ex.printStackTrace(System.err);
                        }
                        w.dispose();
                        done.countDown();
                    });
                    back.setRepeats(false);
                    back.start();
                });
                saved[0].start();
            } catch (Exception ex) {
                ex.printStackTrace(System.err);
                w.dispose();
                done.countDown();
            }
        });
        loaded.setRepeats(false);
        loaded.start();
    }

    /**
     * Markdown variant: opens the seeded document in the remote pane — the
     * editor standing in on its rendered page — checks the render, captures
     * it, then escapes and verifies the pane returned — {@code out[0]}
     * carries the restored session for the standard pixel checks,
     * {@code viewerCapture[0]} the PNG.
     */
    private static void runMarkdownFlow(MainWindow w, CountDownLatch done,
                                        BufferedImage[] out, BufferedImage[] viewerCapture) {
        SessionView view = (SessionView) findComponent(w, c -> c instanceof SessionView);
        if (view == null) {
            add("md-open", false, "no session view found");
            w.dispose();
            done.countDown();
            return;
        }
        FilePane remote = view.remotePane();
        remote.selectEntry("mdshot.md");
        remote.fireTableActionForTest("ENTER");
        Timer rendered = new Timer(1500, e2 -> {
            try {
                dock.editor.EditorPanel editor = (dock.editor.EditorPanel)
                        findComponent(w, c -> c instanceof dock.editor.EditorPanel);
                add("md-open", editor != null && view.showingEditor(remote)
                                && editor.showingRender(),
                        editor != null ? "the editor stands in on its rendered page"
                                : "no editor");
                var shot = new BufferedImage(w.getWidth(), w.getHeight(),
                        BufferedImage.TYPE_INT_RGB);
                var g2 = shot.createGraphics();
                w.paint(g2);
                g2.dispose();
                viewerCapture[0] = shot;
                boolean pageUp = editor != null && editor.renderPage() != null
                        && editor.renderPage().ready();
                javax.swing.text.JTextComponent tp = editor == null || !pageUp ? null
                        : editor.renderPage().text();
                if (pageUp && tp != null) {
                    var doc = editor.renderPage().document();
                    String text = doc.getText(0, doc.getLength());
                    add("md-rendered",
                            text.contains("rendered paragraph") && !text.contains("```"),
                            "prose present, fences gone: " + text.length() + " chars");
                    var heading = doc.getCharacterElement(
                            text.indexOf("Universal Explorer markdown shot"))
                            .getAttributes();
                    var body = doc.getCharacterElement(text.indexOf("rendered paragraph"))
                            .getAttributes();
                    add("md-heading-sized",
                            javax.swing.text.StyleConstants.getFontSize(heading) >= 18
                                    && javax.swing.text.StyleConstants.getFontSize(body) == 13,
                            "heading "
                                    + javax.swing.text.StyleConstants.getFontSize(heading)
                                    + "px, body "
                                    + javax.swing.text.StyleConstants.getFontSize(body) + "px");
                    java.util.List<dock.markdown.CodeBlock> cards = new java.util.ArrayList<>();
                    for (javax.swing.text.Element e : leavesOf(doc))
                        if (javax.swing.text.StyleConstants.getComponent(e.getAttributes())
                                instanceof dock.markdown.CodeBlock cb)
                            cards.add(cb);
                    add("md-code-card", cards.size() == 1
                                    && "java".equals(cards.get(0).language())
                                    && cards.get(0).codeText().contains("int answer")
                                    && cards.get(0).lineCount() == 2
                                    && countComponents(cards.get(0),
                                            c -> c instanceof JButton) == 1
                                    && UIManager.getColor("Dock.mdCodeBackground")
                                            .equals(cards.get(0).fill()),
                            "one themed card: language header, numbered lines, copy button");
                    boolean hlKeyword = false, hlNumber = false;
                    String hlText = cards.get(0).codeText();
                    for (dock.syntax.SyntaxSpan s : cards.get(0).spans()) {
                        String run = hlText.substring(s.start(), s.end());
                        if (s.kind() == dock.syntax.SyntaxKind.KEYWORD
                                && run.contains("int")) hlKeyword = true;
                        if (s.kind() == dock.syntax.SyntaxKind.NUMBER
                                && run.contains("42")) hlNumber = true;
                    }
                    add("md-code-highlight", hlKeyword && hlNumber
                                    && cards.get(0).codePalette()
                                            .of(dock.syntax.SyntaxKind.KEYWORD)
                                            .equals(UIManager.getColor("Dock.codeKeyword")),
                            "java keywords and literals color from the theme token palette");
                    add("md-surface-themed",
                            UIManager.getColor("TextPane.background").equals(tp.getBackground()),
                            "reading surface follows the Laf text surface");
                    var page = editor.renderPage();
                    javax.swing.JScrollPane mdScroll = page.scroll();
                    add("md-fits-pane", mdScroll.getX() == 0
                                    && mdScroll.getWidth() == page.getWidth()
                                    && page.getWidth() == editor.getWidth()
                                    && tp.getBackground()
                                            .equals(mdScroll.getViewport().getBackground()),
                            "the page spans the editor; its padding paints as page, not gaps");
                    add("md-text-themed", javax.swing.text.StyleConstants.getForeground(body)
                                    .equals(UIManager.getColor("Label.foreground")),
                            "body text carries the theme text color");
                    boolean icon = false;
                    for (javax.swing.text.Element e : leavesOf(doc))
                        if (javax.swing.text.StyleConstants.getIcon(e.getAttributes()) != null)
                            icon = true;
                    add("md-image-inline", icon, "the seeded png embeds inline");
                    java.util.List<javax.swing.JCheckBox> tasks = new java.util.ArrayList<>();
                    for (javax.swing.text.Element e : leavesOf(doc))
                        if (javax.swing.text.StyleConstants.getComponent(e.getAttributes())
                                instanceof javax.swing.JCheckBox cb)
                            tasks.add(cb);
                    add("md-task-checkbox", tasks.size() == 2 && tasks.get(0).isSelected()
                            && !tasks.get(1).isSelected()
                            && tasks.stream().allMatch(cb -> !cb.isEnabled())
                            && !text.contains("[x]"),
                            "task items are the laf's own disabled checkboxes: "
                                    + tasks.size() + " boxes");
                    // The editor's toolbar: undo, redo, save, find, wrap, the
                    // render flip, close.
                    int buttons = countComponents(
                            (Container) ((Container) editor.getComponent(0)).getComponent(0),
                            c -> c instanceof JButton);
                    add("md-controls", buttons == 7, "toolbar buttons=" + buttons);
                    // The universal word-wrap toggle on the rendered page:
                    // off, the page keeps its natural width and the editor
                    // scrolls sideways for what doesn't fold; back on, it
                    // tracks the viewport again and the scrollbar folds away.
                    editor.toggleWrapForTest();
                    w.validate();
                    boolean unwrapped = !tp.getScrollableTracksViewportWidth()
                            && mdScroll.getHorizontalScrollBar().isVisible()
                            && tp.getPreferredSize().width
                                    > mdScroll.getViewport().getWidth();
                    editor.toggleWrapForTest();
                    w.validate();
                    add("md-nowrap", unwrapped
                                    && tp.getScrollableTracksViewportWidth()
                                    && !mdScroll.getHorizontalScrollBar().isVisible(),
                            "word wrap off unwraps and scrolls sideways; on folds");
                }
                // The escape hatch: the pane must come back whole.
                view.fireEditorKeyForTest(remote, "ESCAPE");
                add("md-restores-pane",
                        !view.showingEditor(remote) && remote.isDisplayable(),
                        "pane returned to the hierarchy");
                // The repo convention open: an extensionless LICENSE rides
                // the same ENTER dispatch a .md file does.
                remote.selectEntry("LICENSE");
                remote.fireTableActionForTest("ENTER");
                Timer repoDoc = new Timer(1200, e3 -> {
                    try {
                        dock.editor.EditorPanel license = (dock.editor.EditorPanel)
                                findComponent(w, c -> c instanceof dock.editor.EditorPanel);
                        String body = "";
                        if (license != null && license.showingRender()
                                && license.renderPage().ready()) {
                            body = license.renderPage().document()
                                    .getText(0, license.renderPage()
                                            .document().getLength());
                        }
                        add("md-repo-doc-opens", license != null
                                        && license.showingRender()
                                        && body.contains("Universal Explorer contributors"),
                                "extensionless LICENSE opens on the rendered page");
                        view.fireEditorKeyForTest(remote, "ESCAPE");
                        Timer back = new Timer(400, e4 -> {
                            try {
                                collectStructuralChecks(w, true, false);
                                var restored = new BufferedImage(w.getWidth(), w.getHeight(),
                                        BufferedImage.TYPE_INT_RGB);
                                var g3 = restored.createGraphics();
                                w.paint(g3);
                                g3.dispose();
                                out[0] = restored;
                            } catch (Exception ex) {
                                ex.printStackTrace(System.err);
                            }
                            w.dispose();
                            done.countDown();
                        });
                        back.setRepeats(false);
                        back.start();
                    } catch (Exception ex) {
                        ex.printStackTrace(System.err);
                        w.dispose();
                        done.countDown();
                    }
                });
                repoDoc.setRepeats(false);
                repoDoc.start();
            } catch (Exception ex) {
                ex.printStackTrace(System.err);
                w.dispose();
                done.countDown();
            }
        });
        rendered.setRepeats(false);
        rendered.start();
    }

    private static List<javax.swing.text.Element> leavesOf(
            javax.swing.text.StyledDocument doc) {
        List<javax.swing.text.Element> out = new ArrayList<>();
        collectLeaves(doc.getDefaultRootElement(), out);
        return out;
    }

    private static void collectLeaves(javax.swing.text.Element e,
                                      List<javax.swing.text.Element> out) {
        if (e.isLeaf()) out.add(e);
        for (int i = 0; i < e.getElementCount(); i++) collectLeaves(e.getElement(i), out);
    }

    private static void enqueueDemoUpload(Path src, dock.sftp.SftpFs remote) {
        if (src == null || remote == null) return;
        FileEntry entry = new FileEntry(src.getFileName().toString(), false, 0, 0, null, false);
        dock.core.transfer.TransferEngine.GLOBAL.enqueue(
                dock.core.fs.LocalFs.INSTANCE, src.getParent().toString(),
                List.of(entry), remote, "/uploads", false);
    }

    /** Captures and checks the transfers popup; must run while it is showing. */
    private static void captureTransfers(MainWindow w, Path dir, ThemeManager.Mode m) {
        var tp = w.transfersPopup();
        var win = tp.windowForTest();
        add("transfers-popup-showing", tp.popupVisibleForTest() && win.isShowing(),
                "popup visible and showing");
        add("transfers-job-rows", tp.rowCountForTest() >= 1, "rows=" + tp.rowCountForTest());
        // The popup is rows of progress bars, not a table — at least one
        // bar per listed job.
        add("transfers-row-bars", tp.barCountForTest() >= tp.rowCountForTest(),
                "bars=" + tp.barCountForTest() + " rows=" + tp.rowCountForTest());
        try {
            var image = new BufferedImage(Math.max(1, win.getWidth()),
                    Math.max(1, win.getHeight()), BufferedImage.TYPE_INT_RGB);
            var g2 = image.createGraphics();
            win.paint(g2);
            g2.dispose();
            Path q = dir.resolve("transfers-" + m.name().toLowerCase(Locale.ROOT) + ".png");
            ImageIO.write(image, "png", q.toFile());
            System.out.println("wrote " + q.toAbsolutePath());
        } catch (Exception ex) {
            add("transfers-capture", false, ex.toString());
        }
    }

    // ----- structural checks against the live component tree -----

    private static void collectStructuralChecks(MainWindow w, boolean sessionMode,
                                                boolean terminalShot) {
        boolean flatTitlePane = findComponent(w, c ->
                c.getClass().getName().contains("FlatTitlePane")) != null;
        add("flatlaf-title-pane", flatTitlePane,
                flatTitlePane ? "FlatLaf draws the title bar"
                        : "native Windows title bar is in use");

        if (!sessionMode) {
            CardPanel card = (CardPanel) findComponent(w, c -> c instanceof CardPanel);
            if (card == null) {
                add("card-found", false, "CardPanel not found in tree");
            } else {
                cardRect = SwingUtilities.convertRectangle(
                        card, new Rectangle(0, 0, card.getWidth(), card.getHeight()), w);
                add("card-found", cardRect.width >= 500,
                        "launcher card " + cardRect.width + "x" + cardRect.height);
            }

            var home = (dock.ui.SessionsHome) findComponent(
                    w, c -> c instanceof dock.ui.SessionsHome);
            if (home == null) {
                add("home-launcher", false, "SessionsHome not found in tree");
            } else {
                // The launcher column (hero + card) must center in the window.
                java.awt.Component column = home.getComponentCount() > 0
                        ? home.getComponent(0) : null;
                if (column != null && column.getWidth() > 0) {
                    Rectangle cb = column.getBounds();
                    double dx = Math.abs((home.getWidth() - cb.getWidth()) / 2.0 - cb.getX());
                    double dy = Math.abs((home.getHeight() - cb.getHeight()) / 2.0 - cb.getY());
                    add("home-content-centered", dx <= 1.0 && dy <= 1.0,
                            "offset from center dx=%.1f dy=%.1f".formatted(dx, dy));
                } else {
                    add("home-content-centered", false, "launcher column not laid out");
                }
                add("home-rows", home.rowCount() == 3 && home.visibleRowCount() == 3,
                        "rows=" + home.rowCount() + " visible=" + home.visibleRowCount());
                add("home-recency-first", home.rowCount() > 0
                        && "shot-nas".equals(home.siteForTest(0).name()),
                        "first row=" + (home.rowCount() > 0 ? home.siteForTest(0).name() : "-"));
                var chrome = home.searchChromeForTest();
                if (chrome != null && chrome.getWidth() > 0) {
                    searchChromeRect = SwingUtilities.convertRectangle(chrome,
                            new Rectangle(0, 0, chrome.getWidth(), chrome.getHeight()), w);
                }
                JButton more = home.moreButtonForTest(0);
                add("home-row-menu", more != null
                                && "borderless".equals(more.getClientProperty("JButton.buttonType"))
                                && more.getWidth() == 28,
                        "row ⋯ button " + (more == null ? "missing"
                                : more.getWidth() + "x" + more.getHeight()));
                // Regression guard for the overflowing row: the ⋯ button must
                // sit fully inside the card's right edge, not just exist.
                if (more != null && cardRect != null) {
                    Rectangle mb = SwingUtilities.convertRectangle(more,
                            new Rectangle(0, 0, more.getWidth(), more.getHeight()), w);
                    add("home-row-fits-card",
                            mb.x + mb.width <= cardRect.x + cardRect.width - 2,
                            "⋯ right edge %d vs card right %d".formatted(
                                    mb.x + mb.width, cardRect.x + cardRect.width));
                }
                home.setSearchTextForTest("shot-hetz");
                add("home-filter", home.visibleRowCount() == 1,
                        "visible after filter=" + home.visibleRowCount());
                home.setSearchTextForTest("");
                if (more != null) {
                    // more's parent is the east cluster; the row is one level up.
                    java.awt.Component rowComp = more.getParent() != null
                            ? more.getParent().getParent() : null;
                    if (rowComp != null) {
                        rowRect = SwingUtilities.convertRectangle(rowComp,
                                new Rectangle(0, 0, rowComp.getWidth(), rowComp.getHeight()), w);
                    }
                }
            }

            Component tile = findComponent(w, c -> c.getClass().getSimpleName().equals("IconTile"));
            if (tile != null) {
                tileRect = SwingUtilities.convertRectangle(
                        tile, new Rectangle(0, 0, tile.getWidth(), tile.getHeight()), w);
            }
        } else {
            add("session-tab-count", w.sessionCount() == (terminalShot ? 2 : 1),
                    "tabs=" + w.sessionCount());
            Icon closeIcon = (Icon) UIManager.get("TabbedPane.closeIcon");
            add("tab-close-icon-small", closeIcon != null && closeIcon.getIconWidth() <= 12,
                    closeIcon == null ? "no TabbedPane.closeIcon default"
                            : "close icon " + closeIcon.getIconWidth()
                                    + "x" + closeIcon.getIconHeight());
            int panes = countComponents(w, c -> c instanceof FilePane);
            add("filepane-count", panes == 2, "FilePanes=" + panes);
            SessionView view = (SessionView) findComponent(w, c -> c instanceof SessionView);
            if (view != null) {
                // Regression guard for the doubled-columns bug: JTable(model)
                // auto-creates a column set that manual setup must replace.
                add("local-table-columns", view.localPane().table().getColumnCount() == 3,
                        "columns=" + view.localPane().table().getColumnCount());
                add("remote-table-columns", view.remotePane().table().getColumnCount() == 4,
                        "columns=" + view.remotePane().table().getColumnCount());

                // Regression guards for the mismatched-toolbar bug: identical
                // geometry on every nav button and identical optical ink size
                // (thin chevrons normalized against dense glyphs).
                List<JButton> toolButtons = new ArrayList<>();
                collectToolButtons(view.localPane(), toolButtons);
                collectToolButtons(view.remotePane(), toolButtons);
                boolean uniformButtons = !toolButtons.isEmpty()
                        && toolButtons.stream().allMatch(b ->
                                b.getWidth() == toolButtons.get(0).getWidth()
                                && b.getHeight() == toolButtons.get(0).getHeight());
                add("toolbar-buttons-uniform", uniformButtons, toolButtons.size()
                        + " buttons, first " + (toolButtons.isEmpty() ? "-"
                                : toolButtons.get(0).getWidth() + "x"
                                        + toolButtons.get(0).getHeight()));
                int inkLo = Integer.MAX_VALUE;
                int inkHi = 0;
                for (JButton b : toolButtons) {
                    int d = inkMaxDim(b.getIcon());
                    inkLo = Math.min(inkLo, d);
                    inkHi = Math.max(inkHi, d);
                }
                add("toolbar-icons-uniform", !toolButtons.isEmpty() && inkHi - inkLo <= 1,
                        "ink max-dim " + (toolButtons.isEmpty() ? "-" : inkLo + "-" + inkHi)
                                + "px across " + toolButtons.size() + " icons");
                if (!toolButtons.isEmpty()) {
                    // Sample an ENABLED button (Back is disabled without
                    // history and a disabled button paints nothing at all).
                    JButton first = toolButtons.stream()
                            .filter(java.awt.Component::isEnabled).findFirst()
                            .orElse(toolButtons.get(0));
                    Color hoverBg = UIManager.getColor("Button.toolbar.hoverBackground");
                    add("toolbar-hover-key-loaded",
                            hoverBg != null && hoverBg.getAlpha() > 0 && hoverBg.getAlpha() < 255,
                            "Button.toolbar.hoverBackground = " + hoverBg
                                    + " alpha=" + (hoverBg == null ? "-" : hoverBg.getAlpha()));
                    checkGhostPaint(first);
                    toolButtonRect = SwingUtilities.convertRectangle(
                            first, new Rectangle(0, 0, first.getWidth(), first.getHeight()), w);
                }
                var localBar = (dock.commander.PathBar) findComponent(view.localPane(),
                        c -> c instanceof dock.commander.PathBar);
                add("pathbar-height",
                        localBar != null && localBar.getHeight() == 30,
                        "path bar " + (localBar == null ? "missing"
                                : localBar.getHeight() + "px tall"));
                int expected = (int) java.util.Arrays.stream(
                                System.getProperty("user.home").split("[\\\\/]"))
                        .filter(s -> !s.isEmpty()).count();
                // At user.home the bar legitimately shows either shape: the
                // raw chain (C: › Users › name) or, where the shell resolves
                // known folders, one collapsed icon head for the whole home.
                add("pathbar-pathlet-count",
                        localBar != null && (localBar.pathletCount() == expected
                                || (localBar.pathletCount() == 1
                                        && localBar.pathletIconForTest(0) != null)),
                        "local pathlets=" + (localBar == null ? "-" : localBar.pathletCount())
                                + " expected=" + expected + " (or one home head)");
                var remoteBar = (dock.commander.PathBar) findComponent(view.remotePane(),
                        c -> c instanceof dock.commander.PathBar);
                add("remote-pathbar-pathlets",
                        remoteBar != null && remoteBar.pathletCount() >= 1,
                        "remote pathlets=" + (remoteBar == null ? "-"
                                : remoteBar.pathletCount()));
                if (localBar != null) {
                    pathFieldRect = SwingUtilities.convertRectangle(localBar,
                            new Rectangle(0, 0, localBar.getWidth(), localBar.getHeight()), w);
                }
                JTabbedPane pane = (JTabbedPane) findComponent(w, c -> c instanceof JTabbedPane);
                if (pane != null && pane.getTabCount() > 0) {
                    firstTabRect = SwingUtilities.convertRectangle(
                            pane, pane.getUI().getTabBounds(pane, 0), w);
                }
            }
            int remoteRows = view == null ? -1 : view.remotePane().rowCount();
            add("remote-listing-loaded", remoteRows > 0,
                    "remote table rows=" + (remoteRows < 0 ? "no session view" : remoteRows));
            int localRows = view == null ? -1 : view.localPane().rowCount();
            add("local-listing-loaded", localRows > 0,
                    "local table rows=" + (localRows < 0 ? "no session view" : localRows));

            // Regression guard for the invisible-tab-pane bug: components can
            // exist in the tree with data in their models yet paint nothing.
            // With a terminal tab selected, the session tab is hidden by
            // design, so only require showing-ness when it is the active tab.
            boolean panesVisible = view != null
                    && view.localPane().isShowing() && view.localPane().getWidth() > 100
                    && view.localPane().getHeight() > 100
                    && view.remotePane().isShowing() && view.remotePane().getWidth() > 100
                    && view.remotePane().getHeight() > 100;
            if (terminalShot) {
                add("session-panes-visible", view != null && view.localPane().getWidth() > 100,
                        "session tab hidden behind the selected terminal tab (by design)");
            } else {
                add("session-panes-visible", panesVisible,
                        panesVisible
                                ? "both panes showing, local %dx%d remote %dx%d".formatted(
                                        view.localPane().getWidth(), view.localPane().getHeight(),
                                        view.remotePane().getWidth(), view.remotePane().getHeight())
                                : "panes not showing or zero-sized");
            }

            double lw = view.localPane().getWidth();
            double rw = view.remotePane().getWidth();
            add("split-balanced", Math.abs(lw - rw) / Math.max(1.0, Math.max(lw, rw)) < 0.1,
                    "local %.0fpx vs remote %.0fpx".formatted(lw, rw));

            if (terminalShot) {
                Component term = findComponent(w, c ->
                        c.getClass().getName().contains("JediTermWidget")
                                || c.getClass().getName().contains("TerminalPanel"));
                add("terminal-widget-visible",
                        term != null && term.isShowing() && term.getWidth() > 100,
                        term == null ? "no JediTerm component found"
                                : "terminal %dx%d showing=%s".formatted(
                                        term.getWidth(), term.getHeight(), term.isShowing()));
                if (term != null) {
                    terminalRect = SwingUtilities.convertRectangle(
                            term, new Rectangle(0, 0, term.getWidth(), term.getHeight()), w);
                }
                // Regression guard for the visible-resize bug: resizing used
                // to write a literal "stty rows R cols C" into the shell,
                // which echoed it into the terminal as text.
                String junkLine = dock.terminal.TerminalDiagnostics.resizeJunkLine(w);
                if (junkLine != null) {
                    add("terminal-no-resize-junk", false,
                            "resize echoed into the terminal buffer");
                } else {
                    add("terminal-no-resize-junk", true, "no resize text in the buffer");
                }
                // Regression guard for the console-codepage bug: a local
                // cmd banner decoded as UTF-8 turns Cyrillic into runs of
                // question marks / replacement chars.
                boolean garbled = dock.terminal.TerminalDiagnostics.bannerGarbled(w);
                add("terminal-text-decoded", !garbled,
                        garbled ? "replacement chars or ?-runs in the first lines"
                                : "banner text decoded in the console codepage");
            }

            // The remote table header must actually be painted in the capture.
            Rectangle remoteBounds = SwingUtilities.convertRectangle(
                    view.remotePane(), new Rectangle(0, 0, view.remotePane().getWidth(),
                            view.remotePane().getHeight()), w);
            remoteTableRect = remoteBounds;
        }

        int menus = w.getJMenuBar() != null ? w.getJMenuBar().getMenuCount() : 0;
        add("menu-count", menus == 3, "menus=" + menus);
    }

    private static Component findComponent(Container root, java.util.function.Predicate<Component> p) {
        for (int i = 0; i < root.getComponentCount(); i++) {
            Component c = root.getComponent(i);
            if (p.test(c)) return c;
            if (c instanceof Container ct) {
                Component r = findComponent(ct, p);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static int countComponents(Container root, java.util.function.Predicate<Component> p) {
        int n = 0;
        for (int i = 0; i < root.getComponentCount(); i++) {
            Component c = root.getComponent(i);
            if (p.test(c)) n++;
            if (c instanceof Container ct) n += countComponents(ct, p);
        }
        return n;
    }

    /** Icon-only borderless buttons = the file-pane navigation toolbar. */
    private static void collectToolButtons(Container root, List<JButton> out) {
        for (int i = 0; i < root.getComponentCount(); i++) {
            Component c = root.getComponent(i);
            if (c instanceof JButton b && b.getText().isEmpty()
                    && "borderless".equals(b.getClientProperty("JButton.buttonType"))
                    // PathBar pathlets are also icon-only borderless buttons
                    && SwingUtilities.getAncestorOfClass(dock.commander.PathBar.class, b) == null) {
                out.add(b);
            }
            if (c instanceof Container ct) collectToolButtons(ct, out);
        }
    }

    /** Largest non-transparent extent of a rendered icon's ink. */
    private static int inkMaxDim(Icon icon) {        BufferedImage img = new BufferedImage(Math.max(1, icon.getIconWidth()),
                Math.max(1, icon.getIconHeight()), BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        try {
            icon.paintIcon(null, g, 0, 0);
        } finally {
            g.dispose();
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) != 0) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        return maxX < 0 ? 0 : Math.max(maxX - minX + 1, maxY - minY + 1);
    }

    /**
     * Paints the button standalone, at rest and with the model rolled over.
     * At rest nothing may paint in the 3px edge band (no border ring, no
     * fill); on hover the state fill must appear there. Painted in
     * isolation this catches 1px artifacts a window sampler can miss.
     */
    private static void checkGhostPaint(JButton b) {
        int w = b.getWidth(), h = b.getHeight();
        if (w < 10 || h < 10) {
            add("toolbutton-ghost-paint", false, "button not laid out");
            return;
        }
        String diag = "";
        try {
            var f = Class.forName("com.formdev.flatlaf.ui.FlatButtonUI")
                    .getDeclaredField("toolbarHoverBackground");
            f.setAccessible(true);
            diag = " ui=" + b.getUI().getClass().getSimpleName()
                    + " toolbarHover=" + f.get(b.getUI());
        } catch (Exception ignored) {
            diag = " (reflection failed)";
        }
        BufferedImage rest = paintButton(b, false);
        BufferedImage hover = paintButton(b, true);
        int band = 3;
        int restInk = 0, hoverInk = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (x >= band && y >= band && x < w - band && y < h - band) continue;
                if ((rest.getRGB(x, y) >>> 24) != 0) restInk++;
                if ((hover.getRGB(x, y) >>> 24) != 0) hoverInk++;
            }
        }
        add("toolbutton-ghost-paint", restInk == 0 && hoverInk > 60,
                "edge-band ink: rest=%d hover=%d%s".formatted(restInk, hoverInk, diag));
    }

    private static BufferedImage paintButton(JButton b, boolean rollover) {
        BufferedImage img = new BufferedImage(Math.max(1, b.getWidth()),
                Math.max(1, b.getHeight()), BufferedImage.TYPE_INT_ARGB);
        var model = b.getModel();
        boolean was = model.isRollover();
        model.setRollover(rollover);
        var g = img.createGraphics();
        try {
            b.paint(g);
        } finally {
            g.dispose();
        }
        model.setRollover(was);
        b.repaint();
        return img;
    }

    // ----- pixel checks against the captured image -----

    private static void verifyPixels(BufferedImage img, boolean sessionMode) {
        verifyPixels(img, sessionMode, false);
    }

    private static void verifyPixels(BufferedImage img, boolean sessionMode, boolean terminalShot) {
        Color panelBg = uiColor("Panel.background");
        Color cardBg = uiColor("Dock.cardBackground");
        Color accent = uiColor("Dock.accent");

        int w = img.getWidth(), h = img.getHeight();

        if (!sessionMode) {
            Color margin = new Color(img.getRGB((int) (w * 0.08), h / 2), true);
            add("window-background", close(margin, panelBg, 18),
                    "sampled #%06X expected #%06X".formatted(margin.getRGB() & 0xFFFFFF,
                            panelBg.getRGB() & 0xFFFFFF));
        }

        if (!sessionMode && cardRect != null) {
            // Card top border must actually paint: sample the 1px stroke row
            // itself (the fill row below can sit within tolerance of the
            // light theme's window background).
            Color edge = new Color(img.getRGB(cardRect.x + cardRect.width / 2,
                    Math.max(0, cardRect.y)), true);
            add("card-found-in-capture", dist(edge, panelBg) > 8,
                    "card top edge #%06X vs window #%06X".formatted(
                            edge.getRGB() & 0xFFFFFF, panelBg.getRGB() & 0xFFFFFF));
            // The search well now covers the card top edge to edge, so the
            // bare card surface is sampled in the footer's empty middle
            // (between the hint and the buttons).
            Color inside = new Color(img.getRGB(cardRect.x + cardRect.width / 2,
                    cardRect.y + cardRect.height - 6), true);
            add("card-background", close(inside, cardBg, 18),
                    "sampled #%06X expected #%06X".formatted(inside.getRGB() & 0xFFFFFF,
                            cardBg.getRGB() & 0xFFFFFF));
        }

        // Launcher search field: recessed tint well, distinct from the card.
        if (!sessionMode && searchChromeRect != null) {
            Rectangle sc = searchChromeRect;
            Color tint = uiColor("Dock.tileBackground");
            boolean tinted = true;
            for (double fx : new double[] {0.3, 0.5, 0.7, 0.9}) {
                Color c = new Color(img.getRGB(sc.x + (int) (sc.width * fx), sc.y + 2), true);
                tinted &= close(c, tint, 18);
            }
            add("home-search-tinted", tinted && dist(tint, cardBg) > 6,
                    "top-row tint match=%s tint #%06X card #%06X".formatted(
                            tinted, tint.getRGB() & 0xFFFFFF, cardBg.getRGB() & 0xFFFFFF));
        }

        // Launcher rows: at rest the row area equals the card surface (no
        // borders/rings), yet the row's text must render.
        if (!sessionMode && rowRect != null) {
            Rectangle rr = rowRect;
            int[][] probes = {
                    {rr.x + rr.width / 2, rr.y + 2},
                    {rr.x + rr.width / 2, rr.y + rr.height - 3},
                    {rr.x + 2, rr.y + rr.height / 2}};
            int worst = 0;
            for (int[] p : probes) {
                worst = Math.max(worst, dist(new Color(img.getRGB(p[0], p[1]), true), cardBg));
            }
            add("home-row-ghost-rest", worst <= 8,
                    "worst channel delta vs card = %d".formatted(worst));
            int ink = 0;
            for (int y = rr.y + 2; y < rr.y + rr.height - 2; y++) {
                for (int x = rr.x + 2; x < rr.x + rr.width - 2; x++) {
                    if (dist(new Color(img.getRGB(x, y), true), cardBg) > 60) ink++;
                }
            }
            add("home-row-content-visible", ink > 30, "row ink pixels=" + ink);
        }

        boolean accentFound = false;
        outer:
        for (int y = 0; y < h; y += 2) {
            for (int xx = 0; xx < w; xx += 2) {
                if (close(new Color(img.getRGB(xx, y), true), accent, 30)) { accentFound = true; break outer; }
            }
        }
        add("accent-visible", accentFound, "accent #%06X present in capture".formatted(accent.getRGB() & 0xFFFFFF));

        // Navbar ghost buttons: at rest every sampled point inside a nav
        // button must equal the bar background just outside it — no fill,
        // no border ring.
        if (sessionMode && !terminalShot && toolButtonRect != null) {
            Rectangle tb = toolButtonRect;
            Color ref = new Color(img.getRGB(Math.max(0, tb.x - 5), tb.y + tb.height / 2), true);
            int cx = tb.x + tb.width / 2;
            int cy = tb.y + tb.height / 2;
            // Exact-edge pixels catch a 1px border ring; inner points catch
            // any rest fill. All must equal the bar background.
            int[][] probes = {
                    {cx, tb.y}, {cx, tb.y + tb.height - 1},
                    {tb.x, cy}, {tb.x + tb.width - 1, cy},
                    {cx, tb.y + 4}, {cx, tb.y + tb.height - 5},
                    {tb.x + 4, cy}, {tb.x + tb.width - 5, cy}};
            int worst = 0;
            for (int[] p : probes) {
                worst = Math.max(worst, dist(new Color(img.getRGB(p[0], p[1]), true), ref));
            }
            add("toolbar-button-ghost-rest", worst <= 8,
                    "worst channel delta vs bar = %d (ref #%06X)".formatted(
                            worst, ref.getRGB() & 0xFFFFFF));
        }

        // Path bar: tinted fill (distinct from the pane background) with no
        // outline. Pathlets run through the middle, so sample the top fill
        // row at several x positions instead of the center.
        if (sessionMode && !terminalShot && pathFieldRect != null) {
            Rectangle pf = pathFieldRect;
            Color tint = uiColor("Dock.fieldBackground");
            Color paneBg = uiColor("Panel.background");
            boolean tinted = true;
            for (double fx : new double[] {0.2, 0.35, 0.5, 0.65, 0.8}) {
                Color c = new Color(img.getRGB(pf.x + (int) (pf.width * fx), pf.y + 2), true);
                tinted &= close(c, tint, 18);
            }
            boolean distinct = dist(tint, paneBg) > 6;
            add("pathbar-tinted", tinted && distinct,
                    "top-row tint match=%s tint #%06X pane #%06X".formatted(
                            tinted, tint.getRGB() & 0xFFFFFF, paneBg.getRGB() & 0xFFFFFF));
            // Breadcrumb content (pathlets, chevrons) must render: count
            // ink pixels across the bar that differ from the tint.
            int textInk = 0;
            for (int y = pf.y + 3; y < pf.y + pf.height - 3; y++) {
                for (int x = pf.x + 3; x < pf.x + pf.width - 3; x++) {
                    if (dist(new Color(img.getRGB(x, y), true), tint) > 60) textInk++;
                }
            }
            add("pathbar-content-visible", textInk > 40, "ink pixels=" + textInk);
        }

        // The tab close icon must actually paint: a color supplier that
        // resolves to null NPEs inside FlatLaf's tab renderer and the mark
        // silently vanishes. Scan the right end of the first tab (skipping
        // edge lines and the selected-tab underline) for icon ink.
        if (sessionMode && !terminalShot && firstTabRect != null) {
            int sx = firstTabRect.x + firstTabRect.width - 30;
            int sy = firstTabRect.y + 3;
            int sh = Math.max(1, firstTabRect.height - 6);
            java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
            for (int y = sy; y < sy + sh; y++) {
                for (int x = sx; x < sx + 27; x++) {
                    counts.merge(img.getRGB(x, y) & 0xFFFFFF, 1, Integer::sum);
                }
            }
            int bgRgb = counts.entrySet().stream()
                    .max(java.util.Map.Entry.comparingByValue())
                    .map(java.util.Map.Entry::getKey).orElse(0);
            Color bg = new Color(bgRgb);
            int ink = 0;
            for (int y = sy; y < sy + sh; y++) {
                for (int x = sx; x < sx + 27; x++) {
                    if (dist(new Color(img.getRGB(x, y), true), bg) > 40) ink++;
                }
            }
            add("tab-close-painted", ink >= 6, "close-mark ink pixels=" + ink);
        }

        // Session mode: the remote pane must paint table content. Scan the
        // pane's center column starting below its toolbar (~38px):
        // the table header must appear before any row body pixels.
        // Terminal theming: the terminal area must render predominantly in the
        // current theme's background, not the other theme's.
        if (terminalShot && terminalRect != null) {
            Color want = new Color(ThemeManager.mode() == ThemeManager.Mode.DARK
                    ? 0x26292F : 0xFFFFFF);
            Color other = new Color(ThemeManager.mode() == ThemeManager.Mode.DARK
                    ? 0xFFFFFF : 0x26292F);
            int wantHits = 0;
            int otherHits = 0;
            for (int y = terminalRect.y; y < terminalRect.y + terminalRect.height; y += 3) {
                for (int x = terminalRect.x; x < terminalRect.x + terminalRect.width; x += 3) {
                    Color c = new Color(img.getRGB(x, y), true);
                    if (close(c, want, 18)) wantHits++;
                    else if (close(c, other, 18)) otherHits++;
                }
            }
            add("terminal-themed", wantHits > otherHits * 2 && wantHits > 100,
                    "current-theme pixels=%d other-theme=%d".formatted(wantHits, otherHits));
        }

        if (sessionMode && !terminalShot && remoteTableRect != null) {
            Color headerBg = uiColor("TableHeader.background");
            Color tableBg = uiColor("Table.background");
            Color altBg = uiColor("Table.alternateRowColor");
            int hx = remoteTableRect.x + remoteTableRect.width / 2;
            boolean headerPainted = false;
            for (int y = remoteTableRect.y + 44; y < remoteTableRect.y + remoteTableRect.height; y++) {
                Color c = new Color(img.getRGB(hx, y), true);
                if (close(c, headerBg, 22)) { headerPainted = true; break; }
                if (close(c, tableBg, 22) || (altBg != null && close(c, altBg, 22))) break;
            }
            add("remote-table-painted", headerPainted,
                    headerPainted ? "table header painted in capture"
                            : "no table header pixels found in remote pane area");
        }

        // The glyph inside the hero tile must be optically centered (empty mode only).
        if (!sessionMode && tileRect != null) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
            for (int y = tileRect.y; y < tileRect.y + tileRect.height; y++) {
                for (int xx = tileRect.x; xx < tileRect.x + tileRect.width; xx++) {
                    if (close(new Color(img.getRGB(xx, y), true), accent, 40)) {
                        if (xx < minX) minX = xx;
                        if (xx > maxX) maxX = xx;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
            if (maxX < 0) {
                add("tile-glyph-centered", false, "no accent ink found in tile");
            } else {
                double inkCx = (minX + maxX) / 2.0, inkCy = (minY + maxY) / 2.0;
                double tileCx = tileRect.x + tileRect.width / 2.0;
                double tileCy = tileRect.y + tileRect.height / 2.0;
                add("tile-glyph-centered",
                        Math.abs(inkCx - tileCx) <= 1.5 && Math.abs(inkCy - tileCy) <= 1.5,
                        "ink center (%.1f, %.1f) vs tile center (%.1f, %.1f), ink %dx%d"
                                .formatted(inkCx, inkCy, tileCx, tileCy, maxX - minX + 1, maxY - minY + 1));
            }
        }
    }

    // ----- font coverage: every catalogue glyph must exist in Symbols Nerd Font -----

    private static void verifyGlyphCoverage() {
        var font = FontRegistry.symbol(20f);
        List<String> missing = new ArrayList<>();
        for (Field f : Glyphs.class.getDeclaredFields()) {
            if (f.getType() != String.class || !java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            try {
                String glyph = (String) f.get(null);
                // Code points, not chars: a supplementary-plane glyph
                // reaches source as a surrogate pair, and each half
                // alone can never display.
                glyph.codePoints().forEach(cp -> {
                    if (!font.canDisplay(cp)) missing.add(f.getName() + "=U+%04X".formatted(cp));
                });
            } catch (IllegalAccessException ignored) {}
        }
        add("glyph-coverage", missing.isEmpty(),
                missing.isEmpty() ? "all catalogue glyphs render" : "missing: " + missing);
    }

    // ----- helpers -----

    private static Color uiColor(String key) {
        Color c = javax.swing.UIManager.getColor(key);
        return c != null ? c : Color.MAGENTA;
    }

    private static int dist(Color a, Color b) {
        int dr = a.getRed() - b.getRed(), dg = a.getGreen() - b.getGreen(), db = a.getBlue() - b.getBlue();
        return (int) Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private static boolean close(Color a, Color b, int tol) { return dist(a, b) <= tol; }

    private static String hex(Color c) {
        return c == null ? "null" : String.format("#%06x", c.getRGB() & 0xFFFFFF);
    }

    private static void add(String name, boolean pass, String detail) {
        CHECKS.add(new Check(name, pass, detail));
    }
}
