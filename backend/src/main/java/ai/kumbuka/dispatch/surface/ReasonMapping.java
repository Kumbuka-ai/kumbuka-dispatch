package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.DispatchException;

/**
 * The kernel's reasons, translated into the caller's.
 *
 * <p>One switch with no {@code default}, in one direction. A reason added to
 * the kernel is a compile error here, which is the only arrangement under
 * which "a reason not in the table cannot be returned" survives the next
 * change to the domain — the alternative is a new kernel refusal escaping as
 * {@code UNEXPECTED_FAILURE} in a deployment nobody is watching, which is the
 * worst of both: the caller is told it is a defect, and it is not.
 *
 * <p><strong>Several kernel reasons map onto one caller reason, and that is
 * the point.</strong> The kernel distinguishes {@code SCOPE_UNRESOLVED} from
 * {@code NOT_FOUND} because it has to decide differently; the caller must not
 * be able to tell them apart, because the difference is exactly what a scope
 * enumerator is looking for. The narrowing happens here, once, rather than in
 * each adapter — where it was the kind of thing that gets done twice and
 * differently.
 */
public final class ReasonMapping {

    private ReasonMapping() {
    }

    /**
     * The caller-facing code for a kernel refusal.
     *
     * <p>{@code SESSION_NOT_BOUND} is the one 5xx in the set and maps to
     * {@link RefusalCode#UNEXPECTED_FAILURE}: the session contract was not
     * bound, no retry of the caller's fixes it, and telling it that its call
     * broke a rule would send it looking for a rule that does not exist.
     */
    public static RefusalCode of(DispatchException.Reason reason) {
        return switch (reason) {
            // Nothing is visible there — and the three causes stay blurred.
            case NOT_FOUND, SCOPE_UNRESOLVED -> RefusalCode.NOT_FOUND;

            // The object is real and its state says no.
            case TRANSITION_NOT_PERMITTED -> RefusalCode.STATE_DOES_NOT_ALLOW;

            // Not this caller, ever.
            case RATIFICATION_NOT_PERMITTED, ACTOR_UNKNOWN -> RefusalCode.ROLE_DOES_NOT_ALLOW;

            // The call is the holder's and the caller does not hold the task:
            // another does, nobody does, or the caller's own lease ended.
            case CLAIM_REQUIRED, LEASE_LAPSED -> RefusalCode.NOT_THE_HOLDER;
            case RECEIPT_MISMATCH -> RefusalCode.RECEIPT_WRONG;

            // The bracket's own gate: a root closes with unfinished children
            // only on a confirmation, and one handed out goes stale when the
            // set changes.
            case CONFIRMATION_REQUIRED, CONFIRMATION_STALE ->
                RefusalCode.CHILDREN_NOT_FINISHED;

            // The condition on an attribute that a claim checks.
            case DEFERRAL_PENDING -> RefusalCode.DEFERRAL_PENDING;

            // A set with nothing free in it.
            case NOTHING_TO_CLAIM -> RefusalCode.NOTHING_TO_TAKE;

            // The lease's one form rule.
            case CLAIM_DURATION_NOT_POSITIVE -> RefusalCode.CLAIM_DURATION_INVALID;

            // The bracket kind.
            case SELECTOR_NOT_DECLARED, SELECTOR_WITHDRAWN, SELECTOR_IN_USE ->
                RefusalCode.SELECTOR_UNKNOWN;

            // The scope itself. One to one, unlike the not-found group above:
            // these three say three different things to a caller and lead to
            // three different remedies, so narrowing any pair of them onto one
            // code would take away the only part a caller can act on. None of
            // them is folded into NOT_FOUND either — the directory answered for
            // the scope, so the caller can already see it, and hiding it now
            // would withhold nothing it did not already have.
            case SCOPE_KIND_UNSUPPORTED -> RefusalCode.SCOPE_KIND_UNSUPPORTED;
            case SCOPE_READ_ONLY -> RefusalCode.SCOPE_READ_ONLY;
            case SCOPE_LOCKED -> RefusalCode.SCOPE_LOCKED;

            // Form faults: an argument named, typed or shaped wrongly. Nothing
            // was written, so none of them carries a state.
            case ADDENDUM_SUFFIX_EXHAUSTED, METADATA_REFUSED,
                 FILTER_VALUE_REFUSED, CURATION_TARGET_SELF ->
                RefusalCode.ARGUMENT_INVALID;

            // The key that says "the call I already made" and was given to a
            // different one.
            case IDEMPOTENCY_KEY_REUSED -> RefusalCode.IDEMPOTENCY_KEY_REUSED;
            case FILTER_FIELD_UNKNOWN -> RefusalCode.ARGUMENT_UNKNOWN;
            // A required argument: the surface refuses each of them by name
            // before the kernel is called, so these arrive from it only if a
            // declaration and the kernel disagree on what a call needs.
            case UPDATE_EMPTY, ADDENDUM_TEXT_MISSING, RECEIPT_ABSENT,
                 CONFLICT_TOKEN_MISSING ->
                RefusalCode.ARGUMENT_MISSING;

            // The payload of an answer, and the two proofs of the commissioner.
            case ANSWER_NOT_AN_OPTION -> RefusalCode.ARGUMENT_INVALID;
            case CONFLICT_TOKEN_STALE -> RefusalCode.CONFLICT_TOKEN_STALE;

            // Ours, not the caller's.
            case SESSION_NOT_BOUND -> RefusalCode.UNEXPECTED_FAILURE;
        };
    }
}
