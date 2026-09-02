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
        /** A field frozen at send or at ratification was written. */
        FROZEN,
        /** The bracket cannot terminate: a sibling is still non-terminal. */
        SIBLINGS_NON_TERMINAL,
        /** The selector was never declared in this scope. */
        SELECTOR_NOT_DECLARED,
        /** The selector exists but was withdrawn. */
        SELECTOR_WITHDRAWN,
        /** A selector that has been used cannot be withdrawn. */
        SELECTOR_IN_USE,
        /** An addendum was addressed with a regular sub-number instead of a letter. */
        ADDENDUM_MALFORMED,
        /** An addendum was asked for on its own. It is never independently drawable. */
        ADDENDUM_NOT_DRAWABLE,
        /** Letter suffixes past `z` are deferred, so this is refused rather than wrapped. */
        ADDENDUM_SUFFIX_EXHAUSTED,
        /** A caller supplied a number. Numbers are allocated, never accepted. */
        NUMBER_NOT_ACCEPTED,
        /** The scope could not be resolved against the platform's read contract. */
        SCOPE_UNRESOLVED,
        /** The session settings the read contract needs were not bound. */
        SESSION_NOT_BOUND,
        /** No exchange at that address. */
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
        /** The receipt presented does not match the one held on the exchange. */
        RECEIPT_MISMATCH,
        /** A caller supplied a holder identifier. Holders are minted, never accepted. */
        HOLDER_NOT_ACCEPTED,
        /** A claim duration was zero or negative. */
        CLAIM_DURATION_NOT_POSITIVE,
        /** The exchange already carries a ratified handover. */
        HANDOVER_ALREADY_RATIFIED,
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
         * A draw from a set found nothing it could take.
         *
         * <p>Distinct from NOT_FOUND, which is about an address. This one says
         * the address was fine and the set is empty of anything claimable
         * right now — a caller retries the first and waits on the second.
         */
        NOTHING_TO_CLAIM
    }

    private final transient Reason reason;
    private final transient List<String> offenders;

    public DispatchException(Reason reason, String message) {
        this(reason, message, List.of());
    }

    public DispatchException(Reason reason, String message, List<String> offenders) {
        super(message);
        this.reason = reason;
        this.offenders = List.copyOf(offenders);
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
}
