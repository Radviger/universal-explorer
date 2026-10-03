package dock.terminal;

import com.jediterm.core.Color;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import dock.kit.FontRegistry;
import dock.kit.ThemeManager;
import dock.sftp.SftpFs;
import java.awt.BorderLayout;
import java.awt.Font;
import java.io.IOException;
import javax.swing.JPanel;

/** An embedded shell for one session: JediTerm wired to a shell channel. */
public final class TerminalView extends JPanel {

    private final JediTermWidget term;
    private final com.jediterm.terminal.TtyConnector channel;

    public TerminalView(SftpFs fs) throws IOException {
        this(new ShellChannel(fs.session(), 120, 32));
    }

    /** Any tty connector — remote shell channels and local shells alike. */
    public TerminalView(com.jediterm.terminal.TtyConnector channel) {
        super(new BorderLayout());
        this.channel = channel;
        this.term = new JediTermWidget(120, 32, new DockSettings());
        term.setTtyConnector(channel);
        add(term, BorderLayout.CENTER);
        term.start();
        // The settings provider reads the current theme per paint; a nudge
        // repaints live when the app theme flips.
        ThemeManager.addListener(term::repaint);
    }

    public void close() {
        term.close();
        channel.close();
    }

    /** The visible screen as text, straight from the terminal's own text
     *  model — the screenshot harness scans it for de-anonymizing paths. */
    public String screenTextForTest() {
        return term.getTerminalTextBuffer().getScreenLines();
    }

    public boolean isConnected() {
        return channel.isConnected();
    }

    /**
     * Theme-aware terminal scheme: the panel re-queries the palette and the
     * default colors on every paint, so switching the app theme switches the
     * terminal with a simple repaint.
     */
    public static final class DockSettings extends DefaultSettingsProvider {

        @Override
        public Font getTerminalFont() {
            return FontRegistry.mono(14);
        }

        @Override
        public ColorPalette getTerminalColorPalette() {
            return dark() ? DockPalettes.DARK : DockPalettes.LIGHT;
        }

        /**
         * Seeds the terminal's StyleState — the style runs fall back to after
         * an SGR reset, and the filler for reverse-video runs. The stock
         * default is black-on-white, so a server banner ending in ESC[0m made
         * everything after it paint with an explicit white background.
         */
        @Override
        public TextStyle getDefaultStyle() {
            boolean dark = dark();
            return new TextStyle(
                    TerminalColor.color(new Color(dark ? DockPalettes.DARK_FG
                            : DockPalettes.LIGHT_FG)),
                    TerminalColor.color(new Color(dark ? DockPalettes.DARK_BG
                            : DockPalettes.LIGHT_BG)));
        }

        @Override
        public TextStyle getSelectionColor() {
            return new TextStyle(
                    TerminalColor.color(new Color(0xFFFFFF)),
                    TerminalColor.color(new Color(dark() ? 0x7AA2F7 : 0x2563EB)));
        }

        @Override
        public boolean useInverseSelectionColor() {
            return false;
        }

        private static boolean dark() {
            return ThemeManager.mode() == ThemeManager.Mode.DARK;
        }
    }

    /** ANSI 16-color schemes tuned to the app themes. */
    private static final class DockPalettes {

        // One-Dark-ish on the card background.
        static final int DARK_BG = 0x26292F;
        static final int DARK_FG = 0xD7DAE0;
        static final int LIGHT_BG = 0xFFFFFF;
        static final int LIGHT_FG = 0x282C34;

        static final ColorPalette DARK = palette(new int[][]{
                {0x3F4451, 0xE06C75, 0x98C379, 0xE5C07B, 0x61AFEF, 0xC678DD, 0x56B6C2, 0xABB2BF},
                {0x5C6370, 0xF28B92, 0xB1D39B, 0xF0D08A, 0x84C3F5, 0xD8A0F0, 0x8ACBDB, 0xD3DAE5},
        });

        static final ColorPalette LIGHT = palette(new int[][]{
                {0x383A42, 0xCC3B3B, 0x3E9B4F, 0x9A6B00, 0x2F6FD0, 0x9B45B3, 0x0E8F8F, 0x525561},
                {0x676A75, 0xE06A5E, 0x53B56B, 0xC08A2D, 0x5690E8, 0xB569CE, 0x3FB0B0, 0x757884},
        });

        private static ColorPalette palette(int[][] scheme) {
            return new ColorPalette() {
                @Override
                protected Color getBackgroundByColorIndex(int i) {
                    return colorAt(scheme, i);
                }

                @Override
                public Color getForegroundByColorIndex(int i) {
                    return colorAt(scheme, i);
                }
            };
        }

        private static Color colorAt(int[][] scheme, int i) {
            int[] row = i < 8 ? scheme[0] : scheme[1];
            int idx = i & 7;
            return new Color(row[idx]);
        }
    }
}
