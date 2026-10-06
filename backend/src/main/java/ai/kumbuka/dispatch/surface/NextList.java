package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.HoldReason;
import ai.kumbuka.dispatch.domain.HolderState;
import ai.kumbuka.dispatch.domain.TaskState;
import ai.kumbuka.dispatch.domain.TaskVerb;
import ai.kumbuka.dispatch.domain.TaskView;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The calls open to a caller, with one sentence each, and what the task waits
 * for where none is (TAR-0004 section 7).
 *
 * <p>The list is the kernel's: {@link TaskView#next()} is {@code
 * Decision.openVerbs}, the transitions whose situational checks pass for this
 * caller, in table order, each with the sentence of its row. This class names
 * them on the caller's surface and adds one call the table cannot list,
 * because it is not a transition: {@code read_text}, first, at the two points
 * of the main course where the text is what the caller needs next (concept
 * section 3.4) — for the holder right after a claim, and for the commissioner
 * of a delivered task, before it accepts.
 */
public final class NextList {

    /** The first entry of {@code next} after a claim: concept section 3.5, word for word. */
    static final String READ_THE_COMMISSION =
        "Reads the commission. Do this first: the claim did not return it.";

    /** The first entry for the commissioner of a delivered task. */
    static final String READ_THE_ANSWER =
        "Reads the delivered answer, part \"return\". Do this before you accept it.";

    private NextList() {
    }

    /** One open call: its name on the caller's surface, and what it does. */
    public record Step(String call, String does) {
    }

    /**
     * The calls open to {@code caller} on the task whose head is {@code view}.
     *
     * @param afterClaim whether this answers the claim that just made the
     *                   caller its holder
     */
    public static List<Step> of(Surface surface, TaskView view, Actor caller,
                                boolean afterClaim) {
        List<Step> steps = new ArrayList<>();
        if (afterClaim && view.holder() == HolderState.SELF) {
            steps.add(new Step(ProcessVerb.READ_TEXT.on(surface), READ_THE_COMMISSION));
        } else if (caller.isConsole() && view.state() == TaskState.DELIVERED) {
            steps.add(new Step(ProcessVerb.READ_TEXT.on(surface), READ_THE_ANSWER));
        }
        view.next().stream()
            .map(verb -> new Step(ProcessVerb.of(verb).on(surface), verb.nextSentence()))
            .forEach(steps::add);
        return List.copyOf(steps);
    }

    /**
     * What the task waits for, where nothing is open to the caller; null
     * otherwise.
     *
     * <p>A parked task -- on hold without a holder, after its third lapsed
     * lease -- waits for a person: only the web interface moves it out of the
     * hold, or the commissioner withdraws it.
     */
    public static String waitingFor(TaskView view, List<Step> next, Instant now) {
        if (!next.isEmpty()) {
            return null;
        }
        return switch (view.state()) {
            case DRAFT -> "the commissioner, to send it";
            case OPEN -> view.notBefore() != null && view.notBefore().isAfter(now)
                ? "the instant it was deferred to, " + view.notBefore()
                    + ", and then an executor to take it up"
                : "an executor to take it up";
            case ACTIVE -> "its holder";
            case ON_HOLD -> onHold(view);
            case DELIVERED -> "the commissioner, to accept the answer or send it back";
            case CLOSED -> "nothing: the task is closed";
        };
    }

    private static String onHold(TaskView view) {
        if (view.holdReason() == HoldReason.QUESTION) {
            return "the commissioner, to answer the question";
        }
        if (view.holder() == HolderState.NOBODY) {
            return "a person, to move it out of the hold through the web interface, or the "
                + "commissioner, to withdraw it: its lease lapsed three times and it has no "
                + "holder";
        }
        return "its holder, to resume it";
    }
}
