package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.ExchangeStatus;
import ai.kumbuka.dispatch.domain.Transition;

import java.util.List;
import java.util.Set;

/**
 * What one process verb does to the kernel, and what it needs before it can.
 *
 * <p>This is the join between section 5 of the contract and {@link Transition},
 * and it is the reason {@code next} needs no second table. A process verb is
 * offered exactly when the contract declares it from this state, the kernel's
 * own chain runs from this state, the caller takes part in the right way, and
 * its declared preconditions hold.
 *
 * <h2>Two sources for the from-states, and both must agree</h2>
 *
 * The kernel's chain is not the whole rule. {@code dispatch_cancel} is a {@code
 * close}, and the kernel permits {@code close} from {@code returned} too —
 * where it would discard an answer that has already been frozen, which is
 * precisely why section 5 declares the verb from {@code open}, {@code active}
 * and {@code needs_input} and not from there. So {@link #from} carries the
 * contract's own list and the chain carries the kernel's, and a verb is
 * offered only where both hold. Fail-closed on disagreement: a state the two
 * disagree about is a state where nothing is listed, never one where something
 * is listed and then refused.
 *
 * <p><strong>The preconditions are not a second state machine.</strong> Each
 * one is a property the kernel already checks at the transition itself: {@code
 * ratify} refuses without a return draft, {@code append} refuses on an unfrozen
 * exchange, {@code close} on a bracket root refuses while a child is
 * unfinished. Reading them here is reading the same condition from the same
 * object; inventing one here that the kernel does not enforce is what would
 * make the list lie.
 *
 * @param from the states section 5 declares this verb from
 */
public record VerbStep(ProcessVerb verb, Set<ExchangeStatus> from,
                       List<Transition> transitions, List<Precondition> preconditions) {

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
         * <p>{@code dispatch_accept_return} and {@code dispatch_curate_return}
         * declare it: section 6 replaces both with {@code
         * dispatch_close_bracket} on a root, because a bracket's record is
         * accepted by the call that also checks the bracket.
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
         * refuses.
         */
        IS_NOT_TERMINAL,

        /**
         * Every exchange of the bracket is finished, where this is its root.
         *
         * <p>Holds trivially at a child, which is what makes it the whole of
         * section 6's parenthetical "(root: only if every child is terminal)":
         * one condition covering both halves of the row rather than a rule
         * about roots and a silence about children. {@code
         * dispatch_close_bracket} pairs it with {@link #IS_BRACKET_ROOT} and so
         * reads as the strict version.
         */
        BRACKET_MAY_END
    }

    /**
     * Whether the contract declares this verb from {@code state} AND the
     * kernel's chain runs from there.
     *
     * <p>The chain is checked from its first step that is permitted here, not
     * from its literal head. {@code dispatch_accept_return} is {@code ratify}
     * then {@code close}; on an exchange that is already {@code returned} the
     * ratification has happened and the act is the close alone. Insisting on
     * the head would hide the verb on exactly the state section 6 lists it in.
     */
    public boolean reachableFrom(ExchangeStatus state) {
        return from.contains(state) && chainRunsFrom(state);
    }

    /**
     * Whether some suffix of the chain runs from here, end to end.
     *
     * <p>A suffix and not a subsequence: the steps of a compound verb happen
     * in order inside one transaction, and skipping one in the middle would be
     * a different act. What may be skipped is a prefix that has already
     * happened.
     */
    private boolean chainRunsFrom(ExchangeStatus state) {
        if (transitions.isEmpty()) {
            // The one that moves no state: add_correction. Its availability is
            // decided by its from-states and its preconditions alone.
            return true;
        }
        for (int start = 0; start < transitions.size(); start++) {
            if (!transitions.get(start).permittedFrom(state)) {
                continue;
            }
            ExchangeStatus at = transitions.get(start).to();
            boolean chains = true;
            for (int next = start + 1; next < transitions.size(); next++) {
                if (!transitions.get(next).permittedFrom(at)) {
                    chains = false;
                    break;
                }
                at = transitions.get(next).to();
            }
            if (chains) {
                return true;
            }
        }
        return false;
    }

    /** The state the exchange ends in, for the declaration's own record. */
    public ExchangeStatus result() {
        return transitions.isEmpty() ? null : transitions.get(transitions.size() - 1).to();
    }
}
