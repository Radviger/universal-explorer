package dock.core.config;

import java.util.List;

/**
 * A place outside the app that already knows connections — ~/.ssh/config,
 * ~/.aws — read fresh on demand, never copied unless the user imports.
 * The launcher lists each shown source as its own section, and the
 * Session menu offers a show toggle and an import for each.
 */
public interface SiteSource {

    /** Section caption, e.g. "From ~/.ssh/config". */
    String title();

    /** Menu wording for the source itself, e.g. "SSH Config Hosts". */
    String menuName();

    /** Short tag in a row's age slot, e.g. "ssh config". */
    String tag();

    /** The settings.json flag that shows this source on the launcher. */
    String settingKey();

    /** The connections as sites, in the source's own order; empty on any read problem. */
    List<Site> sites();

    /** Every source, in launcher order. */
    static List<SiteSource> all() {
        return List.of(SshConfig.SOURCE, AwsProfiles.SOURCE);
    }

    /** Whether the launcher lists this source (off until switched on). */
    default boolean shown() {
        return AppSettings.flag(settingKey(), false);
    }
}
