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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * One idempotency key a caller has spent on a task call.
 *
 * <p>The key, the call it was spent on, a digest of that call's arguments and
 * the task it answered with. Remembered for {@link #REMEMBERED_FOR}: the key
 * outlives a draft it created, which is deleted, so that a repeat after the
 * deletion is refused rather than carried out a second time (C10.4).
 */
@Entity
@Table(name = "task_idempotency_key", schema = "dispatch")
public class SpentTaskKey {

    /** How long a spent key speaks for its call: twenty-four hours. */
    public static final java.time.Duration REMEMBERED_FOR = java.time.Duration.ofHours(24);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    public Long id;

    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "scope_id", nullable = false)
    public UUID scopeId;

    /** The task the first call answered with; absent once that task, a draft, was deleted. */
    @Column(name = "task_id")
    public @Nullable Long taskId;

    @Column(name = "caller_subject", nullable = false)
    public String callerSubject;

    @Column(name = "idempotency_key", nullable = false)
    public String key;

    @Column(name = "call_name", nullable = false)
    public String callName;

    @Column(name = "argument_digest", nullable = false)
    public String argumentDigest;

    @Column(name = "first_seen_at", nullable = false)
    public Instant firstSeenAt;

    /** Whether this row still speaks for the key at {@code now}. */
    public boolean stillStandsAt(Instant now) {
        return firstSeenAt != null && firstSeenAt.isAfter(now.minus(REMEMBERED_FOR));
    }

    /** Whether this row records the same call the caller is now making. */
    public boolean records(String call, String digest) {
        return callName.equals(call) && argumentDigest.equals(digest);
    }
}
