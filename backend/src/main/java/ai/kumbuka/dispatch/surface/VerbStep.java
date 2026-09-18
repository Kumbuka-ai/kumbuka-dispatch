package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.ExchangeStatus;
import ai.kumbuka.dispatch.domain.Transition;

import java.util.List;

/**
 * What one process verb does to the kernel, and what it needs before it can.
 *
 * <p>This is the join between section 5 of the contract and {@link Transition},
 * and it is the reason {@code next} needs no second table. A process verb is
 * offered exactly when its first kernel transition is permitted from the
 * current state, its caller takes part in the right way, and its declared
 * preconditions hold. Every row of section 6 falls out of that — it was
 * checked row by row and no row needed an exception.
 *
 * <p><strong>The preconditions are not a second state machine.</strong> Each
 * one is a property the kernel already checks at the transition itself: {@code
 * ratify} refuses without a return draft, {@code append} refuses on an unfrozen
 * exchange, {@code cancel} is declared not to be for a bracket root. Reading
 * them here is reading the same condition from the same object; inventing one
 * here that the kernel does not enforce is what would make the list lie, and
 * that is the failure the stop condition of this build names.
 */
public record VerbStep(ProcessVerb verb, List<Transition> transitions,
                       List<Precondition> preconditions) {

    /**
     * A condition on the exchange that decides whether a call can succeed, over
     * and above its state and the caller's part in it.
     *
     * <p>Each of these is readable from the exchange the caller is looking at,
     * which is what makes {@code next} computable at all. A condition that
     * needed a second query — "is there a free executor", say — could not be
     * answered while building an answer, and a list built without it would
     * offer calls that fail.
     */
    public enum Precondition {

        /**
         * The exchange carries a delivered answer.
         *
         * <p>What tells the two meanings of {@code needs_input} apart. The
         * kernel checks it at {@code ratify} ("has no return draft to ratify"),
         * so a call offered without it would be refused for a reason the list
         * did not know — exactly the shape this build is told to stop on.
         */
        ANSWER_DELIVERED,

        /** The exchange is a bracket root. {@code dispatch_close_bracket} only. */
        IS_BRACKET_ROOT,

        /**
         * The exchange is NOT a bracket root.
         *
         * <p>{@code dispatch_cancel} declares it, in section 5's own words:
         * "Not for a bracket root; a bracket is finished with
         * dispatch_close_bracket."
         */
        IS_NOT_BRACKET_ROOT,

        /** The exchange is frozen, so a correction has something to correct. */
        IS_FROZEN,

        /**
         * The exchange is not finished.
         *
         * <p>{@code dispatch_add_correction} needs it. A correction closes
         * together with what it corrects, and that cascade runs at the base's
         * terminal transition — so one attached afterwards would be
         * non-terminal for ever, hanging off a finished exchange. The kernel
         * refuses it; without this the list would offer a call the kernel then
         * refuses, which is the one shape this build is told to stop on.
         */
        IS_NOT_TERMINAL
    }

    /**
     * Whether the kernel would let this verb's first transition run from
     * {@code state}.
     *
     * <p>Only the first: a compound verb runs its steps in one transaction, and
     * the later ones start from a state the earlier one produced. Checking the
     * whole chain here would ask whether {@code close} is permitted from the
     * state the exchange is in NOW rather than from {@code returned}, and would
     * hide {@code dispatch_accept_return} on every exchange that has one.
     */
    public boolean reachableFrom(ExchangeStatus state) {
        if (transitions.isEmpty()) {
            // The two that move no state: add_correction and the readers. Their
            // availability is decided by their preconditions alone.
            return true;
        }
        return transitions.get(0).permittedFrom(state);
    }

    /** The state the exchange ends in, for the declaration's own record. */
    public ExchangeStatus result() {
        return transitions.isEmpty() ? null : transitions.get(transitions.size() - 1).to();
    }
}
