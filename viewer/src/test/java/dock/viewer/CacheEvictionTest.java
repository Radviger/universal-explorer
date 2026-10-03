package dock.viewer;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

/** The decoded-image LRU: budget-driven eviction, capped GIF weight. */
class CacheEvictionTest {

    private static DecodedImage.Still still(int w, int h) {
        return new DecodedImage.Still(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB),
                "png", false);
    }

    private static DecodedImage.Animated animated(int w, int h, int frames) {
        BufferedImage f = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        return new DecodedImage.Animated(
                java.util.Collections.nCopies(frames, f),
                java.util.Collections.nCopies(frames, 100), "gif");
    }

    @Test
    void evictsTheLeastRecentlyUsedBeyondBudget() {
        ViewerCache cache = new ViewerCache(1000);
        cache.put("/a", still(20, 20));   // 400 px
        cache.put("/b", still(20, 20));
        cache.get("/a");                  // touch: b is now the eldest
        cache.put("/c", still(20, 20));   // 1200 > 1000, evicts b
        assertTrue(cache.has("/a"));
        assertFalse(cache.has("/b"), "the untouched eldest went first");
        assertTrue(cache.has("/c"));
        assertEquals(800, cache.usedForTest());
    }

    @Test
    void aSingleOversizedEntryEvictsItself() {
        ViewerCache cache = new ViewerCache(100);
        cache.put("/huge", still(50, 50));   // 2500 px
        assertFalse(cache.has("/huge"), "one giant image can't pin the heap");
        assertEquals(0, cache.usedForTest());
    }

    @Test
    void gifWeightCountsAtMostEightFrames() {
        DecodedImage.Animated longGif = animated(10, 10, 30);
        DecodedImage.Animated shortGif = animated(10, 10, 8);
        assertEquals(ViewerCache.weight(shortGif), ViewerCache.weight(longGif),
                "a 30-frame GIF weighs like an 8-frame one");
        assertEquals(800L, ViewerCache.weight(longGif));
    }

    @Test
    void rePuttingAKeyKeepsTheBooksBalanced() {
        ViewerCache cache = new ViewerCache(10_000);
        cache.put("/a", still(10, 10));            // 100 px
        cache.put("/a", still(20, 20));            // 400 px now
        assertEquals(400, cache.usedForTest());
        assertEquals(1, cache.entriesForTest());
    }
}
