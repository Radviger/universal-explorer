package dock.media;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Names the subtitle sidecars beside a movie: same directory, matched
 * case-insensitively — {@code {basename}.{srt,ass,ssa,vtt}} plus the
 * {@code {basename}.{lang}.srt} variants (film.en.srt, film.pt-BR.srt),
 * in case-insensitive name order so the cycle reads the same every time.
 */
final class Sidecars {

    private static final Set<String> EXTS = Set.of("srt", "ass", "ssa", "vtt");

    private Sidecars() {}

    /** The sidecar names for {@code movie} among a directory listing. */
    static List<String> find(List<String> names, String movie) {
        String base = baseOf(movie);
        List<String> out = new ArrayList<>();
        for (String n : names)
            if (matches(n, base)) out.add(n);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** The lowercased name minus its extension; extensionless stay whole. */
    private static String baseOf(String movie) {
        String lower = movie.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return dot < 0 ? lower : lower.substring(0, dot);
    }

    private static boolean matches(String candidate, String base) {
        String lower = candidate.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = lower.substring(dot + 1);
        if (!EXTS.contains(ext)) return false;
        String stem = lower.substring(0, dot);
        if (stem.equals(base)) return true;
        // The language variant — srt only, the one convention in the wild
        // worth guessing at; .en.ass libraries cycle by hand like everyone.
        if (!ext.equals("srt")) return false;
        int langDot = stem.lastIndexOf('.');
        return langDot > 0 && stem.substring(0, langDot).equals(base)
                && isLang(stem.substring(langDot + 1));
    }

    /** en, ru, ukr, pt-br — a short language tag, not a whole other name
     *  that merely shares a prefix. */
    private static boolean isLang(String token) {
        int hyphen = token.indexOf('-');
        String primary = hyphen < 0 ? token : token.substring(0, hyphen);
        if (!alpha(primary, 2, 3)) return false;
        return hyphen < 0 || alpha(token.substring(hyphen + 1), 2, 8);
    }

    private static boolean alpha(String s, int min, int max) {
        if (s.length() < min || s.length() > max) return false;
        for (int i = 0; i < s.length(); i++)
            if (!Character.isLetter(s.charAt(i))) return false;
        return true;
    }
}
