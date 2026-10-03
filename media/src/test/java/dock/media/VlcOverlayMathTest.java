package dock.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;
import javax.swing.JLabel;
import org.junit.jupiter.api.Test;

/**
 * The overlay hook receives the graphics as vlcj's scaled image painter
 * left it — translated into the letterboxed video's pixel space and
 * scaled to the frame's native size. The engine's recording painter must
 * map it back to component space, or the panel's centered flash lands
 * off-center by the letterbox margins. Pure transform math: no natives,
 * no frame clock.
 */
class VlcOverlayMathTest {

    @Test
    void overlayDrawingIsCenteredAfterTheLetterboxUndo() {
        // A 640×360 frame in a 1000×500 area: aspect-fit leaves pillarbox
        // bars. Record the frame through the painter itself (into a
        // throwaway buffer), exactly as the engine does per frame.
        int iw = 640, ih = 360, w = 1000, h = 500;
        VlcEngine.RecordingImagePainter painter = new VlcEngine.RecordingImagePainter();
        JComponent video = sized(w, h);
        Graphics2D scratch = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                .createGraphics();
        painter.paint(scratch, video, new BufferedImage(iw, ih, BufferedImage.TYPE_INT_RGB));
        scratch.dispose();

        // The graphics the overlay hook actually receives: replay the
        // translate+scale ScaledCallbackImagePainter.paint applies.
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        float scale = Math.min(w / (float) iw, h / (float) ih);
        g.translate((w - iw * scale) / 2f, (h - ih * scale) / 2f);
        if (scale != 1f) g.scale(scale, scale);

        painter.undoLetterbox(g, video);

        // A mark at the component center must land at the buffer's center.
        g.setColor(Color.RED);
        g.fillRect(w / 2 - 1, h / 2 - 1, 2, 2);
        g.dispose();
        assertEquals(0xFFFF0000, canvas.getRGB(w / 2, h / 2),
                "center drawn after the undo lands center");
    }

    @Test
    void beforeAnyFrameTheUndoChangesNothing() {
        VlcEngine.RecordingImagePainter painter = new VlcEngine.RecordingImagePainter();
        BufferedImage canvas = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.translate(10, 10);            // an arbitrary pre-existing transform
        painter.undoLetterbox(g, sized(100, 100));
        g.setColor(Color.RED);
        g.fillRect(40, 40, 2, 2);       // a component-space point
        g.dispose();
        assertEquals(0xFFFF0000, canvas.getRGB(50, 50),
                "no frame recorded: drawing stays where it was");
    }

    @Test
    void theFittedBoxIsThePaintersOwnAspectFit() {
        VlcEngine.RecordingImagePainter painter = new VlcEngine.RecordingImagePainter();
        // No frame yet — audio-only files never record one.
        assertNull(painter.fittedBox(1000, 500), "nothing fitted before a frame");

        record(painter, 1280, 720);
        // A 16:9 frame in a taller area: width-limited, pillarboxed.
        assertArrayEquals(new int[] {960, 540}, painter.fittedBox(960, 822));
        // The same frame in a wider area: height-limited, letterboxed.
        assertArrayEquals(new int[] {1822, 1025}, painter.fittedBox(1900, 1025));
        record(painter, 1280, 536);
        // An anamorphic 2.39:1 rip, the case that oversizes area-proportioned
        // overlays most: the picture is far shorter than the area.
        assertArrayEquals(new int[] {1900, 796}, painter.fittedBox(1900, 1030));
    }

    /** Records a frame through the painter itself, exactly as the engine
     *  does per painted frame. */
    private static void record(VlcEngine.RecordingImagePainter painter,
                               int iw, int ih) {
        Graphics2D scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
                .createGraphics();
        painter.paint(scratch, sized(10, 10),
                new BufferedImage(iw, ih, BufferedImage.TYPE_INT_RGB));
        scratch.dispose();
    }

    private static JComponent sized(int w, int h) {
        JComponent c = new JLabel();
        c.setSize(w, h);
        return c;
    }
}
