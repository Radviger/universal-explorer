package dock;

import dock.core.fs.FileSystem;
import dock.ftp.FtpFs;
import dock.ftp.FtpSessions;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FTP backend against an in-process fake server: a raw-socket control
 * listener speaking enough RFC 959 (plus REST/MFMT) for commons-net —
 * UTF-8, passive data connections, UNIX listings. Everything runs on
 * localhost; no external service is touched.
 */
@Timeout(60)
class FtpFsTest {

    private static final Instant MTIME = Instant.parse("2024-06-01T12:00:00Z");
    private static final long LISTED_MTIME = LocalDate.of(2024, 6, 1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

    private FakeFtp ftp;
    private FtpSessions.FtpSession session;
    private FtpFs fs;

    @AfterEach
    void tearDown() {
        if (session != null) session.close();
        if (ftp != null) ftp.stop();
    }

    private void up(String requiredUserPass, String basePath) throws IOException {
        ftp = new FakeFtp();
        ftp.requiredUserPass = requiredUserPass;
        session = FtpSessions.connect(new FtpSessions.FtpSpec(
                "localhost", ftp.port(), false, basePath,
                requiredUserPass == null ? null : requiredUserPass.split(":", 2)[0],
                requiredUserPass == null ? null
                        : requiredUserPass.split(":", 2)[1].toCharArray()));
        fs = (FtpFs) session.fs();
    }

    @Test
    void listsNamesSizesAndTimes() throws IOException {
        up(null, null);
        List<String> names = fs.list("/").stream().map(e -> e.name()).toList();
        assertEquals(List.of("docs", "readme.txt", "фото 2024.txt"), names,
                "decoded names, dotfiles carried as hidden");
        var readme = fs.list("/").stream()
                .filter(e -> e.name().equals("readme.txt")).findFirst().orElseThrow();
        assertFalse(readme.directory());
        assertEquals(5, readme.size());
        assertEquals(LISTED_MTIME, readme.mtimeMillis(),
                "the yyyy listing style lands at local midnight");
        var docs = fs.list("/").stream()
                .filter(e -> e.name().equals("docs")).findFirst().orElseThrow();
        assertTrue(docs.directory());
        assertEquals(1, fs.list("/docs").size(), "only inner.txt inside docs");
    }

    @Test
    void statExistsAndHomePath() throws IOException {
        up(null, "/docs");
        assertEquals("/docs", fs.home(), "the opening directory is home, not the root");
        assertEquals("ftp://localhost:" + ftp.port(), fs.label(),
                "non-default ports appear in the label");
        assertEquals(5, fs.stat("/readme.txt").size());
        assertTrue(fs.exists("/readme.txt"));
        assertTrue(fs.exists("/docs"));
        assertFalse(fs.exists("/nope.txt"));
        assertFalse(fs.exists("/docs/nope"));
    }

    @Test
    void mkdirRenameDeleteRoundTrip() throws IOException {
        up(null, null);
        fs.mkdir("/made");
        assertTrue(fs.exists("/made"));
        assertTrue(ftp.dirs.containsKey("/made"));

        fs.rename("/docs/inner.txt", "/docs/moved.txt");
        assertFalse(fs.exists("/docs/inner.txt"));
        assertTrue(fs.exists("/docs/moved.txt"));

        fs.delete("/docs/moved.txt");
        assertFalse(fs.exists("/docs/moved.txt"));
    }

    @Test
    void writeStreamsReadsAndAppends() throws IOException {
        up(null, null);
        byte[] payload = new byte[300_000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
        try (OutputStream out = fs.write("/up.bin", false)) {
            out.write(payload, 0, 100_000);
            out.write(payload, 100_000, payload.length - 100_000);
            out.flush();
        }
        assertArrayEquals(payload, ftp.files.get("/up.bin"),
                "close() must finish the pending 226 before returning");

        try (InputStream in = fs.read("/up.bin")) {
            assertArrayEquals(payload, in.readAllBytes());
        }

        byte[] extra = "tail".getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = fs.write("/up.bin", true)) {
            out.write(extra);
        }
        byte[] expected = Arrays.copyOf(payload, payload.length + extra.length);
        System.arraycopy(extra, 0, expected, payload.length, extra.length);
        assertArrayEquals(expected, ftp.files.get("/up.bin"), "APPE extends the file");
    }

    @Test
    void resumeRidesRestNotASkip() throws IOException {
        up(null, null);
        byte[] payload = new byte[100_000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 7);
        try (OutputStream out = fs.write("/big.bin", false)) {
            out.write(payload);
        }
        try (InputStream in = fs.read("/big.bin", 40_000)) {
            assertArrayEquals(Arrays.copyOfRange(payload, 40_000, payload.length),
                    in.readAllBytes());
        }
        assertEquals(40_000, ftp.lastRest.get(),
                "resume sends REST, the server slices — no skipped bytes cross the wire");
    }

    @Test
    void deleteOfNonEmptyDirectoryIsRefused() throws IOException {
        up(null, null);
        IOException e = assertThrows(IOException.class, () -> fs.delete("/docs"));
        assertTrue(e.getMessage().contains("not empty"),
                "the fs checks before offering RMD the subtree");
        fs.delete("/docs/inner.txt");
        fs.delete("/docs");
        assertFalse(fs.exists("/docs"));
    }

    @Test
    void setTimesSendsMfmt() throws IOException {
        up(null, null);
        long t2 = Instant.parse("2025-01-02T03:04:05Z").toEpochMilli();
        fs.setTimes("/readme.txt", t2);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(t2));
        assertTrue(ftp.lastMfmt != null && ftp.lastMfmt.startsWith(stamp)
                        && ftp.lastMfmt.endsWith("/readme.txt"),
                "MFMT carried the 14-digit stamp and the path; was: " + ftp.lastMfmt);
    }

    @Test
    void streamViewDialsADedicatedClient() throws IOException {
        up("aladdin:opensesamy", null);
        long before = ftp.logins.get();
        byte[] payload = "hello over a private line".getBytes(StandardCharsets.UTF_8);
        try (FileSystem view = fs.streamView()) {
            assertNotSame(fs, view, "transfers must not share the browsing control line");
            try (OutputStream out = view.write("/via-stream.bin", false)) {
                out.write(payload);
            }
        }
        assertEquals(before + 1, ftp.logins.get(), "the stream view logged in separately");
        try (InputStream in = fs.read("/via-stream.bin")) {
            assertArrayEquals(payload, in.readAllBytes(),
                    "the browsing client still works after the transfer view closed");
        }
    }

    @Test
    void wrongPasswordFailsToConnectWithReadableError() throws IOException {
        ftp = new FakeFtp();
        ftp.requiredUserPass = "aladdin:correct";
        IOException e = assertThrows(IOException.class, () -> FtpSessions.connect(
                new FtpSessions.FtpSpec("localhost", ftp.port(), false, null,
                        "aladdin", "opensesamy".toCharArray())));
        assertTrue(e.getMessage().contains("Authentication failed"),
                () -> "message was: " + e.getMessage());
    }

    @Test
    void reconnectRedialsTheSameSpec() throws IOException {
        up("aladdin:opensesamy", null);
        long before = ftp.logins.get();
        FtpFs fresh = (FtpFs) session.reconnect();
        assertEquals(fs.label(), fresh.label());
        assertEquals(5, fresh.stat("/readme.txt").size());
        assertEquals(before + 1, ftp.logins.get());
    }

    // ---- the server ----

    /**
     * Just enough FTP for a sparring partner: a raw control listener with
     * per-connection threads, passive data sockets on demand, UNIX-style
     * listings, and the REST/MFMT extensions resume and timestamps use.
     */
    private static final class FakeFtp {
        final ServerSocket control;
        final ExecutorService pool = Executors.newCachedThreadPool();
        final Map<String, byte[]> files = new ConcurrentHashMap<>();
        final Map<String, Long> fileTimes = new ConcurrentHashMap<>();
        final Map<String, Long> dirs = new ConcurrentHashMap<>();
        final AtomicLong logins = new AtomicLong();
        final AtomicLong lastRest = new AtomicLong(-1);
        volatile String requiredUserPass;
        volatile String lastMfmt;

        FakeFtp() throws IOException {
            dirs.put("/", 0L);
            dirs.put("/docs", MTIME.toEpochMilli());
            files.put("/readme.txt", "hello".getBytes(StandardCharsets.UTF_8));
            fileTimes.put("/readme.txt", MTIME.toEpochMilli());
            files.put("/docs/inner.txt", "x".getBytes(StandardCharsets.UTF_8));
            fileTimes.put("/docs/inner.txt", MTIME.toEpochMilli());
            files.put("/фото 2024.txt", "photo".getBytes(StandardCharsets.UTF_8));
            fileTimes.put("/фото 2024.txt", MTIME.toEpochMilli());
            control = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            pool.submit(this::acceptLoop);
        }

        int port() { return control.getLocalPort(); }

        void stop() {
            try {
                control.close();
            } catch (IOException ignored) {
                // The pool threads die with their sockets.
            }
            pool.shutdownNow();
        }

        private void acceptLoop() {
            while (!control.isClosed()) {
                try {
                    Socket s = control.accept();
                    pool.submit(() -> session(s));
                } catch (IOException e) {
                    return; // stop() closed the listener
                }
            }
        }

        private void session(Socket socket) {
            try (Socket sock = socket;
                 BufferedReader in = new BufferedReader(new InputStreamReader(
                         sock.getInputStream(), StandardCharsets.UTF_8))) {
                Writer out = new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8);
                SessionState st = new SessionState();
                reply(out, "220 FakeFtp ready");
                String line;
                while ((line = in.readLine()) != null) {
                    if (!handle(line.trim(), in, out, st)) return;
                }
            } catch (Exception ignored) {
                // Client dropped the control line; nothing to clean up.
            }
        }

        /** One command; false = end the session. */
        private boolean handle(String cmd, BufferedReader in, Writer out, SessionState st)
                throws IOException {
            String verb = cmd.split(" ", 2)[0].toUpperCase(Locale.ROOT);
            String arg = cmd.contains(" ") ? cmd.substring(cmd.indexOf(' ') + 1) : "";
            switch (verb) {
                case "USER" -> { st.user = arg; reply(out, "331 Password required"); }
                case "PASS" -> {
                    if (requiredUserPass != null
                            && !(st.user + ":" + arg).equals(requiredUserPass)) {
                        reply(out, "530 Login incorrect");
                    } else {
                        logins.incrementAndGet();
                        reply(out, "230 Logged in");
                    }
                }
                case "SYST" -> reply(out, "215 UNIX Type: L8");
                case "FEAT" -> { out.write("211-Features:\r\n UTF8\r\n211 End\r\n"); out.flush(); }
                case "OPTS", "TYPE", "STRU", "MODE", "NOOP" -> reply(out, "200 OK");
                case "PWD", "XPWD" -> reply(out, "257 \"/\" is the current directory");
                case "CWD", "CDUP" -> reply(out, "250 OK");
                case "PASV" -> {
                    st.pendingData = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                    int p = st.pendingData.getLocalPort();
                    reply(out, "227 Entering Passive Mode (127,0,0,1,"
                            + (p >>> 8) + "," + (p & 0xFF) + ")");
                }
                case "EPSV" -> {
                    st.pendingData = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                    reply(out, "229 Entering Extended Passive Mode (|||"
                            + st.pendingData.getLocalPort() + "|)");
                }
                case "REST" -> {
                    lastRest.set(Long.parseLong(arg));
                    st.rest = Long.parseLong(arg);
                    reply(out, "350 Restarting at " + st.rest);
                }
                case "LIST", "NLST" -> list(arg, out, st);
                case "RETR" -> retr(arg, out, st);
                case "STOR" -> store(arg, out, st, false);
                case "APPE" -> store(arg, out, st, true);
                case "DELE" -> {
                    if (files.remove(arg) != null) {
                        fileTimes.remove(arg);
                        reply(out, "250 File deleted");
                    } else {
                        reply(out, "550 No such file");
                    }
                }
                case "MKD" -> { dirs.put(arg, System.currentTimeMillis()); reply(out, "257 Created"); }
                case "RMD" -> {
                    if (!dirs.containsKey(arg)) reply(out, "550 No such directory");
                    else if (hasChildren(arg)) reply(out, "550 Directory not empty");
                    else { dirs.remove(arg); reply(out, "250 Removed"); }
                }
                case "RNFR" -> { st.renameFrom = arg; reply(out, "350 Ready for RNTO"); }
                case "RNTO" -> {
                    move(st.renameFrom, arg);
                    reply(out, "250 Renamed");
                }
                case "MDTM" -> {
                    Long t = fileTimes.get(arg);
                    reply(out, t == null ? "550 No such file" : "213 " + DateTimeFormatter
                            .ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)
                            .format(Instant.ofEpochMilli(t)));
                }
                case "MFMT" -> {
                    lastMfmt = arg;
                    int sp = arg.indexOf(' ');
                    reply(out, "213 Modify=" + arg.substring(0, sp) + "; "
                            + arg.substring(sp + 1));
                }
                case "QUIT" -> { reply(out, "221 Bye"); return false; }
                default -> reply(out, "500 Unknown command " + verb);
            }
            return true;
        }

        private void list(String arg, Writer out, SessionState st) throws IOException {
            String path = arg.replaceFirst("(?i)^-a\\s+", "");
            if (path.isEmpty()) path = "/";
            if (!dirs.containsKey(path)) {
                reply(out, "550 No such directory");
                return;
            }
            reply(out, "150 Opening data connection");
            try (Socket data = accept(st)) {
                Writer w = new OutputStreamWriter(data.getOutputStream(), StandardCharsets.UTF_8);
                for (String child : childrenOf(path)) w.write(listingLine(child));
                w.flush();
            }
            reply(out, "226 Transfer complete");
        }

        private void retr(String arg, Writer out, SessionState st) throws IOException {
            byte[] d = files.get(arg);
            if (d == null) {
                reply(out, "550 No such file");
                return;
            }
            reply(out, "150 Opening data connection");
            try (Socket data = accept(st)) {
                int from = (int) Math.min(st.rest, d.length);
                data.getOutputStream().write(d, from, d.length - from);
                data.getOutputStream().flush();
            }
            st.rest = 0;
            reply(out, "226 Transfer complete");
        }

        private void store(String arg, Writer out, SessionState st, boolean append)
                throws IOException {
            reply(out, "150 Ok to send data");
            try (Socket data = accept(st)) {
                byte[] got = data.getInputStream().readAllBytes();
                byte[] old = append ? files.getOrDefault(arg, new byte[0]) : new byte[0];
                byte[] merged = Arrays.copyOf(old, old.length + got.length);
                System.arraycopy(got, 0, merged, old.length, got.length);
                files.put(arg, merged);
                fileTimes.put(arg, System.currentTimeMillis());
            }
            reply(out, "226 Transfer complete");
        }

        private Socket accept(SessionState st) throws IOException {
            ServerSocket pending = st.pendingData;
            st.pendingData = null;
            if (pending == null) throw new IOException("no PASV before data command");
            try {
                Socket data = pending.accept();
                pending.close();
                return data;
            } catch (IOException e) {
                try { pending.close(); } catch (IOException ignored) {}
                throw e;
            }
        }

        private String listingLine(String path) {
            boolean dir = dirs.containsKey(path);
            String name = path.substring(path.lastIndexOf('/') + 1);
            long t = dir ? dirs.get(path) : fileTimes.getOrDefault(path, 0L);
            String date = t <= 0 ? "Jan 01 1970" : DateTimeFormatter
                    .ofPattern("MMM dd yyyy", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(t));
            long size = dir ? 4096 : files.getOrDefault(path, new byte[0]).length;
            return (dir ? 'd' : '-') + "rw-r--r--   1 owner    group        "
                    + String.format("%8d %s %s%n", size, date, name);
        }

        private void move(String from, String to) {
            if (files.containsKey(from)) {
                files.put(to, files.remove(from));
                fileTimes.put(to, fileTimes.remove(from));
            } else if (dirs.containsKey(from)) {
                dirs.put(to, dirs.remove(from));
                remapUnder(files, from, to);
                remapUnder(fileTimes, from, to);
                remapUnder(dirs, from, to);
            }
        }

        private static <V> void remapUnder(Map<String, V> map, String from, String to) {
            for (String key : map.keySet().toArray(new String[0])) {
                if (key.startsWith(from + "/")) {
                    V value = map.remove(key);
                    map.put(to + key.substring(from.length()), value);
                }
            }
        }

        private boolean hasChildren(String path) {
            return !childrenOf(path).isEmpty();
        }

        private List<String> childrenOf(String path) {
            List<String> out = new ArrayList<>();
            String prefix = path.equals("/") ? "/" : path + "/";
            for (String key : files.keySet()) {
                if (key.startsWith(prefix) && !key.substring(prefix.length()).contains("/")) {
                    out.add(key);
                }
            }
            for (String key : dirs.keySet()) {
                if (!key.equals(path) && key.startsWith(prefix)
                        && !key.substring(prefix.length()).contains("/")) {
                    out.add(key);
                }
            }
            out.sort(String::compareTo);
            return out;
        }

        private static void reply(Writer out, String line) throws IOException {
            out.write(line + "\r\n");
            out.flush();
        }

        private static final class SessionState {
            String user;
            long rest;
            String renameFrom;
            ServerSocket pendingData;
        }
    }
}
