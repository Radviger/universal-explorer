package dock.s3;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The signer against the worked examples from AWS's "Authenticating
 * Requests (AWS Signature Version 4)" documentation — the same access
 * key, timestamps, headers, and expected signatures, verbatim. If these
 * pass, the canonicalization matches what real servers verify.
 */
class SigV4Test {

    private static final String DOC_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final char[] DOC_SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY".toCharArray();

    @Test
    void rangedGetMatchesThePublishedAwsExample() {
        String auth = SigV4.authorization("GET", "/test.txt", Map.of(),
                Map.of("host", "examplebucket.s3.amazonaws.com", "range", "bytes=0-9"),
                SigV4.EMPTY_HASH, DOC_KEY, DOC_SECRET, "us-east-1", "20130524T000000Z");
        assertEquals("AWS4-HMAC-SHA256 "
                + "Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"
                + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
                auth);
    }

    @Test
    void putWithAnEncodedKeyAndSignedExtrasMatchesThePublishedAwsExample() {
        String payloadHash = "44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072";
        String auth = SigV4.authorization("PUT", "/test$file.text", Map.of(),
                Map.of("date", "Fri, 24 May 2013 00:00:00 GMT",
                        "host", "examplebucket.s3.amazonaws.com",
                        "x-amz-storage-class", "REDUCED_REDUNDANCY"),
                payloadHash, DOC_KEY, DOC_SECRET, "us-east-1", "20130524T000000Z");
        assertEquals("AWS4-HMAC-SHA256 "
                + "Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                + "SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class,"
                + "Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd",
                auth);
    }

    @Test
    void listingWithQueryParametersMatchesThePublishedAwsExample() {
        String auth = SigV4.authorization("GET", "/", Map.of("max-keys", "2", "prefix", "J"),
                Map.of("host", "examplebucket.s3.amazonaws.com"),
                SigV4.EMPTY_HASH, DOC_KEY, DOC_SECRET, "us-east-1", "20130524T000000Z");
        assertEquals("AWS4-HMAC-SHA256 "
                + "Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                + "SignedHeaders=host;x-amz-content-sha256;x-amz-date,"
                + "Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7",
                auth);
    }

    @Test
    void theEncoderFollowsTheDocumentedUriRules() {
        assertEquals("a%20b", SigV4.uriEncode("a b", false), "space is %20, never +");
        assertEquals("%24", SigV4.uriEncode("$", true));
        assertEquals("~._-", SigV4.uriEncode("~._-", true), "unreserved bytes stay");
        assertEquals("a/b", SigV4.uriEncode("a/b", true), "key slashes survive");
        assertEquals("a%2Fb", SigV4.uriEncode("a/b", false), "query slashes encode");
        assertEquals("%D0%BE%D1%82%D1%87%D1%91%D1%82", SigV4.uriEncode("отчёт", false),
                "multibyte UTF-8, uppercase hex");
    }

    @Test
    void theCanonicalQuerySortsAndEncodes() {
        assertEquals("continuation-token=abc%2B%2F%3D&list-type=2&prefix=a%20b",
                SigV4.canonicalQuery(java.util.Map.of(
                        "prefix", "a b", "list-type", "2", "continuation-token", "abc+/=")));
        assertEquals("", SigV4.canonicalQuery(Map.of()));
    }
}
