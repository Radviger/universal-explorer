package dock.s3;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4, S3's header flavor, as pure functions — no
 * wire, no clock, no state, so the published AWS examples pin every
 * branch (see {@code SigV4Test}). One encoder serves both the request
 * URI and the canonical request: the two can never disagree about a
 * key's encoding, which is the classic way hand-rolled SigV4 breaks
 * against real servers.
 */
public final class SigV4 {

    private SigV4() {}

    /** SHA-256 of the empty body — every bodyless S3 verb signs this. */
    public static final String EMPTY_HASH =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /**
     * The docs' UriEncode: unreserved bytes stay, everything else becomes
     * uppercase {@code %XX}, a space is {@code %20} (never {@code +}).
     * Slashes survive inside object-key paths but are encoded in query
     * values.
     */
    public static String uriEncode(String raw, boolean keepSlash) {
        StringBuilder sb = new StringBuilder();
        for (byte b : raw.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved || (keepSlash && c == '/')) {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append("0123456789ABCDEF".charAt(c >> 4));
                sb.append("0123456789ABCDEF".charAt(c & 0xF));
            }
        }
        return sb.toString();
    }

    /** Encoded path from a decoded one: each segment is encoded exactly
     *  once and the separating slashes are kept — S3 rejects double
     *  encoding and path normalization. */
    public static String encodedPath(String rawPath) {
        return uriEncode(rawPath, true);
    }

    /** The canonical query: pairs sorted (names then values), encoded,
     *  joined with {@code &}. The same string goes into the request URL,
     *  so both rides stay identical. */
    public static String canonicalQuery(Map<String, String> query) {
        TreeMap<String, String> sorted = new TreeMap<>(query);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(uriEncode(e.getKey(), false)).append('=')
                    .append(uriEncode(e.getValue(), false));
        }
        return sb.toString();
    }

    /** The {@code x-amz-date} stamp, e.g. {@code 20130524T000000Z}. */
    public static String amzDate(Instant now) {
        return DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC).format(now);
    }

    /** A fresh SHA-256 digest (the algorithm is guaranteed present). */
    public static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }

    /**
     * The Authorization header value for one request. {@code headersToSign}
     * must carry {@code host} and any extra headers this call signs
     * (a {@code range}, an {@code x-amz-copy-source}); the two
     * {@code x-amz-*} stamps are added here. Names are lowercased.
     */
    public static String authorization(String method, String rawPath, Map<String, String> query,
            Map<String, String> headersToSign, String payloadHash, String accessKey,
            char[] secret, String region, String amzDate) {
        TreeMap<String, String> headers = new TreeMap<>();
        for (Map.Entry<String, String> e : headersToSign.entrySet()) {
            headers.put(e.getKey().toLowerCase(), e.getValue());
        }
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", amzDate);
        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signed = new StringBuilder();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            canonicalHeaders.append(e.getKey()).append(':')
                    .append(e.getValue().trim()).append('\n');
            if (signed.length() > 0) signed.append(';');
            signed.append(e.getKey());
        }
        String canonical = method + '\n' + encodedPath(rawPath) + '\n' + canonicalQuery(query)
                + '\n' + canonicalHeaders + '\n' + signed + '\n' + payloadHash;
        String date = amzDate.substring(0, 8);
        String scope = date + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
        byte[] key = hmac(("AWS4" + new String(secret)).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, "s3");
        key = hmac(key, "aws4_request");
        return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                + ",SignedHeaders=" + signed + ",Signature=" + hex(hmac(key, stringToSign));
    }

    static byte[] sha256(byte[] data) {
        return digest().digest(data);
    }

    static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 is missing", e);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append("0123456789abcdef".charAt((b >> 4) & 0xF));
            sb.append("0123456789abcdef".charAt(b & 0xF));
        }
        return sb.toString();
    }
}
