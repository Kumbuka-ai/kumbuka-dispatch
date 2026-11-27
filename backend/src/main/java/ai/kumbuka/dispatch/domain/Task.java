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
import java.util.Optional;
import java.util.UUID;

/**
 * A task of an instance: a row of {@code dispatch.task} of the store line.
 *
 * <p>Written by {@code create} in the state {@code draft}, with its place in
 * the instance and nothing of its projection: what a draft will carry stands
 * in the draft workspace until it is sent (data model A.7). {@code send} sets
 * the projection -- its title, its activity, the task it concerns and the role
 * it is addressed to (D16) -- from the commission it writes, and moves the
 * task to {@code open}. A draft is deleted outright (DEC-0015).
 *
 * <p>Read besides to tell the unfinished tasks of an instance -- every task
 * whose state is not {@code closed} -- and changed by the {@code withdraw} that
 * closing an instance with unfinished tasks writes for each of them (TAR-0004
 * 2.3). The projection columns that withdrawing empties go with it, so that
 * the closed task meets the checks of its table: a closed task has no hold
 * reason, no deferral and is not parked. The update names the changed columns
 * alone, since the runtime role holds {@code UPDATE} on the projection only.
 */
@Entity
@DynamicUpdate
@Table(name = "task", schema = "dispatch")
public class Task extends RuntimeRow {

    /** The state of a finished task, as the store writes it. */
    static final String CLOSED = "closed";

    /** The state of a task before it is sent, as the store writes it. */
    static final String DRAFT = "draft";

    /** The state of a task sent and not yet taken up, as the store writes it. */
    static final String OPEN = "open";

    @Column(name = "instance_id", nullable = false, updatable = false)
    private Long instanceId;

    @Column(name = "sub", nullable = false, updatable = false)
    private int sub;

    /** Absent until the task is sent. */
    @Column(name = "title")
    private @Nullable String title;

    /** Absent until the task is sent. */
    @Column(name = "activity_id")
    private @Nullable Long activityId;

    /** Absent where the task concerns no other task. */
    @Column(name = "concerns_task_id")
    private @Nullable Long concernsTaskId;

    /** Absent where the task is addressed to no role (D16). */
    @Column(name = "addressed_role")
    private @Nullable String addressedRole;

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

    private Task(UUID scopeId, long instanceId, int sub) {
        super(scopeId);
        this.instanceId = instanceId;
        this.sub = sub;
        this.state = DRAFT;
    }

    /** A task in draft, at the place its instance's counter handed out. */
    static Task drafted(UUID scopeId, long instanceId, int sub) {
        return new Task(scopeId, instanceId, sub);
    }

    /** The instance the task belongs to, by its surrogate. */
    public long instanceId() {
        return instanceId;
    }

    /** The place of the task in its instance: the number after the instance's. */
    public int sub() {
        return sub;
    }

    /** The state as the store wrote it. */
    public String state() {
        return state;
    }

    /** Whether the task is a draft, the one state its draft workspace is changed in. */
    public boolean draft() {
        return DRAFT.equals(state);
    }

    /** Whether the task is unfinished: in any state but closed. */
    public boolean unfinished() {
        return !CLOSED.equals(state);
    }

    /** How a closed task was closed, as the store wrote it; empty while it is not closed. */
    public Optional<String> resolution() {
        return Optional.ofNullable(resolution);
    }

    /** The title its commission gave it; empty until it is sent. */
    public Optional<String> title() {
        return Optional.ofNullable(title);
    }

    /** The role its commission addresses; empty until it is sent, or where it names none. */
    public Optional<String> addressedRole() {
        return Optional.ofNullable(addressedRole);
    }

    /**
     * Sends the task: its projection takes what the commission froze, and it
     * opens for an executor to take it up.
     */
    void send(Commission commission) {
        this.title = commission.title();
        this.activityId = commission.activityId();
        this.concernsTaskId = commission.concernsTaskId().orElse(null);
        this.addressedRole = commission.addressedRole().orElse(null);
        this.state = OPEN;
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
