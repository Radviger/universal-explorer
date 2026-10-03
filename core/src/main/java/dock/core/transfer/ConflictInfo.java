package dock.core.transfer;

import dock.core.fs.FileEntry;

/** Everything the resolver needs to know about one collision. */
public record ConflictInfo(
        String sourcePath,
        FileEntry source,
        String targetPath,
        FileEntry target,
        int remainingConflicts) {
}
