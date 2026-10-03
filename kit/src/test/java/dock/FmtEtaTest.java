package dock;

import dock.kit.Fmt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** ETA compaction for transfer rows: bare seconds, then m+ss, then h+mm. */
class FmtEtaTest {

    @Test
    void underAMinuteIsBareSeconds() {
        assertEquals("0s", Fmt.eta(0));
        assertEquals("8s", Fmt.eta(8));
        assertEquals("59s", Fmt.eta(59));
    }

    @Test
    void minutesPadTheSeconds() {
        assertEquals("1m 00s", Fmt.eta(60));
        assertEquals("1m 12s", Fmt.eta(72));
        assertEquals("59m 59s", Fmt.eta(59 * 60 + 59));
    }

    @Test
    void hoursPadTheMinutes() {
        assertEquals("1h 00m", Fmt.eta(3600));
        assertEquals("1h 04m", Fmt.eta(64 * 60));
        assertEquals("3h 27m", Fmt.eta(3 * 3600 + 27 * 60));
    }

    @Test
    void negativeClampsToZero() {
        assertEquals("0s", Fmt.eta(-5));
    }
}
