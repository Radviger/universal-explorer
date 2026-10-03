package dock.kit;

import java.awt.Graphics2D;

/**
 * Paints a breadcrumb segment: square inner sides, the bar's rounded
 * corners on the capped ends. A capped end reuses the chrome's own
 * primitive — a wider {@code fillRoundRect} clipped to the segment
 * bounds — so its corner geometry is identical to what painted the bar,
 * not an approximation with a curve constant of its own.
 */
public final class Segments {

    private Segments() { }

    public static void fill(Graphics2D g2, int w, int h,
                            boolean leftCap, boolean rightCap) {
        if (leftCap && rightCap) {
            g2.fillRoundRect(0, 0, w, h, Tokens.ARC, Tokens.ARC);
        } else if (leftCap || rightCap) {
            g2.clipRect(0, 0, w, h);
            g2.fillRoundRect(leftCap ? 0 : -Tokens.ARC, 0, w + Tokens.ARC, h,
                    Tokens.ARC, Tokens.ARC);
        } else {
            g2.fillRect(0, 0, w, h);
        }
    }
}
