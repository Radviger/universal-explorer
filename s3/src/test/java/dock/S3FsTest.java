package dock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dock.core.config.AppPaths;
import dock.core.fs.FileEntry;
import dock.core.fs.FileSystem;
import dock.s3.S3Sessions;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The S3 backend against an in-process fake server (the JDK's own
 * HttpServer speaking just enough S3: ListBuckets, ListObjectsV2 with
 * delimiter and continuation tokens, GET with Range, PUT with
 * copy-source, DELETE). The fake <em>verifies every request's SigV4
 * signature</em> — an independent second implementation of the signing
 * spec — so a client that canonicalizes differently than it signs is
 * rejected on the spot. Everything runs on localhost; no external
 * service is touched.
 */
@Timeout(60)
class S3FsTest {

    private static final long MTIME = Instant.parse("2024-06-01T12:00:00Z").toEpochMilli();

    private FakeS3 s3;
    private S3Sessions.S3Session session;
    private FileSystem fs;

    @BeforeAll
    static void isolateCache(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        // write() lands its temp files under the app cache; keep them out
        // of the real profile.
        AppPaths.override(dir);
    }

    @AfterAll
    static void restoreCache() {
        AppPaths.override(null);
    }

    @AfterEach
    void tearDown() {
        if (session != null) session.close();
        if (s3 != null) s3.stop();
    }

    private void up() throws IOException {
        s3 = new FakeS3();
        s3.start();
        session = S3Sessions.connect(new S3Sessions.S3Spec("localhost", s3.port(), false,
                null, FakeS3.ACCESS, FakeS3.SECRET, null));
        fs = session.fs();
    }

    private List<String> names(List<FileEntry> rows) {
        return rows.stream().map(FileEntry::name).toList();
    }

    @Test
    void listsBucketsAsShareRowsAtTheEndpointRoot() throws IOException {
        up();
        assertEquals(List.of("archive", "media", "quirks"), names(fs.list("/")));
        var media = fs.list("/").stream()
                .filter(e -> e.name().equals("media")).findFirst().orElseThrow();
        assertTrue(media.directory(), "a bucket navigates like a directory");
        assertTrue(media.share(), "and is a network object, not a folder");
        assertEquals(MTIME, media.mtimeMillis());
        assertTrue(fs.immutableListing("/"));
        assertFalse(fs.immutableListing("/media"));
        assertTrue(fs.exists("/"));
    }

    @Test
    void listsPrefixesAndFilesInsideABucket() throws IOException {
        up();
        assertEquals(List.of("docs", "empty", "logs", "readme.md", "отчёт 2024.txt"),
                names(fs.list("/media")), "the server's key order, files and prefixes interleaved");
        var readme = fs.list("/media").stream()
                .filter(e -> e.name().equals("readme.md")).findFirst().orElseThrow();
        assertFalse(readme.directory());
        assertEquals(8, readme.size());
        assertEquals(MTIME, readme.mtimeMillis());
        assertTrue(fs.list("/media").stream()
                .filter(e -> e.name().equals("docs")).findFirst().orElseThrow().directory());
        assertEquals(List.of("guide.txt"), names(fs.list("/media/docs")));
        assertEquals(List.of(), names(fs.list("/media/empty")),
                "the zero-byte marker keeps an empty directory visible");
        assertTrue(s3.tokensIssued > 0,
                "the fake pages at 2, so this listing rode continuation tokens");
    }

    @Test
    void folderShadowsAndQuirkyMarkersPresentAsDirectories() throws IOException {
        up();
        // The spec-legal shape: a zero-byte "notes" key beside a live
        // "notes/" prefix shows once, as the folder — never as a size-0
        // file named like one.
        assertEquals(List.of("empty-file", "notes"), names(fs.list("/quirks")));
        var notes = fs.list("/quirks").stream()
                .filter(e -> e.name().equals("notes")).findFirst().orElseThrow();
        assertTrue(notes.directory(), "the shadow hides behind its folder");
        var standalone = fs.list("/quirks").stream()
                .filter(e -> e.name().equals("empty-file")).findFirst().orElseThrow();
        assertFalse(standalone.directory(), "a zero-byte key with no prefix is a real file");
        assertEquals(0, standalone.size());
        assertTrue(fs.stat("/quirks/notes").directory(), "stat agrees with the listing");
        assertFalse(fs.stat("/quirks/empty-file").directory());

        // Provider quirk: "dir/" markers returned as Contents rows instead
        // of CommonPrefixes still fold into directories.
        s3.markersAsContents = true;
        var marker = fs.list("/media").stream()
                .filter(e -> e.name().equals("empty")).findFirst().orElseThrow();
        assertTrue(marker.directory(), "a Contents marker lists as the folder");

        // Provider quirk: the delimiter ignored, raw keys listed — the
        // client rolls them back into the same shape a conformant server
        // would have sent.
        s3.markersAsContents = false;
        s3.ignoreDelimiter = true;
        assertEquals(List.of("docs", "empty", "logs", "readme.md", "отчёт 2024.txt"),
                names(fs.list("/media")));
        for (String dir : new String[] {"docs", "empty", "logs"}) {
            assertTrue(fs.list("/media").stream()
                    .filter(e -> e.name().equals(dir)).findFirst().orElseThrow().directory(),
                    dir + " rolls back into a folder");
        }
    }

    @Test
    void statAndExistsCoverFilesDirectoriesAndBuckets() throws IOException {
        up();
        assertEquals(8, fs.stat("/media/readme.md").size());
        assertEquals(MTIME, fs.stat("/media/readme.md").mtimeMillis());
        assertTrue(fs.stat("/media/docs").directory(), "implicit: children pin the prefix");
        assertTrue(fs.stat("/media/empty").directory(), "explicit: the marker key");
        assertTrue(fs.stat("/media").share());
        assertTrue(fs.exists("/media/logs/2024"), "a nested implicit directory");
        assertFalse(fs.exists("/media/nope"));
        assertFalse(fs.exists("/media/nope/x.txt"));
        assertFalse(fs.exists("/nope"));
    }

    @Test
    void mkdirWritesAMarkerThatListsAsADirectory() throws IOException {
        up();
        fs.mkdir("/media/made");
        assertTrue(fs.exists("/media/made"));
        assertTrue(fs.list("/media").stream().anyMatch(
                e -> e.name().equals("made") && e.directory()));
        assertTrue(s3.buckets.get("media").containsKey("made/"),
                "the directory is a zero-byte marker key");
        IOException root = assertThrows(IOException.class, () -> fs.mkdir("/"));
        assertTrue(root.getMessage().contains("open a bucket"), root.getMessage());
        IOException bucket = assertThrows(IOException.class, () -> fs.mkdir("/made2"));
        assertTrue(bucket.getMessage().contains("bucket"), bucket.getMessage());
    }

    @Test
    void emptyFoldersOnMarkerStrippingProvidersStillListAsDirectories() throws IOException {
        up();
        s3.slashStrippingMarkers = true;
        fs.mkdir("/media/samples");
        fs.mkdir("/media/samples/sub");
        var row = fs.list("/media").stream()
                .filter(e -> e.name().equals("samples")).findFirst().orElseThrow();
        assertTrue(row.directory(), "the stripped marker still presents as the folder");
        assertTrue(fs.stat("/media/samples").directory(), "stat agrees with the listing");
        assertTrue(fs.list("/media/samples").stream().anyMatch(
                e -> e.name().equals("sub") && e.directory()),
                "a nested folder folds the same way");
        assertFalse(s3.buckets.get("media").containsKey("samples/"),
                "the provider kept the marker bare, Selectel-style");
        assertEquals("application/directory", s3.contentTypes.get("media/samples"));
    }

    @Test
    void genuineEmptyFilesStayFilesBesideStrippedMarkers() throws IOException {
        up();
        s3.slashStrippingMarkers = true;
        fs.mkdir("/media/samples");
        try (OutputStream out = fs.write("/media/keep.file", false)) {
            // zero bytes on purpose: an empty upload is not a folder
        }
        var rows = fs.list("/media");
        assertTrue(rows.stream().filter(e -> e.name().equals("samples"))
                .findFirst().orElseThrow().directory());
        var keep = rows.stream().filter(e -> e.name().equals("keep.file"))
                .findFirst().orElseThrow();
        assertFalse(keep.directory(), "only the stamped marker folds; an empty upload is data");
        assertEquals(0, keep.size());
        assertFalse(fs.stat("/media/keep.file").directory());
    }

    @Test
    void deletingAStrippedMarkerFolderRemovesTheBareMarker() throws IOException {
        up();
        s3.slashStrippingMarkers = true;
        fs.mkdir("/media/gone");
        assertTrue(fs.exists("/media/gone"));
        fs.delete("/media/gone");
        assertFalse(fs.exists("/media/gone"));
        assertFalse(s3.buckets.get("media").containsKey("gone"),
                "the bare marker left with the folder");
    }

    @Test
    void writeUploadsOnCloseAndReadsBackWithRangedResume() throws IOException {
        up();
        byte[] payload = new byte[300_000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
        try (OutputStream out = fs.write("/media/up.bin", false)) {
            out.write(payload, 0, 100_000);
            out.write(payload, 100_000, payload.length - 100_000);
            out.flush();
        }
        assertArrayEquals(payload, s3.buckets.get("media").get("up.bin"),
                "close() must upload before returning");
        assertEquals(300_000, fs.stat("/media/up.bin").size());
        try (InputStream in = fs.read("/media/up.bin")) {
            assertArrayEquals(payload, in.readAllBytes());
        }
        s3.lastRangeHeader = null;
        try (InputStream in = fs.read("/media/up.bin", 100_000)) {
            assertArrayEquals(Arrays.copyOfRange(payload, 100_000, payload.length),
                    in.readAllBytes());
        }
        assertEquals("bytes=100000-", s3.lastRangeHeader,
                "resume rides a Range request, not a skip-through");
    }

    @Test
    void renameCopiesServerSideAndDeletesTheOriginal() throws IOException {
        up();
        fs.rename("/media/readme.md", "/media/about.md");
        assertFalse(fs.exists("/media/readme.md"));
        try (InputStream in = fs.read("/media/about.md")) {
            assertEquals("hello s3", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals("/media/readme.md", s3.lastCopySource);
        IOException dir = assertThrows(IOException.class,
                () -> fs.rename("/media/docs", "/media/manuals"));
        assertTrue(dir.getMessage().contains("directory rename"), dir.getMessage());
        IOException bucket = assertThrows(IOException.class,
                () -> fs.rename("/media", "/media2"));
        assertTrue(bucket.getMessage().contains("bucket"), bucket.getMessage());
        IOException across = assertThrows(IOException.class,
                () -> fs.rename("/media/readme.md", "/archive/readme.md"));
        assertTrue(across.getMessage().contains("across buckets"), across.getMessage());
    }

    @Test
    void deleteRefusesNonEmptyDirectoriesAndClearsMarkers() throws IOException {
        up();
        IOException e = assertThrows(IOException.class, () -> fs.delete("/media/docs"));
        assertTrue(e.getMessage().contains("not empty"),
                "a silent marker delete would strand the children");
        fs.deleteTree("/media/logs");
        assertFalse(fs.exists("/media/logs"));
        fs.mkdir("/media/tmp");
        fs.delete("/media/tmp");
        assertFalse(fs.exists("/media/tmp"), "the marker key is gone");
        fs.delete("/media/readme.md");
        assertFalse(fs.exists("/media/readme.md"));
    }

    @Test
    void wrongSecretIsRejectedByTheSignatureCheck() throws IOException {
        s3 = new FakeS3();
        s3.start();
        IOException e = assertThrows(IOException.class, () -> S3Sessions.connect(
                new S3Sessions.S3Spec("localhost", s3.port(), false, null,
                        FakeS3.ACCESS, "wrong-secret".toCharArray(), null)));
        assertTrue(e.getMessage().contains("Authentication failed"),
                () -> "message was: " + e.getMessage());
        assertEquals(1, s3.rejectedSignatures);
    }

    @Test
    void appendIsRefused() throws IOException {
        up();
        assertThrows(IOException.class, () -> fs.write("/media/up.bin", true));
    }

    @Test
    void reconnectRedialsTheSameSpec() throws IOException {
        up();
        FileSystem fresh = session.reconnect();
        assertEquals(fs.label(), fresh.label());
        assertEquals(8, fresh.stat("/media/readme.md").size());
    }

    @Test
    void everyRequestCarriesAVerifiedSignature() throws IOException {
        up();
        fs.list("/");
        fs.list("/media");
        fs.stat("/media/readme.md");
        try (InputStream in = fs.read("/media/readme.md")) {
            in.readAllBytes();
        }
        assertTrue(s3.requests >= 5);
        assertEquals(0, s3.rejectedSignatures,
                "the fake 403s any request whose signature it cannot reproduce");
    }

    // ---- the server ----

    /**
     * Just enough S3 to be a sparring partner: an in-memory bucket/key
     * tree, ListObjectsV2 with delimiter and forced-2 pagination, GET
     * with a Range, PUT with copy-source, DELETE — and a SigV4 verifier
     * written from the AWS docs, independent of {@code dock.s3.SigV4}.
     */
    private static final class FakeS3 {

        static final String ACCESS = "docktest";
        static final char[] SECRET = "dock-secret-1".toCharArray();
        private static final String SECRET_TEXT = new String(SECRET);

        final HttpServer server;
        final ExecutorService pool = Executors.newFixedThreadPool(4);
        final Map<String, TreeMap<String, byte[]>> buckets = new ConcurrentHashMap<>();
        final Map<String, Long> mtimes = new ConcurrentHashMap<>();
        /** Page cap forced low so every multi-row listing rides tokens. */
        int pageSize = 2;
        /** Provider quirk: "dir/" markers come back as Contents rows
         *  instead of CommonPrefixes. */
        volatile boolean markersAsContents;
        /** Provider quirk: the delimiter is ignored; raw keys list. */
        volatile boolean ignoreDelimiter;
        /** Provider quirk (Selectel-shaped): folder markers land bare,
         *  stamped application/directory. */
        volatile boolean slashStrippingMarkers;
        /** Per-key content types; absent means the plain-upload default. */
        final Map<String, String> contentTypes = new ConcurrentHashMap<>();
        volatile int requests;
        volatile int rejectedSignatures;
        volatile int tokensIssued;
        volatile String lastRangeHeader;
        volatile String lastCopySource;

        FakeS3() throws IOException {
            put("media", "readme.md", "hello s3".getBytes(StandardCharsets.UTF_8));
            put("media", "docs/guide.txt", "guide".getBytes(StandardCharsets.UTF_8));
            put("media", "logs/2024/a.log", "aaa".getBytes(StandardCharsets.UTF_8));
            put("media", "logs/2024/b.log", "bbb".getBytes(StandardCharsets.UTF_8));
            put("media", "отчёт 2024.txt", "report".getBytes(StandardCharsets.UTF_8));
            put("media", "empty/", new byte[0]);
            put("archive", "old.txt", "old".getBytes(StandardCharsets.UTF_8));
            // The conformant folder-shadow shape: a zero-byte "notes" key
            // next to a live "notes/" prefix — spec-legal, and the reason
            // naive listings show size-0 "files" named like folders.
            put("quirks", "notes", new byte[0]);
            put("quirks", "notes/a.txt", "a".getBytes(StandardCharsets.UTF_8));
            put("quirks", "empty-file", new byte[0]);
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(pool);
            server.createContext("/", this::handle);
        }

        private void put(String bucket, String key, byte[] data) {
            buckets.computeIfAbsent(bucket, b -> new TreeMap<>()).put(key, data);
            mtimes.put(bucket + "/" + key, MTIME);
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
                if (!signatureOk(x, body)) {
                    rejectedSignatures++;
                    respondError(x, 403, "SignatureDoesNotMatch",
                            "The request signature we calculated does not match yours.");
                    return;
                }
                String path = URLDecoder.decode(
                        x.getRequestURI().getRawPath(), StandardCharsets.UTF_8);
                if (path.equals("/")) {
                    listBuckets(x);
                    return;
                }
                int cut = path.indexOf('/', 1);
                String bucket = cut < 0 ? path.substring(1) : path.substring(1, cut);
                String key = cut < 0 ? "" : path.substring(cut + 1);
                switch (x.getRequestMethod()) {
                    case "GET" -> {
                        if (key.isEmpty()) listObjects(x, bucket, queryParams(x));
                        else getObject(x, bucket, key);
                    }
                    case "PUT" -> putObject(x, bucket, key, body);
                    case "DELETE" -> deleteObject(x, bucket, key);
                    default -> respondError(x, 405, "MethodNotAllowed", "unsupported");
                }
            } catch (Exception e) {
                respondError(x, 500, "InternalError", String.valueOf(e));
            } finally {
                x.close();
            }
        }

        // -- the independent SigV4 verifier --

        private boolean signatureOk(HttpExchange x, byte[] body) throws Exception {
            requests++;
            String auth = x.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.startsWith("AWS4-HMAC-SHA256 ")) return false;
            String credential = part(auth, "Credential=");
            String signedHeaders = part(auth, "SignedHeaders=");
            String signature = part(auth, "Signature=");
            String[] c = credential.split("/");
            if (c.length != 5 || !c[0].equals(ACCESS)) return false;
            String date = c[1], region = c[2];
            String claimed = x.getRequestHeaders().getFirst("x-amz-content-sha256");
            if (claimed == null || !claimed.equals(hex(sha256(body)))) {
                return false;    // the signed hash must describe the real body
            }
            String rawQuery = x.getRequestURI().getRawQuery();
            String canonicalQuery = "";
            if (rawQuery != null && !rawQuery.isEmpty()) {
                String[] pairs = rawQuery.split("&");
                Arrays.sort(pairs);
                canonicalQuery = String.join("&", pairs);
            }
            StringBuilder canonicalHeaders = new StringBuilder();
            for (String h : signedHeaders.split(";")) {
                String v = x.getRequestHeaders().getFirst(h);
                canonicalHeaders.append(h).append(':')
                        .append(v == null ? "" : v.trim()).append('\n');
            }
            String canonical = x.getRequestMethod() + "\n"
                    + x.getRequestURI().getRawPath() + "\n"
                    + canonicalQuery + "\n"
                    + canonicalHeaders + "\n"
                    + signedHeaders + "\n"
                    + claimed;
            String amzDate = x.getRequestHeaders().getFirst("x-amz-date");
            String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n"
                    + date + "/" + region + "/s3/aws4_request\n"
                    + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
            byte[] k = hmac(("AWS4" + SECRET_TEXT).getBytes(StandardCharsets.UTF_8), date);
            k = hmac(k, region);
            k = hmac(k, "s3");
            k = hmac(k, "aws4_request");
            return hex(hmac(k, stringToSign)).equals(signature);
        }

        private static String part(String auth, String prefix) {
            for (String piece : auth.substring("AWS4-HMAC-SHA256 ".length()).split(",")) {
                if (piece.trim().startsWith(prefix)) {
                    return piece.trim().substring(prefix.length());
                }
            }
            return "";
        }

        // -- the S3 surface --

        private void listBuckets(HttpExchange x) throws IOException {
            StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<ListAllMyBucketsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                    + "<Owner><ID>dock</ID><DisplayName>dock</DisplayName></Owner><Buckets>");
            for (String bucket : new TreeSet<>(buckets.keySet())) {
                xml.append("<Bucket><Name>").append(bucket).append("</Name>")
                        .append("<CreationDate>").append(iso(MTIME)).append("</CreationDate>")
                        .append("</Bucket>");
            }
            xml.append("</Buckets></ListAllMyBucketsResult>");
            respond(x, 200, "application/xml", xml.toString());
        }

        private void listObjects(HttpExchange x, String bucket, Map<String, String> q)
                throws IOException {
            TreeMap<String, byte[]> objects = buckets.get(bucket);
            if (objects == null) {
                respondError(x, 404, "NoSuchBucket", "The specified bucket does not exist");
                return;
            }
            String prefix = q.getOrDefault("prefix", "");
            boolean delimited = q.containsKey("delimiter") && !ignoreDelimiter;
            TreeSet<String> entries = new TreeSet<>();
            for (String key : objects.keySet()) {
                if (!key.startsWith(prefix)) continue;
                String rest = key.substring(prefix.length());
                if (rest.isEmpty()) continue;    // the listing's own marker
                int slash = delimited ? rest.indexOf('/') : -1;
                entries.add(slash >= 0 ? prefix + rest.substring(0, slash + 1) : key);
            }
            String after = null;
            if (q.containsKey("continuation-token")) {
                after = new String(Base64.getUrlDecoder().decode(q.get("continuation-token")),
                        StandardCharsets.UTF_8);
            }
            List<String> page = new ArrayList<>();
            for (String e : entries) {
                if (after != null && e.compareTo(after) <= 0) continue;
                page.add(e);
            }
            int cap = Math.min(pageSize, Integer.parseInt(q.getOrDefault("max-keys", "1000")));
            boolean truncated = page.size() > cap;
            StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">");
            List<String> shown = page.subList(0, Math.min(cap, page.size()));
            for (String e : shown) {
                if (e.endsWith("/") && !markersAsContents) {
                    xml.append("<CommonPrefixes><Prefix>").append(e).append("</Prefix></CommonPrefixes>");
                } else {
                    // A marker emitted as Contents (or a raw key from a
                    // delimiter-ignoring server) is exactly the shape the
                    // client must still fold into a directory row.
                    byte[] data = objects.getOrDefault(e, new byte[0]);
                    xml.append("<Contents><Key>").append(e).append("</Key><LastModified>")
                            .append(iso(mtimes.getOrDefault(bucket + "/" + e, 0L)))
                            .append("</LastModified><Size>")
                            .append(data.length)
                            .append("</Size></Contents>");
                }
            }
            xml.append("<IsTruncated>").append(truncated).append("</IsTruncated>");
            if (truncated) {
                tokensIssued++;
                xml.append("<NextContinuationToken>")
                        .append(Base64.getUrlEncoder().encodeToString(
                                shown.get(shown.size() - 1).getBytes(StandardCharsets.UTF_8)))
                        .append("</NextContinuationToken>");
            }
            xml.append("</ListBucketResult>");
            respond(x, 200, "application/xml", xml.toString());
        }

        private void getObject(HttpExchange x, String bucket, String key) throws IOException {
            TreeMap<String, byte[]> objects = buckets.get(bucket);
            byte[] data = objects == null ? null : objects.get(key);
            if (data == null) {
                respondError(x, 404, "NoSuchKey", "The specified key does not exist.");
                return;
            }
            String range = x.getRequestHeaders().getFirst("Range");
            lastRangeHeader = range;
            String type = contentTypes.get(bucket + "/" + key);
            x.getResponseHeaders().set("Last-Modified", rfc1123(
                    mtimes.getOrDefault(bucket + "/" + key, 0L)));
            if (range != null && range.matches("bytes=\\d+(-\\d*)?")) {
                String spec = range.substring("bytes=".length());
                int dash = spec.indexOf('-');
                int from = Integer.parseInt(spec.substring(0, dash));
                int to = dash + 1 < spec.length()
                        ? Integer.parseInt(spec.substring(dash + 1)) + 1 : data.length;
                if (from >= data.length) {
                    // Any range of a zero-byte object is out of range — and
                    // that is exactly how the client's stat probe detects
                    // empty objects, and where a stripped marker shows its
                    // application/directory stamp.
                    x.getResponseHeaders().set("Content-Range",
                            "bytes */" + data.length);
                    respond(x, 416, type != null ? type : "application/xml", "");
                    return;
                }
                x.getResponseHeaders().set("Content-Range",
                        "bytes " + from + "-" + (Math.min(to, data.length) - 1) + "/" + data.length);
                respond(x, 206, type != null ? type : "application/octet-stream",
                        Arrays.copyOfRange(data, from, Math.min(to, data.length)));
            } else {
                respond(x, 200, type != null ? type : "application/octet-stream", data);
            }
        }

        private void putObject(HttpExchange x, String bucket, String key, byte[] body)
                throws IOException {
            TreeMap<String, byte[]> objects = buckets.get(bucket);
            if (objects == null) {
                respondError(x, 404, "NoSuchBucket", "The specified bucket does not exist");
                return;
            }
            String copySource = x.getRequestHeaders().getFirst("x-amz-copy-source");
            if (copySource != null) {
                lastCopySource = copySource;
                String src = URLDecoder.decode(copySource, StandardCharsets.UTF_8);
                int cut = src.indexOf('/', 1);
                String srcBucket = cut < 0 ? src.substring(1) : src.substring(1, cut);
                String srcKey = cut < 0 ? "" : src.substring(cut + 1);
                byte[] data = buckets.get(srcBucket) == null ? null
                        : buckets.get(srcBucket).get(srcKey);
                if (data == null) {
                    respondError(x, 404, "NoSuchKey", "The copy source does not exist.");
                    return;
                }
                objects.put(key, data.clone());
                mtimes.put(bucket + "/" + key, mtimes.get(srcBucket + "/" + srcKey));
                respond(x, 200, "application/xml",
                        "<CopyObjectResult><LastModified>" + iso(MTIME) + "</LastModified></CopyObjectResult>");
                return;
            }
            if (x.getRequestHeaders().getFirst("Content-Length") == null) {
                // Real S3 refuses chunked PUTs; the client must send a length.
                respondError(x, 400, "InvalidRequest", "Content-Length is required");
                return;
            }
            if (slashStrippingMarkers && key.endsWith("/") && body.length == 0) {
                // The Selectel shape: the marker lands bare, stamped
                // application/directory instead of keeping its slash.
                String bare = key.substring(0, key.length() - 1);
                objects.put(bare, body);
                mtimes.put(bucket + "/" + bare, System.currentTimeMillis());
                contentTypes.put(bucket + "/" + bare, "application/directory");
                respond(x, 200, "application/xml", "<PutResult/>");
                return;
            }
            objects.put(key, body);
            mtimes.put(bucket + "/" + key, System.currentTimeMillis());
            respond(x, 200, "application/xml", "<PutResult/>");
        }

        private void deleteObject(HttpExchange x, String bucket, String key) throws IOException {
            TreeMap<String, byte[]> objects = buckets.get(bucket);
            if (objects == null) {
                respondError(x, 404, "NoSuchBucket", "The specified bucket does not exist");
                return;
            }
            if (objects.remove(key) != null) {
                mtimes.remove(bucket + "/" + key);
                contentTypes.remove(bucket + "/" + key);
                respond(x, 204, null, "");    // the AWS verdict: gone
            } else {
                // AWS 204s a missing key; gateway-shaped stores 404 —
                // clients have to survive either.
                respondError(x, 404, "NoSuchKey", "The specified key does not exist.");
            }
        }

        // -- plumbing --

        private static Map<String, String> queryParams(com.sun.net.httpserver.HttpExchange x) {
            String raw = x.getRequestURI().getRawQuery();
            if (raw == null || raw.isEmpty()) return Map.of();
            Map<String, String> q = new TreeMap<>();
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                String name = URLDecoder.decode(
                        eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1),
                        StandardCharsets.UTF_8);
                q.put(name, value);
            }
            return q;
        }

        private static String iso(long millis) {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(millis));
        }

        private static String rfc1123(long millis) {
            return DateTimeFormatter.RFC_1123_DATE_TIME
                    .format(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC));
        }

        private static byte[] sha256(byte[] data) throws Exception {
            return MessageDigest.getInstance("SHA-256").digest(data);
        }

        private static byte[] hmac(byte[] key, String data) throws Exception {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        }

        private static String hex(byte[] bytes) {
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append("0123456789abcdef".charAt((b >> 4) & 0xF));
                sb.append("0123456789abcdef".charAt(b & 0xF));
            }
            return sb.toString();
        }

        private static void respond(HttpExchange x, int code, String type, String body)
                throws IOException {
            respond(x, code, type, body.getBytes(StandardCharsets.UTF_8));
        }

        private static void respond(HttpExchange x, int code, String type, byte[] bytes)
                throws IOException {
            if (type != null) x.getResponseHeaders().set("Content-Type", type);
            x.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) x.getResponseBody().write(bytes);
        }

        private static void respondError(HttpExchange x, int code, String error, String message)
                throws IOException {
            respond(x, code, "application/xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Error><Code>" + error
                            + "</Code><Message>" + message + "</Message></Error>");
        }
    }
}
