package dock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The footer's status line is a pub/sub channel: publishers (file panes)
 * push transient text, the shell's status bar subscribes. Blank text is
 * the idle state — never null.
 */
class StatusLineTest {

    private final List<String> seen = new ArrayList<>();
    private final java.util.function.Consumer<String> listener = seen::add;

    @AfterEach
    void unsubscribe() {
        dock.kit.StatusLine.removeListener(listener);
        dock.kit.StatusLine.clear();
    }

    @Test
    void newListenerReceivesTheCurrentTextImmediately() {
        dock.kit.StatusLine.clear();
        dock.kit.StatusLine.addListener(listener);
        assertEquals(List.of(""), seen, "idle state delivered as blank on subscribe");
    }

    @Test
    void publishNotifiesListenersAndNullMeansBlank() {
        dock.kit.StatusLine.addListener(listener);
        dock.kit.StatusLine.publish("f.txt · 12 B");
        dock.kit.StatusLine.publish(null);
        assertEquals(List.of("", "f.txt · 12 B", ""), seen);
        assertEquals("", dock.kit.StatusLine.text());
    }

    @Test
    void removedListenersHearNothingFurther() {
        dock.kit.StatusLine.addListener(listener);
        dock.kit.StatusLine.removeListener(listener);
        dock.kit.StatusLine.publish("gone");
        assertEquals(List.of(""), seen);
    }
}
