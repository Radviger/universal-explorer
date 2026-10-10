package dock.media;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dock.core.fs.FileSystem;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The streaming seam between a {@link FileSystem} and a media engine that
 * only speaks URLs: a loopback HTTP server that answers range requests out
 * of the filesystem's positioned reads. Playback never downloads more than
 * it plays — a seek is one new ranged GET, and the bytes behind the play
 * head are the only ones that cross the wire (over SFTP a true seek, over
 * SMB one positioned READ per request, over WebDAV a Range GET).
 *
 * <p>Every request reads through its own private {@code streamView} —
 * engines keep several connections to one URL alive at once (VLC tails the
 * file for metadata while the main stream plays), so copies must not
 * serialize behind each other: a seek is answered even while the copy it
 * replaces is still writing to a client that stopped reading.
 *
 * <p>The server binds {@code 127.0.0.1} only, and every file is addressed
 * by an unguessable token that is revoked the moment its registration
 * closes — credentials never appear in a URL; the bridge holds the
 * filesystem itself.
 */
public final class MediaBridge {

    /** One served file: fixed metadata plus the copies currently in
     *  flight, so closing the registration can kill them mid-air. */
    private static final class Source {
        final FileSystem fs;
        final String path;
        final long size;
        final String contentType;
        final java.util.List<Flight> flights =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        Source(FileSystem fs, String path, long size, String contentType) {
            this.fs = fs;
            this.path = path;
            this.size = size;
            this.contentType = contentType;
        }
    }

    /** One in-flight body copy. Engines walk away from their old
     *  connections on every seek — often without closing them — so a
     *  stale copy may sit blocked on a write to nobody; it holds nothing
     *  shared, but closing the registration kills it outright instead of
     *  waiting out its buffers. */
    private record Flight(HttpExchange exchange,
                          AtomicReference<InputStream> in) {}

    /** Kills a copy wherever it is blocked — writing to the abandoned
     *  client (the exchange close breaks the socket) or reading the next
     *  buffer (the stream close breaks the read). */
    private static void abort(Flight f) {
        try { f.exchange().close(); } catch (Exception ignored) {}
        InputStream in = f.in().get();
        if (in != null) try { in.close(); } catch (Exception ignored) {}
    }

    /** A live token in the bridge — revoked on close. */
    public static final class Registration implements AutoCloseable {
        private final String token;
        private final String url;
        private final Source source;
        private volatile boolean closed;

        private Registration(String token, String url, Source source) {
            this.token = token;
            this.url = url;
            this.source = source;
        }

        /** The URL the media engine opens. */
        public String url() { return url; }

        @Override public void close() {
            if (closed) return;
            closed = true;
            SERVER.tokens.remove(token, source);
            // Copies may be mid-flight on connections nobody reads anymore.
            for (Flight f : source.flights) abort(f);
        }
    }

    private static final class Server {
        final HttpServer http;
        final Map<String, Source> tokens = new ConcurrentHashMap<>();

        Server() {
            try {
                http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException("could not start the media bridge", e);
            }
            // Requests run on virtual threads and share nothing: every
            // copy reads through its own private stream view, so several
            // connections to one file (VLC plays one while tailing it for
            // metadata) all stream at once.
            http.setExecutor(r -> Thread.ofVirtual().name("dock-media-http").start(r));
            http.createContext("/", MediaBridge::handle);
            http.start();
        }

        int port() { return http.getAddress().getPort(); }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile Server SERVER;

    private MediaBridge() {}

    /** Serves {@code path} on a fresh token until the registration closes. */
    public static Registration serve(FileSystem fs, String path) throws IOException {
        Server server = server();
        long size = fs.stat(path).size();
        String name = baseName(path);
        Source source = new Source(fs, path, size, contentType(name));
        String token = token();
        server.tokens.put(token, source);
        String encoded = URLEncode(name);
        return new Registration(token, "http://127.0.0.1:" + server.port()
                + "/" + token + "/" + encoded, source);
    }

    private static Server server() {
        Server s = SERVER;
        if (s == null) {
            synchronized (MediaBridge.class) {
                if (SERVER == null) SERVER = new Server();
                s = SERVER;
            }
        }
        return s;
    }

    // ---- request handling ----

    private static void handle(HttpExchange ex) throws IOException {
        try {
            String[] parts = ex.getRequestURI().getRawPath().split("/", 3);
            if (parts.length < 3) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            Source source = server().tokens.get(parts[1]);
            if (source == null) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            long[] range = parseRange(ex.getRequestHeaders().getFirst("Range"), source.size);
            if (range == null) {   // start past EOF: the one unsatisfiable case
                ex.getResponseHeaders().set("Content-Range",
                        "bytes */" + source.size);
                ex.sendResponseHeaders(416, -1);
                return;
            }
            long start = range[0], end = range[1];
            // 206 whenever the client asked by range — even one that spans
            // to EOF; 200 only for the un-ranged whole file.
            boolean ranged = range[2] == 1;
            long length = end - start + 1;
            ex.getResponseHeaders().set("Content-Type", source.contentType);
            ex.getResponseHeaders().set("Accept-Ranges", "bytes");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            if (ranged) ex.getResponseHeaders().set("Content-Range",
                    "bytes " + start + "-" + end + "/" + source.size);
            boolean head = "HEAD".equals(ex.getRequestMethod());
            if (head) {
                // No body follows, so the length must ride as a header —
                // engines probe size with HEAD before their first range.
                ex.getResponseHeaders().set("Content-Length", Long.toString(length));
                ex.sendResponseHeaders(ranged ? 206 : 200, -1);
                return;
            }
            if (length == 0) {
                ex.sendResponseHeaders(200, -1);
                return;
            }
            ex.sendResponseHeaders(ranged ? 206 : 200, length);
            if ("GET".equals(ex.getRequestMethod())) copy(source, start, length, ex);
        } catch (Exception ignored) {
            // A vanished token mid-response or a dropped client: nothing to
            // salvage — the exchange dies with the connection.
        } finally {
            ex.close();
        }
    }

    /** One positioned open on a private view, then exactly {@code length}
     *  bytes to the client. The view is this request's alone — nothing
     *  serializes copies, so a seek never waits on the connection it
     *  replaces (which may be parked on a client that stopped reading). */
    private static void copy(Source source, long start, long length, HttpExchange ex)
            throws IOException {
        Flight mine = new Flight(ex, new AtomicReference<>());
        source.flights.add(mine);
        long done = 0;
        try {
            FileSystem view = source.fs.streamView();
            try {
                try (InputStream in = view.read(source.path, start);
                     OutputStream out = ex.getResponseBody()) {
                    mine.in().set(in);
                    byte[] buf = new byte[64 * 1024];
                    while (done < length) {
                        int want = (int) Math.min(buf.length, length - done);
                        int n = in.read(buf, 0, want);
                        if (n < 0) break;   // the file shrank: serve what is there
                        out.write(buf, 0, n);
                        done += n;
                    }
                }
            } finally {
                if (view != source.fs) {
                    try { view.close(); } catch (Exception ignored) {}
                }
            }
        } catch (IOException | RuntimeException e) {
            if (done < length && DEBUG.getAsBoolean()) {
                System.err.printf(
                        "dock-media: copy of %s at %d stopped after %d of %d bytes: %s%n",
                        source.path, start, done, length, String.valueOf(e));
                e.printStackTrace(System.err);
            }
            throw e;
        } finally {
            source.flights.remove(mine);
        }
    }

    private static final java.util.function.BooleanSupplier DEBUG =
            () -> Boolean.getBoolean("dock.media.debug");

    /**
     * Parses a single {@code bytes=a-b} / {@code bytes=a-} / {@code bytes=-n}
     * range against {@code size}: {@code [start, end, 1]} for an explicit
     * range (206 territory, even one spanning to EOF), {@code [0, size-1, 0]}
     * when no usable range was asked (the whole file, 200), null when the
     * range is unsatisfiable. Only the first range of a list is honored —
     * no engine asks for more.
     */
    private static long[] parseRange(String header, long size) {
        if (header == null || !header.startsWith("bytes=") || size <= 0) {
            return new long[] {0, Math.max(-1, size - 1), 0};
        }
        String spec = header.substring("bytes=".length()).split(",")[0].trim();
        int dash = spec.indexOf('-');
        if (dash < 0) return new long[] {0, size - 1, 0};   // malformed: whole file
        String a = spec.substring(0, dash).trim();
        String b = spec.substring(dash + 1).trim();
        try {
            if (a.isEmpty()) {
                if (b.isEmpty()) return new long[] {0, size - 1, 0};
                long suffix = Long.parseUnsignedLong(b);
                if (suffix == 0) return null;
                long start = Math.max(0, size - suffix);
                return new long[] {start, size - 1, 1};
            }
            long start = Long.parseUnsignedLong(a);
            if (start >= size) return null;
            long end = b.isEmpty() ? size - 1
                    : Math.min(Long.parseUnsignedLong(b), size - 1);
            if (end < start) return new long[] {0, size - 1, 0};   // reversed: whole file
            return new long[] {start, end, 1};
        } catch (NumberFormatException e) {
            return new long[] {0, size - 1, 0};
        }
    }

    // ---- naming and types ----

    private static String token() {
        byte[] raw = new byte[18];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String baseName(String path) {
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return cut < 0 ? path : path.substring(cut + 1);
    }

    private static String URLEncode(String name) {
        return java.net.URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String decodeName(String raw) {
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    private static String contentType(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "mp4", "m4v", "mp4v" -> "video/mp4";
            case "mkv" -> "video/x-matroska";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            case "avi" -> "video/x-msvideo";
            case "wmv" -> "video/x-ms-wmv";
            case "flv" -> "video/x-flv";
            case "mpg", "mpeg", "m2v", "vob" -> "video/mpeg";
            case "3gp" -> "video/3gpp";
            case "ts", "m2ts" -> "video/mp2t";
            case "ogv" -> "video/ogg";
            case "asf" -> "video/x-ms-asf";
            case "divx" -> "video/divx";
            case "rm", "rmvb" -> "application/vnd.rn-realmedia";
            case "mp3" -> "audio/mpeg";
            case "flac" -> "audio/flac";
            case "wav" -> "audio/wav";
            case "ogg", "oga" -> "audio/ogg";
            case "opus" -> "audio/opus";
            case "m4a" -> "audio/mp4";
            case "aac" -> "audio/aac";
            case "wma" -> "audio/x-ms-wma";
            case "aiff", "aif", "aifc" -> "audio/aiff";
            case "amr" -> "audio/amr";
            case "awb" -> "audio/amr-wb";
            case "ape" -> "audio/x-ape";
            case "wv" -> "audio/x-wavpack";
            case "ac3" -> "audio/ac3";
            case "dts" -> "audio/vnd.dts";
            case "dsf" -> "audio/x-dsf";
            default -> "application/octet-stream";
        };
    }
}
