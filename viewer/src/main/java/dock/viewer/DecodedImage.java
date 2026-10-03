package dock.viewer;

import com.kitfox.svg.SVGDiagram;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * One decoded, viewable image in the shape the viewer needs it: a still, an
 * animated GIF's pre-composited frames, a vector held for per-zoom
 * rasterization, or an explicit "can't show this" answer. The last one is a
 * value, not an exception — the viewer renders it as its error state.
 */
public sealed interface DecodedImage {

    /** Lowercase format name for the info strip ("png", "gif", …). */
    String format();

    /** A single-frame image. */
    record Still(BufferedImage image, String format, boolean alpha)
            implements DecodedImage {
        public int width() { return image.getWidth(); }
        public int height() { return image.getHeight(); }
    }

    /**
     * An animation: full-canvas frame snapshots in paint order (the GIF's
     * partial patches already composited, disposal methods applied) with
     * each frame's display time.
     */
    record Animated(List<BufferedImage> frames, List<Integer> delaysMs,
                    String format) implements DecodedImage {
        public int width() { return frames.getFirst().getWidth(); }
        public int height() { return frames.getFirst().getHeight(); }
        /** Animations are assumed translucent: GIF transparency is common
         *  and the checkerboard behind costs nothing. */
        public boolean alpha() { return true; }
    }

    /** A vector document, rasterized on demand at the current zoom. */
    record Vector(SVGDiagram diagram, int width, int height)
            implements DecodedImage {
        @Override public String format() { return "svg"; }
        public boolean alpha() { return true; }
    }

    /** A file the viewer refuses or fails to decode, with the reason. */
    record Unsupported(String format, String reason) implements DecodedImage {}
}
