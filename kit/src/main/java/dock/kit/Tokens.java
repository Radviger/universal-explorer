package dock.kit;

/**
 * Design tokens: the single source of truth for spacing and sizing.
 * Every layout pulls values from here — no magic numbers in UI code.
 *
 * Spacing scale is a 4px base grid: 4, 8, 12, 16, 20, 24, 32.
 */
public final class Tokens {

    // Spacing (4px grid)
    public static final int GAP_1 = 4;
    public static final int GAP_2 = 8;
    public static final int GAP_3 = 12;
    public static final int GAP_4 = 16;
    public static final int GAP_5 = 20;
    public static final int GAP_6 = 24;
    public static final int GAP_8 = 32;

    // Component metrics
    public static final int TOOLBAR_HEIGHT = 36;
    public static final int STATUSBAR_HEIGHT = 28;
    public static final int TABLE_ROW_HEIGHT = 30;
    public static final int TAB_HEIGHT = 34;
    public static final int TITLEPANE_HEIGHT = 40;

    // Icon sizes
    public static final int ICON_SMALL = 14;
    public static final int ICON = 16;
    public static final int ICON_LARGE = 20;
    public static final int ICON_HERO = 40;

    // Corner radius
    public static final int ARC = 8;
    public static final int ARC_CARD = 12;

    /** The theme's muted foreground — captions, secondary values, hints. */
    public static java.awt.Color muted() {
        java.awt.Color c = javax.swing.UIManager.getColor("Label.disabledForeground");
        return c != null ? c : javax.swing.UIManager.getColor("Label.foreground");
    }

    private Tokens() {}
}
