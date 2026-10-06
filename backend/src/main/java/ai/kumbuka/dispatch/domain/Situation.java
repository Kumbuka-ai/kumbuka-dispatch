package ai.kumbuka.dispatch.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A task as it effectively stands at one instant: what {@link Decision#of}
 * decides on.
 *
 * <h2>The effective state (concept section 2.1)</h2>
 *
 * A lapsed lease writes nothing back. {@link #of} turns what is stored into
 * what holds, and nothing else reads the stored state:
 *
 * <table>
 *   <caption>stored to effective</caption>
 *   <tr><th>stored</th><th>lease</th><th>lapses recorded</th><th>effective</th></tr>
 *   <tr><td>active</td><td>running</td><td>any</td><td>active; the holder holds</td></tr>
 *   <tr><td>active</td><td>lapsed</td><td>0 or 1</td><td>open; no holder</td></tr>
 *   <tr><td>active</td><td>lapsed</td><td>2 or more</td><td>on_hold, external; no holder</td></tr>
 *   <tr><td>any other</td><td>-</td><td>-</td><td>as stored</td></tr>
 * </table>
 *
 * <p>The lapse being judged is not recorded yet: the count rises when the next
 * executor takes the task up. So the lapse that parks is the one that would be
 * the {@link #LAPSES_TO_PARK}th recorded.
 *
 * @param identity           the task's durable identity
 * @param address            where the task lives in its scope
 * @param state              the effective state
 * @param holdReason         set exactly in {@code on_hold}
 * @param holder             the effective holder's subject, or null
 * @param storedHolder       the holder the row names, lapsed or not; the lock on
 *                           acceptance compares with it in {@code delivered}
 * @param lapsed             the row says {@code active} and its lease has ended
 * @param receiptHash        the stored hash of the holder's receipt
 * @param conflictToken      the token the row currently carries
 * @param notBefore          the instant a {@code defer} named, or null
 * @param questionOptions    the pending question's options, or null
 * @param root               whether the task is a bracket root
 * @param unfinishedChildren the children of a root that are not closed
 * @param now                the instant this situation was read at
 */
public record Situation(
    UUID identity,
    ExchangeAddress address,
    TaskState state,
    HoldReason holdReason,
    String holder,
    String storedHolder,
    boolean lapsed,
    String receiptHash,
    String conflictToken,
    Instant notBefore,
    Map<String, Object> questionOptions,
    boolean root,
    List<Child> unfinishedChildren,
    Instant now) {

    /**
     * The lapse that parks a task: the third.
     *
     * <p>After it the task is effectively {@code on_hold} with reason
     * {@code external} and has no holder (TAR-0004 section 3).
     */
    public static final int LAPSES_TO_PARK = 3;

    /** An unfinished child of a bracket root, as a confirmation names it. */
    public record Child(UUID identity, ExchangeAddress address, TaskState state) {
    }

    public Situation {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(now, "now");
        unfinishedChildren = List.copyOf(unfinishedChildren);
    }

    /**
     * What a task is at {@code now}.
     *
     * @param children the children of the task when it is a bracket root, in
     *                 any state; ignored otherwise
     */
    public static Situation of(Task task, Instant now, List<Task> children) {
        TaskState stored = task.storedState();
        boolean lapsed = stored == TaskState.ACTIVE && !leaseRuns(task, now);

        TaskState state = stored;
        HoldReason reason = task.storedHoldReason();
        String holder = task.storedHolder();
        if (lapsed) {
            boolean parks = task.lapseCount() + 1 >= LAPSES_TO_PARK;
            state = parks ? TaskState.ON_HOLD : TaskState.OPEN;
            reason = parks ? HoldReason.EXTERNAL : null;
            holder = null;
        }

        List<Child> unfinished = task.isBracketRoot()
            ? children.stream()
                .map(c -> new Child(c.uuid, c.address(), of(c, now, List.of()).state()))
                .filter(c -> c.state() != TaskState.CLOSED)
                .toList()
            : List.of();

        return new Situation(task.uuid, task.address(), state, reason, holder,
            task.storedHolder(), lapsed, task.receiptHash(), task.conflictToken(),
            task.notBefore(), task.questionOptions(), task.isBracketRoot(), unfinished, now);
    }

    /**
     * Whether a stored {@code active} row's lease still runs.
     *
     * <p>A row without a lease end counts as lapsed: every way into
     * {@code active} sets one, so an active row without one was written by
     * something other than this kernel, and treating it as held for ever would
     * leave the task unreachable.
     */
    private static boolean leaseRuns(Task task, Instant now) {
        return task.leaseExpiresAt() != null && task.leaseExpiresAt().isAfter(now);
    }

    /** Parked: its third lapse moved it to {@code on_hold} without a holder. */
    public boolean parked() {
        return lapsed && state == TaskState.ON_HOLD;
    }

    /** Whether a {@code defer} still keeps the task from being drawn. */
    public boolean deferred() {
        return notBefore != null && notBefore.isAfter(now);
    }

    /** Whether the caller held this task and its lease ran out. */
    public boolean formerHolder(Actor caller) {
        return lapsed && caller.subject().equals(storedHolder);
    }

    /** Whether the caller holds this task now. */
    public boolean heldBy(Actor caller) {
        return holder != null && holder.equals(caller.subject());
    }
}
