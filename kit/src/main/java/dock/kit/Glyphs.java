package dock.kit;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.util.function.Supplier;
import javax.swing.Icon;
import javax.swing.UIManager;

/**
 * Nerd Font glyph catalogue (Font Awesome subset of Symbols Nerd Font) and
 * the {@link GlyphIcon} renderer that paints them with correct ink-centering.
 */
public final class Glyphs {

    private Glyphs() {}

    // nf-fa-* — stable codepoints in the U+F000–F2FF range.
    public static final String SERVER          = "\uF233";
    public static final String WINDOWS         = "\uF17A";
    public static final String FOLDER          = "\uF07B";
    public static final String FOLDER_OPEN     = "\uF07C";
    public static final String FILE            = "\uF016";
    public static final String FILE_TEXT       = "\uF0F6";
    public static final String TERMINAL        = "\uF120";
    public static final String PLUG            = "\uF1E6";
    public static final String GEAR            = "\uF013";
    public static final String SYNC            = "\uF021";
    public static final String UPLOAD          = "\uF093";
    public static final String DOWNLOAD        = "\uF019";
    public static final String CLOSE           = "\uF00D";
    public static final String PLUS            = "\uF067";
    public static final String HOME            = "\uF015";
    public static final String ARROW_LEFT      = "\uF060";
    public static final String ARROW_RIGHT     = "\uF061";
    public static final String ARROW_UP        = "\uF062";
    /** nf-fa-arrow-down, U+F063 — the find bar's next-match control. */
    public static final String ARROW_DOWN      = "\uF063";
    public static final String CHEVRON_RIGHT   = "\uF054";
    public static final String CHEVRON_LEFT    = "\uF053";
    public static final String UNDO            = "\uF0E2";
    public static final String REDO            = "\uF01E";
    public static final String CHECK           = "\uF00C";
    public static final String TRASH           = "\uF1F8";
    public static final String EDIT            = "\uF044";
    public static final String KEY             = "\uF084";
    public static final String LOCK            = "\uF023";
    public static final String GLOBE           = "\uF0AC";
    public static final String SHARE_ALT       = "\uF1E0";
    public static final String CLOUD           = "\uF0C2";
    public static final String MONITOR         = "\uF108";
    public static final String PAUSE           = "\uF04C";
    public static final String PLAY            = "\uF04B";
    /** nf-fa-volume-up, U+F028 — the speaker with waves: sound on. */
    public static final String VOLUME_HIGH     = "\uF028";
    /** nf-fa-volume-off, U+F026 — the speaker crossed out: muted. */
    public static final String VOLUME_OFF      = "\uF026";
    /** nf-fa-closed-captioning, U+F20A — the CC TV glyph: subtitles. */
    public static final String CLOSED_CAPTION  = "\uF20A";
    public static final String INFO            = "\uF05A";
    public static final String WARNING         = "\uF071";
    public static final String SUN             = "\uF185";
    public static final String MOON            = "\uF186";
    public static final String SEARCH          = "\uF002";
    /** nf-fa-search-plus/minus, U+F00E/U+F010 — magnifiers with the +
     *  or −, the viewer's zoom controls. */
    public static final String SEARCH_PLUS     = "\uF00E";
    public static final String SEARCH_MINUS    = "\uF010";
    /** nf-fa-expand, U+F065 — outward arrows, "fit the image to the pane". */
    public static final String EXPAND          = "\uF065";
    public static final String EXCHANGE        = "\uF0EC";
    public static final String EYE             = "\uF06E";
    public static final String ELLIPSIS        = "\uF141";
    /** nf-fa-copy, U+F0C5 — two overlapping pages; the code card's copy. */
    public static final String COPY            = "\uF0C5";
    /** nf-fa-clipboard, U+F0EA — the clipboard board: paste. */
    public static final String PASTE           = "\uF0EA";
    /** nf-fa-save, U+F0C7 — the floppy; the editor's save. */
    public static final String SAVE            = "\uF0C7";
    /** nf-fa-text-width, U+F035 — a line flanked by width arrows; the
     *  reader's word-wrap toggle. */
    public static final String TEXT_WIDTH      = "\uF035";
    /** nf-fa-code, U+F121 — the angle brackets; switch to the code view. */
    public static final String CODE            = "\uF121";
    /** nf-fa-picture-o, U+F03E — the framed image; switch to the render view. */
    public static final String PICTURE         = "\uF03E";

    // nf-fae-* — Font Awesome Extended shapes. The ringed planet is the
    // app mark (window/taskbar icon, landing hero, About dialog).
    public static final String PLANET          = "\uE22E";
    /** The mark's paint tilt: the glyph's own ~7° ring rise plus this lands
     *  the ring almost on the diagonal, so the ink fills the square. */
    public static final double PLANET_TILT_DEG = -38.0;

    // nf-fa-* file-type shapes (generic kinds; source files use Seti below).
    // Codepoints verified against the Nerd Fonts 3.5.1 glyph table.
    public static final String FILE_IMAGE      = "\uF1C5";
    public static final String FILE_VIDEO      = "\uF1C8";
    public static final String FILE_AUDIO      = "\uF1C7";
    public static final String FILE_PDF        = "\uF1C1";
    public static final String FILE_WORD       = "\uF1C2";
    public static final String FILE_EXCEL      = "\uF1C3";
    public static final String FILE_SLIDES     = "\uF1C4";
    public static final String FILE_ARCHIVE    = "\uF1C6";
    /** nf-fa-archive, U+F187 — the cardboard box: the archive mark, on file
     *  rows and path crumbs alike. Unlike the zipped page of
     *  {@link #FILE_ARCHIVE}, the box reads at a glance even at 16px. */
    public static final String ARCHIVE         = "\uF187";
    public static final String FILE_CODE       = "\uF1C9";
    public static final String HTML5           = "\uF13B";
    public static final String CUBE            = "\uF1B2";
    public static final String DATABASE        = "\uF1C0";
    public static final String HDD             = "\uF0A0";
    public static final String SLIDERS         = "\uF1DE";
    public static final String SCROLL          = "\uEF0D";

    // nf-fa-* shell-folder marks for the local breadcrumb (Downloads reuses
    // DOWNLOAD above, Desktop reuses MONITOR, Documents reuses FILE_TEXT).
    public static final String USER            = "\uF007";
    public static final String IMAGE           = "\uF03E";
    public static final String MUSIC           = "\uF001";
    public static final String FILM            = "\uF008";

    // nf-seti-* per-language file icons (the VS Code / Atom set).
    public static final String SETI_JS         = "\uE60C";
    public static final String SETI_TS         = "\uE628";
    public static final String SETI_JSON       = "\uE60B";
    public static final String SETI_CSS        = "\uE614";
    public static final String SETI_SASS       = "\uE603";
    public static final String SETI_PYTHON     = "\uE606";
    public static final String SETI_JAVA       = "\uE66D";
    public static final String SETI_C          = "\uE649";
    public static final String SETI_CPP        = "\uE646";
    public static final String SETI_CSHARP     = "\uE648";
    public static final String SETI_GO         = "\uE627";
    public static final String SETI_RUST       = "\uE68B";
    public static final String SETI_PHP        = "\uE608";
    public static final String SETI_RUBY       = "\uE605";
    public static final String SETI_SWIFT      = "\uE699";
    public static final String SETI_KOTLIN     = "\uE634";
    public static final String SETI_SHELL      = "\uE691";
    public static final String SETI_POWERSHELL = "\uE683";
    public static final String SETI_XML        = "\uE619";
    public static final String SETI_MARKDOWN   = "\uE609";
    public static final String SETI_CONFIG     = "\uE615";
    public static final String SETI_REACT      = "\uE625";
    public static final String SETI_DOCKER     = "\uE650";
    public static final String SETI_GIT        = "\uE65D";

    /** Creates an icon painting {@code glyph} at the given pixel size. */
    public static Icon icon(String glyph, float size) {
        return icon(glyph, size, () -> UIManager.getColor("Label.foreground"));
    }

    /** Creates an icon with an explicit (possibly theme-aware) color. */
    public static Icon icon(String glyph, float size, Supplier<Color> color) {
        return new GlyphIcon(glyph, size, color, false, false);
    }

    /**
     * Creates an icon whose ink is scaled to a uniform optical size: every
     * glyph reports the same square box and its ink fills the same maximum
     * dimension, so mixed glyphs (thin chevrons next to dense shapes) read
     * as equals in one toolbar.
     */
    public static Icon iconUniform(String glyph, float size, Supplier<Color> color) {
        return new GlyphIcon(glyph, size, color, true, false);
    }

    /**
     * Uniform icon that paints in the hosting component's foreground when it
     * has one — FlatLaf's hidden tab-close button swaps its foreground
     * between closeForeground and closeHoverBackground for hover states,
     * which a plain color supplier cannot see.
     */
    public static Icon iconInheritForeground(String glyph, float size, Supplier<Color> fallback) {
        return new GlyphIcon(glyph, size, fallback, true, true);
    }

    /**
     * Uniform icon rotated by {@code angleDeg} around its center — the
     * diagonal app mark. The rotation rides the paint transform, so the
     * glyph outlines stay crisp at any size, and the uniform fit is
     * computed against the rotated bounds.
     */
    public static Icon iconRotated(String glyph, float size, Supplier<Color> color,
                                   double angleDeg) {
        return new GlyphIcon(glyph, size, color, true, false, angleDeg);
    }

    /**
     * Paints a nerd-font glyph so its ink is optically centered inside an
     * {@code size x size} box. Symbol fonts carry unusable line metrics
     * (they cover huge glyph ranges), so we align by glyph bounds, not
     * by ascent/descent.
     */
    public static final class GlyphIcon implements Icon {
        private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

        private final String glyph;
        private final float size;
        private final Supplier<Color> color;
        private final boolean inheritForeground;
        private final double inkW;
        private final double inkH;
        private final double inkX;
        private final double inkY;
        /** Ink scale factor (1 for plain icons). */
        private final double scale;
        /** Square the ink gets centered in; also the reported icon size. */
        private final int boxSize;
        /** Paint-time rotation about the ink center, degrees (0 = upright). */
        private final double angleDeg;
        /** Rotated glyph outline (font units) — the painted mark, when
         *  angleDeg != 0. Its true bounds drive scaling and centering. */
        private final java.awt.Shape spunShape;
        private final double spunCx;
        private final double spunCy;

        /** Share of the box the largest ink dimension is scaled to fill. */
        private static final double UNIFORM_FILL = 0.85;

        GlyphIcon(String glyph, float size, Supplier<Color> color,
                  boolean normalized, boolean inheritForeground) {
            this(glyph, size, color, normalized, inheritForeground, 0);
        }

        GlyphIcon(String glyph, float size, Supplier<Color> color,
                  boolean normalized, boolean inheritForeground, double angleDeg) {
            this.glyph = glyph;
            this.size = size;
            this.color = color;
            this.inheritForeground = inheritForeground;
            this.angleDeg = angleDeg;
            TextLayout tl = new TextLayout(glyph, FontRegistry.symbol(size), FRC);
            Rectangle2D b = tl.getBounds();
            this.inkW = Math.max(1, b.getWidth());
            this.inkH = Math.max(1, b.getHeight());
            this.inkX = b.getX();
            this.inkY = b.getY();
            if (angleDeg != 0) {
                // Rotating the ink's bounding BOX (w·cos+h·sin) overshoots
                // the real mark: the planet's extreme points sit at
                // mid-height, so the rotated ink folds tighter than its box
                // does. The rotated outline is the only truthful measure.
                java.awt.geom.AffineTransform spin = new java.awt.geom.AffineTransform();
                spin.rotate(Math.toRadians(angleDeg));
                java.awt.Shape spun = tl.getOutline(spin);
                Rectangle2D sb = spun.getBounds2D();
                this.spunShape = spun;
                this.spunCx = sb.getCenterX();
                this.spunCy = sb.getCenterY();
                this.scale = normalized
                        ? size * UNIFORM_FILL / Math.max(sb.getWidth(), sb.getHeight())
                        : 1.0;
                this.boxSize = normalized
                        ? (int) Math.ceil(size)
                        : (int) Math.ceil(Math.max(sb.getWidth(), sb.getHeight()));
            } else {
                this.spunShape = null;
                this.spunCx = this.spunCy = 0;
                if (normalized) {
                    this.scale = size * UNIFORM_FILL / Math.max(inkW, inkH);
                    this.boxSize = (int) Math.ceil(size);
                } else {
                    this.scale = 1.0;
                    this.boxSize = (int) Math.ceil(Math.max(inkW, inkH));
                }
            }
        }

        @Override
        public int getIconWidth() {
            return boxSize;
        }

        @Override
        public int getIconHeight() {
            return boxSize;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                Color col = inheritForeground && c != null ? c.getForeground() : null;
                if (col == null) col = color.get();
                if (col == null) col = UIManager.getColor("Label.foreground");
                g2.setColor(col);
                Font f = FontRegistry.symbol(size);
                g2.setFont(f);
                if (spunShape != null) {
                    // Rotated mark: fill the pre-spun outline, centered by
                    // the outline's own bounds so the visible ink (not its
                    // axis-aligned box) sits in the middle of the square.
                    g2.translate(x + boxSize / 2.0, y + boxSize / 2.0);
                    g2.scale(scale, scale);
                    g2.translate(-spunCx, -spunCy);
                    g2.fill(spunShape);
                    return;
                }
                TextLayout tl = new TextLayout(glyph, f, g2.getFontRenderContext());
                // Center the (possibly scaled) ink box inside the icon square
                // anchored at (x, y); drawing at (-inkX, -inkY) puts the ink
                // origin at the translated position in scaled space.
                double iw = inkW * scale;
                double ih = inkH * scale;
                g2.translate(x + (boxSize - iw) / 2, y + (boxSize - ih) / 2);
                g2.scale(scale, scale);
                tl.draw(g2, (float) -inkX, (float) -inkY);
            } finally {
                g2.dispose();
            }
        }
    }
}
