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
 * The control a task in draft will carry when it is sent: a row of {@code
 * dispatch.draft} of the store line, one per task (data model A.7).
 *
 * <p>The only changeable place of the store besides the projections. {@code
 * create} writes it, {@code update} changes it, and {@code delete} and {@code
 * send} remove it; none of them writes an act. Its {@code changed_at} is the
 * conflict token of the task while it is a draft (D29): written at every create
 * and update as the later of the clock and the previous value plus one
 * microsecond, under the lock of this row. The update names the changed
 * columns alone, since the runtime role holds {@code UPDATE} on those only.
 */
@Entity
@DynamicUpdate
@Table(name = "draft", schema = "dispatch")
public class Draft extends RuntimeRow {

    @Column(name = "task_id", nullable = false, updatable = false)
    private Long taskId;

    @Column(name = "title")
    private String title;

    /** Absent where the draft names no activity yet. */
    @Column(name = "activity_id")
    private @Nullable Long activityId;

    /** Absent where the draft concerns no other task. */
    @Column(name = "concerns_task_id")
    private @Nullable Long concernsTaskId;

    /** Absent where the draft is addressed to no role (D16). */
    @Column(name = "addressed_role")
    private @Nullable String addressedRole;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    /** For the persistence provider only. */
    protected Draft() {
        // The provider fills every column from the row it reads.
    }

    Draft(UUID scopeId, long taskId, Control control, Instant changedAt) {
        super(scopeId);
        this.taskId = taskId;
        this.changedAt = changedAt;
        this.title = control.title();
        this.activityId = control.activityId();
        this.concernsTaskId = control.concernsTaskId();
        this.addressedRole = control.addressedRole();
    }

    /**
     * The control of a draft, as a call hands it in and the draft holds it.
     *
     * @param title          the task's title
     * @param activityId     its activity; absent where none is named
     * @param concernsTaskId the task of the same instance it concerns; absent
     *                       where it concerns none
     * @param addressedRole  the role it is addressed to; absent where it names none
     */
    record Control(String title, @Nullable Long activityId, @Nullable Long concernsTaskId,
                   @Nullable String addressedRole) {
    }

    /** The control the draft holds now. */
    Control control() {
        return new Control(title, activityId, concernsTaskId, addressedRole);
    }

    /** Takes the control given in place of the one it held. */
    void take(Control control) {
        this.title = control.title();
        this.activityId = control.activityId();
        this.concernsTaskId = control.concernsTaskId();
        this.addressedRole = control.addressedRole();
    }

    /** Moves the conflict token on to the instant given. */
    void changedAt(Instant at) {
        this.changedAt = at;
    }

    public long taskId() {
        return taskId;
    }

    public String title() {
        return title;
    }

    /** The activity the draft names; empty where it names none. */
    public Optional<Long> activityId() {
        return Optional.ofNullable(activityId);
    }

    /** The task it concerns; empty where it concerns none. */
    public Optional<Long> concernsTaskId() {
        return Optional.ofNullable(concernsTaskId);
    }

    /** The role it is addressed to; empty where it names none. */
    public Optional<String> addressedRole() {
        return Optional.ofNullable(addressedRole);
    }

    /** The conflict token of the task while it is a draft. */
    public Instant changedAt() {
        return changedAt;
    }
}
