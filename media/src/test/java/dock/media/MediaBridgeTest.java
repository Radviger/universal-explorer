package dock.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The loopback range server against a real local file: every HTTP range
 * shape a media engine asks for, served from the filesystem's positioned
 * reads — plus one pass through a live SFTP server proving the seek path
 * end-to-end over a real remote backend.
 */
class MediaBridgeTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static byte[] payload;

    @BeforeAll
    static void payload() {
        payload = new byte[1000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31 + i % 7);
    }

    private static byte[] slice(int from, int toExclusive) {
        return Arrays.copyOfRange(payload, from, toExclusive);
    }

    private static HttpResponse<byte[]> get(String url, String range) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(url)).GET();
        if (range != null) b.header("Range", range);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void fullGetServesTheWholeFile(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("clip.mp4");
        Files.write(file, payload);
        try (MediaBridge.Registration reg = MediaBridge.serve(
                dock.core.fs.LocalFs.INSTANCE, file.toString())) {
            HttpResponse<byte[]> r = get(reg.url(), null);
            assertEquals(200, r.statusCode());
            assertArrayEquals(payload, r.body());
            assertEquals("video/mp4", r.headers().firstValue("Content-Type").orElse(null));
            assertEquals("bytes", r.headers().firstValue("Accept-Ranges").orElse(null));
            assertEquals("1000", r.headers().firstValue("Content-Length").orElse(null));
        }
    }

    @Test
    void openAndMiddleRangesServeExactSlices(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("clip.mp4");
        Files.write(file, payload);
        try (MediaBridge.Registration reg = MediaBridge.serve(
                dock.core.fs.LocalFs.INSTANCE, file.toString())) {
            HttpResponse<byte[]> open = get(reg.url(), "bytes=0-");
            assertEquals(206, open.statusCode());
            assertArrayEquals(payload, open.body());
            assertEquals("bytes 0-999/1000",
                    open.headers().firstValue("Content-Range").orElse(null));

            HttpResponse<byte[]> mid = get(reg.url(), "bytes=10-19");
            assertEquals(206, mid.statusCode());
            assertArrayEquals(slice(10, 20), mid.body());
            assertEquals("bytes 10-19/1000",
                    mid.headers().firstValue("Content-Range").orElse(null));
        }
    }

    @Test
    void suffixRangeServesTheTail(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("clip.mp4");
        Files.write(file, payload);
        try (MediaBridge.Registration reg = MediaBridge.serve(
                dock.core.fs.LocalFs.INSTANCE, file.toString())) {
            HttpResponse<byte[]> tail = get(reg.url(), "bytes=-7");
            assertEquals(206, tail.statusCode());
            assertArrayEquals(slice(993, 1000), tail.body());
            assertEquals("bytes 993-999/1000",
                    tail.headers().firstValue("Content-Range").orElse(null));
        }
    }

    @Test
    void aRangePastTheEndIsRefusedWithTheFileSize(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("clip.mp4");
        Files.write(file, payload);
        try (MediaBridge.Registration reg = MediaBridge.serve(
                dock.core.fs.LocalFs.INSTANCE, file.toString())) {
            HttpResponse<String> gone = HTTP.send(HttpRequest
                            .newBuilder(URI.create(reg.url())).header("Range", "bytes=1000-")
                            .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(416, gone.statusCode());
            assertEquals("bytes */1000",
                    gone.headers().firstValue("Content-Range").orElse(null));
        }
    }

    @Test
    void headAnswersHeadersOnly(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("clip.mp4");
        Files.write(file, payload);
        try (MediaBridge.Registration reg = MediaBridge.serve(
                dock.core.fs.LocalFs.INSTANCE, file.toString())) {
            HttpResponse<Void> head = HTTP.send(HttpRequest
                            .newBuilder(URI.create(reg.url())).method("HEAD",
                                    HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(200, head.statusCode());
            assertEquals("video/mp4", head.headers().firstValue("Content-Type").orElse(null));
            assertEquals(1000, head.statusCode() == 200
                    ? Integer.parseInt(head.headers()
                            .firstValue("Content-Length").orElse("-1")) : -1,
                    "HEAD carries the size without a body");
        }
    }

    @Test
    void closedTokensAndWrongOnesAreGone(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("clip.mp4");
        Files.write(file, payload);
        MediaBridge.Registration reg = MediaBridge.serve(
                dock.core.fs.LocalFs.INSTANCE, file.toString());
        String url = reg.url();
        reg.close();
        assertEquals(404, get(url, null).statusCode());
        // The token URL minus one character is a wrong token outright.
        assertEquals(404, get(url.substring(0, url.length() - 1), null).statusCode());
    }

    @Test
    void contentTypesFollowTheExtension(@TempDir Path tmp) throws Exception {
        Path mkv = tmp.resolve("film.mkv");
        Files.write(mkv, new byte[] {1});
        Path mp3 = tmp.resolve("track.mp3");
        Files.write(mp3, new byte[] {1});
        Path odd = tmp.resolve("blob.xyz");
        Files.write(odd, new byte[] {1});
        // The formats the extension map newly routes: the bridge types them
        // honestly rather than falling back to octet-stream.
        Path vob = tmp.resolve("dvd.vob");
        Files.write(vob, new byte[] {1});
        Path amr = tmp.resolve("memo.amr");
        Files.write(amr, new byte[] {1});
        Path ogv = tmp.resolve("capture.ogv");
        Files.write(ogv, new byte[] {1});
        try (var a = MediaBridge.serve(dock.core.fs.LocalFs.INSTANCE, mkv.toString());
             var b = MediaBridge.serve(dock.core.fs.LocalFs.INSTANCE, mp3.toString());
             var c = MediaBridge.serve(dock.core.fs.LocalFs.INSTANCE, odd.toString());
             var d = MediaBridge.serve(dock.core.fs.LocalFs.INSTANCE, vob.toString());
             var e = MediaBridge.serve(dock.core.fs.LocalFs.INSTANCE, amr.toString());
             var f = MediaBridge.serve(dock.core.fs.LocalFs.INSTANCE, ogv.toString())) {
            assertEquals("video/x-matroska",
                    get(a.url(), null).headers().firstValue("Content-Type").orElse(null));
            assertEquals("audio/mpeg",
                    get(b.url(), null).headers().firstValue("Content-Type").orElse(null));
            assertEquals("application/octet-stream",
                    get(c.url(), null).headers().firstValue("Content-Type").orElse(null));
            assertEquals("video/mpeg",
                    get(d.url(), null).headers().firstValue("Content-Type").orElse(null));
            assertEquals("audio/amr",
                    get(e.url(), null).headers().firstValue("Content-Type").orElse(null));
            assertEquals("video/ogg",
                    get(f.url(), null).headers().firstValue("Content-Type").orElse(null));
        }
    }

    @Test
    void namesWithSpacesSurviveTheUrl(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("my clip (2026).mp4");
        Files.write(file, payload);
        try (MediaBridge.Registration reg = MediaBridge.serve(
                dock.core.fs.LocalFs.INSTANCE, file.toString())) {
            assertTrue(reg.url().contains("%20"), "the space is encoded: " + reg.url());
            assertArrayEquals(payload, get(reg.url(), null).body());
        }
    }

    // ---- stale copies must die the moment a newer range arrives ----

    /** What every seek looks like from the bridge's side: the engine walks
     *  away from its old connection (never reads it again, may not close it
     *  for a long time) and immediately opens a new ranged GET. */
    private static Socket openRange(MediaBridge.Registration reg, String range)
            throws IOException {
        URI u = URI.create(reg.url());
        Socket s = new Socket(u.getHost(), u.getPort());
        s.setSoTimeout(10_000);
        s.getOutputStream().write(("GET " + u.getRawPath()
                + " HTTP/1.1\r\nHost: localhost\r\nRange: " + range
                + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        s.getOutputStream().flush();
        return s;
    }

    /** Reads the status line and headers, nothing more. */
    private static String readHead(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        StringBuilder head = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c < 0) throw new IOException("connection closed before headers ended");
            head.append((char) c);
            if (head.toString().endsWith("\r\n\r\n")) return head.toString();
        }
    }

    /** True once the server has closed the connection (graceful EOF or
     *  reset); leftover buffered body bytes are drained on the way. */
    private static boolean serverClosed(Socket s) {
        try {
            InputStream in = s.getInputStream();
            byte[] drain = new byte[64 * 1024];
            while (in.read(drain) >= 0) { /* drain until the server's EOF */ }
            return true;
        } catch (java.net.SocketTimeoutException e) {
            return false;   // nothing closed within the deadline
        } catch (IOException e) {
            return true;    // a reset counts as closed
        }
    }

    private static Path bigFile(Path tmp) throws IOException {
        Path file = tmp.resolve("long.mkv");
        Files.write(file, new byte[4 * 1024 * 1024]);   // past every loopback buffer
        return file;
    }

    @Test
    void aNewRangeIsServedWhileAnEarlierCopyStalls(@TempDir Path tmp) throws Exception {
        Path file = bigFile(tmp);
        try (MediaBridge.Registration reg = MediaBridge.serve(
                dock.core.fs.LocalFs.INSTANCE, file.toString());
             Socket stalled = openRange(reg, "bytes=0-")) {
            readHead(stalled);
            stalled.getInputStream().readNBytes(64 * 1024);   // then it stalls
            // The seek: a fresh range while the old copy still holds a
            // client that never reads and never closes. It must be
            // answered at once — copies share nothing, so the seek never
            // queues behind the connection it replaces (or behind VLC's
            // parallel metadata tail-read, for that matter).
            try (Socket seek = openRange(reg, "bytes=2000000-")) {
                String head = readHead(seek).toLowerCase(java.util.Locale.ROOT);
                assertTrue(head.contains(" 206 "), head);
                assertTrue(head.contains("content-range: bytes 2000000-"), head);
            }
        }
    }

    @Test
    void closingTheRegistrationKillsTheInFlightCopy(@TempDir Path tmp) throws Exception {
        Path file = bigFile(tmp);
        MediaBridge.Registration reg = MediaBridge.serve(
                dock.core.fs.LocalFs.INSTANCE, file.toString());
        Socket stalled = openRange(reg, "bytes=0-");
        readHead(stalled);
        stalled.getInputStream().readNBytes(64 * 1024);
        reg.close();
        assertTrue(serverClosed(stalled),
                "close() aborts the copy instead of waiting out its buffers");
        stalled.close();
    }

    /** The remote proof: a real SFTP server, a real ranged read behind the
     *  HTTP facade — this is exactly what playing over the wire does. */
    @Test
    void aRangedGetServesSlicesOverSftp() throws Exception {
        try (var demo = dock.sftp.DemoServer.start()) {
            dock.core.fs.FileSystem fs = dock.sftp.SshSessions.connect("demo",
                    "127.0.0.1", demo.port(), "demo".toCharArray(), null, null,
                    org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier.INSTANCE);
            try (fs) {
                Files.write(demo.root().resolve("stream.mkv"), payload);
                try (MediaBridge.Registration reg = MediaBridge.serve(fs, "/stream.mkv")) {
                    HttpResponse<byte[]> full = get(reg.url(), null);
                    assertEquals(200, full.statusCode());
                    assertArrayEquals(payload, full.body(), "the whole file over SFTP");
                    HttpResponse<byte[]> mid = get(reg.url(), "bytes=400-499");
                    assertEquals(206, mid.statusCode());
                    assertArrayEquals(slice(400, 500), mid.body(),
                            "a seek-shaped range reads from byte 400 — the wire only carries 100 bytes");
                }
            }
        }
    }
}
