package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.ExchangeStatus;
import ai.kumbuka.dispatch.domain.Transition;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What this caller can do with this exchange next, and what it is waiting for.
 *
 * <p><strong>One computation, two vocabularies.</strong> The question "which
 * calls succeed from here" is answered once, from {@link Transition} and the
 * caller's part in the exchange. What differs per surface is only the name each
 * answer goes by: the assistant surface says {@code dispatch_accept_return},
 * the generic surface says {@code accept} then {@code close}. Building two
 * computations would be building two answers to one question, and the first
 * time they disagreed the caller would be told it could do something it cannot.
 *
 * <h2>The promise this class makes</h2>
 *
 * Everything listed succeeds; everything omitted is refused from state or role.
 * That is checkable, and A3 checks it by driving an exchange through every
 * state in both roles and calling both halves of the list. It is also the
 * reason the preconditions of {@link VerbStep} exist: a call whose kernel
 * transition is permitted can still be refused for a reason the state does not
 * carry — no answer to accept, a bracket root where a child was meant — and
 * listing it would break the promise in the one way a caller cannot recover
 * from, because it would have followed the list.
 */
public final class NextCalculator {

    private NextCalculator() {
    }

    /** One offered call, and what it does, in one clause. */
    public record Step(String call, String does) {
    }

    /**
     * The state of the exchange as the calculation needs to see it.
     *
     * <p>A value type rather than the entity, because the calculation is pure
     * and is exercised by a unit test over all nine states times three
     * participations without a database. The four booleans are exactly the
     * preconditions of {@link VerbStep.Precondition} — if a fifth is ever
     * needed, it appears here and the compiler finds every caller.
     *
     * @param status          where the exchange stands
     * @param answerDelivered whether an answer is present to be accepted
     * @param bracketRoot     whether this is the {@code .0} of its bracket
     * @param frozen          whether the dispatch text is committed
     */
    public record Situation(ExchangeStatus status, boolean answerDelivered,
                            boolean bracketRoot, boolean frozen) {
    }

    /**
     * What each process verb does to the kernel.
     *
     * <p>Compound verbs carry their steps in order and the whole list runs in
     * one transaction. {@code dispatch_accept_return} is {@code ratify} then
     * {@code close} — which is why, on this surface, {@code returned} is a
     * state a caller never sees.
     */
    private static final Map<ProcessVerb, VerbStep> STEPS = steps();

    private static Map<ProcessVerb, VerbStep> steps() {
        Map<ProcessVerb, VerbStep> steps = new LinkedHashMap<>();

        // The commissioner's.
        steps.put(ProcessVerb.ADD_CORRECTION, new VerbStep(ProcessVerb.ADD_CORRECTION,
            List.of(), List.of(VerbStep.Precondition.IS_FROZEN,
                VerbStep.Precondition.IS_NOT_TERMINAL)));
        steps.put(ProcessVerb.ACCEPT_RETURN, new VerbStep(ProcessVerb.ACCEPT_RETURN,
            List.of(Transition.RATIFY, Transition.CLOSE),
            List.of(VerbStep.Precondition.ANSWER_DELIVERED,
                VerbStep.Precondition.IS_NOT_BRACKET_ROOT)));
        steps.put(ProcessVerb.CURATE_RETURN, new VerbStep(ProcessVerb.CURATE_RETURN,
            List.of(Transition.RATIFY, Transition.CONSUME),
            List.of(VerbStep.Precondition.ANSWER_DELIVERED,
                VerbStep.Precondition.IS_NOT_BRACKET_ROOT)));
        steps.put(ProcessVerb.REPLY_TO_EXECUTOR, new VerbStep(ProcessVerb.REPLY_TO_EXECUTOR,
            List.of(Transition.RESUME), List.of()));
        steps.put(ProcessVerb.CANCEL, new VerbStep(ProcessVerb.CANCEL,
            List.of(Transition.CLOSE),
            List.of(VerbStep.Precondition.IS_NOT_BRACKET_ROOT)));
        steps.put(ProcessVerb.CLOSE_BRACKET, new VerbStep(ProcessVerb.CLOSE_BRACKET,
            List.of(Transition.RATIFY, Transition.CLOSE),
            List.of(VerbStep.Precondition.ANSWER_DELIVERED,
                VerbStep.Precondition.IS_BRACKET_ROOT)));

        // The executor's.
        steps.put(ProcessVerb.TAKE, new VerbStep(ProcessVerb.TAKE,
            List.of(Transition.TAKEUP), List.of()));
        steps.put(ProcessVerb.DELIVER_RETURN, new VerbStep(ProcessVerb.DELIVER_RETURN,
            List.of(Transition.BLOCK), List.of()));
        steps.put(ProcessVerb.ASK_COMMISSIONER, new VerbStep(ProcessVerb.ASK_COMMISSIONER,
            List.of(Transition.BLOCK), List.of()));
        steps.put(ProcessVerb.DECLINE, new VerbStep(ProcessVerb.DECLINE,
            List.of(Transition.FAIL), List.of()));

        return Map.copyOf(steps);
    }

    /**
     * The one-clause description of each offered call.
     *
     * <p>Shorter than the tool description on purpose: {@code next} is read
     * inside an answer the caller already has, so it answers "which of these"
     * rather than "what is this". The tool list answers the second.
     */
    private static final Map<ProcessVerb, String> DOES = Map.ofEntries(
        Map.entry(ProcessVerb.ADD_CORRECTION,
            "Attaches a correction to the frozen text."),
        Map.entry(ProcessVerb.ACCEPT_RETURN,
            "Accepts the delivered answer and finishes the exchange."),
        Map.entry(ProcessVerb.CURATE_RETURN,
            "Accepts the answer and carries it forward into a named object."),
        Map.entry(ProcessVerb.REPLY_TO_EXECUTOR,
            "Sends it back with a message; the holder continues."),
        Map.entry(ProcessVerb.CANCEL,
            "Withdraws the commission and closes the exchange."),
        Map.entry(ProcessVerb.CLOSE_BRACKET,
            "Accepts the record on the root and finishes the bracket."),
        Map.entry(ProcessVerb.TAKE,
            "Takes the exchange up and returns a receipt."),
        Map.entry(ProcessVerb.DELIVER_RETURN,
            "Delivers your answer to the commissioner."),
        Map.entry(ProcessVerb.ASK_COMMISSIONER,
            "Asks the commissioner something you cannot decide."),
        Map.entry(ProcessVerb.DECLINE,
            "Declines the work. Final."));

    /**
     * The generic verbs of the REST surface, by the transition they make.
     *
     * <p>The generic surface names transitions, so its {@code next} is the set
     * of transitions permitted from the state — the same computation, read off
     * one level lower. {@code REVERT} is deliberately absent from the offered
     * set even though it is permitted from {@code active}: it discards an
     * unratified draft, and offering it beside {@code update} in a list a
     * caller follows would be offering a way to lose work as if it were a step
     * forward.
     */
    private static final Map<Transition, String> GENERIC_DOES = Map.of(
        Transition.SEND, "Freezes the dispatch and opens it to an executor.",
        Transition.TAKEUP, "Acquires the lease and mints the receipt.",
        Transition.REJECT, "Refuses the commission before takeup. Final.",
        Transition.FAIL, "Records a failure to complete. Final.",
        Transition.BLOCK, "Pauses; the commissioner is due.",
        Transition.RESUME, "The commissioner answered; back to work.",
        Transition.RATIFY, "Freezes the answer that is there.",
        Transition.CLOSE, "Closes the exchange. Final.",
        Transition.CONSUME, "Curates the answer forward. Final.");

    /**
     * The calls open to this caller, in the vocabulary of its surface.
     *
     * <p>Empty for a terminal exchange whichever surface asks, and empty for a
     * holder waiting on its commissioner. Both are states in which the honest
     * answer is that there is nothing to do, and {@link #waitingFor} says who
     * the wait is on.
     */
    public static List<Step> next(Surface surface, Situation situation,
                                  Participation participation) {
        return surface == Surface.MCP
            ? processNext(situation, participation)
            : genericNext(situation, participation);
    }

    private static List<Step> processNext(Situation situation, Participation participation) {
        List<Step> offered = new ArrayList<>();
        for (ProcessVerb verb : ProcessVerb.values()) {
            VerbStep step = STEPS.get(verb);
            if (step == null) {
                // The two readers and the two that address a collection. Always
                // available on a visible exchange, and section 6 says they are
                // not listed — a list of "you can also read it" on every answer
                // is noise a caller has to filter on every turn.
                continue;
            }
            if (open(step, situation, participation)) {
                offered.add(new Step(verb.call(), DOES.get(verb)));
            }
        }
        return List.copyOf(offered);
    }

    /**
     * Whether one process verb is open here.
     *
     * <p>Three gates in order: the caller's part in the exchange, the kernel's
     * own permission for the first transition, and the declared preconditions.
     * All three are readable from what the answer already holds, which is what
     * makes this computable inside the answer rather than as a second round
     * trip.
     */
    private static boolean open(VerbStep step, Situation situation,
                                Participation participation) {
        if (!participates(step.verb(), participation)) {
            return false;
        }
        if (!step.reachableFrom(situation.status())) {
            return false;
        }
        for (VerbStep.Precondition precondition : step.preconditions()) {
            if (!holds(precondition, situation)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the caller's part in the exchange admits this verb.
     *
     * <p>{@code dispatch_take} and {@code dispatch_decline} are declared with
     * {@link Participation#BYSTANDER} and mean something narrower than "anyone
     * who is not the commissioner": a caller that already holds the exchange
     * has no take to make, and its decline is the holder's. So the holder is
     * admitted to {@code dispatch_decline} as well — the one verb both an
     * un-taken and a taken-up caller can make, which is exactly what section
     * 5.2 describes ("Before you took it up this records a refusal of the
     * commission; after, a failure to complete it").
     */
    private static boolean participates(ProcessVerb verb, Participation participation) {
        if (verb == ProcessVerb.DECLINE) {
            return participation != Participation.COMMISSIONER;
        }
        if (verb == ProcessVerb.TAKE) {
            return participation == Participation.BYSTANDER;
        }
        return verb.role() == participation;
    }

    private static boolean holds(VerbStep.Precondition precondition, Situation situation) {
        return switch (precondition) {
            case ANSWER_DELIVERED -> situation.answerDelivered();
            case IS_BRACKET_ROOT -> situation.bracketRoot();
            case IS_NOT_BRACKET_ROOT -> !situation.bracketRoot();
            case IS_FROZEN -> situation.frozen();
            case IS_NOT_TERMINAL -> !situation.status().terminal();
        };
    }

    /**
     * The generic surface's list: the transitions permitted from this state,
     * under the same role rules.
     *
     * <p>Read straight off {@link Transition#permittedFrom}, which is the same
     * source the process list consults one level up. {@code RATIFY} stays with
     * the commissioner because the kernel binds it to the actor, and the three
     * executor transitions stay with the holder for the same reason.
     */
    private static List<Step> genericNext(Situation situation, Participation participation) {
        List<Step> offered = new ArrayList<>();
        for (Transition transition : Transition.values()) {
            if (!GENERIC_DOES.containsKey(transition)) {
                continue;
            }
            if (!transition.permittedFrom(situation.status())) {
                continue;
            }
            if (!genericParticipates(transition, participation)) {
                continue;
            }
            if (transition == Transition.RATIFY && !situation.answerDelivered()) {
                continue;
            }
            offered.add(new Step(transition.verb(), GENERIC_DOES.get(transition)));
        }
        return List.copyOf(offered);
    }

    /** Executor transitions for the holder, ratification for the commissioner. */
    private static boolean genericParticipates(Transition transition,
                                               Participation participation) {
        Set<Transition> holders = EnumSet.of(Transition.BLOCK, Transition.FAIL);
        Set<Transition> commissioners =
            EnumSet.of(Transition.RATIFY, Transition.RESUME, Transition.SEND);

        if (holders.contains(transition)) {
            return participation == Participation.HOLDER;
        }
        if (commissioners.contains(transition)) {
            return participation == Participation.COMMISSIONER;
        }
        if (transition == Transition.TAKEUP || transition == Transition.REJECT) {
            return participation == Participation.BYSTANDER;
        }
        // close and consume: the commissioner's administrative end.
        return participation == Participation.COMMISSIONER;
    }

    /**
     * Who the exchange is waiting for, where the caller can do nothing.
     *
     * <p>Null when {@code next} is non-empty: an exchange whose caller has
     * something to do is not waiting on anybody, and saying both would leave
     * the caller to work out which of the two answers is the real one.
     */
    public static String waitingFor(Situation situation, Participation participation,
                                    List<Step> next) {
        if (!next.isEmpty()) {
            return null;
        }
        if (situation.status().terminal()) {
            return "nobody: the exchange is finished";
        }
        return switch (situation.status()) {
            case NEEDS_INPUT -> "the commissioner";
            case OPEN, ACTIVE -> "the executor";
            // returned: the answer is frozen and the commissioner has not
            // finished it. A holder or a bystander waits on them.
            case RETURNED -> "the commissioner";
            // draft never reaches this surface; if one ever did, the honest
            // answer is that nobody is waiting because nobody can see it.
            case DRAFT -> "the commissioner";
            default -> null;
        };
    }
}
