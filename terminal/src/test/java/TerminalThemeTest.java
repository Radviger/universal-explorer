import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.jediterm.terminal.TtyConnector;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Terminal theming contract: a server banner that ends with an SGR reset must
 * not leave the following text on a white background. The terminal's
 * StyleState is seeded from the settings' default style, whose stock value is
 * black-on-white — DockSettings must override it with the theme colors.
 */
class TerminalThemeTest {

    private static final int DARK_BG = 0x26292F;

    @BeforeAll
    static void boot() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
        dock.kit.ThemeManager.setMode(dock.kit.ThemeManager.Mode.DARK);
    }

    @Test
    void bannerWithResetKeepsThemeBackground() throws Exception {
        // Reverse-video banner line, reset, then plain text — the sequence a
        // login MOTD typically emits.
        String stream = "\u001b[7m  WELCOME TO THE MACHINE  \u001b[0m"
                + "plain text after the reset\r\n$ ";
        var widget = new com.jediterm.terminal.ui.JediTermWidget(80, 24,
                new dock.terminal.TerminalView.DockSettings());
        widget.setTtyConnector(new CannedTty(stream));
        widget.start();

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String line = widget.getTerminalTextBuffer().getLine(0).getText();
            if (line.contains("WELCOME") && line.contains("plain")) break;
            Thread.sleep(20);
        }
        // Line 0 holds the inverse banner AND the post-reset text; line 1
        // the prompt. The paint scan below is what actually pins the fix.
        assertTrue(widget.getTerminalTextBuffer().getLine(0).getText().contains("plain"),
                "banner was not consumed");

        BufferedImage[] shot = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            widget.setSize(600, 200);
            layoutAll(widget);
            var img = new BufferedImage(600, 200, BufferedImage.TYPE_INT_RGB);
            widget.paint(img.createGraphics());
            shot[0] = img;
        });

        int white = 0;
        int darkBg = 0;
        int total = 0;
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 600; x++) {
                int rgb = shot[0].getRGB(x, y) & 0xFFFFFF;
                total++;
                if (rgb == 0xFFFFFF) white++;
                if (rgb == DARK_BG) darkBg++;
            }
        }
        assertTrue(white == 0, "pure-white pixels leaked into the terminal: " + white);
        assertTrue(darkBg > total / 2, "theme background must dominate, got "
                + darkBg + "/" + total);
    }

    /** Top-down layout for off-screen components (validate is a no-op without a peer). */
    private static void layoutAll(java.awt.Component c) {
        c.doLayout();
        if (c instanceof java.awt.Container ct) {
            for (java.awt.Component child : ct.getComponents()) layoutAll(child);
        }
    }

    /** Serves a fixed byte stream; writes are discarded. */
    private static final class CannedTty implements TtyConnector {
        private final ByteArrayInputStream in;
        private final AtomicBoolean connected = new AtomicBoolean(true);

        CannedTty(String data) {
            this.in = new ByteArrayInputStream(
                    StandardCharsets.UTF_8.encode(data).array());
        }

        @Override public int read(char[] buf, int off, int len) throws IOException {
            if (in.available() == 0) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return 0;
            }
            byte[] bytes = new byte[len];
            int n = in.read(bytes, 0, len);
            String s = new String(bytes, 0, n, StandardCharsets.UTF_8);
            s.getChars(0, n, buf, off);
            return n;
        }

        @Override public void write(byte[] bytes) { }

        @Override public void write(String string) { }

        @Override public boolean isConnected() {
            return connected.get();
        }

        @Override public boolean ready() {
            return in.available() > 0;
        }

        @Override public int waitFor() {
            return 0;
        }

        @Override public String getName() {
            return "canned";
        }

        @Override public void resize(Dimension termSize) { }

        @Override public void resize(Dimension termSize, Dimension pixelSize) { }

        @Override public void close() {
            connected.set(false);
        }
    }
}
