package dock.kit;

/**
 * App-wide view settings, driven from the View menu. Panes read these at
 * display time; MainWindow pushes changes into every open session's panes.
 */
public final class ViewSettings {

    private static boolean showHidden = false;

    /** Windows extra: also hide dot-prefixed names in local panes. */
    private static boolean hideDotPrefixedOnWindows = false;

    private ViewSettings() {}

    public static boolean showHidden() { return showHidden; }

    public static void setShowHidden(boolean value) { showHidden = value; }

    public static boolean hideDotPrefixedOnWindows() { return hideDotPrefixedOnWindows; }

    public static void setHideDotPrefixedOnWindows(boolean value) {
        hideDotPrefixedOnWindows = value;
    }
}
