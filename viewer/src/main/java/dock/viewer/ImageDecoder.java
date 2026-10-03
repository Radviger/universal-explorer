package dock.viewer;

import com.kitfox.svg.SVGDiagram;
import com.kitfox.svg.SVGUniverse;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.metadata.IIOMetadataNode;
import net.ifok.image.image4j.codec.ico.ICODecoder;
import org.w3c.dom.Node;

/**
 * Turns image bytes into a {@link DecodedImage}. Everything here is
 * CPU-heavy — callers run it off the EDT. Formats nobody decodes in pure
 * Java answer as {@link DecodedImage.Unsupported} values rather than
 * throwing: the viewer shows that as its error state.
 */
public final class ImageDecoder {

    private ImageDecoder() {}

    /** Extensions with no solid pure-Java decoder; refused up front. */
    private static final Set<String> KNOWN_UNSUPPORTED = Set.of("heic", "heif", "avif", "xcf");

    /** Never rasterize an SVG beyond this edge, at any zoom. */
    private static final int MAX_RASTER = 8192;

    public static DecodedImage decode(byte[] bytes, String name) {
        String ext = ext(name);
        if (KNOWN_UNSUPPORTED.contains(ext))
            return new DecodedImage.Unsupported(ext, "no decoder for this format");
        if (looksLikeSvg(bytes)) return decodeSvg(bytes);
        if (ext.equals("ico")) return decodeIco(bytes);
        return decodeImageIO(bytes, ext);
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // ---- SVG ----

    /** SVG is text that starts with markup; a real image never does. */
    private static boolean looksLikeSvg(byte[] bytes) {
        String head = new String(bytes, 0, Math.min(bytes.length, 8192), StandardCharsets.UTF_8);
        return head.contains("<svg");
    }

    private static DecodedImage decodeSvg(byte[] bytes) {
        try {
            SVGUniverse universe = new SVGUniverse();
            var uri = universe.loadSVG(new ByteArrayInputStream(bytes), "dock-image");
            SVGDiagram diagram = uri == null ? null : universe.getDiagram(uri);
            if (diagram == null)
                return new DecodedImage.Unsupported("svg", "could not parse");
            int w = (int) Math.ceil(diagram.getWidth());
            int h = (int) Math.ceil(diagram.getHeight());
            if (w <= 0 || h <= 0)
                return new DecodedImage.Unsupported("svg", "no intrinsic size");
            return new DecodedImage.Vector(diagram, w, h);
        } catch (Exception e) {
            return new DecodedImage.Unsupported("svg", shortError(e));
        }
    }

    /** Rasterizes a decoded vector at its own size, width capped — for
     *  embedders that want an image, not the SVG type (and its library)
     *  on their classpath. Safe off the EDT: each vector owns its fresh
     *  diagram. The height is derived from the aspect: handing the
     *  letterboxing overload an unbounded height would center the art in
     *  a MAX_RASTER-tall canvas, and the transparency pads the embed. */
    public static BufferedImage rasterize(DecodedImage.Vector vector, int maxWidth) {
        int w = Math.min(vector.width(), maxWidth);
        int h = Math.max(1, Math.round(w * (vector.height() / (float) vector.width())));
        return rasterize(vector.diagram(), w, h);
    }

    /** Rasterizes a diagram at the requested size, edges capped, aspect
     *  preserved. Runs on the EDT inside the viewer's paint path — SVGs
     *  raster in milliseconds; the cap bounds the worst case. */
    public static BufferedImage rasterize(SVGDiagram diagram, int targetW, int targetH) {
        int w = Math.clamp(targetW, 1, MAX_RASTER);
        int h = Math.clamp(targetH, 1, MAX_RASTER);
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE);
            double s = Math.min(w / diagram.getWidth(), h / diagram.getHeight());
            g.translate((w - diagram.getWidth() * s) / 2.0,
                        (h - diagram.getHeight() * s) / 2.0);
            g.scale(s, s);
            diagram.setIgnoringClipHeuristic(true);
            try {
                diagram.render(g);
            } catch (com.kitfox.svg.SVGException e) {
                // A render hiccup degrades to a blank raster; it must not
                // take the paint path down with it.
            }
        } finally {
            g.dispose();
        }
        return bi;
    }

    // ---- ICO (image4j is a plain decoder, not an ImageIO plugin) ----

    private static DecodedImage decodeIco(byte[] bytes) {
        try {
            List<BufferedImage> images = ICODecoder.read(new ByteArrayInputStream(bytes));
            if (images.isEmpty())
                return new DecodedImage.Unsupported("ico", "no image in file");
            BufferedImage best = images.getFirst();
            for (BufferedImage img : images)
                if (img.getWidth() * img.getHeight() > best.getWidth() * best.getHeight())
                    best = img;
            return new DecodedImage.Still(best, "ico", best.getColorModel().hasAlpha());
        } catch (Exception e) {
            return new DecodedImage.Unsupported("ico", shortError(e));
        }
    }

    // ---- everything the ImageIO SPIs claim ----

    private static DecodedImage decodeImageIO(byte[] bytes, String ext) {
        try (ImageInputStream in = ImageIO.createImageInputStream(
                new ByteArrayInputStream(bytes))) {
            if (in == null) return notAnImage(ext);
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return notAnImage(ext);
            ImageReader r = readers.next();
            try {
                r.setInput(in);
                String format = r.getFormatName().toLowerCase(Locale.ROOT);
                int count = 1;
                try { count = r.getNumImages(true); } catch (Exception e) { count = 1; }
                // Only GIF animates; a multi-page TIFF shows its first page.
                if (count > 1 && format.equals("gif"))
                    return animatedGif(r, count);
                BufferedImage img = r.read(0);
                if (img == null) return notAnImage(format);
                return new DecodedImage.Still(img, format, img.getColorModel().hasAlpha());
            } finally {
                r.dispose();
            }
        } catch (Exception e) {
            return new DecodedImage.Unsupported(ext, shortError(e));
        }
    }

    private static DecodedImage.Unsupported notAnImage(String ext) {
        return new DecodedImage.Unsupported(ext, "not an image");
    }

    /**
     * Reads every frame onto one persistent canvas, honoring the GIF's
     * disposal methods, and snapshots the canvas per frame — the viewer
     * then just paints snapshots. The JDK reader hands back each frame as
     * its own (possibly partial) patch; offsets come from frame metadata.
     */
    private static DecodedImage animatedGif(ImageReader r, int count) throws IOException {
        int screenW = -1, screenH = -1;
        IIOMetadata streamMeta = r.getStreamMetadata();
        if (streamMeta != null) {
            Node logical = child(streamMeta.getAsTree("javax_imageio_gif_stream_1.0"),
                    "LogicalScreenDescriptor");
            if (logical != null) {
                screenW = intAttr(logical, "logicalScreenWidth", -1);
                screenH = intAttr(logical, "logicalScreenHeight", -1);
            }
        }
        BufferedImage first = r.read(0);
        if (screenW <= 0) screenW = Math.max(first.getWidth(), intAttr(
                imageMetaNode(r, 0, "ImageDescriptor"), "imageLeftPosition", 0)
                + first.getWidth());
        if (screenH <= 0) screenH = Math.max(first.getHeight(), intAttr(
                imageMetaNode(r, 0, "ImageDescriptor"), "imageTopPosition", 0)
                + first.getHeight());

        BufferedImage canvas = new BufferedImage(screenW, screenH,
                BufferedImage.TYPE_INT_ARGB_PRE);
        List<BufferedImage> frames = new ArrayList<>(count);
        List<Integer> delays = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BufferedImage patch = i == 0 ? first : r.read(i);
            Node descriptor = imageMetaNode(r, i, "ImageDescriptor");
            int left = intAttr(descriptor, "imageLeftPosition", 0);
            int top = intAttr(descriptor, "imageTopPosition", 0);
            Node control = imageMetaNode(r, i, "GraphicControlExtension");
            String disposal = control != null
                    ? attr(control, "disposalMethod", "none") : "none";
            delays.add(Math.max(0, intAttr(control, "delayTime", 1)) * 10);
            BufferedImage saved = disposal.equals("restoreToPrevious")
                    ? snapshot(canvas, left, top, patch.getWidth(), patch.getHeight()) : null;
            Graphics2D g = canvas.createGraphics();
            try {
                g.drawImage(patch, left, top, null);
            } finally {
                g.dispose();
            }
            frames.add(snapshot(canvas, 0, 0, screenW, screenH));
            switch (disposal) {
                case "restoreToBackgroundColor" -> {
                    Graphics2D bg = canvas.createGraphics();
                    try {
                        bg.setComposite(java.awt.AlphaComposite.Clear);
                        bg.fillRect(left, top, patch.getWidth(), patch.getHeight());
                    } finally {
                        bg.dispose();
                    }
                }
                case "restoreToPrevious" -> {
                    Graphics2D back = canvas.createGraphics();
                    try {
                        back.drawImage(saved, left, top, null);
                    } finally {
                        back.dispose();
                    }
                }
                default -> { /* none: the next frame draws over */ }
            }
        }
        return new DecodedImage.Animated(frames, delays, "gif");
    }

    private static Node imageMetaNode(ImageReader r, int index, String childName)
            throws IOException {
        IIOMetadata meta = r.getImageMetadata(index);
        if (meta == null) return null;
        return child(meta.getAsTree("javax_imageio_gif_image_1.0"), childName);
    }

    private static Node child(Node parent, String name) {
        if (parent == null) return null;
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling())
            if (c.getNodeName().equals(name)) return c;
        return null;
    }

    private static String attr(Node node, String name, String fallback) {
        if (node == null) return fallback;
        Node a = node.getAttributes().getNamedItem(name);
        return a == null ? fallback : a.getNodeValue();
    }

    private static int intAttr(Node node, String name, int fallback) {
        try {
            return Integer.parseInt(attr(node, name, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static BufferedImage snapshot(BufferedImage src, int x, int y, int w, int h) {
        BufferedImage copy = new BufferedImage(Math.max(1, w), Math.max(1, h),
                BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = copy.createGraphics();
        try {
            g.drawImage(src, -x, -y, null);
        } finally {
            g.dispose();
        }
        return copy;
    }

    private static String shortError(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m.split("\n")[0];
    }
}
