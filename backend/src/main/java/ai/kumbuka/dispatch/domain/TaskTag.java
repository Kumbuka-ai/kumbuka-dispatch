/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.StringUuidConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One tag a task carries: the relation between a task and a tag of the
 * platform's vocabulary (V24).
 *
 * <p>A relation, never a field of the task: writing one leaves the task row,
 * its conflict token and its change stamps as they stand. The tag itself is
 * the platform's and is read through {@code platform.tag_access}; this row
 * holds its id and nothing of its token.
 *
 * <p>Mapped so that a listing narrowed by a tag stays one statement; the
 * relation is written by the repository's insert, which answers a tag already
 * attached as nothing done.
 */
@Entity
@Table(name = "task_tag", schema = "dispatch")
@IdClass(TaskTag.Key.class)
public class TaskTag {

    @Id
    @Column(name = "task_id", nullable = false, updatable = false)
    public Long taskId;

    @Id
    @Column(name = "tag_id", nullable = false, updatable = false)
    public UUID tagId;

    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    /** The subject that attached the tag: who did it, never how often. */
    @Column(name = "attached_by", updatable = false)
    public @Nullable String attachedBy;

    @Column(name = "attached_at", nullable = false, insertable = false, updatable = false)
    public Instant attachedAt;

    /** The key of the relation: the task and the tag. */
    public static final class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        public @Nullable Long taskId;
        public @Nullable UUID tagId;

        public Key() {
            // The persistence provider builds the key reflectively.
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                && Objects.equals(taskId, key.taskId) && Objects.equals(tagId, key.tagId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(taskId, tagId);
        }
    }
}
