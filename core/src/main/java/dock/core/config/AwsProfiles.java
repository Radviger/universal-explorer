package dock.core.config;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The AWS CLI's profiles, read the way the CLI reads them: keys from the
 * shared credentials file ({@code ~/.aws/credentials}, or
 * {@code AWS_SHARED_CREDENTIALS_FILE}), region and endpoint from the config
 * file ({@code ~/.aws/config}, or {@code AWS_CONFIG_FILE}, where profiles
 * are {@code [profile name]} except {@code [default]}). Keys found in both
 * files come from the credentials file.
 *
 * <p>An S3 site that authenticates by profile stores only the profile name
 * and resolves the keys here at every dial — the app never copies them, so
 * a rotation with {@code aws configure} just works. Profiles without static
 * keys (SSO, role assumption, credential_process) are not supported yet and
 * are left out of the listing.
 */
public final class AwsProfiles {

    /** One profile's resolved settings (null = not set). */
    public record Profile(String name, String accessKeyId, String secretAccessKey,
                          String sessionToken, String region, String endpointUrl) {

        public boolean hasKeys() {
            return accessKeyId != null && secretAccessKey != null;
        }

        /**
         * This profile as an S3 site: the profile's endpoint (S3-compatible
         * stores) or AWS's regional one, authenticating by profile — the
         * site's user is the profile name and it keeps no secret.
         */
        public Site toSite() {
            String host;
            int port = 443;
            boolean secure = true;
            URI endpoint = endpointUrl == null ? null : parse(endpointUrl);
            if (endpoint != null && endpoint.getHost() != null) {
                host = endpoint.getHost();
                secure = !"http".equalsIgnoreCase(endpoint.getScheme());
                port = endpoint.getPort() > 0 ? endpoint.getPort() : secure ? 443 : 80;
            } else {
                host = region == null ? "s3.amazonaws.com" : "s3." + region + ".amazonaws.com";
            }
            return new Site(siteName(name), Protocol.S3, host, port, name, null, true, null,
                    null, false, secure, 0, null, null, null, region);
        }
    }

    /** ~/.aws as a launcher source: every profile with keys as an S3 site. */
    public static final SiteSource SOURCE = new SiteSource() {
        @Override public String title() { return "From ~/.aws"; }
        @Override public String menuName() { return "AWS Profiles"; }
        @Override public String tag() { return "aws profile"; }
        @Override public String settingKey() { return AppSettings.AWS_PROFILES; }
        @Override public List<Site> sites() {
            return list().stream().filter(Profile::hasKeys).map(Profile::toSite).toList();
        }
    };

    private AwsProfiles() {}

    /** The launcher name of a profile's site. */
    public static String siteName(String profile) {
        return "AWS " + profile;
    }

    /** Every profile either file names, {@code default} first. */
    public static List<Profile> list() {
        return list(System::getenv, Path.of(System.getProperty("user.home")));
    }

    /** One profile, or null when neither file names it. */
    public static Profile profile(String name) {
        return profile(name, System::getenv, Path.of(System.getProperty("user.home")));
    }

    static Profile profile(String name, Function<String, String> env, Path home) {
        for (Profile p : list(env, home)) if (p.name().equals(name)) return p;
        return null;
    }

    static List<Profile> list(Function<String, String> env, Path home) {
        Map<String, Map<String, String>> creds = sections(
                file(env.apply("AWS_SHARED_CREDENTIALS_FILE"), home, "credentials"), false);
        Map<String, Map<String, String>> config = sections(
                file(env.apply("AWS_CONFIG_FILE"), home, "config"), true);
        List<String> names = new ArrayList<>();
        for (String n : creds.keySet()) if (!names.contains(n)) names.add(n);
        for (String n : config.keySet()) if (!names.contains(n)) names.add(n);
        if (names.remove("default")) names.addFirst("default");

        List<Profile> out = new ArrayList<>();
        for (String n : names) {
            Map<String, String> merged = new LinkedHashMap<>(config.getOrDefault(n, Map.of()));
            merged.putAll(creds.getOrDefault(n, Map.of()));
            out.add(new Profile(n, merged.get("aws_access_key_id"),
                    merged.get("aws_secret_access_key"), merged.get("aws_session_token"),
                    merged.get("region"), merged.get("endpoint_url")));
        }
        return out;
    }

    private static Path file(String override, Path home, String name) {
        if (override != null && !override.isBlank()) {
            return Path.of(override.startsWith("~") ? home + override.substring(1) : override);
        }
        return home.resolve(".aws").resolve(name);
    }

    /**
     * INI sections → lowercase keys. In the config file only {@code [default]}
     * and {@code [profile x]} are profiles ({@code [sso-session]},
     * {@code [services]} are not); nested blocks (an empty {@code key =}
     * followed by indented lines) are skipped.
     */
    private static Map<String, Map<String, String>> sections(Path file, boolean configFile) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        List<String> lines;
        try {
            if (!Files.isRegularFile(file)) return out;
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            return out;
        }
        return sections(lines, configFile);
    }

    static Map<String, Map<String, String>> sections(List<String> lines, boolean configFile) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        Map<String, String> current = null;
        boolean nested = false;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                nested = false;
                String header = line.substring(1, line.length() - 1).trim();
                String name = profileName(header, configFile);
                current = name == null ? null : out.computeIfAbsent(name, k -> new LinkedHashMap<>());
                continue;
            }
            if (current == null) continue;
            boolean indented = Character.isWhitespace(raw.charAt(0));
            if (nested && indented) continue;   // a sub-property of a nested block
            nested = false;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(eq + 1).trim();
            if (value.isEmpty()) {
                nested = true;
                continue;
            }
            current.putIfAbsent(key, value);
        }
        return out;
    }

    private static String profileName(String header, boolean configFile) {
        if (!configFile) return header;
        if (header.equals("default")) return "default";
        if (header.startsWith("profile ")) return header.substring("profile ".length()).trim();
        return null;
    }

    private static URI parse(String url) {
        try {
            return URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
