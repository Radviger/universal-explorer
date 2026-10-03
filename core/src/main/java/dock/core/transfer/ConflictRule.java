package dock.core.transfer;

/** How an existing target file is handled during a transfer. */
public enum ConflictRule {
    PROMPT, OVERWRITE, OVERWRITE_IF_NEWER, SKIP, RENAME
}
