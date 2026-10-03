package dock.smb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * The SMB line's self-healing rule, without a server: a failure on a dead
 * line redials and retries once; a failure on a live line is genuine. Plus
 * the config pin for the regression that motivated all of this — a finite
 * soTimeout disconnects idle sessions.
 */
class SmbHealTest {

    @Test
    void failureOnADeadLineHealsAndRetriesOnce() throws Exception {
        int[] calls = {0};
        int[] heals = {0};
        boolean[] alive = {false};
        String out = SmbFs.selfHeal(
                () -> {
                    calls[0]++;
                    if (calls[0] == 1) {
                        // What smbj throws once its transport tore the line down.
                        throw new IllegalStateException("DiskShare has already been closed");
                    }
                    return "ok";
                },
                () -> alive[0],
                () -> { heals[0]++; alive[0] = true; });
        assertEquals("ok", out);
        assertEquals(2, calls[0]);
        assertEquals(1, heals[0]);
    }

    @Test
    void failureOnALiveLineIsGenuineAndPropagates() {
        int[] heals = {0};
        assertThrows(IllegalStateException.class, () ->
                SmbFs.selfHeal(
                        () -> { throw new IllegalStateException("STATUS_ACCESS_DENIED"); },
                        () -> true,
                        () -> heals[0]++));
        assertEquals(0, heals[0]);
    }

    @Test
    void unreachableServerSurfacesTheDialFailure() {
        IOException dial = new IOException("\\\\nas: Connection refused");
        IOException thrown = assertThrows(IOException.class, () ->
                SmbFs.selfHeal(
                        () -> { throw new IllegalStateException("DiskShare has already been closed"); },
                        () -> false,
                        () -> { throw dial; }));
        assertSame(dial, thrown);
    }

    @Test
    void genuineFailureAfterTheHealStillPropagates() {
        assertThrows(IllegalStateException.class, () ->
                SmbFs.selfHeal(
                        () -> { throw new IllegalStateException("still failing"); },
                        () -> false,
                        () -> { }));
    }

    @Test
    void idleReadsMustStayUntimed() {
        // smbj's async transport bounds every socket read by soTimeout —
        // including the idle wait for the next packet — so any finite value
        // disconnects sessions left idle that long. This pins the fix for
        // the "DiskShare has already been closed after ~a minute idle" bug.
        assertEquals(0, SmbSessions.config().getSoTimeout());
    }
}
