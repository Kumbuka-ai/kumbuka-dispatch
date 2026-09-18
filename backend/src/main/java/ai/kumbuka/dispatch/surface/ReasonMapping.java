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
            case NOT_FOUND, SCOPE_UNRESOLVED, ADDENDUM_NOT_DRAWABLE -> RefusalCode.NOT_FOUND;

            // The object is real and its state says no.
            case TRANSITION_NOT_PERMITTED, FROZEN, RETURN_ALREADY_RATIFIED ->
                RefusalCode.STATE_DOES_NOT_ALLOW;

            // Not this caller, ever.
            case RATIFICATION_NOT_PERMITTED, ACTOR_UNKNOWN -> RefusalCode.ROLE_DOES_NOT_ALLOW;

            // Somebody else holds it, or the proof is wrong.
            case CLAIM_REQUIRED -> RefusalCode.NOT_THE_HOLDER;
            case RECEIPT_ABSENT -> RefusalCode.RECEIPT_MISSING;
            case RECEIPT_MISMATCH -> RefusalCode.RECEIPT_WRONG;

            // The exchange is real, the caller is entitled, and there is simply
            // nothing delivered yet.
            case RETURN_ABSENT -> RefusalCode.NO_ANSWER_DELIVERED;

            // The bracket's own gate.
            case SIBLINGS_NON_TERMINAL -> RefusalCode.CHILDREN_NOT_FINISHED;

            // A set with nothing free in it.
            case NOTHING_TO_CLAIM -> RefusalCode.NOTHING_TO_TAKE;

            // The lease's one form rule.
            case CLAIM_DURATION_NOT_POSITIVE -> RefusalCode.CLAIM_DURATION_INVALID;

            // The bracket kind.
            case SELECTOR_NOT_DECLARED, SELECTOR_WITHDRAWN, SELECTOR_IN_USE ->
                RefusalCode.SELECTOR_UNKNOWN;

            // Form faults: an argument named, typed or shaped wrongly. Nothing
            // was written, so none of them carries a state.
            case NUMBER_NOT_ACCEPTED, HOLDER_NOT_ACCEPTED -> RefusalCode.ARGUMENT_UNKNOWN;
            case ADDENDUM_MALFORMED, ADDENDUM_SUFFIX_EXHAUSTED, METADATA_REFUSED,
                 FILTER_VALUE_REFUSED -> RefusalCode.ARGUMENT_INVALID;
            case FILTER_FIELD_UNKNOWN -> RefusalCode.ARGUMENT_UNKNOWN;
            case UPDATE_EMPTY, RETURN_DRAFT_REQUIRED -> RefusalCode.ARGUMENT_MISSING;

            // Ours, not the caller's.
            case SESSION_NOT_BOUND -> RefusalCode.UNEXPECTED_FAILURE;
        };
    }
}
