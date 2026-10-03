import dock.terminal.LocalShell;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Local shell tab contract: the machine's shell spawns, its banner decodes in
 * the console codepage (no replacement chars), and a command round-trips.
 */
class LocalShellTest {

    @Test
    void spawnsDecodesAndRoundTrips() throws Exception {
        LocalShell sh = LocalShell.spawn();
        try {
            // Banner: on Windows the localized cmd header must come through
            // the OEM codepage, not as replacement chars.
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1024];
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline && sb.indexOf("Microsoft") < 0) {
                if (sh.ready()) {
                    int n = sh.read(buf, 0, buf.length);
                    if (n > 0) sb.append(buf, 0, n);
                } else {
                    Thread.sleep(30);
                }
            }
            String banner = sb.toString();
            assertTrue(banner.contains("Microsoft"), "banner was not read: " + banner);
            assertTrue(!banner.contains("\uFFFD") && !banner.matches("(?s).*\\?{4,}.*"),
                    "localized banner garbled: " + banner);

            // Command round-trip, typed the way JediTerm sends it: one
            // keystroke per write, Enter as a bare CR. A piped cmd ignores
            // lone CRs, so the connector must translate line endings.
            for (String k : new String[] {"e", "c", "h", "o", " ", "d", "o", "c",
                    "k", "-", "l", "o", "c", "a", "l", "-", "4", "2", "\r"}) {
                sh.write(k);
            }
            StringBuilder echo = new StringBuilder();
            deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline
                    && echo.indexOf("dock-local-42") < 0) {
                if (sh.ready()) {
                    int n = sh.read(buf, 0, buf.length);
                    if (n > 0) echo.append(buf, 0, n);
                } else {
                    Thread.sleep(30);
                }
            }
            assertTrue(echo.indexOf("dock-local-42") >= 0,
                    "typed command did not execute: " + echo);
        } finally {
            sh.close();
        }
    }
}
