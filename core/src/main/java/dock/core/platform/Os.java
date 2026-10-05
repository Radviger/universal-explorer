package dock.core.platform;

import java.util.Locale;

/** The desktop OS this JVM runs on, read once from {@code os.name}. */
public enum Os {
    WINDOWS, MAC, LINUX, OTHER;

    private static final Os CURRENT = of(System.getProperty("os.name", ""));

    public static Os current() {
        return CURRENT;
    }

    /** Pure classification (tests drive it with literal names). */
    static Os of(String osName) {
        String n = osName.toLowerCase(Locale.ROOT);
        if (n.startsWith("windows")) return WINDOWS;
        if (n.startsWith("mac") || n.startsWith("darwin")) return MAC;
        if (n.startsWith("linux")) return LINUX;
        return OTHER;
    }
}
