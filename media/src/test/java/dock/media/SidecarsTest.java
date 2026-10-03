package dock.media;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The sidecar naming convention: direct matches, language variants,
 *  and everything that must not match. */
class SidecarsTest {

    @Test
    void directMatchesInCaseInsensitiveOrder() {
        // Names keep their own case; only the matching and the order
        // ignore it.
        assertEquals(List.of("film.ass", "FILM.SRT", "film.ssa", "film.vtt"),
                Sidecars.find(List.of("film.vtt", "noise.txt", "FILM.SRT",
                        "film.ssa", "film.ass"), "film.mkv"));
    }

    @Test
    void languageVariantsAfterDirectSrt() {
        assertEquals(List.of("film.en.srt", "film.pt-BR.srt", "film.srt"),
                Sidecars.find(List.of("film.srt", "film.pt-BR.srt",
                        "film.en.srt", "other.srt"), "film.mkv"));
    }

    @Test
    void theMoviesOwnCaseDoesNotMatter() {
        assertEquals(List.of("film.srt"),
                Sidecars.find(List.of("film.srt"), "FILM.MKV"));
    }

    @Test
    void multiDotBasenamesKeepTheirWholeName() {
        assertEquals(List.of("My.Movie.2024.en.srt", "My.Movie.2024.srt"),
                Sidecars.find(List.of("My.Movie.srt", "My.Movie.2024.srt",
                        "My.Movie.2024.en.srt", "My.srt"), "My.Movie.2024.mkv"));
    }

    @Test
    void nearMissesMatchNothing() {
        assertEquals(List.of(), Sidecars.find(List.of(
                        "film.english.srt",   // a word, not a language tag
                        "film.e.srt",         // single letter
                        "film.en.ass",        // the variant convention is srt-only
                        "film.en.en.srt",     // two tags stacked
                        "film2.srt",          // different base
                        "afilm.srt",          // prefix, not the base
                        "film.srt.bak",       // the extension must be last
                        "film.docx",          // not a subtitle format
                        "film"),              // nothing to strip
                "film.mkv"));
    }
}
