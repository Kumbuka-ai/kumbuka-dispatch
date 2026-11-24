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
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * The commission a {@code send} gives a task: a row of {@code
 * dispatch.commission} of the store line, anchored at the send act.
 *
 * <p>Written once, when the task is sent (DEC-0014), from what its draft
 * holds, and never changed: the runtime role holds no {@code UPDATE} on the
 * table, and the mapping says so. The role it is addressed to is frozen here
 * (D16). What the draft did not carry, the commission does not carry either.
 */
@Entity
@Immutable
@Table(name = "commission", schema = "dispatch")
public class Commission extends RuntimeRow {

    @Column(name = "act_id", nullable = false, updatable = false)
    private Long actId;

    @Column(name = "instance_id", nullable = false, updatable = false)
    private Long instanceId;

    @Column(name = "title", nullable = false, updatable = false)
    private String title;

    @Column(name = "activity_id", nullable = false, updatable = false)
    private Long activityId;

    /** Absent where the task concerns no other task. */
    @Column(name = "concerns_task_id", updatable = false)
    private @Nullable Long concernsTaskId;

    /** Absent where the task is addressed to no role. */
    @Column(name = "addressed_role", updatable = false)
    private @Nullable String addressedRole;

    /** For the persistence provider only. */
    protected Commission() {
        // The provider fills every column from the row it reads.
    }

    Commission(UUID scopeId, long actId, long instanceId, Draft draft, long activityId) {
        super(scopeId);
        this.actId = actId;
        this.instanceId = instanceId;
        this.title = draft.title();
        this.activityId = activityId;
        this.concernsTaskId = draft.concernsTaskId().orElse(null);
        this.addressedRole = draft.addressedRole().orElse(null);
    }

    public String title() {
        return title;
    }

    public long activityId() {
        return activityId;
    }

    /** The task it concerns; empty where it concerns none. */
    public Optional<Long> concernsTaskId() {
        return Optional.ofNullable(concernsTaskId);
    }

    /** The role it is addressed to; empty where it names none. */
    public Optional<String> addressedRole() {
        return Optional.ofNullable(addressedRole);
    }
}
