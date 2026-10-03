package dock.core.fs;

/**
 * One directory entry, local or remote. {@code posixPerms} is the classic
 * rwx octal (e.g. 0644) when the backend can provide it, null otherwise.
 * {@code share} marks a server's exported share sitting where a folder
 * would (an SMB root listing): navigable like a directory, but not one.
 */
public record FileEntry(
        String name,
        boolean directory,
        long size,
        long mtimeMillis,
        Integer posixPerms,
        boolean hidden,
        boolean share) {

    public FileEntry(String name, boolean directory, long size, long mtimeMillis,
                     Integer posixPerms, boolean hidden) {
        this(name, directory, size, mtimeMillis, posixPerms, hidden, false);
    }

    public static final FileEntry PARENT = new FileEntry("..", true, 0, 0, null, false);
}
