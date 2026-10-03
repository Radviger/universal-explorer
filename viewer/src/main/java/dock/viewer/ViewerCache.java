package dock.viewer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An LRU of decoded images under a pixel budget: walking a directory back
 * and forth doesn't re-decode, yet one giant scan can't pin the heap. The
 * {@code removeEldestEntry} override is the house pattern (the pane's
 * selection memory works the same way).
 */
final class ViewerCache {
    private final long budgetPixels;
    private long used;

    private final LinkedHashMap<String, DecodedImage> map =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, DecodedImage> eldest) {
                    if (used > budgetPixels) {
                        used -= weight(eldest.getValue());
                        return true;
                    }
                    return false;
                }
            };

    ViewerCache(long budgetPixels) {
        this.budgetPixels = budgetPixels;
    }

    /** A GIF counts for at most eight frames so one long animation can't
     *  evict the whole cache; vectors are light until rasterized. */
    static long weight(DecodedImage d) {
        return switch (d) {
            case DecodedImage.Still s -> (long) s.width() * s.height();
            case DecodedImage.Animated a -> (long) a.width() * a.height()
                    * Math.min(a.frames().size(), 8);
            case DecodedImage.Vector v -> 4096L;
            case DecodedImage.Unsupported u -> 1L;
        };
    }

    synchronized DecodedImage get(String key) {
        return map.get(key);
    }

    synchronized boolean has(String key) {
        return map.containsKey(key);
    }

    /** The budget must be updated before the put: {@code removeEldestEntry}
     *  fires from inside {@code map.put} and reads it. */
    synchronized void put(String key, DecodedImage image) {
        DecodedImage old = map.get(key);
        used += old == null ? weight(image) : weight(image) - weight(old);
        map.put(key, image);
    }

    synchronized int entriesForTest() {
        return map.size();
    }

    synchronized long usedForTest() {
        return used;
    }
}
