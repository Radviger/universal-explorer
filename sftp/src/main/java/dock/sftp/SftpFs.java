package dock.sftp;

import dock.core.fs.FileEntry;
import dock.core.fs.FileSystem;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

/**
 * Remote filesystem over a connected SFTP session. All operations
 * synchronize on the single SftpClient channel (it is not thread-safe),
 * which is fine for interactive browsing: operations are small and
 * sequential per pane.
 */
public final class SftpFs implements FileSystem {

    private final SshClient client;
    private final ClientSession session;
    private final SftpClient sftp;
    private final String label;
    private final boolean ownsSession;

    public SftpFs(SshClient client, ClientSession session, SftpClient sftp, String label) {
        this(client, session, sftp, label, true);
    }

    private SftpFs(SshClient client, ClientSession session, SftpClient sftp, String label,
                   boolean ownsSession) {
        this.client = client;
        this.session = session;
        this.sftp = sftp;
        this.label = label;
        this.ownsSession = ownsSession;
    }

    /** A view with its own SFTP channel; closing it leaves the session alone. */
    @Override
    public FileSystem streamView() throws IOException {
        return new SftpFs(client, session, openChannel(session), label, false);
    }

    public static SftpClient openChannel(ClientSession session) throws IOException {
        return SftpClientFactory.instance().createSftpClient(session);
    }

    /** The underlying session — used for shell channels (terminal). */
    public ClientSession session() {
        return session;
    }

    @Override public String label() { return label; }
    @Override public boolean remote() { return true; }
    @Override public String separator() { return "/"; }

    @Override public String home() {
        synchronized (sftp) {
            try {
                return sftp.canonicalPath(".");
            } catch (IOException e) {
                return "/";
            }
        }
    }

    @Override public List<String> roots() { return List.of("/"); }

    @Override public String normalize(String path) {
        String[] parts = path.split("/");
        List<String> stack = new ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) {
                if (!stack.isEmpty()) stack.remove(stack.size() - 1);
                continue;
            }
            stack.add(p);
        }
        return "/" + String.join("/", stack);
    }

    @Override public String parent(String path) {
        String n = normalize(path);
        int cut = n.lastIndexOf('/');
        if (cut <= 0) return "/";
        return n.substring(0, cut);
    }

    @Override public String child(String dir, String name) {
        String base = dir.endsWith("/") ? dir : dir + "/";
        return normalize(base + name);
    }

    @Override public boolean exists(String path) {
        try {
            stat(path);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override public List<FileEntry> list(String path) throws IOException {
        List<FileEntry> out = new ArrayList<>();
        synchronized (sftp) {
            Iterable<SftpClient.DirEntry> entries = sftp.readDir(normalize(path));
            for (var e : entries) {
                if (e.getFilename().equals(".") || e.getFilename().equals("..")) continue;
                out.add(toEntry(e.getFilename(), e.getAttributes()));
            }
        }
        return out;
    }

    @Override public FileEntry stat(String path) throws IOException {
        synchronized (sftp) {
            return toEntry(nameOf(path), sftp.stat(normalize(path)));
        }
    }

    private static String nameOf(String path) {
        String n = path.endsWith("/") && path.length() > 1
                ? path.substring(0, path.length() - 1) : path;
        int cut = n.lastIndexOf('/');
        return cut >= 0 ? n.substring(cut + 1) : n;
    }

    private static FileEntry toEntry(String name, SftpClient.Attributes a) {
        // The SFTP permissions field carries file-type bits in the high octets
        // (e.g. 0o100644 for a regular file); keep only rwx bits.
        int perms = a.getPermissions() & 0777;
        return new FileEntry(name, a.isDirectory(), a.getSize(),
                a.getModifyTime() == null ? 0 : a.getModifyTime().toMillis(),
                perms == 0 ? null : perms,
                name.startsWith("."));
    }

    @Override public void mkdir(String path) throws IOException {
        synchronized (sftp) { sftp.mkdir(normalize(path)); }
    }

    @Override public void delete(String path) throws IOException {
        String n = normalize(path);
        synchronized (sftp) {
            if (sftp.stat(n).isDirectory()) sftp.rmdir(n);
            else sftp.remove(n);
        }
    }

    @Override public void rename(String from, String to) throws IOException {
        synchronized (sftp) { sftp.rename(normalize(from), normalize(to)); }
    }

    @Override public InputStream read(String path) throws IOException {
        synchronized (sftp) {
            return sftp.read(normalize(path), SftpClient.IO_BUFFER_SIZE);
        }
    }

    @Override public InputStream read(String path, long offset) throws IOException {
        synchronized (sftp) {
            var channel = new org.apache.sshd.sftp.client.impl.SftpRemotePathChannel(
                    normalize(path), sftp, false,
                    java.util.Set.of(SftpClient.OpenMode.Read));
            channel.position(offset);
            return java.nio.channels.Channels.newInputStream(channel);
        }
    }

    @Override public OutputStream write(String path, boolean append) throws IOException {
        synchronized (sftp) {
            return append
                    ? sftp.write(normalize(path), SftpClient.IO_BUFFER_SIZE,
                            SftpClient.OpenMode.Write, SftpClient.OpenMode.Append)
                    : sftp.write(normalize(path), SftpClient.IO_BUFFER_SIZE,
                            SftpClient.OpenMode.Write, SftpClient.OpenMode.Create,
                            SftpClient.OpenMode.Truncate);
        }
    }

    @Override public void setTimes(String path, long mtimeMillis) throws IOException {
        synchronized (sftp) {
            sftp.setStat(normalize(path),
                    new SftpClient.Attributes().modifyTime(mtimeMillis, TimeUnit.MILLISECONDS));
        }
    }

    @Override public void setPerms(String path, int posix) throws IOException {
        synchronized (sftp) {
            sftp.setStat(normalize(path), new SftpClient.Attributes().perms(posix));
        }
    }

    @Override public void close() {
        try { sftp.close(); } catch (IOException ignored) {}
        if (ownsSession) {
            session.close(true);
            client.close(true);
        }
    }
}
