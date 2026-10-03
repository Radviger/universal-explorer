package dock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dock.webdav.WebDavFs;
import dock.webdav.WebDavSessions;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebDAV backend against an in-process fake server (the JDK's own
 * HttpServer speaking just enough DAV: PROPFIND/MKCOL/MOVE/PROPPATCH
 * plus GET with Range). Everything runs on localhost; no external
 * service is touched.
 */
@Timeout(60)
class WebDavFsTest {

    private static final long MTIME = Instant.parse("2024-06-01T12:00:00Z").toEpochMilli();

    private FakeDav dav;
    private WebDavSessions.WebDavSession session;
    private WebDavFs fs;

    @AfterEach
    void tearDown() {
        if (session != null) session.close();
        if (dav != null) dav.stop();
    }

    private void up(String authUserPass) throws IOException {
        dav = new FakeDav();
        dav.requiredAuth = authUserPass;
        dav.start();
        session = WebDavSessions.connect(new WebDavSessions.WebDavSpec(
                "localhost", dav.port(), false, "/dav",
                authUserPass == null ? null : authUserPass.split(":", 2)[0],
                authUserPass == null ? null
                        : authUserPass.split(":", 2)[1].toCharArray()));
        fs = (WebDavFs) session.fs();
    }

    @Test
    void listsNamesSizesAndTimes() throws IOException {
        up(null);
        List<String> names = fs.list("/").stream().map(e -> e.name()).toList();
        assertEquals(List.of("docs", "readme.txt", "фото 2024.txt"), names,
                "directories first, decoded names, no self entry");
        var readme = fs.list("/").stream()
                .filter(e -> e.name().equals("readme.txt")).findFirst().orElseThrow();
        assertFalse(readme.directory());
        assertEquals(5, readme.size());
        assertEquals(MTIME, readme.mtimeMillis());
        var docs = fs.list("/").stream()
                .filter(e -> e.name().equals("docs")).findFirst().orElseThrow();
        assertTrue(docs.directory());
        assertEquals(1, fs.list("/docs").size(), "only inner.txt inside docs");
    }

    @Test
    void statAndExists() throws IOException {
        up(null);
        assertEquals(5, fs.stat("/readme.txt").size());
        assertEquals(MTIME, fs.stat("/readme.txt").mtimeMillis());
        assertTrue(fs.exists("/readme.txt"));
        assertTrue(fs.exists("/docs"));
        assertFalse(fs.exists("/nope.txt"));
        assertFalse(fs.exists("/docs/nope"));
    }

    @Test
    void mkdirRenameDeleteRoundTrip() throws IOException {
        up(null);
        fs.mkdir("/made");
        assertTrue(fs.exists("/made"));
        assertTrue(dav.dirs.containsKey("/dav/made"));

        fs.rename("/docs/inner.txt", "/docs/moved.txt");
        assertFalse(fs.exists("/docs/inner.txt"));
        assertTrue(fs.exists("/docs/moved.txt"));

        fs.delete("/docs/moved.txt");
        assertFalse(fs.exists("/docs/moved.txt"));
    }

    @Test
    void writeStreamsThroughThePipeAndReadsBack() throws IOException {
        up(null);
        byte[] payload = new byte[300_000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
        try (OutputStream out = fs.write("/up.bin", false)) {
            out.write(payload, 0, 100_000);
            out.write(payload, 100_000, payload.length - 100_000);
            out.flush();
        }
        assertArrayEquals(payload, dav.files.get("/dav/up.bin"),
                "close() must wait for the PUT verdict before returning");

        try (InputStream in = fs.read("/up.bin")) {
            assertArrayEquals(payload, in.readAllBytes());
        }

        dav.lastRangeHeader = null;
        try (InputStream in = fs.read("/up.bin", 100_000)) {
            assertArrayEquals(Arrays.copyOfRange(payload, 100_000, payload.length),
                    in.readAllBytes());
        }
        assertEquals("bytes=100000-", dav.lastRangeHeader,
                "resume rides a Range request, not a skip-through");
    }

    @Test
    void deleteOfNonEmptyDirectoryIsRefused() throws IOException {
        up(null);
        IOException e = assertThrows(IOException.class, () -> fs.delete("/docs"));
        assertTrue(e.getMessage().contains("not empty"),
                "HTTP DELETE would remove the subtree; the fs must not offer that");
        fs.delete("/docs/inner.txt");
        fs.delete("/docs");
        assertFalse(fs.exists("/docs"));
    }

    @Test
    void setTimesSendsAProppatchWithRfc1123Stamp() throws IOException {
        up(null);
        long t2 = Instant.parse("2025-01-02T03:04:05Z").toEpochMilli();
        fs.setTimes("/readme.txt", t2);
        String stamp = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(Instant.ofEpochMilli(t2).atZone(ZoneOffset.UTC));
        assertTrue(dav.lastProppatchBody != null && dav.lastProppatchBody.contains(stamp),
                "PROPPATCH body carried the formatted stamp");
    }

    @Test
    void appendIsRefused() throws IOException {
        up(null);
        assertThrows(IOException.class, () -> fs.write("/up.bin", true));
    }

    @Test
    void wrongPasswordFailsToConnectWithReadableError() throws IOException {
        dav = new FakeDav();
        dav.requiredAuth = "aladdin:correct";
        dav.start();
        IOException e = assertThrows(IOException.class, () -> WebDavSessions.connect(
                new WebDavSessions.WebDavSpec("localhost", dav.port(), false, "/dav",
                        "aladdin", "opensesamy".toCharArray())));
        assertTrue(e.getMessage().contains("Authentication failed"),
                () -> "message was: " + e.getMessage());
    }

    @Test
    void missingBasePathFailsToConnect() throws IOException {
        dav = new FakeDav();
        dav.start();
        IOException e = assertThrows(IOException.class, () -> WebDavSessions.connect(
                new WebDavSessions.WebDavSpec("localhost", dav.port(), false, "/nope",
                        null, null)));
        assertTrue(e.getMessage().contains("check the path"),
                () -> "message was: " + e.getMessage());
    }

    @Test
    void reconnectRedialsTheSameSpec() throws IOException {
        up("aladdin:opensesamy");
        WebDavFs fresh = (WebDavFs) session.reconnect();
        assertEquals(fs.label(), fresh.label());
        assertEquals(5, fresh.stat("/readme.txt").size());
    }

    // ---- the server ----

    /**
     * Just enough WebDAV to be a sparring partner: an in-memory tree, RFC
     * 4918 multistatus PROPFIND, Basic auth, MOVE with Destination, and
     * GET with a single tail Range.
     */
    private static final class FakeDav {
        final HttpServer server;
        final ExecutorService pool = Executors.newFixedThreadPool(4);
        final Map<String, byte[]> files = new ConcurrentHashMap<>();
        final Map<String, Long> fileTimes = new ConcurrentHashMap<>();
        final Map<String, Long> dirs = new ConcurrentHashMap<>();
        volatile String requiredAuth;
        volatile String lastRangeHeader;
        volatile String lastProppatchBody;

        FakeDav() throws IOException {
            dirs.put("/dav", 0L);
            dirs.put("/dav/docs", MTIME);
            files.put("/dav/readme.txt", "hello".getBytes(StandardCharsets.UTF_8));
            fileTimes.put("/dav/readme.txt", MTIME);
            files.put("/dav/docs/inner.txt", "x".getBytes(StandardCharsets.UTF_8));
            fileTimes.put("/dav/docs/inner.txt", MTIME);
            files.put("/dav/фото 2024.txt", "photo".getBytes(StandardCharsets.UTF_8));
            fileTimes.put("/dav/фото 2024.txt", MTIME);
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(pool);
            server.createContext("/", this::handle);
        }

        void start() { server.start(); }

        int port() { return server.getAddress().getPort(); }

        void stop() {
            server.stop(0);
            pool.shutdownNow();
        }

        private void handle(HttpExchange x) throws IOException {
            byte[] body = x.getRequestBody().readAllBytes();
            try {
                if (requiredAuth != null) {
                    String expect = "Basic " + Base64.getEncoder()
                            .encodeToString(requiredAuth.getBytes(StandardCharsets.UTF_8));
                    if (!expect.equals(x.getRequestHeaders().getFirst("Authorization"))) {
                        respond(x, 401, "text/plain", "auth required");
                        return;
                    }
                }
                String path = URLDecoder.decode(
                        x.getRequestURI().getPath(), StandardCharsets.UTF_8);
                while (path.length() > 1 && path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                switch (x.getRequestMethod()) {
                    case "PROPFIND" -> propfind(x, path);
                    case "GET" -> get(x, path);
                    case "PUT" -> {
                        files.put(path, body);
                        fileTimes.put(path, System.currentTimeMillis());
                        respond(x, 201, "text/plain", "");
                    }
                    case "MKCOL" -> {
                        dirs.put(path, System.currentTimeMillis());
                        respond(x, 201, "text/plain", "");
                    }
                    case "DELETE" -> delete(x, path);
                    case "MOVE" -> move(x, path);
                    case "PROPPATCH" -> {
                        lastProppatchBody = new String(body, StandardCharsets.UTF_8);
                        respond(x, 207, "application/xml;charset=utf-8", """
                                <?xml version="1.0" encoding="utf-8"?>
                                <D:multistatus xmlns:D="DAV:">
                                 <D:response><D:href>%s</D:href><D:propstat><D:prop><D:getlastmodified/></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
                                </D:multistatus>
                                """.formatted(encodePath(path)));
                    }
                    default -> respond(x, 405, "text/plain", "unsupported");
                }
            } catch (Exception e) {
                respond(x, 500, "text/plain", String.valueOf(e));
            } finally {
                x.close();
            }
        }

        private void propfind(HttpExchange x, String path) throws IOException {
            if (!exists(path)) {
                respond(x, 404, "text/plain", "not found");
                return;
            }
            StringBuilder xml = new StringBuilder("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <D:multistatus xmlns:D="DAV:">
                    """);
            xml.append(responseFor(path));
            if ("1".equals(x.getRequestHeaders().getFirst("Depth"))) {
                for (String child : childrenOf(path)) xml.append(responseFor(child));
            }
            xml.append("</D:multistatus>\n");
            respond(x, 207, "application/xml;charset=utf-8", xml.toString());
        }

        private String responseFor(String path) {
            boolean dir = dirs.containsKey(path);
            StringBuilder sb = new StringBuilder(" <D:response><D:href>")
                    .append(encodePath(path)).append(dir ? "/" : "").append("</D:href>")
                    .append("<D:propstat><D:prop>");
            if (dir) {
                sb.append("<D:resourcetype><D:collection/></D:resourcetype>");
                long t = dirs.get(path);
                if (t > 0) sb.append(rfc1123(t));
            } else {
                byte[] d = files.get(path);
                sb.append("<D:resourcetype/>")
                        .append("<D:getcontentlength>")
                        .append(d == null ? 0 : d.length)
                        .append("</D:getcontentlength>");
                long t = fileTimes.getOrDefault(path, 0L);
                if (t > 0) sb.append(rfc1123(t));
            }
            sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>\n");
            return sb.toString();
        }

        private static String rfc1123(long millis) {
            return "<D:getlastmodified>" + DateTimeFormatter.RFC_1123_DATE_TIME
                    .format(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC))
                    + "</D:getlastmodified>";
        }

        private void get(HttpExchange x, String path) throws IOException {
            byte[] d = files.get(path);
            if (d == null) {
                respond(x, 404, "text/plain", "not found");
                return;
            }
            String range = x.getRequestHeaders().getFirst("Range");
            lastRangeHeader = range;
            if (range != null && range.matches("bytes=\\d+-")) {
                int from = Integer.parseInt(range.substring(6, range.length() - 1));
                x.getResponseHeaders().add("Content-Range",
                        "bytes " + from + "-" + (d.length - 1) + "/" + d.length);
                respond(x, 206, "application/octet-stream",
                        Arrays.copyOfRange(d, from, d.length));
            } else {
                respond(x, 200, "application/octet-stream", d);
            }
        }

        private void delete(HttpExchange x, String path) throws IOException {
            if (files.remove(path) != null) {
                fileTimes.remove(path);
                respond(x, 204, "text/plain", "");
                return;
            }
            if (dirs.containsKey(path)) {
                dirs.remove(path);
                files.keySet().removeIf(p -> p.startsWith(path + "/"));
                fileTimes.keySet().removeIf(p -> p.startsWith(path + "/"));
                dirs.keySet().removeIf(p -> p.startsWith(path + "/"));
                respond(x, 204, "text/plain", "");
                return;
            }
            respond(x, 404, "text/plain", "not found");
        }

        private void move(HttpExchange x, String path) throws IOException {
            String dest = x.getRequestHeaders().getFirst("Destination");
            String to = URLDecoder.decode(dest.replaceFirst("^\\w+://[^/]+", ""),
                    StandardCharsets.UTF_8);
            if (files.containsKey(path)) {
                files.put(to, files.remove(path));
                fileTimes.put(to, fileTimes.remove(path));
            } else if (dirs.containsKey(path)) {
                dirs.put(to, dirs.remove(path));
                remapUnder(files, path, to);
                remapUnder(fileTimes, path, to);
                remapUnder(dirs, path, to);
            } else {
                respond(x, 404, "text/plain", "not found");
                return;
            }
            respond(x, 201, "text/plain", "");
        }

        private static <V> void remapUnder(Map<String, V> map, String from, String to) {
            for (String key : map.keySet().toArray(new String[0])) {
                if (key.startsWith(from + "/")) {
                    V value = map.remove(key);
                    map.put(to + key.substring(from.length()), value);
                }
            }
        }

        private boolean exists(String path) {
            return dirs.containsKey(path) || files.containsKey(path);
        }

        private List<String> childrenOf(String path) {
            List<String> out = new ArrayList<>();
            String prefix = path.equals("/") ? "" : path;
            for (String key : files.keySet()) {
                if (key.startsWith(prefix + "/") && !key.substring(prefix.length() + 1).contains("/")) {
                    out.add(key);
                }
            }
            for (String key : dirs.keySet()) {
                if (!key.equals(path) && key.startsWith(prefix + "/")
                        && !key.substring(prefix.length() + 1).contains("/")) {
                    out.add(key);
                }
            }
            out.sort(String::compareTo);
            return out;
        }

        private static String encodePath(String path) {
            StringBuilder sb = new StringBuilder();
            for (String seg : path.split("/")) {
                if (!seg.isEmpty()) sb.append('/').append(encode(seg));
            }
            return sb.length() == 0 ? "/" : sb.toString();
        }

        private static String encode(String seg) {
            return URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20");
        }

        private static void respond(HttpExchange x, int code, String type, String body)
                throws IOException {
            respond(x, code, type, body.getBytes(StandardCharsets.UTF_8));
        }

        private static void respond(HttpExchange x, int code, String type, byte[] body)
                throws IOException {
            x.getResponseHeaders().set("Content-Type", type);
            x.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
            if (body.length > 0) x.getResponseBody().write(body);
        }
    }
}
