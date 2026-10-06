package dock.core.config;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The hosts of an OpenSSH client config ({@code ~/.ssh/config}), read the
 * way {@code ssh} reads them: for each concrete {@code Host} alias, every
 * block whose patterns match contributes the options it sets first, so
 * {@code Host *} defaults fill in what a specific block leaves out.
 * {@code Include} is followed (globs, paths relative to {@code ~/.ssh});
 * {@code Match} blocks are conditional on runtime facts and are skipped.
 * Wildcard and negated patterns are never listed as hosts of their own.
 * The same file format on every OS — Windows' OpenSSH reads it too.
 */
public final class SshConfig {

    /** One connectable alias with its resolved options (null = not set). */
    public record Host(String alias, String hostName, String user, int port,
                       List<String> identityFiles, String proxyJump) {

        /**
         * This host as an SFTP site named after its alias. The key is the
         * first configured identity file that exists, else the first of
         * OpenSSH's default keys that exists; without either the site
         * authenticates by password. User defaults to the local account,
         * as ssh does.
         */
        public Site toSite(Path home, String localUser, Predicate<Path> exists) {
            String key = null;
            for (String f : identityFiles) {
                if (exists.test(Path.of(f))) { key = f; break; }
            }
            if (key == null) {
                for (String name : DEFAULT_KEYS) {
                    Path p = home.resolve(".ssh").resolve(name);
                    if (exists.test(p)) { key = p.toString(); break; }
                }
            }
            return new Site(alias, Protocol.SFTP, hostName != null ? hostName : alias,
                    port, user != null ? user : localUser, key, false, null, null,
                    false, false, 0);
        }

        public Site toSite() {
            Path home = Path.of(System.getProperty("user.home"));
            return toSite(home, System.getProperty("user.name"), Files::isRegularFile);
        }
    }

    /** ~/.ssh/config as a launcher source: every host as an SFTP site. */
    public static final SiteSource SOURCE = new SiteSource() {
        @Override public String title() { return "From ~/.ssh/config"; }
        @Override public String menuName() { return "SSH Config Hosts"; }
        @Override public String tag() { return "ssh config"; }
        @Override public String settingKey() { return AppSettings.SSH_CONFIG_HOSTS; }
        @Override public List<Site> sites() {
            return load().stream().map(Host::toSite).toList();
        }
    };

    /** OpenSSH's default identities, in its own try order. */
    static final List<String> DEFAULT_KEYS = List.of("id_ed25519", "id_ecdsa", "id_rsa");

    private static final int MAX_INCLUDE_DEPTH = 16;

    private SshConfig() {}

    /** The user's {@code ~/.ssh/config}; empty when it is absent or unreadable. */
    public static List<Host> load() {
        Path home = Path.of(System.getProperty("user.home"));
        return load(home.resolve(".ssh").resolve("config"), home,
                System.getProperty("user.name"));
    }

    public static List<Host> load(Path file, Path home, String localUser) {
        List<Block> blocks = new ArrayList<>();
        blocks.add(new Block(List.of("*")));   // options before the first Host apply to all
        try {
            read(file, home, blocks, 0);
        } catch (IOException e) {
            return List.of();
        }
        return resolve(blocks, home, localUser);
    }

    /** Parses config text directly (tests); Includes resolve against {@code home}. */
    static List<Host> parse(String text, Path home, String localUser) {
        List<Block> blocks = new ArrayList<>();
        blocks.add(new Block(List.of("*")));
        try {
            readLines(text.lines().toList(), home, blocks, 0);
        } catch (IOException e) {
            return List.of();
        }
        return resolve(blocks, home, localUser);
    }

    // ---- reading ----

    /** A Host (patterns) or Match (null patterns: never applies) block. */
    private record Block(List<String> patterns, List<String[]> options) {
        Block(List<String> patterns) {
            this(patterns, new ArrayList<>());
        }
    }

    private static void read(Path file, Path home, List<Block> blocks, int depth)
            throws IOException {
        if (!Files.isRegularFile(file)) {
            if (depth == 0) throw new IOException("no config");
            return;   // a missing Include target is not an error for ssh either
        }
        readLines(Files.readAllLines(file), home, blocks, depth);
    }

    private static void readLines(List<String> lines, Path home, List<Block> blocks,
                                  int depth) throws IOException {
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            List<String> words = tokens(line);
            if (words.isEmpty()) continue;
            String keyword = words.getFirst().toLowerCase(Locale.ROOT);
            List<String> args = words.subList(1, words.size());
            switch (keyword) {
                case "host" -> blocks.add(new Block(List.copyOf(args)));
                case "match" -> blocks.add(new Block(null));
                case "include" -> {
                    if (depth >= MAX_INCLUDE_DEPTH) continue;
                    for (String arg : args) {
                        for (Path p : includeTargets(arg, home)) {
                            read(p, home, blocks, depth + 1);
                        }
                    }
                }
                default -> {
                    if (!args.isEmpty()) {
                        blocks.getLast().options().add(
                                new String[] {keyword, String.join(" ", args)});
                    }
                }
            }
        }
    }

    /** Keyword and arguments: whitespace or one '=' separates the keyword,
     *  double quotes group an argument. */
    static List<String> tokens(String line) {
        List<String> out = new ArrayList<>();
        int i = 0, n = line.length();
        // The keyword ends at whitespace or '='.
        int k = 0;
        while (k < n && !Character.isWhitespace(line.charAt(k)) && line.charAt(k) != '=') k++;
        out.add(line.substring(0, k));
        i = k;
        while (i < n && Character.isWhitespace(line.charAt(i))) i++;
        if (i < n && line.charAt(i) == '=') i++;
        while (i < n) {
            while (i < n && Character.isWhitespace(line.charAt(i))) i++;
            if (i >= n) break;
            StringBuilder arg = new StringBuilder();
            if (line.charAt(i) == '"') {
                i++;
                while (i < n && line.charAt(i) != '"') arg.append(line.charAt(i++));
                i++;   // closing quote
            } else {
                while (i < n && !Character.isWhitespace(line.charAt(i))) arg.append(line.charAt(i++));
            }
            out.add(arg.toString());
        }
        return out;
    }

    private static List<Path> includeTargets(String arg, Path home) throws IOException {
        String expanded = arg.startsWith("~") ? home + arg.substring(1) : arg;
        Path pattern = Path.of(expanded);
        if (!pattern.isAbsolute()) pattern = home.resolve(".ssh").resolve(expanded);
        if (!expanded.contains("*") && !expanded.contains("?")) return List.of(pattern);
        Path dir = pattern.getParent();
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        PathMatcher m = FileSystems.getDefault().getPathMatcher(
                "glob:" + pattern.getFileName().toString());
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> m.matches(p.getFileName())).sorted().toList();
        }
    }

    // ---- resolving ----

    private static List<Host> resolve(List<Block> blocks, Path home, String localUser) {
        Set<String> aliases = new LinkedHashSet<>();
        for (Block b : blocks) {
            if (b.patterns() == null) continue;
            for (String p : b.patterns()) {
                if (!p.startsWith("!") && !p.contains("*") && !p.contains("?")) aliases.add(p);
            }
        }
        List<Host> out = new ArrayList<>();
        for (String alias : aliases) {
            Map<String, String> first = new LinkedHashMap<>();
            List<String> identities = new ArrayList<>();
            for (Block b : blocks) {
                if (!matches(b.patterns(), alias)) continue;
                for (String[] opt : b.options()) {
                    if (opt[0].equals("identityfile")) identities.add(opt[1]);
                    else first.putIfAbsent(opt[0], opt[1]);
                }
            }
            String hostName = first.get("hostname");
            hostName = hostName == null ? null : expand(hostName, home, localUser, alias, null);
            String user = first.get("user");
            String effectiveHost = hostName != null ? hostName : alias;
            String effectiveUser = user != null ? user : localUser;
            List<String> keys = new ArrayList<>();
            for (String f : identities) {
                keys.add(expand(f, home, localUser, effectiveHost, effectiveUser));
            }
            out.add(new Host(alias, hostName, user, port(first.get("port")), List.copyOf(keys),
                    first.get("proxyjump")));
        }
        return out;
    }

    /** OpenSSH host-pattern semantics: any negated match vetoes the block. */
    static boolean matches(List<String> patterns, String alias) {
        if (patterns == null) return false;
        boolean hit = false;
        for (String p : patterns) {
            boolean negated = p.startsWith("!");
            String glob = negated ? p.substring(1) : p;
            if (glob(glob).matcher(alias).matches()) {
                if (negated) return false;
                hit = true;
            }
        }
        return hit;
    }

    private static Pattern glob(String glob) {
        StringBuilder re = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> re.append(".*");
                case '?' -> re.append('.');
                default -> re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE);
    }

    /** The tokens ssh expands in paths and HostName: ~ %d %u %h %r %%. */
    static String expand(String value, Path home, String localUser, String host,
                         String remoteUser) {
        String v = value.startsWith("~") ? home + value.substring(1) : value;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c != '%' || i + 1 >= v.length()) {
                out.append(c);
                continue;
            }
            char t = v.charAt(++i);
            switch (t) {
                case 'd' -> out.append(home);
                case 'u' -> out.append(localUser);
                case 'h' -> out.append(host);
                case 'r' -> out.append(remoteUser != null ? remoteUser : localUser);
                case '%' -> out.append('%');
                default -> out.append('%').append(t);
            }
        }
        return out.toString();
    }

    private static int port(String value) {
        if (value == null) return 0;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
