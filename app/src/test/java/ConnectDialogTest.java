import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import dock.ui.ConnectDialog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ConnectDialog auth-mode contract: switching the Authentication combo shows
 * exactly the inputs (and captions) of the selected method and hides the
 * rest — labels never orphaned.
 */
class ConnectDialogTest {

    @BeforeAll
    static void boot() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @Test
    void authModeShowsOnlyRelatedInputs() {
        ConnectDialog d = new ConnectDialog(null, (session, site) -> {});
        assertTrue(d.passwordInputsVisible(), "password mode is the default");
        assertFalse(d.keyInputsVisible());
        assertFalse(d.agentHintVisible());

        d.authForTest().setSelectedItem("Private key file");
        assertFalse(d.passwordInputsVisible(), "password inputs hidden in key mode");
        assertTrue(d.keyInputsVisible());
        assertFalse(d.agentHintVisible());

        d.authForTest().setSelectedItem("SSH agent (YubiKey)");
        assertFalse(d.passwordInputsVisible(), "password inputs hidden in agent mode");
        assertFalse(d.keyInputsVisible(), "key inputs hidden in agent mode");
        assertTrue(d.agentHintVisible(), "agent hint replaces the secret inputs");

        d.authForTest().setSelectedItem("Password");
        assertTrue(d.passwordInputsVisible());
        assertFalse(d.keyInputsVisible());
        assertFalse(d.agentHintVisible());
    }

    @Test
    void keyModeDialogIsTallerThanPasswordMode() {
        ConnectDialog d = new ConnectDialog(null, (session, site) -> {});
        int passwordHeight = d.getHeight();
        d.authForTest().setSelectedItem("Private key file");
        assertTrue(d.getHeight() > passwordHeight,
                "key mode shows two more rows, got " + d.getHeight()
                        + " vs " + passwordHeight);
        d.authForTest().setSelectedItem("Password");
        assertEquals(passwordHeight, d.getHeight(), "pack shrinks back");
    }
}
