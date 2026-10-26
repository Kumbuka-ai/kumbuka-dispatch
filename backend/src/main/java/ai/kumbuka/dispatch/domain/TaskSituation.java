/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import org.jspecify.annotations.Nullable;

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
 * @param holdReason         set exactly in {@code on_hold}; absent in every other state
 * @param outcome            set exactly in {@code closed}; absent in every other state
 * @param holder             the effective holder's subject; absent where nobody holds
 *                           the task, a lapsed lease included
 * @param storedHolder       the holder the row names, lapsed or not; the lock on
 *                           acceptance compares with it in {@code delivered}; absent
 *                           where the row names none
 * @param lapsed             the row says {@code active} and its lease has ended
 * @param receiptHash        the stored hash of the holder's receipt; absent with the
 *                           stored holder
 * @param conflictToken      the token the row currently carries
 * @param notBefore          the instant a {@code defer} named; absent where none stands
 * @param questionOptions    the pending question's options; absent without a question
 * @param root               whether the task is a bracket root
 * @param unfinishedChildren the children of a root that are not closed
 * @param takenUp            whether an executor ever took the task up: see
 *                           {@link #takenUp(Task, Beside)}
 * @param childrenPresent    whether the task is a bracket root with a task under it,
 *                           in any state
 * @param now                the instant this situation was read at
 */
public record TaskSituation(
    UUID identity,
    ExchangeAddress address,
    TaskState state,
    @Nullable HoldReason holdReason,
    @Nullable Outcome outcome,
    @Nullable String holder,
    @Nullable String storedHolder,
    boolean lapsed,
    @Nullable String receiptHash,
    String conflictToken,
    @Nullable Instant notBefore,
    @Nullable Map<String, Object> questionOptions,
    boolean root,
    List<Child> unfinishedChildren,
    boolean takenUp,
    boolean childrenPresent,
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

    /**
     * What a situation reads beside the task's own row.
     *
     * @param workTexts       whether a text of the work stands beside the commission:
     *                        a return, a question, an answer or a remark
     * @param childrenPresent whether the task is a bracket root with a task under it,
     *                        in any state, a draft included
     */
    public record Beside(boolean workTexts, boolean childrenPresent) {

        /** Nothing beside the row: the commission's texts alone, and no task under it. */
        public static final Beside NOTHING = new Beside(false, false);

        /** What the texts that exist say, and whether a task stands under the root. */
        public static Beside of(List<TaskView.TextHead> texts, boolean childrenPresent) {
            return new Beside(texts.stream().anyMatch(t -> t.type() != TextType.DISPATCH),
                childrenPresent);
        }
    }

    public TaskSituation {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(now, "now");
        unfinishedChildren = List.copyOf(unfinishedChildren);
    }

    /**
     * What a task is at {@code now}.
     *
     * @param children the children of the task when it is a bracket root, in
     *                 any state, where the call reads them; ignored otherwise
     * @param beside   what was read beside the row: the texts and the tasks under it
     */
    public static TaskSituation of(Task task, Instant now, List<Task> children, Beside beside) {
        TaskState stored = task.storedState();
        boolean lapsed = stored == TaskState.ACTIVE && !leaseRuns(task, now);

        TaskState state = stored;
        @Nullable HoldReason reason = task.storedHoldReason().orElse(null);
        @Nullable String holder = task.storedHolder().orElse(null);
        if (lapsed) {
            boolean parks = task.lapseCount() + 1 >= LAPSES_TO_PARK;
            state = parks ? TaskState.ON_HOLD : TaskState.OPEN;
            reason = parks ? HoldReason.EXTERNAL : null;
            holder = null;
        }

        List<Child> unfinished = task.isBracketRoot()
            ? children.stream()
                .map(c -> new Child(c.uuid, c.address(),
                    of(c, now, List.of(), Beside.NOTHING).state()))
                .filter(c -> c.state() != TaskState.CLOSED)
                .toList()
            : List.of();

        return new TaskSituation(task.uuid, task.address(), state, reason,
            task.outcome().orElse(null), holder, task.storedHolder().orElse(null), lapsed,
            task.receiptHash().orElse(null), task.conflictToken(), task.notBefore().orElse(null),
            task.questionOptions().orElse(null), task.isBracketRoot(), unfinished,
            takenUp(task, beside), task.isBracketRoot() && beside.childrenPresent(), now);
    }

    /**
     * Whether an executor ever took the task up: the first take-up froze its
     * content, and from then on it is never recalled (DEC-0014, REQ-0035).
     *
     * <p>Where the kernel recorded the instant of the first take-up (V23), that
     * instant answers. A task without it was sent before the instant was
     * recorded, or taken up by an image that did not record it, and counts as
     * taken up where the store shows a trace of a take-up: a lapse counted; a
     * text of the work beside the commission (a return, a question, an answer,
     * a remark); a deferral still standing, which only a holder sets; or a
     * stored state only a take-up reaches. A task taken up and given back
     * without any of these traces -- released without a remark under such an
     * image -- leaves none, and counts as never taken up.
     */
    static boolean takenUp(Task task, Beside beside) {
        TaskState stored = task.storedState();
        return task.firstTakenAt().isPresent()
            || task.lapseCount() > 0
            || beside.workTexts()
            || task.notBefore().isPresent()
            || stored == TaskState.ACTIVE || stored == TaskState.ON_HOLD
            || stored == TaskState.DELIVERED;
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
        return task.leaseExpiresAt().filter(end -> end.isAfter(now)).isPresent();
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
