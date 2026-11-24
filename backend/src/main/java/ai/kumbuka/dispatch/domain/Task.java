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

import java.time.Instant;

/**
 * A task of an instance: a row of {@code dispatch.task} of the store line,
 * mapped with what closing its instance needs.
 *
 * <p>Read to tell the unfinished tasks of an instance -- every task whose
 * state is not {@code closed} -- and changed by one act alone in this change:
 * the {@code withdraw} that closing an instance with unfinished tasks writes
 * for each of them (TAR-0004 2.3). The projection columns that withdrawing
 * empties go with it, so that the closed task meets the checks of its table:
 * a closed task has no hold reason, no deferral and is not parked. The update
 * names the changed columns alone.
 */
@Entity
@DynamicUpdate
@Table(name = "task", schema = "dispatch")
public class Task extends RuntimeRow {

    /** The state of a finished task, as the store writes it. */
    static final String CLOSED = "closed";

    @Column(name = "instance_id", nullable = false, updatable = false)
    private Long instanceId;

    @Column(name = "sub", nullable = false, updatable = false)
    private int sub;

    @Column(name = "state", nullable = false)
    private String state;

    /** Absent but on a task on hold. */
    @Column(name = "hold_reason")
    private @Nullable String holdReason;

    /** Absent but on a closed task. */
    @Column(name = "resolution")
    private @Nullable String resolution;

    /** Absent but on an open task that was deferred. */
    @Column(name = "not_before")
    private @Nullable Instant notBefore;

    @Column(name = "parked", nullable = false)
    private boolean parked;

    /** For the persistence provider only. */
    protected Task() {
        // The provider fills every column from the row it reads.
    }

    /** The place of the task in its instance: the number after the instance's. */
    public int sub() {
        return sub;
    }

    /** The state as the store wrote it. */
    public String state() {
        return state;
    }

    /** Whether the task is unfinished: in any state but closed. */
    public boolean unfinished() {
        return !CLOSED.equals(state);
    }

    /** Closes the task as withdrawn, as the act that withdraws it records. */
    void withdraw() {
        this.state = CLOSED;
        this.resolution = "withdrawn";
        this.holdReason = null;
        this.notBefore = null;
        this.parked = false;
    }
}
