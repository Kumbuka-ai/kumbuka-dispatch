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

/**
 * The state of a task as a reader is told it at an instant: its stored
 * projection with the lapses of its holdings laid over it (ADR-0002, C11.2,
 * data model A.5 "Lapses" and "Holding", TAR-0004 2.5).
 *
 * <p>A pure function of the stored projection, the task's holdings and the
 * instant of the check. A lapse is derived, never written: no reading and no
 * check writes one back, and the stored state stays {@code active} until an
 * act writes another.
 *
 * <ul>
 *   <li>A lease runs only while the task is stored {@code active}. The youngest
 *       holding no act ended has lapsed where the task is stored {@code active}
 *       and the end of that holding's youngest lease is not after the instant
 *       of the check: an end exactly at that instant has lapsed, and so has a
 *       holding without any lease. Every older holding no act ended has lapsed,
 *       since a later claim presupposes its lapse.</li>
 *   <li>The number of lapses is the number of lapsed holdings.</li>
 *   <li>Stored {@code active} with its youngest holding lapsed is, up to
 *       {@link #PARKING_LAPSE} - 1 lapses, {@code open} without a holder, and from
 *       the {@link #PARKING_LAPSE}th lapse on {@code on_hold} for {@code external},
 *       parked, without a holder. Every other stored state is itself, a stored
 *       parked hold included.</li>
 * </ul>
 *
 * @param state      the state a reader is told
 * @param holdReason why the task is on hold; absent where it is not
 * @param parked     whether it is parked
 * @param lapses     how many of its holdings lapsed
 * @param youngest   what became of its youngest holding no act ended
 */
public record EffectiveState(String state, @Nullable String holdReason, boolean parked,
                             int lapses, Youngest youngest) {

    /** The lapse that parks a task: the third (REA-0010, parameters of the wave). */
    public static final int PARKING_LAPSE = 3;

    /** The hold reason of a parked task. */
    static final String EXTERNAL = "external";

    /** What became of the youngest holding no act ended. */
    public enum Youngest {
        /** It holds: the task is stored active and its lease runs. */
        RUNNING,
        /** It lapsed. */
        LAPSED,
        /** No such holding stands, or one stands on a task not stored active, where no lease runs. */
        NONE
    }

    /**
     * One holding of a task, as the derivation reads it.
     *
     * @param ended    whether an act ended it
     * @param leaseEnd the end of its youngest lease; absent where it has none
     */
    public record Line(boolean ended, @Nullable Instant leaseEnd) {
    }

    /**
     * The state of a task at an instant.
     *
     * @param stored     the state as the store holds it
     * @param holdReason the stored hold reason; absent but on a task stored on hold
     * @param parked     whether the store holds the task parked
     * @param holdings   the task's holdings, oldest first
     * @param at         the instant of the check
     */
    public static EffectiveState of(String stored, @Nullable String holdReason, boolean parked,
                                    List<Line> holdings, Instant at) {
        List<Line> open = holdings.stream().filter(line -> !line.ended()).toList();
        if (open.isEmpty()) {
            return new EffectiveState(stored, holdReason, parked, 0, Youngest.NONE);
        }
        int older = open.size() - 1;
        Line youngest = open.get(older);
        boolean active = Task.ACTIVE.equals(stored);
        if (!active) {
            return new EffectiveState(stored, holdReason, parked, older, Youngest.NONE);
        }
        Instant leaseEnd = youngest.leaseEnd();
        boolean lapsed = leaseEnd == null || !leaseEnd.isAfter(at);
        if (!lapsed) {
            return new EffectiveState(stored, holdReason, parked, older, Youngest.RUNNING);
        }
        int lapses = older + 1;
        return lapses >= PARKING_LAPSE
            ? new EffectiveState(Task.ON_HOLD, EXTERNAL, true, lapses, Youngest.LAPSED)
            : new EffectiveState(Task.OPEN, null, false, lapses, Youngest.LAPSED);
    }

    /** Whether a holder holds the task now: its youngest holding runs. */
    public boolean held() {
        return youngest == Youngest.RUNNING;
    }
}
