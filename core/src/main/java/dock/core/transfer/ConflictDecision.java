package dock.core.transfer;

/** What a resolver decided for one conflict. */
public record ConflictDecision(ConflictRule rule, boolean applyToAll) {

    public static ConflictDecision once(ConflictRule rule) {
        return new ConflictDecision(rule, false);
    }
}
