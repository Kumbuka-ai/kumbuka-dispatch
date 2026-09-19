package ai.kumbuka.dispatch.surface;

/**
 * Every reason this surface can refuse with — the closed set of section 4.4.
 *
 * <p><strong>A reason not in this enum cannot be returned.</strong> That is
 * not a convention kept by review: {@link ReasonCatalogue} holds the message
 * pattern and the remedy for each of these, and the start-up guard refuses to
 * bring the service up if the two sets differ in either direction. A code with
 * no pattern is a refusal nobody can read; a pattern with no code is a promise
 * to a caller that nothing can keep.
 *
 * <p>These are the caller's vocabulary, not the kernel's. The kernel's
 * {@code DispatchException.Reason} stays where it is and says what the domain
 * refused; this says what the caller is told. The mapping between them is one
 * switch with no default, in {@link ReasonMapping} — so a reason added to the
 * kernel is a compile error here rather than a refusal that escapes as an
 * unexpected failure.
 */
public enum RefusalCode {

    /**
     * Nothing is visible at the address given.
     *
     * <p>The one deliberately indistinguishable refusal. It does not exist,
     * the scope is not visible, and the address cannot be routed all answer
     * this, byte-identically and with no {@code data} — which is why it alone
     * names neither the call nor the address. The caller knows both from its
     * own request; an attacker learns nothing about which of the three it hit.
     */
    NOT_FOUND,

    /** The exchange is real and its state does not permit this call. */
    STATE_DOES_NOT_ALLOW,

    /** The call belongs to the other role. */
    ROLE_DOES_NOT_ALLOW,

    /** Somebody else holds the exchange. */
    NOT_THE_HOLDER,

    /** A call that needs the take's receipt arrived without one. */
    RECEIPT_MISSING,

    /** A receipt arrived and is not the one this exchange issued. */
    RECEIPT_WRONG,

    /** There is nothing delivered to accept. */
    NO_ANSWER_DELIVERED,

    /** The bracket has unfinished exchanges; they travel in {@code data.offenders}. */
    CHILDREN_NOT_FINISHED,

    /** The draw found nothing free. About a set, never about an address. */
    NOTHING_TO_TAKE,

    /** A claim duration was absent, malformed, zero or negative. */
    CLAIM_DURATION_INVALID,

    /** An argument the call does not declare, at any depth. Nothing was written. */
    ARGUMENT_UNKNOWN,

    /** An argument the call requires did not arrive. */
    ARGUMENT_MISSING,

    /** An argument arrived with a value it cannot take. */
    ARGUMENT_INVALID,

    /** A call that repeats the conflict token arrived without one. */
    CONFLICT_TOKEN_MISSING,

    /** The token presented is not the one the exchange holds. */
    CONFLICT_TOKEN_STALE,

    /** The bracket kind is not declared in this scope. */
    SELECTOR_UNKNOWN,

    /**
     * The same idempotency key was used for a different call.
     *
     * <p>A key means "this is the call I already made". Answering a caller
     * that reused one with different arguments as though it were that call
     * would silently discard the second call's content; refusing it is the
     * only answer that cannot lose a write.
     */
    IDEMPOTENCY_KEY_REUSED,

    /**
     * The call exists on this surface and not at this address depth.
     *
     * <p>{@code dispatch_close_bracket} at a child, {@code dispatch_take} at a
     * collection. Distinct from {@link #ARGUMENT_INVALID}, which the
     * predecessor used here: the address is well formed and names something
     * real, and what is wrong is the pairing of the call with it. A caller
     * told its address is invalid goes looking for a typo it does not have.
     */
    CALL_NOT_AT_THIS_ADDRESS,

    /**
     * A defect on our side, reported as one.
     *
     * <p>The only code whose message says the rule was not the problem. It
     * carries a reference so the caller can report it and nothing else — and
     * it states that nothing was changed, because a caller that cannot tell a
     * failed write from a partial one has to assume the worst and retry into
     * a half-done state.
     */
    UNEXPECTED_FAILURE
}
