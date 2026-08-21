package ai.kumbuka.dispatch.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * The verbs, and the one transition each of them makes.
 *
 * <p><strong>Each transition is reached through exactly one verb.</strong>
 * That is the whole point of naming them here rather than letting callers set
 * a status: a generic status write would let any state be reached from any
 * other, and the freeze — which is a rule about a transition, not about a
 * field — could then be walked straight past.
 *
 * <p>The table is data rather than a chain of conditionals so that the
 * question "what can reach REJECTED" has one place to be answered, and so a
 * test can enumerate the whole machine instead of asserting one path at a
 * time.
 */
public enum Transition {

    /** Freezes the dispatch. */
    SEND("send", EnumSet.of(ExchangeStatus.DRAFT), ExchangeStatus.OPEN),
    /** Awards the receipt. */
    TAKEUP("takeup", EnumSet.of(ExchangeStatus.OPEN), ExchangeStatus.ACTIVE),
    /** From OPEN only: a refusal says the commission was wrong. */
    REJECT("reject", EnumSet.of(ExchangeStatus.OPEN), ExchangeStatus.REJECTED),
    /** From ACTIVE only: a failure says the work was. */
    FAIL("fail", EnumSet.of(ExchangeStatus.ACTIVE), ExchangeStatus.FAILED),
    /** A pause. The holder is kept. */
    BLOCK("block", EnumSet.of(ExchangeStatus.ACTIVE), ExchangeStatus.NEEDS_INPUT),
    /** The commissioner answered. */
    RESUME("resume", EnumSet.of(ExchangeStatus.NEEDS_INPUT), ExchangeStatus.ACTIVE),
    /** Freezes the handover, in one transaction with the write of the answer. */
    RATIFY("ratify", EnumSet.of(ExchangeStatus.ACTIVE, ExchangeStatus.NEEDS_INPUT),
        ExchangeStatus.RETURNED),
    /** Administrative closure, with or without an answer. */
    CLOSE("close", EnumSet.of(ExchangeStatus.OPEN, ExchangeStatus.ACTIVE,
        ExchangeStatus.RETURNED, ExchangeStatus.NEEDS_INPUT), ExchangeStatus.CLOSED),
    /** Curated forward into a named object. */
    CONSUME("consume", EnumSet.of(ExchangeStatus.RETURNED), ExchangeStatus.CONSUMED),
    /** The one way back: a takeup undone. */
    REVERT("revert", EnumSet.of(ExchangeStatus.ACTIVE), ExchangeStatus.OPEN);

    private final String verb;
    private final Set<ExchangeStatus> from;
    private final ExchangeStatus to;

    Transition(String verb, Set<ExchangeStatus> from, ExchangeStatus to) {
        this.verb = verb;
        this.from = from;
        this.to = to;
    }

    public String verb() {
        return verb;
    }

    public Set<ExchangeStatus> from() {
        return from;
    }

    public ExchangeStatus to() {
        return to;
    }

    public boolean permittedFrom(ExchangeStatus current) {
        return from.contains(current);
    }

    /**
     * Whether applying this verb to an exchange already in the target state is
     * a successful no-op rather than an error.
     *
     * <p>Only the terminating verbs. A retried sequence across two services
     * cannot complete otherwise: the retry sees an exchange it already
     * terminated, and an error there would leave the sequence permanently
     * half-done. Re-sending or re-taking-up is a different matter — those
     * carry content and a holder, and repeating them silently would hide a
     * real conflict.
     */
    public boolean idempotentWhenAlreadyThere() {
        return to.terminal();
    }
}
