package dock.core.transfer;

/** Called on a worker thread when a target exists; UI implementations block. */
@FunctionalInterface
public interface ConflictResolver {

    ConflictDecision resolve(ConflictInfo info);

    ConflictResolver ALL_OVERWRITE = info -> ConflictDecision.once(ConflictRule.OVERWRITE);
    ConflictResolver ALL_SKIP = info -> ConflictDecision.once(ConflictRule.SKIP);
}
