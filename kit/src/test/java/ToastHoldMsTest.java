import dock.kit.Toast;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Reading time for toasts scales with length and caps at 10s. */
class ToastHoldMsTest {

    @Test
    void toastReadingTimeScalesAndCaps() {
        assertEquals(2_200, Toast.holdMs("Reconnected."));
        assertEquals(7_700, Toast.holdMs("x".repeat(140)),
                "55ms per character past the first 40");
        assertEquals(10_000, Toast.holdMs("x".repeat(400)), "capped at 10s");
    }
}
