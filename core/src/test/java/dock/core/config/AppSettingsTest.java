package dock.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppSettingsTest {

    @TempDir Path dir;

    @BeforeEach void isolate() { AppPaths.override(dir); }

    @AfterEach void release() { AppPaths.override(null); }

    @Test
    void sshConfigHostsAreShownByDefault() {
        assertTrue(SshConfig.SOURCE.shown());
    }

    @Test
    void aFlagSurvivesARead() throws Exception {
        AppSettings.setFlag(AppSettings.SSH_CONFIG_HOSTS, false);
        assertFalse(SshConfig.SOURCE.shown());
    }

    @Test
    void unknownKeysSurviveARewrite() throws Exception {
        Files.writeString(dir.resolve("settings.json"), "{\"future\":\"kept\"}");
        AppSettings.setFlag(AppSettings.SSH_CONFIG_HOSTS, false);
        assertTrue(Files.readString(dir.resolve("settings.json")).contains("\"future\" : \"kept\""));
    }

    @Test
    void anUnreadableFileReadsAsDefaults() throws Exception {
        Files.writeString(dir.resolve("settings.json"), "not json");
        assertEquals(true, AppSettings.flag(AppSettings.SSH_CONFIG_HOSTS, true));
    }
}
