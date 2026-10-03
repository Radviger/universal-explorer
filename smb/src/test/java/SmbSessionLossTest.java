import com.hierynomus.smbj.event.ConnectionClosed;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loss filter: ConnectionClosed events carry only host+port and one
 * event bus serves every SMB session in the process, so an event reports
 * the loss of <em>this</em> session only when our own line is down. This
 * is what keeps the previous connection's close (after a reconnect) and
 * other sessions to the same server from triggering phantom reconnects.
 */
class SmbSessionLossTest {

    @Test
    void ownLineDownAndEndpointMatchIsALoss() {
        assertTrue(dock.smb.SmbSessions.SmbSession.isLoss(
                new ConnectionClosed("nas", 445), "nas", 445, false));
    }

    @Test
    void ownLineAliveIsNotALoss() {
        // Stale close after a reconnect: our fresh connection answers alive.
        assertFalse(dock.smb.SmbSessions.SmbSession.isLoss(
                new ConnectionClosed("nas", 445), "nas", 445, true));
    }

    @Test
    void foreignServerOrPortIsNotALoss() {
        assertFalse(dock.smb.SmbSessions.SmbSession.isLoss(
                new ConnectionClosed("router", 445), "nas", 445, false));
        assertFalse(dock.smb.SmbSessions.SmbSession.isLoss(
                new ConnectionClosed("nas", 1445), "nas", 445, false));
    }

    @Test
    void hostnameMatchIsCaseInsensitive() {
        assertTrue(dock.smb.SmbSessions.SmbSession.isLoss(
                new ConnectionClosed("NAS", 445), "nas", 445, false));
    }
}
