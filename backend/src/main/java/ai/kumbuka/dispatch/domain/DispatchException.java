package ai.kumbuka.dispatch.domain;

import java.util.List;

/**
 * A typed refusal, naming what was refused and why.
 *
 * <p>Every refusal in this service carries a {@link Reason} rather than only a
 * message. A caller that has to match on prose is a caller that breaks when
 * somebody improves the wording, and an adapter that cannot tell "you may not
 * do that yet" from "that does not exist" cannot map either onto its own
 * protocol without guessing.
 *
 * <p>The message is for a human and names the specifics; the reason is for a
 * caller and is stable.
 */
public class DispatchException extends RuntimeException {

    public enum Reason {
        /** The verb is not permitted from the current status. */
        TRANSITION_NOT_PERMITTED,
        /** The selector was never declared in this scope. */
        SELECTOR_NOT_DECLARED,
        /** The selector exists but was withdrawn. */
        SELECTOR_WITHDRAWN,
        /** A selector that has been used cannot be withdrawn. */
        SELECTOR_IN_USE,
        /**
         * An addendum was attached without its text.
         *
         * <p>Its own reason and not {@link #ADDENDUM_MALFORMED}, because the
         * remedy is different and a refusal is only worth anything if it
         * carries one: a malformed addendum is an address to correct, and
         * this is an argument to supply. Under the shared reason the caller
         * was told "a value given is not a value this call takes" and sent
         * looking at the address, which was right.
         */
        ADDENDUM_TEXT_MISSING,
        /** Letter suffixes past `z` are deferred, so this is refused rather than wrapped. */
        ADDENDUM_SUFFIX_EXHAUSTED,
        /** The scope could not be resolved against the platform's read contract. */
        SCOPE_UNRESOLVED,

        /**
         * The scope is of a kind this service does not serve.
         *
         * <p>A category statement and not a permission one. The platform's
         * read contract answers for every service, so it publishes private
         * scopes too; a private scope is a per-tenant container for memory
         * content and a task has no meaning in one. The caller is told
         * what the scheme carries, so that it stops rather than retries with
         * another token.
         *
         * <p>Declared under this name deliberately: the condition is the same
         * on every service behind the same contract, and a code that differed
         * per service would be two vocabularies for one condition (DEC-0042).
         */
        SCOPE_KIND_UNSUPPORTED,

        /**
         * The caller may read this scope but not write to it.
         *
         * <p>The write right is the platform's answer about the membership,
         * over a service channel. It is not about the task, not about the
         * caller's capacity, and not lifted by anything this service offers —
         * which is why it is its own reason rather than a reuse of the
         * transition refusal that would send the caller back to retry.
         */
        SCOPE_READ_ONLY,

        /**
         * The scope is locked, so it refuses every write.
         *
         * <p>Apart from {@link #SCOPE_READ_ONLY} because the remedies differ:
         * a lock is lifted where it was set, a missing write right is lifted
         * by whoever administers the membership. The platform derives the
         * write right as "not locked and …", so a locked scope also carries no
         * write right — the check order is what keeps this reason reachable,
         * and it is asserted rather than left to the reading order of a
         * method.
         */
        SCOPE_LOCKED,
        /** The session settings the read contract needs were not bound. */
        SESSION_NOT_BOUND,
        /** No task at that address. */
        NOT_FOUND,
        /** The caller has no subject, or no capacity the core can act on. */
        ACTOR_UNKNOWN,
        /**
         * The executing apparatus called the ratification verb.
         *
         * <p>Its own reason rather than a reuse of the transition refusal: the
         * transition IS permitted from this state, just not to this caller,
         * and an adapter that could not tell the two apart would report "you
         * cannot do that yet" where the truth is "not you, ever".
         */
        RATIFICATION_NOT_PERMITTED,
        /** A claim is required for what was asked, and the caller holds none. */
        CLAIM_REQUIRED,
        /** The receipt presented does not match the one held on the task. */
        RECEIPT_MISMATCH,
        /** A claim duration was zero or negative. */
        CLAIM_DURATION_NOT_POSITIVE,
        /** Metadata carried an assertion, or a URL carrying credentials. */
        METADATA_REFUSED,

        /**
         * A listing was narrowed by a field this scheme does not filter on.
         *
         * <p>Refused rather than ignored. An ignored filter answers with the
         * full set, which looks exactly like a correct narrow answer and gives
         * the caller nothing to notice it by — the one failure mode of a
         * filter that is worse than an outright refusal.
         */
        FILTER_FIELD_UNKNOWN,

        /**
         * A declared filter field carried a value it cannot take.
         *
         * <p>Its own reason rather than a reuse of the one above: the caller
         * asked for the right thing in the wrong terms, and matching it
         * against nothing would answer "there is nothing here" to a question
         * that was never asked.
         */
        FILTER_VALUE_REFUSED,

        /**
         * A curation named the task being curated as its own target.
         *
         * <p>Its own reason rather than a reuse of a filter refusal, which is
         * what it borrowed before. A surface has to word this one specifically
         * — the contract makes it {@code ARGUMENT_INVALID} on {@code into} —
         * and a reason shared with three other faults cannot be worded
         * specifically without reading the kernel's sentence, which is the one
         * thing a message may not do.
         */
        CURATION_TARGET_SELF,

        /**
         * The same idempotency key was presented for a different call.
         *
         * <p>The key says "this is the call I already made". Answering with
         * the first call's answer would discard this call's content silently,
         * so the only answer that cannot lose a write is a refusal.
         */
        IDEMPOTENCY_KEY_REUSED,

        /**
         * A draw from a set found nothing it could take.
         *
         * <p>Distinct from NOT_FOUND, which is about an address. This one says
         * the address was fine and the set is empty of anything claimable
         * right now — a caller retries the first and waits on the second.
         */
        NOTHING_TO_CLAIM,

        /**
         * An update arrived with no field set to write.
         *
         * <p>A form fault, not a state fault: an empty write is a call whose
         * verb is undefined, and it is refused rather than treated as a no-op.
         * A no-op would still rotate the conflict token, and a later reader
         * cannot distinguish that from a real one-field write it never made.
         */
        UPDATE_EMPTY,

        /**
         * A call that needs the receipt from the takeup arrived without one.
         *
         * <p>Split from {@link #CLAIM_REQUIRED}, which says the caller holds
         * no claim at all. This one says the claim may well be theirs and the
         * proof did not travel — a different thing for the caller to do about
         * it, and the surface contract declares a separate code for each.
         */
        RECEIPT_ABSENT,

        /**
         * A former holder called after its lease ended.
         *
         * <p>Apart from {@link #CLAIM_REQUIRED} because the caller did hold
         * the task, and what it needs to learn is that its time ran out — not
         * that it never had the task. Raised by the task kernel only.
         */
        LEASE_LAPSED,

        /**
         * A claim arrived before the instant a {@code defer} named.
         *
         * <p>The concept names this reason {@code NOT_BEFORE}, after the
         * attribute. That spelling leads with the negation, which the form
         * rule of refusal reasons refuses for every reason added after it; the
         * subject here is the deferral, and its state is that it still runs.
         */
        DEFERRAL_PENDING,

        /**
         * The call would close a bracket root that has unfinished children.
         *
         * <p>Names each child and hands out a confirmation; the same call
         * repeated with it withdraws the children and closes the root.
         * Replaces {@link #SIBLINGS_NON_TERMINAL} for the task kernel.
         */
        CONFIRMATION_REQUIRED,

        /** The set of unfinished children changed since the confirmation was handed out. */
        CONFIRMATION_STALE,

        /** An answer named none of the question's options, and free text was not admitted. */
        ANSWER_NOT_AN_OPTION,

        /**
         * A call that is fenced by the conflict token arrived without one.
         *
         * <p>The same condition the surface has refused under this name since
         * the conflict token existed; the task kernel checks the token itself
         * (concept section 2.3, check 5), so the reason moves into the
         * kernel's catalogue under the name it already carries on the wire.
         */
        CONFLICT_TOKEN_MISSING,

        /** The conflict token presented is not the one the task holds. */
        CONFLICT_TOKEN_STALE
    }

    private final transient Reason reason;
    private final transient List<String> offenders;
    private final transient String confirmation;

    public DispatchException(Reason reason, String message) {
        this(reason, message, List.of());
    }

    public DispatchException(Reason reason, String message, List<String> offenders) {
        this(reason, message, offenders, null);
    }

    /**
     * A refusal that hands out a confirmation.
     *
     * <p>Only {@link Reason#CONFIRMATION_REQUIRED} carries one: the value the
     * caller repeats its call with to confirm that the named children are to
     * be withdrawn.
     */
    public DispatchException(Reason reason, String message, List<String> offenders,
                             String confirmation) {
        super(message);
        this.reason = reason;
        this.offenders = List.copyOf(offenders);
        this.confirmation = confirmation;
    }

    public Reason reason() {
        return reason;
    }

    /**
     * The objects that caused the refusal, where naming them is the point.
     *
     * <p>A bracket that refuses to close because a sibling is unfinished must
     * say WHICH sibling. A refusal that only states the rule sends the reader
     * back to the store to work out what it was talking about, and the whole
     * value of checking at the transition rather than beside it is that the
     * answer is right there when the check runs.
     */
    public List<String> offenders() {
        return offenders;
    }

    /** The confirmation this refusal hands out, where it hands one out. */
    public java.util.Optional<String> confirmation() {
        return java.util.Optional.ofNullable(confirmation);
    }
}
