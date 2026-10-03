package dock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dock.sftp.SftpFs;
import dock.sftp.SshSessions;
import dock.sftp.DemoServer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.PropertyResolverUtils;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.junit.jupiter.api.Test;

/**
 * Idle-drop resilience: the connection must carry a keepalive that expects
 * replies, so a connection dropped for inactivity (NAT/firewall idle
 * timeout, sleep/wake — no close notification ever arrives) is detected,
 * closed, and handed to the reconnect flow instead of sitting "open" and
 * dead forever.
 */
class KeepaliveTest {

    /** SshSessions must configure the reply-expecting keepalive it relies on. */
    @Test
    void sessionsConfigureReplyExpectingKeepalive() throws Exception {
        try (DemoServer.Handle demo = DemoServer.start()) {
            SftpFs fs = SshSessions.connect("demo", "127.0.0.1", demo.port(),
                    "demo".toCharArray(), null, null, AcceptAllServerKeyVerifier.INSTANCE);
            try {
                var resolver = (org.apache.sshd.common.PropertyResolver)
                        fs.session().getFactoryManager();
                assertEquals(SshSessions.KEEPALIVE_INTERVAL.toMillis(),
                        resolver.getLong(CoreModuleProperties.HEARTBEAT_INTERVAL.getName()),
                        "keepalive interval is set on the client");
                assertEquals((Long) (long) SshSessions.KEEPALIVE_NO_REPLY_MAX,
                        resolver.getLong(CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.getName()),
                        "unanswered-keepalive tolerance is set on the client");
            } finally {
                fs.close();
            }
        }
    }

    /**
     * A server that never answers: after the tolerated number of unanswered
     * keepalives the session must close itself (which is what fires the
     * UI's connection-lost / auto-reconnect machinery).
     */
    @Test
    void unansweredKeepalivesCloseTheSession() throws Exception {
        try (DemoServer.Handle demo = DemoServer.start(0, true)) {
            try (SshClient client = fastKeepaliveClient()) {
                ClientSession session = connect(client, demo.port());
                try {
                    CountDownLatch closed = new CountDownLatch(1);
                    session.addSessionListener(new SessionListener() {
                        @Override public void sessionClosed(
                                org.apache.sshd.common.session.Session s) {
                            closed.countDown();
                        }
                    });
                    try (var sftp = SftpClientFactory.instance().createSftpClient(session)) {
                        sftp.lstat("/readme.md");
                    }
                    assertTrue(closed.await(15, TimeUnit.SECONDS),
                            "unanswered keepalives must close the dead session");
                    // sessionClosed is the exact event the UI reconnect
                    // machinery listens to; isClosed() itself flips a beat
                    // later, so the latch above is the authoritative check.
                } finally {
                    session.close(true);
                }
            }
        }
    }

    /** Control: a responsive server answers keepalives; the session stays up. */
    @Test
    void answeredKeepalivesKeepTheSessionAlive() throws Exception {
        try (DemoServer.Handle demo = DemoServer.start()) {
            try (SshClient client = fastKeepaliveClient()) {
                ClientSession session = connect(client, demo.port());
                try {
                    try (var sftp = SftpClientFactory.instance().createSftpClient(session)) {
                        sftp.lstat("/readme.md");
                    }
                    // ~15 heartbeats at the test cadence: any false positive
                    // would have closed the session long before this.
                    Thread.sleep(3_000);
                    assertTrue(!session.isClosed(), "healthy session must survive keepalives");
                } finally {
                    session.close(true);
                }
            }
        }
    }

    /** Same configuration SshSessions applies, but at test cadence (200ms). */
    private static SshClient fastKeepaliveClient() {
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        PropertyResolverUtils.updateProperty(client,
                CoreModuleProperties.HEARTBEAT_INTERVAL.getName(), 200L);
        PropertyResolverUtils.updateProperty(client,
                CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.getName(),
                SshSessions.KEEPALIVE_NO_REPLY_MAX);
        client.start();
        return client;
    }

    private static ClientSession connect(SshClient client, int port) throws Exception {
        ClientSession session = client.connect("demo", "127.0.0.1", port)
                .verify(15, TimeUnit.SECONDS).getSession();
        session.addPasswordIdentity("demo");
        session.auth().verify(15, TimeUnit.SECONDS);
        return session;
    }
}
