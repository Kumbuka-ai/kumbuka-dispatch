/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * A holding of a task: a row of {@code dispatch.holding} of the store line,
 * from the act {@code claim} that opened it to the act that ended it (data
 * model A.5 "Holding").
 *
 * <p>Written by {@code claim} with its act; {@code release} and {@code defer}
 * end it by naming their act in {@code ended_by_act_id}, the one column the
 * runtime role may change, and the update names it alone. A lapse is never
 * written: a lapsed holding keeps its empty {@code ended_by_act_id}, and the
 * next {@code claim} opens a holding beside it (ADR-0002, C11.2).
 */
@Entity
@DynamicUpdate
@Table(name = "holding", schema = "dispatch")
public class Holding extends RuntimeRow {

    @Column(name = "task_id", nullable = false, updatable = false)
    private Long taskId;

    @Column(name = "claim_act_id", nullable = false, updatable = false)
    private Long claimActId;

    /** Absent while the holding is open: no act ended it, or it lapsed. */
    @Column(name = "ended_by_act_id")
    private @Nullable Long endedByActId;

    /** For the persistence provider only. */
    protected Holding() {
        // The provider fills every column from the row it reads.
    }

    Holding(UUID scopeId, long taskId, long claimActId) {
        super(scopeId);
        this.taskId = taskId;
        this.claimActId = claimActId;
    }

    public long taskId() {
        return taskId;
    }

    /** The act {@code claim} that opened the holding; its actor is the holder (D17). */
    public long claimActId() {
        return claimActId;
    }

    /** Whether no act ended the holding: it runs, or it lapsed. */
    public boolean open() {
        return endedByActId == null;
    }

    /** Ends the holding by the act that gives the task back. */
    void end(long actId) {
        this.endedByActId = actId;
    }
}
