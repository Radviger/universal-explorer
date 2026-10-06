package dock.s3;

import dock.core.config.AppPaths;
import dock.core.fs.FileEntry;
import dock.core.fs.FileSystem;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * S3 filesystem over one JDK HttpClient, every request signed with AWS
 * SigV4 (see {@link SigV4}). The pane root "/" is the endpoint itself:
 * it lists the buckets (rows flagged {@code share}, like SMB's share
 * list); "/Bucket/prefix/…" is key space. Directories are not objects —
 * they are prefixes, optionally pinned by a zero-byte marker key that
 * {@link #mkdir} writes so empty directories stay visible. AWS keeps the
 * marker at "path/"; some gateways (Swift-backed ones notably) strip the
 * slash and stamp the object {@code application/directory} instead —
 * every one of those shapes folds into the same directory row.
 *
 * <p>Addressing is path-style ({@code host/bucket/key}) everywhere: it
 * works on AWS and every S3-compatible endpoint (MinIO, R2, B2, Wasabi)
 * without per-provider DNS games.
 */
public final class S3Fs implements FileSystem {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient http;
    private final S3Sessions.S3Spec spec;
    /** host[:port] exactly as the Host header signs it. */
    private final String authority;
    private final String scheme;
    private final String label;
    private final String homePath;

    S3Fs(HttpClient http, S3Sessions.S3Spec spec) {
        this.http = http;
        this.spec = spec;
        this.scheme = spec.secure() ? "https" : "http";
        int dflt = spec.secure() ? 443 : 80;
        this.authority = spec.host().toLowerCase()
                + (spec.port() == dflt ? "" : ":" + spec.port());
        this.label = "s3://" + spec.host() + (spec.port() == dflt ? "" : ":" + spec.port());
        this.homePath = spec.bucket() == null ? "/" : "/" + spec.bucket();
    }

    @Override public String label() { return label; }
    @Override public boolean remote() { return true; }
    @Override public String separator() { return "/"; }
    @Override public List<String> roots() { return List.of("/"); }
    @Override public String home() { return homePath; }

    @Override public String normalize(String path) { return S3Paths.normalize(path); }
    @Override public String parent(String path) { return S3Paths.parent(path); }
    @Override public String child(String dir, String name) { return S3Paths.child(dir, name); }

    /** The endpoint root is the bucket list — virtual rows that no
     *  new/rename/delete could touch and a directory that receives
     *  nothing. */
    @Override public boolean immutableListing(String path) {
        return S3Paths.isRoot(S3Paths.normalize(path));
    }

    // ---- wire core ----

    /**
     * Signs and sends one request. Non-2xx responses become readable
     * IOExceptions; the body stream is the caller's to drain or read.
     */
    private HttpResponse<InputStream> send(String method, String rawPath,
            Map<String, String> query, Map<String, String> extraHeaders,
            HttpRequest.BodyPublisher body, String payloadHash, Duration timeout)
            throws IOException {
        HttpResponse<InputStream> r = sendRaw(
                method, rawPath, query, extraHeaders, body, payloadHash, timeout);
        if (r.statusCode() / 100 != 2) throw failed(rawPath, r);
        return r;
    }

    private HttpResponse<InputStream> sendRaw(String method, String rawPath,
            Map<String, String> query, Map<String, String> extraHeaders,
            HttpRequest.BodyPublisher body, String payloadHash, Duration timeout)
            throws IOException {
        Map<String, String> toSign = new LinkedHashMap<>();
        toSign.put("host", authority);
        if (extraHeaders != null) toSign.putAll(extraHeaders);
        // Temporary credentials (STS) prove themselves with a signed token.
        if (spec.sessionToken() != null) toSign.put("x-amz-security-token", spec.sessionToken());
        String amzDate = SigV4.amzDate(Instant.now());
        String authorization = SigV4.authorization(method, rawPath, query, toSign,
                payloadHash, spec.accessKey(), spec.secretKey(), spec.region(), amzDate);
        StringBuilder url = new StringBuilder(scheme).append("://").append(authority)
                .append(SigV4.encodedPath(rawPath));
        String q = SigV4.canonicalQuery(query);
        if (!q.isEmpty()) url.append('?').append(q);
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url.toString()))
                .header("x-amz-date", amzDate)
                .header("x-amz-content-sha256", payloadHash)
                .header("Authorization", authorization);
        if (timeout != null) rb.timeout(timeout);
        if (spec.sessionToken() != null) rb.header("x-amz-security-token", spec.sessionToken());
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                rb.header(e.getKey(), e.getValue());
            }
        }
        HttpRequest request = rb.method(method,
                body == null ? HttpRequest.BodyPublishers.noBody() : body).build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.io.InterruptedIOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for " + label + ".", e);
        }
    }

    /** Reads a response body to its end so the connection returns to the pool. */
    private static void drain(HttpResponse<InputStream> r) throws IOException {
        try (InputStream in = r.body()) {
            in.readAllBytes();
        }
    }

    /** Turns an error response into one actionable sentence. */
    private IOException failed(String path, HttpResponse<InputStream> r) {
        String code = null, message = null, region = null;
        try (InputStream in = r.body()) {
            Document doc = parse(in);
            code = text(doc, "Code");
            message = text(doc, "Message");
            region = text(doc, "Region");
        } catch (Exception ignored) {
            // An unparseable error body must not mask the status verdict.
        }
        String detail = message == null || message.isBlank() ? "" : " (" + message + ")";
        String what = switch (r.statusCode()) {
            case 301 -> "The endpoint redirected this request — check the region (301)";
            case 400 -> "AuthorizationHeaderMalformed".equals(code) && region != null
                    ? "Wrong region — the server suggests " + region
                    : "The server rejected the request (400)";
            case 403 -> "SignatureDoesNotMatch".equals(code) || "InvalidAccessKeyId".equals(code)
                    ? "Authentication failed — check the access key and secret"
                    : "Access was refused (403)";
            case 404 -> "Not found";
            default -> "Server error " + r.statusCode();
        };
        return new IOException(what + ": " + path + detail);
    }

    // ---- listing ----

    @Override public List<FileEntry> list(String path) throws IOException {
        String p = S3Paths.normalize(path);
        return S3Paths.isRoot(p) ? listBuckets() : listObjects(S3Paths.bucketOf(p), S3Paths.restOf(p));
    }

    private List<FileEntry> listBuckets() throws IOException {
        HttpResponse<InputStream> r = send("GET", "/", Map.of(), null, null,
                SigV4.EMPTY_HASH, TIMEOUT);
        List<FileEntry> out = new ArrayList<>();
        try (InputStream in = r.body()) {
            for (Element b : elements(parse(in), "Bucket")) {
                out.add(new FileEntry(text(b, "Name"), true, 0,
                        epoch(text(b, "CreationDate")), null, false, true));
            }
        }
        return out;
    }

    /** One bucket's rows at one prefix, all pages of them. */
    private List<FileEntry> listObjects(String bucket, String rest) throws IOException {
        String prefix = rest.isEmpty() ? "" : rest + "/";
        List<FileEntry> out = new ArrayList<>();
        // Directory names implied by any page — folder markers can trail
        // the shadow file they pair with across a pagination boundary.
        Set<String> dirNames = new HashSet<>();
        Set<String> dirRows = new HashSet<>();
        String token = null;
        int pages = 0;
        do {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("list-type", "2");
            q.put("prefix", prefix);
            q.put("delimiter", "/");
            q.put("max-keys", "1000");
            if (token != null) q.put("continuation-token", token);
            Document doc;
            HttpResponse<InputStream> r = send("GET", "/" + bucket, q, null, null,
                    SigV4.EMPTY_HASH, TIMEOUT);
            try (InputStream in = r.body()) {
                doc = parse(in);
            }
            org.w3c.dom.NodeList children = doc.getDocumentElement().getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (!(children.item(i) instanceof Element el)) continue;
                String dir = dirNameOf(el, prefix);
                if (dir != null) {
                    // Rows stream out in the server's key order (files and
                    // prefixes interleaved), so the pane sees S3's own
                    // ordering; one row per directory regardless of how
                    // many shapes hinted at it.
                    dirNames.add(dir);
                    if (dirRows.add(dir)) {
                        out.add(new FileEntry(dir, true, 0, 0, null, false));
                    }
                    continue;
                }
                if (!"Contents".equals(el.getNodeName())) continue;
                String key = text(el, "Key");
                if (key.equals(prefix)) continue;    // this directory's own marker
                out.add(new FileEntry(key.substring(prefix.length()), false,
                        Long.parseLong(text(el, "Size")),
                        epoch(text(el, "LastModified")), null, false));
            }
            boolean more = "true".equalsIgnoreCase(text(doc, "IsTruncated"));
            token = more ? text(doc, "NextContinuationToken") : null;
            if (more && token == null) {
                throw new IOException("Pagination stalled on " + label + ".");
            }
        } while (token != null && ++pages < 10_000);
        // The folder-shadow sweep: a zero-byte object sharing its name with
        // a live directory is the provider's folder marker, not data.
        out.removeIf(e -> !e.directory() && e.size() == 0 && dirNames.contains(e.name()));
        resolveStrippedMarkers(bucket, prefix, out, dirNames);
        return out;
    }

    /**
     * Settles the one shape the listing cannot: a zero-byte key with no
     * live prefix is either a genuine empty file or a provider's folder
     * marker — some gateways strip the marker's trailing slash, so an
     * empty folder arrives as a bare size-0 row. The object's
     * content-type says which: markers are stamped
     * {@code application/directory}, uploads never are. One ranged GET
     * per suspect, eight in flight; suspects sharing a name with a live
     * directory ({@code dirNames}) already folded and never probe.
     */
    private void resolveStrippedMarkers(String bucket, String prefix,
            List<FileEntry> out, Set<String> dirNames) throws IOException {
        Map<Integer, String> suspects = new LinkedHashMap<>();
        for (int i = 0; i < out.size(); i++) {
            FileEntry e = out.get(i);
            if (!e.directory() && e.size() == 0 && !dirNames.contains(e.name())) {
                suspects.put(i, e.name());
            }
        }
        if (suspects.isEmpty()) return;
        Semaphore lanes = new Semaphore(8);
        List<Future<ObjectInfo>> verdicts = new ArrayList<>();
        // close() waits for the probes; an interrupt inside it re-sets the
        // flag and the get() below turns it into the listing's failure.
        try (ExecutorService probes = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String name : suspects.values()) {
                verdicts.add(probes.submit(() -> {
                    lanes.acquire();
                    try {
                        return probeObject("/" + bucket + "/" + prefix + name);
                    } finally {
                        lanes.release();
                    }
                }));
            }
        }
        int next = 0;
        for (Map.Entry<Integer, String> suspect : suspects.entrySet()) {
            ObjectInfo info;
            try {
                info = verdicts.get(next++).get();
            } catch (ExecutionException e) {
                throw e.getCause() instanceof IOException failure ? failure
                        : new IOException("Folder marker probe failed on " + label + ".", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted listing " + label + ".", e);
            }
            if (info != null && info.directoryType()) {
                out.set(suspect.getKey(),
                        new FileEntry(suspect.getValue(), true, 0, info.mtime(), null, false));
            }
        }
    }

    /**
     * The directory a listing element represents, or null when it is a
     * genuine file. CommonPrefixes always names one; because
     * S3-compatible providers differ on folder markers, a Contents key
     * that ends with the delimiter or carries one beyond the prefix is
     * also read as a directory (some return their "dir/" markers as
     * Contents rows, and some ignore the delimiter outright).
     */
    private static String dirNameOf(Element el, String prefix) {
        String raw;
        if ("CommonPrefixes".equals(el.getNodeName())) {
            raw = text(el, "Prefix");
        } else if ("Contents".equals(el.getNodeName())) {
            raw = text(el, "Key");
        } else {
            return null;
        }
        if (raw == null || !raw.startsWith(prefix) || raw.length() <= prefix.length()) {
            return null;
        }
        String rest = raw.substring(prefix.length());
        int slash = rest.indexOf('/');
        return slash < 0 ? null : rest.substring(0, slash);
    }

    // ---- metadata ----

    @Override public FileEntry stat(String path) throws IOException {
        String p = S3Paths.normalize(path);
        if (S3Paths.isRoot(p)) {
            return new FileEntry(spec.host(), true, 0, 0, null, false);
        }
        String bucket = S3Paths.bucketOf(p);
        String rest = S3Paths.restOf(p);
        if (rest.isEmpty()) {
            if (!bucketExists(bucket)) throw new IOException("Not found: " + p);
            return new FileEntry(bucket, true, 0, 0, null, false, true);
        }
        ObjectInfo file = probeObject("/" + bucket + "/" + rest);
        if (file != null) {
            // A zero-byte object is the provider's folder marker — not
            // data — when a live prefix shares its name or the object is
            // stamped application/directory; present it as the directory
            // the listing shows, so stat and the pane never disagree.
            if (file.size() == 0 && (file.directoryType() || prefixHasEntries(bucket, rest))) {
                return new FileEntry(nameOf(p), true, 0, file.mtime(), null, false);
            }
            return new FileEntry(nameOf(p), false, file.size(), file.mtime(), null, false);
        }
        ObjectInfo marker = probeObject("/" + bucket + "/" + rest + "/");
        if (marker != null) {
            return new FileEntry(nameOf(p), true, 0, marker.mtime(), null, false);
        }
        if (prefixHasEntries(bucket, rest)) {
            return new FileEntry(nameOf(p), true, 0, 0, null, false);
        }
        throw new IOException("Not found: " + p);
    }

    @Override public boolean exists(String path) throws IOException {
        String p = S3Paths.normalize(path);
        if (S3Paths.isRoot(p)) return true;
        try {
            stat(p);
            return true;
        } catch (IOException e) {
            if (missing(e)) return false;
            throw e;
        }
    }

    private static boolean missing(IOException e) {
        return e.getMessage() != null && e.getMessage().startsWith("Not found");
    }

    private record ObjectInfo(long size, long mtime, boolean directoryType) {}

    /**
     * One object's size and mtime via a one-byte ranged GET: 206 carries
     * the total in {@code Content-Range}, 416 means the object exists but
     * is empty (any range of a zero-byte object is out of range), 404
     * means absent. The single byte rides the same Range machinery
     * transfers resume on.
     */
    private ObjectInfo probeObject(String rawPath) throws IOException {
        HttpResponse<InputStream> r = sendRaw("GET", rawPath, Map.of(),
                Map.of("range", "bytes=0-0"), null, SigV4.EMPTY_HASH, TIMEOUT);
        return switch (r.statusCode()) {
            case 206, 416 -> {
                drain(r);
                long size = 0;
                String range = r.headers().firstValue("Content-Range").orElse("");
                int slash = range.lastIndexOf('/');
                if (r.statusCode() == 206 && slash >= 0) {
                    size = Long.parseLong(range.substring(slash + 1).trim());
                }
                yield new ObjectInfo(size, mtimeOf(r), directoryType(r));
            }
            case 404 -> {
                drain(r);
                yield null;
            }
            default -> throw failed(rawPath, r);
        };
    }

    private static long mtimeOf(HttpResponse<InputStream> r) {
        String v = r.headers().firstValue("Last-Modified").orElse("");
        if (v.isBlank()) return 0;
        try {
            return Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(v)).toEpochMilli();
        } catch (Exception e) {
            try {
                return Instant.parse(v).toEpochMilli();
            } catch (Exception e2) {
                return 0;
            }
        }
    }

    /**
     * The folder-marker stamp: gateways that strip the marker's trailing
     * slash mark it {@code application/directory} (or the older
     * {@code application/x-directory}); no upload ever carries one.
     */
    private static boolean directoryType(HttpResponse<InputStream> r) {
        String v = r.headers().firstValue("content-type").orElse("");
        int cut = v.indexOf(';');
        String base = (cut < 0 ? v : v.substring(0, cut)).trim().toLowerCase();
        return base.equals("application/directory")
                || base.equals("application/x-directory");
    }

    private boolean bucketExists(String bucket) throws IOException {
        HttpResponse<InputStream> r = sendRaw("GET", "/" + bucket,
                Map.of("list-type", "2", "max-keys", "1"), null, null,
                SigV4.EMPTY_HASH, TIMEOUT);
        boolean ok = r.statusCode() / 100 == 2;
        if (!ok && r.statusCode() != 404) throw failed("/" + bucket, r);
        drain(r);
        return ok;
    }

    /** True when any key lives below this prefix — an implicit directory. */
    private boolean prefixHasEntries(String bucket, String rest) throws IOException {
        HttpResponse<InputStream> r = send("GET", "/" + bucket,
                Map.of("list-type", "2", "prefix", rest + "/", "max-keys", "1"),
                null, null, SigV4.EMPTY_HASH, TIMEOUT);
        boolean any;
        try (InputStream in = r.body()) {
            Document doc = parse(in);
            any = !elements(doc, "Contents").isEmpty()
                    || !elements(doc, "CommonPrefixes").isEmpty();
        }
        return any;
    }

    // ---- mutations ----

    @Override public void mkdir(String path) throws IOException {
        String p = S3Paths.normalize(path);
        String bucket = S3Paths.bucketOf(p);
        String rest = S3Paths.restOf(p);
        if (bucket == null) {
            throw new IOException("Cannot create directories at the endpoint root; "
                    + "open a bucket first.");
        }
        if (rest.isEmpty()) {
            throw new IOException("Cannot create a bucket from here.");
        }
        // A zero-byte "prefix/" marker key: S3 has no directory objects,
        // and without the marker an empty directory would be invisible.
        drain(send("PUT", "/" + bucket + "/" + rest + "/", Map.of(), null, null,
                SigV4.EMPTY_HASH, TIMEOUT));
    }

    @Override public void delete(String path) throws IOException {
        String p = S3Paths.normalize(path);
        String bucket = S3Paths.bucketOf(p);
        String rest = S3Paths.restOf(p);
        if (bucket == null || rest == null || rest.isEmpty()) {
            throw new IOException("Cannot delete a bucket from here.");
        }
        // A silent marker delete would strand the children of an implicit
        // directory, so non-empty is refused — the same contract SMB and
        // WebDAV enforce; deleteTree empties it first.
        boolean dir;
        try {
            dir = stat(p).directory();
        } catch (IOException e) {
            // S3 deletes are idempotent — an already-gone key (or an
            // implicit directory that deleteTree just emptied) is done,
            // exactly as the protocol's own 204-for-missing would be.
            if (missing(e)) return;
            throw e;
        }
        if (dir && !list(p).isEmpty()) {
            throw new IOException("Directory not empty: " + p);
        }
        String key = "/" + bucket + "/" + rest;
        if (!dir) {
            drain(send("DELETE", key, Map.of(), null, null, SigV4.EMPTY_HASH, TIMEOUT));
            return;
        }
        // The marker sits at "rest/" on AWS-shaped stores and bare on the
        // slash-stripping ones; take down whichever this provider kept.
        HttpResponse<InputStream> r = sendRaw("DELETE", key + "/", Map.of(), null, null,
                SigV4.EMPTY_HASH, TIMEOUT);
        if (r.statusCode() == 404) {
            drain(r);
            drain(send("DELETE", key, Map.of(), null, null, SigV4.EMPTY_HASH, TIMEOUT));
        } else if (r.statusCode() / 100 != 2) {
            throw failed(key + "/", r);
        } else {
            drain(r);
        }
    }

    @Override public void rename(String from, String to) throws IOException {
        String f = S3Paths.normalize(from);
        String t = S3Paths.normalize(to);
        String bucket = S3Paths.bucketOf(f);
        String rest = S3Paths.restOf(f);
        if (bucket == null || rest == null || rest.isEmpty()) {
            throw new IOException("Cannot rename a bucket from here.");
        }
        if (!bucket.equals(S3Paths.bucketOf(t))) {
            throw new IOException("Renaming across buckets is a transfer, not a rename.");
        }
        if (stat(f).directory()) {
            throw new IOException("S3 has no directory rename — it stores only keys; "
                    + "copy the contents over and delete the original.");
        }
        String toRest = S3Paths.restOf(t);
        if (toRest == null || toRest.isEmpty()) {
            throw new IOException("Cannot rename onto a bucket root: " + t);
        }
        // Server-side copy, then remove the source: the bytes never leave
        // the provider.
        String copySource = "/" + bucket + "/" + SigV4.uriEncode(rest, true);
        drain(send("PUT", "/" + bucket + "/" + toRest, Map.of(),
                Map.of("x-amz-copy-source", copySource), null, SigV4.EMPTY_HASH, TIMEOUT));
        drain(send("DELETE", "/" + bucket + "/" + rest, Map.of(), null, null,
                SigV4.EMPTY_HASH, TIMEOUT));
    }

    // ---- streams ----

    @Override public InputStream read(String path) throws IOException {
        String p = requireFile(path);
        return send("GET", "/" + S3Paths.bucketOf(p) + "/" + S3Paths.restOf(p),
                Map.of(), null, null, SigV4.EMPTY_HASH, TIMEOUT).body();
    }

    /** Resume primitive: a single-byte-range GET instead of a skip-through. */
    @Override public InputStream read(String path, long offset) throws IOException {
        if (offset <= 0) return read(path);
        String p = requireFile(path);
        return send("GET", "/" + S3Paths.bucketOf(p) + "/" + S3Paths.restOf(p),
                Map.of(), Map.of("range", "bytes=" + offset + "-"), null,
                SigV4.EMPTY_HASH, TIMEOUT).body();
    }

    private String requireFile(String path) throws IOException {
        String p = S3Paths.normalize(path);
        String bucket = S3Paths.bucketOf(p);
        String rest = S3Paths.restOf(p);
        if (bucket == null || rest == null || rest.isEmpty()) {
            throw new IOException("Not a file: " + p);
        }
        return p;
    }

    /**
     * S3 PUTs whole objects and rejects chunked bodies, so the stream
     * lands in a temp file under the app cache and the upload runs at
     * {@code close()} — the server's verdict surfaces to the writer, the
     * WebDAV contract. The payload hash is computed while the bytes
     * stream through, so nothing is read twice. Objects top out at the
     * single-PUT limit (5 GB on AWS); multipart is a parked follow-up.
     */
    @Override public OutputStream write(String path, boolean append) throws IOException {
        if (append) throw new IOException("S3 replaces whole files — it cannot append.");
        String p = requireFile(path);
        String bucket = S3Paths.bucketOf(p);
        String rest = S3Paths.restOf(p);
        Path temp = Files.createTempFile(AppPaths.cache(), "dock-s3-", ".part");
        MessageDigest digest = SigV4.digest();
        DigestOutputStream both = new DigestOutputStream(
                Files.newOutputStream(temp, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING),
                digest);
        return new OutputStream() {
            @Override public void write(int b) throws IOException { both.write(b); }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                both.write(b, off, len);
            }
            @Override public void flush() throws IOException { both.flush(); }
            @Override public void close() throws IOException {
                try {
                    both.close();
                    drain(send("PUT", "/" + bucket + "/" + rest, Map.of(), null,
                            HttpRequest.BodyPublishers.ofFile(temp),
                            SigV4.hex(digest.digest()), null));
                } finally {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (IOException ignored) {
                        // The cache dir is swept on startup; the verdict matters more.
                    }
                }
            }
        };
    }

    /** S3 keeps its own object timestamps; nothing here is settable. */
    @Override public void setTimes(String path, long mtimeMillis) throws IOException {
        throw new UnsupportedOperationException("S3 keeps its own object timestamps.");
    }

    /** S3 has no POSIX mode bits; callers must treat this backend as fixed-mode. */
    @Override public void setPerms(String path, int posix) throws IOException {
        throw new UnsupportedOperationException("S3 has no POSIX permissions.");
    }

    // ---- lifecycle ----

    @Override public void close() {
        try {
            http.close();
        } catch (Exception ignored) {
            // Best-effort; the OS reclaims the sockets regardless.
        }
    }

    /** One stateless client serves browsing and transfers (HttpClient is thread-safe). */
    @Override public FileSystem streamView() { return this; }

    // ---- XML helpers ----

    private static Document parse(InputStream in) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return f.newDocumentBuilder().parse(in);
        } catch (Exception e) {
            throw new IOException("Malformed server XML.", e);
        }
    }

    private static List<Element> elements(Document doc, String tag) {
        NodeList nodes = doc.getElementsByTagName(tag);
        List<Element> out = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            out.add((Element) nodes.item(i));
        }
        return out;
    }

    private static String text(Document doc, String tag) {
        NodeList nodes = doc.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }

    private static long epoch(String iso) {
        if (iso == null || iso.isBlank()) return 0;
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String nameOf(String path) {
        int cut = path.lastIndexOf('/');
        return cut < 0 ? path : path.substring(cut + 1);
    }
}
