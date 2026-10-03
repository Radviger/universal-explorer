package dock.kit;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.UIManager;

/**
 * Registers the bundled fonts (Inter for UI text, JetBrains Mono for monospace
 * contexts, Symbols Nerd Font for icons) and exposes derived instances.
 *
 * Fonts are loaded from their exact TTF faces rather than looked up by family
 * name, so weights (Medium, SemiBold) resolve deterministically on every
 * machine without depending on what the OS has installed.
 */
public final class FontRegistry {

    public static final float BASE_SIZE = 13f;

    private record Face(String resource, Font base) {}

    private static final List<Face> FACES = List.of(
            new Face("Inter-Regular.ttf", null),
            new Face("Inter-Medium.ttf", null),
            new Face("Inter-SemiBold.ttf", null),
            new Face("Inter-Bold.ttf", null),
            new Face("JetBrainsMono-Regular.ttf", null),
            new Face("JetBrainsMono-Medium.ttf", null),
            new Face("JetBrainsMono-Bold.ttf", null),
            new Face("SymbolsNerdFont-Regular.ttf", null),
            new Face("SymbolsNerdFontMono-Regular.ttf", null)
    );

    private static Font interRegular;
    private static Font interMedium;
    private static Font interSemiBold;
    private static Font interBold;
    private static Font monoRegular;
    private static Font monoMedium;
    private static Font monoBold;
    private static Font symbols;
    private static Font symbolsMono;

    private static final Map<Font, Map<Float, Font>> CACHE = new ConcurrentHashMap<>();

    private FontRegistry() {}

    public static synchronized void install() {
        if (interRegular != null) return;
        var ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        try {
            interRegular   = load("Inter-Regular.ttf");
            interMedium    = load("Inter-Medium.ttf");
            interSemiBold  = load("Inter-SemiBold.ttf");
            interBold      = load("Inter-Bold.ttf");
            monoRegular    = load("JetBrainsMono-Regular.ttf");
            monoMedium     = load("JetBrainsMono-Medium.ttf");
            monoBold       = load("JetBrainsMono-Bold.ttf");
            symbols        = load("SymbolsNerdFont-Regular.ttf");
            symbolsMono    = load("SymbolsNerdFontMono-Regular.ttf");
            for (Font f : List.of(interRegular, interMedium, interSemiBold, interBold,
                    monoRegular, monoMedium, monoBold, symbols, symbolsMono)) {
                ge.registerFont(f);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Bundled fonts failed to load", e);
        }
    }

    private static Font load(String name) throws Exception {
        try (InputStream in = FontRegistry.class.getResourceAsStream("/dock/fonts/" + name)) {
            if (in == null) throw new java.io.FileNotFoundException(name);
            return Font.createFont(Font.TRUETYPE_FONT, in);
        }
    }

    private static Font sized(Font base, float size) {
        return CACHE.computeIfAbsent(base, b -> new ConcurrentHashMap<>())
                .computeIfAbsent(size, s -> base.deriveFont(s));
    }

    /** UI text. */
    public static Font ui()               { return ui(BASE_SIZE); }
    public static Font ui(float size)     { return sized(interRegular, size); }
    public static Font uiMedium()         { return uiMedium(BASE_SIZE); }
    public static Font uiMedium(float s)  { return sized(interMedium, s); }
    public static Font uiSemiBold()       { return uiSemiBold(BASE_SIZE); }
    public static Font uiSemiBold(float s){ return sized(interSemiBold, s); }
    public static Font uiBold(float s)    { return sized(interBold, s); }

    /** Monospace text: paths, file lists, terminal. */
    public static Font mono()             { return mono(BASE_SIZE); }
    public static Font mono(float size)   { return sized(monoRegular, size); }
    public static Font monoMedium(float s){ return sized(monoMedium, s); }
    public static Font monoBold(float s)  { return sized(monoBold, s); }

    /** Nerd-font icon glyphs. Proportional variant for labels/buttons/icons. */
    public static Font symbol(float size)     { return sized(symbols, size); }
    /** Nerd-font icon glyphs, monospaced advance for table columns. */
    public static Font symbolMono(float size) { return sized(symbolsMono, size); }

    /** Applies the app font scheme to standard Swing component defaults. */
    public static void applyUiDefaults() {
        var ui = ui();
        var medium = uiMedium(BASE_SIZE - 1);
        for (String key : List.of(
                "Label.font", "Button.font", "ToggleButton.font", "RadioButton.font",
                "CheckBox.font", "ComboBox.font", "TabbedPane.font", "Table.font",
                "TableHeader.font", "TextField.font", "PasswordField.font",
                "FormattedTextField.font", "ProgressBar.font", "ToolBar.font",
                "MenuBar.font", "MenuItem.font", "Menu.font", "CheckBoxMenuItem.font",
                "RadioButtonMenuItem.font", "List.font", "Tree.font", "Spinner.font",
                "ToolTip.font", "OptionPane.font", "ColorChooser.font",
                "FileChooser.font", "ScrollPane.font", "Viewport.font")) {
            UIManager.put(key, ui);
        }
        // Editors and multi-line text are code-ish surfaces.
        for (String key : List.of("TextArea.font", "TextPane.font", "EditorPane.font")) {
            UIManager.put(key, mono());
        }
        UIManager.put("MenuItem.acceleratorFont", medium);
        UIManager.put("TitlePane.font", medium);
        UIManager.put("TextComponent.arc" , 6);
    }
}
