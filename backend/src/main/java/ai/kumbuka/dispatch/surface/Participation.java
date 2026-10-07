package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.HolderState;
import ai.kumbuka.dispatch.domain.TaskState;
import ai.kumbuka.dispatch.domain.TaskView;

import java.util.Locale;

/**
 * How one caller takes part in one task.
 *
 * <p>A property of the pair, not of the caller and not of the task: the same
 * executor holds the task it took up and is a candidate at the open one beside
 * it. The kernel decides with the relation of each row of the transition
 * table; this is the same relation named for a caller who is told which part
 * it has, in a refusal that says the call belongs to another.
 */
public enum Participation {

    /** Holds the commissioning capacity and may see the task. */
    COMMISSIONER,

    /** Holds the task: through a running lease, or paused or delivered. */
    HOLDER,

    /** An executor that could take the task up: the task is open. */
    CANDIDATE,

    /** Sees the task and takes no part in it now. */
    BYSTANDER;

    /** The part {@code caller} takes in the task whose head is {@code view}. */
    public static Participation of(Actor caller, TaskView view) {
        if (caller.isConsole()) {
            return COMMISSIONER;
        }
        if (view.holder() == HolderState.SELF) {
            return HOLDER;
        }
        return view.state() == TaskState.OPEN ? CANDIDATE : BYSTANDER;
    }

    /** The part as a refusal states it. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
