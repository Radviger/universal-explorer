package dock.markdown;

import javax.swing.BorderFactory;
import javax.swing.JLabel;

/**
 * The stand-in for an inline image. The pending variant holds the
 * image's place while its fetch is in flight — the document shows
 * instantly and the icon replaces the chip when it lands. The failed
 * one (missing on the filesystem, over the size cap, undecodable, or
 * a dead URL) says where the image would have come from instead of
 * pretending nothing was there.
 */
final class ImageChip extends JLabel {

    private final String target;
    private final boolean pending;

    ImageChip(String target, MdTheme theme, boolean pending) {
        super(pending ? "[loading " + name(target) + "…]" : "[image: " + target + "]");
        this.target = target;
        this.pending = pending;
        setFont(theme.mono());
        setForeground(theme.muted());
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(
                        pending ? theme.muted() : theme.border()),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)));
    }

    /** The full image target — what an arriving icon replaces this by. */
    String target() { return target; }
    boolean pending() { return pending; }

    private static String name(String target) {
        int cut = Math.max(target.lastIndexOf('/'), target.lastIndexOf('\\'));
        return cut < 0 ? target : target.substring(cut + 1);
    }
}
