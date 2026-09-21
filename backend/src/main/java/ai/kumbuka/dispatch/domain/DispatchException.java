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

        /**
         * The scope is of a kind this service does not serve.
         *
         * <p>A category statement and not a permission one. The platform's
         * read contract answers for every service, so it publishes private
         * scopes too; a private scope is a per-tenant container for memory
         * content and an exchange has no meaning in one. The caller is told
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
         * over a service channel. It is not about the exchange, not about the
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
        /** The exchange already carries a ratified return. */
        RETURN_ALREADY_RATIFIED,
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
         * A curation named the exchange being curated as its own target.
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
         * There is no delivered answer to ratify.
         *
         * <p>Its own reason rather than a reuse of the transition refusal,
         * because the transition IS permitted from this state — the exchange
         * is simply carrying a question rather than an answer. A caller told
         * "the state does not allow it" would read that as "not yet from
         * here", when the truth is "not until the executor delivers".
         */
        RETURN_ABSENT,

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
         * An update after send needs a return draft to write.
         *
         * <p>After send the update verb writes the return role, and the
         * return role has one text-carrying field. An update that carries
         * only metadata has no answer to write; refusing it is more useful
         * than storing null in place of what the caller meant to say.
         */
        RETURN_DRAFT_REQUIRED
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
