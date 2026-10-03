package dock.kit.tabs;

import javax.swing.JComponent;

/**
 * A created tab: what the shell should title it, the tooltip carrying the
 * endpoint (null when the title already is the endpoint), and the
 * component itself.
 */
public record TabContent(String title, String tooltip, JComponent component) { }
