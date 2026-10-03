package dock.sftp;

import dock.core.fs.FileSystem;
import dock.sftp.SftpFs;
import dock.core.session.Session;
import org.apache.sshd.common.session.SessionListener;

/**
 * SSH-backed session: reconnects by re-dialing the stored spec and moves
 * the loss hook onto each fresh connection.
 */
public final class SftpSession implements Session {

    private final SshSessions.ConnectionSpec spec;
    private SftpFs fs;
    private SessionListener hook;

    /** A null spec makes a non-reconnectable ad-hoc session (tests). */
    public SftpSession(SftpFs fs, SshSessions.ConnectionSpec spec) {
        this.fs = fs;
        this.spec = spec;
    }

    @Override public FileSystem fs() { return fs; }

    @Override public FileSystem reconnect() throws java.io.IOException {
        SftpFs fresh = SshSessions.connect(spec, HostKeys.verifier(), null);
        if (hook != null) {
            fs.session().removeSessionListener(hook);
            fresh.session().addSessionListener(hook);
        }
        return fresh;
    }

    @Override public void onConnectionLost(Runnable callback) {
        if (hook != null) fs.session().removeSessionListener(hook);
        hook = new SessionListener() {
            @Override public void sessionClosed(org.apache.sshd.common.session.Session s) {
                callback.run();
            }
        };
        fs.session().addSessionListener(hook);
    }

    @Override public boolean reconnectable() { return spec != null; }

    @Override public void close() { fs.close(); }
}
