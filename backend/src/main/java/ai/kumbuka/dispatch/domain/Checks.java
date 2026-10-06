package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.domain.Decision.Check;
import ai.kumbuka.dispatch.domain.Decision.Refused;
import ai.kumbuka.dispatch.domain.DispatchException.Reason;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The seven checks of {@link Decision}, one method each.
 *
 * <p>Each reads one part of a {@link TaskVerb} row against the
 * {@link TaskSituation} and the {@link TaskCall}, and answers empty or the
 * refusal. None knows its place in the order; {@link Decision#ORDER} does.
 */
final class Checks {

    private Checks() {
    }

    /** Check 1: the effective state is one the row applies in. */
    static Optional<Refused> state(TaskVerb verb, TaskSituation s, Actor caller) {
        if (verb.states().contains(s.state())) {
            return Optional.empty();
        }
        if (verb.relation() == TaskVerb.Relation.HOLDER && s.formerHolder(caller)) {
            return refuse(Check.STATE, Reason.LEASE_LAPSED, s.address() + " was held by the "
                + "caller, and the lease ended; the task is " + s.state().wireName()
                + " now. Take it up again where it is open.");
        }
        return refuse(Check.STATE, Reason.TRANSITION_NOT_PERMITTED, s.address() + " is "
            + s.state().wireName() + "; " + verb.wireName() + " applies only in "
            + names(verb.states()) + ".");
    }

    /** Check 2: the condition on an attribute, where the row has one. */
    static Optional<Refused> condition(TaskVerb verb, TaskSituation s) {
        return switch (verb.condition()) {
            case NONE -> Optional.empty();
            case DRAWABLE -> s.deferred()
                ? refuse(Check.CONDITION, Reason.DEFERRAL_PENDING, s.address() + " was "
                    + "deferred and is not drawable before " + s.notBefore() + ".")
                : Optional.empty();
            case QUESTION_PENDING -> s.holdReason() == HoldReason.QUESTION
                ? Optional.empty()
                : refuse(Check.CONDITION, Reason.TRANSITION_NOT_PERMITTED, s.address()
                    + " waits on no question; " + verb.wireName() + " answers one.");
            case PAUSED_BY_HOLDER -> s.holdReason() == HoldReason.DEPENDENCY
                    || s.holdReason() == HoldReason.EXTERNAL
                ? Optional.empty()
                : refuse(Check.CONDITION, Reason.TRANSITION_NOT_PERMITTED, s.address()
                    + " is paused for a question; the commissioner's answer continues it.");
        };
    }

    /** Check 3: the caller's relation to the task. */
    static Optional<Refused> relation(TaskVerb verb, TaskSituation s, Actor caller) {
        return switch (verb.relation()) {
            case COMMISSIONER -> caller.isConsole()
                ? Optional.empty()
                : refuse(Check.RELATION, Reason.ACTOR_UNKNOWN, verb.wireName() + " is the "
                    + "commissioner's call, and the caller does not hold the commissioning "
                    + "capacity.");
            case CANDIDATE -> caller.isExecutor()
                ? Optional.empty()
                : refuse(Check.RELATION, Reason.ACTOR_UNKNOWN, verb.wireName() + " is an "
                    + "executor's call, and the caller does not hold the executing capacity.");
            case HOLDER -> holder(verb, s, caller);
        };
    }

    private static Optional<Refused> holder(TaskVerb verb, TaskSituation s, Actor caller) {
        if (s.heldBy(caller)) {
            return Optional.empty();
        }
        if (s.formerHolder(caller)) {
            return refuse(Check.RELATION, Reason.LEASE_LAPSED, "the caller's lease on "
                + s.address() + " ended, and " + verb.wireName() + " is the holder's call.");
        }
        return refuse(Check.RELATION, Reason.CLAIM_REQUIRED, verb.wireName() + " is the "
            + "holder's call, and " + s.address() + " is not held by the caller.");
    }

    /** Check 4: the lock on acceptance -- never the identity that delivered. */
    static Optional<Refused> lock(TaskVerb verb, TaskSituation s, Actor caller) {
        if (verb.lock() == TaskVerb.Lock.NOT_THE_DELIVERER
                && caller.subject().equals(s.storedHolder())) {
            return refuse(Check.LOCK, Reason.RATIFICATION_NOT_PERMITTED, "the identity that "
                + "delivered " + s.address() + " cannot accept it, whatever capacity it "
                + "carries: an executor that accepted its own answer would be reviewing itself.");
        }
        return Optional.empty();
    }

    /** Check 5: the receipt or the conflict token. */
    static Optional<Refused> proof(TaskVerb verb, TaskSituation s, TaskCall call) {
        return switch (verb.proof()) {
            case NONE -> Optional.empty();
            case RECEIPT -> receipt(s, call.receipt());
            case CONFLICT_TOKEN -> conflictToken(s, call.conflictToken());
        };
    }

    private static Optional<Refused> receipt(TaskSituation s, String presented) {
        if (presented == null || presented.isBlank()) {
            return refuse(Check.PROOF, Reason.RECEIPT_ABSENT, s.address() + " needs the "
                + "receipt its claim handed out: every write by an executor carries it.");
        }
        if (!Receipt.matches(presented, s.receiptHash())) {
            return refuse(Check.PROOF, Reason.RECEIPT_MISMATCH, "the receipt presented for "
                + s.address() + " is not the one it holds.");
        }
        return Optional.empty();
    }

    static Optional<Refused> conflictToken(TaskSituation s, String presented) {
        if (presented == null || presented.isBlank()) {
            return refuse(Check.PROOF, Reason.CONFLICT_TOKEN_MISSING, "this call on "
                + s.address() + " carries the conflict token of the caller's last read.");
        }
        if (!presented.equals(s.conflictToken())) {
            return refuse(Check.PROOF, Reason.CONFLICT_TOKEN_STALE, "the conflict token is "
                + "not the one " + s.address() + " holds; it was written since the caller "
                + "read it.");
        }
        return Optional.empty();
    }

    /** Check 6: closing a bracket root with unfinished children needs a confirmation. */
    static Optional<Refused> confirmation(TaskVerb verb, TaskSituation s, TaskCall call) {
        if (!verb.closes() || !s.root() || s.unfinishedChildren().isEmpty()) {
            return Optional.empty();
        }
        String expected = Decision.confirmationFor(verb, s);
        if (expected.equals(call.confirmation())) {
            return Optional.empty();
        }
        List<String> children = s.unfinishedChildren().stream()
            .map(c -> c.address() + " (" + c.state().wireName() + ")")
            .toList();
        if (call.confirmation() == null) {
            return Optional.of(new Refused(Check.CONFIRMATION, Reason.CONFIRMATION_REQUIRED,
                s.address() + " has " + children.size() + " unfinished child(ren): "
                    + String.join(", ", children) + ". Repeat the call with the "
                    + "confirmation to withdraw them and close the root.",
                children, expected));
        }
        return Optional.of(new Refused(Check.CONFIRMATION, Reason.CONFIRMATION_STALE,
            "the unfinished children of " + s.address() + " changed since the confirmation "
                + "was handed out; they are now " + String.join(", ", children) + ".",
            children, expected));
    }

    /** Check 7: what the payload carries is admissible for this task. */
    static Optional<Refused> payload(TaskVerb verb, TaskSituation s, TaskCall call) {
        TaskInput payload = verb.payloadOf(call);
        try {
            return switch (payload) {
                case TaskInput.Delivery delivery -> metadata(delivery.metadata());
                case TaskInput.Lease lease -> positive(lease);
                case TaskInput.Reply reply -> option(s, reply);
                case TaskInput.Nothing ignored -> Optional.empty();
                case TaskInput.Remark ignored -> Optional.empty();
                case TaskInput.RequiredRemark ignored -> Optional.empty();
                case TaskInput.Deferral ignored -> Optional.empty();
                case TaskInput.Question ignored -> Optional.empty();
                case TaskInput.Pause ignored -> Optional.empty();
            };
        } catch (DispatchException refused) {
            return refuse(Check.PAYLOAD, refused.reason(), refused.getMessage());
        }
    }

    private static Optional<Refused> metadata(Map<String, Object> metadata) {
        Metadata.validate(metadata);
        return Optional.empty();
    }

    private static Optional<Refused> positive(TaskInput.Lease lease) {
        if (lease.duration().isZero() || lease.duration().isNegative()) {
            return refuse(Check.PAYLOAD, Reason.CLAIM_DURATION_NOT_POSITIVE, "a lease must "
                + "be positive, was " + lease.duration() + ".");
        }
        return Optional.empty();
    }

    private static Optional<Refused> option(TaskSituation s, TaskInput.Reply reply) {
        Map<String, Object> asked = s.questionOptions() == null ? Map.of() : s.questionOptions();
        List<?> options = asked.get("options") instanceof List<?> l ? l : List.of();
        boolean freeText = Boolean.TRUE.equals(asked.get("free_text"));
        boolean admitted = reply.option() != null ? options.contains(reply.option()) : freeText;
        if (admitted) {
            return Optional.empty();
        }
        return refuse(Check.PAYLOAD, Reason.ANSWER_NOT_AN_OPTION, "the answer to the question "
            + "on " + s.address() + " names one of " + options
            + (freeText ? " or carries text." : "; free text was not admitted."));
    }

    private static Optional<Refused> refuse(Check check, Reason reason, String message) {
        return Optional.of(new Refused(check, reason, message, List.of(), null));
    }

    private static String names(Set<TaskState> states) {
        return states.stream().map(TaskState::wireName).sorted().toList().toString();
    }
}
