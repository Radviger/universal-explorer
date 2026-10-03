package dock.kit;

import dock.core.fs.FileEntry;

/** Small formatting helpers shared by the file and transfer UI. */
public final class Fmt {

    private static final java.time.format.DateTimeFormatter DATE =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(java.time.ZoneId.systemDefault());

    private Fmt() {}

    /** Human size of an entry; directories carry no size. */
    public static String sizeOf(FileEntry e) {
        if (e.directory()) return "";
        return bytes(e.size());
    }

    /** Modification stamp of an entry; unknown times stay blank. */
    public static String dateOf(FileEntry e) {
        if (e.mtimeMillis() <= 0) return "";
        return DATE.format(java.time.Instant.ofEpochMilli(e.mtimeMillis()));
    }

    public static String bytes(long b) {
        if (b < 1024) return b + " B";
        double kb = b / 1024.0;
        if (kb < 1024) return "%.1f KB".formatted(kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return "%.1f MB".formatted(mb);
        return "%.1f GB".formatted(mb / 1024.0);
    }

    public static String speed(long bytesPerSec) {
        return bytes(bytesPerSec) + "/s";
    }

    /** Compact remaining time for transfer rows: "8s", "1m 12s", "1h 04m". */
    public static String eta(long seconds) {
        if (seconds < 0) seconds = 0;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m " + "%02ds".formatted(seconds % 60);
        return (minutes / 60) + "h " + "%02dm".formatted(minutes % 60);
    }

    /** Relative age for the session launcher: "just now" … "12d ago", then a date. */
    public static String age(long epochMillis) {
        if (epochMillis <= 0) return "never";
        long delta = System.currentTimeMillis() - epochMillis;
        if (delta < 0) return "just now";
        long minutes = delta / 60_000;
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24;
        if (days < 14) return days + "d ago";
        return java.time.format.DateTimeFormatter.ofPattern("MMM d")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(epochMillis));
    }
}
